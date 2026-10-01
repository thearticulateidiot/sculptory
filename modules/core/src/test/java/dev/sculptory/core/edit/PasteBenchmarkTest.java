package dev.sculptory.core.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.core.transform.Mirror;
import dev.sculptory.core.transform.Transform;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Microbenchmark: compiling and computing a 1,000,000-cell paste with a turn and a mirror must stay well under
 * 2 s (the same budget as the fill benchmark). Also times building the clipboard and hashing it.
 */
class PasteBenchmarkTest {
    private static final long BUDGET_NANOS = 2_000_000_000L;

    @Test
    void millionCellPaste() {
        FakeStateSpace states = new FakeStateSpace();
        int[] palette = {
                states.state("minecraft:stone"), states.state("minecraft:dirt"),
                states.state("minecraft:oak_stairs[facing=north]"), states.state("minecraft:oak_stairs[facing=east,half=top]"),
                states.state("minecraft:oak_log[axis=x]"), states.state("minecraft:oak_log[axis=z]"),
                states.state("testmod:widget[facing=west]"), states.state("minecraft:grass_block[snowy=true]")};
        long buildStart = System.nanoTime();
        Clipboard.Builder builder = Clipboard.builder(states, new BlockPos(100, 100, 100));
        for (int y = 0; y < 100; y++) {
            for (int z = 0; z < 100; z++) {
                for (int x = 0; x < 100; x++) builder.set(x, y, z, palette[(x * 7 + y * 3 + z) % palette.length]);
            }
        }
        Clipboard clipboard = builder.anchor(new BlockPos(50, 0, 50)).build();
        long build = System.nanoTime() - buildStart;

        SourceRef ref = new SourceRef.Clipboard(UUID.randomUUID());
        CompileContext context = CopyTestSupport.context(states, Map.of(ref, clipboard.toSource()));
        OpSpec.Paste op = new OpSpec.Paste(ref, new BlockPos(1000, 0, -1000), new Transform(1, Mirror.X), PasteOptions.DEFAULT);
        FakeWorld world = new FakeWorld(states);

        long[] computeOnly = new long[1];
        long cold = time(op, context, world, null);
        long warm = time(op, context, world, computeOnly);
        System.out.printf("1M-cell paste (turn + mirror): build %.1f ms incl. hash, cold %.1f ms, warm %.1f ms "
                + "(compute only %.1f ms)%n", build / 1e6, cold / 1e6, warm / 1e6, computeOnly[0] / 1e6);
        assertTrue(cold < BUDGET_NANOS, "cold 1M paste took " + cold / 1_000_000 + " ms");
        assertTrue(warm < BUDGET_NANOS, "warm 1M paste took " + warm / 1_000_000 + " ms");
    }

    private static long time(OpSpec.Paste op, CompileContext context, FakeWorld world, long[] computeNanos) {
        long start = System.nanoTime();
        EditProgram program = OpCompiler.compile(op, context);
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
