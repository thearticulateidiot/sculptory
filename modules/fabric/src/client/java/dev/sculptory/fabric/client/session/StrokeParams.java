package dev.sculptory.fabric.client.session;

/**
 * Client-side stroke behaviour that does not affect the server's output.
 *
 * @param predict apply dabs locally before the server confirms them
 * @param maxPredictedCells dabs estimated to touch more cells than this are not predicted
 */
public record StrokeParams(boolean predict, int maxPredictedCells) {
    public static final StrokeParams DEFAULT = new StrokeParams(true, 32_768);

    public StrokeParams {
        if (maxPredictedCells < 0) throw new IllegalArgumentException("Negative prediction cap");
    }
}
