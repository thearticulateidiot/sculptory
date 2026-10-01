package dev.sculptory.core.brush;

import static dev.sculptory.core.brush.BrushFixture.at;
import static dev.sculptory.core.brush.BrushFixture.dab;
import static dev.sculptory.core.brush.BrushFixture.grid;
import static dev.sculptory.core.brush.BrushFixture.spec;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.testing.FakeWorld;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Golden heightfields for each tool on small terrains. Every expected grid was worked out by hand from
 * the kernel rules (see {@code TerrainKernel}); the comments give the arithmetic. Grids list surface
 * heights minus the base level, one line per z, x ascending.
 */
class BrushGoldenTest {
    private final BrushFixture f = new BrushFixture();

    @Test
    void raiseConstantCircle() {
        FakeWorld world = f.flat(60);
        List<BrushFixture.Write> writes = dab(spec(BrushTool.RAISE, 2, 1f, Falloff.CONSTANT, Shape.CIRCLE),
                new StrokeState(), world, at(0, 0, 61, 0));
        // Columns with dx² + dz² <= 4 rise by one whole block.
        assertEquals(grid(
                "0 0 0 0 0 0 0",
                "0 0 0 1 0 0 0",
                "0 0 1 1 1 0 0",
                "0 1 1 1 1 1 0",
                "0 0 1 1 1 0 0",
                "0 0 0 1 0 0 0",
                "0 0 0 0 0 0 0"), f.heights(world, 0, 0, 3, 60));
        assertEquals(13, writes.size());
        for (BrushFixture.Write write : writes) {
            assertEquals(61, write.y());
            assertEquals(f.grass, write.state(), "raise copies the surface state");
        }
    }

    @Test
    void raiseLinearAccumulatesFractions() {
        FakeWorld world = f.flat(60);
        BrushSpec spec = spec(BrushTool.RAISE, 2, 1f, Falloff.LINEAR, Shape.CIRCLE);
        StrokeState stroke = new StrokeState();
        for (int i = 0; i < 4; i++) dab(spec, stroke, world, at(i, 0, 61, 0));
        // Per dab: centre k = 1; distance 1 (t = 0.5) k = 0.5; distance √2 k = 1 - 0.7071 = 0.2929
        // (19195/65536); distance 2 k = 0. After four dabs: 4, 2, 1 (1.17 blocks) and 0.
        assertEquals(grid(
                "0 0 0 0 0",
                "0 1 2 1 0",
                "0 2 4 2 0",
                "0 1 2 1 0",
                "0 0 0 0 0"), f.heights(world, 0, 0, 2, 60));
        assertEquals(4 * 19195 - StrokeState.ONE, stroke.accumulator(1, 1));
        assertEquals(0, stroke.accumulator(0, 0));
        assertEquals(0, stroke.accumulator(2, 0));
    }

    @Test
    void raiseSquareCoversTheCorners() {
        FakeWorld world = f.flat(60);
        dab(spec(BrushTool.RAISE, 1, 1f, Falloff.CONSTANT, Shape.SQUARE), new StrokeState(), world, at(0, 5, 61, -5));
        assertEquals(grid(
                "0 0 0 0 0",
                "0 1 1 1 0",
                "0 1 1 1 0",
                "0 1 1 1 0",
                "0 0 0 0 0"), f.heights(world, 5, -5, 2, 60));
    }

    @Test
    void lowerConstantCircle() {
        FakeWorld world = f.flat(60);
        List<BrushFixture.Write> writes = dab(spec(BrushTool.LOWER, 2, 1f, Falloff.CONSTANT, Shape.CIRCLE),
                new StrokeState(), world, at(0, 0, 61, 0));
        assertEquals(grid(
                "0 0 0 0 0 0 0",
                "0 0 0 -1 0 0 0",
                "0 0 -1 -1 -1 0 0",
                "0 -1 -1 -1 -1 -1 0",
                "0 0 -1 -1 -1 0 0",
                "0 0 0 -1 0 0 0",
                "0 0 0 0 0 0 0"), f.heights(world, 0, 0, 3, 60));
        assertEquals(13, writes.size());
        for (BrushFixture.Write write : writes) assertEquals(f.air, write.state());
    }

    @Test
    void lowerHalfStrengthNeedsTwoDabs() {
        FakeWorld world = f.flat(60);
        BrushSpec spec = spec(BrushTool.LOWER, 1, 0.5f, Falloff.CONSTANT, Shape.SQUARE);
        StrokeState stroke = new StrokeState();
        assertTrue(dab(spec, stroke, world, at(0, 0, 61, 0)).isEmpty());
        assertEquals(-StrokeState.ONE / 2, stroke.accumulator(0, 0));
        assertEquals(9, dab(spec, stroke, world, at(1, 0, 61, 0)).size());
        assertEquals(0, stroke.accumulator(0, 0));
        assertEquals(59, f.surface(world, 1, 1));
    }

    @Test
    void flattenFullStrengthLevelsInOneDab() {
        FakeWorld world = hillAndPit();
        dab(BrushFixture.flatten(3, 1f, Shape.SQUARE, 60), new StrokeState(), world, at(0, 0, 64, 0));
        assertEquals(grid(
                "0 0 0 0 0 0 0 0 0",
                "0 0 0 0 0 0 0 0 0",
                "0 0 0 0 0 0 0 0 0",
                "0 0 0 0 0 0 0 0 0",
                "0 0 0 0 0 0 0 0 0",
                "0 0 0 0 0 0 0 0 0",
                "0 0 0 0 0 0 0 0 0",
                "0 0 0 0 0 0 0 0 0",
                "0 0 0 0 0 0 0 0 0"), f.heights(world, 0, 0, 4, 60));
    }

    @Test
    void flattenHalfStrengthApproachesTheTarget() {
        FakeWorld world = hillAndPit();
        BrushSpec spec = BrushFixture.flatten(3, 0.5f, Shape.SQUARE, 60);
        StrokeState stroke = new StrokeState();
        // Each dab moves k × (flattenY - y) = half the remaining distance, carrying fractions.
        // Dab 1: centre 3 → -1.5 (moves 1), ring 1 (+2) → -1, ring 2 (+1) → -0.5 (none), pit (-2) → +1.
        dab(spec, stroke, world, at(0, 0, 64, 0));
        assertEquals(grid(
                "0 0 0 0 0 0 0 0 0",
                "0 0 0 0 0 0 0 -1 0",
                "0 0 1 1 1 1 1 0 0",
                "0 0 1 1 1 1 1 0 0",
                "0 0 1 1 2 1 1 0 0",
                "0 0 1 1 1 1 1 0 0",
                "0 0 1 1 1 1 1 0 0",
                "0 0 0 0 0 0 0 0 0",
                "0 0 0 0 0 0 0 0 0"), f.heights(world, 0, 0, 4, 60));
        // Dab 2: centre -0.5 - 1.0 → moves 1; ring 1 -0.5 → none; ring 2 -0.5 - 0.5 → reaches 60;
        // pit +0.5 → none. Dab 3 then brings the centre, ring 1 and the pit to 60 exactly.
        dab(spec, stroke, world, at(1, 0, 64, 0));
        assertEquals(grid(
                "0 0 0 0 0 0 0 0 0",
                "0 0 0 0 0 0 0 -1 0",
                "0 0 0 0 0 0 0 0 0",
                "0 0 0 1 1 1 0 0 0",
                "0 0 0 1 1 1 0 0 0",
                "0 0 0 1 1 1 0 0 0",
                "0 0 0 0 0 0 0 0 0",
                "0 0 0 0 0 0 0 0 0",
                "0 0 0 0 0 0 0 0 0"), f.heights(world, 0, 0, 4, 60));
        dab(spec, stroke, world, at(2, 0, 64, 0));
        assertEquals(f.heights(f.flat(60), 0, 0, 4, 60), f.heights(world, 0, 0, 4, 60));
        assertEquals(f.stone, world.get(3, 60, -3), "the pit fills with its own surface state");
        assertEquals(0, stroke.trackedColumns(), "every column reached the target");
    }

    @Test
    void smoothRemovesASpike() {
        FakeWorld world = f.flat(60);
        f.column(world, 0, 0, 64);
        dab(spec(BrushTool.SMOOTH, 2, 1f, Falloff.CONSTANT, Shape.SQUARE), new StrokeState(), world, at(0, 0, 61, 0));
        // Spike: 3×3 mean offset (8 × -4) / 9 = -3.56 → -4. Its neighbours: +4 / 9 = 0.44 → 0.
        assertEquals(f.heights(f.flat(60), 0, 0, 3, 60), f.heights(world, 0, 0, 3, 60));
        assertEquals(f.air, world.get(0, 61, 0));
    }

    @Test
    void smoothSoftensAStep() {
        FakeWorld world = f.terrain((x, z) -> x >= 1 ? 64 : 60);
        dab(spec(BrushTool.SMOOTH, 2, 1f, Falloff.CONSTANT, Shape.SQUARE), new StrokeState(), world, at(0, 0, 61, 0));
        // x = 0: 3×3 offsets sum 3 × 4 = 12 over 9 → 1.33 → +1. x = 1: -12 / 9 → -1. Others see a flat 3×3.
        assertEquals(grid(
                "0 0 0 0 4 4 4",
                "0 0 0 1 3 4 4",
                "0 0 0 1 3 4 4",
                "0 0 0 1 3 4 4",
                "0 0 0 1 3 4 4",
                "0 0 0 1 3 4 4",
                "0 0 0 0 4 4 4"), f.heights(world, 0, 0, 3, 60));
    }

    @Test
    void paintReplacesTheTopLayers() {
        FakeWorld world = f.flat(60);
        List<BrushFixture.Write> writes = dab(BrushFixture.paint(BrushTool.PAINT, 2, 1f, new Pattern.Single(f.sand), 3,
                SurfaceMask.ANY), new StrokeState(), world, at(0, 0, 61, 0));
        assertEquals(39, writes.size());
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                boolean inside = x * x + z * z <= 4;
                for (int y = 58; y <= 60; y++) {
                    int expected = inside ? f.sand : y == 60 ? f.grass : f.stone;
                    assertEquals(expected, world.get(x, y, z), x + "," + y + "," + z);
                }
                assertEquals(f.stone, world.get(x, 57, z));
            }
        }
        assertEquals(f.heights(f.flat(60), 0, 0, 3, 60), f.heights(world, 0, 0, 3, 60), "paint never moves the surface");
    }

    @Test
    void paletteFollowsTheWeightedPattern() {
        FakeWorld world = f.flat(60);
        Pattern palette = new Pattern.Weighted(new int[] {f.stone, f.dirt, f.sand}, new int[] {1, 1, 2}, 42L);
        dab(BrushFixture.paint(BrushTool.PALETTE, 3, 1f, palette, 1, SurfaceMask.ANY), new StrokeState(), world,
                at(0, 0, 61, 0));
        Set<Integer> used = new HashSet<>();
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                int expected = x * x + z * z <= 9 ? palette.apply(world.states(), x, 60, z, f.grass) : f.grass;
                assertEquals(expected, world.get(x, 60, z), x + "," + z);
                assertEquals(f.stone, world.get(x, 59, z));
                used.add(world.get(x, 60, z));
            }
        }
        assertTrue(used.contains(f.stone) && used.contains(f.dirt) && used.contains(f.sand), "all entries picked");
    }

    /**
     * Flat at 60 with a stepped hill (63 at the centre, 62 for Chebyshev ring 1, 61 for ring 2) and a
     * two-deep pit at (3, -3) whose floor is stone.
     */
    private FakeWorld hillAndPit() {
        FakeWorld world = f.terrain((x, z) -> 60 + Math.max(0, 3 - Math.max(Math.abs(x), Math.abs(z))));
        world.set(3, 60, -3, f.air);
        world.set(3, 59, -3, f.air);
        return world;
    }
}
