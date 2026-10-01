package dev.sculptory.core.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Jump and Through: where the feet go, and when there is nowhere. */
class LandingTest {
    private final FakeStateSpace states = new FakeStateSpace();
    private final int stone = states.state("minecraft:stone");
    private final int lava = states.state("minecraft:lava");
    private final int water = states.state("minecraft:water");
    private final int grass = states.state("minecraft:short_grass");

    /** Stone ground up to y 63 around the origin. */
    private FakeWorld ground() {
        FakeWorld world = new FakeWorld(states);
        world.fill(new Box(new BlockPos(-20, 60, -20), new BlockPos(20, 63, 20)), stone);
        return world;
    }

    private static Optional<BlockPos> at(int x, int y, int z) {
        return Optional.of(new BlockPos(x, y, z));
    }

    @Test
    void jumpStandsOnTopOfTheBlockLookedAt() {
        FakeWorld world = ground();
        assertEquals(at(3, 64, 4), Landing.onTop(world, new BlockPos(3, 63, 4)));
        // Looking at the side of the ground (a block under others): on top of the column.
        assertEquals(at(3, 64, 4), Landing.onTop(world, new BlockPos(3, 61, 4)));
    }

    @Test
    void jumpTakesTheFirstGapTallEnoughOnTheColumn() {
        FakeWorld world = ground();
        // One free cell at 64, then stone at 65: too low. Free from 66: the feet go there.
        world.set(0, 65, 0, stone);
        assertEquals(at(0, 66, 0), Landing.onTop(world, new BlockPos(0, 63, 0)));
        // A cave in the column: two free cells over stone.
        world.set(0, 64, 0, stone);
        world.set(0, 66, 0, stone);
        world.set(0, 67, 0, stone);
        world.set(0, 70, 0, stone);
        assertEquals(at(0, 68, 0), Landing.onTop(world, new BlockPos(0, 60, 0)));
    }

    @Test
    void jumpIntoAPlantStandsInIt() {
        FakeWorld world = ground();
        world.set(2, 64, 2, grass);
        assertEquals(at(2, 64, 2), Landing.onTop(world, new BlockPos(2, 64, 2)));
    }

    @Test
    void jumpNeverLandsInOrOnLavaOrWater() {
        FakeWorld world = ground();
        world.set(1, 64, 1, lava);
        // Lava is no ground and no place for the feet: nothing above it to stand on.
        assertEquals(Optional.empty(), Landing.onTop(world, new BlockPos(1, 63, 1)));
        world.set(1, 65, 1, stone);
        assertEquals(at(1, 66, 1), Landing.onTop(world, new BlockPos(1, 63, 1)));
        // Lava over the head.
        world.set(1, 67, 1, lava);
        assertEquals(Optional.empty(), Landing.onTop(world, new BlockPos(1, 65, 1)));
        // Water holds the feet (on the ground under it), but is no ground itself.
        world.set(5, 64, 5, water);
        world.set(5, 65, 5, water);
        assertEquals(at(5, 64, 5), Landing.onTop(world, new BlockPos(5, 63, 5)));
        world.set(5, 63, 5, stone);
        world.set(5, 64, 5, stone);
        assertEquals(at(5, 65, 5), Landing.onTop(world, new BlockPos(5, 64, 5)));
    }

    @Test
    void jumpWorksUpToTheBuildHeightAndNotInUnloadedChunks() {
        FakeWorld world = new FakeWorld(states, 0, 64);
        world.set(0, 63, 0, stone);
        // On the topmost layer the feet are above the build height, in the open air.
        assertEquals(at(0, 64, 0), Landing.onTop(world, new BlockPos(0, 63, 0)));
        world.set(0, 62, 0, stone);
        assertEquals(at(0, 64, 0), Landing.onTop(world, new BlockPos(0, 62, 0)));
        world.set(1, 0, 1, stone);
        assertEquals(at(1, 1, 1), Landing.onTop(world, new BlockPos(1, 0, 1)));
        world.setLoaded(0, 0, false);
        assertEquals(Optional.empty(), Landing.onTop(world, new BlockPos(0, 63, 0)));
    }

    /** A wall at x 5 (thickness {@code thick}), ground at 63, a room past it. */
    private FakeWorld wall(int thick) {
        FakeWorld world = ground();
        world.fill(new Box(new BlockPos(5, 64, -5), new BlockPos(4 + thick, 70, 5)), stone);
        return world;
    }

    @Test
    void throughAThinWallStandsOnTheFloorBehindIt() {
        // Looking level at the wall's block at eye height: the head there, the feet on the floor.
        assertEquals(at(6, 64, 0), Landing.through(wall(1), new BlockPos(5, 65, 0), 1, 0, 0, 64));
        // Looking a little down or up changes nothing.
        assertEquals(at(6, 64, 0), Landing.through(wall(1), new BlockPos(5, 65, 0), 1, -0.1, 0, 64));
        assertEquals(at(6, 64, 0), Landing.through(wall(1), new BlockPos(5, 64, 0), 1, 0.2, 0.1, 64));
    }

    @Test
    void throughAThickWallGoesOnToTheFirstSpace() {
        assertEquals(at(15, 64, 0), Landing.through(wall(10), new BlockPos(5, 65, 0), 1, 0, 0, 64));
        // Too deep for the limit: nothing.
        assertEquals(Optional.empty(), Landing.through(wall(10), new BlockPos(5, 65, 0), 1, 0, 0, 9));
        assertEquals(at(15, 64, 0), Landing.through(wall(10), new BlockPos(5, 65, 0), 1, 0, 0, 10));
    }

    @Test
    void throughAFloorHangsUnderIt() {
        FakeWorld world = ground();
        // A room under the ground, 60 to 57 free.
        world.fill(new Box(new BlockPos(-3, 57, -3), new BlockPos(3, 60, 3)), states.air());
        world.fill(new Box(new BlockPos(-3, 50, -3), new BlockPos(3, 56, 3)), stone);
        assertEquals(at(0, 59, 0), Landing.through(world, new BlockPos(0, 63, 0), 0, -1, 0, 64));
        // Up through a ceiling: the feet on it.
        assertEquals(at(0, 64, 0), Landing.through(world, new BlockPos(0, 61, 0), 0, 1, 0, 64));
    }

    @Test
    void throughNeedsTwoFreeCellsPastTheWall() {
        FakeWorld world = wall(1);
        // Past the wall a gap one block high, then stone again: nothing within 3.
        world.fill(new Box(new BlockPos(6, 64, 0), new BlockPos(8, 64, 0)), stone);
        world.fill(new Box(new BlockPos(6, 66, 0), new BlockPos(8, 70, 0)), stone);
        assertEquals(Optional.empty(), Landing.through(world, new BlockPos(5, 65, 0), 1, 0, 0, 3));
        assertEquals(at(9, 64, 0), Landing.through(world, new BlockPos(5, 65, 0), 1, 0, 0, 64));
    }

    @Test
    void throughSkipsLavaAndStopsAtTheWorldsEdges() {
        FakeWorld world = wall(1);
        world.fill(new Box(new BlockPos(6, 64, 0), new BlockPos(7, 66, 0)), lava);
        assertEquals(at(8, 64, 0), Landing.through(world, new BlockPos(5, 65, 0), 1, 0, 0, 64));
        // Down through the bottom of the world: the void is no spot.
        FakeWorld low = new FakeWorld(states, 0, 64);
        low.fill(new Box(new BlockPos(0, 0, 0), new BlockPos(0, 3, 0)), stone);
        assertEquals(Optional.empty(), Landing.through(low, new BlockPos(0, 3, 0), 0, -1, 0, 64));
        // Up through the topmost layer: standing on it, never higher.
        low.fill(new Box(new BlockPos(2, 60, 0), new BlockPos(2, 63, 0)), stone);
        assertEquals(at(2, 64, 0), Landing.through(low, new BlockPos(2, 60, 0), 0, 1, 0, 64));
        // An unloaded chunk behind the wall.
        FakeWorld far = wall(1);
        far.setLoaded(0, 0, true);
        assertEquals(at(6, 64, 0), Landing.through(far, new BlockPos(5, 65, 0), 1, 0, 0, 64));
        FakeWorld edge = ground();
        edge.fill(new Box(new BlockPos(14, 64, 0), new BlockPos(15, 70, 0)), stone);
        edge.setLoaded(1, 0, false);
        assertEquals(Optional.empty(), Landing.through(edge, new BlockPos(14, 65, 0), 1, 0, 0, 64));
    }

    @Test
    void throughWithoutAWallOrADirectionFindsNothing() {
        FakeWorld world = ground();
        assertEquals(Optional.empty(), Landing.through(world, new BlockPos(0, 65, 0), 1, 0, 0, 64));
        assertEquals(Optional.empty(), Landing.through(world, new BlockPos(0, 63, 0), 0, 0, 0, 64));
    }
}
