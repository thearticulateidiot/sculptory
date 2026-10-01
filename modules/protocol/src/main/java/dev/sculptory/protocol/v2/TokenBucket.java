package dev.sculptory.protocol.v2;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * A token bucket: {@code tokensPerPeriod} tokens refill continuously over each {@code periodNanos}, up to
 * {@code capacity}; it starts full. Integer arithmetic in token-nanoseconds, so it is exact and cannot drift.
 * Not thread-safe.
 */
public final class TokenBucket {
    public static final long NANOS_PER_SECOND = 1_000_000_000L;
    public static final long NANOS_PER_MINUTE = 60 * NANOS_PER_SECOND;

    private final long tokensPerPeriod;
    private final long periodNanos;
    private final long capacity;
    private final long capacityScaled;
    private final long fillNanos;
    private final LongSupplier nanoClock;
    private long availableScaled;
    private long lastNanos;

    /** {@code ratePerSecond} tokens per second. */
    public TokenBucket(long ratePerSecond, long capacity, LongSupplier nanoClock) {
        this(ratePerSecond, NANOS_PER_SECOND, capacity, nanoClock);
    }

    /**
     * @param nanoClock a monotonic clock such as {@code System::nanoTime}
     */
    public TokenBucket(long tokensPerPeriod, long periodNanos, long capacity, LongSupplier nanoClock) {
        if (tokensPerPeriod < 1 || periodNanos < 1 || capacity < 1) {
            throw new IllegalArgumentException("Rate, period and capacity must be positive");
        }
        if (capacity > Long.MAX_VALUE / periodNanos) throw new IllegalArgumentException("Capacity too large");
        this.tokensPerPeriod = tokensPerPeriod;
        this.periodNanos = periodNanos;
        this.capacity = capacity;
        this.capacityScaled = capacity * periodNanos;
        this.fillNanos = capacityScaled / tokensPerPeriod + 1;
        this.nanoClock = Objects.requireNonNull(nanoClock);
        this.availableScaled = capacityScaled;
        this.lastNanos = nanoClock.getAsLong();
    }

    /** Tokens per second, rounded down (0 for buckets slower than one token a second). */
    public long ratePerSecond() {
        return tokensPerPeriod * NANOS_PER_SECOND / periodNanos;
    }

    public long capacity() {
        return capacity;
    }

    /** Takes {@code tokens} if they are all available; otherwise takes nothing. Requests over capacity fail. */
    public boolean tryAcquire(long tokens) {
        if (tokens < 0) throw new IllegalArgumentException("Negative token request");
        refill();
        if (tokens > capacity) return false;
        long cost = tokens * periodNanos;
        if (availableScaled < cost) return false;
        availableScaled -= cost;
        return true;
    }

    /** Whole tokens available now. */
    public long available() {
        refill();
        return availableScaled / periodNanos;
    }

    private void refill() {
        long now = nanoClock.getAsLong();
        long elapsed = now - lastNanos;
        lastNanos = now;
        if (elapsed <= 0) return;
        if (elapsed >= fillNanos) {
            availableScaled = capacityScaled;
        } else {
            availableScaled = Math.min(capacityScaled, availableScaled + elapsed * tokensPerPeriod);
        }
    }
}
