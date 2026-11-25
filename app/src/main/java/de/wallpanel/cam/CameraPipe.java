package de.wallpanel.cam;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.util.Range;
import android.view.Surface;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class CameraPipe {
    private static final String TAG = "WallpanelCam";
    private static final String CAMERA_ID = "0";

    private HandlerThread thread;
    private Handler handler;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private volatile boolean closing;
    private volatile String error;
    private final AtomicBoolean opened = new AtomicBoolean();

    String error() {
        return error == null ? "" : error;
    }

    boolean open(Context context, Surface target) {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            error = "Kamera-Berechtigung fehlt";
            return false;
        }
        closing = false;
        error = null;
        opened.set(false);
        thread = new HandlerThread("wallpanel-cam");
        thread.start();
        handler = new Handler(thread.getLooper());
        CountDownLatch latch = new CountDownLatch(1);
        CameraManager manager = context.getSystemService(CameraManager.class);
        try {
            logFacts(manager);
            manager.openCamera(CAMERA_ID, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice device) {
                    if (closing) {
                        device.close();
                        latch.countDown();
                        return;
                    }
                    camera = device;
                    createSession(device, target, latch);
                }

                @Override
                public void onDisconnected(CameraDevice device) {
                    if (!closing) {
                        error = "Kamera getrennt";
                    }
                    device.close();
                    latch.countDown();
                }

                @Override
                public void onError(CameraDevice device, int code) {
                    error = "Kamerafehler " + code;
                    device.close();
                    latch.countDown();
                }
            }, handler);
        } catch (Exception e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
            close();
            return false;
        }
        try {
            if (!latch.await(8, TimeUnit.SECONDS)) {
                error = "Kamera-Timeout";
                close();
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error = "unterbrochen";
            close();
            return false;
        }
        if (!opened.get()) {
            if (error == null) {
                error = "Session nicht bereit";
            }
            close();
            return false;
        }
        return true;
    }

    void close() {
        closing = true;
        CameraCaptureSession currentSession;
        CameraDevice currentCamera;
        synchronized (this) {
            currentSession = session;
            session = null;
            currentCamera = camera;
            camera = null;
        }
        if (currentSession != null) {
            try {
                currentSession.stopRepeating();
            } catch (Exception ignored) {
            }
            try {
                currentSession.close();
            } catch (Exception ignored) {
            }
        }
        if (currentCamera != null) {
            try {
                currentCamera.close();
            } catch (Exception ignored) {
            }
        }
        HandlerThread current = thread;
        thread = null;
        handler = null;
        if (current != null) {
            current.quitSafely();
            if (Looper.myLooper() != current.getLooper()) {
                try {
                    current.join(1500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void createSession(CameraDevice device, Surface target, CountDownLatch latch) {
        try {
            device.createCaptureSession(Collections.singletonList(target), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession captureSession) {
                    if (closing) {
                        captureSession.close();
                        latch.countDown();
                        return;
                    }
                    synchronized (CameraPipe.this) {
                        session = captureSession;
                    }
                    try {
                        repeating(device, captureSession, target, true);
                        opened.set(true);
                        Log.i(TAG, "capture repeating");
                    } catch (Exception e) {
                        error = "Request: " + e.getMessage();
                        Log.w(TAG, "repeating failed", e);
                    }
                    latch.countDown();
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession captureSession) {
                    error = "Session abgelehnt";
                    latch.countDown();
                }
            }, handler);
        } catch (CameraAccessException e) {
            error = "Session: " + e.getMessage();
            latch.countDown();
        }
    }

    private void repeating(CameraDevice device, CameraCaptureSession captureSession, Surface target, boolean withFps)
            throws CameraAccessException {
        CaptureRequest.Builder builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        builder.addTarget(target);
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        if (withFps) {
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(FramePace.CAMERA_FPS, FramePace.CAMERA_FPS));
        }
        try {
            captureSession.setRepeatingRequest(builder.build(), null, handler);
        } catch (IllegalArgumentException e) {
            if (!withFps) {
                throw e;
            }
            Log.w(TAG, "AE " + FramePace.CAMERA_FPS + " abgelehnt, ohne FPS-Vorgabe");
            repeating(device, captureSession, target, false);
        }
    }

    private static void logFacts(CameraManager manager) {
        try {
            CameraCharacteristics chars = manager.getCameraCharacteristics(CAMERA_ID);
            Integer orientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
            Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
            Range<Integer>[] ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            StringBuilder fps = new StringBuilder();
            if (ranges != null) {
                for (Range<Integer> range : ranges) {
                    if (fps.length() > 0) {
                        fps.append(' ');
                    }
                    fps.append(range.getLower()).append('-').append(range.getUpper());
                }
            }
            Log.i(TAG, "camera " + CAMERA_ID + " orientation " + orientation + " facing " + facing + " fps " + fps);
        } catch (Exception e) {
            Log.w(TAG, "camera facts: " + e.getMessage());
        }
    }

}
