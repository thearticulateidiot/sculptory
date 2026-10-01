package dev.sculptory.core.brush;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.edit.MixLayout;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.core.world.WorldReader;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Palette Paint and the Shape brush with a mix laid out in space: every
 * painted or placed cell holds the pattern's block at that cell, Steepness follows the ground's slope
 * ({@link ColumnSteepness}), a symmetric copy mirrors the pattern, reads stay inside the server's checked box, and a
 * Steepness pattern exists only for Palette Paint.
 */
class MixPatternBrushTest {
    private final BrushFixture f = new BrushFixture();
    private final int dirt = f.states.state("minecraft:dirt");
    private final int sand = f.states.state("minecraft:sand");
    private final int cobble = f.states.state("minecraft:cobblestone");
    private final Pattern.Weighted mix = new Pattern.Weighted(new int[] {dirt, sand, cobble}, new int[] {3, 2, 1}, 99L);

    private static BrushSpec palette(int radius, Pattern material, int depth) {
        return BrushFixture.paint(BrushTool.PALETTE, radius, 1f, material, depth, SurfaceMask.ANY);
    }

    /** Patches and a Gradient: every painted cell holds the pattern's block at that very cell, depth cells too. */
    @Test
    void palettePaintWritesThePatternsBlockAtEachCell() {
        for (MixLayout layout : new MixLayout[] {new MixLayout.Patches(4),
                new MixLayout.Gradient(new BlockPos(-10, 60, -3), new BlockPos(10, 60, 5), 3)}) {
            Pattern.Arranged pattern = new Pattern.Arranged(mix, layout);
            FakeWorld world = f.terrain((x, z) -> 60 + (x + z) / 6);
            List<BrushFixture.Write> writes = BrushFixture.dab(palette(8, pattern, 2), new StrokeState(), world,
                    BrushFixture.at(0, 0, 62, 0));
            assertTrue(writes.size() > 150, layout + " painted " + writes.size());
            for (BrushFixture.Write write : writes) {
                assertEquals(pattern.apply(write.x(), write.y(), write.z(), 0), write.state(), "at " + write);
            }
        }
    }

    /** The ground's steepness per column: flat 0°, a lone step about 17°, a 1:1 staircase 45°, a 20-block cliff 76-81°. */
    @Test
    void steepnessIsTheSlopeOfTheGroundAroundTheColumn() {
        StateSpace states = f.states;
        FakeWorld flat = f.flat(60);
        assertEquals(0, new ColumnSteepness(flat, states, 0, 0, 0, 0, 80, 40).degrees(0, 0), 1e-9);
        FakeWorld step = f.terrain((x, z) -> x > 0 ? 61 : 60);
        assertEquals(Math.toDegrees(Math.atan(0.3)), new ColumnSteepness(step, states, 0, 0, 0, 0, 80, 40).degrees(0, 0),
                1e-9);
        FakeWorld stairs = f.terrain((x, z) -> 60 + x / 2 + 10);
        FakeWorld diagonal = f.terrain((x, z) -> 70 + x);
        assertEquals(45, new ColumnSteepness(diagonal, states, 0, 0, 0, 0, 90, 40).degrees(0, 0), 1e-9);
        assertTrue(new ColumnSteepness(stairs, states, 0, 0, 0, 0, 90, 40).degrees(0, 0) < 30, "one up per two across");
        FakeWorld cliff = f.terrain((x, z) -> x <= 0 ? 84 : 64);
        ColumnSteepness atCliff = new ColumnSteepness(cliff, states, -3, 3, 0, 0, 95, 50);
        assertEquals(Math.toDegrees(Math.atan(6)), atCliff.degrees(0, 0), 1e-9);
        assertEquals(Math.toDegrees(Math.atan(6)), atCliff.degrees(1, 0), 1e-9);
        assertEquals(Math.toDegrees(Math.atan(4)), atCliff.degrees(-1, 0), 1e-9);
        assertEquals(Math.toDegrees(Math.atan(4)), atCliff.degrees(2, 0), 1e-9);
        assertEquals(0, atCliff.degrees(-3, 0), 1e-9);
        assertEquals(0, atCliff.degrees(3, 0), 1e-9);
    }

    /**
     * Palette Paint with Steepness over a plateau, a 1:1 slope and a cliff: dirt on the flats, sand on the slope,
     * cobblestone at the cliff's edge (weights 1, 1, 1: 0-30°, 30-60°, 60-90°, no edge).
     */
    @Test
    void palettePaintWithSteepnessFollowsTheSlope() {
        Pattern.Arranged pattern = new Pattern.Arranged(new Pattern.Weighted(new int[] {dirt, sand, cobble},
                new int[] {1, 1, 1}, 3L), new MixLayout.Steepness(0));
        // Flat at 60 for x < -12; a 1:1 slope up to 72 at x = 0; flat at 72 to x = 12; a cliff down to 55 after.
        FakeWorld world = f.terrain((x, z) -> x < -12 ? 60 : x <= 0 ? 72 + x : x <= 12 ? 72 : 55);
        StrokeState stroke = new StrokeState();
        for (int x = -24; x <= 24; x += 6) {
            BrushFixture.dab(palette(6, pattern, 1), stroke, world, BrushFixture.at(0, x, f.surface(world, x, 0) + 1, 0));
        }
        assertEquals(dirt, world.get(-20, 60, 0), "flat ground below the slope");
        assertEquals(sand, world.get(-6, 66, 0), "on the slope");
        assertEquals(dirt, world.get(6, 72, 0), "on the plateau");
        assertEquals(cobble, world.get(12, 72, 0), "at the cliff's edge");
        assertEquals(cobble, world.get(13, 55, 0), "at the cliff's foot");
        assertEquals(dirt, world.get(20, 55, 0), "flat ground below the cliff");
    }

    /**
     * Steepness reads the columns around the footprint up to two further, within the server's dab box (radius + 2), and
     * only the Steepness pattern reads them.
     */
    @Test
    void steepnessReadsStayInsideTheDabBox() {
        FakeWorld world = f.terrain((x, z) -> 60 + (x * x + z * z) / 40);
        int[] reach = {0, 0};
        WorldReader recording = new WorldReader() {
            @Override
            public StateSpace states() {
                return world.states();
            }

            @Override
            public int bottomY() {
                return world.bottomY();
            }

            @Override
            public int topYExclusive() {
                return world.topYExclusive();
            }

            @Override
            public boolean isLoaded(int cx, int cz) {
                return world.isLoaded(cx, cz);
            }

            @Override
            public int get(int x, int y, int z) {
                reach[0] = Math.max(reach[0], Math.abs(x - 5));
                reach[1] = Math.max(reach[1], Math.abs(z + 4));
                return world.get(x, y, z);
            }

            @Override
            public dev.sculptory.core.buffer.BlockEntityData tile(int x, int y, int z) {
                return world.tile(x, y, z);
            }

            @Override
            public void copySection(int sx, int sy, int sz, dev.sculptory.core.buffer.SectionBuffer into) {
                world.copySection(sx, sy, sz, into);
            }
        };
        for (int radius : new int[] {1, 2, 7}) {
            reach[0] = reach[1] = 0;
            Pattern steep = new Pattern.Arranged(mix, new MixLayout.Steepness(5));
            BrushKernels.forTool(BrushTool.PALETTE).apply(palette(radius, steep, 1), BrushFixture.at(0, 5, 62, -4),
                    new StrokeState(), recording, (x, y, z, h) -> { });
            assertEquals(radius + 2, reach[0], "x reach at radius " + radius);
            assertEquals(radius + 2, reach[1], "z reach at radius " + radius);
            reach[0] = reach[1] = 0;
            BrushKernels.forTool(BrushTool.PALETTE).apply(palette(radius, mix, 1), BrushFixture.at(0, 5, 62, -4),
                    new StrokeState(), recording, (x, y, z, h) -> { });
            assertEquals(radius + 1, reach[0], "a random mix reads as before at radius " + radius);
        }
    }

    /**
     * Mirror X: the copy's columns hold the pattern read at their mirror image, so over symmetric ground the painted
     * cells are exactly symmetric (Patches, a Gradient, Steepness); a Random mix stays unmirrored, as before.
     */
    @Test
    void aMirroredCopyMirrorsThePattern() {
        Symmetry mirror = new Symmetry(Symmetry.Mode.MIRROR_X, 1, 0);
        for (MixLayout layout : new MixLayout[] {new MixLayout.Patches(3),
                new MixLayout.Gradient(new BlockPos(4, 62, -6), new BlockPos(16, 62, 6), 2), new MixLayout.Steepness(8)}) {
            Pattern.Arranged pattern = new Pattern.Arranged(mix, layout);
            FakeWorld world = f.terrain((x, z) -> 60 + (Math.abs(x) + Math.abs(z)) / 3);
            BrushSpec spec = palette(5, pattern, 1).withSymmetry(mirror);
            List<BrushFixture.Write> writes = BrushFixture.dab(spec, new StrokeState(), world,
                    BrushFixture.at(0, 10, f.surface(world, 10, 0) + 1, 0));
            int painted = 0;
            for (int x = 5; x <= 15; x++) {
                for (int z = -5; z <= 5; z++) {
                    int top = f.surface(world, x, z);
                    assertEquals(top, f.surface(world, -x, z), "symmetric ground");
                    assertEquals(world.get(x, top, z), world.get(-x, top, z), layout + " at " + x + "," + z);
                    if (world.get(x, top, z) != f.grass) painted++;
                }
            }
            assertTrue(painted > 60 && writes.size() > 2 * 60, layout + ": " + painted);
        }
        // A Random mix is picked at each cell itself on both sides (unchanged from before patterns).
        FakeWorld world = f.flat(60);
        BrushFixture.dab(palette(5, mix, 1).withSymmetry(mirror), new StrokeState(), world, BrushFixture.at(0, 10, 61, 0));
        int same = 0;
        for (int x = 8; x <= 12; x++) {
            for (int z = -2; z <= 2; z++) {
                assertEquals(mix.apply(f.states, -x, 60, z, 0), world.get(-x, 60, z));
                if (world.get(x, 60, z) == world.get(-x, 60, z)) same++;
            }
        }
        assertTrue(same < 20, "random picks are not mirrored: " + same + " of 25 alike");
    }

    /**
     * The Shape brush places the pattern's block at each cell, and a Mirror X copy mirrors it: a Patches sphere and a
     * Gradient cube. A Random mix keeps picking at each cell.
     */
    @Test
    void theShapeBrushPlacesThePatternAndMirrorsItInCopies() {
        Symmetry mirror = new Symmetry(Symmetry.Mode.MIRROR_X, 1, 0);
        ShapeSpec sphere = new ShapeSpec(ShapeSpec.Kind.SPHERE, 9, Facing.UP, ShapeSpec.Mode.PLACE, 0);
        ShapeSpec cube = new ShapeSpec(ShapeSpec.Kind.CUBE, 7, Facing.UP, ShapeSpec.Mode.PLACE, 0);
        record Case(ShapeSpec shape, MixLayout layout) {}
        for (Case c : List.of(new Case(sphere, new MixLayout.Patches(2)),
                new Case(cube, new MixLayout.Gradient(new BlockPos(6, 70, 0), new BlockPos(14, 70, 0), 1)))) {
            Pattern.Arranged pattern = new Pattern.Arranged(mix, c.layout());
            FakeWorld world = new FakeWorld(f.states);
            BrushSpec spec = BrushSpec.shape(4, c.shape(), pattern, 1L, null, mirror);
            int[] written = {0};
            BrushKernels.forTool(BrushTool.SHAPE).apply(spec, BrushFixture.at(0, 10, 70, 0), new StrokeState(), world,
                    (x, y, z, h) -> {
                        world.set(x, y, z, h);
                        written[0]++;
                    });
            int original = 0;
            for (int x = 5; x <= 15; x++) {
                for (int y = 65; y <= 75; y++) {
                    for (int z = -5; z <= 5; z++) {
                        int here = world.get(x, y, z);
                        if (here == f.air) continue;
                        original++;
                        assertEquals(pattern.apply(x, y, z, 0), here, c.layout() + " at " + x + "," + y + "," + z);
                        assertEquals(here, world.get(-x, y, z), "the mirrored copy at " + -x + "," + y + "," + z);
                    }
                }
            }
            assertEquals(2 * original, written[0], c.layout() + ": the dab's shape and its copy");
        }
    }

    /** A Steepness mix exists only for Palette Paint: the Shape brush and Paint refuse it. */
    @Test
    void steepnessIsOnlyForPalettePaint() {
        Pattern steep = new Pattern.Arranged(mix, new MixLayout.Steepness(10));
        ShapeSpec sphere = new ShapeSpec(ShapeSpec.Kind.SPHERE, 9, Facing.UP, ShapeSpec.Mode.PAINT, 0);
        assertThrows(IllegalArgumentException.class, () -> BrushSpec.shape(4, sphere, steep, 1L, null, Symmetry.NONE));
        assertThrows(IllegalArgumentException.class,
                () -> BrushFixture.paint(BrushTool.PAINT, 4, 1f, steep, 1, SurfaceMask.ANY));
        palette(4, steep, 1);
        // Patches and a Gradient go anywhere a mix does.
        BrushSpec.shape(4, sphere, new Pattern.Arranged(mix, new MixLayout.Patches(3)), 1L, null, Symmetry.NONE);
    }

    /** A cliff taller than the scan window still reads as steep: the far side counts as the window's edge. */
    @Test
    void aCliffTallerThanTheScanWindowIsSteep() {
        FakeWorld cliff = f.terrain((x, z) -> x <= 0 ? 95 : 55);
        // A radius-2 dab on the top at 96: the window is 86-106, the foot (55) far below it.
        // The foot counts as 85: a 10-block drop, atan(3) = 71.6°.
        ColumnSteepness top = new ColumnSteepness(cliff, f.states, -2, 2, 0, 0, 106, 86);
        assertEquals(Math.toDegrees(Math.atan(3)), top.degrees(0, 0), 1e-9);
        // From the foot at 56: the window is 46-66, the top (95) counts as 66: an 11-block rise, atan(3.3) = 73.1°.
        ColumnSteepness foot = new ColumnSteepness(cliff, f.states, -2, 2, 0, 0, 66, 46);
        assertEquals(Math.toDegrees(Math.atan(3.3)), foot.degrees(1, 0), 1e-9);
    }
}
