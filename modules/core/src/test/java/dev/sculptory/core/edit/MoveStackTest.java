package dev.sculptory.core.edit;

import static dev.sculptory.core.edit.CopyTestSupport.box;
import static dev.sculptory.core.edit.CopyTestSupport.chest;
import static dev.sculptory.core.edit.CopyTestSupport.grow;
import static dev.sculptory.core.edit.CopyTestSupport.runAndUndo;
import static dev.sculptory.core.edit.CopyTestSupport.snapshot;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.edit.CopyTestSupport.Cell;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.region.CellSet;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.testing.FakeExecutor;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.core.transform.Mirror;
import dev.sculptory.core.transform.Transform;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class MoveStackTest {
    private final FakeStateSpace states = new FakeStateSpace();
    private final CompileContext context = CopyTestSupport.context(states, Map.of());
    private final int air = states.air();
    private final int stone = states.state("minecraft:stone");
    private final int dirt = states.state("minecraft:dirt");
    private final int sand = states.state("minecraft:sand");

    private EditProgram compile(OpSpec op) {
        return OpCompiler.compile(op, context);
    }

    /** Fills {@code box} with seeded random states (a third of them air), chests carrying distinct tiles. */
    private FakeWorld randomWorld(Box box, long seed) {
        Random random = new Random(seed);
        List<Integer> palette = new ArrayList<>();
        for (int h = 0; h < states.size(); h++) palette.add(h);
        FakeWorld world = new FakeWorld(states);
        int chests = 0;
        for (int x = box.min().x(); x <= box.max().x(); x++) {
            for (int y = box.min().y(); y <= box.max().y(); y++) {
                for (int z = box.min().z(); z <= box.max().z(); z++) {
                    int state = random.nextInt(3) == 0 ? air : palette.get(random.nextInt(palette.size()));
                    world.set(x, y, z, state);
                    if (StateFlags.has(states.flags(state), StateFlags.HAS_BLOCK_ENTITY)) {
                        world.setTile(x, y, z, chest("minecraft:emerald", ++chests));
                    }
                }
            }
        }
        return world;
    }

    // ------------------------------------------------------------------ move

    /** Reference model: vacate the whole source box, then write the transformed copy (read from the original). */
    private Map<BlockPos, Cell> expectedMove(Map<BlockPos, Cell> before, Box box, BlockPos offset, Transform t, int leave) {
        Map<BlockPos, Cell> expected = new HashMap<>(before);
        for (int x = box.min().x(); x <= box.max().x(); x++) {
            for (int y = box.min().y(); y <= box.max().y(); y++) {
                for (int z = box.min().z(); z <= box.max().z(); z++) expected.put(new BlockPos(x, y, z), new Cell(leave, null));
            }
        }
        BlockPos destMin = box.min().add(offset);
        for (int x = box.min().x(); x <= box.max().x(); x++) {
            for (int y = box.min().y(); y <= box.max().y(); y++) {
                for (int z = box.min().z(); z <= box.max().z(); z++) {
                    int lx = x - box.min().x(), lz = z - box.min().z();
                    BlockPos to = new BlockPos(destMin.x() + t.mapX(lx, lz, box.sizeX(), box.sizeZ()),
                            destMin.y() + t.mapY(y - box.min().y(), box.sizeY()),
                            destMin.z() + t.mapZ(lx, lz, box.sizeX(), box.sizeZ()));
                    Cell from = before.get(new BlockPos(x, y, z));
                    expected.put(to, new Cell(t.applyToState(states, from.state()), from.tile()));
                }
            }
        }
        return expected;
    }

    @Test
    void overlappingMovesInEveryDirectionAreExact() {
        Box box = box(0, 0, 0, 9, 5, 6);
        List<BlockPos> offsets = List.of(new BlockPos(3, 0, 0), new BlockPos(-3, 0, 0), new BlockPos(0, 2, 0),
                new BlockPos(0, -2, 0), new BlockPos(0, 0, 3), new BlockPos(0, 0, -3), new BlockPos(2, -1, 3),
                new BlockPos(-1, 1, -2), new BlockPos(0, 0, 0), new BlockPos(20, 0, 0));
        List<Transform> transforms = List.of(Transform.IDENTITY, Transform.rotation(1), new Transform(2, Mirror.X),
                new Transform(3, Mirror.Z), new Transform(0, Mirror.X), Transform.UPSIDE_DOWN,
                new Transform(1, Mirror.Z, true), new Transform(2, Mirror.X, true));
        long seed = 1;
        for (BlockPos offset : offsets) {
            for (Transform t : transforms) {
                String what = "move by " + offset + " with " + t;
                FakeWorld world = randomWorld(box, seed++);
                Box region = grow(box(0, 0, 0, 29, 5, 9), 4);
                Map<BlockPos, Cell> expected = expectedMove(snapshot(world, region), box, offset, t, air);
                EditProgram program = compile(new OpSpec.Move(box, offset, t, new Pattern.Single(air)));
                runAndUndo(program, world, region, what);
                CopyTestSupport.assertWorld(expected, world, what);
            }
        }
    }

    @Test
    void moveMetadata() {
        Box box = box(0, 0, 0, 9, 5, 6);
        EditProgram program = compile(new OpSpec.Move(box, new BlockPos(3, 0, 0), Transform.rotation(1), new Pattern.Single(air)));
        assertEquals("Move", program.label());
        // The turned box is 7 x 6 x 10, with its minimum corner at (3, 0, 0).
        assertEquals(box(0, 0, 0, 9, 5, 9), program.bounds());
        Box destination = box(3, 0, 0, 9, 5, 9);
        long overlap = 7L * 6 * 7;
        assertEquals(destination.volume() + box.volume() - overlap, program.estimatedCells());
        LongOpenHashSet boxSections = new LongOpenHashSet();
        box.forEachSectionKey(boxSections::add);
        assertEquals(boxSections, new LongOpenHashSet(program.sourceSections()));
        long[] order = program.sectionOrder();
        long[] sorted = order.clone();
        SectionOrder.sort(sorted);
        assertArrayEquals(sorted, order);
    }

    @Test
    void vacatedCellsTakeTheLeavePattern() {
        Box box = box(0, 0, 0, 3, 1, 3);
        FakeWorld world = new FakeWorld(states);
        world.fill(box, stone);
        FakeExecutor.run(compile(new OpSpec.Move(box, new BlockPos(2, 0, 0), Transform.IDENTITY, new Pattern.Single(dirt))), world);
        for (int x = 0; x <= 5; x++) {
            for (int z = 0; z <= 3; z++) assertEquals(x < 2 ? dirt : stone, world.get(x, 0, z), x + "," + z);
        }
        // A Dry leave pattern dries a vacated waterlogged chest and keeps its items (the block stays the same).
        int wetChest = states.state("minecraft:chest[facing=north,waterlogged=true]");
        FakeWorld drained = new FakeWorld(states);
        drained.fill(box, stone);
        drained.set(0, 0, 0, wetChest);
        drained.setTile(0, 0, 0, CopyTestSupport.chest("minecraft:emerald", 3));
        FakeExecutor.run(compile(new OpSpec.Move(box, new BlockPos(0, 5, 0), Transform.IDENTITY, new Pattern.Dry())), drained);
        assertEquals(wetChest, drained.get(0, 5, 0), "the moved chest is as it was");
        assertEquals(states.state("minecraft:chest[facing=north]"), drained.get(0, 0, 0), "the vacated cell is dried");
        assertTrue(CopyTestSupport.chest("minecraft:emerald", 3).sameContent(drained.tile(0, 0, 0)), "and keeps its items");
        assertEquals(stone, drained.get(1, 0, 0), "stone is left as it is by Dry");
        Pattern weighted = new Pattern.Weighted(new int[] {dirt, sand}, new int[] {1, 1}, 7L);
        FakeWorld other = new FakeWorld(states);
        other.fill(box, stone);
        FakeExecutor.run(compile(new OpSpec.Move(box, new BlockPos(0, 5, 0), Transform.IDENTITY, weighted)), other);
        assertEquals(weighted.apply(states, 1, 1, 2, stone), other.get(1, 1, 2));
        assertThrows(IllegalArgumentException.class, () -> compile(
                new OpSpec.Move(box, BlockPos.ORIGIN, Transform.IDENTITY, new Pattern.Single(states.size()))));
    }

    @Test
    void moveRefusesDestinationsOutsideTheWorld() {
        Box box = box(0, 0, 0, 3, 3, 3);
        assertThrows(IllegalArgumentException.class,
                () -> compile(new OpSpec.Move(box, new BlockPos(0, 400, 0), Transform.IDENTITY, new Pattern.Single(air))));
        assertThrows(IllegalArgumentException.class,
                () -> compile(new OpSpec.Move(box(0, 400, 0, 3, 410, 3), new BlockPos(0, -200, 0), Transform.IDENTITY,
                        new Pattern.Single(air))));
        assertThrows(IllegalArgumentException.class, () -> compile(new OpSpec.Move(box,
                new BlockPos(Integer.MAX_VALUE, 0, 0), Transform.IDENTITY, new Pattern.Single(air))));
        // Partly above the world: the part inside is written, the rest is lost.
        FakeWorld world = new FakeWorld(states);
        world.fill(box, stone);
        FakeExecutor.run(compile(new OpSpec.Move(box, new BlockPos(0, 318, 0), Transform.IDENTITY, new Pattern.Single(air))), world);
        assertEquals(stone, world.get(0, 319, 0));
        assertEquals(air, world.get(0, 0, 0));
    }

    // ------------------------------------------------------------------ stack

    /** Reference model: copies 1..count in order, each read from the original, later copies overwriting. */
    private Map<BlockPos, Cell> expectedStack(Map<BlockPos, Cell> before, Box box, int dx, int dy, int dz, int count) {
        return expectedStack(before, box, dx, dy, dz, count, false);
    }

    /** The reference model with every copy flipped upside down within the box's height, when {@code flip}. */
    private Map<BlockPos, Cell> expectedStack(Map<BlockPos, Cell> before, Box box, int dx, int dy, int dz, int count,
                                              boolean flip) {
        Map<BlockPos, Cell> expected = new HashMap<>(before);
        for (int k = 1; k <= count; k++) {
            for (int x = box.min().x(); x <= box.max().x(); x++) {
                for (int y = box.min().y(); y <= box.max().y(); y++) {
                    for (int z = box.min().z(); z <= box.max().z(); z++) {
                        int ty = flip ? box.min().y() + box.max().y() - y : y;
                        Cell from = before.get(new BlockPos(x, y, z));
                        expected.put(new BlockPos(x + k * dx, ty + k * dy, z + k * dz),
                                flip ? new Cell(states.flip(from.state()), from.tile()) : from);
                    }
                }
            }
        }
        return expected;
    }

    @Test
    void stacksMatchTheReferenceModel() {
        Box box = box(0, 0, 0, 2, 1, 2);
        int[][] cases = {
                {3, 0, 0, 4}, {5, 0, 0, 3}, {0, 2, 0, 3}, {-3, 0, 0, 4}, {0, 0, -4, 2},
                {2, 0, 0, 4}, {1, 1, 1, 3}, {0, 0, -1, 5}, {-1, 0, 2, 6}, {16, 0, 16, 2}};
        long seed = 100;
        for (boolean flip : new boolean[] {false, true}) {
            for (int[] c : cases) {
                String what = "stack " + c[3] + " x (" + c[0] + "," + c[1] + "," + c[2] + ")" + (flip ? " upside down" : "");
                FakeWorld world = randomWorld(box, seed++);
                Box region = grow(box(-20, 0, -20, 40, 8, 40), 1);
                Map<BlockPos, Cell> expected = expectedStack(snapshot(world, region), box, c[0], c[1], c[2], c[3], flip);
                EditProgram program = compile(new OpSpec.Stack(new Region.Cuboid(box), c[0], c[1], c[2], c[3],
                        EntityFilter.NONE, Symmetry.NONE, PasteOptions.Into.EVERYTHING, flip));
                assertEquals("Stack", program.label());
                assertEquals(box.volume() * c[3], program.estimatedCells(), what);
                runAndUndo(program, world, region, what);
                CopyTestSupport.assertWorld(expected, world, what);
            }
        }
    }

    @Test
    void stackCountAndSpacing() {
        Box box = box(0, 0, 0, 1, 0, 0);
        FakeWorld world = new FakeWorld(states);
        world.set(0, 0, 0, stone);
        world.set(1, 0, 0, dirt);
        EditProgram program = compile(new OpSpec.Stack(box, 3, 0, 0, 4));
        assertEquals(box(3, 0, 0, 13, 0, 0), program.bounds());
        FakeExecutor.Result result = FakeExecutor.run(program, world);
        assertEquals(8, result.written());
        for (int x = 0; x <= 16; x++) {
            int expected = x > 13 ? air : switch (x % 3) {
                case 0 -> stone;
                case 1 -> dirt;
                default -> air;
            };
            assertEquals(expected, world.get(x, 0, 0), "x=" + x);
        }
    }

    @Test
    void stackClipsAndRefuses() {
        Box box = box(0, 300, 0, 3, 309, 3);
        EditProgram program = compile(new OpSpec.Stack(box, 0, 10, 0, 5));
        // Copy 1 is y 310-319; copies 2+ are above the world.
        assertEquals(box(0, 310, 0, 3, 319, 3), program.bounds());
        assertEquals(4 * 10 * 4, program.estimatedCells());
        assertThrows(IllegalArgumentException.class, () -> compile(new OpSpec.Stack(box(0, 310, 0, 3, 319, 3), 0, 10, 0, 3)));
        assertThrows(IllegalArgumentException.class, () -> compile(new OpSpec.Stack(box, 0, 0, 1 << 20, 1 << 12)));
        assertThrows(IllegalArgumentException.class, () -> compile(new OpSpec.Stack(box, Integer.MAX_VALUE, 0, 0, 2)));
    }

    // ------------------------------------------------------------------ the flip's pivot across the build height

    /** The world's height in the compile context: -64 to 319. */
    private static final int BOTTOM = -64;
    private static final int TOP = 319;

    /** {@code box} cut to the world's height. */
    private static Box inside(Box box) {
        return box(box.min().x(), Math.max(box.min().y(), BOTTOM), box.min().z(),
                box.max().x(), Math.min(box.max().y(), TOP), box.max().z());
    }

    /**
     * Reference model of a flipped move or stack of a selection that crosses the build height: as the client shows it,
     * every cell inside the height turns over within the selection's whole bounds ({@code y} becomes
     * {@code min + max − y}), then shifts by {@code k · offset} for copies {@code 1..count} (a move is one copy, its
     * vacated cells taking {@code leave}). A stack copy's cell landing outside the height is cut, as a plain stack's is;
     * a flipped move whose image would leave the height is refused instead (see the move test), so here a move's image
     * is always inside.
     */
    private Map<BlockPos, Cell> expectedFlippedAcrossHeight(Map<BlockPos, Cell> before, Region region, BlockPos offset,
                                                             int count, Integer leave) {
        Box bounds = region.bounds();
        long sum = (long) bounds.min().y() + bounds.max().y();
        Map<BlockPos, Cell> expected = new HashMap<>(before);
        if (leave != null) {
            for (BlockPos p : before.keySet()) if (region.contains(p.x(), p.y(), p.z())) expected.put(p, new Cell(leave, null));
        }
        for (int k = 1; k <= count; k++) {
            for (Map.Entry<BlockPos, Cell> cell : before.entrySet()) {
                BlockPos p = cell.getKey();
                if (!region.contains(p.x(), p.y(), p.z())) continue;
                BlockPos to = new BlockPos(p.x() + k * offset.x(), (int) (sum - p.y()) + k * offset.y(), p.z() + k * offset.z());
                if (to.y() < BOTTOM || to.y() > TOP) continue;
                expected.put(to, new Cell(states.flip(cell.getValue().state()), cell.getValue().tile()));
            }
        }
        return expected;
    }

    /** A checkerboard of the box's cells, the ones outside the world's height included, so the bounds cross it. */
    private static Region checkerboard(Box box) {
        CellSet.Builder builder = CellSet.builder();
        for (int x = box.min().x(); x <= box.max().x(); x++) {
            for (int y = box.min().y(); y <= box.max().y(); y++) {
                for (int z = box.min().z(); z <= box.max().z(); z++) {
                    if ((x + y + z) % 2 == 0) builder.add(x, y, z);
                }
            }
        }
        return new Region.Cells(builder.build());
    }

    @Test
    void aFlippedMoveOfASelectionCrossingTheBuildHeightTurnsOverWithinItsWholeBounds() {
        // {box y range, offset y}: over the top, under the bottom, both (a turn in place), and a cell set over the top.
        int[][] cases = {{310, 329, -10}, {-74, -55, 10}, {-70, 325, 0}, {300, 335, -16}};
        long seed = 300;
        for (int[] c : cases) {
            Box box = box(0, c[0], 0, 2, c[1], 1);
            Region region = c == cases[3] ? checkerboard(box) : new Region.Cuboid(box);
            BlockPos offset = new BlockPos(0, c[2], 0);
            String what = "flipped move of " + box + " by " + c[2];
            FakeWorld world = randomWorld(inside(box), seed++);
            Box watched = grow(inside(grow(box, 2)), 0);
            Map<BlockPos, Cell> expected = expectedFlippedAcrossHeight(snapshot(world, watched), region, offset, 1, air);
            EditProgram program = compile(new OpSpec.Move(region, offset, Transform.UPSIDE_DOWN, new Pattern.Single(air),
                    EntityFilter.NONE, Symmetry.NONE, PasteOptions.Into.EVERYTHING));
            runAndUndo(program, world, watched, what);
            CopyTestSupport.assertWorld(expected, world, what);
        }
        // A flipped move whose image would leave the world is refused, as the edit service refuses a plain move whose
        // destination would: turned over in place, every cell of a box 10 over the top lands above the world; moved down
        // by 5, half of them still do. (The unflipped program clips instead: moveRefusesDestinationsOutsideTheWorld.)
        Box over = box(0, 310, 0, 2, 329, 1);
        assertThrows(IllegalArgumentException.class, () -> compile(new OpSpec.Move(over, BlockPos.ORIGIN,
                Transform.UPSIDE_DOWN, new Pattern.Single(air))));
        assertThrows(IllegalArgumentException.class, () -> compile(new OpSpec.Move(over, new BlockPos(0, -5, 0),
                Transform.UPSIDE_DOWN, new Pattern.Single(air))));
        assertThrows(IllegalArgumentException.class, () -> compile(new OpSpec.Move(box(0, -74, 0, 2, -55, 1),
                new BlockPos(0, 4, 0), Transform.UPSIDE_DOWN, new Pattern.Single(air))), "partly under the bottom");
    }

    @Test
    void flippedStackCopiesOfASelectionCrossingTheBuildHeightTurnOverWithinItsWholeBounds() {
        // A box 10 over the top, stacked twice downwards: its inside part (300-319) turns over to 310-329, so copy 1 is
        // 270-289 and copy 2 is 230-249 (the cut part alone would have given 260-279 and 220-239).
        Box box = box(0, 300, 0, 2, 329, 1);
        for (Region region : List.of(new Region.Cuboid(box), checkerboard(box))) {
            String what = "flipped stack of " + region.getClass().getSimpleName() + " " + box;
            FakeWorld world = randomWorld(inside(box), 400);
            Box watched = box(-1, 200, -1, 3, TOP, 2);
            Map<BlockPos, Cell> expected = expectedFlippedAcrossHeight(snapshot(world, watched), region,
                    new BlockPos(0, -40, 0), 2, null);
            EditProgram program = compile(new OpSpec.Stack(region, 0, -40, 0, 2, EntityFilter.NONE, Symmetry.NONE,
                    PasteOptions.Into.EVERYTHING, true));
            assertEquals(box(0, 230, 0, 2, 289, 1), program.bounds(), what);
            if (region instanceof Region.Cuboid) assertEquals(2 * 20 * 3 * 2, program.estimatedCells(), what);
            runAndUndo(program, world, watched, what);
            CopyTestSupport.assertWorld(expected, world, what);
        }
        // A box 10 under the bottom stacked once upwards by its height: the inside part (-64..-55) turns over to
        // -74..-65 and lands at -54..-45.
        Box low = box(0, -74, 0, 1, -55, 0);
        FakeWorld world = randomWorld(inside(low), 401);
        Box watched = box(-1, BOTTOM, -1, 2, 0, 1);
        Map<BlockPos, Cell> expected = expectedFlippedAcrossHeight(snapshot(world, watched), new Region.Cuboid(low),
                new BlockPos(0, 20, 0), 1, null);
        EditProgram program = compile(new OpSpec.Stack(new Region.Cuboid(low), 0, 20, 0, 1, EntityFilter.NONE,
                Symmetry.NONE, PasteOptions.Into.EVERYTHING, true));
        assertEquals(box(0, -54, 0, 1, -45, 0), program.bounds());
        runAndUndo(program, world, watched, "flipped stack under the bottom");
        CopyTestSupport.assertWorld(expected, world, "flipped stack under the bottom");
    }
}
