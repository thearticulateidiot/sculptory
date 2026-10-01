package dev.sculptory.fabric.client.editor.tools.select;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * A magic select in progress: the flood fill, how its result combines with the selection, and the time it may take
 * per frame. The Select tool runs it from {@code frame}, so a large fill spreads over several frames instead of
 * stalling one. Client thread only.
 */
final class MagicJob {
    /** How the found blocks combine with the selection. */
    enum Combine {
        /** Click: they become the selection. */
        REPLACE,
        /** Shift+click: they join it. */
        ADD,
        /** Alt+click: they leave it. */
        SUBTRACT
    }

    /** Time the fill may take per frame. */
    static final long FRAME_BUDGET_NANOS = 2_000_000L;
    /** Cells examined between clock reads. */
    static final int BATCH = 1024;

    private final MagicSelect fill;
    private final Combine combine;

    MagicJob(MagicSelect fill, Combine combine) {
        this.fill = Objects.requireNonNull(fill);
        this.combine = Objects.requireNonNull(combine);
    }

    MagicSelect fill() {
        return fill;
    }

    Combine combine() {
        return combine;
    }

    /**
     * Runs the fill until it is done or {@code budgetNanos} have passed on {@code clock} (at least one batch runs);
     * returns whether it is done.
     */
    boolean run(long budgetNanos, LongSupplier clock) {
        long start = clock.getAsLong();
        do {
            if (fill.step(BATCH)) return true;
        } while (clock.getAsLong() - start < budgetNanos);
        return false;
    }
}
