package dev.sculptory.fabric.client.editor.tools.fluid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.region.CellSet;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import org.junit.jupiter.api.Test;

/** The flood search: connectivity, the level, the rim, the selection bound, the limit, unloaded chunks, bad seeds. */
class FluidFloodTest {
    private final FakeStateSpace states = new FakeStateSpace();
    private final int stone = states.state("minecraft:stone");
    private final int dryStairs = states.state("minecraft:oak_stairs[facing=east]");
    private final int wetStairs = states.state("minecraft:oak_stairs[facing=east,waterlogged=true]");
    private final int water = states.state("minecraft:water[level=0]");

    private static Box box(int x0, int y0, int z0, int x1, int y1, int z1) {
        return new Box(new BlockPos(x0, y0, z0), new BlockPos(x1, y1, z1));
    }

    /**
     * A stone basin: ground up to y 60, walls at x 0 and 9, z 0 and 9 up to y 64, so the interior (x, z 1-8, y 61-64) is
     * an air pocket open to the sky.
     */
    private FakeWorld basin() {
        FakeWorld world = new FakeWorld(states);
        world.fill(box(-40, 40, -40, 60, 60, 60), stone);
        world.fill(box(0, 61, 0, 9, 64, 0), stone);
        world.fill(box(0, 61, 9, 9, 64, 9), stone);
        world.fill(box(0, 61, 0, 0, 64, 9), stone);
        world.fill(box(9, 61, 0, 9, 64, 9), stone);
        return world;
    }

    private static FluidFlood run(FluidFlood flood) {
        while (!flood.step(256)) {
            // slices
        }
        return flood;
    }

    @Test
    void theFloodFillsTheConnectedAirAtOrBelowTheLevel() {
        FluidFlood flood = run(new FluidFlood(basin(), new BlockPos(1, 62, 1), 100_000, null, true));
        assertTrue(flood.done());
        assertEquals(62, flood.level());
        assertEquals(8 * 8 * 2, flood.count(), "two layers of the basin");
        assertFalse(flood.hitLimit());
        assertFalse(flood.hitUnloaded());
        assertTrue(flood.examined() > 0);
        CellSet cells = flood.cells();
        assertEquals(128, cells.size());
        assertTrue(cells.contains(8, 61, 8));
        assertTrue(cells.contains(4, 62, 4));
        assertFalse(cells.contains(4, 63, 4), "nothing above the level");
        assertFalse(cells.contains(0, 61, 4), "the wall is not air");
        assertFalse(cells.contains(10, 61, 4), "nothing outside the basin");
        assertEquals(box(1, 61, 1, 8, 62, 8), cells.bounds());
    }

    @Test
    void theRimIsCollectedButNotSearchedThrough() {
        FakeWorld world = basin();
        world.set(3, 61, 3, dryStairs);
        world.set(4, 61, 4, wetStairs);
        // A stair in the wall: waterlogged, but the search must not continue into the air beyond it.
        world.set(0, 61, 5, dryStairs);
        FluidFlood flood = run(new FluidFlood(world, new BlockPos(1, 62, 1), 100_000, null, true));
        assertEquals(128 - 2 + 2, flood.count(), "the pocket less the two stairs, plus the two dry stairs");
        assertTrue(flood.contains(3, 61, 3));
        assertTrue(flood.contains(0, 61, 5), "the wall's stair is on the rim");
        assertFalse(flood.contains(4, 61, 4), "already waterlogged: not written");
        assertFalse(flood.contains(-1, 61, 5), "no search through the rim");

        FluidFlood plain = run(new FluidFlood(world, new BlockPos(1, 62, 1), 100_000, null, false));
        assertEquals(126, plain.count(), "air only");
        assertFalse(plain.contains(3, 61, 3));
        assertFalse(plain.contains(0, 61, 5));
    }

    @Test
    void aSeedThatIsNotAirFindsNothing() {
        FakeWorld world = basin();
        world.set(4, 61, 4, water);
        assertEquals(0, run(new FluidFlood(world, new BlockPos(0, 61, 4), 100, null, true)).count(), "stone");
        assertEquals(0, run(new FluidFlood(world, new BlockPos(4, 61, 4), 100, null, true)).count(), "water");
        assertEquals(0, run(new FluidFlood(world, new BlockPos(4, 500, 4), 100, null, true)).count(), "above the world");
        FluidFlood stoneSeed = run(new FluidFlood(world, new BlockPos(0, 61, 4), 100, null, true));
        assertTrue(stoneSeed.done());
        assertEquals(0, stoneSeed.examined(), "nothing is examined for a seed that is not air");
        assertThrows(IllegalArgumentException.class, () -> new FluidFlood(world, new BlockPos(1, 61, 1), 0, null, true));
    }

    @Test
    void theSelectionBoundsTheFlood() {
        Box bounds = box(1, 61, 1, 4, 62, 8);
        FluidFlood flood = run(new FluidFlood(basin(), new BlockPos(1, 62, 1), 100_000, bounds, true));
        assertEquals(4 * 8 * 2, flood.count());
        assertFalse(flood.contains(5, 61, 1));
        assertFalse(flood.hitLimit());
        FluidFlood outside = run(new FluidFlood(basin(), new BlockPos(6, 62, 1), 100_000, bounds, true));
        assertEquals(0, outside.count(), "a seed outside the bounds finds nothing");
    }

    @Test
    void theLimitStopsTheFlood() {
        FluidFlood flood = run(new FluidFlood(basin(), new BlockPos(1, 62, 1), 10, null, true));
        assertTrue(flood.done());
        assertEquals(10, flood.count());
        assertTrue(flood.hitLimit());
        FluidFlood exact = run(new FluidFlood(basin(), new BlockPos(1, 61, 1), 64, null, true));
        assertEquals(64, exact.count());
        assertFalse(exact.hitLimit(), "exactly the limit with nothing more connected is not cut off");
    }

    @Test
    void unloadedChunksStopTheFlood() {
        FakeWorld world = new FakeWorld(states);
        world.fill(box(-8, 40, -8, 40, 60, 8), stone);
        world.setLoaded(1, 0, false);
        Box line = box(0, 61, 0, 30, 61, 0);
        FluidFlood flood = run(new FluidFlood(world, new BlockPos(0, 61, 0), 100_000, line, true));
        assertEquals(16, flood.count(), "up to the unloaded chunk");
        assertTrue(flood.hitUnloaded());
        assertFalse(flood.contains(16, 61, 0));
        FluidFlood seedUnloaded = run(new FluidFlood(world, new BlockPos(20, 61, 0), 100_000, line, true));
        assertEquals(0, seedUnloaded.count());
    }

    @Test
    void stepsAreBoundedAndResume() {
        FluidFlood flood = new FluidFlood(basin(), new BlockPos(1, 62, 1), 100_000, null, true);
        assertEquals(1, flood.count(), "the seed is collected at once");
        int steps = 0;
        while (!flood.step(1)) {
            steps++;
            assertTrue(steps < 10_000);
        }
        assertTrue(steps > 100, "one cell per step takes many steps");
        assertEquals(128, flood.count());
        assertTrue(flood.step(1), "done stays done");
    }
}
