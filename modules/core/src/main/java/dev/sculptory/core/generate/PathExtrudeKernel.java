package dev.sculptory.core.generate;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.path.PathFrame;
import dev.sculptory.core.path.PathKind;
import dev.sculptory.core.path.PathSample;
import dev.sculptory.core.path.PathSampler;
import dev.sculptory.core.path.PathSpec;
import dev.sculptory.core.state.StateSpace;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Extrude along a path: a flat face carried along a line through clicked points, turning with it. Commits like
 * Generate's Line, as a generated clipboard pasted with the {@code PATH_EXTRUDE} label.
 *
 * <p>The line starts at the face's centre cell ({@link FaceCells#centre}) and runs Straight, Curve or Hanging through the
 * points ({@link PathSampler}). The face, square to its normal, is first turned onto the line's heading, then carried
 * along it ({@link SectionSweep}) from half a block past the face to half a block past the last point, so a face pulled
 * straight out along its normal makes exactly its own blocks once per layer. The face itself is never written.
 *
 * <p>How it turns: a wall face stays upright, turning with the heading only ({@link PathFrame#upright}); with
 * {@code followSlope} it tilts with the climb ({@link PathFrame#followSlope}), as Generate's Sweep does. A floor or
 * ceiling face turns the least way at every step ({@link PathFrame#minimalTwist}). Block states turn with the heading
 * by the nearest quarter turn. Face cells holding a block entity are left out ({@link GeneratedResult#leftOut}).
 */
public final class PathExtrudeKernel {
    /** The line is followed through samples this far apart (blocks). */
    static final double SAMPLE_SPACING = 0.125;

    private PathExtrudeKernel() {}

    /**
     * @param face the face (not empty)
     * @param points the clicked points, 1-{@value PathSpec#MAX_POINTS}-1 of them: the line runs from the face's centre
     *     through them
     * @param sag for a hanging line, as {@link PathSpec#sag}; else 0
     */
    public record Spec(FaceCells face, List<BlockPos> points, PathKind kind, double sag, boolean followSlope) {
        public Spec {
            Objects.requireNonNull(face);
            Objects.requireNonNull(kind);
            points = List.copyOf(points);
            if (face.isEmpty()) throw new IllegalArgumentException("An empty face");
            if (points.isEmpty() || points.size() + 1 > PathSpec.MAX_POINTS) {
                throw new IllegalArgumentException("A path needs 1-" + (PathSpec.MAX_POINTS - 1) + " points");
            }
            path(face, points, kind, sag); // refuses a bad sag
        }

        /** The line the face is carried along: from its centre cell through the points. */
        public PathSpec path() {
            return path(face, points, kind, sag);
        }

        private static PathSpec path(FaceCells face, List<BlockPos> points, PathKind kind, double sag) {
            List<BlockPos> all = new ArrayList<>(points.size() + 1);
            all.add(face.centre());
            all.addAll(points);
            return new PathSpec(all, kind, kind == PathKind.HANGING ? sag : 0);
        }
    }

    /**
     * The carried face's cells within {@code [bottomY, topYExclusive)}.
     *
     * @throws GeneratedTooLargeException over {@code maxCells}
     */
    public static GeneratedResult generate(Spec spec, StateSpace states, long maxCells, int bottomY, int topYExclusive) {
        Objects.requireNonNull(spec);
        Objects.requireNonNull(states);
        FaceCells face = spec.face().without(states);
        if (face.isEmpty()) return new GeneratedResult(GeneratedSource.empty(), face.leftOut());
        PathSpec path = spec.path();
        // Every block along adds a layer of the face: refuse a hopeless one before sampling.
        if (PathSampler.chordLength(path) - 1 > (double) maxCells / face.size() + 1) {
            throw new GeneratedTooLargeException(maxCells);
        }
        List<PathSample> samples;
        try {
            samples = PathSampler.sample(path, SAMPLE_SPACING);
        } catch (IllegalArgumentException tooLong) {
            throw new GeneratedTooLargeException(maxCells);
        }
        boolean closed = PathSampler.closed(path);
        double[] n = face.normal();
        // The face's own right and up, as a frame square to its normal has them (v = u × t).
        double[] u0 = face.wall() ? new double[] {-n[2], 0, n[0]} : new double[] {1, 0, 0};
        double[] v0 = {u0[1] * n[2] - u0[2] * n[1], u0[2] * n[0] - u0[0] * n[2], u0[0] * n[1] - u0[1] * n[0]};
        List<PathFrame.Frame> frames;
        if (!face.wall()) {
            frames = PathFrame.minimalTwist(samples, closed, n, u0);
        } else if (spec.followSlope()) {
            frames = PathFrame.followSlope(samples, closed, n);
        } else {
            frames = PathFrame.upright(samples, closed, n);
        }
        BlockPos centre = face.centre();
        double cx = centre.x() + 0.5, cy = centre.y() + 0.5, cz = centre.z() + 0.5;
        double[] a = new double[face.size()], b = new double[face.size()];
        int[] cellStates = new int[face.size()];
        for (int i = 0; i < face.size(); i++) {
            double dx = face.x(i) + 0.5 - cx, dy = face.y(i) + 0.5 - cy, dz = face.z(i) + 0.5 - cz;
            a[i] = dx * u0[0] + dy * u0[1] + dz * u0[2];
            b[i] = dx * v0[0] + dy * v0[1] + dz * v0[2];
            cellStates[i] = face.state(i);
        }
        SectionSweep.Section section = new SectionSweep.Section(a, b, cellStates);
        Long2IntOpenHashMap own = spec.face().index();
        double length = samples.get(samples.size() - 1).along();
        // A wall face's blocks turn from its own heading; a floor's from the line's first heading.
        double[] reference = face.wall() ? n : null;
        GeneratedSource source = SectionSweep.sweep(samples, frames, closed, new SectionSweep.Section[] {section},
                along -> 0, SectionSweep.STEP * 1.5, length + SectionSweep.STEP / 2, reference, states,
                (x, y, z) -> own.get(FaceCells.key(x, y, z)) >= 0, maxCells, bottomY, topYExclusive);
        return new GeneratedResult(source, face.leftOut());
    }
}
