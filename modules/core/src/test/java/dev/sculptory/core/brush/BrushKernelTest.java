package dev.sculptory.core.brush;

import static dev.sculptory.core.brush.BrushFixture.at;
import static dev.sculptory.core.brush.BrushFixture.dab;
import static dev.sculptory.core.brush.BrushFixture.masked;
import static dev.sculptory.core.brush.BrushFixture.spec;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.SplitMix64;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.testing.FakeWorld;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntBinaryOperator;
import org.junit.jupiter.api.Test;

class BrushKernelTest {
    private final BrushFixture f = new BrushFixture();

    // ---- Determinism ----

    /** Rolling terrain from a position hash: 58 to 64. */
    private static final IntBinaryOperator ROLLING = (x, z) -> 58
            + (int) Long.remainderUnsigned(SplitMix64.hash(3L, x >> 1, 0, z >> 1), 4)
            + (int) Long.remainderUnsigned(SplitMix64.hash(4L, x, 0, z), 2) + (Math.abs(x) + Math.abs(z)) % 2;

    private List<BrushFixture.Write> stroke(BrushSpec spec, FakeWorld world) {
        StrokeState stroke = new StrokeState();
        List<BrushFixture.Write> writes = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            // A curving path in 1/16 blocks, with varying pressure.
            Dab d = new Dab(i, -40 + i * 11, 62 * 16 + (i % 3) * 5, 11 - i * i, 120 + (i * 37) % 136);
            BrushKernels.forTool(spec.tool()).apply(spec, d, stroke, world, (x, y, z, h) -> {
                writes.add(new BrushFixture.Write(x, y, z, h));
                world.set(x, y, z, h);
            });
        }
        return writes;
    }

    @Test
    void sameStrokeTwiceGivesIdenticalOutput() {
        Pattern palette = new Pattern.Weighted(new int[] {f.stone, f.dirt, f.sand}, new int[] {3, 2, 1}, 9L);
        for (BrushTool tool : BrushTool.TERRAIN) {
            for (Falloff falloff : Falloff.values()) {
                BrushSpec spec = new BrushSpec(tool, 5, 0.73f, falloff, Shape.CIRCLE, palette, SurfaceMask.ANY, 2, 60, 5L);
                List<BrushFixture.Write> first = stroke(spec, f.terrain(ROLLING));
                // The second world is built in a different order, so its hash maps differ internally.
                FakeWorld reordered = new FakeWorld(f.states);
                for (int x = BrushFixture.EXTENT; x >= -BrushFixture.EXTENT; x--) {
                    for (int z = BrushFixture.EXTENT; z >= -BrushFixture.EXTENT; z--) {
                        int top = ROLLING.applyAsInt(x, z);
                        for (int y = top; y >= BrushFixture.FLOOR; y--) reordered.set(x, y, z, y == top ? f.grass : f.stone);
                    }
                }
                List<BrushFixture.Write> second = stroke(spec, reordered);
                assertEquals(first, second, tool + " " + falloff);
                assertFalse(first.isEmpty(), tool + " " + falloff + " changed nothing");
            }
        }
    }

    @Test
    void outputDoesNotDependOnWriteThrough() {
        for (BrushTool tool : BrushTool.TERRAIN) {
            BrushSpec spec = new BrushSpec(tool, 6, 1f, Falloff.SMOOTH, Shape.CIRCLE, new Pattern.Single(f.sand),
                    SurfaceMask.ANY, 1, 59, 0L);
            FakeWorld through = f.terrain(ROLLING);
            List<BrushFixture.Write> written = dab(spec, new StrokeState(), through, at(0, 0, 62, 0));
            List<BrushFixture.Write> buffered = new ArrayList<>();
            BrushKernels.forTool(tool).apply(spec, at(0, 0, 62, 0), new StrokeState(), f.terrain(ROLLING),
                    (x, y, z, h) -> buffered.add(new BrushFixture.Write(x, y, z, h)));
            assertEquals(written, buffered, tool.toString());
        }
    }

    @Test
    void sinkOrderIsColumnMajorAndYAscending() {
        List<BrushFixture.Write> writes = stroke(new BrushSpec(BrushTool.LOWER, 5, 1f, Falloff.CONSTANT, Shape.CIRCLE,
                null, SurfaceMask.ANY, 0, 0, 0L), f.terrain(ROLLING));
        // Within one dab, (x, z, y) never decreases; a dab's writes form one ascending run.
        int runs = 1;
        for (int i = 1; i < writes.size(); i++) {
            BrushFixture.Write a = writes.get(i - 1), b = writes.get(i);
            int order = a.x() != b.x() ? Integer.compare(a.x(), b.x())
                    : a.z() != b.z() ? Integer.compare(a.z(), b.z()) : Integer.compare(a.y(), b.y());
            if (order >= 0) runs++;
        }
        assertTrue(runs <= 14, "more ascending runs than dabs: " + runs);
    }

    // ---- Surface finding and skipping ----

    @Test
    void columnsWithoutASurfaceAreSkipped() {
        FakeWorld world = f.flat(60);
        world.set(1, 61, 0, f.stairs);                      // a structure on the ground
        for (int y = BrushFixture.FLOOR; y <= 60; y++) world.set(-1, y, 0, f.air);   // a shaft below the window
        for (int y = 61; y <= 80; y++) world.set(0, y, -1, f.stone);                  // a pillar above the window
        List<BrushFixture.Write> writes = dab(spec(BrushTool.RAISE, 1, 1f, Falloff.CONSTANT, Shape.SQUARE),
                new StrokeState(), world, at(0, 0, 61, 0));
        assertEquals(6, writes.size());
        assertEquals(f.stairs, world.get(1, 61, 0));
        assertEquals(f.air, world.get(-1, 61, 0));
        assertEquals(61, f.surface(world, 0, 0));
        assertEquals(61, f.surface(world, 1, 1));
    }

    @Test
    void unloadedChunksAreSkippedWithoutReading() {
        FakeWorld world = f.flat(60);
        for (int x = 16; x <= 20; x++) {
            for (int z = -3; z <= 3; z++) {
                for (int y = 56; y <= 60; y++) world.set(x, y, z, f.water);
            }
        }
        world.setLoaded(1, 0, false);
        world.setLoaded(1, -1, false);
        for (BrushTool tool : new BrushTool[] {BrushTool.RAISE, BrushTool.LOWER, BrushTool.SMOOTH}) {
            // FakeWorld throws on any read of an unloaded chunk, so this also proves nothing was read there.
            List<BrushFixture.Write> writes = dab(spec(tool, 2, 1f, Falloff.CONSTANT, Shape.SQUARE), new StrokeState(),
                    world, at(0, 15, 61, 0));
            for (BrushFixture.Write write : writes) assertTrue(write.x() <= 15, tool + " wrote " + write);
        }
        world.setLoaded(1, 0, true);
        world.setLoaded(1, -1, true);
        assertEquals(f.water, world.get(16, 60, 0));
    }

    @Test
    void vegetationIsSeenThroughAndClearedWhenTheGroundMoves() {
        FakeWorld world = f.flat(60);
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) world.set(x, 61, z, f.shortGrass);
        }
        world.set(0, 62, 0, f.shortGrass);   // a two-high plant
        dab(spec(BrushTool.RAISE, 1, 1f, Falloff.CONSTANT, Shape.SQUARE), new StrokeState(), world, at(0, 0, 62, 0));
        assertEquals(f.grass, world.get(0, 61, 0), "the plant's cell is raised ground now");
        assertEquals(f.air, world.get(0, 62, 0), "no half plant left on top");
        assertEquals(f.grass, world.get(1, 61, 1));
        assertEquals(f.shortGrass, world.get(2, 61, 0), "outside the brush");

        FakeWorld lower = f.flat(60);
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) lower.set(x, 61, z, f.shortGrass);
        }
        dab(spec(BrushTool.LOWER, 1, 1f, Falloff.CONSTANT, Shape.SQUARE), new StrokeState(), lower, at(0, 0, 62, 0));
        assertEquals(f.air, lower.get(0, 60, 0));
        assertEquals(f.air, lower.get(0, 61, 0), "no floating plant");
        assertEquals(59, f.surface(lower, 1, -1));
        assertEquals(f.shortGrass, lower.get(2, 61, 0));
    }

    @Test
    void raiseStopsAtTheBuildLimitAndUnderStructures() {
        FakeWorld world = new FakeWorld(f.states, 0, 64);
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) world.set(x, 62, z, f.grass);
        }
        BrushSpec spec = BrushFixture.flatten(1, 1f, Shape.SQUARE, 70);
        List<BrushFixture.Write> writes = dab(spec, new StrokeState(), world, at(0, 0, 63, 0));
        assertEquals(9, writes.size());
        for (BrushFixture.Write write : writes) assertEquals(63, write.y());

        // Above the scan window (58 + 1 + 8 = 67) a raise stops under a structure instead of replacing it.
        // Elsewhere it stops at the per-dab cap of radius + 8 = 9 blocks.
        FakeWorld built = f.flat(60);
        built.set(0, 68, 0, f.stairs);
        dab(BrushFixture.flatten(1, 1f, Shape.SQUARE, 80), new StrokeState(), built, at(0, 0, 58, 0));
        assertEquals(67, f.surface(built, 0, 0));
        assertEquals(f.stairs, built.get(0, 68, 0));
        assertEquals(69, f.surface(built, 1, 0));
    }

    // ---- Fluids ----

    /** Ground at {@code bank} for x <= 2; for x >= 3 a stone bed at 55 under water up to 60. */
    private FakeWorld shore(int bank) {
        FakeWorld world = f.terrain((x, z) -> x <= 2 ? bank : 55);
        for (int x = 3; x <= BrushFixture.EXTENT; x++) {
            for (int z = -BrushFixture.EXTENT; z <= BrushFixture.EXTENT; z++) {
                world.set(x, 55, z, f.stone);
                for (int y = 56; y <= 60; y++) world.set(x, y, z, f.water);
            }
        }
        return world;
    }

    @Test
    void loweringBelowTheWaterSurfaceRefills() {
        FakeWorld world = shore(60);
        List<BrushFixture.Write> writes = dab(spec(BrushTool.LOWER, 1, 1f, Falloff.CONSTANT, Shape.SQUARE),
                new StrokeState(), world, at(0, 1, 61, 0));
        // x = 2 touches the sea at y 60; x = 1 and x = 0 fill from the cells beside them.
        assertEquals(9, writes.size());
        for (BrushFixture.Write write : writes) assertEquals(f.water, write.state(), write.toString());
        assertEquals(59, f.surface(world, 0, 0));
    }

    @Test
    void loweringAboveTheWaterSurfaceLeavesAir() {
        FakeWorld world = shore(62);
        List<BrushFixture.Write> writes = dab(spec(BrushTool.LOWER, 1, 1f, Falloff.CONSTANT, Shape.SQUARE),
                new StrokeState(), world, at(0, 1, 63, 0));
        assertEquals(9, writes.size());
        for (BrushFixture.Write write : writes) assertEquals(f.air, write.state(), write.toString());
    }

    @Test
    void loweringALakeBedLeavesNoAirPocket() {
        FakeWorld world = shore(60);
        dab(spec(BrushTool.LOWER, 1, 1f, Falloff.CONSTANT, Shape.SQUARE), new StrokeState(), world, at(0, 6, 57, 0));
        for (int x = 5; x <= 7; x++) {
            for (int z = -1; z <= 1; z++) {
                assertEquals(f.water, world.get(x, 55, z));
                assertEquals(54, f.surface(world, x, z));
            }
        }
    }

    @Test
    void waterloggedNeighbourCountsAsWater() {
        FakeWorld world = f.flat(60);
        int loggedStairs = f.states.state("minecraft:oak_stairs[waterlogged=true]");
        world.set(2, 60, 0, loggedStairs);   // a structure: its own column is skipped
        List<BrushFixture.Write> writes = dab(spec(BrushTool.LOWER, 1, 1f, Falloff.CONSTANT, Shape.CIRCLE),
                new StrokeState(), world, at(0, 1, 61, 0));
        // (1, 60, 0) touches the stairs; the other removed cells fill from it.
        assertEquals(4, writes.size());
        for (BrushFixture.Write write : writes) assertEquals(f.water, write.state(), write.toString());
        assertEquals(loggedStairs, world.get(2, 60, 0));
    }

    @Test
    void deepFlattenFillsTheWholeCutBelowWater() {
        FakeWorld world = shore(60);
        List<BrushFixture.Write> writes = dab(BrushFixture.flatten(1, 1f, Shape.SQUARE, 57), new StrokeState(), world,
                at(0, 1, 61, 0));
        assertEquals(27, writes.size());
        for (BrushFixture.Write write : writes) assertEquals(f.water, write.state(), write.toString());
    }

    // ---- Falloff ----

    @Test
    void falloffIsMonotone() {
        for (Falloff falloff : Falloff.values()) {
            double previous = TerrainKernel.falloff(falloff, 0);
            assertEquals(1.0, previous, falloff.toString());
            for (int step = 1; step <= 256; step++) {
                double weight = TerrainKernel.falloff(falloff, step / 256.0);
                assertTrue(weight <= previous, falloff + " rises at " + step);
                assertTrue(weight >= 0, falloff + " negative at " + step);
                previous = weight;
            }
        }
        assertEquals(0.0, TerrainKernel.falloff(Falloff.LINEAR, 1), 0);
        assertEquals(0.5, TerrainKernel.falloff(Falloff.SMOOTH, 0.5), 1e-12);
    }

    @Test
    void displacementFallsOffWithDistance() {
        for (Falloff falloff : Falloff.values()) {
            for (Shape shape : Shape.values()) {
                StrokeState stroke = new StrokeState();
                FakeWorld world = f.flat(60);
                assertTrue(dab(new BrushSpec(BrushTool.RAISE, 8, 0.9f, falloff, shape, null, SurfaceMask.ANY, 0, 0, 0L),
                        stroke, world, at(0, 0, 61, 0)).isEmpty());
                int previous = stroke.accumulator(0, 0);
                assertEquals((int) (0.9f * (double) StrokeState.ONE + 0.5), previous);
                for (int d = 1; d <= 8; d++) {
                    int along = stroke.accumulator(d, 0), diagonal = stroke.accumulator(d, d);
                    assertTrue(along <= previous, falloff + " " + shape + " at " + d);
                    assertTrue(diagonal <= along, falloff + " " + shape + " diagonal at " + d);
                    previous = along;
                }
            }
        }
    }

    // ---- Masks ----

    @Test
    void surfaceBlockMask() {
        FakeWorld world = f.flat(60);
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) if ((x + z) % 2 == 0) world.set(x, 60, z, f.dirt);
        }
        SurfaceMask dirtOnly = new SurfaceMask.SurfaceBlocks(new CellMask.Blocks(List.of(new NamespacedId("minecraft:dirt"))));
        dab(BrushFixture.paint(BrushTool.PAINT, 2, 1f, new Pattern.Single(f.sand), 1, dirtOnly), new StrokeState(),
                world, at(0, 0, 61, 0));
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                if (x * x + z * z > 4) continue;
                assertEquals((x + z) % 2 == 0 ? f.sand : f.grass, world.get(x, 60, z), x + "," + z);
            }
        }
    }

    @Test
    void elevationSlopeAndLogicMasks() {
        IntBinaryOperator ridges = (x, z) -> Math.floorMod(x, 2) == 0 ? 60 : 62;
        SurfaceMask high = new SurfaceMask.Elevation(61, 70);
        assertEquals(raisedColumns(ridges, high), columns(ridges, (x, z) -> ridges.applyAsInt(x, z) == 62));
        assertEquals(raisedColumns(ridges, new SurfaceMask.Not(high)),
                columns(ridges, (x, z) -> ridges.applyAsInt(x, z) == 60));

        IntBinaryOperator step = (x, z) -> x >= 1 ? 64 : 60;
        SurfaceMask flat = new SurfaceMask.Slope(0, 0);
        assertEquals(raisedColumns(step, flat), columns(step, (x, z) -> x != 0 && x != 1));
        assertEquals(raisedColumns(step, new SurfaceMask.Slope(4, 4)), columns(step, (x, z) -> x == 0 || x == 1));

        // Or via De Morgan: flat columns, or the step's upper side.
        SurfaceMask upper = new SurfaceMask.Elevation(64, 64);
        SurfaceMask either = new SurfaceMask.Not(new SurfaceMask.And(List.of(new SurfaceMask.Not(flat), new SurfaceMask.Not(upper))));
        assertEquals(raisedColumns(step, either), columns(step, (x, z) -> x != 0));
        SurfaceMask both = new SurfaceMask.And(List.of(flat, upper));
        assertEquals(raisedColumns(step, both), columns(step, (x, z) -> x >= 2));
    }

    /** The columns (as "x,z") a full-strength radius-3 raise changes under {@code mask}. */
    private List<String> raisedColumns(IntBinaryOperator height, SurfaceMask mask) {
        FakeWorld world = f.terrain(height);
        List<String> raised = new ArrayList<>();
        for (BrushFixture.Write write : dab(masked(BrushTool.RAISE, 3, mask), new StrokeState(), world, at(0, 0, 62, 0))) {
            raised.add(write.x() + "," + write.z());
        }
        return raised;
    }

    private static List<String> columns(IntBinaryOperator height, java.util.function.BiPredicate<Integer, Integer> keep) {
        List<String> expected = new ArrayList<>();
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                if (x * x + z * z <= 9 && keep.test(x, z)) expected.add(x + "," + z);
            }
        }
        return expected;
    }

    // ---- Edge cases and refusals ----

    @Test
    void zeroPressureOrStrengthDoesNothing() {
        FakeWorld world = f.flat(60);
        BrushSpec spec = spec(BrushTool.RAISE, 4, 1f, Falloff.CONSTANT, Shape.CIRCLE);
        StrokeState stroke = new StrokeState();
        assertTrue(dab(spec, stroke, world, new Dab(0, 8, 61 * 16, 8, 0)).isEmpty());
        assertTrue(dab(spec(BrushTool.RAISE, 4, 0f, Falloff.CONSTANT, Shape.CIRCLE), stroke, world, at(1, 0, 61, 0)).isEmpty());
        assertEquals(0, stroke.trackedColumns());
    }

    @Test
    void paintingWithTheSameBlockWritesNothing() {
        FakeWorld world = f.flat(60);
        assertTrue(dab(BrushFixture.paint(BrushTool.PAINT, 3, 1f, new Pattern.Single(f.grass), 1, SurfaceMask.ANY),
                new StrokeState(), world, at(0, 0, 61, 0)).isEmpty());
    }

    @Test
    void paintHalfStrengthCoversOnTheSecondDab() {
        FakeWorld world = f.flat(60);
        BrushSpec spec = BrushFixture.paint(BrushTool.PAINT, 1, 0.5f, new Pattern.Single(f.sand), 1, SurfaceMask.ANY);
        StrokeState stroke = new StrokeState();
        assertTrue(dab(spec, stroke, world, at(0, 0, 61, 0)).isEmpty());
        assertEquals(5, dab(spec, stroke, world, at(1, 0, 61, 0)).size());
    }

    @Test
    void largestRadiusCoversTheWholeDisc() {
        FakeWorld world = f.flat(60);
        List<BrushFixture.Write> writes = dab(spec(BrushTool.RAISE, 32, 1f, Falloff.CONSTANT, Shape.CIRCLE),
                new StrokeState(), world, at(0, 0, 61, 0));
        assertEquals(3209, writes.size());   // lattice points with x² + z² <= 32²
    }

    @Test
    void resetForgetsAccumulators() {
        FakeWorld world = f.flat(60);
        StrokeState stroke = new StrokeState();
        dab(spec(BrushTool.RAISE, 2, 0.5f, Falloff.CONSTANT, Shape.CIRCLE), stroke, world, at(0, 0, 61, 0));
        assertEquals(13, stroke.trackedColumns());
        stroke.reset();
        assertEquals(0, stroke.trackedColumns());
        assertTrue(dab(spec(BrushTool.RAISE, 2, 0.5f, Falloff.CONSTANT, Shape.CIRCLE), stroke, world, at(1, 0, 61, 0)).isEmpty());
    }

    @Test
    void refusesMismatchedToolsBadMaterialsAndFarDabs() {
        FakeWorld world = f.flat(60);
        CellSink ignore = (x, y, z, h) -> {};
        BrushSpec raise = spec(BrushTool.RAISE, 2, 1f, Falloff.CONSTANT, Shape.CIRCLE);
        assertThrows(IllegalArgumentException.class,
                () -> BrushKernels.forTool(BrushTool.LOWER).apply(raise, at(0, 0, 61, 0), new StrokeState(), world, ignore));
        BrushSpec badPaint = BrushFixture.paint(BrushTool.PAINT, 2, 1f, new Pattern.Single(f.states.size()), 1, SurfaceMask.ANY);
        assertThrows(IllegalArgumentException.class,
                () -> BrushKernels.forTool(BrushTool.PAINT).apply(badPaint, at(0, 0, 61, 0), new StrokeState(), world, ignore));
        assertThrows(IllegalArgumentException.class, () -> BrushKernels.forTool(BrushTool.RAISE)
                .apply(raise, new Dab(0, Integer.MAX_VALUE, 0, 0, 255), new StrokeState(), world, ignore));
        BrushSpec badMask = masked(BrushTool.RAISE, 2,
                new SurfaceMask.SurfaceBlocks(new CellMask.States(new int[] {f.states.size()})));
        assertThrows(IllegalArgumentException.class,
                () -> BrushKernels.forTool(BrushTool.RAISE).apply(badMask, at(0, 0, 61, 0), new StrokeState(), world, ignore));
        assertThrows(NullPointerException.class, () -> BrushKernels.forTool(null));
    }
}
