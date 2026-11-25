package de.wallpanel.cam;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;

final class AvcEncoder {
    static final String TAG = "WallpanelCam";
    // Portrait A4 looked square, so the picture was too wide by √2. 1056x1296 is that correction.
    static final int WIDTH = 1056;
    static final int HEIGHT = 1296;
    static final int CAMERA_WIDTH = 1600;
    static final int CAMERA_HEIGHT = 1200;
    static final int BITRATE = 4_000_000;
    static final int FRAME_RATE = 20;
    static final int I_FRAME_SEC = 2;

    interface Listener {
        void onCodecConfig(byte[] annexb);

        void onAccessUnit(byte[] annexb, long presentationTimeUs, boolean key);

        void onError(String message);
    }

    private MediaCodec encoder;
    private Surface surface;
    private volatile boolean running;
    private Thread drain;

    Surface start(Listener listener) throws Exception {
        Exception baselineError = null;
        try {
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            configure(encoder, true);
        } catch (Exception first) {
            baselineError = first;
            releaseCodec();
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            configure(encoder, false);
        }
        Log.i(TAG, "encoder " + encoder.getName()
                + (baselineError == null ? " baseline" : " default-profile"));
        surface = encoder.createInputSurface();
        encoder.start();
        running = true;
        drain = new Thread(() -> drainLoop(listener), "wallpanel-enc");
        drain.start();
        return surface;
    }

    void requestSync() {
        MediaCodec current = encoder;
        if (current == null || !running) {
            return;
        }
        try {
            Bundle params = new Bundle();
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            current.setParameters(params);
        } catch (Exception e) {
            Log.w(TAG, "sync request failed: " + e.getMessage());
        }
    }

    void stop() {
        running = false;
        Thread thread = drain;
        drain = null;
        if (thread != null) {
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        releaseCodec();
        if (surface != null) {
            surface.release();
            surface = null;
        }
    }

    private void releaseCodec() {
        MediaCodec current = encoder;
        encoder = null;
        if (current == null) {
            return;
        }
        try {
            current.stop();
        } catch (Exception ignored) {
        }
        try {
            current.release();
        } catch (Exception ignored) {
        }
    }

    private static void configure(MediaCodec codec, boolean baseline) {
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_SEC);
        if (Build.VERSION.SDK_INT >= 29) {
            format.setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1);
        }
        if (baseline) {
            format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
            format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel4);
            if (Build.VERSION.SDK_INT >= 29) {
                format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
            }
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
    }

    private void drainLoop(Listener listener) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (running) {
            MediaCodec current = encoder;
            if (current == null) {
                return;
            }
            int index;
            try {
                index = current.dequeueOutputBuffer(info, 10_000);
            } catch (Exception e) {
                if (running) {
                    listener.onError(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                }
                return;
            }
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                continue;
            }
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                try {
                    emitFormat(current, listener);
                } catch (Exception e) {
                    Log.w(TAG, "csd: " + e.getMessage());
                }
                continue;
            }
            if (index < 0) {
                continue;
            }
            try {
                ByteBuffer buffer = current.getOutputBuffer(index);
                if (buffer != null && info.size > 0) {
                    byte[] data = new byte[info.size];
                    buffer.position(info.offset);
                    buffer.limit(info.offset + info.size);
                    buffer.get(data);
                    boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                    if (config) {
                        listener.onCodecConfig(data);
                    } else {
                        listener.onAccessUnit(data, info.presentationTimeUs, key);
                    }
                }
            } catch (Exception e) {
                if (running) {
                    listener.onError(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                }
            } finally {
                try {
                    current.releaseOutputBuffer(index, false);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void emitFormat(MediaCodec current, Listener listener) {
        MediaFormat format = current.getOutputFormat();
        byte[] csd0 = asAnnex(bytes(format.getByteBuffer("csd-0")));
        byte[] csd1 = asAnnex(bytes(format.getByteBuffer("csd-1")));
        if (csd0 == null && csd1 == null) {
            return;
        }
        int n0 = csd0 == null ? 0 : csd0.length;
        int n1 = csd1 == null ? 0 : csd1.length;
        byte[] joined = new byte[n0 + n1];
        if (csd0 != null) {
            System.arraycopy(csd0, 0, joined, 0, n0);
        }
        if (csd1 != null) {
            System.arraycopy(csd1, 0, joined, n0, n1);
        }
        listener.onCodecConfig(joined);
    }

    private static byte[] asAnnex(byte[] nal) {
        if (nal == null || nal.length == 0) {
            return null;
        }
        if (startsWithStartCode(nal) || Rtp.looksLikeAvcc(nal)) {
            return nal;
        }
        byte[] out = new byte[nal.length + 4];
        out[3] = 1;
        System.arraycopy(nal, 0, out, 4, nal.length);
        return out;
    }

    private static boolean startsWithStartCode(byte[] data) {
        return data.length >= 4 && data[0] == 0 && data[1] == 0 && (data[2] == 1 || (data[2] == 0 && data[3] == 1));
    }

    private static byte[] bytes(ByteBuffer buffer) {
        if (buffer == null) {
            return null;
        }
        ByteBuffer copy = buffer.duplicate();
        byte[] out = new byte[copy.remaining()];
        copy.get(out);
        return out;
    }
}
