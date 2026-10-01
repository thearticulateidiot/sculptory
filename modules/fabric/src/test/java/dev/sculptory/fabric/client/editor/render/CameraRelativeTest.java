package dev.sculptory.fabric.client.editor.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CameraRelativeTest {
    @Test
    void blockOutlineIsRelativeAndInflatedFarFromTheOrigin() {
        CameraRelative.Bounds bounds = CameraRelative.block(29_999_000, 64, -29_999_000, 29_999_000.5, 65.62, -29_998_999.5);
        assertEquals(-0.502f, bounds.minX(), 1e-5f);
        assertEquals(-1.622f, bounds.minY(), 1e-5f);
        assertEquals(-0.502f, bounds.minZ(), 1e-5f);
        assertEquals(0.502f, bounds.maxX(), 1e-5f);
        assertEquals(-0.618f, bounds.maxY(), 1e-5f);
        assertEquals(0.502f, bounds.maxZ(), 1e-5f);
    }

    @Test
    void boxUsesTheGivenInflation() {
        CameraRelative.Bounds bounds = CameraRelative.box(0, 0, 0, 4, 2, 1, 1, 1, 1, 0);
        assertEquals(new CameraRelative.Bounds(-1, -1, -1, 3, 1, 0), bounds);
        assertEquals(2.5f, CameraRelative.relative(1_000_002.5, 1_000_000), 0f);
    }
}
