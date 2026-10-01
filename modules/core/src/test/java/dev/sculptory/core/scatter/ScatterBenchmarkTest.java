package dev.sculptory.core.scatter;

import static dev.sculptory.core.scatter.ScatterFixture.context;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.edit.EditProgram;
import dev.sculptory.core.edit.MultiPaste;
import dev.sculptory.core.edit.OpCompiler;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.scatter.ScatterSettings.Density;
import dev.sculptory.core.scatter.ScatterSettings.Filters;
import dev.sculptory.core.scatter.ScatterSettings.Transforms;
import dev.sculptory.core.scatter.ScatterSettings.Variant;
import dev.sculptory.core.testing.FakeWorld;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Microbenchmarks (printed; asserted only against generous budgets so a slow CI machine does not fail them):
 * planning over 512 × 512 = 262,144 columns, and committing 1,000 placements of 1,000 cells each (1M cells).
 */
class ScatterBenchmarkTest {
    private static final long PLAN_BUDGET_NANOS = 5_000_000_000L;
    private static final long COMMIT_BUDGET_NANOS = 2_000_000_000L;

    private final ScatterFixture f = new ScatterFixture();

    @Test
    void plan262kColumns() {
        FakeWorld world = f.terrain(-1, -1, 512, 512, ScatterFixture::hills, f.grass);
        ScatterSettings settings = ScatterFixture.Spec.box(0, 0, 511, 511)
                .spacing(4)
                .filters(Filters.NONE.withSlope(0, 2))
                .variants(new Variant(0, 5), new Variant(1, 3), new Variant(2, 2))
                .transforms(Transforms.ALL)
                .build();
        List<Clipboard> sources = List.of(f.tree(), f.slab(3, 1, f.stone), f.single(f.dirt));

        long cold = System.nanoTime();
        ScatterPlan first = ScatterPlanner.plan(settings, sources, world, Long.MAX_VALUE);
        cold = System.nanoTime() - cold;
        long warm = System.nanoTime();
        ScatterPlan second = ScatterPlanner.plan(settings, sources, world, Long.MAX_VALUE);
        warm = System.nanoTime() - warm;

        // The same plan surveyed chunk by chunk, as a server would across ticks.
        long survey = System.nanoTime();
        ScatterPlanner planner = new ScatterPlanner(settings, sources, world, Long.MAX_VALUE);
        for (int cx = 0; cx < 32; cx++) {
            for (int cz = 0; cz < 32; cz++) planner.survey(cx << 4, cz << 4, (cx << 4) + 15, (cz << 4) + 15);
        }
        survey = System.nanoTime() - survey;
        long finish = System.nanoTime();
        ScatterPlan third = planner.finish();
        finish = System.nanoTime() - finish;

        assertEquals(262_144, first.columns());
        assertEquals(first.hash(), second.hash());
        assertEquals(first.hash(), third.hash());
        ScatterFixture.assertBalanced(first);
        System.out.printf("Scatter plan, 262,144 columns: %d candidates, %d placements, %d cells; cold %.1f ms, "
                        + "warm %.1f ms; chunk by chunk: survey %.1f ms + sort/accept %.1f ms; %s%n",
                first.candidates(), first.placements().size(), first.totalCells(), cold / 1e6, warm / 1e6,
                survey / 1e6, finish / 1e6, first.rejectedCounts());
        assertTrue(first.placements().size() > 5_000, first.toString());
        assertTrue(warm < PLAN_BUDGET_NANOS, "warm plan took " + warm / 1_000_000 + " ms");
    }

    @Test
    void commitThousandPlacementsOfThousandCells() {
        FakeWorld world = f.flat(-8, -8, 520, 520, 64);
        int[] palette = {f.stone, f.dirt, f.log, f.states.state("minecraft:oak_log[axis=x]"),
                f.states.state("minecraft:oak_stairs[facing=east,half=top]"), f.states.state("testmod:widget[facing=north]")};
        List<Clipboard> sources = List.of(cube(palette, 0), cube(palette, 1), cube(palette, 2));
        ScatterSettings settings = ScatterFixture.Spec.box(0, 0, 511, 511)
                .density(new Density.Count(1000))
                .spacing(12)
                .variants(new Variant(0, 1), new Variant(1, 1), new Variant(2, 1))
                .transforms(Transforms.ALL)
                .build();
        long planStart = System.nanoTime();
        ScatterPlan plan = ScatterPlanner.plan(settings, sources, world, Long.MAX_VALUE);
        long planNanos = System.nanoTime() - planStart;
        assertEquals(1000, plan.placements().size());
        assertEquals(1_000_000, plan.totalCells());

        UUID id = UUID.randomUUID();
        MultiPaste paste = plan.toMultiPaste();
        long cold = commit(paste, id, world);
        long warm = commit(paste, id, world);
        System.out.printf("Scatter commit, 1,000 placements x 1,000 cells: plan %.1f ms, compile + compute cold %.1f ms, "
                + "warm %.1f ms%n", planNanos / 1e6, cold / 1e6, warm / 1e6);
        assertTrue(warm < COMMIT_BUDGET_NANOS, "warm commit took " + warm / 1_000_000 + " ms");
    }

    /** Compiles and computes every section (without writing the world, like the paste benchmark). */
    private long commit(MultiPaste paste, UUID id, FakeWorld world) {
        long start = System.nanoTime();
        EditProgram program = OpCompiler.compile(new OpSpec.ScatterCommit(id), context(f.states, id, paste, Long.MAX_VALUE));
        assertEquals(1_000_000, program.estimatedCells());
        long written = 0;
        SectionBuffer before = new SectionBuffer();
        // The live world is offered, so the commit's open-cell check of every placement is part of the timing.
        dev.sculptory.core.edit.ComputeContext ctx = new dev.sculptory.core.edit.ComputeContext() {
            @Override
            public dev.sculptory.core.state.StateSpace states() {
                return world.states();
            }

            @Override
            public long seed() {
                return 0;
            }

            @Override
            public SectionBuffer source(long key) {
                return null;
            }

            @Override
            public dev.sculptory.core.world.WorldReader world() {
                return world;
            }
        };
        for (long key : program.sectionOrder()) {
            world.copySection(BlockBuffer.keyX(key), BlockBuffer.keyY(key), BlockBuffer.keyZ(key), before);
            SectionBuffer out = new SectionBuffer();
            program.compute(key, before, out, ctx);
            written += out.presentCount();
        }
        long elapsed = System.nanoTime() - start;
        assertEquals(1_000_000, written);
        return elapsed;
    }

    /** A solid 10 × 10 × 10 block of mixed (rotatable) states, anchored at its bottom centre. */
    private Clipboard cube(int[] palette, int salt) {
        Clipboard.Builder b = Clipboard.builder(f.states, new BlockPos(10, 10, 10)).anchor(new BlockPos(5, 0, 5));
        for (int x = 0; x < 10; x++) {
            for (int y = 0; y < 10; y++) {
                for (int z = 0; z < 10; z++) b.set(x, y, z, palette[(x * 7 + y * 3 + z + salt) % palette.length]);
            }
        }
        return b.build();
    }
}
