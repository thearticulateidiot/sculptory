package dev.sculptory.core.brush;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.core.world.WorldReader;
import org.junit.jupiter.api.Test;

/** The shared surface definition (brushes and scatter) as a public API. */
class SurfaceScanTest {
    private final FakeStateSpace states = new FakeStateSpace();
    private final int stone = states.state("minecraft:stone");
    private final int grass = states.state("minecraft:grass_block");
    private final int shortGrass = states.state("minecraft:short_grass");
    private final int water = states.state("minecraft:water");

    @Test
    void findsTheGroundBelowOpenCellsAndCountsPlants() {
        FakeWorld world = new FakeWorld(states);
        world.set(0, 60, 0, stone);
        world.set(0, 61, 0, grass);
        world.set(0, 62, 0, shortGrass);
        world.set(0, 63, 0, shortGrass);
        world.set(0, 64, 0, shortGrass);
        int[] found = new int[2];
        assertEquals(61, SurfaceScan.scan(world, states, 0, 0, 100, 0, found));
        assertArrayEquals(new int[] {grass, 2}, found);
        // Water above the ground is open but not a plant.
        world.set(1, 50, 1, grass);
        world.set(1, 51, 1, water);
        assertEquals(50, SurfaceScan.scan(world, states, 1, 1, 100, 0, found));
        assertEquals(0, found[1]);
        assertEquals(50, SurfaceScan.scan(world, states, 1, 1, 100, 0, null));
    }

    @Test
    void structuresWindowsAndWrongHintsGiveNoSurfaceOrAFullScan() {
        FakeWorld world = new FakeWorld(states);
        world.set(0, 61, 0, grass);
        world.set(0, 62, 0, states.state("minecraft:oak_stairs"));
        assertEquals(SurfaceScan.NONE, SurfaceScan.scan(world, states, 0, 0, 100, 0, null), "a structure on top");
        world.set(1, 61, 1, grass);
        assertEquals(SurfaceScan.NONE, SurfaceScan.scan(world, states, 1, 1, 61, 0, null), "starts inside the ground");
        assertEquals(SurfaceScan.NONE, SurfaceScan.scan(world, states, 1, 1, 100, 62, null), "ground below the window");
        assertEquals(SurfaceScan.NONE, SurfaceScan.scan(world, states, 5, 5, 100, 0, null), "nothing at all");

        // A hint that undershoots onto a solid cell is ignored.
        FakeWorld backing = new FakeWorld(states);
        backing.set(0, 61, 0, stone);
        backing.set(0, 70, 0, grass);
        assertEquals(70, SurfaceScan.scan(new WrongHint(backing, 61), states, 0, 0, 100, 0, null));
    }

    /** Answers every {@link WorldReader#heightHint} with a fixed y. */
    private record WrongHint(FakeWorld world, int hint) implements WorldReader {
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

        @Override
        public int heightHint(int x, int z) {
            return hint;
        }
    }

    @Test
    void classifiesFlags() {
        int solid = StateFlags.TERRAIN_SOLID;
        assertTrue(SurfaceScan.ground(solid));
        assertFalse(SurfaceScan.ground(solid | StateFlags.HAS_BLOCK_ENTITY));
        assertTrue(SurfaceScan.open(StateFlags.AIR));
        assertTrue(SurfaceScan.open(StateFlags.VEGETATION));
        assertFalse(SurfaceScan.open(StateFlags.REPLACEABLE | StateFlags.HAS_BLOCK_ENTITY));
        assertTrue(SurfaceScan.structure(0));
        assertTrue(SurfaceScan.structure(StateFlags.HAS_BLOCK_ENTITY));
        assertTrue(SurfaceScan.plant(StateFlags.VEGETATION | StateFlags.REPLACEABLE));
        assertFalse(SurfaceScan.plant(StateFlags.VEGETATION | StateFlags.FLUID_BLOCK));
    }
}
