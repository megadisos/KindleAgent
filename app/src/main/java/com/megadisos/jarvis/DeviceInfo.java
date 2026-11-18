package com.megadisos.jarvis;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A short, readable summary of the tablet's state (battery, model, storage,
 * memory, wifi, screen, sensors) that goes along with every question, so
 * Jarvis can answer "how much battery do you have?" and the like.
 * Light and motion sensors are only listened to while the app is open.
 */
class DeviceInfo implements SensorEventListener {

    private final Context context;
    private final SensorManager sensors;
    private volatile float lux = -1;
    private volatile float[] gravity;  // last accelerometer reading

    DeviceInfo(Context context) {
        this.context = context.getApplicationContext();
        this.sensors = (SensorManager) this.context.getSystemService(Context.SENSOR_SERVICE);
    }

    void resume() {
        if (sensors == null) return;
        Sensor light = sensors.getDefaultSensor(Sensor.TYPE_LIGHT);
        Sensor accel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (light != null) sensors.registerListener(this, light, SensorManager.SENSOR_DELAY_NORMAL);
        if (accel != null) sensors.registerListener(this, accel, SensorManager.SENSOR_DELAY_NORMAL);
    }

    void pause() {
        if (sensors != null) sensors.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() == Sensor.TYPE_LIGHT) lux = e.values[0];
        else if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) gravity = e.values.clone();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    /** One paragraph in Spanish. Each part is optional: a failure just leaves it out. */
    String summary() {
        List<String> parts = new ArrayList<>();
        add(parts, this::battery);
        add(parts, this::model);
        add(parts, this::storage);
        add(parts, this::memory);
        add(parts, this::wifi);
        add(parts, this::screen);
        add(parts, this::sound);
        add(parts, this::position);
        add(parts, this::sensorList);
        add(parts, () -> "encendida hace " + duration(SystemClock.elapsedRealtime() / 1000));
        return TextUtils.join("; ", parts);
    }

    private interface Part { String get() throws Exception; }

    private static void add(List<String> parts, Part p) {
        try {
            String s = p.get();
            if (s != null && !s.isEmpty()) parts.add(s);
        } catch (Exception ignored) { }
    }

    private String battery() {
        Intent b = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return null;
        int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        int plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        int temp = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        int health = b.getIntExtra(BatteryManager.EXTRA_HEALTH, -1);
        StringBuilder s = new StringBuilder("batería ");
        s.append(Math.round(level * 100f / scale)).append("%");
        if (status == BatteryManager.BATTERY_STATUS_FULL) {
            s.append(" (llena)");
        } else {
            s.append(status == BatteryManager.BATTERY_STATUS_CHARGING ? " (cargando" : " (descargando");
            if (plugged == BatteryManager.BATTERY_PLUGGED_AC) s.append(" con cargador");
            else if (plugged == BatteryManager.BATTERY_PLUGGED_USB) s.append(" por USB");
            else if (plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS) s.append(" inalámbrico");
            s.append(")");
        }
        if (temp != Integer.MIN_VALUE) s.append(String.format(Locale.US, ", %.1f °C", temp / 10f));
        if (health == BatteryManager.BATTERY_HEALTH_OVERHEAT) s.append(", sobrecalentada");
        else if (health == BatteryManager.BATTERY_HEALTH_DEAD) s.append(", dañada");
        return s.toString();
    }

    private String model() {
        return "equipo " + Build.MANUFACTURER + " " + Build.MODEL
                + " (Android " + Build.VERSION.RELEASE + ", API " + Build.VERSION.SDK_INT
                + ", " + Runtime.getRuntime().availableProcessors() + " núcleos"
                + (Build.SUPPORTED_ABIS.length > 0 ? " " + Build.SUPPORTED_ABIS[0] : "") + ")";
    }

    private String storage() {
        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        long total = fs.getBlockCountLong() * fs.getBlockSizeLong();
        long free = fs.getAvailableBlocksLong() * fs.getBlockSizeLong();
        return "almacenamiento " + gb(free) + " libres de " + gb(total);
    }

    private String memory() {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo m = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(m);
        return "RAM " + gb(m.availMem) + " libre de " + gb(m.totalMem);
    }

    @SuppressWarnings("deprecation")
    private String wifi() {
        WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        if (wm == null || !wm.isWifiEnabled()) return "wifi apagado";
        WifiInfo w = wm.getConnectionInfo();
        if (w == null || w.getNetworkId() == -1) return "wifi sin conectar";
        int bars = WifiManager.calculateSignalLevel(w.getRssi(), 5);
        return "wifi señal " + bars + "/4 (" + w.getRssi() + " dBm, " + w.getLinkSpeed() + " Mbps)";
    }

    private String screen() throws Settings.SettingNotFoundException {
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int bright = Settings.System.getInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
        boolean landscape = context.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        return "pantalla " + dm.widthPixels + "x" + dm.heightPixels + " (" + dm.densityDpi + " dpi, "
                + (landscape ? "horizontal" : "vertical") + ", brillo " + Math.round(bright * 100f / 255) + "%)";
    }

    private String sound() {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        int v = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        return "volumen " + Math.round(v * 100f / max) + "%";
    }

    private String position() {
        List<String> s = new ArrayList<>();
        float l = lux;
        if (l >= 0) s.add(String.format(Locale.US, "luz ambiente %.0f lux", l));
        float[] g = gravity;
        if (g != null) {
            float z = Math.abs(g[2]);
            s.add(z > 8.5f ? (g[2] > 0 ? "acostada boca arriba" : "acostada boca abajo")
                    : z < 4f ? "de pie" : "inclinada");
        }
        return TextUtils.join(", ", s);
    }

    private String sensorList() {
        if (sensors == null) return null;
        Set<String> names = new LinkedHashSet<>();
        for (Sensor s : sensors.getSensorList(Sensor.TYPE_ALL)) {
            String n = sensorName(s.getType());
            if (n != null) names.add(n);
        }
        return names.isEmpty() ? null : "sensores: " + TextUtils.join(", ", names);
    }

    private static String sensorName(int type) {
        switch (type) {
            case Sensor.TYPE_ACCELEROMETER: return "acelerómetro";
            case Sensor.TYPE_GYROSCOPE: return "giroscopio";
            case Sensor.TYPE_MAGNETIC_FIELD: return "brújula";
            case Sensor.TYPE_LIGHT: return "luz";
            case Sensor.TYPE_PROXIMITY: return "proximidad";
            case Sensor.TYPE_PRESSURE: return "barómetro";
            case Sensor.TYPE_AMBIENT_TEMPERATURE: return "temperatura";
            case Sensor.TYPE_RELATIVE_HUMIDITY: return "humedad";
            case Sensor.TYPE_STEP_COUNTER: return "podómetro";
            default: return null;
        }
    }

    private static String gb(long bytes) {
        return String.format(Locale.US, "%.1f GB", bytes / 1e9);
    }

    private static String duration(long seconds) {
        long h = seconds / 3600, m = seconds % 3600 / 60;
        return h > 0 ? h + " h " + m + " min" : m + " min";
    }
}
