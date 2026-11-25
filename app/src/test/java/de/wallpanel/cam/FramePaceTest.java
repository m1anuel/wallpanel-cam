package de.wallpanel.cam;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class FramePaceTest {
    @Test
    public void keepsTheRequestedShareOfCameraFrames() {
        assertEquals(20, taken(20, 20));
        assertEquals(15, taken(15, 20));
        assertEquals(10, taken(10, 20));
        assertEquals(5, taken(5, 20));
    }

    @Test
    public void storedFpsFallsBackTo20() {
        assertEquals(20, Prefs.fps(0));
        assertEquals(20, Prefs.fps(12));
        assertEquals(20, Prefs.fps(30));
        assertEquals(5, Prefs.fps(5));
        assertEquals(10, Prefs.fps(10));
        assertEquals(15, Prefs.fps(15));
        assertEquals(20, Prefs.fps(20));
    }

    private static int taken(int target, int frames) {
        FramePace pace = new FramePace(target);
        int kept = 0;
        for (int i = 0; i < frames; i++) {
            if (pace.take()) {
                kept++;
            }
        }
        return kept;
    }
}
