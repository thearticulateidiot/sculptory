package dev.sculptory.fabric.client.editor.tools.fluid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import org.junit.jupiter.api.Test;

/** The drain search: the connected body of one fluid, waterlogged blocks and water plants, bounds, limits, bad seeds. */
class FluidDrainTest {
    private final FakeStateSpace states = new FakeStateSpace();
    private final int stone = states.state("minecraft:stone");
    private final int water = states.state("minecraft:water[level=0]");
    private final int flowing = states.state("minecraft:water[level=4]");
    private final int lava = states.state("minecraft:lava[level=0]");
    private final int wetStairs = states.state("minecraft:oak_stairs[facing=east,waterlogged=true]");
    private final int dryStairs = states.state("minecraft:oak_stairs[facing=east]");
    private final int kelp = states.state("minecraft:kelp[age=3]");

    private static Box box(int x0, int y0, int z0, int x1, int y1, int z1) {
        return new Box(new BlockPos(x0, y0, z0), new BlockPos(x1, y1, z1));
    }

    /** The flood test's basin (interior x, z 1-8 above y 60) holding water in y 61-63. */
    private FakeWorld pool() {
        FakeWorld world = new FakeWorld(states);
        world.fill(box(-40, 40, -40, 60, 60, 60), stone);
        world.fill(box(0, 61, 0, 9, 64, 0), stone);
        world.fill(box(0, 61, 9, 9, 64, 9), stone);
        world.fill(box(0, 61, 0, 0, 64, 9), stone);
        world.fill(box(9, 61, 0, 9, 64, 9), stone);
        world.fill(box(1, 61, 1, 8, 63, 8), water);
        world.set(8, 63, 8, flowing);
        return world;
    }

    private static FluidDrain run(FluidDrain drain) {
        while (!drain.step(256)) {
            // slices
        }
        return drain;
    }

    @Test
    void theDrainCollectsTheConnectedWaterAtAnyLevel() {
        FakeWorld world = pool();
        world.set(1, 61, 1, wetStairs);
        world.set(5, 61, 5, kelp);
        world.set(2, 62, 2, dryStairs);
        FluidDrain drain = run(new FluidDrain(world, new BlockPos(4, 62, 4), 100_000, null, true));
        assertTrue(drain.done());
        assertTrue(drain.water());
        assertEquals(8 * 8 * 3 - 1, drain.count(), "every water cell, the wet stairs and the kelp, not the dry stairs");
        assertTrue(drain.contains(8, 63, 8), "flowing water belongs to the body");
        assertTrue(drain.contains(1, 61, 1));
        assertTrue(drain.contains(5, 61, 5));
        assertFalse(drain.contains(2, 62, 2));
        assertFalse(drain.contains(4, 64, 4), "the air above is not water");
        assertFalse(drain.hitLimit());
        assertFalse(drain.hitUnloaded());

        FluidDrain plain = run(new FluidDrain(world, new BlockPos(4, 62, 4), 100_000, null, false));
        assertEquals(8 * 8 * 3 - 3, plain.count(), "fluid blocks only");
        assertFalse(plain.contains(1, 61, 1));
        assertFalse(plain.contains(5, 61, 5));
    }

    @Test
    void waterloggedBlocksContinueTheSearchOnlyWhenTakenAlong() {
        FakeWorld world = new FakeWorld(states);
        world.fill(box(-8, 40, -8, 8, 60, 8), stone);
        world.set(0, 61, 0, water);
        world.set(1, 61, 0, wetStairs);
        world.set(2, 61, 0, water);
        world.set(3, 61, 0, kelp);
        world.set(4, 61, 0, water);
        assertEquals(5, run(new FluidDrain(world, new BlockPos(0, 61, 0), 100, null, true)).count());
        assertEquals(1, run(new FluidDrain(world, new BlockPos(0, 61, 0), 100, null, false)).count(),
                "the stairs stop the search");
        FluidDrain fromStairs = run(new FluidDrain(world, new BlockPos(1, 61, 0), 100, null, true));
        assertEquals(5, fromStairs.count(), "a waterlogged seed is water");
        assertTrue(fromStairs.water());
        assertEquals(0, run(new FluidDrain(world, new BlockPos(1, 61, 0), 100, null, false)).count());
    }

    @Test
    void lavaDrainsLavaOnly() {
        FakeWorld world = new FakeWorld(states);
        world.fill(box(-8, 40, -8, 8, 60, 8), stone);
        world.fill(box(0, 61, 0, 1, 61, 1), lava);
        world.fill(box(2, 61, 0, 3, 61, 1), water);
        world.set(0, 62, 0, wetStairs);
        FluidDrain drain = run(new FluidDrain(world, new BlockPos(0, 61, 0), 100, null, true));
        assertFalse(drain.water());
        assertEquals(4, drain.count(), "the lava, not the water beside it nor the wet stairs above");
        FluidDrain fromWater = run(new FluidDrain(world, new BlockPos(2, 61, 0), 100, null, true));
        assertTrue(fromWater.water());
        assertEquals(4, fromWater.count(), "the water, not the lava beside it");
    }

    @Test
    void aSeedWithoutFluidFindsNothing() {
        FakeWorld world = pool();
        assertEquals(0, run(new FluidDrain(world, new BlockPos(0, 61, 4), 100, null, true)).count(), "stone");
        assertEquals(0, run(new FluidDrain(world, new BlockPos(4, 64, 4), 100, null, true)).count(), "air");
        assertEquals(0, run(new FluidDrain(world, new BlockPos(4, 61, 4), 100, box(6, 61, 6, 8, 63, 8), true)).count(),
                "outside the bounds");
    }

    @Test
    void boundsLimitAndUnloadedChunksStopTheDrain() {
        FakeWorld world = pool();
        FluidDrain bounded = run(new FluidDrain(world, new BlockPos(2, 62, 2), 100_000, box(1, 61, 1, 4, 63, 8), true));
        assertEquals(4 * 8 * 3, bounded.count());
        FluidDrain limited = run(new FluidDrain(world, new BlockPos(2, 62, 2), 7, null, true));
        assertEquals(7, limited.count());
        assertTrue(limited.hitLimit());

        FakeWorld wide = new FakeWorld(states);
        wide.fill(box(-8, 40, -8, 40, 60, 8), stone);
        wide.fill(box(0, 61, 0, 30, 61, 0), water);
        wide.setLoaded(1, 0, false);
        FluidDrain drain = run(new FluidDrain(wide, new BlockPos(0, 61, 0), 100_000, null, true));
        assertEquals(16, drain.count());
        assertTrue(drain.hitUnloaded());
        assertEquals(0, run(new FluidDrain(wide, new BlockPos(20, 61, 0), 100_000, null, true)).count(),
                "a seed in an unloaded chunk");
    }
}
