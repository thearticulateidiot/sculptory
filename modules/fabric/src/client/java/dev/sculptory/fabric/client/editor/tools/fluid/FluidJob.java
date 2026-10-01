package dev.sculptory.fabric.client.editor.tools.fluid;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * A flood or drain search in progress and the time it may take per frame: the Fluid tool runs it from {@code frame},
 * so a large search spreads over several frames instead of stalling one (magic select's budget and batches). Client
 * thread only.
 */
final class FluidJob {
    /** Time the search may take per frame. */
    static final long FRAME_BUDGET_NANOS = 2_000_000L;
    /** Cells examined between clock reads. */
    static final int BATCH = 1024;

    private final FluidSearch search;

    FluidJob(FluidSearch search) {
        this.search = Objects.requireNonNull(search);
    }

    FluidSearch search() {
        return search;
    }

    /**
     * Runs the search until it is done or {@code budgetNanos} have passed on {@code clock} (at least one batch runs);
     * returns whether it is done.
     */
    boolean run(long budgetNanos, LongSupplier clock) {
        long start = clock.getAsLong();
        do {
            if (search.step(BATCH)) return true;
        } while (clock.getAsLong() - start < budgetNanos);
        return false;
    }
}
