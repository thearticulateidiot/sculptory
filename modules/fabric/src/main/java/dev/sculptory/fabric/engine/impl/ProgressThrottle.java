package dev.sculptory.fabric.engine.impl;

/**
 * Rate limit for {@code JobListener.progress}: never more than 4 events per second;
 * within that, an event is sent when progress advanced by at least 5% of the total, or at least once a second
 * while progress keeps changing. Phase changes bypass the throttle (at most a few per job).
 */
public final class ProgressThrottle {
    static final long MIN_INTERVAL_NANOS = 250_000_000L;
    static final long MAX_SILENCE_NANOS = 1_000_000_000L;

    private boolean emittedAny;
    private long lastNanos;
    private long lastDone = -1;

    /** Whether a progress event for {@code done} of {@code total} should be sent at {@code now}. */
    public boolean shouldEmit(long now, long done, long total) {
        if (done == lastDone) return false;
        if (!emittedAny) return true;
        long since = now - lastNanos;
        if (since < MIN_INTERVAL_NANOS) return false;
        boolean step = total <= 0 || (done - lastDone) * 20 >= total;
        return step || since >= MAX_SILENCE_NANOS;
    }

    /** Records an event that was sent (throttled or forced). */
    public void emitted(long now, long done) {
        emittedAny = true;
        lastNanos = now;
        lastDone = done;
    }
}
