package dev.sculptory.fabric.client.editor.ui.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.ui.Rect;
import org.junit.jupiter.api.Test;

/** The clip math used when the editor UI is scaled (the drawing itself needs a running client). */
class DrawContextGraphicsTest {
    @Test
    void aPixelIsInsideWhenItsCentreIs() {
        assertEquals(7, DrawContextGraphics.pixel(7.0));
        assertEquals(7, DrawContextGraphics.pixel(7.25));
        assertEquals(7, DrawContextGraphics.pixel(7.5), "pixel 7's centre sits on the edge: inside");
        assertEquals(8, DrawContextGraphics.pixel(7.51));
        assertEquals(0, DrawContextGraphics.pixel(-0.25));
        assertEquals(-1, DrawContextGraphics.pixel(-0.5));
        for (int edge = -3; edge <= 4000; edge++) {
            assertEquals(edge, DrawContextGraphics.pixel(edge), "whole-pixel edges are unchanged");
        }
    }

    @Test
    void clipsScaleToFramebufferPixelsAndNest() {
        Rect clip = new Rect(5, 10, 100, 10);
        assertArrayEquals(new int[] {15, 30, 315, 60}, DrawContextGraphics.pixelEdges(clip, 3.0, null),
                "100% at GUI scale 3 is exact");
        // 75% at GUI scale 2: edges at 7.5, 15, 157.5 and 30 framebuffer pixels.
        assertArrayEquals(new int[] {7, 15, 157, 30}, DrawContextGraphics.pixelEdges(clip, 1.5, null));
        // 125% at GUI scale 2: edges at 12.5, 25, 262.5 and 50; 60% at GUI scale 3: 9, 18, 189, 36.
        assertArrayEquals(new int[] {12, 25, 262, 50}, DrawContextGraphics.pixelEdges(clip, 2.5, null));
        assertArrayEquals(new int[] {9, 18, 189, 36}, DrawContextGraphics.pixelEdges(clip, 0.6F * 3, null));
        assertArrayEquals(new int[] {10, 15, 150, 30},
                DrawContextGraphics.pixelEdges(clip, 1.5, new int[] {10, 0, 150, 40}), "intersected with the outer clip");
        int[] disjoint = DrawContextGraphics.pixelEdges(clip, 1.5, new int[] {200, 0, 300, 10});
        assertTrue(disjoint[2] <= disjoint[0] || disjoint[3] <= disjoint[1], "no overlap clips everything");
    }

    @Test
    void aClipUnderAMovedOriginMovesWithIt() {
        // The wiki's page: 4/3 larger with its origin at (30, 40) units, at UI 50% and GUI scale 6 (3 pixels a unit).
        Rect clip = new Rect(0, 0, 30, 15);
        assertArrayEquals(new int[] {90, 120, 210, 180}, DrawContextGraphics.pixelEdges(clip, 4.0, 90, 120, null));
        assertArrayEquals(DrawContextGraphics.pixelEdges(clip, 3.0, null),
                DrawContextGraphics.pixelEdges(clip, 3.0, 0, 0, null), "no offset: as before");
    }
}
