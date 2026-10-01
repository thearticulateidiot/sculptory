package dev.sculptory.fabric.client.editor.tools.extrude;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.region.CellSet;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.ShapeKind;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.fabric.client.editor.tools.select.MagicSelect;
import dev.sculptory.fabric.client.editor.world.BoxFace;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The face search: planar connected faces on every side against brute force, match modes, caps, chunks, slices. */
class FaceSelectTest {
    private final FakeStateSpace states = new FakeStateSpace();
    private final FakeWorld world = new FakeWorld(states);
    private final int stone = states.state("minecraft:stone");
    private final int dirt = states.state("minecraft:dirt");
    private final int water = states.state("minecraft:water");
    private final int logY = states.state("minecraft:oak_log[axis=y]");
    private final int logX = states.state("minecraft:oak_log[axis=x]");

    private static FaceSelect run(FaceSelect select) {
        int guard = 0;
        while (!select.step(1000)) {
            if (++guard > 1_000_000) throw new AssertionError("The search never finished");
        }
        return select;
    }

    private FaceSelect connected(int x, int y, int z, BoxFace side, MagicSelect.Match match, boolean diagonals, long limit) {
        return run(FaceSelect.connected(world, new BlockPos(x, y, z), side, match, diagonals, limit));
    }

    private static Set<BlockPos> cells(FaceSelect select) {
        Set<BlockPos> cells = cellsOf(select.cells());
        assertEquals(select.count(), cells.size());
        long[] array = select.cellArray();
        assertEquals(select.count(), array.length);
        assertEquals(array.length, select.stateArray().length);
        for (long packed : array) {
            assertTrue(cells.contains(new BlockPos(FaceSelect.cellX(packed), FaceSelect.cellY(packed), FaceSelect.cellZ(packed))));
        }
        return cells;
    }

    private static Set<BlockPos> cellsOf(CellSet set) {
        Set<BlockPos> cells = new HashSet<>();
        if (set.isEmpty()) return cells;
        Region.Cells region = new Region.Cells(set);
        for (long key : region.sectionKeys()) {
            int baseX = BlockBuffer.keyX(key) << 4;
            int baseY = BlockBuffer.keyY(key) << 4;
            int baseZ = BlockBuffer.keyZ(key) << 4;
            for (int i = 0; i < 4096; i++) {
                int x = baseX + (i & 15);
                int y = baseY + (i >>> 8);
                int z = baseZ + ((i >>> 4) & 15);
                if (region.contains(x, y, z)) cells.add(new BlockPos(x, y, z));
            }
        }
        return cells;
    }

    /**
     * Brute force: every cell within {@code reach} of the seed in its plane that matches and is exposed, then the ones
     * connected to the seed, grown one at a time.
     */
    private Set<BlockPos> bruteForce(BlockPos seed, BoxFace side, MagicSelect.Match match, boolean diagonals, int reach) {
        int seedState = world.get(seed.x(), seed.y(), seed.z());
        Set<BlockPos> candidates = new HashSet<>();
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dy = -reach; dy <= reach; dy++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    BlockPos cell = seed.offset(dx, dy, dz);
                    if ((side.axis() == 0 && dx != 0) || (side.axis() == 1 && dy != 0) || (side.axis() == 2 && dz != 0)) continue;
                    if (cell.y() < world.bottomY() || cell.y() >= world.topYExclusive()) continue;
                    if (!world.isLoaded(cell.x() >> 4, cell.z() >> 4)) continue;
                    int state = world.get(cell.x(), cell.y(), cell.z());
                    if (StateFlags.has(states.flags(state), StateFlags.AIR)) continue;
                    boolean matches = switch (match) {
                        case EXACT_STATE -> state == seedState;
                        case SAME_BLOCK -> states.blockId(state).equals(states.blockId(seedState));
                        case ANY_BLOCK -> true;
                    };
                    if (!matches) continue;
                    BlockPos beyond = cell.offset(side.normalX(), side.normalY(), side.normalZ());
                    if (beyond.y() < world.bottomY() || beyond.y() >= world.topYExclusive()) continue;
                    if (!world.isLoaded(beyond.x() >> 4, beyond.z() >> 4)) continue;
                    int flags = states.flags(world.get(beyond.x(), beyond.y(), beyond.z()));
                    if (!StateFlags.has(flags, StateFlags.AIR) && !StateFlags.has(flags, StateFlags.REPLACEABLE)) continue;
                    candidates.add(cell);
                }
            }
        }
        Set<BlockPos> found = new HashSet<>();
        if (!candidates.contains(seed)) return found;
        Deque<BlockPos> open = new ArrayDeque<>();
        open.add(seed);
        found.add(seed);
        while (!open.isEmpty()) {
            BlockPos at = open.poll();
            for (int du = -1; du <= 1; du++) {
                for (int dv = -1; dv <= 1; dv++) {
                    if (du == 0 && dv == 0) continue;
                    if (!diagonals && du != 0 && dv != 0) continue;
                    BlockPos next = switch (side.axis()) {
                        case 0 -> at.offset(0, du, dv);
                        case 1 -> at.offset(du, 0, dv);
                        default -> at.offset(du, dv, 0);
                    };
                    if (candidates.contains(next) && found.add(next)) open.add(next);
                }
            }
        }
        return found;
    }

    private void check(BlockPos seed, BoxFace side, MagicSelect.Match match, boolean diagonals) {
        FaceSelect select = connected(seed.x(), seed.y(), seed.z(), side, match, diagonals, 1_000_000);
        assertEquals(bruteForce(seed, side, match, diagonals, 24), cells(select), side + " " + match + " diagonals=" + diagonals);
    }

    // ---- Planes on every side ----

    @Test
    void everySideOfACubeIsItsOwnFace() {
        world.fill(new Box(new BlockPos(0, 60, 0), new BlockPos(4, 64, 4)), stone);
        for (BoxFace side : BoxFace.values()) {
            BlockPos seed = new BlockPos(2 + 2 * side.normalX(), 62 + 2 * side.normalY(), 2 + 2 * side.normalZ());
            FaceSelect select = connected(seed.x(), seed.y(), seed.z(), side, MagicSelect.Match.SAME_BLOCK, false, 1000);
            assertEquals(25, select.count(), side.toString());
            assertEquals(side, select.face());
            assertEquals(FaceSelect.coordinate(seed, side.axis()), select.plane());
            check(seed, side, MagicSelect.Match.SAME_BLOCK, false);
            check(seed, side, MagicSelect.Match.ANY_BLOCK, true);
        }
        // A cell inside the cube is not exposed, so a "face" from it is empty.
        FaceSelect inside = connected(2, 62, 2, BoxFace.UP, MagicSelect.Match.SAME_BLOCK, false, 1000);
        assertTrue(inside.done());
        assertEquals(0, inside.count());
    }

    @Test
    void aBlockStandingOnTheFaceBreaksItAndAStepIsAnotherPlane() {
        world.fill(new Box(new BlockPos(0, 60, 0), new BlockPos(9, 60, 9)), stone);
        world.set(4, 61, 4, stone); // stands on the floor: (4, 60, 4) is covered, (4, 61, 4) is another plane
        world.fill(new Box(new BlockPos(10, 61, 0), new BlockPos(12, 61, 9)), stone); // a step up, not connected
        FaceSelect floor = connected(0, 60, 0, BoxFace.UP, MagicSelect.Match.SAME_BLOCK, false, 1000);
        assertEquals(99, floor.count());
        assertFalse(floor.contains(4, 60, 4));
        assertFalse(floor.contains(4, 61, 4));
        assertFalse(floor.contains(10, 61, 0));
        check(new BlockPos(0, 60, 0), BoxFace.UP, MagicSelect.Match.SAME_BLOCK, false);
        FaceSelect top = connected(4, 61, 4, BoxFace.UP, MagicSelect.Match.SAME_BLOCK, false, 1000);
        assertEquals(1, top.count());
        // The block's east side is a one-cell face; the floor's east edge is the step's west wall... none: air beyond.
        FaceSelect east = connected(4, 61, 4, BoxFace.EAST, MagicSelect.Match.SAME_BLOCK, false, 1000);
        assertEquals(1, east.count());
        check(new BlockPos(9, 60, 3), BoxFace.EAST, MagicSelect.Match.ANY_BLOCK, true);
    }

    @Test
    void wallsOnEveryHorizontalSideAgainstBruteForce() {
        // An L-shaped building with a doorway and a window: two walls per side of each wing.
        world.fill(new Box(new BlockPos(0, 60, 0), new BlockPos(10, 66, 6)), stone);
        world.fill(new Box(new BlockPos(1, 61, 1), new BlockPos(9, 65, 5)), states.air());
        world.fill(new Box(new BlockPos(10, 60, 6), new BlockPos(16, 66, 12)), stone);
        world.set(5, 61, 0, states.air()); // a doorway
        world.set(5, 62, 0, states.air());
        world.set(0, 63, 3, states.air()); // a window
        for (BoxFace side : List.of(BoxFace.NORTH, BoxFace.SOUTH, BoxFace.EAST, BoxFace.WEST)) {
            for (boolean diagonals : new boolean[] {false, true}) {
                check(new BlockPos(0, 62, 2), side, MagicSelect.Match.SAME_BLOCK, diagonals);
                check(new BlockPos(3, 62, 0), side, MagicSelect.Match.SAME_BLOCK, diagonals);
                check(new BlockPos(10, 62, 6), side, MagicSelect.Match.ANY_BLOCK, diagonals);
            }
        }
        FaceSelect north = connected(3, 62, 0, BoxFace.NORTH, MagicSelect.Match.SAME_BLOCK, false, 10_000);
        assertEquals(11 * 7 - 2, north.count(), "the north wall minus the doorway");
        assertTrue(north.contains(0, 60, 0));
        assertFalse(north.contains(1, 62, 1), "the hollow inside is air");
    }

    // ---- Match modes and diagonals ----

    @Test
    void matchModesFollowMagicSelect() {
        world.fill(new Box(new BlockPos(0, 60, 0), new BlockPos(9, 60, 9)), stone);
        world.fill(new Box(new BlockPos(3, 60, 3), new BlockPos(5, 60, 5)), dirt);
        world.set(0, 60, 9, logY);
        world.set(1, 60, 9, logX);
        world.set(2, 60, 9, logY);
        FaceSelect same = connected(0, 60, 0, BoxFace.UP, MagicSelect.Match.SAME_BLOCK, false, 1000);
        assertEquals(100 - 9 - 3, same.count());
        FaceSelect any = connected(0, 60, 0, BoxFace.UP, MagicSelect.Match.ANY_BLOCK, false, 1000);
        assertEquals(100, any.count());
        FaceSelect logs = connected(0, 60, 9, BoxFace.UP, MagicSelect.Match.SAME_BLOCK, false, 1000);
        assertEquals(3, logs.count(), "every log, whatever its axis");
        FaceSelect exact = connected(0, 60, 9, BoxFace.UP, MagicSelect.Match.EXACT_STATE, false, 1000);
        assertEquals(1, exact.count(), "the x log between cuts the y logs apart");
        FaceSelect dirtPatch = connected(4, 60, 4, BoxFace.UP, MagicSelect.Match.SAME_BLOCK, false, 1000);
        assertEquals(9, dirtPatch.count());
        for (MagicSelect.Match match : MagicSelect.Match.values()) {
            check(new BlockPos(0, 60, 0), BoxFace.UP, match, false);
            check(new BlockPos(0, 60, 9), BoxFace.UP, match, true);
        }
    }

    @Test
    void diagonalsJoinSquaresTouchingAtACorner() {
        world.fill(new Box(new BlockPos(0, 60, 0), new BlockPos(2, 60, 2)), stone);
        world.fill(new Box(new BlockPos(3, 60, 3), new BlockPos(5, 60, 5)), stone);
        assertEquals(9, connected(1, 60, 1, BoxFace.UP, MagicSelect.Match.SAME_BLOCK, false, 1000).count());
        assertEquals(18, connected(1, 60, 1, BoxFace.UP, MagicSelect.Match.SAME_BLOCK, true, 1000).count());
        check(new BlockPos(1, 60, 1), BoxFace.UP, MagicSelect.Match.SAME_BLOCK, true);
        // A wall too: the corner touch lies in the wall's plane.
        world.fill(new Box(new BlockPos(0, 60, 0), new BlockPos(5, 65, 5)), states.air());
        world.fill(new Box(new BlockPos(0, 60, 0), new BlockPos(0, 62, 2)), stone);
        world.fill(new Box(new BlockPos(0, 63, 3), new BlockPos(0, 65, 5)), stone);
        assertEquals(9, connected(0, 61, 1, BoxFace.WEST, MagicSelect.Match.SAME_BLOCK, false, 1000).count());
        assertEquals(18, connected(0, 61, 1, BoxFace.WEST, MagicSelect.Match.SAME_BLOCK, true, 1000).count());
        check(new BlockPos(0, 61, 1), BoxFace.WEST, MagicSelect.Match.SAME_BLOCK, true);
    }

    // ---- Exposure ----

    @Test
    void waterAndGrassOverTheFaceLeaveItExposedButStoneDoesNot() {
        world.fill(new Box(new BlockPos(0, 60, 0), new BlockPos(9, 60, 9)), stone);
        world.fill(new Box(new BlockPos(0, 61, 0), new BlockPos(4, 61, 9)), water);
        world.set(5, 61, 5, states.state("minecraft:short_grass"));
        world.set(9, 61, 9, dirt);
        FaceSelect floor = connected(0, 60, 0, BoxFace.UP, MagicSelect.Match.SAME_BLOCK, false, 1000);
        assertEquals(99, floor.count(), "the cell under the dirt is covered");
        assertTrue(floor.contains(0, 60, 0), "under water");
        assertTrue(floor.contains(5, 60, 5), "under grass");
        assertFalse(floor.contains(9, 60, 9));
        // The water surface is a face of its own in Any block mode.
        assertEquals(51, connected(0, 61, 0, BoxFace.UP, MagicSelect.Match.ANY_BLOCK, false, 1000).count(),
                "the water and the grass beside it");
        assertEquals(50, connected(0, 61, 0, BoxFace.UP, MagicSelect.Match.SAME_BLOCK, false, 1000).count(),
                "water is replaceable, not air: it matches itself");
    }

    @Test
    void seedsThatAreAirCoveredUnloadedOrOutsideTheWorldGiveNothing() {
        world.fill(new Box(new BlockPos(0, 60, 0), new BlockPos(9, 60, 9)), stone);
        assertEquals(0, connected(0, 61, 0, BoxFace.UP, MagicSelect.Match.ANY_BLOCK, false, 1000).count(), "air");
        assertEquals(100, connected(0, 60, 0, BoxFace.DOWN, MagicSelect.Match.ANY_BLOCK, false, 1000).count(),
                "the floor's underside is open");
        world.set(3, 59, 3, stone);
        assertEquals(0, connected(3, 60, 3, BoxFace.DOWN, MagicSelect.Match.SAME_BLOCK, false, 1000).count(), "covered");
        world.setLoaded(0, 0, false);
        FaceSelect unloaded = connected(0, 60, 0, BoxFace.UP, MagicSelect.Match.ANY_BLOCK, false, 1000);
        assertTrue(unloaded.done());
        assertEquals(0, unloaded.count());
        int top = world.topYExclusive();
        world.set(20, top - 1, 20, stone);
        FaceSelect atTop = connected(20, top - 1, 20, BoxFace.UP, MagicSelect.Match.ANY_BLOCK, false, 1000);
        assertEquals(0, atTop.count(), "nothing can be pulled out beyond the build height");
        assertEquals(1, connected(20, top - 1, 20, BoxFace.EAST, MagicSelect.Match.ANY_BLOCK, false, 1000).count());
        assertEquals(0, connected(20, top, 20, BoxFace.UP, MagicSelect.Match.ANY_BLOCK, false, 1000).count());
        assertThrows(IllegalArgumentException.class, () -> FaceSelect.connected(world, new BlockPos(0, 60, 0), BoxFace.UP,
                MagicSelect.Match.ANY_BLOCK, false, 0));
    }

    // ---- Caps and chunks ----

    @Test
    void theLimitStopsAFaceWithMoreToTakeButNotAnExactFit() {
        world.fill(new Box(new BlockPos(0, 60, 0), new BlockPos(4, 60, 4)), stone);
        FaceSelect cut = connected(2, 60, 2, BoxFace.UP, MagicSelect.Match.SAME_BLOCK, false, 10);
        assertTrue(cut.hitLimit());
        assertEquals(10, cut.count());
        assertEquals(10, cells(cut).size());
        FaceSelect exact = connected(2, 60, 2, BoxFace.UP, MagicSelect.Match.SAME_BLOCK, false, 25);
        assertFalse(exact.hitLimit());
        assertEquals(25, exact.count());
        assertEquals(1, connected(2, 60, 2, BoxFace.UP, MagicSelect.Match.SAME_BLOCK, false, 1).count());
    }

    @Test
    void unloadedChunksAreNeverReadAndStopTheFace() {
        world.fill(new Box(new BlockPos(0, 60, 0), new BlockPos(31, 60, 15)), stone);
        world.setLoaded(1, 0, false);
        FaceSelect floor = connected(3, 60, 3, BoxFace.UP, MagicSelect.Match.SAME_BLOCK, false, 10_000);
        assertTrue(floor.hitUnloaded());
        assertEquals(16 * 16, floor.count(), "only the loaded chunk");
        assertEquals(bruteForce(new BlockPos(3, 60, 3), BoxFace.UP, MagicSelect.Match.SAME_BLOCK, false, 40), cells(floor));
        // A wall on the chunk border facing into the unloaded chunk: the cells beyond can't be read, so no face.
        world.fill(new Box(new BlockPos(0, 60, 0), new BlockPos(31, 60, 15)), states.air());
        world.fill(new Box(new BlockPos(15, 60, 0), new BlockPos(15, 64, 15)), stone);
        FaceSelect wall = connected(15, 62, 5, BoxFace.EAST, MagicSelect.Match.SAME_BLOCK, false, 10_000);
        assertTrue(wall.hitUnloaded());
        assertEquals(0, wall.count());
        assertEquals(5 * 16, connected(15, 62, 5, BoxFace.WEST, MagicSelect.Match.SAME_BLOCK, false, 10_000).count());
    }

    @Test
    void aChunkUnloadedBetweenSlicesCountsAsUnloaded() {
        world.fill(new Box(new BlockPos(0, 60, 0), new BlockPos(31, 60, 15)), stone);
        FaceSelect select = FaceSelect.connected(world, new BlockPos(3, 60, 3), BoxFace.UP, MagicSelect.Match.SAME_BLOCK,
                false, 10_000);
        assertFalse(select.step(4));
        world.setLoaded(1, 0, false);
        run(select);
        assertTrue(select.hitUnloaded());
        assertEquals(256, select.count());
    }

    @Test
    void negativeCoordinatesAndSectionBordersAgainstBruteForce() {
        world.fill(new Box(new BlockPos(-20, -3, -20), new BlockPos(20, -3, 20)), stone);
        world.fill(new Box(new BlockPos(-2, -2, -2), new BlockPos(2, -2, 2)), stone);
        FaceSelect floor = connected(-17, -3, 16, BoxFace.UP, MagicSelect.Match.SAME_BLOCK, false, 100_000);
        assertEquals(41 * 41 - 25, floor.count());
        assertEquals(bruteForce(new BlockPos(-17, -3, 16), BoxFace.UP, MagicSelect.Match.SAME_BLOCK, false, 45), cells(floor));
        world.fill(new Box(new BlockPos(-33, -20, -1), new BlockPos(-33, 20, 1)), dirt);
        check(new BlockPos(-33, 0, 0), BoxFace.WEST, MagicSelect.Match.SAME_BLOCK, true);
        check(new BlockPos(-33, 0, 0), BoxFace.EAST, MagicSelect.Match.SAME_BLOCK, false);
        check(new BlockPos(-33, 0, -1), BoxFace.NORTH, MagicSelect.Match.SAME_BLOCK, false);
        check(new BlockPos(-33, 20, 0), BoxFace.UP, MagicSelect.Match.SAME_BLOCK, false);
        check(new BlockPos(-33, -20, 0), BoxFace.DOWN, MagicSelect.Match.SAME_BLOCK, false);
    }

    // ---- Slices ----

    @Test
    void stepsAreBoundedAndTheResultDoesNotDependOnThem() {
        world.fill(new Box(new BlockPos(0, 60, 0), new BlockPos(49, 60, 49)), stone);
        FaceSelect sliced = FaceSelect.connected(world, new BlockPos(25, 60, 25), BoxFace.UP, MagicSelect.Match.SAME_BLOCK,
                true, 100_000);
        int steps = 0;
        long examined = 0;
        while (!sliced.step(64)) {
            steps++;
            assertTrue(sliced.examined() - examined <= 64 + 8, "at most a batch and one cell's neighbours per step");
            examined = sliced.examined();
        }
        assertTrue(steps > 100, "a 2,500-cell face takes many small steps: " + steps);
        assertEquals(2500, sliced.count());
        FaceSelect whole = connected(25, 60, 25, BoxFace.UP, MagicSelect.Match.SAME_BLOCK, true, 100_000);
        assertEquals(cells(whole), cells(sliced));
        assertTrue(sliced.step(64), "done stays done");
    }

    // ---- Layers of a selection ----

    @Test
    void aSelectionsLayerIsItsCellsOnThatSideWithoutAir() {
        world.fill(new Box(new BlockPos(0, 60, 0), new BlockPos(9, 60, 9)), stone);
        world.set(0, 62, 0, dirt);
        Box box = new Box(new BlockPos(0, 58, 0), new BlockPos(9, 62, 9));
        FaceSelect top = run(FaceSelect.layer(world, new Region.Cuboid(box), BoxFace.UP, 100_000));
        assertEquals(1, top.count(), "the top layer holds one dirt block");
        assertTrue(top.contains(0, 62, 0));
        assertEquals(62, top.plane());
        FaceSelect bottom = run(FaceSelect.layer(world, new Region.Cuboid(box), BoxFace.DOWN, 100_000));
        assertEquals(0, bottom.count(), "air under the floor");
        Box floorBox = new Box(new BlockPos(0, 60, 0), new BlockPos(9, 60, 9));
        for (BoxFace side : BoxFace.values()) {
            FaceSelect layer = run(FaceSelect.layer(world, new Region.Cuboid(floorBox), side, 100_000));
            assertEquals(side.axis() == 1 ? 100 : 10, layer.count(), side.toString());
            assertFalse(layer.hitLimit());
        }
        // A shape's layer: the sphere's top cells; a cell set's: its cells at the top.
        Region.Shape sphere = new Region.Shape(new Box(new BlockPos(0, 51, 0), new BlockPos(9, 60, 9)), ShapeKind.ELLIPSOID,
                Facing.UP);
        FaceSelect sphereTop = run(FaceSelect.layer(world, sphere, BoxFace.UP, 100_000));
        assertTrue(sphereTop.count() > 0 && sphereTop.count() < 100, "the tip of the sphere: " + sphereTop.count());
        for (BlockPos cell : cells(sphereTop)) {
            assertEquals(60, cell.y());
            assertTrue(sphere.contains(cell.x(), cell.y(), cell.z()));
        }
        CellSet set = CellSet.builder().add(1, 60, 1).add(2, 60, 1).add(2, 59, 1).build();
        FaceSelect setTop = run(FaceSelect.layer(world, new Region.Cells(set), BoxFace.UP, 100_000));
        assertEquals(2, setTop.count());
        FaceSelect setSouth = run(FaceSelect.layer(world, new Region.Cells(set), BoxFace.SOUTH, 100_000));
        assertEquals(2, setSouth.count(), "the south layer is z = 1: the two stone cells (the third is air)");
    }

    @Test
    void layersHitTheLimitAndUnloadedChunksAndComeInBoundedSteps() {
        world.fill(new Box(new BlockPos(0, 60, 0), new BlockPos(31, 60, 15)), stone);
        Box box = new Box(new BlockPos(0, 60, 0), new BlockPos(31, 60, 15));
        FaceSelect cut = run(FaceSelect.layer(world, new Region.Cuboid(box), BoxFace.UP, 100));
        assertTrue(cut.hitLimit());
        assertEquals(100, cut.count());
        world.setLoaded(1, 0, false);
        FaceSelect partial = FaceSelect.layer(world, new Region.Cuboid(box), BoxFace.UP, 100_000);
        assertFalse(partial.step(8));
        assertTrue(partial.examined() <= 8);
        run(partial);
        assertTrue(partial.hitUnloaded());
        assertEquals(256, partial.count());
        assertEquals(cellsOf(CellSet.of(new Region.Cuboid(new Box(new BlockPos(0, 60, 0), new BlockPos(15, 60, 15))),
                1000)), cells(partial));
    }
}
