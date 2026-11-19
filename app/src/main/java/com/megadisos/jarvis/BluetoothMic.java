package com.megadisos.jarvis;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

/**
 * Uses the microphone of connected Bluetooth earbuds (like Galaxy Buds).
 * Android only records from a Bluetooth headset over an "SCO" link, which
 * apps have to open themselves and close again afterwards, so the answer
 * plays back in full quality.
 */
@SuppressWarnings("deprecation")
class BluetoothMic {

    interface Ready {
        void onReady(boolean bluetooth);  // false: use the tablet's own mic
    }

    private static final int CONNECT_TIMEOUT_MS = 4000;

    private final Context context;
    private final AudioManager audio;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private BroadcastReceiver receiver;
    private boolean on;

    BluetoothMic(Context context) {
        this.context = context.getApplicationContext();
        this.audio = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
    }

    /** True when earbuds with a microphone are connected. */
    boolean available() {
        if (audio == null || !audio.isBluetoothScoAvailableOffCall()) return false;
        if (Build.VERSION.SDK_INT >= 23) {
            for (AudioDeviceInfo d : audio.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) return true;
            }
            return false;
        }
        return audio.isBluetoothA2dpOn();
    }

    /** Opens the earbuds' mic and calls back on the main thread, once. */
    void connect(Ready ready) {
        if (!available()) {
            ready.onReady(false);
            return;
        }
        final boolean[] done = {false};
        Runnable timeout = () -> {
            if (done[0]) return;
            done[0] = true;
            disconnect();
            ready.onReady(false);
        };
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                int state = i.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1);
                if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED && !done[0]) {
                    done[0] = true;
                    handler.removeCallbacks(timeout);
                    ready.onReady(true);
                }
            }
        };
        context.registerReceiver(receiver, new IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED));
        handler.postDelayed(timeout, CONNECT_TIMEOUT_MS);
        try {
            audio.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audio.startBluetoothSco();
            audio.setBluetoothScoOn(true);
            on = true;
        } catch (RuntimeException e) {
            handler.removeCallbacks(timeout);
            timeout.run();
        }
    }

    /** Back to normal audio, so Megadisos's answer plays in stereo quality. */
    void disconnect() {
        handler.removeCallbacksAndMessages(null);  // a pending timeout must not start recording
        if (receiver != null) {
            try { context.unregisterReceiver(receiver); } catch (RuntimeException ignored) { }
            receiver = null;
        }
        if (!on) return;
        on = false;
        try {
            audio.setBluetoothScoOn(false);
            audio.stopBluetoothSco();
            audio.setMode(AudioManager.MODE_NORMAL);
        } catch (RuntimeException ignored) { }
    }
}
