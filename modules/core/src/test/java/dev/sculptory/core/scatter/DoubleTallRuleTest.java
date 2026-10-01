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
import dev.sculptory.core.testing.FakeExecutor;
import dev.sculptory.core.testing.FakeWorld;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Two-block plants are never left half: a placement may replace half of one only together with its other half, and a
 * placement cut short writes nothing more, so a double-tall variant is written whole or not at all even when it
 * straddles two sections.
 */
class DoubleTallRuleTest {
    private final ScatterFixture f = new ScatterFixture();
    private final int poppy = f.states.state("minecraft:poppy");
    private final int tallLower = f.states.state("minecraft:tall_grass[half=lower]");
    private final int tallUpper = f.states.state("minecraft:tall_grass[half=upper]");
    private final UUID id = UUID.randomUUID();

    private void tallGrass(FakeWorld world, int x, int y, int z) {
        world.set(x, y, z, tallLower);
        world.set(x, y + 1, z, tallUpper);
    }

    /** The columns x0..x0 + 4 at z, their surface searched between y - 5 and y. */
    private static Spec row(int x0, int y, int z) {
        return new Spec(new ScatterArea.Region(Box.of(new BlockPos(x0, y - 5, z), new BlockPos(x0 + 4, y, z))));
    }

    private EditProgram compile(ScatterPlan plan) {
        return OpCompiler.compile(new OpSpec.ScatterCommit(id),
                ScatterFixture.context(f.states, id, plan.toMultiPaste(), Long.MAX_VALUE));
    }

    /** The planner: a one-cell poppy may not take the lower half of standing tall grass; tall grass over it may. */
    @Test
    void aOneCellVariantDoesNotTakeHalfOfATallPlant() {
        FakeWorld world = f.flat(0, 0, 15, 15, 64);
        tallGrass(world, 4, 65, 4);
        ScatterPlan poppies = ScatterFixture.plan(row(4, 65, 4), List.of(BlockVariants.clipboard(f.states, poppy)), world);
        assertEquals(4, poppies.placements().size(), poppies.toString());
        for (ScatterPlan.Placement p : poppies.placements()) assertTrue(p.anchor().x() != 4, "on the tall grass: " + p);
        assertEquals(1, poppies.count(Outcome.COLLISION));

        ScatterPlan tall = ScatterFixture.plan(row(4, 65, 4), List.of(BlockVariants.clipboard(f.states, tallLower)), world);
        assertEquals(5, tall.placements().size(), "tall grass may replace tall grass whole: " + tall);
    }

    /**
     * An asset reaching only the upper half of tall grass (a cell above the ground) may not take it either; one that
     * covers both halves may.
     */
    @Test
    void aFootprintTakingOnlyTheUpperHalfCollides() {
        FakeWorld world = f.flat(0, 0, 15, 15, 64);
        tallGrass(world, 5, 65, 4);
        // An L: a post at the anchor and an arm one up and one east.
        Clipboard arm = Clipboard.builder(f.states, new BlockPos(2, 2, 1)).set(0, 0, 0, f.log).set(0, 1, 0, f.log)
                .set(1, 1, 0, f.log).build();
        Spec atFour = new Spec(new ScatterArea.Region(Box.of(new BlockPos(4, 60, 4), new BlockPos(4, 65, 4))));
        ScatterPlan armOverUpper = ScatterFixture.plan(atFour, List.of(arm), world);
        assertEquals(0, armOverUpper.placements().size(), "the arm took the upper half alone: " + armOverUpper);
        assertEquals(1, armOverUpper.count(Outcome.COLLISION));
        Spec atFive = new Spec(new ScatterArea.Region(Box.of(new BlockPos(5, 60, 4), new BlockPos(5, 65, 4))));
        assertEquals(1, ScatterFixture.plan(atFive, List.of(arm), world).placements().size(),
                "the post takes both halves");
    }

    /**
     * The commit: tall grass planted where a poppy was planned, before the commit decides it or while its section is
     * being written, is left whole and the poppy is skipped.
     */
    @Test
    void aTallPlantGrownSinceThePreviewIsLeftWhole() {
        FakeWorld world = f.flat(0, 0, 15, 15, 64);
        ScatterPlan plan = ScatterFixture.plan(row(4, 65, 4), List.of(BlockVariants.clipboard(f.states, poppy)), world);
        assertEquals(5, plan.placements().size());
        tallGrass(world, 6, 65, 4);
        FakeExecutor.Result before = FakeExecutor.run(compile(plan), world);
        assertEquals(tallLower, world.get(6, 65, 4));
        assertEquals(tallUpper, world.get(6, 66, 4));
        assertEquals(4, before.written());
        assertEquals(1, before.conflicts());

        FakeWorld later = f.flat(0, 0, 15, 15, 64);
        long section = BlockBuffer.keyOfBlock(7, 65, 4);
        FakeExecutor.Result during = FakeExecutor.run(compile(plan), later, new FakeExecutor.Hooks() {
            @Override
            public void afterCompute(long key, FakeWorld w) {
                if (key == section) tallGrass(w, 7, 65, 4);
            }
        });
        assertEquals(tallLower, later.get(7, 65, 4), "the write-time guard let the poppy take the lower half");
        assertEquals(tallUpper, later.get(7, 66, 4));
        assertEquals(4, during.written());
        assertEquals(1, during.conflicts());
    }

    /** Tall grass planned with its lower half at y = 79 (the top of section 4) and its upper half at y = 80. */
    private ScatterPlan straddling(FakeWorld world) {
        ScatterPlan plan = ScatterFixture.plan(row(4, 79, 4), List.of(BlockVariants.clipboard(f.states, tallLower)), world);
        assertEquals(5, plan.placements().size(), plan.toString());
        for (ScatterPlan.Placement p : plan.placements()) assertEquals(79, p.anchor().y());
        return plan;
    }

    private void assertWhole(FakeWorld world, int x, int z) {
        assertEquals(tallLower, world.get(x, 79, z), "lower half at " + x);
        assertEquals(tallUpper, world.get(x, 80, z), "upper half at " + x);
    }

    /**
     * The upper cell is built on after the lower section was computed, before the lower half is written: the plant is
     * checked whole right before its first write, so neither half is written.
     */
    @Test
    void anUpperCellTakenBeforeTheFirstWriteSkipsThePlantWhole() {
        FakeWorld world = f.flat(0, 0, 15, 15, 78);
        EditProgram program = compile(straddling(world));
        long lowerSection = BlockBuffer.keyOfBlock(6, 79, 4);
        FakeExecutor.Result result = FakeExecutor.run(program, world, new FakeExecutor.Hooks() {
            @Override
            public void afterCompute(long key, FakeWorld w) {
                if (key == lowerSection) w.set(6, 80, 4, f.stone);
            }
        });
        assertEquals(f.air, world.get(6, 79, 4), "a lower half without its upper half");
        assertEquals(f.stone, world.get(6, 80, 4));
        for (int x : new int[] {4, 5, 7, 8}) assertWhole(world, x, 4);
        assertEquals(8, result.written());
        assertEquals(1, result.conflicts(), "reported once");
    }

    /**
     * The lower cell is built on after its section was computed: the plant is cut short, and its upper half (in the
     * next section, still open) is not written either.
     */
    @Test
    void aLowerCellTakenMidWriteLeavesNoUpperHalf() {
        FakeWorld world = f.flat(0, 0, 15, 15, 78);
        EditProgram program = compile(straddling(world));
        long lowerSection = BlockBuffer.keyOfBlock(6, 79, 4);
        FakeExecutor.Result result = FakeExecutor.run(program, world, new FakeExecutor.Hooks() {
            @Override
            public void afterCompute(long key, FakeWorld w) {
                if (key == lowerSection) w.set(6, 79, 4, f.stone);
            }
        });
        assertEquals(f.stone, world.get(6, 79, 4));
        assertEquals(f.air, world.get(6, 80, 4), "a floating upper half");
        for (int x : new int[] {4, 5, 7, 8}) assertWhole(world, x, 4);
        assertEquals(8, result.written());
        assertEquals(1, result.conflicts());
    }
}
