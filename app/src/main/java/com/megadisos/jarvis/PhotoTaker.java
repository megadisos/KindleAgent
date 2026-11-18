package com.megadisos.jarvis;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;

/**
 * Takes one still photo without showing a camera screen. Uses the old
 * android.hardware.Camera API on purpose: it works on every Fire OS version
 * and needs no extra libraries.
 */
@SuppressWarnings("deprecation")
class PhotoTaker {

    interface Callback {
        void onPhoto(byte[] jpeg);
        void onError(String message);
    }

    private static final int MAX_SIDE = 1280;
    private final Handler main = new Handler(Looper.getMainLooper());

    /** @param front true for the screen-side camera (the one looking at you). */
    void take(boolean front, Callback cb) {
        int id = findCamera(front ? Camera.CameraInfo.CAMERA_FACING_FRONT : Camera.CameraInfo.CAMERA_FACING_BACK);
        if (id < 0) id = findCamera(front ? Camera.CameraInfo.CAMERA_FACING_BACK : Camera.CameraInfo.CAMERA_FACING_FRONT);
        if (id < 0) {
            cb.onError("Esta tableta no tiene cámara disponible");
            return;
        }
        final Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(id, info);
        final Camera camera;
        try {
            camera = Camera.open(id);
        } catch (RuntimeException e) {
            cb.onError("No pude abrir la cámara");
            return;
        }
        try {
            Camera.Parameters params = camera.getParameters();
            Camera.Size best = null;
            for (Camera.Size size : params.getSupportedPictureSizes()) {
                if (best == null || size.width * size.height > best.width * best.height) best = size;
            }
            if (best != null) params.setPictureSize(best.width, best.height);
            camera.setParameters(params);
            // A preview must be running to take a picture; send it nowhere.
            camera.setPreviewTexture(new SurfaceTexture(10));
            camera.startPreview();
        } catch (Exception e) {
            camera.release();
            cb.onError("No pude preparar la cámara");
            return;
        }
        // Give auto-exposure a moment so the photo is not dark.
        main.postDelayed(() -> {
            try {
                camera.takePicture(null, null, (data, cam) -> {
                    cam.release();
                    new Thread(() -> {
                        try {
                            byte[] jpeg = uprightAndSmall(data, info);
                            main.post(() -> cb.onPhoto(jpeg));
                        } catch (Exception e) {
                            main.post(() -> cb.onError("No pude procesar la foto"));
                        }
                    }).start();
                });
            } catch (RuntimeException e) {
                camera.release();
                cb.onError("La cámara no tomó la foto");
            }
        }, 900);
    }

    private static int findCamera(int facing) {
        Camera.CameraInfo info = new Camera.CameraInfo();
        for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == facing) return i;
        }
        return -1;
    }

    /** Rotate to how the tablet is held in portrait and shrink. */
    private static byte[] uprightAndSmall(byte[] data, Camera.CameraInfo info) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, opts);

        // info.orientation is the clockwise rotation that makes the picture
        // upright with the tablet in its natural (portrait) position, for
        // both cameras. Still pictures are not mirrored, only previews are.
        Matrix m = new Matrix();
        m.postRotate(info.orientation);
        float scale = Math.min(1f, MAX_SIDE / (float) Math.max(bmp.getWidth(), bmp.getHeight()));
        m.postScale(scale, scale);
        Bitmap out = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);

        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        out.compress(Bitmap.CompressFormat.JPEG, 85, jpeg);
        return jpeg.toByteArray();
    }
}
