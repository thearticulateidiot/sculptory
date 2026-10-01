package dev.sculptory.core.generate;

/** A generator would produce more cells than its caller allows; nothing more was generated. */
public final class GeneratedTooLargeException extends RuntimeException {
    private final long maxCells;

    public GeneratedTooLargeException(long maxCells) {
        super("More than " + maxCells + " generated cells");
        this.maxCells = maxCells;
    }

    public long maxCells() {
        return maxCells;
    }
}
