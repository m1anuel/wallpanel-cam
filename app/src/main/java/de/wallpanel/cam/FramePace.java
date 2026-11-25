package de.wallpanel.cam;

/** Drops camera frames so a 20 fps sensor can feed a slower stream. */
final class FramePace {
    static final int CAMERA_FPS = 20;

    private final int targetFps;
    private int accumulator;
    private boolean primed;

    FramePace(int targetFps) {
        this.targetFps = targetFps;
    }

    boolean take() {
        if (!primed) {
            primed = true;
            return true;
        }
        accumulator += targetFps;
        if (accumulator < CAMERA_FPS) {
            return false;
        }
        accumulator -= CAMERA_FPS;
        return true;
    }
}
