package dev.sculptory.core.edit;

import dev.sculptory.core.BlockPos;
import java.util.Objects;

/**
 * How a block mix is laid out in space: the Pattern setting's
 * <b>Patches</b>, <b>Gradient</b> and <b>Steepness</b>. <b>Random</b>, the default, is a plain {@link Pattern.Weighted}
 * and has no layout. A layout is carried by {@link Pattern.Arranged}, which pairs it with the mix's blocks, weights and
 * seed.
 *
 * <p>Every layout is a pure function of the cell's position, the mix and its seed (and for Steepness the steepness of
 * the ground there, which the caller measures): the same inputs give the same block on the client and the server. The
 * arithmetic is integer where it can be, then {@code + - * /}, {@code Math.floor}, {@code Math.sqrt} and
 * {@link StrictMath}, which Java defines exactly.
 *
 * <ul>
 *   <li><b>Patches</b>: clumps about {@code size} blocks across. Space is cut into cells of {@code size} blocks, each
 *       holding one point at a random place in it; a block takes the entry of the nearest point (among its own cell's
 *       and the 26 around it), after its position is pushed about by a smooth noise so the borders wave instead of
 *       running straight. Each point picks its entry by weight, so the weights set roughly how much of each block
 *       appears.</li>
 *   <li><b>Gradient</b>: the entries lie in order along the line from {@code from} to {@code to} (block centres),
 *       each taking a stretch as long as its share of the total weight. A block's place along the line is its centre's
 *       projection onto it; blocks before the start take the first entry and blocks past the end the last. Each border
 *       between two entries is dithered over {@code edge} blocks: every block's place is moved by a random amount of up
 *       to half the edge either way before its entry is looked up, so the next block's share rises evenly across the
 *       edge.</li>
 *   <li><b>Steepness</b>: the entries lie in order from flat ground (0°) to vertical (90°), each taking a share of the
 *       angle by weight; the ground's steepness at a block is measured by the caller (Palette Paint: the column's slope,
 *       {@code core.brush.ColumnSteepness}). The borders are dithered over {@code edge} degrees as the Gradient's are
 *       over blocks.</li>
 * </ul>
 */
public sealed interface MixLayout {
    /** Blocks further than this from the origin (x, z) cannot be a Gradient's end (the kernels' horizontal limit). */
    int MAX_HORIZONTAL = 1 << 25;
    /** The largest |y| of a Gradient's end. */
    int MAX_VERTICAL = 4096;

    /** Clumps about {@code size} blocks across ({@value #MIN_SIZE}-{@value #MAX_SIZE}). */
    record Patches(int size) implements MixLayout {
        public static final int MIN_SIZE = 1;
        public static final int MAX_SIZE = 32;
        public static final int DEFAULT_SIZE = 6;

        public Patches {
            if (size < MIN_SIZE || size > MAX_SIZE) {
                throw new IllegalArgumentException("Patch size must be " + MIN_SIZE + "-" + MAX_SIZE + ", not " + size);
            }
        }

        /** The entry of {@code mix} at block (x, y, z). */
        int entry(Pattern.Weighted mix, int x, int y, int z) {
            return mix.entryFor(MixNoise.patch(mix.seed(), size, x, y, z));
        }
    }

    /**
     * The mix in order along the line from block {@code from} to block {@code to} (not the same block), its borders
     * dithered over {@code edge} blocks ({@value #MIN_EDGE}-{@value #MAX_EDGE}).
     */
    record Gradient(BlockPos from, BlockPos to, int edge) implements MixLayout {
        public static final int MIN_EDGE = 0;
        public static final int MAX_EDGE = 32;
        public static final int DEFAULT_EDGE = 4;

        public Gradient {
            Objects.requireNonNull(from);
            Objects.requireNonNull(to);
            if (from.equals(to)) throw new IllegalArgumentException("A gradient line needs two different blocks");
            checkEnd(from);
            checkEnd(to);
            if (edge < MIN_EDGE || edge > MAX_EDGE) {
                throw new IllegalArgumentException("Gradient edge must be " + MIN_EDGE + "-" + MAX_EDGE + ", not " + edge);
            }
        }

        /** Whether {@code end} may be a line's end: within ±2²⁵ across and ±4096 up and down. */
        public static boolean validEnd(BlockPos end) {
            return Math.abs((long) end.x()) <= MAX_HORIZONTAL && Math.abs((long) end.z()) <= MAX_HORIZONTAL
                    && Math.abs((long) end.y()) <= MAX_VERTICAL;
        }

        private static void checkEnd(BlockPos end) {
            if (!validEnd(end)) throw new IllegalArgumentException("A gradient line end outside the world: " + end);
        }

        /**
         * Where block (x, y, z) lies along the line: 0 at the start's centre, 1 at the end's, the projection of its
         * centre (below 0 before the start, above 1 past the end).
         */
        public double place(int x, int y, int z) {
            long dx = (long) to.x() - from.x(), dy = (long) to.y() - from.y(), dz = (long) to.z() - from.z();
            long length2 = dx * dx + dy * dy + dz * dz;
            // Exact: each product is under 2^59 (a block within 2^31, the ends within 2^26 of each other).
            long along = ((long) x - from.x()) * dx + ((long) y - from.y()) * dy + ((long) z - from.z()) * dz;
            return (double) along / length2;
        }

        /** The line's length in blocks (between the two blocks' centres). */
        public double length() {
            long dx = (long) to.x() - from.x(), dy = (long) to.y() - from.y(), dz = (long) to.z() - from.z();
            return Math.sqrt((double) (dx * dx + dy * dy + dz * dz));
        }

        int entry(Pattern.Weighted mix, int x, int y, int z) {
            // Dithered before the clamp, so the line's ends blend like every other border (no hard seam at either end).
            double t = place(x, y, z) + MixNoise.dither(mix.seed(), x, y, z) * edge / length();
            if (t <= 0) return 0;
            if (t >= 1) return mix.size() - 1;
            return mix.entryAt(t);
        }
    }

    /**
     * The mix in order from flat ground to vertical faces, its borders dithered over {@code edge} degrees
     * ({@value #MIN_EDGE}-{@value #MAX_EDGE}).
     */
    record Steepness(int edge) implements MixLayout {
        public static final int MIN_EDGE = 0;
        public static final int MAX_EDGE = 45;
        public static final int DEFAULT_EDGE = 10;

        public Steepness {
            if (edge < MIN_EDGE || edge > MAX_EDGE) {
                throw new IllegalArgumentException("Steepness edge must be " + MIN_EDGE + "-" + MAX_EDGE + ", not " + edge);
            }
        }

        /** The entry for ground {@code degrees} steep (0 flat, 90 vertical; clamped) at block (x, y, z). */
        int entry(Pattern.Weighted mix, int x, int y, int z, double degrees) {
            double angle = Math.max(0, Math.min(90, degrees));
            return mix.entryAt((angle + MixNoise.dither(mix.seed(), x, y, z) * edge) / 90);
        }
    }
}
