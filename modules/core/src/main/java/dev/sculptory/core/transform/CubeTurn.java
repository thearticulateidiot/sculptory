package dev.sculptory.core.transform;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One of the 48 symmetries of a cube (the signed permutations of the axes) as block states take it: an exact
 * {@link Transform} ({@link #first()}: mirror, quarter turns about the vertical, flip upside down; the 16 symmetries
 * that keep up vertical), then at most one {@link Tip}: a quarter turn about a horizontal axis. Every cube symmetry is
 * one of these 48 pairs, and {@link #nearest} picks the one nearest an {@link Orientation}'s turn: what directional
 * blocks snap to when a copy turns by a free angle ("the nearest facing on any axis").
 *
 * @param first the exact part, applied first
 * @param tip the quarter turn about a horizontal axis applied after it, or {@link Tip#NONE}
 */
public record CubeTurn(Transform first, Tip tip) {
    /** A quarter turn about a horizontal world axis, applied after the exact part. */
    public enum Tip {
        /** None: the turn keeps up vertical. */
        NONE,
        /** {@code Orientation}'s pitch +90° about the east-west axis: up goes south, south down, down north. */
        PITCH,
        /** {@code Orientation}'s roll +90° about the north-south axis: east goes up, up west, west down. */
        ROLL;

        /** The tip's matrix, row-major (the identity for NONE). */
        public int[] matrix() {
            return switch (this) {
                case NONE -> new int[] {1, 0, 0, 0, 1, 0, 0, 0, 1};
                case PITCH -> new int[] {1, 0, 0, 0, 0, -1, 0, 1, 0};
                case ROLL -> new int[] {0, -1, 0, 1, 0, 0, 0, 0, 1};
            };
        }

        /** Where the direction {@code (x, y, z)} (a unit axis vector) goes, as a new array. */
        public int[] apply(int x, int y, int z) {
            int[] m = matrix();
            return new int[] {m[0] * x + m[1] * y + m[2] * z, m[3] * x + m[4] * y + m[5] * z, m[6] * x + m[7] * y + m[8] * z};
        }
    }

    /** No turn at all. */
    public static final CubeTurn IDENTITY = new CubeTurn(Transform.IDENTITY, Tip.NONE);

    /** The 24 rotations of the cube, the identity first, then in a fixed order (ties in {@link #nearest} take the first). */
    private static final List<int[]> ROTATIONS = rotations();

    public CubeTurn {
        Objects.requireNonNull(first);
        Objects.requireNonNull(tip);
    }

    /** Whether the turn keeps up vertical (no tip): an exact {@link Transform} alone. */
    public boolean upright() {
        return tip == Tip.NONE;
    }

    /** The whole turn's matrix, row-major: the tip times the exact part's signed permutation. */
    public int[] matrix() {
        return times(tip.matrix(), Orientation.linear(first));
    }

    /**
     * The cube symmetry nearest the orientation's linear map {@code R · T}: {@code Q · T} for the rotation {@code Q} of
     * the cube that agrees most with the free rotation {@code R} (the largest {@code trace(Qᵀ R)}; ties go to the first
     * in a fixed order that starts with the identity). An exact orientation, or a free one turned by multiples of 90°,
     * gets its own map exactly.
     */
    public static CubeTurn nearest(Orientation orientation) {
        Objects.requireNonNull(orientation);
        if (orientation.isExact()) return new CubeTurn(orientation.transform(), Tip.NONE);
        double[] r = Orientation.rotation(orientation.yaw(), orientation.pitch(), orientation.roll());
        int[] best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int[] q : ROTATIONS) {
            double score = 0;
            for (int i = 0; i < 9; i++) score += q[i] * r[i];
            if (score > bestScore) {
                bestScore = score;
                best = q;
            }
        }
        return of(times(best, Orientation.linear(orientation.transform())));
    }

    /**
     * The pair for the signed permutation {@code q} (row-major): with the tip that takes up to where {@code q} takes it
     * (pitch for north or south, roll for east or west), and the exact part the tip leaves to do.
     *
     * @throws IllegalArgumentException if {@code q} is not a signed permutation
     */
    public static CubeTurn of(int[] q) {
        if (q.length != 9) throw new IllegalArgumentException("A cube symmetry has 9 entries");
        // Where up goes: the matrix's middle column.
        Tip tip = q[4] != 0 ? Tip.NONE : q[7] != 0 ? Tip.PITCH : Tip.ROLL;
        // first = tipᵀ · q (a rotation's inverse is its transpose).
        int[] rest = times(transpose(tip.matrix()), q);
        for (Transform t : Transform.allWithFlips()) {
            if (java.util.Arrays.equals(Orientation.linear(t), rest)) return new CubeTurn(t, tip);
        }
        throw new IllegalArgumentException("Not a cube symmetry: " + java.util.Arrays.toString(q));
    }

    private static List<int[]> rotations() {
        List<int[]> out = new ArrayList<>(24);
        int[][] permutations = {{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}};
        for (int[] p : permutations) {
            for (int signs = 0; signs < 8; signs++) {
                int[] m = new int[9];
                for (int row = 0; row < 3; row++) m[3 * row + p[row]] = (signs & (1 << row)) == 0 ? 1 : -1;
                if (determinant(m) == 1) out.add(m);
            }
        }
        return List.copyOf(out);
    }

    private static int determinant(int[] m) {
        return m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6]);
    }

    private static int[] transpose(int[] m) {
        return new int[] {m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8]};
    }

    static int[] times(int[] a, int[] b) {
        int[] m = new int[9];
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                for (int k = 0; k < 3; k++) m[3 * row + col] += a[3 * row + k] * b[3 * k + col];
            }
        }
        return m;
    }
}
