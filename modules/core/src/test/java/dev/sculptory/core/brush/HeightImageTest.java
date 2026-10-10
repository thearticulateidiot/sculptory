package dev.sculptory.core.brush;

import static dev.sculptory.core.brush.HeightmapKernelTest.bytes;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Resampling a height image: the brush's turned stamp and the import's stretch. */
class HeightImageTest {
    /** A 5 × 5 ramp: each pixel 10 × x + 50 × y, so every turn and mirror shows. */
    private static final HeightImage RAMP = ramp();

    private static HeightImage ramp() {
        byte[] pixels = new byte[25];
        for (int y = 0; y < 5; y++) {
            for (int x = 0; x < 5; x++) pixels[y * 5 + x] = (byte) (10 * x + 50 * y);
        }
        return new HeightImage(5, 5, pixels);
    }

    @Test
    void aStampTheImagesOwnSizeIsTheImage() {
        assertArrayEquals(RAMP.pixels(), RAMP.stamp(5, 0));
        assertArrayEquals(RAMP.pixels(), RAMP.stamp(5, 360));
    }

    /** Clockwise seen from above (east to south): stamp (u, v) of a quarter turn is picture (v, 4 - u). */
    @Test
    void quarterTurnsAreExactClockwiseTurns() {
        byte[] quarter = RAMP.stamp(5, 90);
        byte[] half = RAMP.stamp(5, 180);
        byte[] three = RAMP.stamp(5, 270);
        for (int v = 0; v < 5; v++) {
            for (int u = 0; u < 5; u++) {
                assertEquals(RAMP.value(v, 4 - u), quarter[v * 5 + u] & 0xFF, "90 at " + u + "," + v);
                assertEquals(RAMP.value(4 - u, 4 - v), half[v * 5 + u] & 0xFF, "180 at " + u + "," + v);
                assertEquals(RAMP.value(4 - v, u), three[v * 5 + u] & 0xFF, "270 at " + u + "," + v);
            }
        }
        assertArrayEquals(three, RAMP.stamp(5, -90));
    }

    @Test
    void aFreeAngleTurnsAboutTheCentreAndCutsTheCorners() {
        byte[] full = new byte[9 * 9];
        Arrays.fill(full, (byte) 200);
        HeightImage square = new HeightImage(9, 9, full);
        byte[] turned = square.stamp(9, 45);
        assertEquals(200, turned[4 * 9 + 4] & 0xFF);
        assertEquals(0, turned[0] & 0xFF, "the turned square's corner leaves the stamp's corner empty");
        assertEquals(0, turned[8 * 9 + 8] & 0xFF);
        assertArrayEquals(turned, square.stamp(9, 45), "the same every time");
        assertArrayEquals(square.stamp(9, 30), square.stamp(9, 390));
    }

    @Test
    void aWideImageIsFittedAcrossTheStampAndCentred() {
        byte[] pixels = new byte[8];
        Arrays.fill(pixels, (byte) 200);
        byte[] stamp = new HeightImage(4, 2, pixels).stamp(5, 0);
        for (int u = 0; u < 5; u++) {
            assertEquals(0, stamp[u] & 0xFF, "row 0 is outside the picture");
            if (u > 0 && u < 4) assertEquals(200, stamp[2 * 5 + u] & 0xFF, "the middle row is inside");
            assertEquals(0, stamp[4 * 5 + u] & 0xFF, "row 4 is outside the picture");
        }
    }

    @Test
    void shrinkingAveragesTheImage() {
        byte[] pixels = new byte[256 * 256];
        for (int i = 0; i < pixels.length; i++) pixels[i] = (byte) ((i / 256 + i % 256) % 2 == 0 ? 0 : 200);
        byte[] stamp = new HeightImage(256, 256, pixels).stamp(65, 0);
        for (int v = 2; v < 63; v++) {
            for (int u = 2; u < 63; u++) {
                int value = stamp[v * 65 + u] & 0xFF;
                assertEquals(100, value, 10, "a checkerboard shrinks to its mean at " + u + "," + v);
            }
        }
    }

    @Test
    void stretchTurnsInQuarterTurnsAndInverts() {
        HeightImage four = new HeightImage(2, 2, bytes(10, 20, 30, 40));
        assertArrayEquals(bytes(10, 20, 30, 40), four.stretch(2, 2, 0, false));
        assertArrayEquals(bytes(30, 10, 40, 20), four.stretch(2, 2, 1, false));
        assertArrayEquals(bytes(40, 30, 20, 10), four.stretch(2, 2, 2, false));
        assertArrayEquals(bytes(20, 40, 10, 30), four.stretch(2, 2, 3, false));
        assertArrayEquals(bytes(20, 40, 10, 30), four.stretch(2, 2, -1, false));
        assertArrayEquals(bytes(245, 235, 225, 215), four.stretch(2, 2, 0, true));
    }

    @Test
    void stretchHoldsTheEdgesOverAWideFootprint() {
        HeightImage two = new HeightImage(2, 1, bytes(0, 200));
        byte[] wide = two.stretch(8, 3, 0, false);
        for (int row = 0; row < 3; row++) {
            assertEquals(0, wide[row * 8] & 0xFF);
            assertEquals(0, wide[row * 8 + 1] & 0xFF);
            assertEquals(200, wide[row * 8 + 6] & 0xFF);
            assertEquals(200, wide[row * 8 + 7] & 0xFF);
            assertEquals(75, wide[row * 8 + 3] & 0xFF, "bilinear between the two pixels");
            assertEquals(125, wide[row * 8 + 4] & 0xFF);
        }
    }


    /**
     * The Heightmap brush's widest stamp (257 × 257, radius 128): from a 256-pixel picture (the built-ins' size) a smooth
     * ramp stays smooth (each step at most one level, rising all the way), a quarter turn is exact, and a 1,024-pixel
     * picture (the library's largest) shrinks with each value the mean of 4 × 4 samples, so a fine checkerboard averages
     * to grey instead of aliasing. Each stamp is worked out in tens of milliseconds at most (printed).
     */
    @Test
    void theWidestStampIsSmoothExactAndQuick() {
        byte[] ramp = new byte[256 * 256];
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 256; x++) ramp[y * 256 + x] = (byte) x;
        }
        HeightImage picture = new HeightImage(256, 256, ramp);
        long start = System.nanoTime();
        byte[] stamp = picture.stamp(257, 0);
        long plain = System.nanoTime() - start;
        for (int v = 0; v < 257; v++) {
            for (int u = 1; u < 256; u++) {
                int step = (stamp[v * 257 + u] & 0xFF) - (stamp[v * 257 + u - 1] & 0xFF);
                assertTrue(step >= 0 && step <= 1, "a smooth ramp at " + u + "," + v + ": " + step);
            }
        }
        assertTrue((stamp[128 * 257 + 255] & 0xFF) >= 250, "the ramp reaches the far edge");
        byte[] quarter = picture.stamp(257, 90);
        for (int v = 0; v < 257; v++) {
            for (int u = 0; u < 257; u++) assertEquals(stamp[(256 - u) * 257 + v], quarter[v * 257 + u], u + "," + v);
        }
        byte[] checks = new byte[1024 * 1024];
        for (int y = 0; y < 1024; y++) {
            for (int x = 0; x < 1024; x++) checks[y * 1024 + x] = (byte) ((x + y) % 2 == 0 ? 255 : 0);
        }
        HeightImage fine = new HeightImage(1024, 1024, checks);
        start = System.nanoTime();
        byte[] shrunk = fine.stamp(257, 37);
        long turned = System.nanoTime() - start;
        int c = 128 * 257 + 128;
        assertTrue(Math.abs((shrunk[c] & 0xFF) - 128) <= 16, "grey in the middle: " + (shrunk[c] & 0xFF));
        System.out.printf("Heightmap stamp 257 x 257: from 256 px %.1f ms; from 1024 px turned 37 degrees %.1f ms%n",
                plain / 1e6, turned / 1e6);
        assertTrue(turned < 2_000_000_000L, "a stamp took " + turned / 1e6 + " ms");
    }
    @Test
    void badSizesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new HeightImage(0, 1, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new HeightImage(2, 2, new byte[3]));
        assertThrows(IllegalArgumentException.class, () -> RAMP.stamp(HeightmapSpec.MAX_SIDE + 1, 0));
        assertThrows(IllegalArgumentException.class, () -> RAMP.stamp(0, 0));
        assertThrows(IllegalArgumentException.class, () -> RAMP.stretch(0, 3, 0, false));
    }
}
