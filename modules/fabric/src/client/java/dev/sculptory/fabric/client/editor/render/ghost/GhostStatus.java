package dev.sculptory.fabric.client.editor.render.ghost;

import java.util.Locale;
import java.util.Objects;

/**
 * What the last frame of one ghost preview drew, for the HUD.
 *
 * @param totalBlocks block cells in the volume
 * @param meshedBlocks block cells of the sections chosen for full meshes
 * @param simplifiedSections sections drawn as bounding boxes instead
 * @param reason the most important reason for simplifying, or {@link Reason#NONE}
 */
public record GhostStatus(long totalBlocks, long meshedBlocks, int simplifiedSections, Reason reason) {
    public static final GhostStatus EMPTY = new GhostStatus(0, 0, 0, Reason.NONE);

    /** Why sections were simplified, in increasing order of importance. */
    public enum Reason {
        NONE,
        /** Some sections are beyond the full-detail distance. */
        DISTANCE,
        /** The vertex memory cap was reached. */
        MEMORY_CAP,
        /** The meshed block cap was reached. */
        BLOCK_CAP,
        /** A shader pack is active: everything is drawn as outlines. */
        SHADER_PACK
    }

    public GhostStatus {
        Objects.requireNonNull(reason, "reason");
    }

    public boolean simplified() {
        return reason != Reason.NONE;
    }

    /** "Preview simplified: 1.2M blocks" while simplified, otherwise empty. */
    public String hudText() {
        return simplified() ? "Preview simplified: " + compactCount(totalBlocks) + " blocks" : "";
    }

    /** 950, 12.3k, 1.2M: one decimal, trailing ".0" dropped. */
    static String compactCount(long count) {
        if (count < 1_000) {
            return Long.toString(count);
        }
        if (count < 1_000_000) {
            return oneDecimal(count / 1_000.0) + "k";
        }
        return oneDecimal(count / 1_000_000.0) + "M";
    }

    private static String oneDecimal(double value) {
        // Truncate, so 999_999 reads "999.9k" rather than rounding up to "1000.0k" (the epsilon absorbs binary
        // representation error, e.g. 1.2 * 10 just below 12).
        String text = String.format(Locale.ROOT, "%.1f", Math.floor(value * 10 + 1e-9) / 10);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }
}
