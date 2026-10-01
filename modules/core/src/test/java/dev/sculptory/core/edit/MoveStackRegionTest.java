package dev.sculptory.core.edit;

import static dev.sculptory.core.edit.CopyTestSupport.box;
import static dev.sculptory.core.edit.CopyTestSupport.chest;
import static dev.sculptory.core.edit.CopyTestSupport.grow;
import static dev.sculptory.core.edit.CopyTestSupport.runAndUndo;
import static dev.sculptory.core.edit.CopyTestSupport.snapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.edit.CopyTestSupport.Cell;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.region.CellSet;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.ShapeKind;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.core.transform.Mirror;
import dev.sculptory.core.transform.Transform;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Move, Stack and Copy over shapes and cell sets: only region cells move or
 * are copied, the vacated cells are the region cells nothing lands on, overlapping sources and destinations are exact,
 * volumes count cells, and every result undoes exactly.
 */
class MoveStackRegionTest {
    private final FakeStateSpace states = new FakeStateSpace();
    private final CompileContext context = CopyTestSupport.context(states, Map.of());
    private final int air = states.air();
    private final int dirt = states.state("minecraft:dirt");

    private EditProgram compile(OpSpec op) {
        return OpCompiler.compile(op, context);
    }

    /** Random states over {@code box} (a third of them air), chests carrying distinct tiles. */
    private FakeWorld randomWorld(Box box, long seed) {
        Random random = new Random(seed);
        FakeWorld world = new FakeWorld(states);
        int chests = 0;
        for (int x = box.min().x(); x <= box.max().x(); x++) {
            for (int y = box.min().y(); y <= box.max().y(); y++) {
                for (int z = box.min().z(); z <= box.max().z(); z++) {
                    int state = random.nextInt(3) == 0 ? air : random.nextInt(states.size());
                    world.set(x, y, z, state);
                    if (StateFlags.has(states.flags(state), StateFlags.HAS_BLOCK_ENTITY)) {
                        world.setTile(x, y, z, chest("minecraft:emerald", ++chests));
                    }
                }
            }
        }
        return world;
    }

    private static List<BlockPos> cells(Region region) {
        List<BlockPos> cells = new ArrayList<>();
        Box b = region.bounds();
        for (int x = b.min().x(); x <= b.max().x(); x++) {
            for (int y = b.min().y(); y <= b.max().y(); y++) {
                for (int z = b.min().z(); z <= b.max().z(); z++) {
                    if (region.contains(x, y, z)) cells.add(new BlockPos(x, y, z));
                }
            }
        }
        return cells;
    }

    private List<Region> regions() {
        Random random = new Random(5);
        CellSet.Builder builder = CellSet.builder();
        for (int i = 0; i < 300; i++) builder.add(random.nextInt(14), random.nextInt(7), random.nextInt(11));
        // An L of rows, so moved rows and gaps line up with each other.
        for (int x = 0; x < 14; x++) builder.add(x, 3, 0);
        for (int z = 0; z < 11; z++) builder.add(0, 3, z);
        return List.of(new Region.Shape(box(0, 0, 0, 13, 6, 10), ShapeKind.ELLIPSOID, Facing.UP),
                new Region.Shape(box(0, 0, 0, 13, 6, 10), ShapeKind.CONE, Facing.EAST),
                new Region.Shape(box(0, 0, 0, 13, 6, 10), ShapeKind.PYRAMID, Facing.SOUTH),
                new Region.Cells(builder.build()));
    }

    // =================================================================== move

    /** Reference model: vacate every region cell, then write each region cell at its moved place (read before). */
    private Map<BlockPos, Cell> expectedMove(Map<BlockPos, Cell> before, Region region, BlockPos offset, Transform t) {
        Map<BlockPos, Cell> expected = new HashMap<>(before);
        Box b = region.bounds();
        List<BlockPos> cells = cells(region);
        for (BlockPos cell : cells) expected.put(cell, new Cell(air, null));
        BlockPos destMin = b.min().add(offset);
        for (BlockPos cell : cells) {
            int lx = cell.x() - b.min().x(), lz = cell.z() - b.min().z();
            BlockPos to = new BlockPos(destMin.x() + t.mapX(lx, lz, b.sizeX(), b.sizeZ()),
                    destMin.y() + t.mapY(cell.y() - b.min().y(), b.sizeY()),
                    destMin.z() + t.mapZ(lx, lz, b.sizeX(), b.sizeZ()));
            Cell from = before.get(cell);
            expected.put(to, new Cell(t.applyToState(states, from.state()), from.tile()));
        }
        return expected;
    }

    @Test
    void movesOfShapesAndCellSetsAreExactAndUndoExactly() {
        List<BlockPos> offsets = List.of(new BlockPos(3, 0, 0), new BlockPos(-2, 1, 2), new BlockPos(0, -2, 0),
                new BlockPos(1, 0, -3), new BlockPos(0, 0, 0), new BlockPos(30, 0, 0));
        List<Transform> transforms = List.of(Transform.IDENTITY, Transform.rotation(1), new Transform(2, Mirror.X),
                new Transform(3, Mirror.Z), new Transform(0, Mirror.X), Transform.UPSIDE_DOWN,
                new Transform(3, Mirror.X, true));
        long seed = 1;
        for (Region region : regions()) {
            for (BlockPos offset : offsets) {
                for (Transform t : transforms) {
                    String what = region + " moved by " + offset + " with " + t;
                    FakeWorld world = randomWorld(grow(region.bounds(), 3), seed++);
                    Box area = grow(box(0, 0, 0, 44, 6, 13), 5);
                    Map<BlockPos, Cell> before = snapshot(world, area);
                    Map<BlockPos, Cell> expected = expectedMove(before, region, offset, t);
                    EditProgram program = compile(new OpSpec.Move(region, offset, t, new Pattern.Single(air), EntityFilter.NONE));
                    long changedCells = 0;
                    for (Map.Entry<BlockPos, Cell> entry : expected.entrySet()) {
                        if (!entry.getValue().equals(before.get(entry.getKey()))) changedCells++;
                    }
                    assertTrue(program.estimatedCells() >= changedCells, what + ": estimate");
                    runAndUndo(program, world, area, what);
                    CopyTestSupport.assertWorld(expected, world, what);
                    // Only the region's sections are snapshotted.
                    LongOpenHashSet regionSections = new LongOpenHashSet(region.sectionKeys());
                    assertEquals(regionSections, new LongOpenHashSet(program.sourceSections()), what);
                }
            }
        }
    }

    /**
     * A shape reaching below the world moved up and turned: only its cells inside the build height move, landing
     * where the turn puts them (the part of the box below the world counts for where they land).
     */
    @Test
    void aShapeCrossingTheBottomOfTheWorldMovesOnlyItsCellsInside() {
        Region.Shape ball = new Region.Shape(box(0, -70, 0, 12, -58, 9), ShapeKind.CONE, Facing.WEST);
        BlockPos offset = new BlockPos(20, 3, 2);
        Transform t = Transform.rotation(3);
        FakeWorld world = randomWorld(box(-2, -64, -2, 14, -56, 12), 77);
        Box area = box(-2, -64, -2, 40, -50, 30);
        Map<BlockPos, Cell> before = snapshot(world, area);
        // The turn works on the bounds cut to the build height, whose x and z are the whole box's.
        Box source = box(0, -64, 0, 12, -58, 9);
        Map<BlockPos, Cell> expected = new HashMap<>(before);
        List<BlockPos> inside = cells(ball).stream().filter(cell -> cell.y() >= -64).toList();
        for (BlockPos cell : inside) expected.put(cell, new Cell(air, null));
        BlockPos destMin = source.min().add(offset);
        for (BlockPos cell : inside) {
            int lx = cell.x() - source.min().x(), lz = cell.z() - source.min().z();
            BlockPos to = new BlockPos(destMin.x() + t.mapX(lx, lz, source.sizeX(), source.sizeZ()), cell.y() + offset.y(),
                    destMin.z() + t.mapZ(lx, lz, source.sizeX(), source.sizeZ()));
            Cell from = before.get(cell);
            expected.put(to, new Cell(t.applyToState(states, from.state()), from.tile()));
        }
        EditProgram program = compile(new OpSpec.Move(ball, offset, t, new Pattern.Single(air), EntityFilter.NONE));
        runAndUndo(program, world, area, "a shape below the world moved");
        CopyTestSupport.assertWorld(expected, world, "a shape below the world moved");
    }

    /**
     * The same shape moved flipped upside down: it turns over within the height of its whole bounds, the pivot the
     * client shows. The part inside the world (-64..-58) turns over to -70..-64, so moved up by 6 every cell lands
     * inside the world; moved up by 3 the image would leave it, and the move is refused (as a plain move whose
     * destination leaves the world is), nothing lost.
     */
    @Test
    void aShapeCrossingTheBottomOfTheWorldFlipsWithinItsWholeBounds() {
        Box bounds = box(0, -70, 0, 12, -58, 9);
        Region.Shape ball = new Region.Shape(bounds, ShapeKind.CONE, Facing.WEST);
        BlockPos offset = new BlockPos(20, 6, 2);
        Transform t = new Transform(3, Mirror.NONE, true);
        FakeWorld world = randomWorld(box(-2, -64, -2, 14, -56, 12), 78);
        Box area = box(-2, -64, -2, 40, -50, 30);
        Map<BlockPos, Cell> before = snapshot(world, area);
        Map<BlockPos, Cell> expected = new HashMap<>(before);
        List<BlockPos> inside = cells(ball).stream().filter(cell -> cell.y() >= -64).toList();
        for (BlockPos cell : inside) expected.put(cell, new Cell(air, null));
        BlockPos destMin = bounds.min().add(offset);
        for (BlockPos cell : inside) {
            int lx = cell.x() - bounds.min().x(), lz = cell.z() - bounds.min().z();
            BlockPos to = new BlockPos(destMin.x() + t.mapX(lx, lz, bounds.sizeX(), bounds.sizeZ()),
                    destMin.y() + bounds.max().y() - cell.y(), destMin.z() + t.mapZ(lx, lz, bounds.sizeX(), bounds.sizeZ()));
            assertTrue(to.y() >= -64, "every cell lands inside the world: " + to);
            Cell from = before.get(cell);
            expected.put(to, new Cell(t.applyToState(states, from.state()), from.tile()));
        }
        EditProgram program = compile(new OpSpec.Move(ball, offset, t, new Pattern.Single(air), EntityFilter.NONE));
        runAndUndo(program, world, area, "a shape below the world moved upside down");
        CopyTestSupport.assertWorld(expected, world, "a shape below the world moved upside down");
        assertThrows(IllegalArgumentException.class, () -> compile(new OpSpec.Move(ball, new BlockPos(20, 3, 2), t,
                new Pattern.Single(air), EntityFilter.NONE)), "the image would leave the world");
    }

    /** The estimate is the moved cells plus the vacated ones, exactly. */
    @Test
    void moveEstimatesCountCells() {
        for (Region region : regions()) {
            for (BlockPos offset : List.of(new BlockPos(2, 0, 0), new BlockPos(0, 1, 1), new BlockPos(40, 0, 0))) {
                EditProgram program = compile(new OpSpec.Move(region, offset, Transform.IDENTITY, new Pattern.Single(air),
                        EntityFilter.NONE));
                Set<BlockPos> source = new HashSet<>(cells(region));
                Set<BlockPos> moved = new HashSet<>();
                for (BlockPos cell : source) moved.add(cell.add(offset));
                long vacated = source.stream().filter(cell -> !moved.contains(cell)).count();
                assertEquals(moved.size() + vacated, program.estimatedCells(), region + " by " + offset);
                LongOpenHashSet sections = new LongOpenHashSet();
                for (BlockPos cell : source) sections.add(BlockBuffer.keyOfBlock(cell.x(), cell.y(), cell.z()));
                for (BlockPos cell : moved) sections.add(BlockBuffer.keyOfBlock(cell.x(), cell.y(), cell.z()));
                assertEquals(sections, new LongOpenHashSet(program.sectionOrder()), "exactly the sections with cells");
            }
        }
    }

    // =================================================================== stack

    /** Reference model: copies 1..count of the region's cells, each read from the original, later ones winning. */
    private static Map<BlockPos, Cell> expectedStack(Map<BlockPos, Cell> before, Region region, int dx, int dy, int dz,
                                                     int count) {
        return expectedStack(before, region, dx, dy, dz, count, null);
    }

    /** The reference model with every copy flipped upside down within the region's bounds, when {@code flip} is set. */
    private static Map<BlockPos, Cell> expectedStack(Map<BlockPos, Cell> before, Region region, int dx, int dy, int dz,
                                                     int count, StateSpace flip) {
        Map<BlockPos, Cell> expected = new HashMap<>(before);
        List<BlockPos> cells = cells(region);
        Box b = region.bounds();
        for (int k = 1; k <= count; k++) {
            for (BlockPos cell : cells) {
                Cell from = before.get(cell);
                if (flip == null) {
                    expected.put(cell.offset(k * dx, k * dy, k * dz), from);
                } else {
                    BlockPos turned = new BlockPos(cell.x(), b.min().y() + b.max().y() - cell.y(), cell.z());
                    expected.put(turned.offset(k * dx, k * dy, k * dz), new Cell(flip.flip(from.state()), from.tile()));
                }
            }
        }
        return expected;
    }

    @Test
    void stacksOfShapesAndCellSetsAreExactAndUndoExactly() {
        int[][] cases = {{14, 0, 0, 3}, {3, 0, 0, 4}, {0, 2, 0, 3}, {-3, 0, 1, 3}, {1, 1, 1, 5}, {0, 0, -2, 4}, {20, 0, 20, 2}};
        long seed = 100;
        for (boolean flip : new boolean[] {false, true}) {
            for (Region region : regions()) {
                for (int[] c : cases) {
                    String what = region + " stacked " + c[3] + " x (" + c[0] + "," + c[1] + "," + c[2] + ")"
                            + (flip ? " upside down" : "");
                    checkStack(region, c, flip, seed++, grow(box(-20, 0, -20, 60, 30, 60), 1), what);
                }
            }
        }
    }

    /**
     * Flipped stacks of regions whose height crosses section boundaries: each target section's layers come from two
     * source sections, read in reverse.
     */
    @Test
    void flippedStacksAcrossSectionsAreExact() {
        Random random = new Random(9);
        CellSet.Builder builder = CellSet.builder();
        for (int i = 0; i < 400; i++) builder.add(random.nextInt(9), 5 + random.nextInt(33), random.nextInt(6));
        List<Region> regions = List.of(new Region.Cells(builder.build()),
                new Region.Shape(box(0, 7, 0, 8, 36, 5), ShapeKind.CONE, Facing.UP));
        int[][] cases = {{9, 0, 0, 2}, {0, 32, 0, 2}, {0, 7, 0, 3}, {2, -5, 1, 2}, {0, 1, 0, 4}};
        long seed = 500;
        for (Region region : regions) {
            for (int[] c : cases) {
                String what = region + " stacked upside down " + c[3] + " x (" + c[0] + "," + c[1] + "," + c[2] + ")";
                checkStack(region, c, true, seed++, grow(box(-10, -20, -10, 30, 120, 20), 1), what);
            }
        }
    }

    private void checkStack(Region region, int[] c, boolean flip, long seed, Box area, String what) {
        FakeWorld world = randomWorld(grow(region.bounds(), 2), seed);
        Map<BlockPos, Cell> before = snapshot(world, area);
        Map<BlockPos, Cell> expected = expectedStack(before, region, c[0], c[1], c[2], c[3], flip ? states : null);
        EditProgram program = compile(new OpSpec.Stack(region, c[0], c[1], c[2], c[3], EntityFilter.NONE, Symmetry.NONE,
                PasteOptions.Into.EVERYTHING, flip));
        assertEquals(region.cellCount() * c[3], program.estimatedCells(), what);
        runAndUndo(program, world, area, what);
        CopyTestSupport.assertWorld(expected, world, what);
        LongOpenHashSet sections = new LongOpenHashSet();
        Box b = region.bounds();
        for (BlockPos cell : cells(region)) {
            int y = flip ? b.min().y() + b.max().y() - cell.y() : cell.y();
            for (int k = 1; k <= c[3]; k++) {
                sections.add(BlockBuffer.keyOfBlock(cell.x() + k * c[0], y + k * c[1], cell.z() + k * c[2]));
            }
        }
        assertEquals(sections, new LongOpenHashSet(program.sectionOrder()), what + ": exactly the copies' sections");
    }

    /**
     * A sparse lattice stacked 256 times one block apart: every section is worked 16 cells at a time per copy, not one
     * region lookup per cell per copy. (Before: about 6 ms a section, 74 s for this stack.) The result matches the
     * reference model on a smaller count, and the time for the whole stack is printed.
     */
    @Test
    void aSparseStackIsWorkedByRows() {
        CellSet.Builder lattice = CellSet.builder();
        for (int x = 0; x < 32; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) lattice.add(x * 16, y * 8, z * 16);
            }
        }
        Region.Cells region = new Region.Cells(lattice.build());
        assertEquals(8_192, region.cellCount());
        EditProgram program = compile(new OpSpec.Stack(region, 1, 0, 0, 256, EntityFilter.NONE));
        SectionBuffer air = SectionBuffer.uniform(this.air);
        ComputeContext ctx = new ComputeContext() {
            @Override
            public StateSpace states() {
                return states;
            }

            @Override
            public long seed() {
                return 0;
            }

            @Override
            public SectionBuffer source(long key) {
                return SectionBuffer.uniform(dirt);
            }
        };
        long start = System.nanoTime();
        long written = 0;
        for (long key : program.sectionOrder()) {
            SectionBuffer out = new SectionBuffer();
            program.compute(key, air, out, ctx);
            written += out.presentCount();
        }
        long millis = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("stack of an 8,192-cell lattice, 256 copies 1 apart: %,d sections, %,d cells, %d ms (%.1f us a "
                + "section)%n", program.sectionOrder().length, written, millis, millis * 1000.0 / program.sectionOrder().length);
        // Each of the 16 × 16 rows gets x = 16i + k for i < 32 and k from 1 to 256: 1 to 752.
        assertEquals(752L * 256, written, "every copied cell once");
        assertTrue(millis < 20_000, "took " + millis + " ms");

        // Exact, with overlapping copies and diagonal steps, against the reference model on a small lattice.
        CellSet.Builder small = CellSet.builder();
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 3; y++) {
                for (int z = 0; z < 3; z++) small.add(x * 16 + y, y * 8, z * 16 + x);
            }
        }
        Region.Cells sparse = new Region.Cells(small.build());
        for (int[] step : new int[][] {{1, 0, 0, 40}, {3, 1, -2, 12}, {-1, 0, 5, 9}}) {
            FakeWorld world = randomWorld(grow(sparse.bounds(), 1), step[3]);
            Box area = box(-50, 0, -50, 110, 40, 110);
            Map<BlockPos, Cell> before = snapshot(world, area);
            String what = "a sparse stack by " + step[0] + "," + step[1] + "," + step[2] + " x " + step[3];
            runAndUndo(compile(new OpSpec.Stack(sparse, step[0], step[1], step[2], step[3], EntityFilter.NONE)), world, area,
                    what);
            CopyTestSupport.assertWorld(expectedStack(before, sparse, step[0], step[1], step[2], step[3]), world, what);
        }
    }

    @Test
    void stackCopiesAboveTheWorldAreClippedAndCounted() {
        Region.Shape ball = new Region.Shape(box(0, 300, 0, 9, 309, 9), ShapeKind.ELLIPSOID, Facing.UP);
        EditProgram program = compile(new OpSpec.Stack(ball, 0, 8, 0, 3, EntityFilter.NONE));
        long expected = 0;
        for (BlockPos cell : cells(ball)) {
            for (int k = 1; k <= 3; k++) if (cell.y() + 8 * k <= 319) expected++;
        }
        assertEquals(expected, program.estimatedCells());
    }

    // =================================================================== copy

    @Test
    void copiesHoldOnlyTheRegionCells() {
        FakeWorld world = randomWorld(box(-2, -2, -2, 16, 9, 13), 9);
        for (Region region : regions()) {
            Box b = region.bounds();
            Clipboard clipboard = Clipboard.copyOf(world, region, b, b.min(), null, "test");
            assertEquals(new BlockPos(b.sizeX(), b.sizeY(), b.sizeZ()), clipboard.size());
            assertEquals(region.cellCount(), clipboard.cellCount(), region.toString());
            for (int x = b.min().x(); x <= b.max().x(); x++) {
                for (int y = b.min().y(); y <= b.max().y(); y++) {
                    for (int z = b.min().z(); z <= b.max().z(); z++) {
                        int local = clipboard.get(x - b.min().x(), y - b.min().y(), z - b.min().z());
                        assertEquals(region.contains(x, y, z) ? world.get(x, y, z) : -1, local, region + " at " + x + "," + y + "," + z);
                    }
                }
            }
        }
        // The box form is the cuboid region.
        Box box = box(1, 1, 1, 6, 4, 5);
        assertEquals(Clipboard.copyOf(world, box, box.min(), null, "a").contentHash(),
                Clipboard.copyOf(world, new Region.Cuboid(box), box, box.min(), null, "b").contentHash());
        // A mask applies on top of the region.
        Region ball = regions().get(0);
        Clipboard masked = Clipboard.copyOf(world, ball, ball.bounds(), ball.bounds().min(),
                (x, y, z, state) -> state == dirt, "masked");
        long dirtCells = cells(ball).stream().filter(cell -> world.get(cell.x(), cell.y(), cell.z()) == dirt).count();
        assertEquals(dirtCells, masked.cellCount());
    }
}
