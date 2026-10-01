package dev.sculptory.core.mask;

import dev.sculptory.core.region.Region;
import java.util.Objects;

/**
 * One rule of an {@link EditMask}, tested at each cell an edit writes, against the
 * world as it was before the edit. Rules that look at a neighbour ({@link OnTopOf}, {@link Under}, {@link NextTo},
 * {@link Exposed}, {@link Slope}) need the cells around the written one; the others ({@link Is}, {@link NotAir},
 * {@link Solid}, {@link Height}, {@link Inside}, {@link Chance}) the cell alone. Wire kinds 0-10 in declaration order
 * (append only).
 */
public sealed interface MaskRule {
    /** The most blocks, up or down, a {@link Slope} can name: as {@code SurfaceMask.Slope}. */
    int MAX_SLOPE = 16;

    /** The cell's state is in the set. */
    record Is(BlockSet blocks) implements MaskRule {
        public Is {
            Objects.requireNonNull(blocks);
        }
    }

    /** The cell below is in the set (the cell sits on top of one of these blocks). */
    record OnTopOf(BlockSet blocks) implements MaskRule {
        public OnTopOf {
            Objects.requireNonNull(blocks);
        }
    }

    /** The cell above is in the set. */
    record Under(BlockSet blocks) implements MaskRule {
        public Under {
            Objects.requireNonNull(blocks);
        }
    }

    /** One of the six cells around is in the set. */
    record NextTo(BlockSet blocks) implements MaskRule {
        public NextTo {
            Objects.requireNonNull(blocks);
        }
    }

    /** One of the six cells around is air ({@code StateFlags.AIR}). */
    record Exposed() implements MaskRule {}

    /** The cell is not air. */
    record NotAir() implements MaskRule {}

    /** The cell is solid ground ({@code StateFlags.TERRAIN_SOLID}). */
    record Solid() implements MaskRule {}

    /** The cell's y is in {@code [minY, maxY]}. */
    record Height(int minY, int maxY) implements MaskRule {
        public Height {
            if (minY > maxY) throw new IllegalArgumentException("Inverted height range");
        }
    }

    /**
     * The steepest cardinal step, in blocks, from the surface of the cell's column to a neighbouring column's is in
     * {@code [minStep, maxStep]}, 0-{@value #MAX_SLOPE}, as {@code SurfaceMask.Slope} measures it.
     */
    record Slope(int minStep, int maxStep) implements MaskRule {
        public Slope {
            if (minStep < 0 || minStep > maxStep || maxStep > MAX_SLOPE) {
                throw new IllegalArgumentException("Slope range must be within 0-" + MAX_SLOPE);
            }
        }
    }

    /** The cell is inside the region (the selection when the rule was made); on the wire a cuboid, shape or upload. */
    record Inside(Region region) implements MaskRule {
        public Inside {
            Objects.requireNonNull(region);
        }
    }

    /**
     * A random {@code percent} of cells (1-99), the same cells for the same seed: {@code SplitMix64.hash(seed, x, y, z)}
     * reduced modulo 100 is below {@code percent}.
     */
    record Chance(int percent, long seed) implements MaskRule {
        public Chance {
            if (percent < 1 || percent > 99) throw new IllegalArgumentException("Chance must be 1-99%");
        }
    }
}
