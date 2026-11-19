package com.megadisos.jarvis;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.Uri;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs the orders Megadisos puts in its answers: open or close apps, open links,
 * play music from YouTube in a small player over the face, pause, volume.
 * Everything here runs on the UI thread.
 */
class TabletActions {

    interface Status {
        void show(String text);
    }

    private static final Pattern YOUTUBE_ID =
            Pattern.compile("(?:v=|youtu\\.be/|/embed/|/shorts/|^)([A-Za-z0-9_-]{11})(?:[?&#/]|$)");

    private final Activity activity;
    private final FrameLayout root;
    private final Status status;
    private FrameLayout playerBox;
    private WebView player;
    private String playingTitle;
    private boolean pausedForListening;

    TabletActions(Activity activity, FrameLayout root, Status status) {
        this.activity = activity;
        this.root = root;
        this.status = status;
    }

    void run(JSONArray actions) {
        for (int i = 0; i < actions.length(); i++) {
            JSONObject a = actions.optJSONObject(i);
            if (a == null) continue;
            try {
                runOne(a);
            } catch (RuntimeException e) {
                status.show("No pude hacer eso en la tableta (" + e.getMessage() + ")");
            }
        }
    }

    private void runOne(JSONObject a) {
        switch (a.optString("accion")) {
            case "musica":
                String id = youtubeId(a.optString("youtube"));
                if (id != null) play("https://m.youtube.com/watch?v=" + id, a.optString("titulo", "YouTube"));
                else if (!a.optString("buscar").isEmpty()) {
                    play("https://m.youtube.com/results?search_query=" + Uri.encode(a.optString("buscar")),
                            "Búsqueda: " + a.optString("buscar"));
                }
                break;
            case "parar_musica":
                stopMusic();
                break;
            case "pausar":
                if (player != null) pausePlayer(true);
                mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE);
                break;
            case "seguir":
                if (player != null) pausePlayer(false);
                else mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY);
                break;
            case "volumen":
                setVolume(a.optInt("nivel", 50));
                break;
            case "abrir_app":
                openApp(a.optString("app"));
                break;
            case "cerrar_app":
                closeApp(a.optString("app"));
                break;
            case "abrir_url":
                openUrl(a.optString("url"));
                break;
            default:
                break;
        }
    }

    // ---- music player over the face -------------------------------------------

    boolean isPlaying() {
        return player != null;
    }

    String playingTitle() {
        return player == null ? null : playingTitle;
    }

    /** While Jorge talks the music pauses; it continues after Megadisos answers. */
    void pauseForListening() {
        if (player == null) return;
        pausedForListening = true;
        pausePlayer(true);
    }

    void resumeAfterAnswer() {
        if (player != null && pausedForListening) pausePlayer(false);
        pausedForListening = false;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void play(String url, String title) {
        if (player == null) {
            player = new WebView(activity);
            WebSettings s = player.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setMediaPlaybackRequiresUserGesture(false);
            player.setWebChromeClient(new WebChromeClient());
            player.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, String u) {
                    // Stay on YouTube inside the player; anything else goes to the browser.
                    if (u.contains("youtube.com") || u.contains("youtu.be") || u.contains("google.com")) return false;
                    openUrl(u);
                    return true;
                }

                @Override
                public void onPageFinished(WebView view, String u) {
                    // Autoplay is often blocked: keep nudging the video for a few seconds.
                    view.evaluateJavascript("(function(){var n=0;var t=setInterval(function(){"
                            + "var v=document.querySelector('video');"
                            + "if(v){v.play();if(!v.paused||++n>20)clearInterval(t);}else if(++n>20)clearInterval(t);"
                            + "},500);})();", null);
                }
            });

            playerBox = new FrameLayout(activity);
            playerBox.setBackgroundColor(Color.BLACK);
            playerBox.addView(player, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            TextView close = new TextView(activity);
            close.setText("✕");
            close.setTextColor(Color.WHITE);
            close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            close.setBackgroundColor(0xAA000000);
            close.setGravity(Gravity.CENTER);
            close.setOnClickListener(v -> stopMusic());
            int size = dp(40);
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.RIGHT);
            playerBox.addView(close, clp);

            int w = Math.round(activity.getResources().getDisplayMetrics().widthPixels * 0.45f);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, Math.round(w * 9f / 16f) + dp(40),
                    Gravity.BOTTOM | Gravity.RIGHT);
            lp.rightMargin = dp(12);
            lp.bottomMargin = dp(12);
            root.addView(playerBox, lp);
        }
        playingTitle = title;
        pausedForListening = false;
        player.loadUrl(url);
        status.show("♪ " + title);
    }

    private void pausePlayer(boolean pause) {
        if (player == null) return;
        player.evaluateJavascript("(function(){var v=document.querySelector('video');if(v){"
                + (pause ? "v.pause();" : "v.play();") + "}})();", null);
    }

    void stopMusic() {
        if (player == null) return;
        root.removeView(playerBox);
        player.loadUrl("about:blank");
        player.destroy();
        player = null;
        playerBox = null;
        playingTitle = null;
        pausedForListening = false;
    }

    // ---- other apps ---------------------------------------------------------

    /** Launchable apps, by name, for Megadisos to know what it can open. */
    List<String> appNames() {
        List<String> names = new ArrayList<>();
        for (ResolveInfo r : launchables()) {
            String label = r.loadLabel(activity.getPackageManager()).toString();
            if (!label.equals(activity.getString(R.string.app_name)) && !names.contains(label)) names.add(label);
        }
        java.util.Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    private List<ResolveInfo> launchables() {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        return activity.getPackageManager().queryIntentActivities(i, 0);
    }

    private ResolveInfo findApp(String name) {
        String want = simple(name);
        if (want.isEmpty()) return null;
        ResolveInfo best = null;
        for (ResolveInfo r : launchables()) {
            String label = simple(r.loadLabel(activity.getPackageManager()).toString());
            if (label.equals(want)) return r;
            if (best == null && (label.contains(want) || want.contains(label))) best = r;
        }
        return best;
    }

    private void openApp(String name) {
        ResolveInfo r = findApp(name);
        if (r == null) {
            status.show("No encontré la app " + name + " en la tableta");
            return;
        }
        Intent i = activity.getPackageManager().getLaunchIntentForPackage(r.activityInfo.packageName);
        if (i == null) return;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(i);
    }

    private void closeApp(String name) {
        mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE);
        // Megadisos is in front when it gets this order, so the other app is in the background now.
        ResolveInfo r = findApp(name);
        if (r == null) return;
        ActivityManager am = (ActivityManager) activity.getSystemService(Activity.ACTIVITY_SERVICE);
        if (am != null) am.killBackgroundProcesses(r.activityInfo.packageName);
    }

    private void openUrl(String url) {
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) return;
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            status.show("No hay una app para abrir ese link");
        }
    }

    // ---- sound --------------------------------------------------------------

    private void mediaKey(int code) {
        AudioManager am = (AudioManager) activity.getSystemService(Activity.AUDIO_SERVICE);
        if (am == null) return;
        long now = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0));
    }

    private void setVolume(int percent) {
        AudioManager am = (AudioManager) activity.getSystemService(Activity.AUDIO_SERVICE);
        if (am == null) return;
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int v = Math.round(Math.max(0, Math.min(100, percent)) * max / 100f);
        am.setStreamVolume(AudioManager.STREAM_MUSIC, v, AudioManager.FLAG_SHOW_UI);
    }

    // ---- helpers ------------------------------------------------------------

    static String youtubeId(String s) {
        if (s == null || s.isEmpty()) return null;
        Matcher m = YOUTUBE_ID.matcher(s.trim());
        return m.find() ? m.group(1) : null;
    }

    private static String simple(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }

    void destroy() {
        stopMusic();
    }
}
