package dev.sculptory.fabric.client.editor.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.world.SurfaceSampler;
import dev.sculptory.fabric.client.editor.world.TerrainProbe;
import org.junit.jupiter.api.Test;

/** The pure geometry behind {@link BrushCursorRenderer} and {@link OverlayColors}. */
class BrushCursorGeometryTest {
    /** Ground at y = 60 + x in the disc's columns. */
    private static final TerrainProbe SLOPE = new TerrainProbe() {
        @Override
        public boolean isTerrainSolid(int x, int y, int z) {
            return y <= 60 + x;
        }

        @Override
        public int bottomY() {
            return -64;
        }

        @Override
        public int topY() {
            return 320;
        }
    };

    @Test
    void falloffIsFlatInsideThenLinear() {
        assertEquals(1, BrushCursorRenderer.falloffWeight(0, 4, 0.5), 1e-9);
        assertEquals(1, BrushCursorRenderer.falloffWeight(2, 4, 0.5), 1e-9);
        assertEquals(0.5, BrushCursorRenderer.falloffWeight(3, 4, 0.5), 1e-9);
        assertEquals(0, BrushCursorRenderer.falloffWeight(4, 4, 0.5), 1e-9);
        assertEquals(0, BrushCursorRenderer.falloffWeight(9, 4, 1.0), 1e-9);
        assertEquals(1, BrushCursorRenderer.falloffWeight(3.9, 4, 1.0), 1e-9);
    }

    @Test
    void serverOnlyRingIsDashed() {
        int drawn = 0;
        for (int i = 0; i < BrushCursorRenderer.RING_SEGMENTS; i++) {
            assertTrue(BrushCursorRenderer.segmentDrawn(i, false));
            if (BrushCursorRenderer.segmentDrawn(i, true)) {
                drawn++;
            }
        }
        assertEquals(BrushCursorRenderer.RING_SEGMENTS / 2, drawn);
        assertTrue(BrushCursorRenderer.segmentDrawn(1, true));
        assertFalse(BrushCursorRenderer.segmentDrawn(2, true));
    }

    @Test
    void ringFollowsTheEdgeColumnsOfTheDisc() {
        SurfaceSampler.Samples samples = SurfaceSampler.scan(SLOPE, 0, 60, 0, 3);
        double outer = BrushCursorRenderer.outerRadius(3);
        // East and west edge of the ring read the x = +3 and x = -3 columns.
        assertEquals(64, BrushCursorRenderer.surfaceTop(samples, 0.5, 0.5, 0.5 + outer, 0.5, -1), 1e-9);
        assertEquals(58, BrushCursorRenderer.surfaceTop(samples, 0.5, 0.5, 0.5 - outer, 0.5, -1), 1e-9);
        // The centre reads the centre column.
        assertEquals(61, BrushCursorRenderer.surfaceTop(samples, 0.5, 0.5, 0.5, 0.5, -1), 1e-9);
        assertEquals(61, BrushCursorRenderer.fallbackTop(samples), 1e-9);
    }

    @Test
    void colourHelpersKeepChannels() {
        assertEquals(0x40FF8000, OverlayColors.withAlpha(0xFFFF8000, 0x40));
        assertEquals(0x80FF8000, OverlayColors.scaleAlpha(0xFFFF8000, 128 / 255.0));
        assertEquals(0xFFFFFFFF, OverlayColors.lighten(0xFF000000, 1.0));
        assertEquals(0x12345678, OverlayColors.argb(0x12, 0x34, 0x56, 0x78));
    }
}
