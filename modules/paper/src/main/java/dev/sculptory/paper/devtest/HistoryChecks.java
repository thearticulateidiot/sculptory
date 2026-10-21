package dev.sculptory.paper.devtest;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

/** The crash-safe history hooks (HistorySaveHooks): which path each kind of world save takes. */
final class HistoryChecks {
    private HistoryChecks() {
    }

    /**
     * Paper's periodic save ({@code saveIncrementally}, level data only) journals what is open in one pass; a full save
     * ({@code ServerLevel.save}, as {@code save-all} runs it) journals every loaded column ahead of its chunk. Both are
     * run on the overworld here, as the server itself would run them.
     */
    static CompletableFuture<Void> saveHooks(SelfTestRun r) {
        long[] before = r.host.historySaveHookCounts();
        r.level.saveIncrementally(true);
        long[] periodic = r.host.historySaveHookCounts();
        r.level.save(null, false, false);
        long[] full = r.host.historySaveHookCounts();
        r.record("history saving: the periodic save takes the light path", periodic[2] == before[2] + 1
                && periodic[1] == before[1], "counts (unload, full, periodic) " + Arrays.toString(before) + " -> "
                + Arrays.toString(periodic));
        r.record("history saving: a full save journals every loaded column first", full[1] == periodic[1] + 1
                && full[2] == periodic[2], "counts " + Arrays.toString(periodic) + " -> " + Arrays.toString(full));
        return CompletableFuture.completedFuture(null);
    }
}
