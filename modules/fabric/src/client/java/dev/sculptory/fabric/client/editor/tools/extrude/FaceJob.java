package dev.sculptory.fabric.client.editor.tools.extrude;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * A face search in progress and the time it may take per frame. The Extrude tool runs it from {@code frame}, so a
 * large face spreads over several frames instead of stalling one (as the Select tool's {@code MagicJob} does).
 * Client thread only.
 */
final class FaceJob {
    /** Time the search may take per frame. */
    static final long FRAME_BUDGET_NANOS = 2_000_000L;
    /** Cells examined between clock reads. */
    static final int BATCH = 1024;

    private final FaceSelect select;

    FaceJob(FaceSelect select) {
        this.select = Objects.requireNonNull(select);
    }

    FaceSelect select() {
        return select;
    }

    /**
     * Runs the search until it is done or {@code budgetNanos} have passed on {@code clock} (at least one batch runs);
     * returns whether it is done.
     */
    boolean run(long budgetNanos, LongSupplier clock) {
        long start = clock.getAsLong();
        do {
            if (select.step(BATCH)) return true;
        } while (clock.getAsLong() - start < budgetNanos);
        return false;
    }
}
