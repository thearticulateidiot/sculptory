package dev.sculptory.core.region;

/** Bytes {@link CellSet#decode} refuses: malformed, or over the caller's {@link CellSet.Limits}. */
public final class CellSetFormatException extends Exception {
    private final boolean tooLarge;

    public CellSetFormatException(String message, boolean tooLarge) {
        super(message);
        this.tooLarge = tooLarge;
    }

    /** True when the input is well formed as far as it was read but over a limit; false when it is malformed. */
    public boolean tooLarge() {
        return tooLarge;
    }
}
