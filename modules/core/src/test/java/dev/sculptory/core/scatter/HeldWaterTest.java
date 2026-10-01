package dev.sculptory.core.scatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.edit.EditProgram;
import dev.sculptory.core.edit.OpCompiler;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.scatter.ScatterFixture.Spec;
import dev.sculptory.core.scatter.ScatterSettings.Fit;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.testing.FakeExecutor;
import dev.sculptory.core.testing.FakeWorld;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Cells that hold water without a fluid flag (seagrass, kelp, bubble columns: {@code StateSpace.fluidSource}) are fluid
 * cells to scatter: unless fluids are allowed, neither the planner nor the commit replaces them, so no dry cell is left
 * under water.
 */
class HeldWaterTest {
    private final ScatterFixture f = new ScatterFixture();
    private final int seagrass = f.states.state("minecraft:seagrass");
    private final Clipboard poppy = BlockVariants.clipboard(f.states, f.states.state("minecraft:poppy"));
    private final UUID id = UUID.randomUUID();

    /** The columns x 4..8 at z 4, their surface searched between y 60 and 65. */
    private static Spec row() {
        return new Spec(new ScatterArea.Region(Box.of(new BlockPos(4, 60, 4), new BlockPos(8, 65, 4))));
    }

    private EditProgram compile(ScatterPlan plan) {
        return OpCompiler.compile(new OpSpec.ScatterCommit(id),
                ScatterFixture.context(f.states, id, plan.toMultiPaste(), Long.MAX_VALUE));
    }

    @Test
    void thePlannerLeavesSeagrassUnlessFluidsAreAllowed() {
        int fluidFlags = StateFlags.FLUID_BLOCK | StateFlags.WATERLOGGED;
        assertTrue(f.states.fluidSource(seagrass) >= 0 && (f.states.flags(seagrass) & fluidFlags) == 0,
                "the fixture's seagrass holds water without a fluid flag");
        FakeWorld world = f.flat(0, 0, 15, 15, 64);
        world.set(4, 65, 4, seagrass);
        ScatterPlan dry = ScatterFixture.plan(row(), List.of(poppy), world);
        assertEquals(4, dry.placements().size(), dry.toString());
        for (ScatterPlan.Placement p : dry.placements()) assertTrue(p.anchor().x() != 4, "on the seagrass: " + p);
        assertEquals(1, dry.count(Outcome.COLLISION));

        ScatterPlan wet = ScatterFixture.plan(row().fit(Fit.DEFAULT.withAllowInFluid(true)), List.of(poppy), world);
        assertEquals(5, wet.placements().size(), "allowed in fluids: " + wet);
    }

    /** Seagrass grown where a poppy was planned, before the commit or while its section is written, stays. */
    @Test
    void theCommitLeavesSeagrassGrownSinceThePreview() {
        FakeWorld world = f.flat(0, 0, 15, 15, 64);
        ScatterPlan plan = ScatterFixture.plan(row(), List.of(poppy), world);
        assertEquals(5, plan.placements().size());
        world.set(5, 65, 4, seagrass);
        FakeExecutor.Result before = FakeExecutor.run(compile(plan), world);
        assertEquals(seagrass, world.get(5, 65, 4));
        assertEquals(4, before.written());
        assertEquals(1, before.conflicts());

        FakeWorld later = f.flat(0, 0, 15, 15, 64);
        long section = BlockBuffer.keyOfBlock(6, 65, 4);
        FakeExecutor.Result during = FakeExecutor.run(compile(plan), later, new FakeExecutor.Hooks() {
            @Override
            public void afterCompute(long key, FakeWorld w) {
                if (key == section) w.set(6, 65, 4, seagrass);
            }
        });
        assertEquals(seagrass, later.get(6, 65, 4), "the write-time guard let the poppy take the seagrass");
        assertEquals(4, during.written());
        assertEquals(1, during.conflicts());
    }
}
