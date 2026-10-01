package dev.sculptory.core.history;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.buffer.NbtBytes;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.CompileContext;
import dev.sculptory.core.edit.EditProgram;
import dev.sculptory.core.edit.OpCompiler;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.edit.SectionOrder;
import dev.sculptory.core.edit.SourceBlocks;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeExecutor;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Undo/redo over real buffers: exact round trips, conflict handling, partial jobs and tiles. */
class HistoryProgramsTest {
    private final FakeStateSpace states = new FakeStateSpace();
    private final CompileContext context = new CompileContext() {
        @Override
        public StateSpace states() {
            return states;
        }

        @Override
        public Optional<SourceBlocks> source(SourceRef ref) {
            return Optional.empty();
        }
    };
    private final int air = states.air();
    private final int stone = states.state("minecraft:stone");
    private final int dirt = states.state("minecraft:dirt");
    private final int sand = states.state("minecraft:sand");
    private final int chest = states.state("minecraft:chest[facing=east]");
    private final int water = states.state("minecraft:water");
    private final int loggedStairs = states.state("minecraft:oak_stairs[waterlogged=true]");
    private final NbtBytes chestItems = new NbtBytes("minecraft:chest", new byte[] {10, 9, 9, 0});

    /** Spans eight sections, with a negative corner. */
    private final Box area = Box.of(new BlockPos(-10, 60, -10), new BlockPos(9, 69, 9));
    private FakeWorld world;

    @BeforeEach
    void terrain() {
        world = new FakeWorld(states);
        for (int x = -10; x <= 9; x++) {
            for (int z = -10; z <= 9; z++) {
                int top = 62 + Math.floorMod(x * 3 + z * 5, 5);
                for (int y = 60; y <= top; y++) world.set(x, y, z, y == top ? dirt : stone);
            }
        }
        world.set(0, 65, 0, water);
        world.set(1, 67, 1, loggedStairs);
        world.set(-3, 66, 4, chest);
        world.setTile(-3, 66, 4, chestItems);
    }

    private HistoryEntry fill(Box box, int state) {
        EditProgram program = OpCompiler.compile(new OpSpec.Fill(box, new Pattern.Single(state), CellMask.ANY), context);
        FakeExecutor.Result result = FakeExecutor.run(program, world);
        return entry(program.label(), result.record());
    }

    private static HistoryEntry entry(String label, EditRecord record) {
        return new HistoryEntry(UUID.randomUUID(), new UUID(0, 1), "minecraft:overworld", label, record, 0L);
    }

    @Test
    void undoRedoRoundTripIsExact() {
        BlockBuffer original = snapshot();
        HistoryEntry e = fill(area, sand);
        BlockBuffer filled = snapshot();
        assertEquals(sand, world.get(-3, 66, 4));
        assertNull(world.tile(-3, 66, 4));

        FakeExecutor.Result undo = FakeExecutor.run(HistoryPrograms.undo(e, ConflictPolicy.SKIP_CONFLICTS), world);
        assertEquals(0, undo.conflicts());
        assertEquals(e.record().before().cellCount(), undo.written());
        assertSameContent(original, snapshot());
        assertSame(chestItems, world.tile(-3, 66, 4));

        FakeExecutor.Result redo = FakeExecutor.run(HistoryPrograms.redo(e, ConflictPolicy.SKIP_CONFLICTS), world);
        assertEquals(0, redo.conflicts());
        assertSameContent(filled, snapshot());

        FakeExecutor.run(HistoryPrograms.undo(e, ConflictPolicy.SKIP_CONFLICTS), world);
        assertSameContent(original, snapshot());
    }

    @Test
    void undoSkipsAndCountsConflicts() {
        BlockBuffer original = snapshot();
        HistoryEntry e = fill(area, sand);
        world.set(0, 61, 0, dirt);
        world.set(5, 69, 5, stone);
        world.set(-10, 60, -10, air);
        FakeExecutor.Result undo = FakeExecutor.run(HistoryPrograms.undo(e, ConflictPolicy.SKIP_CONFLICTS), world);
        assertEquals(3, undo.conflicts());
        assertEquals(e.record().before().cellCount() - 3, undo.written());
        assertEquals(dirt, world.get(0, 61, 0));
        assertEquals(stone, world.get(5, 69, 5));
        assertEquals(air, world.get(-10, 60, -10));
        // Everything else is back.
        world.set(0, 61, 0, original.get(0, 61, 0));
        world.set(5, 69, 5, original.get(5, 69, 5));
        world.set(-10, 60, -10, original.get(-10, 60, -10));
        assertSameContent(original, snapshot());
    }

    /** A hook that sets cell (x, y, z) to {@code state} right after the section holding it is computed. */
    private static FakeExecutor.Hooks changeAfterCompute(int x, int y, int z, int state) {
        long section = BlockBuffer.keyOfBlock(x, y, z);
        return new FakeExecutor.Hooks() {
            @Override
            public void afterCompute(long key, FakeWorld w) {
                if (key == section) w.set(x, y, z, state);
            }
        };
    }

    /**
     * A cell changed after its section was computed but before it is written (an undo or redo written over several
     * ticks) is a conflict too: left alone and reported. Redo after that partial undo, and undo again, stay exact for
     * every other cell.
     */
    @Test
    void cellsChangedWhileTheSectionIsWrittenAreConflicts() {
        BlockBuffer original = snapshot();
        HistoryEntry e = fill(area, sand);
        BlockBuffer filled = snapshot();
        FakeExecutor.Result undo = FakeExecutor.run(HistoryPrograms.undo(e, ConflictPolicy.SKIP_CONFLICTS), world,
                changeAfterCompute(4, 63, 4, chest));
        assertEquals(1, undo.conflicts());
        assertEquals(1, undo.refused());
        assertEquals(chest, world.get(4, 63, 4), "the undo overwrote the chest");
        world.set(4, 63, 4, original.get(4, 63, 4));
        assertSameContent(original, snapshot());
        world.set(4, 63, 4, chest);

        FakeExecutor.Result redo = FakeExecutor.run(HistoryPrograms.redo(e, ConflictPolicy.SKIP_CONFLICTS), world,
                changeAfterCompute(-5, 66, -5, stone));
        assertEquals(2, redo.conflicts(), "the chest (at compute) and the stone (at write)");
        assertEquals(chest, world.get(4, 63, 4));
        assertEquals(stone, world.get(-5, 66, -5), "the redo overwrote the stone");
        world.set(4, 63, 4, filled.get(4, 63, 4));
        world.set(-5, 66, -5, filled.get(-5, 66, -5));
        assertSameContent(filled, snapshot());
        world.set(4, 63, 4, chest);
        world.set(-5, 66, -5, stone);

        FakeExecutor.Result again = FakeExecutor.run(HistoryPrograms.undo(e, ConflictPolicy.SKIP_CONFLICTS), world);
        assertEquals(2, again.conflicts());
        world.set(4, 63, 4, original.get(4, 63, 4));
        world.set(-5, 66, -5, original.get(-5, 66, -5));
        assertSameContent(original, snapshot());

        // Under OVERWRITE the write-time check lets everything through.
        HistoryEntry e2 = fill(area, sand);
        FakeExecutor.Result forced = FakeExecutor.run(HistoryPrograms.undo(e2, ConflictPolicy.OVERWRITE), world,
                changeAfterCompute(4, 63, 4, chest));
        assertEquals(0, forced.refused());
        assertEquals(original.get(4, 63, 4), world.get(4, 63, 4));
    }

    @Test
    void overwriteWritesConflictingCells() {
        BlockBuffer original = snapshot();
        HistoryEntry e = fill(area, sand);
        world.set(0, 61, 0, dirt);
        world.set(5, 69, 5, stone);
        FakeExecutor.Result undo = FakeExecutor.run(HistoryPrograms.undo(e, ConflictPolicy.OVERWRITE), world);
        assertEquals(0, undo.conflicts());
        assertSameContent(original, snapshot());
    }

    @Test
    void redoSkipsCellsChangedSinceTheUndo() {
        HistoryEntry e = fill(area, sand);
        BlockBuffer filled = snapshot();
        FakeExecutor.run(HistoryPrograms.undo(e, ConflictPolicy.SKIP_CONFLICTS), world);
        world.set(2, 64, 2, chest);
        FakeExecutor.Result redo = FakeExecutor.run(HistoryPrograms.redo(e, ConflictPolicy.SKIP_CONFLICTS), world);
        assertEquals(1, redo.conflicts());
        assertEquals(chest, world.get(2, 64, 2));
        FakeExecutor.Result overwrite = FakeExecutor.run(HistoryPrograms.redo(e, ConflictPolicy.OVERWRITE), world);
        assertEquals(1, overwrite.written());
        assertSameContent(filled, snapshot());
    }

    @Test
    void cellsAlreadyRestoredAreNotConflicts() {
        BlockBuffer original = snapshot();
        HistoryEntry e = fill(area, sand);
        world.set(3, 63, 3, original.get(3, 63, 3));
        FakeExecutor.Result undo = FakeExecutor.run(HistoryPrograms.undo(e, ConflictPolicy.SKIP_CONFLICTS), world);
        assertEquals(0, undo.conflicts());
        assertEquals(e.record().before().cellCount() - 1, undo.written());
        assertSameContent(original, snapshot());
    }

    /**
     * An edit that changed only a chest's contents undoes and redoes exactly while nobody touches it. Once someone
     * changes the contents again, the chest counts as changed since the step: kept and counted under SKIP_CONFLICTS,
     * written under OVERWRITE.
     */
    @Test
    void anNbtOnlyEditUndoesWhileUntouchedAndKeepsContentsChangedSince() {
        NbtBytes emptied = new NbtBytes("minecraft:chest", new byte[] {10, 0});
        NbtBytes refilled = new NbtBytes("minecraft:chest", new byte[] {10, 7, 0});
        RecordBuilder builder = new RecordBuilder();
        builder.record(-3, 66, 4, chest, chestItems, chest, emptied);
        world.setTile(-3, 66, 4, emptied);
        HistoryEntry e = entry("Edit chest", builder.build());
        FakeExecutor.Result undo = FakeExecutor.run(HistoryPrograms.undo(e, ConflictPolicy.SKIP_CONFLICTS), world);
        assertEquals(0, undo.conflicts());
        assertEquals(1, undo.written());
        assertSame(chestItems, world.tile(-3, 66, 4));
        FakeExecutor.run(HistoryPrograms.redo(e, ConflictPolicy.SKIP_CONFLICTS), world);
        assertSame(emptied, world.tile(-3, 66, 4));
        // Already at the target with the same content: nothing to write, nothing kept.
        FakeExecutor.Result again = FakeExecutor.run(HistoryPrograms.redo(e, ConflictPolicy.SKIP_CONFLICTS), world);
        assertEquals(0, again.written());
        assertEquals(0, again.conflicts());

        // Someone changed the contents since the redo: the state still matches, the contents do not.
        world.setTile(-3, 66, 4, refilled);
        FakeExecutor.Result kept = FakeExecutor.run(HistoryPrograms.undo(e, ConflictPolicy.SKIP_CONFLICTS), world);
        assertEquals(1, kept.conflicts());
        assertEquals(0, kept.written());
        assertSame(refilled, world.tile(-3, 66, 4), "the contents put in since are kept");
        FakeExecutor.Result overwrite = FakeExecutor.run(HistoryPrograms.undo(e, ConflictPolicy.OVERWRITE), world);
        assertEquals(0, overwrite.conflicts());
        assertEquals(1, overwrite.written());
        assertSame(chestItems, world.tile(-3, 66, 4), "OVERWRITE restores the recorded contents");
    }

    /**
     * An edit placed chests; another player fills one of them. Undo takes back the untouched chests and keeps the filled
     * one, counted with the other kept blocks; OVERWRITE (Undo anyway's policy) removes it too.
     */
    @Test
    void undoKeepsAContainerFilledSinceTheEdit() {
        NbtBytes empty = new NbtBytes("minecraft:chest", new byte[] {10, 0});
        NbtBytes filled = new NbtBytes("minecraft:chest", new byte[] {10, 9, 9, 9, 0});
        RecordBuilder builder = new RecordBuilder();
        for (int x = 0; x < 3; x++) builder.record(x, 72, 0, air, null, chest, empty);
        builder.record(5, 72, 0, air, null, stone, null);
        HistoryEntry e = entry("Place chests", builder.build());
        for (int x = 0; x < 3; x++) {
            world.set(x, 72, 0, chest);
            world.setTile(x, 72, 0, empty);
        }
        world.set(5, 72, 0, stone);
        world.setTile(1, 72, 0, filled);             // another player fills the middle chest
        world.set(5, 72, 0, dirt);                   // and replaces the stone: a state conflict, counted alike

        FakeExecutor.Result undo = FakeExecutor.run(HistoryPrograms.undo(e, ConflictPolicy.SKIP_CONFLICTS), world);
        assertEquals(2, undo.conflicts(), "the filled chest and the replaced stone");
        assertEquals(2, undo.written());
        assertEquals(air, world.get(0, 72, 0));
        assertEquals(air, world.get(2, 72, 0));
        assertEquals(chest, world.get(1, 72, 0));
        assertSame(filled, world.tile(1, 72, 0), "the filled chest keeps what was put in it");
        assertEquals(dirt, world.get(5, 72, 0));

        FakeExecutor.Result anyway = FakeExecutor.run(HistoryPrograms.undo(e, ConflictPolicy.OVERWRITE), world);
        assertEquals(0, anyway.conflicts());
        assertEquals(2, anyway.written());
        assertEquals(air, world.get(1, 72, 0));
        assertNull(world.tile(1, 72, 0));
        assertEquals(air, world.get(5, 72, 0));
    }

    /**
     * The redo side: a fill replaced the chest, undo brought it back with its items, and someone took items out since.
     * Redo keeps that chest (counted); OVERWRITE replaces it.
     */
    @Test
    void redoKeepsAContainerChangedSinceTheUndo() {
        NbtBytes takenOut = new NbtBytes("minecraft:chest", new byte[] {10, 9, 0});
        HistoryEntry e = fill(area, sand);
        FakeExecutor.run(HistoryPrograms.undo(e, ConflictPolicy.SKIP_CONFLICTS), world);
        assertSame(chestItems, world.tile(-3, 66, 4));
        world.setTile(-3, 66, 4, takenOut);

        FakeExecutor.Result redo = FakeExecutor.run(HistoryPrograms.redo(e, ConflictPolicy.SKIP_CONFLICTS), world);
        assertEquals(1, redo.conflicts());
        assertEquals(e.record().before().cellCount() - 1, redo.written());
        assertEquals(chest, world.get(-3, 66, 4));
        assertSame(takenOut, world.tile(-3, 66, 4));

        FakeExecutor.Result anyway = FakeExecutor.run(HistoryPrograms.redo(e, ConflictPolicy.OVERWRITE), world);
        assertEquals(1, anyway.written());
        assertEquals(sand, world.get(-3, 66, 4));
        assertNull(world.tile(-3, 66, 4));
    }

    /**
     * Contents changed after the section was computed but before the cell is written (a section written over several
     * ticks) are a conflict at write time: refused and counted. OVERWRITE lets it through.
     */
    @Test
    void contentsChangedWhileTheSectionIsWrittenAreConflicts() {
        NbtBytes takenOut = new NbtBytes("minecraft:chest", new byte[] {10, 9, 0});
        HistoryEntry e = fill(area, sand);
        FakeExecutor.run(HistoryPrograms.undo(e, ConflictPolicy.SKIP_CONFLICTS), world);
        long section = BlockBuffer.keyOfBlock(-3, 66, 4);
        FakeExecutor.Hooks takeOut = new FakeExecutor.Hooks() {
            @Override
            public void afterCompute(long key, FakeWorld w) {
                if (key == section) w.setTile(-3, 66, 4, takenOut);
            }
        };
        FakeExecutor.Result redo = FakeExecutor.run(HistoryPrograms.redo(e, ConflictPolicy.SKIP_CONFLICTS), world, takeOut);
        assertEquals(1, redo.conflicts());
        assertEquals(1, redo.refused(), "refused at write time, not at compute");
        assertSame(takenOut, world.tile(-3, 66, 4));

        FakeExecutor.run(HistoryPrograms.undo(e, ConflictPolicy.OVERWRITE), world);
        FakeExecutor.Result forced = FakeExecutor.run(HistoryPrograms.redo(e, ConflictPolicy.OVERWRITE), world, takeOut);
        assertEquals(0, forced.refused());
        assertEquals(sand, world.get(-3, 66, 4));
    }

    /**
     * The matcher decides what counts as changed contents. Here one that reads content as the server does: a missing
     * tile (a record made when the edit placed a chest without data, as every record of such a chest is, old journals
     * included) stands for the state's default, and a trailing timer byte is ignored. With it, an untouched chest whose
     * bytes differ from the record undoes; a filled one is kept. With {@link TileMatcher#EXACT} the same untouched chest
     * would be kept.
     */
    @Test
    void theMatcherDecidesWhatCountsAsChangedContents() {
        byte[] defaultChest = {10, 0};
        TileMatcher canonical = (state, live, expected) -> {
            byte[] a = live == null ? defaultChest : live.nbtBytes();
            byte[] b = expected == null ? defaultChest : expected.nbtBytes();
            // The last byte plays a timer the game rewrites by itself.
            return java.util.Arrays.equals(a, 0, a.length - 1, b, 0, b.length - 1);
        };
        RecordBuilder builder = new RecordBuilder();
        builder.record(0, 72, 0, air, null, chest, null);   // placed without data: the default chest
        builder.record(1, 72, 0, air, null, chest, null);
        builder.record(2, 72, 0, air, null, chest, new NbtBytes("minecraft:chest", new byte[] {10, 5, 0}));
        HistoryEntry e = entry("Place chests", builder.build());
        for (int x = 0; x < 3; x++) world.set(x, 72, 0, chest);
        world.setTile(0, 72, 0, new NbtBytes("minecraft:chest", new byte[] {10, 1}));       // the default, timer ticked
        world.setTile(1, 72, 0, new NbtBytes("minecraft:chest", new byte[] {10, 9, 9, 0})); // filled
        world.setTile(2, 72, 0, new NbtBytes("minecraft:chest", new byte[] {10, 5, 3}));    // as placed, timer ticked

        FakeExecutor.Result undo = FakeExecutor.run(
                HistoryPrograms.undo(e, ConflictPolicy.SKIP_CONFLICTS, canonical), world);
        assertEquals(1, undo.conflicts(), "only the filled chest");
        assertEquals(air, world.get(0, 72, 0));
        assertEquals(chest, world.get(1, 72, 0));
        assertEquals(air, world.get(2, 72, 0));

        world.set(0, 72, 0, chest);
        world.setTile(0, 72, 0, new NbtBytes("minecraft:chest", new byte[] {10, 1}));
        world.set(2, 72, 0, chest);
        world.setTile(2, 72, 0, new NbtBytes("minecraft:chest", new byte[] {10, 5, 3}));
        FakeExecutor.Result byBytes = FakeExecutor.run(HistoryPrograms.undo(e, ConflictPolicy.SKIP_CONFLICTS), world);
        assertEquals(3, byBytes.conflicts(), "byte for byte, every chest looks changed");
    }

    /** Regression: a same-state chest placed after the edit keeps its own contents through an undo. */
    @Test
    void undoKeepsNbtAddedAfterTheEdit() {
        NbtBytes newItems = new NbtBytes("minecraft:chest", new byte[] {10, 4, 2, 0});
        HistoryEntry e = fill(area, sand);                  // replaces chest(chestItems) with sand
        world.set(-3, 66, 4, chest);                        // a new chest of the same state...
        world.setTile(-3, 66, 4, newItems);                 // ...holding other items
        FakeExecutor.Result undo = FakeExecutor.run(HistoryPrograms.undo(e, ConflictPolicy.SKIP_CONFLICTS), world);
        assertEquals(1, undo.conflicts());
        assertEquals(chest, world.get(-3, 66, 4));
        assertSame(newItems, world.tile(-3, 66, 4), "the new chest's contents survive");

        FakeExecutor.Result overwrite = FakeExecutor.run(HistoryPrograms.undo(e, ConflictPolicy.OVERWRITE), world);
        assertEquals(0, overwrite.conflicts());
        assertEquals(1, overwrite.written());
        assertSame(chestItems, world.tile(-3, 66, 4), "OVERWRITE restores the recorded contents");
    }

    /** Regression, redo side: an edit that placed chest(A), undone, then a chest(B) placed by hand. */
    @Test
    void redoKeepsNbtAddedAfterTheUndo() {
        NbtBytes placed = new NbtBytes("minecraft:chest", new byte[] {10, 1, 1, 0});
        NbtBytes byHand = new NbtBytes("minecraft:chest", new byte[] {10, 2, 2, 0});
        RecordBuilder builder = new RecordBuilder();
        builder.record(4, 70, 4, air, null, chest, placed);
        HistoryEntry e = entry("Place chest", builder.build());
        world.set(4, 70, 4, chest);
        world.setTile(4, 70, 4, placed);
        FakeExecutor.run(HistoryPrograms.undo(e, ConflictPolicy.SKIP_CONFLICTS), world);
        assertEquals(air, world.get(4, 70, 4));
        world.set(4, 70, 4, chest);
        world.setTile(4, 70, 4, byHand);
        FakeExecutor.Result redo = FakeExecutor.run(HistoryPrograms.redo(e, ConflictPolicy.SKIP_CONFLICTS), world);
        assertEquals(1, redo.conflicts());
        assertEquals(0, redo.written());
        assertSame(byHand, world.tile(4, 70, 4));
        FakeExecutor.run(HistoryPrograms.redo(e, ConflictPolicy.OVERWRITE), world);
        assertSame(placed, world.tile(4, 70, 4));
    }

    @Test
    void partiallyAppliedJobUndoesExactly() {
        BlockBuffer original = snapshot();
        EditProgram program = OpCompiler.compile(new OpSpec.Fill(area, new Pattern.Single(air), CellMask.ANY), context);
        FakeExecutor.Result partial = FakeExecutor.run(program, world, 3);
        assertEquals(3, partial.sections());
        assertTrue(partial.written() > 0);
        HistoryEntry e = entry("Fill (cancelled)", partial.record());
        EditProgram undo = HistoryPrograms.undo(e, ConflictPolicy.SKIP_CONFLICTS);
        assertEquals(3, undo.sectionOrder().length);
        FakeExecutor.run(undo, world);
        assertSameContent(original, snapshot());
    }

    @Test
    void programMetadata() {
        HistoryEntry e = fill(area, sand);
        EditProgram undo = HistoryPrograms.undo(e, ConflictPolicy.SKIP_CONFLICTS);
        EditProgram redo = HistoryPrograms.redo(e, ConflictPolicy.OVERWRITE);
        assertEquals("Undo Fill", undo.label());
        assertEquals("Redo Fill", redo.label());
        assertEquals(e.record().before().cellCount(), undo.estimatedCells());
        assertEquals(e.record().before().bounds(), undo.bounds());
        assertTrue(area.contains(undo.bounds()));
        assertEquals(0, undo.sourceSections().length);
        long[] expected = e.record().before().sortedKeys();
        SectionOrder.sort(expected);
        assertArrayEquals(expected, undo.sectionOrder());
        assertArrayEquals(expected, redo.sectionOrder());
    }

    @Test
    void refusesEmptyRecords() {
        HistoryEntry empty = entry("Nothing", new EditRecord(new BlockBuffer(), new BlockBuffer()));
        assertThrows(IllegalArgumentException.class, () -> HistoryPrograms.undo(empty, ConflictPolicy.SKIP_CONFLICTS));
        assertThrows(IllegalArgumentException.class, () -> HistoryPrograms.redo(empty, ConflictPolicy.OVERWRITE));
    }

    @Test
    void historyStackDrivesUndoAndRedo() {
        BlockBuffer original = snapshot();
        PlayerHistory history = new PlayerHistory(HistoryLimits.DEFAULTS);
        HistoryEntry first = fill(area, sand);
        history.push(first);
        BlockBuffer afterFirst = snapshot();
        history.push(fill(Box.of(new BlockPos(-2, 60, -2), new BlockPos(2, 69, 2)), air));

        HistoryEntry undo = history.undoCandidate().orElseThrow();
        FakeExecutor.run(HistoryPrograms.undo(undo, ConflictPolicy.SKIP_CONFLICTS), world);
        assertTrue(history.markUndone(undo.id()));
        assertSameContent(afterFirst, snapshot());

        undo = history.undoCandidate().orElseThrow();
        FakeExecutor.run(HistoryPrograms.undo(undo, ConflictPolicy.SKIP_CONFLICTS), world);
        assertTrue(history.markUndone(undo.id()));
        assertSameContent(original, snapshot());

        HistoryEntry redo = history.redoCandidate().orElseThrow();
        assertSame(first, redo);
        FakeExecutor.run(HistoryPrograms.redo(redo, ConflictPolicy.SKIP_CONFLICTS), world);
        assertTrue(history.markRedone(redo.id()));
        assertSameContent(afterFirst, snapshot());
    }

    // ---------------------------------------------------------------- Undo anyway / Redo anyway (reapply)

    /** Puts every cell (and tile) of a {@link #snapshot()} back. */
    private void restore(BlockBuffer snapshot) {
        for (int x = area.min().x() - 1; x <= area.max().x() + 1; x++) {
            for (int y = area.min().y() - 1; y <= area.max().y() + 1; y++) {
                for (int z = area.min().z() - 1; z <= area.max().z() + 1; z++) {
                    world.set(x, y, z, snapshot.get(x, y, z));
                    world.setTile(x, y, z, snapshot.tile(x, y, z));
                }
            }
        }
    }

    /**
     * Three overlapping edits (the chest's cell among them), then another player changes cells in every part of them.
     * Undoing the three (newest first) skips those cells; Undo anyway of the run leaves the world exactly as undoing
     * the three with OVERWRITE from the start would have: the original world, since every changed cell is recorded.
     */
    @Test
    void undoAnywayEqualsTheRunUndoneWithOverwrite() {
        BlockBuffer original = snapshot();
        HistoryEntry e1 = fill(area, sand);
        HistoryEntry e2 = fill(Box.of(new BlockPos(-6, 60, -6), new BlockPos(3, 66, 5)), dirt);
        HistoryEntry e3 = fill(Box.of(new BlockPos(0, 64, -2), new BlockPos(9, 69, 9)), air);
        NbtBytes foreignItems = new NbtBytes("minecraft:chest", new byte[] {10, 3, 3, 0});
        world.set(8, 61, 8, stone);                  // e1 only
        world.set(2, 65, 2, water);                  // e2 and e3
        world.set(-3, 66, 4, chest);                 // e1 and e2 (where the original chest was): a new chest
        world.setTile(-3, 66, 4, foreignItems);
        world.set(9, 69, 9, loggedStairs);           // e1 and e3 (the top corner)
        BlockBuffer changed = snapshot();

        long skipped = 0;
        for (HistoryEntry e : List.of(e3, e2, e1)) {
            skipped += FakeExecutor.run(HistoryPrograms.undo(e, ConflictPolicy.SKIP_CONFLICTS), world).conflicts();
        }
        assertTrue(skipped >= 4, "the undos kept the other player's cells: " + skipped);
        assertSame(foreignItems, world.tile(-3, 66, 4));

        FakeExecutor.Result anyway = FakeExecutor.run(HistoryPrograms.reapply(List.of(e3, e2, e1), false), world);
        assertEquals(0, anyway.conflicts());
        assertEquals(0, anyway.refused());
        BlockBuffer reapplied = snapshot();

        restore(changed);
        for (HistoryEntry e : List.of(e3, e2, e1)) FakeExecutor.run(HistoryPrograms.undo(e, ConflictPolicy.OVERWRITE), world);
        assertSameContent(snapshot(), reapplied);
        assertSameContent(original, reapplied);
        assertSame(chestItems, reapplied.tile(-3, 66, 4), "the recorded chest contents are back");
    }

    /** The same for a run of redos: after undoing two edits, cells changed before redoing them are overwritten too. */
    @Test
    void redoAnywayEqualsTheRunRedoneWithOverwrite() {
        HistoryEntry e1 = fill(area, sand);
        HistoryEntry e2 = fill(Box.of(new BlockPos(-4, 62, -4), new BlockPos(6, 67, 6)), stone);
        BlockBuffer edited = snapshot();
        FakeExecutor.run(HistoryPrograms.undo(e2, ConflictPolicy.SKIP_CONFLICTS), world);
        FakeExecutor.run(HistoryPrograms.undo(e1, ConflictPolicy.SKIP_CONFLICTS), world);
        world.set(0, 63, 0, chest);                  // e1 and e2
        world.setTile(0, 63, 0, chestItems);
        world.set(-9, 60, -9, water);                // e1 only
        BlockBuffer changed = snapshot();

        long skipped = 0;
        for (HistoryEntry e : List.of(e1, e2)) {
            skipped += FakeExecutor.run(HistoryPrograms.redo(e, ConflictPolicy.SKIP_CONFLICTS), world).conflicts();
        }
        assertTrue(skipped >= 2, "skipped " + skipped);
        FakeExecutor.run(HistoryPrograms.reapply(List.of(e1, e2), true), world);
        BlockBuffer reapplied = snapshot();

        restore(changed);
        for (HistoryEntry e : List.of(e1, e2)) FakeExecutor.run(HistoryPrograms.redo(e, ConflictPolicy.OVERWRITE), world);
        assertSameContent(snapshot(), reapplied);
        assertSameContent(edited, reapplied);
        assertNull(reapplied.tile(0, 63, 0), "the redo's stone replaced the new chest");
    }

    /** Where entries of the run share a cell, the one applied last decides it: the order of the run matters. */
    @Test
    void theLastEntryOfTheRunDecidesASharedCell() {
        RecordBuilder older = new RecordBuilder();
        older.record(1, 70, 1, air, null, sand, null);
        older.record(2, 70, 2, air, null, sand, null);
        RecordBuilder newer = new RecordBuilder();
        newer.record(1, 70, 1, sand, null, dirt, null);
        HistoryEntry first = entry("Place sand", older.build());
        HistoryEntry second = entry("Place dirt", newer.build());
        world.set(1, 70, 1, stone);                  // someone else's block over both
        world.set(2, 70, 2, water);

        FakeExecutor.run(HistoryPrograms.reapply(List.of(second, first), false), world);
        assertEquals(air, world.get(1, 70, 1), "undoing newest first ends with the older entry's before");
        assertEquals(air, world.get(2, 70, 2));
        world.set(1, 70, 1, stone);
        FakeExecutor.run(HistoryPrograms.reapply(List.of(first, second), false), world);
        assertEquals(sand, world.get(1, 70, 1), "the other order ends with the newer entry's before");

        FakeExecutor.run(HistoryPrograms.reapply(List.of(first, second), true), world);
        assertEquals(dirt, world.get(1, 70, 1), "redoing oldest first ends with the newer entry's after");
        assertEquals(sand, world.get(2, 70, 2));
    }

    /** A cancelled Undo anyway leaves part of it written; running it again finishes it exactly (it is idempotent). */
    @Test
    void aPartialUndoAnywayFinishesExactlyWhenRunAgain() {
        BlockBuffer original = snapshot();
        HistoryEntry e1 = fill(area, sand);
        HistoryEntry e2 = fill(Box.of(new BlockPos(-8, 61, -8), new BlockPos(8, 68, 8)), dirt);
        for (int i = -9; i <= 9; i += 3) world.set(i, 64, i, chest);
        FakeExecutor.run(HistoryPrograms.undo(e2, ConflictPolicy.SKIP_CONFLICTS), world);
        FakeExecutor.run(HistoryPrograms.undo(e1, ConflictPolicy.SKIP_CONFLICTS), world);
        EditProgram anyway = HistoryPrograms.reapply(List.of(e2, e1), false);
        FakeExecutor.Result partial = FakeExecutor.run(anyway, world, 3);
        assertEquals(3, partial.sections());
        FakeExecutor.run(HistoryPrograms.reapply(List.of(e2, e1), false), world);
        assertSameContent(original, snapshot());
        assertEquals(0, FakeExecutor.run(HistoryPrograms.reapply(List.of(e2, e1), false), world).written(),
                "once restored, nothing is left to write");
    }

    @Test
    void reapplyMetadata() {
        HistoryEntry e1 = fill(Box.of(new BlockPos(-10, 60, -10), new BlockPos(-1, 64, -1)), sand);
        HistoryEntry e2 = fill(Box.of(new BlockPos(-5, 62, -5), new BlockPos(9, 69, 9)), dirt);
        EditProgram undo = HistoryPrograms.reapply(List.of(e2, e1), false);
        EditProgram redo = HistoryPrograms.reapply(List.of(e1), true);
        assertEquals("Undo anyway (2 steps)", undo.label());
        assertEquals("Redo anyway (1 step)", redo.label());
        assertEquals(Box.of(new BlockPos(-10, 60, -10), new BlockPos(9, 69, 9)), undo.bounds());
        assertEquals(e1.record().before().bounds(), redo.bounds());
        long overlap = 5L * 3 * 5;                   // x and z -5..-1, y 62..64
        assertEquals(e1.record().before().cellCount() + e2.record().before().cellCount() - overlap, undo.estimatedCells(),
                "cells recorded by both entries count once");
        assertEquals(0, undo.sourceSections().length);
        java.util.TreeSet<Long> keys = new java.util.TreeSet<>();
        for (long key : e1.record().before().sortedKeys()) keys.add(key);
        for (long key : e2.record().before().sortedKeys()) keys.add(key);
        long[] expected = keys.stream().mapToLong(Long::longValue).toArray();
        SectionOrder.sort(expected);
        assertArrayEquals(expected, undo.sectionOrder());
        assertTrue(undo.mayReplace(expected[0], 0, stone, null), "overwrites whatever a cell holds when written");

        HistoryEntry empty = entry("Nothing", new EditRecord(new BlockBuffer(), new BlockBuffer()));
        assertThrows(IllegalArgumentException.class, () -> HistoryPrograms.reapply(List.of(), false));
        assertThrows(IllegalArgumentException.class, () -> HistoryPrograms.reapply(List.of(e1, empty), true));
    }

    /** Every cell of the test area (plus a margin), with tiles. */
    private BlockBuffer snapshot() {
        BlockBuffer copy = new BlockBuffer();
        for (int x = area.min().x() - 1; x <= area.max().x() + 1; x++) {
            for (int y = area.min().y() - 1; y <= area.max().y() + 1; y++) {
                for (int z = area.min().z() - 1; z <= area.max().z() + 1; z++) {
                    copy.set(x, y, z, world.get(x, y, z));
                    BlockEntityData tile = world.tile(x, y, z);
                    if (tile != null) copy.setTile(x, y, z, tile);
                }
            }
        }
        return copy;
    }

    private void assertSameContent(BlockBuffer expected, BlockBuffer actual) {
        for (int x = area.min().x() - 1; x <= area.max().x() + 1; x++) {
            for (int y = area.min().y() - 1; y <= area.max().y() + 1; y++) {
                for (int z = area.min().z() - 1; z <= area.max().z() + 1; z++) {
                    String at = x + "," + y + "," + z;
                    assertEquals(expected.get(x, y, z), actual.get(x, y, z), at);
                    BlockEntityData want = expected.tile(x, y, z), got = actual.tile(x, y, z);
                    assertTrue(RecordBuilder.sameTile(want, got), "tile at " + at);
                }
            }
        }
    }
}
