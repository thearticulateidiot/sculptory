package dev.sculptory.fabric.client.editor.tour;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** The wiki pictures' PNG: exact with few colours, at most 256 colours close to the picture's otherwise. */
class TourPngTest {
    private static int[] decode(byte[] png, int width, int height) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertEquals(width, image.getWidth());
        assertEquals(height, image.getHeight());
        int[] rgb = image.getRGB(0, 0, width, height, null, 0, width);
        return Arrays.stream(rgb).map(pixel -> pixel & 0xFFFFFF).toArray();
    }

    @Test
    void aPictureOfFewColoursIsWrittenExactly() throws IOException {
        int[] ui = new int[40 * 10];
        for (int i = 0; i < ui.length; i++) {
            ui[i] = i % 7 == 0 ? 0xFFFFFF : i % 3 == 0 ? 0x2B2D31 : 0x5B8DEF;
        }
        assertArrayEquals(ui, decode(TourPng.encode(ui, 40, 10), 40, 10));
        int[] one = new int[9];
        Arrays.fill(one, 0x123456);
        assertArrayEquals(one, decode(TourPng.encode(one, 3, 3), 3, 3), "a single colour");
    }

    @Test
    void aPictureOfManyColoursKeepsAtMost256CloseToTheOriginal() throws IOException {
        int width = 200;
        int height = 150;
        int[] world = new int[width * height];
        Random random = new Random(7);
        for (int i = 0; i < world.length; i++) {
            int shade = random.nextInt(64);
            world[i] = (60 + shade) << 16 | (110 + shade) << 8 | (40 + random.nextInt(200));
        }
        int[] written = decode(TourPng.encode(world, width, height), width, height);
        assertTrue(Arrays.stream(written).distinct().count() <= TourPng.MAX_COLOURS);
        for (int i = 0; i < world.length; i++) {
            for (int shift = 0; shift <= 16; shift += 8) {
                int error = Math.abs(((world[i] >> shift) & 0xFF) - ((written[i] >> shift) & 0xFF));
                assertTrue(error <= 48, "pixel " + i + " channel " + shift + " off by " + error);
            }
        }
    }

    @Test
    void theAlphaChannelIsIgnoredAndBadSizesAreRefused() throws IOException {
        int[] rgba = {0xFF102030, 0x00102030};
        assertArrayEquals(new int[] {0x102030, 0x102030}, decode(TourPng.encode(rgba, 2, 1), 2, 1));
        assertThrows(IllegalArgumentException.class, () -> TourPng.encode(new int[5], 2, 2));
        assertThrows(IllegalArgumentException.class, () -> TourPng.encode(new int[0], 0, 0));
    }
}
