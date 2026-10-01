package dev.sculptory.core.brush;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.Pattern;
import java.util.List;
import org.junit.jupiter.api.Test;

class BrushSpecTest {
    private static BrushSpec spec(BrushTool tool, int radius, float strength, Pattern material) {
        return new BrushSpec(tool, radius, strength, Falloff.SMOOTH, Shape.CIRCLE, material, SurfaceMask.ANY, 1, 64, 7L);
    }

    @Test
    void radiusAndStrengthRanges() {
        assertDoesNotThrow(() -> spec(BrushTool.RAISE, 1, 0f, null));
        assertDoesNotThrow(() -> spec(BrushTool.RAISE, 32, 1f, null));
        assertThrows(IllegalArgumentException.class, () -> spec(BrushTool.RAISE, 0, 0.5f, null));
        assertThrows(IllegalArgumentException.class, () -> spec(BrushTool.RAISE, 33, 0.5f, null));
        assertThrows(IllegalArgumentException.class, () -> spec(BrushTool.RAISE, 4, -0.01f, null));
        assertThrows(IllegalArgumentException.class, () -> spec(BrushTool.RAISE, 4, 1.01f, null));
        assertThrows(IllegalArgumentException.class, () -> spec(BrushTool.RAISE, 4, Float.NaN, null));
    }

    @Test
    void paintToolsNeedMaterial() {
        assertThrows(IllegalArgumentException.class, () -> spec(BrushTool.PAINT, 4, 1f, null));
        assertThrows(IllegalArgumentException.class, () -> spec(BrushTool.PALETTE, 4, 1f, null));
        assertDoesNotThrow(() -> spec(BrushTool.PAINT, 4, 1f, new Pattern.Single(1)));
        assertThrows(IllegalArgumentException.class, () -> new BrushSpec(BrushTool.PAINT, 4, 1f, Falloff.LINEAR,
                Shape.SQUARE, new Pattern.Single(1), SurfaceMask.ANY, 33, 0, 0L));
    }

    @Test
    void clipBoxIsOptionalAndBounded() {
        BrushSpec free = spec(BrushTool.RAISE, 4, 1f, null);
        assertNull(free.clip(), "the ten-field constructor has no clip");
        Box box = new Box(new BlockPos(-5, -64, 3), new BlockPos(5, 319, 9));
        BrushSpec clipped = free.withClip(box);
        assertEquals(box, clipped.clip());
        assertNotEquals(free, clipped, "the clip is part of the spec");
        assertEquals(free, clipped.withClip(null));
        int far = BrushSpec.CLIP_MAX_HORIZONTAL;
        assertDoesNotThrow(() -> free.withClip(new Box(new BlockPos(-far, -BrushSpec.CLIP_MAX_Y, -far),
                new BlockPos(far, BrushSpec.CLIP_MAX_Y, far))));
        assertThrows(IllegalArgumentException.class, () -> free.withClip(Box.of(new BlockPos(far + 1, 0, 0))));
        assertThrows(IllegalArgumentException.class, () -> free.withClip(Box.of(new BlockPos(0, 0, -far - 1))));
        assertThrows(IllegalArgumentException.class, () -> free.withClip(Box.of(new BlockPos(0, BrushSpec.CLIP_MAX_Y + 1, 0))));
        assertThrows(IllegalArgumentException.class,
                () -> free.withClip(new Box(new BlockPos(0, -BrushSpec.CLIP_MAX_Y - 1, 0), new BlockPos(0, 0, 0))));
    }

    @Test
    void dabFixedPoint() {
        Dab dab = Dab.of(3, 10.5, 64.99, -0.03, Dab.FULL_PRESSURE);
        assertEquals(168, dab.x16());
        assertEquals(1039, dab.y16());
        assertEquals(-1, dab.z16());
        assertEquals(10, dab.blockX());
        assertEquals(64, dab.blockY());
        assertEquals(-1, dab.blockZ());
        assertThrows(IllegalArgumentException.class, () -> new Dab(-1, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Dab(0, 0, 0, 0, 256));
    }

    @Test
    void surfaceMaskValidation() {
        assertThrows(IllegalArgumentException.class, () -> new SurfaceMask.Elevation(10, 5));
        assertThrows(IllegalArgumentException.class, () -> new SurfaceMask.Slope(-1, 2));
        assertThrows(IllegalArgumentException.class, () -> new SurfaceMask.Slope(3, 2));
        assertThrows(IllegalArgumentException.class, () -> new SurfaceMask.And(List.of()));
        SurfaceMask mask = new SurfaceMask.And(List.of(
                new SurfaceMask.SurfaceBlocks(CellMask.ANY),
                new SurfaceMask.Not(new SurfaceMask.Slope(3, 10)),
                new SurfaceMask.Elevation(-64, 100)));
        assertEquals(6, mask.nodeCount(), "the nested cell mask counts toward the budget");
        assertEquals(3, mask.depth());
        List<CellMask> wide = new java.util.ArrayList<>();
        for (int i = 0; i < CellMask.MAX_NODES - 1; i++) wide.add(CellMask.ANY);
        CellMask fullCellMask = new CellMask.And(wide);
        assertThrows(IllegalArgumentException.class, () -> new SurfaceMask.SurfaceBlocks(fullCellMask));
        assertEquals(SurfaceMask.MAX_NODES, new SurfaceMask.SurfaceBlocks(new CellMask.And(wide.subList(1, wide.size())))
                .nodeCount());
        SurfaceMask deep = SurfaceMask.ANY;
        for (int depth = 2; depth <= SurfaceMask.MAX_DEPTH; depth++) deep = new SurfaceMask.Not(deep);
        SurfaceMask deepest = deep;
        assertThrows(IllegalArgumentException.class, () -> new SurfaceMask.Not(deepest));
    }
}
