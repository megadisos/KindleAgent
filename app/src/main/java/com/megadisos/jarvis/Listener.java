package com.megadisos.jarvis;

import android.content.Context;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.IOException;

/**
 * Records one spoken question to an .m4a file and stops by itself after a
 * short silence that follows speech (or after a hard time limit).
 */
class Listener {

    interface Callback {
        void onLevel(float level);          // 0..1, for the face animation
        void onFinished(File audio, boolean heardSpeech);
        void onError(String message);
    }

    private static final int POLL_MS = 100;
    private static final int SPEECH_LEVEL = 2500;      // getMaxAmplitude units, 0..32767
    private static final int SPEECH_LEVEL_BT = 1500;   // earbud mics come in quieter
    private static final int SILENCE_TO_STOP_MS = 1500;
    private static final int NO_SPEECH_TIMEOUT_MS = 7000;
    private static final int MAX_MS = 30000;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaRecorder recorder;
    private File file;
    private Callback callback;
    private long startedAt;
    private long lastSpeechAt;
    private boolean heardSpeech;
    private int speechLevel = SPEECH_LEVEL;

    Listener(Context context) {
        this.context = context;
    }

    boolean isListening() {
        return recorder != null;
    }

    void start(Callback cb) {
        start(cb, false);
    }

    /** @param bluetooth record from the earbuds (BluetoothMic already connected) */
    void start(Callback cb, boolean bluetooth) {
        if (recorder != null) return;
        callback = cb;
        file = new File(context.getCacheDir(), "question.m4a");
        try {
            recorder = new MediaRecorder();
            // Over Bluetooth only the plain MIC source is routed to the earbuds everywhere.
            recorder.setAudioSource(bluetooth ? MediaRecorder.AudioSource.MIC
                    : MediaRecorder.AudioSource.VOICE_RECOGNITION);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioChannels(1);
            recorder.setAudioSamplingRate(16000);
            recorder.setAudioEncodingBitRate(32000);
            recorder.setOutputFile(file.getAbsolutePath());
            recorder.prepare();
            recorder.start();
        } catch (IOException | RuntimeException e) {
            release();
            cb.onError("No pude usar el micrófono");
            return;
        }
        speechLevel = bluetooth ? SPEECH_LEVEL_BT : SPEECH_LEVEL;
        startedAt = System.currentTimeMillis();
        lastSpeechAt = startedAt;
        heardSpeech = false;
        recorder.getMaxAmplitude(); // first call always returns 0
        handler.postDelayed(poll, POLL_MS);
    }

    /** Stop now and send whatever was recorded. */
    void finish() {
        if (recorder == null) return;
        handler.removeCallbacks(poll);
        boolean ok = true;
        try {
            recorder.stop();
        } catch (RuntimeException e) {
            ok = false; // stop() throws if nothing was recorded
        }
        release();
        Callback cb = callback;
        callback = null;
        if (cb != null) cb.onFinished(file, ok && heardSpeech);
    }

    /** Stop and throw the recording away. */
    void cancel() {
        handler.removeCallbacks(poll);
        if (recorder != null) {
            try { recorder.stop(); } catch (RuntimeException ignored) { }
        }
        release();
        callback = null;
    }

    private void release() {
        if (recorder != null) {
            recorder.release();
            recorder = null;
        }
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (recorder == null) return;
            long now = System.currentTimeMillis();
            int amp = recorder.getMaxAmplitude();
            if (amp > speechLevel) {
                heardSpeech = true;
                lastSpeechAt = now;
            }
            if (callback != null) callback.onLevel(Math.min(1f, amp / 12000f));

            boolean silenceAfterSpeech = heardSpeech && now - lastSpeechAt > SILENCE_TO_STOP_MS;
            boolean nothingSaid = !heardSpeech && now - startedAt > NO_SPEECH_TIMEOUT_MS;
            if (silenceAfterSpeech || nothingSaid || now - startedAt > MAX_MS) {
                finish();
            } else {
                handler.postDelayed(this, POLL_MS);
            }
        }
    };
}
