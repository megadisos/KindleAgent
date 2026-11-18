package com.megadisos.jarvis;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen face. The face itself is HTML/JS in assets/face; this activity
 * hosts it in a WebView, records questions with the microphone, sends them to
 * the bridge on the PC and speaks the answer with Android's TextToSpeech.
 *
 * JS -> Android: window.Android.listen(), stopListening(), stop(), speak(text),
 *                getConfig(), saveConfig(host, code), savePrefs(json),
 *                previewVoice(text, pitch, rate), sendText(text), toggleCamera(), flipCamera(), setTyping(on)
 * Android -> JS: Face.setMode(mode), Face.setLevel(x), Face.setTalking(bool),
 *                Face.pulse(), Face.setStatus(text), Face.configResult(ok, msg)
 */
public class MainActivity extends Activity {

    private static final int REQ_MIC = 1;
    private static final int REQ_CAM = 2;
    private static final String PREFS = "jarvis";

    private WebView webView;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private Listener listener;
    private SharedPreferences prefs;
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private boolean listenAfterPermission = false;
    private LiveCamera liveCamera;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        listener = new Listener(this);

        webView = new WebView(this);
        webView.setBackgroundColor(0xFF000000);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        webView.addJavascriptInterface(new Bridge(), "Android");
        // The face fills the screen; the live camera preview floats on top.
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        root.addView(webView);
        android.view.TextureView preview = new android.view.TextureView(this);
        root.addView(preview);
        liveCamera = new LiveCamera(this, preview);
        setContentView(root);
        webView.loadUrl("file:///android_asset/face/index.html");

        tts = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.SUCCESS) {
                setStatus("No hay voz disponible en esta tableta");
                return;
            }
            int result = tts.setLanguage(new Locale("es", "US"));
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(new Locale("es"));
            }
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { callJs("Face.setTalking(true)"); }
                @Override public void onDone(String id) { callJs("Face.setTalking(false)"); }
                @Override public void onError(String id) { callJs("Face.setTalking(false)"); }
                @Override public void onRangeStart(String id, int start, int end, int frame) {
                    callJs("Face.pulse()");
                }
            });
            applyVoice(prefs.getFloat("pitch", 1f), prefs.getFloat("rate", 1f));
            ttsReady = true;
        });
    }

    // ---- Listening and asking -------------------------------------------

    private void startListening() {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            listenAfterPermission = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        String host = prefs.getString("host", "");
        String code = prefs.getString("code", "");
        if (host.isEmpty() || code.isEmpty()) {
            callJs("Face.openSettings()");
            return;
        }
        if (tts != null) tts.stop();
        callJs("Face.setMode('listening')");
        setStatus("Te escucho…");
        listener.start(new Listener.Callback() {
            @Override public void onLevel(float level) {
                callJs("Face.setLevel(" + level + ")");
            }

            @Override public void onFinished(File audio, boolean heardSpeech) {
                if (!heardSpeech) {
                    callJs("Face.setMode('idle')");
                    setStatus("No escuché nada. Toca para hablar");
                    return;
                }
                askBridge(audio, new BridgeClient(host, code));
            }

            @Override public void onError(String message) {
                callJs("Face.setMode('idle')");
                setStatus(message);
            }
        });
    }

    private interface Question {
        BridgeClient.Answer send(BridgeClient client, BridgeClient.Options options) throws BridgeClient.BridgeException;
    }

    private BridgeClient client() {
        return new BridgeClient(prefs.getString("host", ""), prefs.getString("code", ""));
    }

    private BridgeClient.Options options() {
        BridgeClient.Options o = new BridgeClient.Options();
        o.model = prefs.getString("model", "sonnet");
        o.edit = prefs.getBoolean("edit", false);
        o.shell = o.edit && prefs.getBoolean("shell", false);
        return o;
    }

    private void askBridge(File audio, BridgeClient client) {
        runQuestion((c, o) -> c.ask(audio, o));
    }

    private void runQuestion(Question question) {
        callJs("Face.setMode('thinking')");
        setStatus("Pensando…");
        BridgeClient.Options o = options();
        BridgeClient c = client();
        if (liveCamera.isOn()) {
            // Look first: grab what the camera sees right now and send it along.
            liveCamera.grab(jpeg -> network.execute(() -> send(c, o, question, jpeg)));
        } else {
            network.execute(() -> send(c, o, question, null));
        }
    }

    /** Runs on the network thread. */
    private void send(BridgeClient c, BridgeClient.Options o, Question question, byte[] jpeg) {
        try {
            if (jpeg != null) {
                try {
                    o.imageId = c.uploadImage(jpeg);
                } catch (BridgeClient.BridgeException e) {
                    o.imageId = null;  // answer anyway, just without the picture
                }
            }
            BridgeClient.Answer answer = question.send(c, o);
            callJs("Face.setMode('idle')");
            setStatus(answer.heard.isEmpty() ? "" : "«" + answer.heard + "»");
            speak(answer.reply);
        } catch (BridgeClient.BridgeException e) {
            callJs("Face.setMode('idle')");
            setStatus(e.getMessage());
            speak("Tengo un problema para conectarme con el computador.");
        }
    }

    private boolean configured() {
        if (prefs.getString("host", "").isEmpty() || prefs.getString("code", "").isEmpty()) {
            callJs("Face.openSettings()");
            return false;
        }
        return true;
    }

    private void sendText(String text) {
        if (text == null || text.trim().isEmpty() || !configured()) return;
        if (tts != null) tts.stop();
        String t = text.trim();
        runQuestion((c, o) -> c.chat(t, o));
    }

    private void toggleCamera() {
        if (liveCamera.isOn()) {
            liveCamera.stop();
            callJs("Face.setCameraOn(false)");
            setStatus("Cámara apagada");
            return;
        }
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAM);
            return;
        }
        String error = liveCamera.start(!"back".equals(prefs.getString("camera", "front")));
        if (error != null) {
            setStatus(error);
            return;
        }
        callJs("Face.setCameraOn(true)");
        setStatus("Cámara encendida: veré lo que tenga enfrente cuando me hables o escribas");
    }

    /** Switch between the front and back camera while it is on. */
    private void flipCamera() {
        String next = "back".equals(prefs.getString("camera", "front")) ? "front" : "back";
        prefs.edit().putString("camera", next).apply();
        if (!liveCamera.isOn()) return;
        String error = liveCamera.start("front".equals(next));
        if (error != null) {
            callJs("Face.setCameraOn(false)");
            setStatus(error);
            return;
        }
        callJs("Face.setCameraOn('" + next + "')");
        setStatus("front".equals(next) ? "Cámara frontal" : "Cámara trasera");
    }

    private void applyVoice(float pitch, float rate) {
        if (tts == null) return;
        tts.setPitch(pitch);
        tts.setSpeechRate(rate);
    }

    private void speak(String text) {
        if (!ttsReady || text == null || text.isEmpty()) return;
        // Claude sometimes still uses Markdown; don't read the symbols aloud.
        text = text.replaceAll("[*#`_>]+", "").replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1");
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u" + System.currentTimeMillis());
    }

    private void setStatus(String text) {
        callJs("Face.setStatus(" + JSONObject.quote(text) + ")");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_CAM) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) toggleCamera();
            else setStatus("Necesito permiso para usar la cámara");
            return;
        }
        if (requestCode != REQ_MIC) return;
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (granted && listenAfterPermission) {
            startListening();
        } else if (!granted) {
            setStatus("Necesito permiso para usar el micrófono");
        }
        listenAfterPermission = false;
    }

    // ---- Window ----------------------------------------------------------

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        // Let the new screen size settle before measuring it.
        webView.post(liveCamera::onRotated);
    }

    @Override
    protected void onPause() {
        listener.cancel();
        if (liveCamera.isOn()) {
            liveCamera.stop();
            callJs("Face.setCameraOn(false)");
        }
        callJs("Face.setMode('idle')");
        super.onPause();
    }

    private void callJs(String script) {
        runOnUiThread(() -> webView.evaluateJavascript(script, null));
    }

    @Override
    protected void onDestroy() {
        listener.cancel();
        network.shutdownNow();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        webView.destroy();
        super.onDestroy();
    }

    private class Bridge {
        @JavascriptInterface
        public void listen() {
            runOnUiThread(MainActivity.this::startListening);
        }

        @JavascriptInterface
        public void sendText(String text) {
            runOnUiThread(() -> MainActivity.this.sendText(text));
        }

        @JavascriptInterface
        public void toggleCamera() {
            runOnUiThread(MainActivity.this::toggleCamera);
        }

        @JavascriptInterface
        public void flipCamera() {
            runOnUiThread(MainActivity.this::flipCamera);
        }

        @JavascriptInterface
        public void setTyping(boolean on) {
            runOnUiThread(() -> liveCamera.setTyping(on));
        }

        @JavascriptInterface
        public void stopListening() {
            runOnUiThread(listener::finish);
        }

        @JavascriptInterface
        public boolean speak(String text) {
            if (!ttsReady) return false;
            MainActivity.this.speak(text);
            return true;
        }

        @JavascriptInterface
        public void stop() {
            if (tts != null) tts.stop();
            callJs("Face.setTalking(false)");
        }

        @JavascriptInterface
        public String getConfig() {
            try {
                return new JSONObject()
                        .put("host", prefs.getString("host", ""))
                        .put("code", prefs.getString("code", ""))
                        .put("model", prefs.getString("model", "sonnet"))
                        .put("edit", prefs.getBoolean("edit", false))
                        .put("shell", prefs.getBoolean("shell", false))
                        .put("camera", prefs.getString("camera", "front"))
                        .put("pitch", (double) prefs.getFloat("pitch", 1f))
                        .put("rate", (double) prefs.getFloat("rate", 1f))
                        .toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void savePrefs(String json) {
            try {
                JSONObject p = new JSONObject(json);
                String model = p.optString("model", "sonnet");
                float pitch = (float) p.optDouble("pitch", 1.0);
                float rate = (float) p.optDouble("rate", 1.0);
                prefs.edit()
                        .putString("model", model)
                        .putBoolean("edit", p.optBoolean("edit", false))
                        .putBoolean("shell", p.optBoolean("edit", false) && p.optBoolean("shell", false))
                        .putString("camera", "back".equals(p.optString("camera")) ? "back" : "front")
                        .putFloat("pitch", pitch)
                        .putFloat("rate", rate)
                        .apply();
                applyVoice(pitch, rate);
            } catch (Exception ignored) {
                // keep the previous preferences
            }
        }

        @JavascriptInterface
        public void previewVoice(String text, double pitch, double rate) {
            if (!ttsReady) return;
            applyVoice((float) pitch, (float) rate);
            MainActivity.this.speak(text);
        }

        @JavascriptInterface
        public void saveConfig(String host, String code) {
            network.execute(() -> {
                try {
                    new BridgeClient(host, code).checkCode();
                    prefs.edit().putString("host", host.trim()).putString("code", code.trim()).apply();
                    callJs("Face.configResult(true, " + JSONObject.quote("¡Conectado con el PC!") + ")");
                } catch (BridgeClient.BridgeException e) {
                    callJs("Face.configResult(false, " + JSONObject.quote(e.getMessage()) + ")");
                }
            });
        }
    }
}
