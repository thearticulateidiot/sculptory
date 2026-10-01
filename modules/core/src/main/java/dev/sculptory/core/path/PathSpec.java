package dev.sculptory.core.path;

import dev.sculptory.core.BlockPos;
import java.util.List;
import java.util.Objects;

/**
 * A line through clicked points (Generate's Line and the Shape brush's Line mode): {@value #MIN_POINTS}-{@value #MAX_POINTS} block positions, taken at their block centres.
 *
 * @param sag for {@link PathKind#HANGING}, how far each span's middle drops below the straight chord, 0-{@value #MAX_SAG}
 *     blocks; 0 for the other kinds
 */
public record PathSpec(List<BlockPos> points, PathKind kind, double sag) {
    public static final int MIN_POINTS = 2;
    public static final int MAX_POINTS = 64;
    public static final double MAX_SAG = 64;

    public PathSpec {
        points = List.copyOf(points);
        Objects.requireNonNull(kind);
        if (points.size() < MIN_POINTS || points.size() > MAX_POINTS) {
            throw new IllegalArgumentException("A line needs " + MIN_POINTS + "-" + MAX_POINTS + " points");
        }
        if (!(sag >= 0 && sag <= MAX_SAG)) throw new IllegalArgumentException("Sag must be 0-" + MAX_SAG);
        if (kind != PathKind.HANGING && sag != 0) throw new IllegalArgumentException("Only a hanging line sags");
    }

    /** A straight or curved line (no sag). */
    public PathSpec(List<BlockPos> points, PathKind kind) {
        this(points, kind, 0);
    }
}
