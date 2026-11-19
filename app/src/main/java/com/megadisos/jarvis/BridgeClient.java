package com.megadisos.jarvis;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Minimal HTTP client for the Jarvis bridge running on the PC. */
class BridgeClient {

    static final int PORT = 8737;

    static class Answer {
        final String heard;
        final String reply;
        Answer(String heard, String reply) { this.heard = heard; this.reply = reply; }
    }

    static class BridgeException extends Exception {
        BridgeException(String message) { super(message); }
    }

    /** No answer at all from that address (as opposed to an error from Jarvis). */
    static class Unreachable extends BridgeException {
        Unreachable(String message) { super(message); }
    }

    private final String baseUrl;
    private final String code;
    private String remoteUrl;    // Cloudflare link learned at home, or null
    private String remoteToken;  // long key the bridge requires from outside
    // Away from home the remote link is tried first, so each question doesn't wait for the LAN.
    private static volatile boolean lastWorkedRemote;
    private String[] extraHeaders = new String[0];

    BridgeClient(String host, String code) {
        String h = host.trim();
        if (!h.startsWith("http")) h = "http://" + h;
        if (!h.substring(h.indexOf("//") + 2).contains(":")) h = h + ":" + PORT;
        this.baseUrl = h;
        this.code = code.trim();
    }

    /** Lets the client fall back to the Cloudflare link when the PC is not on this wifi. */
    BridgeClient withRemote(String url, String token) {
        if (url != null && url.startsWith("https://") && token != null && !token.isEmpty()) {
            remoteUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
            remoteToken = token;
        }
        return this;
    }

    boolean isRemoteActive() {
        return remoteUrl != null && lastWorkedRemote;
    }

    /** Asks the PC (on the home wifi only) for its outside link and key: [url, token]. */
    String[] remoteInfo() throws BridgeException {
        JSONObject json = requestAt(baseUrl, false, "POST", "/remote-info", "application/json",
                "{}".getBytes(StandardCharsets.UTF_8), 8000, 4000);
        return new String[]{json.optString("url", ""), json.optString("token", "")};
    }

    void health() throws BridgeException {
        request("GET", "/health", null, null, 5000);
    }

    /** Checks the address and the code together. */
    void checkCode() throws BridgeException {
        JSONObject health;
        try {
            health = request("GET", "/health", null, null, 5000);
        } catch (BridgeException e) {
            if (e.getMessage().startsWith("No encuentro")) throw e;
            health = new JSONObject(); // something answered, but not Jarvis
        }
        if (!"jarvis".equals(health.optString("app"))) {
            throw new BridgeException("En esa IP y puerto responde otro programa, no Jarvis. "
                    + "Revisa la IP:puerto que muestra la ventana de Jarvis en el PC.");
        }
        request("POST", "/reset", "application/json", "{}".getBytes(StandardCharsets.UTF_8), 8000);
    }

    /** Per-question options sent as headers. */
    static class Options {
        String model = "sonnet";
        boolean edit, shell;
        String imageId;  // from uploadImage, or null
        String device;   // DeviceInfo.summary(), or null
    }

    String uploadImage(byte[] jpeg) throws BridgeException {
        return request("POST", "/image", "image/jpeg", jpeg, 30000).optString("id", null);
    }

    Answer chat(String text, Options o) throws BridgeException {
        setOptions(o);
        String body;
        try {
            body = new JSONObject().put("text", text).toString();
        } catch (Exception e) {
            throw new BridgeException("Texto inválido");
        }
        JSONObject json = request("POST", "/chat", "application/json",
                body.getBytes(StandardCharsets.UTF_8), 330000);
        return new Answer(json.optString("heard"), json.optString("reply"));
    }

    private void setOptions(Options o) {
        extraHeaders = new String[]{
                "X-Jarvis-Model", o.model,
                "X-Jarvis-Edit", o.edit ? "1" : "0",
                "X-Jarvis-Shell", o.shell ? "1" : "0",
                "X-Jarvis-Image", o.imageId == null ? "" : o.imageId,
                "X-Jarvis-Device", encode(o.device)};
    }

    /** Headers must be plain ASCII, so the Spanish text goes URL-encoded. */
    private static String encode(String s) {
        if (s == null) return "";
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return "";
        }
    }

    Answer ask(File audio, Options o) throws BridgeException {
        byte[] bytes;
        try (InputStream in = new FileInputStream(audio)) {
            bytes = readAll(in);
        } catch (IOException e) {
            throw new BridgeException("No pude leer la grabación");
        }
        setOptions(o);
        // Edits can take the PC a few minutes; the bridge gives up at 300 s.
        JSONObject json = request("POST", "/ask", "audio/mp4", bytes, 330000);
        return new Answer(json.optString("heard"), json.optString("reply"));
    }

    /** Home wifi first, then the Cloudflare link (or the other way round when away). */
    private JSONObject request(String method, String path, String type, byte[] body, int readTimeout)
            throws BridgeException {
        if (remoteUrl == null) return requestAt(baseUrl, false, method, path, type, body, readTimeout, 5000);
        boolean remoteFirst = lastWorkedRemote;
        try {
            JSONObject json = requestAt(remoteFirst ? remoteUrl : baseUrl, remoteFirst,
                    method, path, type, body, readTimeout, remoteFirst ? 8000 : 3000);
            lastWorkedRemote = remoteFirst;
            return json;
        } catch (Unreachable first) {
            try {
                JSONObject json = requestAt(remoteFirst ? baseUrl : remoteUrl, !remoteFirst,
                        method, path, type, body, readTimeout, remoteFirst ? 3000 : 8000);
                lastWorkedRemote = !remoteFirst;
                return json;
            } catch (Unreachable second) {
                throw new BridgeException("No encuentro el PC ni en casa ni por internet. "
                        + "¿Está encendido Jarvis en el PC? Si lo reiniciaste, conéctate una vez al wifi de casa "
                        + "para que aprenda el link nuevo.");
            }
        }
    }

    private JSONObject requestAt(String base, boolean remote, String method, String path, String type,
                                 byte[] body, int readTimeout, int connectTimeout) throws BridgeException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(base + path).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(connectTimeout);
            conn.setReadTimeout(readTimeout);
            if (remote) conn.setRequestProperty("X-Jarvis-Token", remoteToken);
            else conn.setRequestProperty("X-Jarvis-Code", code);
            for (int i = 0; i + 1 < extraHeaders.length; i += 2) {
                conn.setRequestProperty(extraHeaders[i], extraHeaders[i + 1]);
            }
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", type);
                conn.setFixedLengthStreamingMode(body.length);
            }
            try {
                conn.connect();
            } catch (IOException e) {
                throw new Unreachable("No encuentro el PC. ¿Está encendido Jarvis en el PC y en el mismo wifi? ("
                        + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
            }
            if (body != null) {
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(body);
                }
            }
            int status = conn.getResponseCode();
            InputStream in = status < 400 ? conn.getInputStream() : conn.getErrorStream();
            String text = in == null ? "" : new String(readAll(in), StandardCharsets.UTF_8);
            JSONObject json;
            try {
                json = text.isEmpty() ? new JSONObject() : new JSONObject(text);
            } catch (org.json.JSONException e) {
                // Cloudflare answers by itself when the PC's tunnel is gone.
                if (remote && status == 524) {
                    throw new BridgeException("Por internet solo se puede esperar 100 segundos y Jarvis tardó más. "
                            + "Sigue trabajando en el PC: pregúntale en un rato cómo le fue.");
                }
                if (remote && status >= 500) throw new Unreachable("El link de internet ya no funciona");
                // Not our bridge answering (another program, a router, a filter).
                String snippet = text.replaceAll("\\s+", " ").trim();
                if (snippet.length() > 120) snippet = snippet.substring(0, 120);
                throw new BridgeException("Respondió algo que no es Jarvis (error " + status + "): " + snippet);
            }
            if (status >= 400) {
                throw new BridgeException(json.optString("error", "El PC respondió con error") + " (" + status + ")");
            }
            return json;
        } catch (BridgeException e) {
            throw e;
        } catch (java.net.SocketTimeoutException e) {
            throw new BridgeException("El PC tardó demasiado en responder");
        } catch (IOException e) {
            throw new BridgeException("No encuentro el PC. ¿Está encendido Jarvis en el PC y en el mismo wifi? ("
                    + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
        } catch (Exception e) {
            throw new BridgeException("Respuesta extraña del PC (" + e + ")");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
