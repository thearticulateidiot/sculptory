package dev.sculptory.protocol.v2;

import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.BrushTool;
import dev.sculptory.core.brush.HeightmapSpec;

/**
 * Server limits sent in {@code Welcome}. Defaults for {@code maxClipboardVolume} and {@code maxJobsPerPlayer}
 * are provisional (the architecture leaves them open): the op volume cap and the executor's 2 active jobs.
 *
 * @param maxBrushRadius the largest radius of every brush but the Heightmap brush
 * @param maxDabRate dabs per second
 * @param maxSelectionCells the most cells one selection sent to the server may hold ({@code SelectionUpload})
 * @param maxSelectionSections the most 16³ sections one selection sent to the server may touch
 * @param maxHeightmapRadius the Heightmap brush's largest radius (protocol 6)
 * @param maxHeightmapHeight the Heightmap brush's tallest (or deepest) stamp, in blocks (protocol 6)
 */
public record Limits(long maxOpVolume, long maxClipboardVolume, int maxBrushRadius, int maxDabRate,
                     long maxUploadBytes, int maxJobsPerPlayer, long maxSelectionCells, int maxSelectionSections,
                     int maxHeightmapRadius, int maxHeightmapHeight) {
    /** The selection caps of {@link #DEFAULTS}: the op cap's cells in at most 65,536 sections. */
    public static final long DEFAULT_SELECTION_CELLS = 2_097_152L;
    public static final int DEFAULT_SELECTION_SECTIONS = 1 << 16;
    public static final Limits DEFAULTS = new Limits(2_097_152L, 2_097_152L, 32, 20, 32L << 20, 2);

    public Limits {
        if (maxOpVolume < 1 || maxClipboardVolume < 1 || maxBrushRadius < 1 || maxDabRate < 1
                || maxUploadBytes < 1 || maxJobsPerPlayer < 1 || maxSelectionCells < 1 || maxSelectionSections < 1
                || maxHeightmapRadius < 1 || maxHeightmapHeight < 1) {
            throw new IllegalArgumentException("Limits must be positive");
        }
    }

    /** Limits with the Heightmap brush's own caps at their largest ({@link BrushSpec#MAX_HEIGHTMAP_RADIUS}, 128 tall). */
    public Limits(long maxOpVolume, long maxClipboardVolume, int maxBrushRadius, int maxDabRate, long maxUploadBytes,
                  int maxJobsPerPlayer, long maxSelectionCells, int maxSelectionSections) {
        this(maxOpVolume, maxClipboardVolume, maxBrushRadius, maxDabRate, maxUploadBytes, maxJobsPerPlayer,
                maxSelectionCells, maxSelectionSections, BrushSpec.MAX_HEIGHTMAP_RADIUS, HeightmapSpec.MAX_HEIGHT_BLOCKS);
    }

    /** Limits with the default selection caps. */
    public Limits(long maxOpVolume, long maxClipboardVolume, int maxBrushRadius, int maxDabRate, long maxUploadBytes,
                  int maxJobsPerPlayer) {
        this(maxOpVolume, maxClipboardVolume, maxBrushRadius, maxDabRate, maxUploadBytes, maxJobsPerPlayer,
                DEFAULT_SELECTION_CELLS, DEFAULT_SELECTION_SECTIONS);
    }

    /** The largest radius a brush of {@code tool} may have here: {@link BrushSpec#maxRadius} capped by these limits. */
    public int maxRadius(BrushTool tool) {
        int cap = tool == BrushTool.HEIGHTMAP ? maxHeightmapRadius : maxBrushRadius;
        return Math.max(BrushSpec.MIN_RADIUS, Math.min(BrushSpec.maxRadius(tool), cap));
    }
}
