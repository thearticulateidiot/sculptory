package dev.sculptory.core.region;

/**
 * A region with more cells than a caller's cap ({@link CellSet#of}). It is an {@link IllegalArgumentException}, so a
 * caller can map this subtype to a "too large" answer instead of "invalid".
 */
public final class RegionTooLargeException extends IllegalArgumentException {
    private final long cells;
    private final long limit;

    public RegionTooLargeException(long cells, long limit) {
        this(cells + " cells > limit of " + limit, cells, limit);
    }

    /** With its own message, for a size other than cells (sections or chunk columns, {@code Regions}). */
    public RegionTooLargeException(String message, long count, long limit) {
        super(message);
        this.cells = count;
        this.limit = limit;
    }

    /** The region's cell count (saturating at {@link Long#MAX_VALUE}). */
    public long cells() {
        return cells;
    }

    public long limit() {
        return limit;
    }
}
