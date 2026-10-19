package dev.sculptory.core.edit;

import static dev.sculptory.core.edit.CopyTestSupport.assertWorld;
import static dev.sculptory.core.edit.CopyTestSupport.box;
import static dev.sculptory.core.edit.CopyTestSupport.grow;
import static dev.sculptory.core.edit.CopyTestSupport.runAndUndo;
import static dev.sculptory.core.edit.CopyTestSupport.snapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.edit.CopyTestSupport.Cell;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.history.HistoryEntry;
import dev.sculptory.core.history.HistoryPrograms;
import dev.sculptory.core.region.CellSet;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.RegionTooLargeException;
import dev.sculptory.core.region.Regions;
import dev.sculptory.core.state.BufferCells;
import dev.sculptory.core.state.OrientedStates;
import dev.sculptory.core.state.ResampledCopy;
import dev.sculptory.core.testing.FakeExecutor;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.core.transform.CellResampler;
import dev.sculptory.core.transform.Orientation;
import dev.sculptory.core.transform.Resampling;
import dev.sculptory.core.transform.Transform;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The failure cases of the free-rotation methods (fix round 2): a sparse smooth turn is refused before it is mapped,
 * a two-cell block is judged whole by "Paste into" and the source mask, a landing cell that lost its block to a pair
 * keeps the move's own rules, exact count loses no block next to pairs, a job cut short undoes exactly, and stamps and
 * symmetric copies undo exactly in every method.
 */
class FreeResamplingFailureTest {
    private final FakeStateSpace states = new FakeStateSpace();
    private final Map<SourceRef, SourceBlocks> sources = new HashMap<>();
    private final CompileContext context = CopyTestSupport.context(states, sources);
    private final SourceRef ref = new SourceRef.Clipboard(UUID.randomUUID());
    private final int air = states.air();
    private final int stone = states.state("minecraft:stone");
    private final int dirt = states.state("minecraft:dirt");
    private final int lower = states.state("minecraft:oak_door[facing=east,half=lower]");
    private final int upper = states.state("minecraft:oak_door[facing=east,half=upper]");

    // ---------------------------------------------------------------- the landing walk

    @Test
    void aSparseSmoothTurnIsRefusedBeforeItIsMappedAndABoundedOneFinishes() {
        Random random = new Random(5);
        CellSet.Builder cells = CellSet.builder();
        for (int i = 0; i < 10_000; i++) cells.add(random.nextInt(3_000), 64 + random.nextInt(60), random.nextInt(3_000));
        Region sparse = new Region.Cells(cells.build());
        Box pivot = sparse.bounds();
        Orientation smooth = Orientation.free(Transform.IDENTITY, 3_000, 2_000, 0, Resampling.SMOOTH, false);
        CellResampler r = smooth.resampler(pivot.sizeX(), pivot.sizeY(), pivot.sizeZ());
        long started = System.nanoTime();
        assertThrows(RegionTooLargeException.class,
                () -> Regions.moved(sparse, pivot, BlockPos.ORIGIN, r, -64, 319, Long.MAX_VALUE, 10_000));
        assertTrue(System.nanoTime() - started < 3_000_000_000L, "refused at once");
        // The same through admission: refused as too large, not mapped.
        // (A yaw: 3,000 blocks pitched would leave the build height.)
        Orientation flat = Orientation.free(Transform.IDENTITY, 3_000, 0, 0, Resampling.SMOOTH, false);
        OpSpec.Move move = new OpSpec.Move(sparse, BlockPos.ORIGIN, flat, new Pattern.Single(air), EntityFilter.NONE,
                Symmetry.NONE, PasteOptions.Into.EVERYTHING);
        assertThrows(EditTooLargeException.class, () -> OpCompiler.freeLanding(move, -64, 320, 40_000, Long.MAX_VALUE));
        // A few hundred scattered cells with room to spare: mapped, and each lone cell lands nowhere or on few cells.
        CellSet.Builder few = CellSet.builder();
        for (int i = 0; i < 300; i++) few.add(random.nextInt(3_000), 64 + random.nextInt(60), random.nextInt(3_000));
        Region some = new Region.Cells(few.build());
        Box fewPivot = some.bounds();
        CellResampler fr = smooth.resampler(fewPivot.sizeX(), fewPivot.sizeY(), fewPivot.sizeZ());
        try {
            Region landed = Regions.moved(some, fewPivot, BlockPos.ORIGIN, fr, -64, 319, Long.MAX_VALUE, 1_000_000);
            assertTrue(landed.cellCount() <= 300, "lone cells land on at most a cell each: " + landed.cellCount());
        } catch (IllegalArgumentException noCellLeft) {
            // A lone cell never covers more than half of any turned cell: nothing lands at all.
        }
    }

    // ---------------------------------------------------------------- pairs judged whole

    /** A 3x3x3 build: a stone floor and a door standing on it. */
    private Clipboard doorBuild() {
        Clipboard.Builder builder = Clipboard.builder(states, new BlockPos(3, 3, 3)).anchor(BlockPos.ORIGIN);
        for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++) builder.set(x, 0, z, stone);
        builder.set(1, 1, 1, lower);
        builder.set(1, 2, 1, upper);
        return builder.build();
    }

    /** The world cells a paste of {@code clipboard} with {@code o} at {@code origin} puts the door's halves on. */
    private List<BlockPos> doorCells(Clipboard clipboard, Orientation o, BlockPos origin) {
        BlockPos size = clipboard.size();
        Box target = PasteGeometry.pasteTarget(size, clipboard.anchor(), o, origin);
        ResampledCopy copy = ResampledCopy.of(o.resampler(size.x(), size.y(), size.z()),
                new BufferCells(clipboard.toSource().cells(), size, states, false), new OrientedStates(states, o),
                o.smoothSlopes(), null);
        List<BlockPos> out = new ArrayList<>();
        int[] partner = new int[3];
        for (int x = 0; x < target.sizeX(); x++) {
            for (int y = 0; y < target.sizeY(); y++) {
                for (int z = 0; z < target.sizeZ(); z++) {
                    if (copy.partner(x, y, z, partner)) out.add(target.min().offset(x, y, z));
                }
            }
        }
        return out;
    }

    @Test
    void pasteIntoJudgesADoorWhole() {
        Clipboard clipboard = doorBuild();
        sources.put(ref, clipboard.toSource());
        BlockPos origin = new BlockPos(20, 70, 20);
        PasteOptions intoAir = new PasteOptions(false, false, false, PasteOptions.Into.AIR);
        for (Resampling method : Resampling.values()) {
            Orientation o = Orientation.free(Transform.IDENTITY, 3_000, 1_000, 0, method, false);
            List<BlockPos> door = doorCells(clipboard, o, origin);
            assertEquals(2, door.size(), o + ": the door is placed");
            for (BlockPos blocked : door) {
                FakeWorld world = new FakeWorld(states);
                world.set(blocked.x(), blocked.y(), blocked.z(), dirt);
                Box area = grow(PasteGeometry.pasteTarget(clipboard.size(), clipboard.anchor(), o, origin), 1);
                runAndUndo(OpCompiler.compile(new OpSpec.Paste(ref, origin, o, intoAir), context), world, area,
                        o.toString());
                for (BlockPos half : door) {
                    int got = world.get(half.x(), half.y(), half.z());
                    assertEquals(half.equals(blocked) ? dirt : air, got, o + ": neither half where one is taken, at "
                            + half);
                }
            }
            // Nothing in the way: both halves.
            FakeWorld world = new FakeWorld(states);
            FakeExecutor.run(OpCompiler.compile(new OpSpec.Paste(ref, origin, o, intoAir), context), world);
            for (BlockPos half : door) assertTrue(states.format(world.get(half.x(), half.y(), half.z())).contains("door"));
        }
    }

    /** The door build at {@code min} in a fresh world. */
    private FakeWorld doorWorld(BlockPos min) {
        FakeWorld world = new FakeWorld(states);
        for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++) world.set(min.x() + x, min.y(), min.z() + z, stone);
        world.set(min.x() + 1, min.y() + 1, min.z() + 1, lower);
        world.set(min.x() + 1, min.y() + 2, min.z() + 1, upper);
        return world;
    }

    @Test
    void aMoveIntoAirMovesADoorWholeOrLeavesNoHalfOfIt() {
        BlockPos min = new BlockPos(0, 64, 0);
        Box from = box(0, 64, 0, 2, 66, 2);
        BlockPos offset = new BlockPos(10, 0, 0);
        for (Resampling method : Resampling.values()) {
            Orientation o = Orientation.free(Transform.IDENTITY, 3_000, 1_000, 0, method, false);
            OpSpec.Move move = new OpSpec.Move(new Region.Cuboid(from), offset, o, new Pattern.Single(air),
                    EntityFilter.NONE, Symmetry.NONE, PasteOptions.Into.AIR);
            // Where the door lands when nothing is in the way.
            FakeWorld clear = doorWorld(min);
            FakeExecutor.run(OpCompiler.compile(move, context), clear);
            List<BlockPos> door = new ArrayList<>();
            Box area = grow(box(0, 64, 0, 14, 66, 4), 4);
            for (int x = area.min().x(); x <= area.max().x(); x++) {
                for (int y = area.min().y(); y <= area.max().y(); y++) {
                    for (int z = area.min().z(); z <= area.max().z(); z++) {
                        if (states.format(clear.get(x, y, z)).contains("door")) door.add(new BlockPos(x, y, z));
                    }
                }
            }
            assertEquals(2, door.size(), o + ": the door moves whole");
            for (BlockPos blocked : door) {
                FakeWorld world = doorWorld(min);
                world.set(blocked.x(), blocked.y(), blocked.z(), dirt);
                runAndUndo(OpCompiler.compile(move, context), world, area, o.toString());
                int halves = 0;
                for (int x = area.min().x(); x <= area.max().x(); x++) {
                    for (int y = area.min().y(); y <= area.max().y(); y++) {
                        for (int z = area.min().z(); z <= area.max().z(); z++) {
                            if (states.format(world.get(x, y, z)).contains("door")) halves++;
                        }
                    }
                }
                assertEquals(0, halves, o + ": no half of the door is left anywhere when one half cannot land");
                assertEquals(dirt, world.get(blocked.x(), blocked.y(), blocked.z()), o + ": the block in the way stays");
            }
        }
    }

    @Test
    void aLandingCellWhoseBlockAPairTookFollowsTheMovesOwnRules() {
        // A column of doors in a stone block, moved in place with a stone leave pattern: every cell the move covers
        // ends up a moved block, the leave pattern (a region cell nothing lands on) or untouched; never plain air from
        // nowhere.
        Box from = box(0, 64, 0, 4, 68, 4);
        int planks = states.state("minecraft:oak_planks");
        boolean sawSuppressed = false;
        int suppressedSeen = 0;
        for (int[] a : new int[][] {{3_000, 1_000, 0}, {0, 2_500, 0}, {1_500, 0, 3_000}, {4_500, 2_000, 1_000}}) {
            for (Resampling method : Resampling.values()) {
                Orientation o = Orientation.free(Transform.IDENTITY, a[0], a[1], a[2], method, false);
                FakeWorld world = new FakeWorld(states);
                for (int x = 0; x <= 4; x++) {
                    for (int y = 64; y <= 68; y++) for (int z = 0; z <= 4; z++) world.set(x, y, z, planks);
                }
                for (int z = 0; z <= 4; z += 2) {
                    world.set(2, 65, z, lower);
                    world.set(2, 66, z, upper);
                }
                Box area = grow(from, 5);
                for (int x = area.min().x(); x <= area.max().x(); x++) {
                    for (int y = area.min().y(); y <= area.max().y(); y++) {
                        for (int z = area.min().z(); z <= area.max().z(); z++) {
                            if (!from.contains(x, y, z)) world.set(x, y, z, dirt);
                        }
                    }
                }
                Map<BlockPos, Cell> before = snapshot(world, area);
                List<BlockPos> suppressed = suppressedLandings(world, from, o);
                suppressedSeen += suppressed.size();
                OpSpec.Move move = new OpSpec.Move(new Region.Cuboid(from), BlockPos.ORIGIN, o,
                        new Pattern.Single(stone), EntityFilter.NONE, Symmetry.NONE, PasteOptions.Into.EVERYTHING);
                runAndUndo(OpCompiler.compile(move, context), world, area, o.toString());
                for (BlockPos p : suppressed) {
                    int want = from.contains(p.x(), p.y(), p.z()) ? stone : before.get(p).state();
                    assertEquals(states.format(want), states.format(world.get(p.x(), p.y(), p.z())),
                            o + ": a landing cell whose block a door took, at " + p);
                }
                for (Map.Entry<BlockPos, Cell> c : before.entrySet()) {
                    int got = world.get(c.getKey().x(), c.getKey().y(), c.getKey().z());
                    assertFalse(got == air, o + ": air from nowhere at " + c.getKey());
                    if (got == stone) sawSuppressed |= !from.contains(c.getKey().x(), c.getKey().y(), c.getKey().z());
                }
            }
        }
        assertFalse(sawSuppressed, "the leave pattern only fills region cells");
        assertTrue(suppressedSeen > 0, "some landing cell lost its block to a door (the case this test is about)");
    }

    /**
     * The world cells an in-place free move of box {@code from} lands on whose block went to a kept-whole two-cell
     * block (the method fills them from a region cell, the move's copy does not), by the programs' own composition.
     */
    private List<BlockPos> suppressedLandings(FakeWorld world, Box from, Orientation o) {
        int sx = from.sizeX(), sy = from.sizeY(), sz = from.sizeZ();
        CellResampler.SourceCells cells = new CellResampler.SourceCells() {
            @Override
            public int state(int x, int y, int z) {
                if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) return -1;
                return world.get(from.min().x() + x, from.min().y() + y, from.min().z() + z);
            }

            @Override
            public boolean solid(int state) {
                return state != air;
            }
        };
        CellResampler base = o.resampler(sx, sy, sz);
        int[] taken = new int[3];
        ResampledCopy copy = ResampledCopy.of(base, cells, new OrientedStates(states, o), false, null,
                ResampledCopy.Tables.shared(states),
                (x, y, z) -> base.source(x, y, z, cells, taken) && cells.state(taken[0], taken[1], taken[2]) >= 0);
        BlockPos at = from.min().add(o.bounds(sx, sy, sz).offset());
        List<BlockPos> out = new ArrayList<>();
        int[] cell = new int[3];
        BlockPos size = base.size();
        for (int x = 0; x < size.x(); x++) {
            for (int y = 0; y < size.y(); y++) {
                for (int z = 0; z < size.z(); z++) {
                    if (base.source(x, y, z, cells, cell) && cells.state(cell[0], cell[1], cell[2]) >= 0
                            && !copy.cells().source(x, y, z, cells, cell)) {
                        out.add(at.offset(x, y, z));
                    }
                }
            }
        }
        return out;
    }

    @Test
    void exactCountLosesNoBlockNextToPairs() {
        // A wall of doors side by side: turned, their halves crowd each other. Exact count keeps every block but the
        // pairs it leaves out whole; none is lost unseen.
        Clipboard.Builder builder = Clipboard.builder(states, new BlockPos(8, 4, 3)).anchor(BlockPos.ORIGIN);
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 3; z++) {
                builder.set(x, 0, z, stone);
                builder.set(x, 3, z, stone);
            }
            builder.set(x, 1, 1, lower);
            builder.set(x, 2, 1, upper);
        }
        Clipboard clipboard = builder.build();
        sources.put(ref, clipboard.toSource());
        BlockPos size = clipboard.size();
        long blocks = 8 * 3 * 2 + 16;
        for (int[] a : new int[][] {{0, 3_000, 0}, {0, 0, 2_000}, {3_000, 2_000, 1_000}, {4_500, 0, 4_500}}) {
            Orientation o = Orientation.free(Transform.IDENTITY, a[0], a[1], a[2], Resampling.EXACT, false);
            ResampledCopy copy = ResampledCopy.of(o.resampler(size.x(), size.y(), size.z()),
                    new BufferCells(clipboard.toSource().cells(), size, states, false), new OrientedStates(states, o),
                    false, null);
            assertEquals(0, copy.lostBlocks(), o + ": exact count loses no displaced block");
            FakeWorld world = new FakeWorld(states);
            BlockPos origin = new BlockPos(30, 80, 30);
            FakeExecutor.run(OpCompiler.compile(new OpSpec.Paste(ref, origin, o, PasteOptions.DEFAULT), context), world);
            Box target = PasteGeometry.pasteTarget(size, clipboard.anchor(), o, origin);
            long written = 0;
            for (int x = target.min().x(); x <= target.max().x(); x++) {
                for (int y = target.min().y(); y <= target.max().y(); y++) {
                    for (int z = target.min().z(); z <= target.max().z(); z++) if (world.get(x, y, z) != air) written++;
                }
            }
            assertEquals(blocks - 2L * copy.droppedPairs(), written, o + ": every block once, pairs whole or left out");
        }
    }

    // ---------------------------------------------------------------- partial jobs, stamps, symmetry

    @Test
    void aFreeMoveCutShortUndoesExactly() {
        Box from = box(10, 64, 10, 29, 69, 29);
        for (Resampling method : List.of(Resampling.EXACT, Resampling.SMOOTH)) {
            Orientation o = Orientation.free(Transform.IDENTITY, 3_000, 1_500, 0, method, true);
            FakeWorld world = new FakeWorld(states);
            Random random = new Random(9);
            for (int x = 10; x <= 29; x++) {
                for (int y = 64; y <= 69; y++) {
                    for (int z = 10; z <= 29; z++) if (random.nextInt(3) > 0) world.set(x, y, z, stone);
                }
            }
            world.set(15, 65, 15, lower);
            world.set(15, 66, 15, upper);
            Box area = grow(from, 8);
            Map<BlockPos, Cell> original = snapshot(world, area);
            EditProgram program = OpCompiler.compile(new OpSpec.Move(new Region.Cuboid(from), new BlockPos(3, 0, 0), o,
                    new Pattern.Single(air), EntityFilter.NONE, Symmetry.NONE, PasteOptions.Into.EVERYTHING), context);
            assertTrue(program.sectionOrder().length > 2, "a job of several sections");
            FakeExecutor.Result cut = FakeExecutor.run(program, world, 2);
            HistoryEntry entry = new HistoryEntry(UUID.randomUUID(), UUID.randomUUID(), "minecraft:overworld",
                    program.label(), cut.record(), 0L);
            FakeExecutor.Result undo = FakeExecutor.run(HistoryPrograms.undo(entry, ConflictPolicy.SKIP_CONFLICTS), world);
            assertEquals(0, undo.conflicts(), o + ": undo conflicts");
            assertWorld(original, world, o + " cut short, then undone");
        }
    }

    @Test
    void stampsAndSymmetricCopiesUndoExactlyInEveryMethod() {
        Clipboard.Builder builder = Clipboard.builder(states, new BlockPos(6, 4, 5)).anchor(new BlockPos(3, 0, 2));
        Random random = new Random(4);
        for (int x = 0; x < 6; x++) {
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 5; z++) {
                    builder.set(x, y, z, y == 3 ? states.state("minecraft:oak_planks") : random.nextBoolean() ? stone : air);
                }
            }
        }
        builder.set(2, 1, 2, lower);
        builder.set(2, 2, 2, upper);
        Clipboard clipboard = builder.build();
        sources.put(ref, clipboard.toSource());
        Symmetry mirror = new Symmetry(Symmetry.Mode.MIRROR_X, 41, 0);
        List<OpSpec.PasteMany.Placement> placements = List.of(
                new OpSpec.PasteMany.Placement(new BlockPos(10, 70, 0),
                        Orientation.free(Transform.IDENTITY, 3_000, 1_000, 0, Resampling.EXACT, true)),
                new OpSpec.PasteMany.Placement(new BlockPos(12, 70, 3),
                        Orientation.free(Transform.IDENTITY, 2_000, 0, 1_500, Resampling.SMOOTH, true)),
                new OpSpec.PasteMany.Placement(new BlockPos(8, 70, 10),
                        Orientation.free(Transform.IDENTITY, 4_500, 2_000, 0, Resampling.NEAREST, true)));
        FakeWorld world = new FakeWorld(states);
        Box area = box(-10, 60, -15, 50, 85, 25);
        world.fill(box(-10, 60, -15, 50, 64, 25), dirt);
        runAndUndo(OpCompiler.compile(new OpSpec.PasteMany(ref, placements, PasteOptions.DEFAULT, mirror), context), world,
                area, "stamp");
        Box from = box(5, 65, 5, 10, 68, 9);
        for (Resampling method : Resampling.values()) {
            Orientation o = Orientation.free(Transform.IDENTITY, 3_300, 1_200, 0, method, true);
            FakeWorld moving = new FakeWorld(states);
            for (int x = 5; x <= 10; x++) {
                for (int y = 65; y <= 68; y++) for (int z = 5; z <= 9; z++) moving.set(x, y, z, stone);
            }
            moving.set(7, 66, 7, lower);
            moving.set(7, 67, 7, upper);
            OpSpec.Move move = new OpSpec.Move(new Region.Cuboid(from), new BlockPos(0, 0, 4), o,
                    new Pattern.Single(air), EntityFilter.NONE, mirror, PasteOptions.Into.EVERYTHING);
            runAndUndo(OpCompiler.compile(move, context), moving, box(-20, 55, -10, 60, 80, 30), o.toString());
        }
    }

    // ---------------------------------------------------------------- a bed across a chunk border

    private final int foot = states.state("minecraft:red_bed[facing=east,part=foot]");
    private final int head = states.state("minecraft:red_bed[facing=east,part=head]");

    /** Hooks that, while a section of one of the two chunk columns x 0 and x 1 (z 0) is computed, unload the other. */
    private static FakeExecutor.Hooks unloadingTheOtherColumn() {
        return new FakeExecutor.Hooks() {
            @Override
            public void beforeCompute(long key, FakeWorld world) {
                int cx = dev.sculptory.core.buffer.BlockBuffer.keyX(key);
                world.setLoaded(0, 0, cx != 1);
                world.setLoaded(1, 0, cx != 0);
            }

            @Override
            public void afterCompute(long key, FakeWorld world) {
                world.setLoaded(0, 0, true);
                world.setLoaded(1, 0, true);
            }
        };
    }

    /** The bed halves (and where) in {@code area}. */
    private List<BlockPos> bedHalves(FakeWorld world, Box area) {
        List<BlockPos> out = new ArrayList<>();
        for (int x = area.min().x(); x <= area.max().x(); x++) {
            for (int y = area.min().y(); y <= area.max().y(); y++) {
                for (int z = area.min().z(); z <= area.max().z(); z++) {
                    if (states.format(world.get(x, y, z)).contains("red_bed")) out.add(new BlockPos(x, y, z));
                }
            }
        }
        return out;
    }

    @Test
    void aBedAcrossAChunkBorderPastesWholeOrNotAtAll() {
        Clipboard.Builder builder = Clipboard.builder(states, new BlockPos(4, 2, 3)).anchor(BlockPos.ORIGIN);
        for (int x = 0; x < 4; x++) for (int z = 0; z < 3; z++) builder.set(x, 0, z, stone);
        builder.set(1, 1, 1, foot);
        builder.set(2, 1, 1, head);
        Clipboard clipboard = builder.build();
        sources.put(ref, clipboard.toSource());
        for (Resampling method : Resampling.values()) {
            Orientation o = Orientation.free(Transform.IDENTITY, 2_000, 0, 0, method, false);
            // Placed so the foot lands at x 15 and the head at x 16, either side of the chunk border.
            List<BlockPos> at = doorCells(clipboard, o, new BlockPos(40, 70, 4));
            assertEquals(2, at.size(), o + ": the bed is placed");
            int west = Math.min(at.get(0).x(), at.get(1).x());
            assertEquals(1, Math.abs(at.get(0).x() - at.get(1).x()), o + ": the bed lies east-west");
            BlockPos origin = new BlockPos(40 + 15 - west, 70, 4);
            for (PasteOptions.Into into : List.of(PasteOptions.Into.EVERYTHING, PasteOptions.Into.AIR)) {
                PasteOptions options = new PasteOptions(false, false, false, into);
                Box area = grow(PasteGeometry.pasteTarget(clipboard.size(), clipboard.anchor(), o, origin), 2);
                FakeWorld world = new FakeWorld(states);
                runAndUndo(OpCompiler.compile(new OpSpec.Paste(ref, origin, o, options), context), world, area,
                        o + " " + into);
                List<BlockPos> halves = bedHalves(world, area);
                assertEquals(2, halves.size(), o + " " + into + ": the bed across the border, whole");
                assertTrue(halves.stream().anyMatch(p -> p.x() == 15) && halves.stream().anyMatch(p -> p.x() == 16));
                // The other half's chunk unloaded while one half is judged: a paste into air places neither half.
                FakeWorld unloaded = new FakeWorld(states);
                FakeExecutor.run(OpCompiler.compile(new OpSpec.Paste(ref, origin, o, options), context), unloaded,
                        unloadingTheOtherColumn());
                int count = bedHalves(unloaded, area).size();
                assertTrue(count == 2 || into != PasteOptions.Into.EVERYTHING && count == 0,
                        o + " " + into + ": " + count + " halves with the other chunk unloaded");
            }
        }
    }

    @Test
    void aBedAcrossAChunkBorderMovesWholeEvenWhenTheOtherChunkIsNotLoaded() {
        Box from = box(12, 64, 0, 19, 65, 4);
        for (Resampling method : Resampling.values()) {
            Orientation o = Orientation.free(Transform.IDENTITY, 2_000, 0, 0, method, false);
            for (PasteOptions.Into into : List.of(PasteOptions.Into.EVERYTHING, PasteOptions.Into.AIR)) {
                for (boolean unload : new boolean[] {false, true}) {
                    FakeWorld world = new FakeWorld(states);
                    for (int x = 12; x <= 19; x++) for (int z = 0; z <= 4; z++) world.set(x, 64, z, stone);
                    world.set(15, 65, 2, foot);
                    world.set(16, 65, 2, head);
                    OpSpec.Move move = new OpSpec.Move(new Region.Cuboid(from), new BlockPos(0, 0, 6), o,
                            new Pattern.Single(air), EntityFilter.NONE, Symmetry.NONE, into);
                    Box area = grow(box(12, 64, 0, 19, 65, 10), 4);
                    String what = o + " " + into + (unload ? ", the other chunk unloaded while judged" : "");
                    if (unload) {
                        FakeExecutor.run(OpCompiler.compile(move, context), world, unloadingTheOtherColumn());
                    } else {
                        runAndUndo(OpCompiler.compile(move, context), world, area, what);
                    }
                    List<BlockPos> halves = bedHalves(world, area);
                    assertEquals(2, halves.size(), what + ": the bed moved whole, not lost: " + halves);
                    BlockPos a = halves.get(0), b = halves.get(1);
                    assertEquals(1, Math.abs(a.x() - b.x()) + Math.abs(a.y() - b.y()) + Math.abs(a.z() - b.z()),
                            what + ": its halves side by side");
                }
            }
        }
    }
}
