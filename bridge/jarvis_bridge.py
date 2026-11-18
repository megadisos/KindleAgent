"""
Jarvis bridge: runs on Jorge's PC and connects the Fire tablet to Claude.

The tablet POSTs recorded audio to /ask. The bridge transcribes it locally
with Whisper, asks Claude through the Claude Code CLI (which uses the Pro
subscription you are signed in with, not the paid API) and returns the reply
as JSON for the tablet to speak.

Endpoints (all but /health need the X-Jarvis-Code header):
  GET  /health          -> {"ok": true}
  POST /ask   (audio)   -> {"heard": "...", "reply": "..."}
  POST /chat  (json)    -> {"reply": "..."}       body: {"text": "..."}
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

# A conversation is continued while you keep talking; after this many
# seconds of silence, the next question starts a fresh one.
CONVERSATION_TIMEOUT = 15 * 60
MAX_AUDIO_BYTES = 20 * 1024 * 1024


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
        segments, _ = model.transcribe(
            str(audio_path), language="es", vad_filter=True, beam_size=5
        )
        return " ".join(seg.text.strip() for seg in segments).strip()


class Claude:
    """Talks to Claude through the Claude Code CLI in print mode."""

    def __init__(self, claude_cmd, model):
        self.cmd = claude_cmd
        self.model = model
        self.session_id = None
        self.last_used = 0.0
        self._lock = threading.Lock()
        WORKSPACE.mkdir(parents=True, exist_ok=True)
        # Claude Code reads CLAUDE.md from its working folder: that is where
        # Jarvis's personality lives.
        shutil.copyfile(HERE / "persona.md", WORKSPACE / "CLAUDE.md")

    def reset(self):
        with self._lock:
            self.session_id = None

    def ask(self, text):
        with self._lock:
            if time.time() - self.last_used > CONVERSATION_TIMEOUT:
                self.session_id = None
            args = [
                self.cmd, "-p",
                "--output-format", "json",
                "--model", self.model,
                "--tools", "WebSearch",
                "--allowedTools", "WebSearch",
            ]
            if self.session_id:
                args += ["--resume", self.session_id]

            env = dict(os.environ)
            # Make sure Claude Code uses the signed-in Pro subscription and
            # never bills an API key that happens to be set on this PC.
            env.pop("ANTHROPIC_API_KEY", None)

            started = time.time()
            proc = subprocess.run(
                args, input=text, capture_output=True, text=True,
                encoding="utf-8", errors="replace", cwd=WORKSPACE, env=env, timeout=180,
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

        def _body(self):
            length = int(self.headers.get("Content-Length") or 0)
            if length > MAX_AUDIO_BYTES:
                raise ValueError("Audio demasiado largo")
            return self.rfile.read(length)

        def do_GET(self):
            if self.path == "/health":
                self._json(200, {"ok": True})
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
                    reply = claude.ask(text)
                    log(f"Jarvis: {reply}")
                    self._json(200, {"heard": text, "reply": reply})
                elif self.path == "/reset":
                    claude.reset()
                    self._json(200, {"ok": True})
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
            reply = claude.ask(heard)
            log(f"Jarvis: {reply}")
            self._json(200, {"heard": heard, "reply": reply})

    return Handler


def main():
    parser = argparse.ArgumentParser(description="Puente entre la tableta Jarvis y Claude")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--model", default="sonnet", help="Modelo de Claude: sonnet, haiku u opus")
    parser.add_argument("--whisper", default="small", help="Modelo de Whisper: base, small, medium...")
    parser.add_argument("--device", default="cpu", help="cpu o cuda (si tienes tarjeta NVIDIA)")
    parser.add_argument("--claude", default=None, help="Ruta al comando claude si no está en el PATH")
    args = parser.parse_args()

    claude_cmd = args.claude or find_claude()
    if not claude_cmd:
        sys.exit("No encontré Claude Code. Instálalo y ejecuta 'claude' una vez para iniciar sesión.")

    cfg = load_config()
    transcriber = Transcriber(args.whisper, args.device)
    claude = Claude(claude_cmd, args.model)
    # Load Whisper in the background so the first question is not slow.
    threading.Thread(target=transcriber.load, daemon=True).start()

    server = ThreadingHTTPServer(("0.0.0.0", args.port), make_handler(cfg, transcriber, claude))
    print()
    print("=" * 52)
    print("  Jarvis está escuchando")
    print(f"  En la tableta escribe:  IP {lan_ip()}   código {cfg['code']}")
    print("  (Ctrl+C para apagar)")
    print("=" * 52)
    print()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
