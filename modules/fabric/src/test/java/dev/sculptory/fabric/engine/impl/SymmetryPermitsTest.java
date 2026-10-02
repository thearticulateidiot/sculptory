package dev.sculptory.fabric.engine.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.BrushTool;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.core.brush.Falloff;
import dev.sculptory.core.brush.Shape;
import dev.sculptory.core.brush.ShapeSpec;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.brush.SymmetricStep;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.server.engine.ChunkPermit;
import dev.sculptory.server.perm.ChunkPermits;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Brush symmetry on the server: a chunk's permit is asked for the copies' areas in it, the lock check covers each copy's
 * area and searched column, and centres stay in the world. The Shape brush's areas are its shapes' boxes, and its
 * height is limited like its radius.
 */
class SymmetryPermitsTest {
    private static Box box(int x0, int y0, int z0, int x1, int y1, int z1) {
        return new Box(new BlockPos(x0, y0, z0), new BlockPos(x1, y1, z1));
    }

    @Test
    void aChunksPermitCoversTheCopiesInsideIt() {
        Box east = box(20, 60, 2, 30, 90, 12);
        Box west = box(-31, 64, 2, -21, 94, 12);
        List<Box> copies = List.of(east, west);
        assertEquals(east, EngineEditService.permitBounds(copies, 1, 0));
        assertEquals(west, EngineEditService.permitBounds(copies, -2, 0));
        assertNull(EngineEditService.permitBounds(copies, 0, 0), "no copy reaches chunk (0, 0)");
        // Two areas in one chunk: the box holding both parts.
        assertEquals(box(2, 60, 1, 12, 80, 6),
                EngineEditService.permitBounds(List.of(box(2, 60, 3, 5, 70, 6), box(9, 64, 1, 12, 80, 4)), 0, 0));
        // One area across chunk edges: its part in each chunk, as the one-box permit always saw it.
        Box across = box(10, 60, 10, 20, 70, 20);
        assertEquals(box(10, 60, 10, 15, 70, 15), EngineEditService.permitBounds(List.of(across), 0, 0));
        assertEquals(box(16, 60, 16, 20, 70, 20), EngineEditService.permitBounds(List.of(across), 1, 1));

        // A protected west side: the copy's chunk is judged on the copy's area and denied. Asked with the dab's own
        // box alone, a chunk it does not touch would be allowed.
        ChunkPermits.ColumnTest protectedWest = (x, z) -> x >= 0;
        assertEquals(ChunkPermit.DENY, ChunkPermits.forChunk(-2, 0, EngineEditService.permitBounds(copies, -2, 0), protectedWest));
        assertEquals(ChunkPermit.ALLOW, ChunkPermits.forChunk(-2, 0, east, protectedWest));
        assertEquals(ChunkPermit.ALLOW, ChunkPermits.forChunk(1, 0, EngineEditService.permitBounds(copies, 1, 0), protectedWest));
    }

    /** Whether {@code box} touches 16³ section (sx, sy, sz), as the executor's section locks see it. */
    private static boolean touches(Box box, int sx, int sy, int sz) {
        return box.min().x() >> 4 <= sx && sx <= box.max().x() >> 4 && box.min().y() >> 4 <= sy && sy <= box.max().y() >> 4
                && box.min().z() >> 4 <= sz && sz <= box.max().z() >> 4;
    }

    /**
     * The lock check covers what a symmetric step reads and writes: each dab's area at the height it stands at, and each
     * copy's searched column, which reaches far above and below its area. A job holding only a section of that column
     * (here y 112-127 of the copy's chunk, above both areas) refuses the dab; a copy without ground is checked on its
     * column alone.
     */
    @Test
    void theLockCheckCoversEachCopysSearchedColumn() {
        FakeStateSpace states = new FakeStateSpace();
        FakeWorld world = new FakeWorld(states);
        for (int x = -20; x <= 20; x++) {
            for (int z = -8; z <= 8; z++) {
                for (int y = 50; y <= 60; y++) world.set(x, y, z, states.state(y < 60 ? "minecraft:stone" : "minecraft:grass_block"));
            }
        }
        BrushSpec raise = new BrushSpec(BrushTool.RAISE, 3, 1f, Falloff.CONSTANT, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0,
                1L, null, new Symmetry(Symmetry.Mode.MIRROR_X, 1, 1));
        Dab dab = new Dab(0, -10 * 16 + 8, 61 * 16, 8, 255);
        SymmetricStep step = SymmetricStep.of(raise, dab, world);
        Dab copy = new Dab(0, 10 * 16 + 8, 61 * 16, 8, 255);
        assertEquals(List.of(dab, copy), step.dabs());
        Box dabArea = EngineEditService.dabBox(raise, -64, 320, dab);
        Box copyArea = EngineEditService.dabBox(raise, -64, 320, copy);
        Box searched = SymmetricStep.searchColumn(10, 0, 61, -64, 320);
        assertEquals(box(10, -3, 0, 10, 125, 0), searched);
        assertEquals(List.of(dabArea, copyArea, searched), EngineEditService.stepBoxes(raise, -64, 320, dab, step));
        assertFalse(touches(dabArea, 0, 7, 0) || touches(copyArea, 0, 7, 0), "neither area reaches y 112");
        assertTrue(touches(searched, 0, 7, 0), "the search does");

        // The copy's centre on a pillar past the search: it stands nowhere, and only its column is checked.
        for (int y = 61; y <= 140; y++) world.set(10, y, 0, states.state("minecraft:stone"));
        SymmetricStep none = SymmetricStep.of(raise, dab, world);
        assertEquals(List.of(dab), none.dabs());
        assertEquals(List.of(dabArea, searched), EngineEditService.stepBoxes(raise, -64, 320, dab, none));
    }

    /**
     * The Shape brush's step: its copies are not searched for ground, and what it reads and writes is exactly its
     * placements' boxes: a shape lying east reaches sections a terrain brush of the same radius never touches (a job
     * there refuses the dab), and a thin pillar is checked on its own columns only, not on a cube of its height.
     */
    @Test
    void aShapeStepCoversExactlyItsShapesBoxesAndSearchesNothing() {
        FakeWorld world = new FakeWorld(new FakeStateSpace());
        ShapeSpec lying = new ShapeSpec(ShapeSpec.Kind.CYLINDER, 41, Facing.EAST, ShapeSpec.Mode.PLACE, 0);
        BrushSpec shape = BrushSpec.shape(1, lying, new Pattern.Single(1), 0L, null, new Symmetry(Symmetry.Mode.MIRROR_X, 1, 1));
        Dab dab = new Dab(0, 10 * 16 + 8, 120 * 16 + 8, 10 * 16 + 8, 255);
        SymmetricStep step = SymmetricStep.of(shape, dab, world);
        Dab copy = new Dab(0, -9 * 16 - 8, 120 * 16 + 8, 10 * 16 + 8, 255);
        assertEquals(List.of(dab, copy), step.dabs(), "the copy keeps the dab's height");
        assertEquals(List.of(), step.searched());
        // 41 long east of block 10 (cells -10 to 30), its mirror across x = 0.5 (cells -30 to 10), 3 across.
        Box own = box(-10, 119, 9, 30, 121, 11);
        Box mirrored = box(-30, 119, 9, 10, 121, 11);
        assertEquals(List.of(own, mirrored), EngineEditService.stepBoxes(shape, -64, 320, dab, step));
        assertEquals(List.of(own, mirrored), EngineEditService.shapeBoxes(shape, -64, 320, dab));
        // Section x 16-31 at y 112-127: the shape reaches it, a radius-1 terrain brush at the same dab does not.
        assertTrue(touches(own, 1, 7, 0));
        assertFalse(touches(EngineEditService.dabBox(new BrushSpec(BrushTool.RAISE, 1, 1f, Falloff.CONSTANT, Shape.CIRCLE, null,
                SurfaceMask.ANY, 0, 0, 1L), -64, 320, dab), 1, 7, 0));

        // A radius-1 pillar 65 tall: three columns wide, not 67.
        BrushSpec pillar = BrushSpec.shape(1, new ShapeSpec(ShapeSpec.Kind.CYLINDER, 65, Facing.UP, ShapeSpec.Mode.PLACE, 0),
                new Pattern.Single(1), 0L, null, Symmetry.NONE);
        assertEquals(List.of(box(9, 88, 9, 11, 152, 11)), EngineEditService.shapeBoxes(pillar, -64, 320, dab));
        // Clamped to the build height.
        Dab high = new Dab(0, 10 * 16 + 8, 310 * 16 + 8, 10 * 16 + 8, 255);
        assertEquals(List.of(box(9, 278, 9, 11, 319, 11)), EngineEditService.shapeBoxes(pillar, -64, 320, high));
        // Four copies about the dab's own column: one placement each, all checked.
        BrushSpec cones = BrushSpec.shape(2, new ShapeSpec(ShapeSpec.Kind.CONE, 6, Facing.EAST, ShapeSpec.Mode.PLACE, 0),
                new Pattern.Single(1), 0L, null, new Symmetry(Symmetry.Mode.ROTATE_4, 21, 21));
        assertEquals(4, EngineEditService.shapeBoxes(cones, -64, 320, dab).size(), "the twins on the centre count");
    }

    @Test
    void aShapeMayBeAsTallAsTheLargestDiameter() {
        Pattern stone = new Pattern.Single(1);
        BrushSpec nine = BrushSpec.shape(4, new ShapeSpec(ShapeSpec.Kind.CUBE, 9, Facing.UP, ShapeSpec.Mode.PLACE, 0), stone,
                0L, null, Symmetry.NONE);
        assertNull(EngineEditService.overBrushLimit(nine, 4));
        BrushSpec ten = BrushSpec.shape(4, new ShapeSpec(ShapeSpec.Kind.CUBE, 10, Facing.UP, ShapeSpec.Mode.PLACE, 0), stone,
                0L, null, Symmetry.NONE);
        assertEquals("shape height 10 > 9", EngineEditService.overBrushLimit(ten, 4));
        assertNull(EngineEditService.overBrushLimit(ten, 5));
        assertEquals("radius 5 > 4", EngineEditService.overBrushLimit(BrushSpec.shape(5, ten.shapeSpec(), stone, 0L, null,
                Symmetry.NONE), 4));
        BrushSpec raise = new BrushSpec(BrushTool.RAISE, 5, 1f, Falloff.CONSTANT, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0, 1L);
        assertEquals("radius 5 > 4", EngineEditService.overBrushLimit(raise, 4));
        assertNull(EngineEditService.overBrushLimit(raise, 32));
        BrushSpec tallest = BrushSpec.shape(32, new ShapeSpec(ShapeSpec.Kind.CYLINDER, ShapeSpec.MAX_HEIGHT, Facing.UP,
                ShapeSpec.Mode.CARVE, 0), null, 0L, null, Symmetry.NONE);
        assertNull(EngineEditService.overBrushLimit(tallest, 32), "the defaults allow every shape");
    }

    @Test
    void symmetryCentresMustLieInTheWorld() {
        int limit = 30_000_000; // World.HORIZONTAL_LIMIT
        assertTrue(EngineEditService.insideWorld(Symmetry.NONE));
        assertTrue(EngineEditService.insideWorld(new Symmetry(Symmetry.Mode.MIRROR_X, 2 * limit, -2 * limit)));
        assertTrue(EngineEditService.insideWorld(new Symmetry(Symmetry.Mode.ROTATE_4, 21, -7)));
        assertFalse(EngineEditService.insideWorld(new Symmetry(Symmetry.Mode.MIRROR_X, 2 * limit + 1, 0)));
        assertFalse(EngineEditService.insideWorld(new Symmetry(Symmetry.Mode.ROTATE_2, 0, -2 * limit - 1)));
    }
}
