package dev.sculptory.core.brush;

import static dev.sculptory.core.brush.HeightmapKernelTest.bytes;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    void badSizesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new HeightImage(0, 1, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new HeightImage(2, 2, new byte[3]));
        assertThrows(IllegalArgumentException.class, () -> RAMP.stamp(66, 0));
        assertThrows(IllegalArgumentException.class, () -> RAMP.stretch(0, 3, 0, false));
    }
}
