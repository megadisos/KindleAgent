package com.megadisos.jarvis;

import android.Manifest;
import android.app.ActivityManager;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
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
 * Megadisos can answer "how much battery do you have?" and the like.
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
        this.locations = (LocationManager) this.context.getSystemService(Context.LOCATION_SERVICE);
    }

    void resume() {
        startLocation();
        if (sensors == null) return;
        Sensor light = sensors.getDefaultSensor(Sensor.TYPE_LIGHT);
        Sensor accel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (light != null) sensors.registerListener(this, light, SensorManager.SENSOR_DELAY_NORMAL);
        if (accel != null) sensors.registerListener(this, accel, SensorManager.SENSOR_DELAY_NORMAL);
    }

    void pause() {
        if (sensors != null) sensors.unregisterListener(this);
        if (locations != null) {
            try { locations.removeUpdates(locationListener); } catch (RuntimeException ignored) { }
        }
    }

    // ---- location -----------------------------------------------------------

    private final LocationManager locations;
    private volatile Location lastLocation;
    private volatile String address;  // from Geocoder, when the tablet has one
    private volatile Location geocoded;

    private final LocationListener locationListener = new LocationListener() {
        @Override public void onLocationChanged(Location l) { useLocation(l); }
        @Override public void onStatusChanged(String p, int s, Bundle b) { }
        @Override public void onProviderEnabled(String p) { }
        @Override public void onProviderDisabled(String p) { }
    };

    boolean hasLocationPermission() {
        return Build.VERSION.SDK_INT < 23
                || context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Last known fix right away, then updates every few minutes while the app is open. */
    @SuppressWarnings("MissingPermission")
    void startLocation() {
        if (locations == null || !hasLocationPermission()) return;
        try {
            for (String p : locations.getProviders(true)) {
                Location l = locations.getLastKnownLocation(p);
                if (l != null) useLocation(l);
            }
            for (String p : new String[]{LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER}) {
                if (locations.isProviderEnabled(p)) {
                    locations.requestLocationUpdates(p, 5 * 60 * 1000L, 100f, locationListener, Looper.getMainLooper());
                }
            }
        } catch (RuntimeException ignored) { }
    }

    private void useLocation(Location l) {
        Location old = lastLocation;
        boolean better = old == null
                || l.getTime() - old.getTime() > 2 * 60 * 1000
                || l.getAccuracy() < old.getAccuracy();
        if (!better) return;
        lastLocation = l;
        Location g = geocoded;
        if (Geocoder.isPresent() && (g == null || g.distanceTo(l) > 300)) {
            geocoded = l;
            new Thread(() -> {
                try {
                    List<Address> found = new Geocoder(context, new Locale("es"))
                            .getFromLocation(l.getLatitude(), l.getLongitude(), 1);
                    if (found != null && !found.isEmpty()) address = describe(found.get(0));
                } catch (Exception ignored) { }
            }).start();
        }
    }

    private static String describe(Address a) {
        List<String> parts = new ArrayList<>();
        if (a.getThoroughfare() != null) {
            parts.add(a.getThoroughfare() + (a.getSubThoroughfare() != null ? " " + a.getSubThoroughfare() : ""));
        }
        for (String p : new String[]{a.getSubLocality(), a.getLocality(), a.getAdminArea(), a.getCountryName()}) {
            if (p != null && !parts.contains(p)) parts.add(p);
        }
        return TextUtils.join(", ", parts);
    }

    private String location() {
        if (!hasLocationPermission()) return "ubicación sin permiso";
        Location l = lastLocation;
        if (l == null) {
            boolean on = locations != null && (locations.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                    || locations.isProviderEnabled(LocationManager.GPS_PROVIDER));
            return on ? "ubicación todavía no disponible" : "ubicación apagada en la configuración de la tableta";
        }
        long ageMin = (System.currentTimeMillis() - l.getTime()) / 60000;
        StringBuilder s = new StringBuilder(String.format(Locale.US, "ubicación %.5f, %.5f (±%.0f m",
                l.getLatitude(), l.getLongitude(), l.getAccuracy()));
        s.append(ageMin > 1 ? ", de hace " + duration(ageMin * 60) + ")" : ")");
        String a = address;
        if (a != null && !a.isEmpty()) s.append(", cerca de ").append(a);
        return s.toString();
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
        add(parts, this::location);
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
        // WifiInfo hides the network id without location permission on newer
        // Fire OS, so ask ConnectivityManager whether wifi is connected.
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        boolean connected = false;
        if (cm != null) {
            if (Build.VERSION.SDK_INT >= 23) {
                Network n = cm.getActiveNetwork();
                NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
                connected = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            } else {
                NetworkInfo ni = cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
                connected = ni != null && ni.isConnected();
            }
        }
        WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        if (!connected) return wm != null && !wm.isWifiEnabled() ? "wifi apagado" : "wifi sin conectar";
        StringBuilder s = new StringBuilder("wifi conectado");
        WifiInfo w = wm == null ? null : wm.getConnectionInfo();
        if (w != null) {
            String ssid = w.getSSID();
            if (ssid != null && !ssid.contains("unknown ssid")) s.append(" a ").append(ssid.replace("\"", ""));
            if (w.getRssi() > -127) {
                s.append(", señal ").append(WifiManager.calculateSignalLevel(w.getRssi(), 5)).append("/4 (")
                        .append(w.getRssi()).append(" dBm");
                if (w.getLinkSpeed() > 0) s.append(", ").append(w.getLinkSpeed()).append(" Mbps");
                s.append(")");
            }
        }
        return s.toString();
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
