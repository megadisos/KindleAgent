package com.megadisos.jarvis;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

import java.util.Locale;

/**
 * Full-screen face. The face itself is HTML/JS in assets/face; this activity
 * hosts it in a WebView and gives it a voice through Android's TextToSpeech.
 *
 * JS -> Android: window.Android.speak(text)
 * Android -> JS: Face.setTalking(bool), Face.pulse(), Face.setStatus(text)
 */
public class MainActivity extends Activity {

    private WebView webView;
    private TextToSpeech tts;
    private boolean ttsReady = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        webView = new WebView(this);
        webView.setBackgroundColor(0xFF000000);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        webView.addJavascriptInterface(new Bridge(), "Android");
        setContentView(webView);
        webView.loadUrl("file:///android_asset/face/index.html");

        tts = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.SUCCESS) {
                callJs("Face.setStatus('No hay voz disponible en esta tableta')");
                return;
            }
            Locale spanish = new Locale("es", "US");
            int result = tts.setLanguage(spanish);
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
            ttsReady = true;
        });
    }

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

    private void callJs(String script) {
        runOnUiThread(() -> webView.evaluateJavascript(script, null));
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        webView.destroy();
        super.onDestroy();
    }

    private class Bridge {
        @JavascriptInterface
        public boolean speak(String text) {
            if (!ttsReady) return false;
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u" + System.currentTimeMillis());
            return true;
        }

        @JavascriptInterface
        public void stop() {
            if (tts != null) tts.stop();
            callJs("Face.setTalking(false)");
        }
    }
}
