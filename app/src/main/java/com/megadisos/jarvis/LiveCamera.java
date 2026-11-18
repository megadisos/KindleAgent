package com.megadisos.jarvis;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Live camera preview in a small window over the face, plus grabbing the
 * current frame as a JPEG when Jarvis is asked something. Uses the old
 * android.hardware.Camera API: it works on every Fire OS and needs no
 * extra libraries.
 */
@SuppressWarnings("deprecation")
class LiveCamera implements TextureView.SurfaceTextureListener {

    interface FrameCallback {
        void onFrame(byte[] jpeg);  // null if no frame could be taken
    }

    private static final int WANT_W = 640, WANT_H = 480;  // sensor orientation
    private static final int MAX_SIDE = 1024;

    private final Activity activity;
    private final TextureView view;
    private Camera camera;
    private Camera.CameraInfo info;
    private Camera.Size size;
    private boolean front = true;

    LiveCamera(Activity activity, TextureView view) {
        this.activity = activity;
        this.view = view;
        view.setSurfaceTextureListener(this);
        view.setVisibility(View.GONE);
    }

    boolean isOn() {
        return camera != null;
    }

    /** @return an error message, or null when the camera is running. */
    String start(boolean useFront) {
        stop();
        front = useFront;
        int id = find(front ? Camera.CameraInfo.CAMERA_FACING_FRONT : Camera.CameraInfo.CAMERA_FACING_BACK);
        if (id < 0) id = find(front ? Camera.CameraInfo.CAMERA_FACING_BACK : Camera.CameraInfo.CAMERA_FACING_FRONT);
        if (id < 0) return "Esta tableta no tiene cámara disponible";
        info = new Camera.CameraInfo();
        Camera.getCameraInfo(id, info);
        try {
            camera = Camera.open(id);
            Camera.Parameters p = camera.getParameters();
            size = closest(p);
            p.setPreviewSize(size.width, size.height);
            p.setPreviewFormat(ImageFormat.NV21);
            if (p.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            }
            camera.setParameters(p);
            camera.setDisplayOrientation(displayRotation());
        } catch (RuntimeException e) {
            stop();
            return "No pude abrir la cámara";
        }
        sizeView();
        view.setVisibility(View.VISIBLE);
        if (view.isAvailable()) startPreview(view.getSurfaceTexture());
        return null;
    }

    void stop() {
        if (camera != null) {
            try { camera.stopPreview(); } catch (RuntimeException ignored) { }
            camera.release();
            camera = null;
        }
        view.setVisibility(View.GONE);
    }

    /** Grab what the camera sees right now. Must be called on the UI thread. */
    void grab(FrameCallback cb) {
        if (camera == null) {
            cb.onFrame(null);
            return;
        }
        final Camera.Size s = size;
        final int rotation = frameRotation();
        // Exactly one answer: the frame, or null if none arrives in time.
        final AtomicBoolean done = new AtomicBoolean(false);
        view.postDelayed(() -> {
            if (done.compareAndSet(false, true)) cb.onFrame(null);
        }, 2000);
        try {
            camera.setOneShotPreviewCallback((data, cam) -> new Thread(() -> {
                byte[] jpeg;
                try {
                    jpeg = toJpeg(data, s.width, s.height, rotation);
                } catch (Exception e) {
                    jpeg = null;
                }
                if (done.compareAndSet(false, true)) cb.onFrame(jpeg);
            }).start());
        } catch (RuntimeException e) {
            if (done.compareAndSet(false, true)) cb.onFrame(null);
        }
    }

    /** Turn needed so the saved frame is upright the way the tablet is held. */
    private int frameRotation() {
        int degrees = screenDegrees();
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            return (info.orientation + degrees) % 360;
        }
        return (info.orientation - degrees + 360) % 360;
    }

    // ---- preview plumbing -------------------------------------------------

    private void startPreview(SurfaceTexture surface) {
        if (camera == null) return;
        try {
            camera.setPreviewTexture(surface);
            camera.startPreview();
        } catch (Exception e) {
            stop();
        }
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) { startPreview(s); }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) { }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { stop(); return true; }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture s) { }

    private int screenDegrees() {
        switch (activity.getWindowManager().getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_90: return 90;
            case Surface.ROTATION_180: return 180;
            case Surface.ROTATION_270: return 270;
            default: return 0;
        }
    }

    private int displayRotation() {
        int degrees = screenDegrees();
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            int r = (info.orientation + degrees) % 360;
            return (360 - r) % 360;  // the preview is mirrored like a selfie
        }
        return (info.orientation - degrees + 360) % 360;
    }

    private boolean typing;

    /** Small window in the top-left corner (clear of the eyes) with the preview's aspect ratio. */
    private void sizeView() {
        int screenW = activity.getResources().getDisplayMetrics().widthPixels;
        int w = Math.round(screenW * 0.28f);
        boolean sideways = displayRotation() % 180 != 0;
        float aspect = sideways ? size.width / (float) size.height : size.height / (float) size.width;
        int h = Math.round(w * aspect);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
        lp.leftMargin = Math.round(12 * activity.getResources().getDisplayMetrics().density);
        lp.topMargin = Math.round((typing ? 84 : 56) * activity.getResources().getDisplayMetrics().density);
        view.setLayoutParams(lp);
    }

    /** The tablet was turned: turn the preview with it. */
    void onRotated() {
        if (camera == null) return;
        try {
            camera.setDisplayOrientation(displayRotation());
        } catch (RuntimeException ignored) { }
        sizeView();
    }

    /** Moves the window below the typing bar while the user writes. */
    public void setTyping(boolean on) {
        typing = on;
        if (camera != null && size != null) sizeView();
    }

    private static Camera.Size closest(Camera.Parameters p) {
        Camera.Size best = null;
        for (Camera.Size s : p.getSupportedPreviewSizes()) {
            if (best == null || score(s) < score(best)) best = s;
        }
        return best;
    }

    private static int score(Camera.Size s) {
        return Math.abs(s.width - WANT_W) + Math.abs(s.height - WANT_H);
    }

    private static int find(int facing) {
        Camera.CameraInfo i = new Camera.CameraInfo();
        for (int n = 0; n < Camera.getNumberOfCameras(); n++) {
            Camera.getCameraInfo(n, i);
            if (i.facing == facing) return n;
        }
        return -1;
    }

    /** NV21 preview frame -> upright JPEG, at most MAX_SIDE px. */
    private static byte[] toJpeg(byte[] nv21, int w, int h, int rotation) {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        new YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(new Rect(0, 0, w, h), 90, raw);
        byte[] bytes = raw.toByteArray();
        Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        Matrix m = new Matrix();
        m.postRotate(rotation);
        float scale = Math.min(1f, MAX_SIDE / (float) Math.max(bmp.getWidth(), bmp.getHeight()));
        m.postScale(scale, scale);
        Bitmap out = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        out.compress(Bitmap.CompressFormat.JPEG, 85, jpeg);
        return jpeg.toByteArray();
    }
}
