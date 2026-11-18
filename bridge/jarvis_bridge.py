"""
Jarvis bridge: runs on Jorge's PC and connects the Fire tablet to Claude.

The tablet POSTs recorded audio to /ask. The bridge transcribes it locally
with Whisper, asks Claude through the Claude Code CLI (which uses the Pro
subscription you are signed in with, not the paid API) and returns the reply
as JSON for the tablet to speak.

Endpoints (all but /health need the X-Jarvis-Code header):
  GET  /health          -> {"ok": true, "app": "jarvis"}
  POST /ask   (audio)   -> {"heard": "...", "reply": "..."}
  POST /chat  (json)    -> {"reply": "..."}       body: {"text": "..."}
  POST /image (jpeg)    -> {"id": "..."}          then send X-Jarvis-Image: id with /ask or /chat
  POST /reset           -> {"ok": true}           starts a new conversation
"""

import argparse
import json
import os
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HERE = Path(__file__).resolve().parent
HOME = Path.home() / ".jarvis"
WORKSPACE = HOME / "workspace"
CONFIG_FILE = HOME / "config.json"
IMAGES = WORKSPACE / "fotos"
MAX_IMAGES = 30

# A conversation is continued while you keep talking; after this many
# seconds of silence, the next question starts a fresh one.
CONVERSATION_TIMEOUT = 15 * 60
MAX_AUDIO_BYTES = 20 * 1024 * 1024
# Editing a project can take a while; the tablet waits a bit longer than this.
CLAUDE_TIMEOUT = 300
DEFAULT_PORT = 8737


class BridgeServer(ThreadingHTTPServer):
    # http.server turns on SO_REUSEADDR, which on Windows lets two programs
    # share a port silently (the other one may answer the tablet). Refuse
    # instead, and on Windows ask for the port exclusively.
    allow_reuse_address = False
    daemon_threads = True

    def server_bind(self):
        if hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
            self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        super().server_bind()


def log(*args):
    print(time.strftime("[%H:%M:%S]"), *args, flush=True)


def load_config():
    HOME.mkdir(parents=True, exist_ok=True)
    if CONFIG_FILE.exists():
        cfg = json.loads(CONFIG_FILE.read_text(encoding="utf-8"))
    else:
        cfg = {}
    if not cfg.get("code"):
        cfg["code"] = f"{secrets.randbelow(10**6):06d}"
        CONFIG_FILE.write_text(json.dumps(cfg, indent=2), encoding="utf-8")
    return cfg


def image_path(image_id):
    """Map an id from /image back to its file, refusing anything else."""
    if not image_id or not all(c.isalnum() or c == "-" for c in image_id):
        return None
    path = IMAGES / f"{image_id}.jpg"
    return str(path) if path.is_file() else None


def find_claude():
    """Claude Code's Windows installer does not always add itself to PATH."""
    found = shutil.which("claude")
    if found:
        return found
    for candidate in (
        Path.home() / ".local" / "bin" / "claude.exe",
        Path.home() / ".local" / "bin" / "claude",
        Path(os.environ.get("APPDATA", "")) / "npm" / "claude.cmd",
    ):
        if candidate.is_file():
            return str(candidate)
    return None


def lan_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))  # no packet is sent
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def decode_audio(path, rate=16000):
    """Decode any audio file to 16 kHz mono float32 for Whisper.

    faster-whisper's own decoder passes an option that newer PyAV releases
    removed ("metadata_errors"), so we decode with PyAV ourselves.
    """
    import av
    import numpy as np

    resampler = av.AudioResampler(format="s16", layout="mono", rate=rate)
    chunks = []
    with av.open(str(path)) as container:
        for frame in container.decode(audio=0):
            out = resampler.resample(frame)
            for f in out if isinstance(out, list) else [out]:
                chunks.append(f.to_ndarray().reshape(-1))
        out = resampler.resample(None)  # flush what is left in the resampler
        for f in out if isinstance(out, list) else ([out] if out is not None else []):
            chunks.append(f.to_ndarray().reshape(-1))
    if not chunks:
        return np.zeros(0, dtype=np.float32)
    return np.concatenate(chunks).astype(np.float32) / 32768.0


class Transcriber:
    """Local speech-to-text with faster-whisper. The model loads on first use."""

    def __init__(self, model_name, device):
        self.model_name = model_name
        self.device = device
        self._model = None
        self._lock = threading.Lock()

    def load(self):
        with self._lock:
            if self._model is None:
                from faster_whisper import WhisperModel

                log(f"Cargando Whisper '{self.model_name}' (la primera vez se descarga)...")
                compute = "int8" if self.device == "cpu" else "float16"
                self._model = WhisperModel(self.model_name, device=self.device, compute_type=compute)
                log("Whisper listo.")
        return self._model

    def transcribe(self, audio_path):
        model = self.load()
        audio = decode_audio(audio_path)
        if audio.size < 1600:  # less than 0.1 s
            return ""
        segments, _ = model.transcribe(
            audio, language="es", vad_filter=True, beam_size=5
        )
        return " ".join(seg.text.strip() for seg in segments).strip()


MODELS = ("haiku", "sonnet", "opus")
READ_TOOLS = "WebSearch,Read,Glob,Grep"
EDIT_TOOLS = "WebSearch,Read,Glob,Grep,Edit,Write"
# Commands blocked even when the terminal is on: easy to trigger by a
# misheard sentence and hard to undo.
BLOCKED_COMMANDS = [
    "Bash(rm -rf:*)", "Bash(rm -r:*)", "Bash(rm -fr:*)",
    "Bash(rmdir /s:*)", "Bash(rd /s:*)", "Bash(del /s:*)",
    "Bash(Remove-Item:*)", "Bash(format:*)", "Bash(diskpart:*)",
    "Bash(shutdown:*)", "Bash(git push:*)", "Bash(git reset --hard:*)",
    "Bash(git clean:*)",
]


def _existing_dirs(paths):
    return [str(Path(p).expanduser()) for p in paths if Path(p).expanduser().is_dir()]


class Claude:
    """Talks to Claude through the Claude Code CLI in print mode.

    Folder access (from ~/.jarvis/config.json):
      folders       read-only project folders
      edit_folders  folders Jarvis may also edit, but only in edit mode
      projects_dir  where Jarvis creates new projects, only in edit mode
    Edit mode is switched on from the tablet for each question.
    """

    def __init__(self, claude_cmd, model, folders=(), edit_folders=(), projects_dir=None):
        self.cmd = claude_cmd
        self.model = model
        self.folders = _existing_dirs(folders)
        self.edit_folders = _existing_dirs(edit_folders)
        self.projects_dir = str(Path(projects_dir).expanduser()) if projects_dir else None
        if self.projects_dir:
            Path(self.projects_dir).mkdir(parents=True, exist_ok=True)
        self.session_id = None
        self.last_used = 0.0
        self._lock = threading.Lock()
        WORKSPACE.mkdir(parents=True, exist_ok=True)
        # Claude Code reads CLAUDE.md from its working folder: that is where
        # Jarvis's personality and the list of folders live.
        (WORKSPACE / "CLAUDE.md").write_text(self._instructions(), encoding="utf-8")

    def _instructions(self):
        text = (HERE / "persona.md").read_text(encoding="utf-8").rstrip() + "\n\n## Carpetas de Jorge\n\n"
        if self.folders:
            text += "Solo lectura (nunca se pueden modificar):\n" + "".join(f"- {f}\n" for f in self.folders)
        if self.edit_folders:
            text += "Editables en modo edición:\n" + "".join(f"- {f}\n" for f in self.edit_folders)
        if self.projects_dir:
            text += f"Proyectos nuevos: crea una subcarpeta con un nombre corto dentro de {self.projects_dir}\n"
        text += (
            "\nEl modo edición lo activa Jorge desde la tableta. Si no tienes las herramientas Edit y Write, "
            "no está activo: díselo en una frase. En modo edición, las carpetas de solo lectura no están disponibles.\n"
            "La terminal (Bash) solo está disponible si Jorge la activa además del modo edición. "
            "Trabaja siempre dentro de las carpetas editables o de proyectos.\n"
        )
        return text

    def reset(self):
        with self._lock:
            self.session_id = None

    def ask(self, text, model=None, edit=False, shell=False, image=None):
        model = model if model in MODELS else self.model
        if image:
            text = (
                f"{text}\n\n[Jorge te muestra una foto tomada ahora con la cámara de la tableta: {image} . "
                "Mírala con la herramienta Read antes de responder.]"
            )
        with self._lock:
            if time.time() - self.last_used > CONVERSATION_TIMEOUT:
                self.session_id = None
            args = [
                self.cmd, "-p",
                "--output-format", "json",
                "--model", model,
            ]
            shell = shell and edit  # the terminal only exists inside edit mode
            if edit:
                dirs = self.edit_folders + ([self.projects_dir] if self.projects_dir else [])
                tools = EDIT_TOOLS + (",Bash" if shell else "")
            else:
                dirs = self.folders + self.edit_folders + ([self.projects_dir] if self.projects_dir else [])
                tools = READ_TOOLS if (dirs or image) else "WebSearch"
            for folder in dirs:
                args += ["--add-dir", folder]
            args += ["--tools", tools, "--allowedTools", tools]
            if shell:
                args += ["--disallowedTools", *BLOCKED_COMMANDS]
            if self.session_id:
                args += ["--resume", self.session_id]

            env = dict(os.environ)
            # Make sure Claude Code uses the signed-in Pro subscription and
            # never bills an API key that happens to be set on this PC.
            env.pop("ANTHROPIC_API_KEY", None)

            log(f"Modelo: {model}{' · MODO EDICIÓN' if edit else ''}{' + TERMINAL' if shell else ''}")
            started = time.time()
            proc = subprocess.run(
                args, input=text, capture_output=True, text=True,
                encoding="utf-8", errors="replace", cwd=WORKSPACE, env=env, timeout=CLAUDE_TIMEOUT,
            )
            log(f"Claude respondió en {time.time() - started:.1f}s")
            try:
                data = json.loads(proc.stdout)
            except json.JSONDecodeError:
                raise RuntimeError(
                    "Claude Code no respondió bien. ¿Iniciaste sesión con 'claude' en esta PC?\n"
                    + (proc.stderr or proc.stdout)[-800:]
                )
            if data.get("is_error"):
                raise RuntimeError(str(data.get("result") or data))
            self.session_id = data.get("session_id") or self.session_id
            self.last_used = time.time()
            return (data.get("result") or "").strip()


def make_handler(cfg, transcriber, claude):
    code = cfg["code"]

    class Handler(BaseHTTPRequestHandler):
        server_version = "JarvisBridge/0.1"

        def log_message(self, fmt, *args):
            pass  # we log our own lines

        def log_request(self, code="-", size="-"):
            log(f"Conexión de {self.client_address[0]}: {self.command} {self.path} -> {code}")

        def _json(self, status, payload):
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _authorized(self):
            if self.headers.get("X-Jarvis-Code", "") == code:
                return True
            self._json(401, {"error": "Código incorrecto. Revisa el código que muestra el PC."})
            return False

        def _options(self):
            return {
                "model": self.headers.get("X-Jarvis-Model", "").strip().lower() or None,
                "edit": self.headers.get("X-Jarvis-Edit", "") == "1",
                "shell": self.headers.get("X-Jarvis-Shell", "") == "1",
                "image": image_path(self.headers.get("X-Jarvis-Image", "")),
            }

        def _body(self):
            length = int(self.headers.get("Content-Length") or 0)
            if length > MAX_AUDIO_BYTES:
                raise ValueError("Audio demasiado largo")
            return self.rfile.read(length)

        def do_GET(self):
            if self.path == "/health":
                self._json(200, {"ok": True, "app": "jarvis"})
            else:
                self._json(404, {"error": "No existe"})

        def do_POST(self):
            if not self._authorized():
                return
            try:
                if self.path == "/ask":
                    self._ask()
                elif self.path == "/chat":
                    text = json.loads(self._body() or b"{}").get("text", "").strip()
                    if not text:
                        return self._json(400, {"error": "Falta el texto"})
                    log(f"Tú (texto): {text}")
                    reply = claude.ask(text, **self._options())
                    log(f"Jarvis: {reply}")
                    self._json(200, {"heard": text, "reply": reply})
                elif self.path == "/image":
                    data = self._body()
                    if not data.startswith(b"\xff\xd8"):
                        return self._json(400, {"error": "La foto no llegó bien"})
                    IMAGES.mkdir(parents=True, exist_ok=True)
                    image_id = time.strftime("%Y%m%d-%H%M%S") + "-" + secrets.token_hex(3)
                    (IMAGES / f"{image_id}.jpg").write_bytes(data)
                    for old in sorted(IMAGES.glob("*.jpg"))[:-MAX_IMAGES]:
                        old.unlink(missing_ok=True)
                    log(f"Foto recibida ({len(data) // 1024} KB)")
                    self._json(200, {"id": image_id})
                elif self.path == "/reset":
                    claude.reset()
                    self._json(200, {"ok": True, "app": "jarvis"})
                else:
                    self._json(404, {"error": "No existe"})
            except subprocess.TimeoutExpired:
                self._json(504, {"error": "Claude tardó demasiado en responder."})
            except Exception as exc:  # report every failure to the tablet
                log("Error:", exc)
                self._json(500, {"error": str(exc)})

        def _ask(self):
            audio = self._body()
            if not audio:
                return self._json(400, {"error": "No llegó audio"})
            fd, path = tempfile.mkstemp(suffix=".m4a")
            try:
                with os.fdopen(fd, "wb") as f:
                    f.write(audio)
                heard = transcriber.transcribe(path)
            finally:
                os.unlink(path)
            log(f"Tú: {heard or '(nada)'}")
            if not heard:
                return self._json(200, {"heard": "", "reply": "No te escuché bien. ¿Me lo repites?"})
            reply = claude.ask(heard, **self._options())
            log(f"Jarvis: {reply}")
            self._json(200, {"heard": heard, "reply": reply})

    return Handler


def main():
    parser = argparse.ArgumentParser(description="Puente entre la tableta Jarvis y Claude")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--model", default="sonnet", choices=MODELS,
                        help="Modelo por defecto si la tableta no elige uno")
    parser.add_argument("--whisper", default="small", help="Modelo de Whisper: base, small, medium...")
    parser.add_argument("--device", default="cpu", help="cpu o cuda (si tienes tarjeta NVIDIA)")
    parser.add_argument("--claude", default=None, help="Ruta al comando claude si no está en el PATH")
    parser.add_argument("--add-folder", action="append", default=[], metavar="RUTA",
                        help="Deja que Jarvis lea esta carpeta de proyecto (se guarda para la próxima vez)")
    parser.add_argument("--add-folder-edit", action="append", default=[], metavar="RUTA",
                        help="Deja que Jarvis edite esta carpeta cuando el modo edición está activo")
    parser.add_argument("--remove-folder", action="append", default=[], metavar="RUTA",
                        help="Quita una carpeta de las listas")
    parser.add_argument("--projects-dir", default=None, metavar="RUTA",
                        help="Carpeta donde Jarvis crea proyectos nuevos (por defecto ~/JarvisProyectos)")
    args = parser.parse_args()

    claude_cmd = args.claude or find_claude()
    if not claude_cmd:
        sys.exit("No encontré Claude Code. Instálalo y ejecuta 'claude' una vez para iniciar sesión.")

    cfg = load_config()
    original = json.dumps(cfg, sort_keys=True)
    folders = cfg.setdefault("folders", [])
    edit_folders = cfg.setdefault("edit_folders", [])

    def resolve(f):
        path = str(Path(f).expanduser().resolve())
        if not Path(path).is_dir():
            sys.exit(f"No existe la carpeta: {path}")
        return path

    for f in args.add_folder:
        path = resolve(f)
        if path in edit_folders:
            edit_folders.remove(path)
        if path not in folders:
            folders.append(path)
    for f in args.add_folder_edit:
        path = resolve(f)
        if path in folders:
            folders.remove(path)
        if path not in edit_folders:
            edit_folders.append(path)
    removed = {str(Path(f).expanduser().resolve()) for f in args.remove_folder}
    cfg["folders"] = [f for f in folders if f not in removed]
    cfg["edit_folders"] = [f for f in edit_folders if f not in removed]
    if args.projects_dir:
        cfg["projects_dir"] = str(Path(args.projects_dir).expanduser().resolve())
    cfg.setdefault("projects_dir", str(Path.home() / "JarvisProyectos"))
    if json.dumps(cfg, sort_keys=True) != original:
        CONFIG_FILE.write_text(json.dumps(cfg, indent=2, ensure_ascii=False), encoding="utf-8")

    transcriber = Transcriber(args.whisper, args.device)
    claude = Claude(claude_cmd, args.model, cfg["folders"], cfg["edit_folders"], cfg["projects_dir"])
    try:
        server = BridgeServer(("0.0.0.0", args.port), make_handler(cfg, transcriber, claude))
    except OSError:
        sys.exit(
            f"El puerto {args.port} ya lo está usando otro programa de esta PC.\n"
            f"Prueba con otro, por ejemplo:  iniciar_jarvis.bat --port {args.port + 1}"
        )
    # Load Whisper in the background so the first question is not slow.
    threading.Thread(target=transcriber.load, daemon=True).start()

    print()
    print("=" * 52)
    print("  Jarvis está escuchando")
    print(f"  En la tableta escribe:  IP {lan_ip()}   puerto {args.port}   código {cfg['code']}")
    if claude.folders:
        print("  Solo lectura:")
        for f in claude.folders:
            print(f"    - {f}")
    if claude.edit_folders:
        print("  Editables en modo edición:")
        for f in claude.edit_folders:
            print(f"    - {f}")
    print(f"  Proyectos nuevos en: {claude.projects_dir}")
    print("  (Ctrl+C para apagar)")
    print("=" * 52)
    print(flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
