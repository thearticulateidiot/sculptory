package dev.sculptory.core.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Opt-in timing of {@link RecordBuilder#build()} for a 1M-cell job record, which runs on the server thread in the
 * tick a job ends (set {@code SCULPTORY_BENCH=1}). Prints the median of several runs.
 */
@EnabledIfEnvironmentVariable(named = "SCULPTORY_BENCH", matches = "1")
class RecordBuilderBenchTest {
    private static final int SIDE = 100; // 100³ = 1,000,000 cells

    private enum Content { UNIFORM, MIXED, OVERLAPPING }

    /**
     * Records a 100³ job the way BulkJob does: section by section, cells in ascending index order, optionally
     * preparing each section as it is finished (as BulkJob does).
     *
     * @param prepareNanos receives the time spent preparing, if preparing
     */
    private static RecordBuilder record(Content content, long[] prepareNanos) {
        RecordBuilder builder = new RecordBuilder();
        for (int sx = 0; sx < 7; sx++) {
            for (int sz = 0; sz < 7; sz++) {
                for (int sy = 0; sy < 7; sy++) {
                    for (int y = sy * 16; y < Math.min(SIDE, sy * 16 + 16); y++) {
                        for (int z = sz * 16; z < Math.min(SIDE, sz * 16 + 16); z++) {
                            for (int x = sx * 16; x < Math.min(SIDE, sx * 16 + 16); x++) {
                                int mix = (x + y * 5 + z * 3) % 6;
                                int before = switch (content) {
                                    case UNIFORM -> 0;
                                    case OVERLAPPING -> (x * 7 + y * 3 + z) % 3; // terrain: air, stone, dirt
                                    case MIXED -> 10 + ((x * 7 + y * 3 + z) % 6);
                                };
                                int after = switch (content) {
                                    case UNIFORM -> 1;
                                    case MIXED -> 100 + mix;
                                    case OVERLAPPING -> mix % 4; // the paste: air, stone, dirt, planks
                                };
                                if (before != after || content != Content.OVERLAPPING) {
                                    builder.record(x, y, z, before, null, after, null);
                                }
                            }
                        }
                    }
                    if (prepareNanos != null) {
                        long start = System.nanoTime();
                        builder.prepare(dev.sculptory.core.buffer.BlockBuffer.key(sx, sy, sz));
                        prepareNanos[0] += System.nanoTime() - start;
                    }
                }
            }
        }
        return builder;
    }

    private static void time(String what, Content content) {
        long[] runs = new long[7];
        long[] prepareRuns = new long[7];
        long[] preparedBuildRuns = new long[7];
        EditRecord last = null;
        for (int i = 0; i < runs.length; i++) {
            RecordBuilder builder = record(content, null);
            long start = System.nanoTime();
            last = builder.build();
            runs[i] = System.nanoTime() - start;
            long[] prepare = new long[1];
            RecordBuilder prepared = record(content, prepare);
            prepareRuns[i] = prepare[0];
            start = System.nanoTime();
            EditRecord built = prepared.build();
            preparedBuildRuns[i] = System.nanoTime() - start;
            assertEquals(last.before().cellCount(), built.before().cellCount());
        }
        java.util.Arrays.sort(runs);
        java.util.Arrays.sort(prepareRuns);
        java.util.Arrays.sort(preparedBuildRuns);
        assertTrue(last.before().cellCount() > 500_000);
        System.out.printf(Locale.ROOT, "RecordBuilder.build %s: median %.1f ms (min %.1f, max %.1f); prepared per "
                        + "section: %.1f ms spread over the job, then build %.1f ms%n", what,
                runs[runs.length / 2] / 1e6, runs[0] / 1e6, runs[runs.length - 1] / 1e6,
                prepareRuns[prepareRuns.length / 2] / 1e6, preparedBuildRuns[preparedBuildRuns.length / 2] / 1e6);
    }

    @Test
    void build1MUniform() {
        time("1M cells, air -> stone", Content.UNIFORM);
    }

    @Test
    void build1MMixed() {
        time("1M cells, 6 states -> 6 other states", Content.MIXED);
    }

    @Test
    void build1MOverlapping() {
        time("1M cells, a 4-state paste over 3-state terrain (overlapping states)", Content.OVERLAPPING);
    }
}
