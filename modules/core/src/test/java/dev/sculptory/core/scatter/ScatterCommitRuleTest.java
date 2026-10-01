package dev.sculptory.core.scatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.edit.ComputeContext;
import dev.sculptory.core.edit.EditProgram;
import dev.sculptory.core.edit.MultiPaste;
import dev.sculptory.core.edit.OpCompiler;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.history.HistoryEntry;
import dev.sculptory.core.history.HistoryPrograms;
import dev.sculptory.core.scatter.ScatterArea.Stamp;
import dev.sculptory.core.scatter.ScatterFixture.Spec;
import dev.sculptory.core.scatter.ScatterSettings.Fit;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeExecutor;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.core.world.WorldReader;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A scatter commit writes only into cells that are still open ({@code MultiPaste.Replace}): what was built since the
 * preview survives, and a placement is skipped whole rather than half built.
 */
class ScatterCommitRuleTest {
    private final ScatterFixture f = new ScatterFixture();
    private final UUID id = UUID.randomUUID();

    /** Three 3 × 3 stone slabs, well apart, on flat grass at y = 64 (anchors at y = 65). */
    private ScatterPlan slabs(FakeWorld world, Fit fit) {
        ScatterPlan plan = ScatterFixture.plan(Spec.stamps(Stamp.paint(4, 4, 0), Stamp.paint(20, 4, 0), Stamp.paint(40, 8, 0))
                .fit(fit), List.of(f.slab(3, 3, f.stone)), world);
        assertEquals(3, plan.placements().size(), plan.toString());
        return plan;
    }

    private EditProgram compile(MultiPaste paste) {
        return OpCompiler.compile(new OpSpec.ScatterCommit(id), ScatterFixture.context(f.states, id, paste, Long.MAX_VALUE));
    }

    private int slabCells(FakeWorld world, int cx, int cz) {
        int n = 0;
        for (int x = cx - 1; x <= cx + 1; x++) {
            for (int z = cz - 1; z <= cz + 1; z++) {
                if (world.get(x, 65, z) == f.stone) n++;
            }
        }
        return n;
    }

    @Test
    void anUnchangedWorldGetsEveryPlacement() {
        FakeWorld world = f.flat(0, 0, 47, 15, 64);
        ScatterPlan plan = slabs(world, Fit.DEFAULT);
        assertEquals(MultiPaste.Replace.OPEN, plan.toMultiPaste().replace());
        FakeExecutor.Result result = FakeExecutor.run(compile(plan.toMultiPaste()), world);
        assertEquals(27, result.written());
        assertEquals(0, result.conflicts());
    }

    @Test
    void aChestBuiltSinceThePreviewSurvivesAndItsPlacementIsSkippedWhole() {
        FakeWorld world = f.flat(0, 0, 47, 15, 64);
        ScatterPlan plan = slabs(world, Fit.DEFAULT);
        world.set(21, 65, 5, f.chest);
        FakeExecutor.Result result = FakeExecutor.run(compile(plan.toMultiPaste()), world);
        assertEquals(f.chest, world.get(21, 65, 5), "the chest was overwritten");
        assertEquals(0, slabCells(world, 20, 4), "the blocked placement was half built");
        assertEquals(9, slabCells(world, 4, 4));
        assertEquals(9, slabCells(world, 40, 8));
        assertEquals(18, result.written());
        assertEquals(1, result.conflicts(), "one placement skipped");

        // The same program as a plain paste would have overwritten it.
        FakeWorld other = f.flat(0, 0, 47, 15, 64);
        other.set(21, 65, 5, f.chest);
        MultiPaste any = new MultiPaste(plan.toMultiPaste().sources(), plan.toMultiPaste().placements());
        FakeExecutor.run(compile(any), other);
        assertEquals(f.stone, other.get(21, 65, 5));
    }

    /**
     * Without a world to look at, the per-cell guard alone protects the cell; the placement is cut short, and what it
     * had put in the section is taken back.
     */
    @Test
    void withoutAWorldThePerCellGuardStillProtects() {
        FakeWorld world = f.flat(0, 0, 47, 15, 64);
        ScatterPlan plan = slabs(world, Fit.DEFAULT);
        world.set(21, 65, 5, f.chest);
        long[] result = run(compile(plan.toMultiPaste()), world, false);
        assertEquals(f.chest, world.get(21, 65, 5));
        assertEquals(0, slabCells(world, 20, 4), "the cut-short placement was half built");
        assertEquals(18, result[0]);
        assertEquals(1, result[1], "one placement cut short");
    }

    /** A placement reaching a chunk that is not loaded at commit is skipped whole; the rest are written. */
    @Test
    void aPlacementReachingAnUnloadedChunkIsSkipped() {
        FakeWorld world = f.flat(0, 0, 47, 15, 64);
        ScatterPlan plan = ScatterFixture.plan(Spec.stamps(Stamp.paint(4, 4, 0), Stamp.paint(15, 4, 0)),
                List.of(f.slab(3, 3, f.stone)), world);
        assertEquals(2, plan.placements().size());
        world.setLoaded(1, 0, false);
        long[] result = run(compile(plan.toMultiPaste()), world, true);
        for (int x = 14; x <= 15; x++) {
            for (int z = 3; z <= 5; z++) assertEquals(f.air, world.get(x, 65, z), "the straddling placement was half built");
        }
        assertEquals(9, slabCells(world, 4, 4));
        assertEquals(9, result[0]);
        assertEquals(1, result[1]);
    }

    /**
     * A1: a chest placed after the section was computed but before its cells are written (a server writes a section
     * over several ticks) survives: right before the placement's first write its cells are checked again, so the
     * placement writes nothing and is reported once, and undo restores exactly what the commit wrote.
     */
    @Test
    void aCellBuiltOnWhileTheSectionIsWrittenIsLeftAlone() {
        FakeWorld world = f.flat(0, 0, 47, 15, 64);
        ScatterPlan plan = slabs(world, Fit.DEFAULT);
        EditProgram program = compile(plan.toMultiPaste());
        long chestSection = BlockBuffer.keyOfBlock(21, 65, 5);
        int[] placedAfterCompute = {0};
        FakeExecutor.Result result = FakeExecutor.run(program, world, new FakeExecutor.Hooks() {
            @Override
            public void afterCompute(long key, FakeWorld w) {
                if (key == chestSection) {
                    w.set(21, 65, 5, f.chest);
                    placedAfterCompute[0]++;
                }
            }
        });
        assertEquals(1, placedAfterCompute[0]);
        assertEquals(f.chest, world.get(21, 65, 5), "the chest was overwritten");
        assertEquals(0, slabCells(world, 20, 4), "the placement was half built");
        assertEquals(9, slabCells(world, 4, 4));
        assertEquals(9, slabCells(world, 40, 8));
        assertEquals(18, result.written());
        assertEquals(9, result.refused(), "every cell of the placement");
        assertEquals(1, result.conflicts(), "one placement cut short, reported once");

        // Undo restores the written cells and leaves the chest alone.
        HistoryEntry entry = new HistoryEntry(UUID.randomUUID(),
                UUID.randomUUID(), "minecraft:overworld", program.label(), result.record(), 0L);
        FakeExecutor.Result undo = FakeExecutor.run(HistoryPrograms.undo(entry,
                ConflictPolicy.SKIP_CONFLICTS), world);
        assertEquals(0, undo.conflicts());
        assertEquals(18, undo.written());
        FakeWorld expected = f.flat(0, 0, 47, 15, 64);
        expected.set(21, 65, 5, f.chest);
        for (int x = 0; x <= 47; x++) {
            for (int z = 0; z <= 15; z++) assertEquals(expected.get(x, 65, z), world.get(x, 65, z), "after undo at " + x + "," + z);
        }
    }

    /** A2: a placement reaching a column the job may not write (protection, the border) is skipped whole. */
    @Test
    void aPlacementReachingAProtectedColumnIsSkippedWhole() {
        FakeWorld world = f.flat(0, 0, 47, 15, 64);
        ScatterPlan plan = slabs(world, Fit.DEFAULT);
        FakeExecutor.Result result = FakeExecutor.run(compile(plan.toMultiPaste()), world, new FakeExecutor.Hooks() {
            @Override
            public boolean mayWrite(int x, int z) {
                return x != 21;
            }
        });
        assertEquals(0, slabCells(world, 20, 4), "the placement straddling the protected column was half built");
        assertEquals(9, slabCells(world, 4, 4));
        assertEquals(9, slabCells(world, 40, 8));
        assertEquals(18, result.written());
        assertEquals(0, result.denied(), "no cell had to be denied one by one");
        assertEquals(1, result.conflicts());
    }

    /**
     * A2: the program names the other chunks of the placements a section would decide, so the executor loads them
     * before the decision reads them; once decided, a placement's chunks are not asked for again.
     */
    @Test
    void readColumnsNamesTheOtherChunksOfUndecidedPlacements() {
        FakeWorld world = f.flat(0, 0, 47, 15, 64);
        ScatterPlan plan = ScatterFixture.plan(Spec.stamps(Stamp.paint(4, 4, 0), Stamp.paint(15, 4, 0)),
                List.of(f.slab(3, 3, f.stone)), world);
        assertEquals(2, plan.placements().size());
        EditProgram program = compile(plan.toMultiPaste());
        long west = BlockBuffer.keyOfBlock(15, 65, 4), east = BlockBuffer.keyOfBlock(16, 65, 4);
        assertTrue(Arrays.equals(new long[] {EditProgram.column(1, 0)}, program.readColumns(west)),
                Arrays.toString(program.readColumns(west)));
        assertTrue(Arrays.equals(new long[] {EditProgram.column(0, 0)}, program.readColumns(east)));
        FakeExecutor.Result result = FakeExecutor.run(program, world);
        assertEquals(18, result.written());
        assertEquals(0, program.readColumns(east).length, "a decided placement is asked for again");
        assertEquals(0, result.conflicts());

        // Without an OPEN rule nothing is read beyond the section.
        MultiPaste any = new MultiPaste(plan.toMultiPaste().sources(), plan.toMultiPaste().placements());
        assertEquals(0, compile(any).readColumns(west).length);
    }

    @Test
    void fluidsAreWrittenOnlyWhenTheFitAllowsThem() {
        FakeWorld world = f.flat(0, 0, 47, 15, 64);
        ScatterPlan dry = slabs(world, Fit.DEFAULT);
        FakeWorld flooded = f.flat(0, 0, 47, 15, 64);
        flooded.set(4, 65, 4, f.water);
        FakeExecutor.Result result = FakeExecutor.run(compile(dry.toMultiPaste()), flooded);
        assertEquals(f.water, flooded.get(4, 65, 4));
        assertEquals(0, slabCells(flooded, 4, 4));
        assertEquals(1, result.conflicts());

        ScatterPlan wet = slabs(world, Fit.DEFAULT.withAllowInFluid(true));
        assertEquals(MultiPaste.Replace.OPEN_OR_FLUID, wet.toMultiPaste().replace());
        FakeWorld floodedAgain = f.flat(0, 0, 47, 15, 64);
        floodedAgain.set(4, 65, 4, f.water);
        result = FakeExecutor.run(compile(wet.toMultiPaste()), floodedAgain);
        assertEquals(9, slabCells(floodedAgain, 4, 4));
        assertEquals(0, result.conflicts());
    }

    /** Runs the sections of loaded chunks, offering the world to compute or not: {written, conflicts}. */
    private static long[] run(EditProgram program, FakeWorld world, boolean offerWorld) {
        long[] result = {0, 0};
        ComputeContext ctx = new ComputeContext() {
            @Override
            public StateSpace states() {
                return world.states();
            }

            @Override
            public long seed() {
                return 1;
            }

            @Override
            public SectionBuffer source(long key) {
                return null;
            }

            @Override
            public WorldReader world() {
                return offerWorld ? world : null;
            }

            @Override
            public void conflicts(int n) {
                result[1] += n;
            }
        };
        for (long key : program.sectionOrder()) {
            int sx = BlockBuffer.keyX(key), sy = BlockBuffer.keyY(key), sz = BlockBuffer.keyZ(key);
            if (!world.isLoaded(sx, sz)) continue;
            SectionBuffer before = new SectionBuffer();
            world.copySection(sx, sy, sz, before);
            SectionBuffer out = new SectionBuffer();
            program.compute(key, before, out, ctx);
            out.forEachPresent(i -> world.set((sx << 4) + SectionBuffer.localX(i), (sy << 4) + SectionBuffer.localY(i),
                    (sz << 4) + SectionBuffer.localZ(i), out.get(i)));
            result[0] += out.presentCount();
        }
        return result;
    }

    @Test
    void theCommitRuleNeedsNoPlanner() {
        // A hand-made OPEN paste over a chest: the whole single-cell placement is skipped.
        FakeWorld world = f.flat(0, 0, 15, 15, 64);
        world.set(3, 65, 3, f.chest);
        Clipboard dirt = f.single(f.dirt);
        MultiPaste paste = new MultiPaste(List.of(dirt.toSource()), List.of(
                new MultiPaste.Placement(new BlockPos(3, 65, 3), 0, dev.sculptory.core.transform.Transform.IDENTITY),
                new MultiPaste.Placement(new BlockPos(5, 65, 5), 0, dev.sculptory.core.transform.Transform.IDENTITY)),
                MultiPaste.Replace.OPEN);
        FakeExecutor.Result result = FakeExecutor.run(compile(paste), world);
        assertEquals(f.chest, world.get(3, 65, 3));
        assertEquals(f.dirt, world.get(5, 65, 5));
        assertTrue(result.conflicts() == 1 && result.written() == 1, result.toString());
    }

    /**
     * The check before a placement's first write lets a chunk unloaded since the decision pass (its read ticket may
     * have been let go): the slab straddling it is written whole once its own section is loaded again, not skipped.
     */
    @Test
    void theFirstWriteRecheckLetsAChunkUnloadedSinceTheDecisionPass() {
        FakeWorld world = f.flat(0, 0, 47, 15, 64);
        ScatterPlan plan = ScatterFixture.plan(Spec.stamps(Stamp.paint(15, 4, 0)), List.of(f.slab(3, 3, f.stone)), world);
        assertEquals(1, plan.placements().size());
        long west = BlockBuffer.keyOfBlock(15, 65, 4);
        FakeExecutor.Result result = FakeExecutor.run(compile(plan.toMultiPaste()), world, new FakeExecutor.Hooks() {
            @Override
            public void afterCompute(long key, FakeWorld w) {
                if (key == west) w.setLoaded(1, 0, false);
            }

            @Override
            public void beforeCompute(long key, FakeWorld w) {
                w.setLoaded(1, 0, true);
            }
        });
        assertEquals(9, slabCells(world, 15, 4), "the slab was skipped or cut");
        assertEquals(9, result.written());
        assertEquals(0, result.conflicts());
    }
}
