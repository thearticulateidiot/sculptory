package dev.sculptory.core.generate;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.path.PathFrame;
import dev.sculptory.core.path.PathKind;
import dev.sculptory.core.path.PathSample;
import dev.sculptory.core.path.PathSampler;
import dev.sculptory.core.path.PathSpec;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.state.StateSpace;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Generate's Sweep: a profile (the selection's blocks, snapshotted on the client thread) swept along a line through
 * clicked points. Commits like the Line, as a generated clipboard pasted with the {@code SWEEP} label.
 *
 * <p><b>The profile.</b> Its forward axis is its thinner horizontal side (on a tie, the one nearer the line's first
 * heading), pointing the way the line first heads; across it lies its right, and up is up. Its bottom middle cell (the
 * middle of its bounds across and along, its lowest layer) stands on the line: on the clicked blocks, so the profile's
 * bottom layer is one block above each. A profile thicker than one block repeats along the line: each block along takes
 * the next slice of it, front to back, over and over (a fence profile with a post in one of three slices puts a post
 * every three blocks).
 *
 * <p><b>Turning.</b> The profile turns to follow the line's heading and stays upright ({@link PathFrame#upright}); with
 * {@code followSlope} it tilts with the climb ({@link PathFrame#followSlope}). It is carried along ({@link SectionSweep})
 * from half a block before the first point to half a block past the last (a loop all the way round once). Block states
 * turn with the heading by the nearest quarter turn. Air in the profile is not swept; cells holding a block entity are
 * left out ({@link GeneratedResult#leftOut}).
 */
public final class SweepKernel {
    /** The most blocks a profile may hold. */
    public static final int MAX_PROFILE_CELLS = 32_768;
    static final double SAMPLE_SPACING = 0.125;

    private SweepKernel() {}

    /** A profile's blocks in world cells, with their states (air allowed: it is not swept). Arrays are copied. */
    public record Profile(int[] xs, int[] ys, int[] zs, int[] states) {
        public Profile {
            xs = xs.clone();
            ys = ys.clone();
            zs = zs.clone();
            states = states.clone();
            if (ys.length != xs.length || zs.length != xs.length || states.length != xs.length) {
                throw new IllegalArgumentException("Uneven profile");
            }
            if (xs.length > MAX_PROFILE_CELLS) {
                throw new IllegalArgumentException("A profile holds at most " + MAX_PROFILE_CELLS + " blocks");
            }
        }

        public int size() {
            return xs.length;
        }
    }

    /**
     * @param points the clicked points, {@value PathSpec#MIN_POINTS}-{@value PathSpec#MAX_POINTS}
     * @param sag for a hanging line, as {@link PathSpec#sag}; else 0
     */
    public record Spec(Profile profile, List<BlockPos> points, PathKind kind, double sag, boolean followSlope) {
        public Spec {
            Objects.requireNonNull(profile);
            Objects.requireNonNull(kind);
            points = List.copyOf(points);
            new PathSpec(points, kind, kind == PathKind.HANGING ? sag : 0); // refuses bad points or sag
        }

        public PathSpec path() {
            return new PathSpec(points, kind, kind == PathKind.HANGING ? sag : 0);
        }
    }

    /**
     * The swept cells within {@code [bottomY, topYExclusive)}.
     *
     * @throws GeneratedTooLargeException over {@code maxCells}
     */
    public static GeneratedResult generate(Spec spec, StateSpace states, long maxCells, int bottomY, int topYExclusive) {
        Objects.requireNonNull(spec);
        Objects.requireNonNull(states);
        Profile profile = spec.profile();
        // The blocks swept: no air, no block entities (counted).
        List<int[]> kept = new ArrayList<>(profile.size());
        long leftOut = 0;
        for (int i = 0; i < profile.size(); i++) {
            int state = profile.states()[i];
            int flags = states.flags(state);
            if (StateFlags.has(flags, StateFlags.AIR)) continue;
            if (StateFlags.has(flags, StateFlags.HAS_BLOCK_ENTITY)) {
                leftOut++;
                continue;
            }
            kept.add(new int[] {profile.xs()[i], profile.ys()[i], profile.zs()[i], state});
        }
        if (kept.isEmpty()) return new GeneratedResult(GeneratedSource.empty(), leftOut);
        PathSpec path = spec.path();
        List<PathSample> samples;
        try {
            samples = PathSampler.sample(path, SAMPLE_SPACING);
        } catch (IllegalArgumentException tooLong) {
            throw new GeneratedTooLargeException(maxCells);
        }
        boolean closed = PathSampler.closed(path);
        int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] max = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (int[] cell : kept) {
            for (int k = 0; k < 3; k++) {
                min[k] = Math.min(min[k], cell[k]);
                max[k] = Math.max(max[k], cell[k]);
            }
        }
        // The line's first heading decides which way the profile faces.
        double[] first = heading(samples);
        int sizeX = max[0] - min[0] + 1, sizeZ = max[2] - min[2] + 1;
        boolean alongX = sizeX < sizeZ || (sizeX == sizeZ && StrictMath.abs(first[0]) >= StrictMath.abs(first[2]));
        int sign = (alongX ? first[0] : first[2]) >= 0 ? 1 : -1;
        double[] forward = alongX ? new double[] {sign, 0, 0} : new double[] {0, 0, sign};
        int rightX = (int) -forward[2], rightZ = (int) forward[0];
        int anchorX = (int) Math.floorDiv((long) min[0] + max[0] + 1, 2L);
        int anchorZ = (int) Math.floorDiv((long) min[2] + max[2] + 1, 2L);
        int depth = alongX ? sizeX : sizeZ;
        List<List<int[]>> bySlice = new ArrayList<>(depth);
        for (int k = 0; k < depth; k++) bySlice.add(new ArrayList<>());
        for (int[] cell : kept) {
            int slice = alongX ? (sign > 0 ? cell[0] - min[0] : max[0] - cell[0])
                    : (sign > 0 ? cell[2] - min[2] : max[2] - cell[2]);
            bySlice.get(slice).add(cell);
        }
        SectionSweep.Section[] sections = new SectionSweep.Section[depth];
        for (int k = 0; k < depth; k++) {
            List<int[]> cells = bySlice.get(k);
            double[] a = new double[cells.size()], b = new double[cells.size()];
            int[] cellStates = new int[cells.size()];
            for (int i = 0; i < cells.size(); i++) {
                int[] cell = cells.get(i);
                a[i] = (cell[0] - anchorX) * rightX + (cell[2] - anchorZ) * rightZ;
                b[i] = cell[1] - min[1] + 1;
                cellStates[i] = cell[3];
            }
            sections[k] = new SectionSweep.Section(a, b, cellStates);
        }
        List<PathFrame.Frame> frames = spec.followSlope() ? PathFrame.followSlope(samples, closed, forward)
                : PathFrame.upright(samples, closed, forward);
        double length = samples.get(samples.size() - 1).along();
        double from = -SectionSweep.STEP / 2;
        GeneratedSource source = SectionSweep.sweep(samples, frames, closed, sections,
                along -> (int) Math.floorMod((long) StrictMath.floor(along + 0.5), (long) depth), from,
                length + SectionSweep.STEP / 2, forward, states, null, maxCells, bottomY, topYExclusive);
        return new GeneratedResult(source, leftOut);
    }

    /** The line's first heading (flattened), +x when it has none. */
    private static double[] heading(List<PathSample> samples) {
        double[][] tangents = PathFrame.tangents(samples, false);
        for (double[] t : tangents) {
            double length = StrictMath.sqrt(t[0] * t[0] + t[2] * t[2]);
            if (length > 1e-6) return new double[] {t[0] / length, 0, t[2] / length};
        }
        return new double[] {1, 0, 0};
    }
}
