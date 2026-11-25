package de.wallpanel.cam;

/** Hardcoded camera transform. Clockwise, applied on the sensor image. */
final class FrameOrientation {
    // Upright without an extra turn. The 11:9 encoder size undoes the wide stretch.
    // The front-camera texture matrix mirrors; flip X so the room is not a mirror image.
    static final int DEGREES = 0;
    static final boolean MIRROR = true;

    private FrameOrientation() {}

    static boolean needsGl() {
        return DEGREES != 0 || MIRROR;
    }
}
