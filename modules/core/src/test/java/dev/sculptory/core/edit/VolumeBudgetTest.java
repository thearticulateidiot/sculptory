package dev.sculptory.core.edit;

import static dev.sculptory.core.edit.CopyTestSupport.box;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.transform.Transform;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@link OpCompiler#targetVolume}, {@link OpCompiler#sourceVolume} and the compile-time cell budget. */
class VolumeBudgetTest {
    private final FakeStateSpace states = new FakeStateSpace();
    private final Pattern stone = new Pattern.Single(states.state("minecraft:stone"));
    private final Pattern air = new Pattern.Single(states.air());

    private CompileContext budget(long maxCells, Map<SourceRef, SourceBlocks> sources) {
        CompileContext base = CopyTestSupport.context(states, sources);
        return new CompileContext() {
            @Override
            public StateSpace states() {
                return states;
            }

            @Override
            public Optional<SourceBlocks> source(SourceRef ref) {
                return base.source(ref);
            }

            @Override
            public int bottomY() {
                return base.bottomY();
            }

            @Override
            public int topYExclusive() {
                return base.topYExclusive();
            }

            @Override
            public long maxCells() {
                return maxCells;
            }
        };
    }

    @Test
    void volumesOfEveryOp() {
        Box cube = box(0, 0, 0, 9, 9, 9);
        assertEquals(1000, OpCompiler.targetVolume(new OpSpec.Fill(cube, stone, CellMask.ANY), null));
        assertEquals(1000, OpCompiler.targetVolume(new OpSpec.Replace(cube, CellMask.ANY, stone), null));
        assertEquals(1000, OpCompiler.targetVolume(new OpSpec.Erase(cube, CellMask.ANY), null));
        assertEquals(1000, OpCompiler.targetVolume(new OpSpec.Hollow(cube, 1, air), null));
        assertEquals(1000, OpCompiler.targetVolume(new OpSpec.Walls(cube, 1, stone), null));
        OpSpec.Paste paste = new OpSpec.Paste(new SourceRef.Clipboard(UUID.randomUUID()), BlockPos.ORIGIN,
                Transform.rotation(1), PasteOptions.DEFAULT);
        assertEquals(60, OpCompiler.targetVolume(paste, new BlockPos(3, 4, 5)));
        assertThrows(NullPointerException.class, () -> OpCompiler.targetVolume(paste, null));
        assertEquals(0, OpCompiler.sourceVolume(paste));

        // A 10-long row moved by 3 overlaps itself on 7 cells: 10 written + 3 vacated.
        OpSpec.Move shift = new OpSpec.Move(box(0, 0, 0, 9, 0, 0), new BlockPos(3, 0, 0), Transform.IDENTITY, air);
        assertEquals(13, OpCompiler.targetVolume(shift, null));
        assertEquals(10, OpCompiler.sourceVolume(shift));
        // A 4 x 1 x 2 box turned in place becomes 2 x 1 x 4 at the same corner: 2 x 2 cells overlap.
        OpSpec.Move turn = new OpSpec.Move(box(0, 0, 0, 3, 0, 1), BlockPos.ORIGIN, Transform.rotation(1), air);
        assertEquals(12, OpCompiler.targetVolume(turn, null));
        OpSpec.Move apart = new OpSpec.Move(cube, new BlockPos(100, 0, 0), Transform.IDENTITY, air);
        assertEquals(2000, OpCompiler.targetVolume(apart, null));

        OpSpec.Stack stack = new OpSpec.Stack(cube, 10, 0, 0, 7);
        assertEquals(7000, OpCompiler.targetVolume(stack, null));
        assertEquals(1000, OpCompiler.sourceVolume(stack));
        OpSpec.Stack huge = new OpSpec.Stack(box(-1_000_000_000, -1_000_000_000, 0, 1_000_000_000, 1_000_000_000, 1_000_000_000),
                1, 0, 0, Integer.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, OpCompiler.targetVolume(huge, null), "saturates");
        assertEquals(0, OpCompiler.targetVolume(new OpSpec.ScatterCommit(UUID.randomUUID()), null));
        assertEquals(0, OpCompiler.sourceVolume(new OpSpec.Fill(cube, stone, CellMask.ANY)));
    }

    /** The review case: a 6400 x 384 x 3200 move used to take about a second and 250 MB to compile. */
    @Test
    void compileRefusesOverTheBudgetBeforeAnyWork() {
        CompileContext context = budget(2_000_000, Map.of());
        OpSpec.Move giant = new OpSpec.Move(box(0, -64, 0, 6399, 319, 3199), new BlockPos(10, 0, 0), Transform.IDENTITY, air);
        long start = System.nanoTime();
        EditTooLargeException refused = assertThrows(EditTooLargeException.class, () -> OpCompiler.compile(giant, context));
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(millis < 200, "refusal took " + millis + " ms");
        assertEquals(2_000_000, refused.budget());
        assertEquals(OpCompiler.targetVolume(giant, null), refused.cells());
        assertInstanceOf(IllegalArgumentException.class, refused, "existing IllegalArgumentException handlers still apply");

        assertThrows(EditTooLargeException.class,
                () -> OpCompiler.compile(new OpSpec.Stack(box(0, 0, 0, 99, 9, 99), 100, 0, 0, 21), context));
        assertEquals("Stack", OpCompiler.compile(new OpSpec.Stack(box(0, 0, 0, 99, 9, 99), 100, 0, 0, 20), context).label());
        assertThrows(EditTooLargeException.class,
                () -> OpCompiler.compile(new OpSpec.Fill(box(0, 0, 0, 200, 99, 99), stone, CellMask.ANY), context));
        assertEquals("Fill", OpCompiler.compile(new OpSpec.Fill(box(0, 0, 0, 199, 99, 99), stone, CellMask.ANY), context).label());
    }

    @Test
    void pastesAreCheckedAgainstTheirPresentCellsNotTheirBox() {
        SourceRef ref = new SourceRef.Clipboard(UUID.randomUUID());
        // 245 full sections of a 200^3 box: 1,003,520 cells, refused on that count, before the source is scanned.
        BlockBuffer dense = new BlockBuffer();
        for (int i = 0; i < 245; i++) {
            dense.putSection(BlockBuffer.key(i % 12, (i / 12) % 12, i / 144), SectionBuffer.uniform(states.state("minecraft:stone")));
        }
        SourceBlocks big = new SourceBlocks(dense, new BlockPos(200, 200, 200), BlockPos.ORIGIN);
        OpSpec.Paste paste = new OpSpec.Paste(ref, BlockPos.ORIGIN, Transform.IDENTITY, PasteOptions.DEFAULT);
        EditTooLargeException refused = assertThrows(EditTooLargeException.class,
                () -> OpCompiler.compile(paste, budget(1_000_000, Map.of(ref, big))));
        assertEquals(245 * 4096, refused.cells());
        assertEquals("Paste", OpCompiler.compile(paste, budget(245 * 4096, Map.of(ref, big))).label());
        assertEquals("Paste", OpCompiler.compile(paste, CopyTestSupport.context(states, Map.of(ref, big))).label(),
                "the default budget is unbounded");
        // A sparse source in the same box counts its cells: one block in 200^3 passes a budget its box would not.
        BlockBuffer sparse = new BlockBuffer();
        sparse.set(199, 199, 199, states.state("minecraft:stone"));
        SourceBlocks thin = new SourceBlocks(sparse, new BlockPos(200, 200, 200), BlockPos.ORIGIN);
        assertEquals("Paste", OpCompiler.compile(paste, budget(1, Map.of(ref, thin))).label());
        assertThrows(EditTooLargeException.class, () -> OpCompiler.compile(paste, budget(0, Map.of(ref, thin))));
        assertEquals(8_000_000, OpCompiler.targetVolume(paste, new BlockPos(200, 200, 200)), "the box stays the bound");
    }
}
