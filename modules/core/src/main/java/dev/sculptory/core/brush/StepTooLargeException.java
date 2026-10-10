package dev.sculptory.core.brush;

/**
 * A brush step that plans to write more cells than its kernel allows ({@code CellPlan}'s limit; the Heightmap brush's is
 * {@link HeightmapSpec#MAX_STEP_CELLS}): working it out stops there, and nothing of it is written. The server refuses the
 * dab as too large.
 */
public final class StepTooLargeException extends IllegalStateException {
    private final long limit;

    public StepTooLargeException(long limit) {
        super("A brush step writes more than " + limit + " cells");
        this.limit = limit;
    }

    /** The most cells the step could have written. */
    public long limit() {
        return limit;
    }
}
