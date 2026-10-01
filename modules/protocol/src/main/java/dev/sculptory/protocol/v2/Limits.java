package dev.sculptory.protocol.v2;

/**
 * Server limits sent in {@code Welcome}. Defaults for {@code maxClipboardVolume} and {@code maxJobsPerPlayer}
 * are provisional (the architecture leaves them open): the op volume cap and the executor's 2 active jobs.
 *
 * @param maxDabRate dabs per second
 * @param maxSelectionCells the most cells one selection sent to the server may hold ({@code SelectionUpload})
 * @param maxSelectionSections the most 16³ sections one selection sent to the server may touch
 */
public record Limits(long maxOpVolume, long maxClipboardVolume, int maxBrushRadius, int maxDabRate,
                     long maxUploadBytes, int maxJobsPerPlayer, long maxSelectionCells, int maxSelectionSections) {
    /** The selection caps of {@link #DEFAULTS}: the op cap's cells in at most 65,536 sections. */
    public static final long DEFAULT_SELECTION_CELLS = 2_097_152L;
    public static final int DEFAULT_SELECTION_SECTIONS = 1 << 16;
    public static final Limits DEFAULTS = new Limits(2_097_152L, 2_097_152L, 32, 20, 32L << 20, 2);

    public Limits {
        if (maxOpVolume < 1 || maxClipboardVolume < 1 || maxBrushRadius < 1 || maxDabRate < 1
                || maxUploadBytes < 1 || maxJobsPerPlayer < 1 || maxSelectionCells < 1 || maxSelectionSections < 1) {
            throw new IllegalArgumentException("Limits must be positive");
        }
    }

    /** Limits with the default selection caps. */
    public Limits(long maxOpVolume, long maxClipboardVolume, int maxBrushRadius, int maxDabRate, long maxUploadBytes,
                  int maxJobsPerPlayer) {
        this(maxOpVolume, maxClipboardVolume, maxBrushRadius, maxDabRate, maxUploadBytes, maxJobsPerPlayer,
                DEFAULT_SELECTION_CELLS, DEFAULT_SELECTION_SECTIONS);
    }
}
