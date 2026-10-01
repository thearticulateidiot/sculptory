package dev.sculptory.core.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Microbenchmark: compiling and computing a 1,000,000-cell fill must stay well under 2 s. */
class FillBenchmarkTest {
    private static final long BUDGET_NANOS = 2_000_000_000L;

    @Test
    void millionCellFill() {
        FakeStateSpace states = new FakeStateSpace();
        int stone = states.state("minecraft:stone");
        int dirt = states.state("minecraft:dirt");
        FakeWorld world = new FakeWorld(states);
        world.fill(Box.of(new BlockPos(0, 0, 0), new BlockPos(99, 49, 99)), stone);
        Box box = Box.of(new BlockPos(0, 0, 0), new BlockPos(99, 99, 99));
        CompileContext context = new CompileContext() {
            @Override
            public StateSpace states() {
                return states;
            }

            @Override
            public Optional<SourceBlocks> source(SourceRef ref) {
                return Optional.empty();
            }
        };

        long cold = timeFill(box, dirt, context, world, null);
        long[] computeOnly = new long[1];
        long warm = timeFill(box, dirt, context, world, computeOnly);
        System.out.printf("1M-cell fill: cold %.1f ms, warm %.1f ms (compute only %.1f ms)%n",
                cold / 1e6, warm / 1e6, computeOnly[0] / 1e6);
        assertTrue(cold < BUDGET_NANOS, "cold 1M fill took " + cold / 1_000_000 + " ms");
        assertTrue(warm < BUDGET_NANOS, "warm 1M fill took " + warm / 1_000_000 + " ms");
    }

    private static long timeFill(Box box, int state, CompileContext context, FakeWorld world, long[] computeNanos) {
        long start = System.nanoTime();
        EditProgram program = OpCompiler.compile(new OpSpec.Fill(box, new Pattern.Single(state), CellMask.ANY), context);
        assertEquals(1_000_000L, program.estimatedCells());
        long written = 0, compute = 0;
        SectionBuffer before = new SectionBuffer();
        for (long key : program.sectionOrder()) {
            world.copySection(BlockBuffer.keyX(key), BlockBuffer.keyY(key), BlockBuffer.keyZ(key), before);
            SectionBuffer out = new SectionBuffer();
            long computeStart = System.nanoTime();
            program.compute(key, before, out, null);
            compute += System.nanoTime() - computeStart;
            written += out.presentCount();
        }
        long elapsed = System.nanoTime() - start;
        assertEquals(1_000_000L, written);
        if (computeNanos != null) computeNanos[0] = compute;
        return elapsed;
    }
}
