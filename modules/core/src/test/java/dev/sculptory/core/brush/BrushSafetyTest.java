package dev.sculptory.core.brush;

import static dev.sculptory.core.brush.BrushFixture.at;
import static dev.sculptory.core.brush.BrushFixture.dab;
import static dev.sculptory.core.brush.BrushFixture.spec;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.core.world.WorldReader;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Bounded work, hostile inputs, fluids and block entities. */
class BrushSafetyTest {
    private static final Duration FAST = Duration.ofSeconds(5);

    private final BrushFixture f = new BrushFixture();

    // ---- Extreme targets and the per-dab cap ----

    @Test
    void flattenToIntegerMinValueCompletesWithBoundedOutput() {
        FakeWorld world = f.flat(64);
        List<BrushFixture.Write> writes = assertTimeoutPreemptively(FAST, () -> dab(
                BrushFixture.flatten(2, 1f, Shape.CIRCLE, Integer.MIN_VALUE), new StrokeState(), world, at(0, 0, 65, 0)));
        // flattenY clamps to -64; the move caps at radius + 8 = 10 blocks: 13 columns × 10 cells.
        assertEquals(130, writes.size());
        for (BrushFixture.Write write : writes) assertEquals(f.air, write.state());
        assertEquals(54, f.surface(world, 0, 0));
    }

    @Test
    void flattenToIntegerMaxValueCompletesWithBoundedOutput() {
        FakeWorld world = f.flat(64);
        List<BrushFixture.Write> writes = assertTimeoutPreemptively(FAST, () -> dab(
                BrushFixture.flatten(2, 1f, Shape.CIRCLE, Integer.MAX_VALUE), new StrokeState(), world, at(0, 0, 65, 0)));
        assertEquals(130, writes.size());
        assertEquals(74, f.surface(world, 1, 1));
    }

    @Test
    void oneRadius32DabMovesEachColumnAtMostRadiusPlus8() {
        FakeWorld world = f.flat(60);
        StrokeState stroke = new StrokeState();
        List<BrushFixture.Write> writes = assertTimeoutPreemptively(FAST, () -> dab(
                BrushFixture.flatten(32, 1f, Shape.CIRCLE, 319), stroke, world, at(0, 0, 61, 0)));
        assertEquals(3209 * 40, writes.size());
        assertEquals(100, f.surface(world, 0, 0));
        assertEquals(100, f.surface(world, 32, 0));
        assertEquals(60, f.surface(world, 33, 0));
        assertEquals(0, stroke.trackedColumns(), "a capped move drops its fraction");
    }

    // ---- Fluids ----

    /** Ground at 60 for x <= 2; beyond, a bed at 55, source water 56-59 and {@code topWater} at 60. */
    private FakeWorld shore(int topWater) {
        FakeWorld world = f.terrain((x, z) -> x <= 2 ? 60 : 55);
        for (int x = 3; x <= 10; x++) {
            for (int z = -5; z <= 5; z++) {
                world.set(x, 55, z, f.stone);
                for (int y = 56; y <= 59; y++) world.set(x, y, z, f.water);
                world.set(x, 60, z, topWater);
            }
        }
        return world;
    }

    @Test
    void flowingAndFallingWaterDoNotRefill() {
        for (String level : new String[] {"3", "8"}) {
            FakeWorld world = shore(f.states.state("minecraft:water[level=" + level + "]"));
            List<BrushFixture.Write> writes = dab(spec(BrushTool.LOWER, 1, 1f, Falloff.CONSTANT, Shape.SQUARE),
                    new StrokeState(), world, at(0, 1, 61, 0));
            assertEquals(9, writes.size());
            for (BrushFixture.Write write : writes) assertEquals(f.air, write.state(), "level " + level + " " + write);
        }
        FakeWorld sourced = shore(f.water);
        for (BrushFixture.Write write : dab(spec(BrushTool.LOWER, 1, 1f, Falloff.CONSTANT, Shape.SQUARE),
                new StrokeState(), sourced, at(0, 1, 61, 0))) {
            assertEquals(f.water, write.state(), write.toString());
        }
    }

    // ---- Block entities ----

    /**
     * Flat grass at 60 in a state space where chests are solid block entities (like barrels), with one as a
     * surface block at (1, 60, 0), one standing on the ground at (-1, 61, 0) and one buried at (0, 59, 0).
     */
    private FakeWorld barrels() {
        FakeWorld world = new FakeWorld(new BarrelStateSpace(f.states));
        for (int x = -6; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                for (int y = BrushFixture.FLOOR; y <= 60; y++) world.set(x, y, z, y == 60 ? f.grass : f.stone);
            }
        }
        int barrel = f.states.state("minecraft:chest");
        world.set(1, 60, 0, barrel);
        world.set(-1, 61, 0, barrel);
        world.set(0, 59, 0, barrel);
        return world;
    }

    private void assertBarrelsIntact(FakeWorld world) {
        int barrel = f.states.state("minecraft:chest");
        assertEquals(barrel, world.get(1, 60, 0));
        assertEquals(barrel, world.get(-1, 61, 0));
        assertEquals(barrel, world.get(0, 59, 0));
    }

    @Test
    void blockEntitiesAreStructures() {
        FakeWorld raised = barrels();
        List<BrushFixture.Write> raise = dab(spec(BrushTool.RAISE, 1, 1f, Falloff.CONSTANT, Shape.SQUARE),
                new StrokeState(), raised, at(0, 0, 61, 0));
        assertEquals(7, raise.size(), "the two columns topped by barrels are skipped");
        assertBarrelsIntact(raised);

        FakeWorld flattened = barrels();
        List<BrushFixture.Write> flatten = dab(BrushFixture.flatten(1, 1f, Shape.SQUARE, 57), new StrokeState(),
                flattened, at(0, 0, 61, 0));
        assertEquals(6 * 3 + 1, flatten.size(), "lowering (0, 0) stops at the buried barrel");
        assertBarrelsIntact(flattened);

        FakeWorld painted = barrels();
        BrushSpec paint = new BrushSpec(BrushTool.PAINT, 1, 1f, Falloff.CONSTANT, Shape.SQUARE,
                new Pattern.Single(f.sand), SurfaceMask.ANY, 3, 0, 0L);
        assertEquals(6 * 3 + 1, dab(paint, new StrokeState(), painted, at(0, 0, 61, 0)).size());
        assertBarrelsIntact(painted);
    }

    // ---- Cost ----

    /** Counts world reads, for budgeting the server's brush lane. */
    private static final class CountingReader implements WorldReader {
        private final WorldReader world;
        long gets;

        CountingReader(WorldReader world) {
            this.world = world;
        }

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
            gets++;
            return world.get(x, y, z);
        }

        @Override
        public BlockEntityData tile(int x, int y, int z) {
            return world.tile(x, y, z);
        }

        @Override
        public void copySection(int sx, int sy, int sz, SectionBuffer into) {
            world.copySection(sx, sy, sz, into);
        }
    }

    @Test
    void scanCostOfARadius32DabOnFlatGround() {
        // Grid: 67 × 67 columns. Each scans from y 101 (61 + 32 + 8) down to the surface at 60: 42 reads.
        CountingReader scanOnly = new CountingReader(f.flat(60));
        BrushKernels.forTool(BrushTool.RAISE).apply(spec(BrushTool.RAISE, 32, 0f, Falloff.CONSTANT, Shape.CIRCLE),
                at(0, 0, 61, 0), new StrokeState(), scanOnly, (x, y, z, h) -> {});
        assertEquals(67L * 67 * 42, scanOnly.gets);

        // A full-strength raise adds one read per footprint column (the cell it raises into).
        CountingReader raise = new CountingReader(f.flat(60));
        long start = System.nanoTime();
        BrushKernels.forTool(BrushTool.RAISE).apply(spec(BrushTool.RAISE, 32, 1f, Falloff.CONSTANT, Shape.CIRCLE),
                at(0, 0, 61, 0), new StrokeState(), raise, (x, y, z, h) -> {});
        long nanos = System.nanoTime() - start;
        assertEquals(67L * 67 * 42 + 3209, raise.gets);
        System.out.printf("radius-32 raise dab on flat ground: %d world reads (%d scan), %.1f ms over FakeWorld%n",
                raise.gets, scanOnly.gets, nanos / 1e6);
        assertTrue(nanos < FAST.toNanos());
    }
}
