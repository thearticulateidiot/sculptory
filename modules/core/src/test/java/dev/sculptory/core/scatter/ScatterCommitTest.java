package dev.sculptory.core.scatter;

import static dev.sculptory.core.scatter.ScatterFixture.context;
import static dev.sculptory.core.scatter.ScatterFixture.plan;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.buffer.NbtBytes;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.edit.CompileContext;
import dev.sculptory.core.edit.EditProgram;
import dev.sculptory.core.edit.EditTooLargeException;
import dev.sculptory.core.edit.MultiPaste;
import dev.sculptory.core.edit.OpCompiler;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.edit.SectionOrder;
import dev.sculptory.core.edit.SourceBlocks;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.history.HistoryEntry;
import dev.sculptory.core.history.HistoryPrograms;
import dev.sculptory.core.nbt.BlockEntityNbt;
import dev.sculptory.core.nbt.NbtCompound;
import dev.sculptory.core.scatter.ScatterFixture.Spec;
import dev.sculptory.core.scatter.ScatterSettings.Density;
import dev.sculptory.core.scatter.ScatterSettings.Transforms;
import dev.sculptory.core.scatter.ScatterSettings.Variant;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeExecutor;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.core.transform.Mirror;
import dev.sculptory.core.transform.Transform;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ScatterCommitTest {
    private final ScatterFixture f = new ScatterFixture();
    private final UUID planId = UUID.randomUUID();

    private record Cell(int state, BlockEntityData tile) {}

    private List<Clipboard> sources() {
        return List.of(f.tree(), f.slab(3, 1, f.stone), f.single(f.dirt), chestAsset());
    }

    /** A chest (east-facing, with items) on a log: exercises tiles and state rotation. */
    private Clipboard chestAsset() {
        NbtBytes items = BlockEntityNbt.toNbtBytes("minecraft:chest",
                NbtCompound.builder().putString("CustomName", "\"loot\"").build());
        return Clipboard.builder(f.states, new BlockPos(1, 2, 1))
                .set(0, 0, 0, f.log)
                .set(0, 1, 0, f.states.state("minecraft:chest[facing=east]"))
                .setTile(0, 1, 0, items)
                .build();
    }

    private FakeWorld world() {
        FakeWorld world = f.terrain(-10, -10, 60, 60, ScatterFixture::hills, f.grass);
        // Short grass on some columns, which placements overwrite.
        for (int x = -10; x <= 60; x += 2) {
            for (int z = -10; z <= 60; z += 3) world.set(x, ScatterFixture.hills(x, z) + 1, z, f.shortGrass);
        }
        return world;
    }

    private ScatterPlan busyPlan(FakeWorld world) {
        Spec spec = Spec.box(-5, -5, 55, 55).spacing(4).transforms(Transforms.ALL)
                .variants(new Variant(0, 3), new Variant(1, 2), new Variant(2, 1), new Variant(3, 1))
                .density(new Density.Fraction(0.7));
        ScatterPlan plan = plan(spec, sources(), world);
        assertTrue(plan.placements().size() > 40, plan.toString());
        for (Transform t : Transform.all()) {
            assertTrue(plan.placements().stream().anyMatch(p -> p.transform().equals(t)), "uses " + t);
        }
        return plan;
    }

    private EditProgram compile(MultiPaste paste, long maxCells) {
        return OpCompiler.compile(new OpSpec.ScatterCommit(planId), context(f.states, planId, paste, maxCells));
    }

    @Test
    void commitWritesExactlyWhatPastingEachPlacementWrites() {
        FakeWorld world = world();
        ScatterPlan plan = busyPlan(world);
        Box region = grow(plan.bounds().orElseThrow(), 2);
        Map<BlockPos, Cell> before = snapshot(world, region);

        EditProgram program = compile(plan.toMultiPaste(), Long.MAX_VALUE);
        assertEquals("Scatter", program.label());
        assertEquals(plan.totalCells(), program.estimatedCells());
        assertEquals(plan.bounds().orElseThrow(), program.bounds());
        long[] order = program.sectionOrder();
        long[] sorted = order.clone();
        SectionOrder.sort(sorted);
        assertArrayEquals(sorted, order);
        assertEquals(0, program.sourceSections().length);
        FakeExecutor.Result result = FakeExecutor.run(program, world);
        assertEquals(plan.totalCells(), result.written(), "every footprint cell changes here");

        // Oracle: the same placements as individual pastes.
        FakeWorld oracle = world();
        Map<SourceRef, SourceBlocks> refs = new HashMap<>();
        List<SourceRef> byVariant = new ArrayList<>();
        for (Clipboard source : sources()) {
            SourceRef ref = new SourceRef.Clipboard(UUID.randomUUID());
            refs.put(ref, source.toSource());
            byVariant.add(ref);
        }
        CompileContext pasteContext = pasteContext(f.states, refs);
        for (ScatterPlan.Placement p : plan.placements()) {
            SourceRef ref = byVariant.get(plan.settings().variants().get(p.variant()).source());
            FakeExecutor.run(OpCompiler.compile(new OpSpec.Paste(ref, p.anchor(), p.transform(), PasteOptions.DEFAULT),
                    pasteContext), oracle);
        }
        assertEquals(snapshot(oracle, region), snapshot(world, region));

        // And directly: every footprint cell holds its transformed state; nothing else changed.
        Map<BlockPos, Cell> expected = new HashMap<>(before);
        for (ScatterPlan.Placement p : plan.placements()) {
            Clipboard source = sources().get(plan.settings().variants().get(p.variant()).source());
            BlockPos size = source.size();
            BlockPos a = p.transform().apply(source.anchor(), size);
            source.forEachCell((x, y, z, state, tile) -> {
                if (state < 0 || state == f.air) return;
                BlockPos t = p.transform().apply(x, y, z, size.x(), size.y(), size.z());
                expected.put(p.anchor().offset(t.x() - a.x(), t.y() - a.y(), t.z() - a.z()),
                        new Cell(p.transform().applyToState(f.states, state), tile));
            });
        }
        assertEquals(expected, snapshot(world, region));
    }

    @Test
    void undoRestoresExactlyAndRedoReapplies() {
        FakeWorld world = world();
        ScatterPlan plan = busyPlan(world);
        Box region = grow(plan.bounds().orElseThrow(), 2);
        Map<BlockPos, Cell> original = snapshot(world, region);

        FakeExecutor.Result result = FakeExecutor.run(compile(plan.toMultiPaste(), Long.MAX_VALUE), world);
        Map<BlockPos, Cell> after = snapshot(world, region);
        HistoryEntry entry = new HistoryEntry(UUID.randomUUID(), UUID.randomUUID(), "minecraft:overworld", "Scatter",
                result.record(), 0L);
        assertEquals(plan.totalCells(), result.record().before().cellCount(), "one history entry holds every cell");

        FakeExecutor.Result undo = FakeExecutor.run(HistoryPrograms.undo(entry, ConflictPolicy.SKIP_CONFLICTS), world);
        assertEquals(0, undo.conflicts());
        assertEquals(original, snapshot(world, region));
        FakeExecutor.Result redo = FakeExecutor.run(HistoryPrograms.redo(entry, ConflictPolicy.SKIP_CONFLICTS), world);
        assertEquals(0, redo.conflicts());
        assertEquals(after, snapshot(world, region));
    }

    @Test
    void aCancelledCommitUndoesExactly() {
        FakeWorld world = world();
        ScatterPlan plan = busyPlan(world);
        Box region = grow(plan.bounds().orElseThrow(), 2);
        Map<BlockPos, Cell> original = snapshot(world, region);
        EditProgram program = compile(plan.toMultiPaste(), Long.MAX_VALUE);
        FakeExecutor.Result partial = FakeExecutor.run(program, world, program.sectionOrder().length / 2);
        assertTrue(partial.written() > 0 && partial.written() < plan.totalCells());
        HistoryEntry entry = new HistoryEntry(UUID.randomUUID(), UUID.randomUUID(), "minecraft:overworld", "Scatter",
                partial.record(), 0L);
        FakeExecutor.run(HistoryPrograms.undo(entry, ConflictPolicy.SKIP_CONFLICTS), world);
        assertEquals(original, snapshot(world, region));
    }

    @Test
    void refusesUnknownOversizedAndEmptyPlans() {
        FakeWorld world = world();
        ScatterPlan plan = busyPlan(world);
        MultiPaste paste = plan.toMultiPaste();
        assertThrows(EditTooLargeException.class, () -> compile(paste, plan.totalCells() - 1));
        compile(paste, plan.totalCells());
        assertThrows(IllegalArgumentException.class, () -> OpCompiler.compile(new OpSpec.ScatterCommit(UUID.randomUUID()),
                context(f.states, planId, paste, Long.MAX_VALUE)));
        assertThrows(IllegalArgumentException.class, () -> compile(new MultiPaste(List.of(), List.of()), Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> new MultiPaste(List.of(f.single(f.dirt).toSource()),
                List.of(new MultiPaste.Placement(BlockPos.ORIGIN, 1, Transform.IDENTITY))));
        // A source state outside the state space.
        SourceBlocks bad = new SourceBlocks(new BlockBuffer(), new BlockPos(1, 1, 1), BlockPos.ORIGIN);
        bad.cells().set(0, 0, 0, f.states.size());
        assertThrows(IllegalArgumentException.class, () -> compile(new MultiPaste(List.of(bad),
                List.of(new MultiPaste.Placement(BlockPos.ORIGIN, 0, Transform.IDENTITY))), Long.MAX_VALUE));
    }

    @Test
    void theLaterPlacementWinsWhereTheyOverlap() {
        SourceBlocks stoneCube = cube(f.stone), dirtCube = cube(f.dirt);
        // Across a section boundary (x 15/16), and with the later placement's value already in the world there.
        List<MultiPaste.Placement> placements = List.of(
                new MultiPaste.Placement(new BlockPos(14, 64, 0), 0, Transform.IDENTITY),
                new MultiPaste.Placement(new BlockPos(15, 64, 1), 1, Transform.IDENTITY));
        FakeWorld world = new FakeWorld(f.states);
        world.set(15, 64, 1, f.dirt);
        FakeExecutor.run(compile(new MultiPaste(List.of(stoneCube, dirtCube), placements), Long.MAX_VALUE), world);
        for (int y = 64; y <= 65; y++) {
            assertEquals(f.stone, world.get(14, y, 0));
            assertEquals(f.stone, world.get(15, y, 0));
            assertEquals(f.stone, world.get(14, y, 1));
            assertEquals(f.dirt, world.get(15, y, 1), "overlap: the later placement");
            assertEquals(f.dirt, world.get(16, y, 1));
            assertEquals(f.dirt, world.get(16, y, 2));
        }

        FakeWorld reversed = new FakeWorld(f.states);
        FakeExecutor.run(compile(new MultiPaste(List.of(stoneCube, dirtCube), List.of(placements.get(1), placements.get(0))),
                Long.MAX_VALUE), reversed);
        assertEquals(f.stone, reversed.get(15, 64, 1));
        assertEquals(f.dirt, reversed.get(16, 65, 2));
    }

    @Test
    void tilesAreCopiedAndStatesTurned() {
        FakeWorld world = f.flat(0, 0, 15, 15, 64);
        Clipboard chest = chestAsset();
        MultiPaste paste = new MultiPaste(List.of(chest.toSource()), List.of(
                new MultiPaste.Placement(new BlockPos(3, 65, 3), 0, new Transform(1, Mirror.NONE)),
                new MultiPaste.Placement(new BlockPos(8, 65, 8), 0, new Transform(0, Mirror.X))));
        FakeExecutor.run(compile(paste, Long.MAX_VALUE), world);
        assertEquals(f.states.state("minecraft:chest[facing=south]"), world.get(3, 66, 3));
        assertEquals(f.states.state("minecraft:chest[facing=west]"), world.get(8, 66, 8));
        BlockEntityData tile = world.tile(3, 66, 3);
        assertTrue(tile != null && tile.sameContent(chest.tile(0, 1, 0)));
        assertTrue(Objects.requireNonNull(world.tile(8, 66, 8)).sameContent(chest.tile(0, 1, 0)));
    }

    @Test
    void cellsOutsideTheBuildHeightAreClipped() {
        Clipboard.Builder column = Clipboard.builder(f.states, new BlockPos(1, 10, 1));
        for (int y = 0; y < 10; y++) column.set(0, y, 0, f.log);
        MultiPaste paste = new MultiPaste(List.of(column.build().toSource()), List.of(
                new MultiPaste.Placement(new BlockPos(0, 315, 0), 0, Transform.IDENTITY),
                new MultiPaste.Placement(new BlockPos(5, 400, 5), 0, Transform.IDENTITY)));
        EditProgram program = compile(paste, Long.MAX_VALUE);
        assertEquals(5, program.estimatedCells());
        FakeWorld world = new FakeWorld(f.states);
        assertEquals(5, FakeExecutor.run(program, world).written());
        assertEquals(f.log, world.get(0, 319, 0));

        MultiPaste above = new MultiPaste(paste.sources(), List.of(paste.placements().get(1)));
        assertThrows(IllegalArgumentException.class, () -> compile(above, Long.MAX_VALUE));
    }

    // ------------------------------------------------------------ helpers

    private SourceBlocks cube(int state) {
        Clipboard.Builder b = Clipboard.builder(f.states, new BlockPos(2, 2, 2));
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 2; y++) {
                for (int z = 0; z < 2; z++) b.set(x, y, z, state);
            }
        }
        return b.build().toSource();
    }

    private static CompileContext pasteContext(StateSpace states, Map<SourceRef, SourceBlocks> sources) {
        return new CompileContext() {
            @Override
            public StateSpace states() {
                return states;
            }

            @Override
            public Optional<SourceBlocks> source(SourceRef ref) {
                return Optional.ofNullable(sources.get(ref));
            }

            @Override
            public int bottomY() {
                return -64;
            }

            @Override
            public int topYExclusive() {
                return 320;
            }
        };
    }

    private static Map<BlockPos, Cell> snapshot(FakeWorld world, Box box) {
        Map<BlockPos, Cell> cells = new HashMap<>();
        for (int x = box.min().x(); x <= box.max().x(); x++) {
            for (int y = box.min().y(); y <= box.max().y(); y++) {
                for (int z = box.min().z(); z <= box.max().z(); z++) {
                    cells.put(new BlockPos(x, y, z), new Cell(world.get(x, y, z), world.tile(x, y, z)));
                }
            }
        }
        return cells;
    }

    private static Box grow(Box box, int margin) {
        return new Box(box.min().offset(-margin, -margin, -margin), box.max().offset(margin, margin, margin));
    }
}
