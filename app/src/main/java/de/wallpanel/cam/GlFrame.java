package de.wallpanel.cam;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Aspect-correct GLES blit from the camera texture onto the encoder surface. */
final class GlFrame {
    private static final String TAG = "WallpanelCam";
    private static final int EGL_RECORDABLE = 0x3142;

    private static final String VERTEX =
            "attribute vec4 aPosition;\n"
                    + "attribute vec4 aTexCoord;\n"
                    + "varying vec2 vTexCoord;\n"
                    + "void main() {\n"
                    + "  gl_Position = aPosition;\n"
                    + "  vTexCoord = aTexCoord.xy;\n"
                    + "}\n";

    private static final String FRAGMENT =
            "#extension GL_OES_EGL_image_external : require\n"
                    + "precision mediump float;\n"
                    + "varying vec2 vTexCoord;\n"
                    + "uniform samplerExternalOES sTexture;\n"
                    + "uniform mat4 uTexMatrix;\n"
                    + "uniform int uRot;\n"
                    + "uniform float uMirror;\n"
                    + "void main() {\n"
                    + "  vec2 uv = vTexCoord;\n"
                    + "  if (uMirror > 0.5) uv.x = 1.0 - uv.x;\n"
                    + "  vec2 src;\n"
                    + "  if (uRot == 90) src = vec2(1.0 - uv.y, uv.x);\n"
                    + "  else if (uRot == 180) src = vec2(1.0 - uv.x, 1.0 - uv.y);\n"
                    + "  else if (uRot == 270) src = vec2(uv.y, 1.0 - uv.x);\n"
                    + "  else src = uv;\n"
                    + "  vec2 st = (uTexMatrix * vec4(src, 0.0, 1.0)).xy;\n"
                    + "  gl_FragColor = texture2D(sTexture, st);\n"
                    + "}\n";

    private HandlerThread thread;
    private Handler handler;
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private SurfaceTexture surfaceTexture;
    private Surface cameraSurface;
    private int program;
    private int textureId;
    private int texMatrixLoc;
    private int rotLoc;
    private int mirrorLoc;
    private FloatBuffer positions;
    private FloatBuffer texCoords;
    private final float[] texMatrix = new float[16];
    private volatile String error;
    private volatile boolean released;
    private int frameRate = Prefs.DEFAULT_FPS;
    private FramePace pace;

    String error() {
        return error == null ? "" : error;
    }

    Surface start(Surface encoderSurface, int fps) {
        frameRate = Prefs.fps(fps);
        pace = new FramePace(frameRate);
        thread = new HandlerThread("wallpanel-gl");
        thread.start();
        handler = new Handler(thread.getLooper());
        CountDownLatch latch = new CountDownLatch(1);
        handler.post(() -> {
            try {
                init(encoderSurface);
            } catch (Exception e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                Log.e(TAG, "gl init", e);
            } finally {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(4, TimeUnit.SECONDS)) {
                error = "GL-Timeout";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error = "unterbrochen";
        }
        if (error != null || cameraSurface == null) {
            release();
            return null;
        }
        return cameraSurface;
    }

    void release() {
        if (released) {
            return;
        }
        released = true;
        Handler current = handler;
        HandlerThread currentThread = thread;
        if (current == null) {
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        current.post(() -> {
            try {
                releaseGl();
            } finally {
                latch.countDown();
                Looper looper = Looper.myLooper();
                if (looper != null) {
                    looper.quitSafely();
                }
            }
        });
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (currentThread != null) {
            try {
                currentThread.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        handler = null;
        thread = null;
    }

    private void init(Surface encoderSurface) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (display == EGL14.EGL_NO_DISPLAY) {
            throw new IllegalStateException("kein EGL-Display");
        }
        int[] version = new int[2];
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            throw new IllegalStateException("eglInitialize");
        }
        int[] attrib = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE, 1,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        if (!EGL14.eglChooseConfig(display, attrib, 0, configs, 0, 1, count, 0) || count[0] == 0) {
            throw new IllegalStateException("eglChooseConfig");
        }
        int[] ctxAttrib = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttrib, 0);
        if (context == null || context == EGL14.EGL_NO_CONTEXT) {
            throw new IllegalStateException("eglCreateContext");
        }
        int[] surfaceAttrib = {EGL14.EGL_NONE};
        eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], encoderSurface, surfaceAttrib, 0);
        if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE) {
            throw new IllegalStateException("eglCreateWindowSurface " + EGL14.eglGetError());
        }
        if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
            throw new IllegalStateException("eglMakeCurrent");
        }
        program = link(VERTEX, FRAGMENT);
        texMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix");
        rotLoc = GLES20.glGetUniformLocation(program, "uRot");
        mirrorLoc = GLES20.glGetUniformLocation(program, "uMirror");
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        textureId = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        surfaceTexture = new SurfaceTexture(textureId);
        surfaceTexture.setDefaultBufferSize(AvcEncoder.CAMERA_WIDTH, AvcEncoder.CAMERA_HEIGHT);
        surfaceTexture.setOnFrameAvailableListener(ignored -> drawFrame(), handler);
        cameraSurface = new Surface(surfaceTexture);
        positions = buffer(quad(FrameOrientation.DEGREES));
        texCoords = buffer(new float[] {
                0f, 0f,
                1f, 0f,
                0f, 1f,
                1f, 1f
        });
        GLES20.glViewport(0, 0, AvcEncoder.WIDTH, AvcEncoder.HEIGHT);
        Log.i(TAG, "gl rot=" + FrameOrientation.DEGREES + " mirror=" + FrameOrientation.MIRROR
                + " fps=" + frameRate);
    }

    private void drawFrame() {
        if (released || surfaceTexture == null || display == EGL14.EGL_NO_DISPLAY) {
            return;
        }
        try {
            surfaceTexture.updateTexImage();
            if (pace != null && !pace.take()) {
                return;
            }
            surfaceTexture.getTransformMatrix(texMatrix);
            GLES20.glViewport(0, 0, AvcEncoder.WIDTH, AvcEncoder.HEIGHT);
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(program);
            GLES20.glUniformMatrix4fv(texMatrixLoc, 1, false, texMatrix, 0);
            GLES20.glUniform1i(rotLoc, FrameOrientation.DEGREES);
            GLES20.glUniform1f(mirrorLoc, FrameOrientation.MIRROR ? 1f : 0f);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            positions.position(0);
            texCoords.position(0);
            GLES20.glEnableVertexAttribArray(0);
            GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, positions);
            GLES20.glEnableVertexAttribArray(1);
            GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 0, texCoords);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(0);
            GLES20.glDisableVertexAttribArray(1);
            EGLExt.eglPresentationTimeANDROID(display, eglSurface, surfaceTexture.getTimestamp());
            EGL14.eglSwapBuffers(display, eglSurface);
        } catch (Exception e) {
            Log.w(TAG, "gl draw: " + e.getMessage());
        }
    }

    private void releaseGl() {
        if (cameraSurface != null) {
            cameraSurface.release();
            cameraSurface = null;
        }
        if (surfaceTexture != null) {
            surfaceTexture.release();
            surfaceTexture = null;
        }
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(display, eglSurface);
                eglSurface = EGL14.EGL_NO_SURFACE;
            }
            if (context != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(display, context);
                context = EGL14.EGL_NO_CONTEXT;
            }
            EGL14.eglTerminate(display);
            display = EGL14.EGL_NO_DISPLAY;
        }
    }

    private static float[] quad(int degrees) {
        float viewW = AvcEncoder.WIDTH;
        float viewH = AvcEncoder.HEIGHT;
        float imageW = viewW;
        float imageH = viewH;
        if (degrees == 90 || degrees == 270) {
            imageW = viewH;
            imageH = viewW;
        }
        float scale = Math.min(viewW / imageW, viewH / imageH);
        float hx = (imageW * scale) / viewW;
        float hy = (imageH * scale) / viewH;
        return new float[] {
                -hx, -hy,
                hx, -hy,
                -hx, hy,
                hx, hy
        };
    }

    private static FloatBuffer buffer(float[] values) {
        FloatBuffer buffer = ByteBuffer.allocateDirect(values.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        buffer.put(values);
        buffer.position(0);
        return buffer;
    }

    private static int link(String vertex, String fragment) {
        int vs = compile(GLES20.GL_VERTEX_SHADER, vertex);
        int fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment);
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vs);
        GLES20.glAttachShader(program, fs);
        GLES20.glBindAttribLocation(program, 0, "aPosition");
        GLES20.glBindAttribLocation(program, 1, "aTexCoord");
        GLES20.glLinkProgram(program);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(program);
            GLES20.glDeleteProgram(program);
            throw new IllegalStateException("link " + log);
        }
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        return program;
    }

    private static int compile(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new IllegalStateException("shader " + log);
        }
        return shader;
    }
}
