package com.megadisos.jarvis;

import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.view.KeyEvent;

/**
 * A tap on the earbuds (play/pause) reaches Megadisos. Android sends media
 * buttons to the app that played audio last, so on start we play a moment
 * of silence to become that app.
 */
class HeadsetButtons {

    private final Context context;
    private final Runnable onTap;
    private MediaSession session;

    HeadsetButtons(Context context, Runnable onTap) {
        this.context = context.getApplicationContext();
        this.onTap = onTap;
    }

    void start() {
        if (session == null) {
            session = new MediaSession(context, "Megadisos");
            session.setCallback(new MediaSession.Callback() {
                @Override
                public boolean onMediaButtonEvent(Intent intent) {
                    KeyEvent e = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                    if (e == null) return false;
                    if (isTap(e.getKeyCode())) {
                        if (e.getAction() == KeyEvent.ACTION_UP) onTap.run();
                        return true;
                    }
                    return false;
                }
            });
            session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                    | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        }
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE)
                .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                .build());
        session.setActive(true);
        playSilence();
    }

    void stop() {
        if (session != null) {
            session.setActive(false);
        }
    }

    void release() {
        if (session != null) {
            session.release();
            session = null;
        }
    }

    static boolean isTap(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_HEADSETHOOK
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY
                || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE;
    }

    @SuppressWarnings("deprecation")
    private static void playSilence() {
        new Thread(() -> {
            int rate = 16000;
            short[] silence = new short[rate / 4];  // a quarter of a second
            AudioTrack t = null;
            try {
                t = new AudioTrack(AudioManager.STREAM_MUSIC, rate, AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, silence.length * 2, AudioTrack.MODE_STATIC);
                t.write(silence, 0, silence.length);
                t.play();
                Thread.sleep(400);
            } catch (Exception ignored) {
            } finally {
                if (t != null) {
                    try { t.stop(); } catch (RuntimeException ignored) { }
                    t.release();
                }
            }
        }).start();
    }
}
