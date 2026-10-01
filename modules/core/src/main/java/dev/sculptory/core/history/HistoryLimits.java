package dev.sculptory.core.history;

/**
 * History caps (config). The oldest entries are evicted first. 256 steps per player by default (64 before builder mode,
 * whose clicks are one step each), within the per-player and total byte caps.
 */
public record HistoryLimits(int maxEntries, long maxBytesPerPlayer, long maxBytesTotal) {
    public static final HistoryLimits DEFAULTS = new HistoryLimits(256, 256L << 20, 1L << 30);

    public HistoryLimits {
        if (maxEntries < 1 || maxBytesPerPlayer < 1 || maxBytesTotal < 1) {
            throw new IllegalArgumentException("History limits must be positive");
        }
    }
}
