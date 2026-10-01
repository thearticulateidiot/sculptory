package dev.sculptory.core.palette;

import dev.sculptory.core.edit.MixLayout;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * A palette's Pattern setting: how the tools lay its blocks out, saved with the
 * palette so a load gives the same look. Every value is kept whichever pattern is chosen, so switching back finds them
 * again. The Gradient's line is a place in the world, drawn in the session, and is not part of a palette.
 *
 * @param kind Random (each block picked by weight), Patches, Gradient or Steepness
 * @param patchSize Patches' clump size in blocks, {@value MixLayout.Patches#MIN_SIZE}-{@value MixLayout.Patches#MAX_SIZE}
 * @param edge the Gradient's dithered edge in blocks, {@value MixLayout.Gradient#MIN_EDGE}-{@value MixLayout.Gradient#MAX_EDGE}
 * @param steepnessEdge Steepness' dithered edge in degrees,
 *     {@value MixLayout.Steepness#MIN_EDGE}-{@value MixLayout.Steepness#MAX_EDGE}
 * @param seed the seed of the layout's noise (the re-roll button picks a new one); Random ignores it (each stroke or
 *     Fill picks its own, as before)
 */
public record PalettePattern(Kind kind, int patchSize, int edge, int steepnessEdge, long seed) {
    /** The patterns. Wire order: append only. In files, {@link #fileName()}. */
    public enum Kind {
        RANDOM,
        PATCHES,
        GRADIENT,
        STEEPNESS;

        /** The name in a palette file: {@code random}, {@code patches}, {@code gradient}, {@code steepness}. */
        public String fileName() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The kind a file names, or empty for an unknown name. */
        public static Optional<Kind> fromFileName(String name) {
            for (Kind kind : values()) {
                if (kind.fileName().equals(name)) return Optional.of(kind);
            }
            return Optional.empty();
        }
    }

    /** Random, with every other value at its default: what a palette saved before patterns existed loads as. */
    public static final PalettePattern RANDOM = new PalettePattern(Kind.RANDOM, MixLayout.Patches.DEFAULT_SIZE,
            MixLayout.Gradient.DEFAULT_EDGE, MixLayout.Steepness.DEFAULT_EDGE, 0);

    public PalettePattern {
        Objects.requireNonNull(kind);
        if (patchSize < MixLayout.Patches.MIN_SIZE || patchSize > MixLayout.Patches.MAX_SIZE) {
            throw new IllegalArgumentException("Patch size must be " + MixLayout.Patches.MIN_SIZE + "-"
                    + MixLayout.Patches.MAX_SIZE + ", not " + patchSize);
        }
        if (edge < MixLayout.Gradient.MIN_EDGE || edge > MixLayout.Gradient.MAX_EDGE) {
            throw new IllegalArgumentException("Gradient edge must be " + MixLayout.Gradient.MIN_EDGE + "-"
                    + MixLayout.Gradient.MAX_EDGE + ", not " + edge);
        }
        if (steepnessEdge < MixLayout.Steepness.MIN_EDGE || steepnessEdge > MixLayout.Steepness.MAX_EDGE) {
            throw new IllegalArgumentException("Steepness edge must be " + MixLayout.Steepness.MIN_EDGE + "-"
                    + MixLayout.Steepness.MAX_EDGE + ", not " + steepnessEdge);
        }
    }
}
