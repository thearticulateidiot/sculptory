package dev.sculptory.core.edit;

/**
 * An op refused by {@link OpCompiler#compile} because its {@link OpCompiler#targetVolume target} or
 * {@link OpCompiler#sourceVolume source} volume exceeds {@link CompileContext#maxCells()}. It is an
 * {@link IllegalArgumentException}, so callers that only catch that keep working; callers can map this subtype
 * to a "too large" answer instead of "invalid".
 */
public final class EditTooLargeException extends IllegalArgumentException {
    private final long cells;
    private final long budget;

    public EditTooLargeException(long cells, long budget) {
        this(cells + " cells > budget of " + budget, cells, budget);
    }

    /** With its own message, for a size other than cells (a shape's rows, {@code OpCompiler.checkShape}). */
    public EditTooLargeException(String message, long cells, long budget) {
        super(message);
        this.cells = cells;
        this.budget = budget;
    }

    /** The op's volume (saturating at {@link Long#MAX_VALUE}). */
    public long cells() {
        return cells;
    }

    public long budget() {
        return budget;
    }
}
