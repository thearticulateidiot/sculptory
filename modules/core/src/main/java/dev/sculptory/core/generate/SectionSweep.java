package dev.sculptory.core.generate;

import dev.sculptory.core.path.PathFrame;
import dev.sculptory.core.path.PathSample;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.transform.Transform;
import java.util.List;
import java.util.Objects;

/**
 * Carries a cross-section of block cells along a line: what Extrude along a path ({@link PathExtrudeKernel}) and
 * Generate's Sweep ({@link SweepKernel}) share. Deterministic: {@code + - * /} and {@code StrictMath} only.
 *
 * <p><b>Cells.</b> Each cell of the cross-section is a point {@code a} blocks right and {@code b} up of the line (its
 * centre). The section is set down every half block along the line, from {@code from} to {@code to} blocks along it
 * (past the ends the line goes on straight); where it turns, each half block is split until no point of the section
 * moves more than half a block between two steps. At every step each cell lays four points, a quarter block
 * either side of its centre right and up, and every world cell a point falls in takes that cell's state (the first to
 * reach a world cell keeps it). A lattice half a block apart leaves no world cell inside the swept body untouched; a
 * section square to an axis, carried straight along that axis, lays exactly its own cells, once per block.
 *
 * <p><b>States</b> turn with the line by the nearest quarter turn: the heading's turn from {@code reference} (the
 * first heading the line has, when none is given), clockwise seen from above. Upright and slope-following frames give
 * the heading themselves (their right is level), so the states turn exactly as the section does; otherwise a section
 * carried straight up or down keeps the turn it had.
 */
final class SectionSweep {
    /** The half block the section is set down every. */
    static final double STEP = 0.5;
    /** The offsets of a cell's four points from its centre. */
    private static final double[][] POINTS = {{-0.25, -0.25}, {0.25, -0.25}, {-0.25, 0.25}, {0.25, 0.25}};
    /** The most pieces a half block is split into where the line turns. */
    private static final int MAX_SPLIT = 1024;
    /** Points laid per cell of the cap at most, before the sweep is refused as too large. */
    private static final long WORK_PER_CELL = 64;
    private static final double EPSILON = 1e-9;

    private SectionSweep() {}

    /** One cross-section: per cell, its centre's offset right ({@code a}) and up ({@code b}) and its state. */
    static final class Section {
        final double[] a;
        final double[] b;
        final int[] states;
        /** Per quarter turn, the states turned (built when first needed). */
        private final int[][] turned = new int[4][];

        Section(double[] a, double[] b, int[] states) {
            if (a.length != b.length || a.length != states.length) throw new IllegalArgumentException("Uneven section");
            this.a = a;
            this.b = b;
            this.states = states;
        }

        int size() {
            return a.length;
        }

        /** The farthest any point of the section lies from the line, with a cell's reach. */
        double reach() {
            double r = 0;
            for (int i = 0; i < a.length; i++) r = StrictMath.max(r, StrictMath.sqrt(a[i] * a[i] + b[i] * b[i]));
            return r + 0.75;
        }

        int[] turned(StateSpace states, int quarterTurns) {
            int[] out = turned[quarterTurns];
            if (out == null) {
                out = new int[this.states.length];
                Transform turn = Transform.rotation(quarterTurns);
                for (int i = 0; i < out.length; i++) {
                    out[i] = quarterTurns == 0 ? this.states[i] : turn.applyToState(states, this.states[i]);
                }
                turned[quarterTurns] = out;
            }
            return out;
        }
    }

    /** Which section is laid at a step {@code along} blocks along the line. */
    @FunctionalInterface
    interface Slices {
        int at(double along);
    }

    /** World cells a section may not write (the face it is pulled from). */
    @FunctionalInterface
    interface Excluded {
        boolean test(int x, int y, int z);
    }

    /**
     * The swept cells.
     *
     * @param samples the line, densely sampled (consecutive samples close enough that interpolating them is the line)
     * @param frames one frame per sample
     * @param closed whether the line is a loop: steps then run from {@code from} for its whole length and wrap
     * @param to the last step on an open line (unused on a loop)
     * @param reference the heading states are turned from (horizontal, unit), or null for the line's first
     * @param levelRight whether every frame's right is level (upright and slope-following frames): the heading the
     *     states turn by is then the frames' own (square to their right), whatever the tangent does as the line climbs
     * @throws GeneratedTooLargeException past {@code maxCells} cells, or when laying the section would take more than
     *     {@value #WORK_PER_CELL} points per cell of the cap
     */
    static GeneratedSource sweep(List<PathSample> samples, List<PathFrame.Frame> frames, boolean closed, Section[] sections,
                                 Slices slices, double from, double to, double[] reference, boolean levelRight,
                                 StateSpace states, Excluded excluded, long maxCells, int bottomY, int topYExclusive) {
        Objects.requireNonNull(states);
        if (samples.size() != frames.size() || samples.isEmpty()) throw new IllegalArgumentException("One frame per sample");
        Track track = new Track(frames, closed);
        double reach = 0;
        long size = 0;
        for (Section section : sections) {
            reach = StrictMath.max(reach, section.reach());
            size = StrictMath.max(size, section.size());
        }
        GeneratedSource.Builder out = GeneratedSource.builder(maxCells);
        long workCap = maxCells > Long.MAX_VALUE / WORK_PER_CELL ? Long.MAX_VALUE : maxCells * WORK_PER_CELL + (1 << 20);
        Laying laying = new Laying(track, sections, slices, reference, levelRight, states, excluded, out, workCap, size,
                bottomY, topYExclusive);
        double limit = closed ? from + track.length() : to;
        double[] a = new double[9], b = new double[9];
        for (long k = 0; ; k++) {
            double base = from + STEP * k;
            if (closed ? base >= limit - EPSILON : base > limit + EPSILON) break;
            laying.lay(base);
            double next = base + STEP;
            if (!closed && next > limit + EPSILON) continue;
            track.at(base, a);
            track.at(next, b);
            int split = split(a, b, reach);
            for (int j = 1; j < split; j++) {
                double along = base + STEP * j / split;
                if (closed && along >= limit - EPSILON) break;
                laying.lay(along);
            }
        }
        return out.build();
    }

    /** Pieces a half block between frames {@code a} and {@code b} is split into so no point moves over half a block. */
    private static int split(double[] a, double[] b, double reach) {
        double turn = StrictMath.max(angle(a, b, 3), angle(a, b, 6));
        double travel = STEP + reach * turn;
        int split = 1;
        while (travel / split > STEP + EPSILON && split < MAX_SPLIT) split *= 2;
        return split;
    }

    private static double angle(double[] a, double[] b, int offset) {
        double dot = a[offset] * b[offset] + a[offset + 1] * b[offset + 1] + a[offset + 2] * b[offset + 2];
        return StrictMath.acos(StrictMath.max(-1, StrictMath.min(1, dot)));
    }

    /** Lays sections, keeping the quarter turn of the last heading seen. */
    private static final class Laying {
        private final Track track;
        private final Section[] sections;
        private final Slices slices;
        private final StateSpace states;
        private final Excluded excluded;
        private final GeneratedSource.Builder out;
        private final long workCap;
        private final long sectionSize;
        private final int bottomY, topYExclusive;
        private final double[] frame = new double[9];
        private double[] reference;
        private final boolean levelRight;
        /** The level heading the turn was last taken from ({x, z}), or null. */
        private double[] last;
        private int quarterTurns;
        private long work;

        Laying(Track track, Section[] sections, Slices slices, double[] reference, boolean levelRight, StateSpace states,
               Excluded excluded, GeneratedSource.Builder out, long workCap, long sectionSize, int bottomY,
               int topYExclusive) {
            this.track = track;
            this.sections = sections;
            this.slices = slices;
            this.reference = reference;
            this.levelRight = levelRight;
            this.last = reference == null ? null : new double[] {reference[0], reference[2]};
            this.states = states;
            this.excluded = excluded;
            this.out = out;
            this.workCap = workCap;
            this.sectionSize = sectionSize;
            this.bottomY = bottomY;
            this.topYExclusive = topYExclusive;
        }

        void lay(double along) {
            work += sectionSize * POINTS.length;
            if (work > workCap) throw new GeneratedTooLargeException(workCap / WORK_PER_CELL);
            track.at(along, frame);
            turn();
            Section section = sections[slices.at(along)];
            int[] turnedStates = section.turned(states, quarterTurns);
            double px = frame[0], py = frame[1], pz = frame[2];
            double ux = frame[6], uy = frame[7], uz = frame[8];
            // v = u × t
            double tx = frame[3], ty = frame[4], tz = frame[5];
            double vx = uy * tz - uz * ty, vy = uz * tx - ux * tz, vz = ux * ty - uy * tx;
            for (int i = 0; i < section.size(); i++) {
                for (double[] point : POINTS) {
                    double a = section.a[i] + point[0], b = section.b[i] + point[1];
                    int x = floor(px + a * ux + b * vx);
                    int y = floor(py + a * uy + b * vy);
                    int z = floor(pz + a * uz + b * vz);
                    if (y < bottomY || y >= topYExclusive) continue;
                    if (out.has(x, y, z) || (excluded != null && excluded.test(x, y, z))) continue;
                    out.set(x, y, z, turnedStates[i]);
                }
            }
        }

        /**
         * The quarter turn of the frame's heading from the reference. With level rights the heading is the frame's own
         * (its right is (-hz, 0, hx)), so the states turn exactly as the section does. Else it is the tangent's level
         * part, kept while the frame heads straight up or down, and while it is {@link PathFrame#STEEP} and heads back
         * the way the turn was taken from (a curve wobbling as it climbs); with no reference given, a steep heading does
         * not set it: the line's first level heading does.
         */
        private void turn() {
            if (levelRight) {
                double hx = frame[8], hz = -frame[6];
                double length = StrictMath.sqrt(hx * hx + hz * hz);
                if (length < 1e-3) return;
                if (reference == null) reference = new double[] {hx / length, 0, hz / length};
                setTurn(hx, hz);
                return;
            }
            double hx = frame[3], hz = frame[5];
            double length = StrictMath.sqrt(hx * hx + hz * hz);
            if (length < 1e-3) return;
            hx /= length;
            hz /= length;
            if (length < PathFrame.STEEP) {
                if (reference == null) return;
                if (last != null && hx * last[0] + hz * last[1] < 0) return;
            }
            last = new double[] {hx, hz};
            if (reference == null) reference = new double[] {hx, 0, hz};
            setTurn(hx, hz);
        }

        private void setTurn(double hx, double hz) {
            double yaw = StrictMath.atan2(hz, hx) - StrictMath.atan2(reference[2], reference[0]);
            quarterTurns = (int) Math.floorMod(StrictMath.round(yaw / (StrictMath.PI / 2)), 4L);
        }
    }

    /** The frames as a line that can be read at any distance along it. */
    static final class Track {
        private final double[] along;
        private final double[][] frames;
        private final boolean closed;

        Track(List<PathFrame.Frame> frames, boolean closed) {
            int n = frames.size();
            this.along = new double[n];
            this.frames = new double[n][];
            this.closed = closed;
            for (int i = 0; i < n; i++) {
                PathFrame.Frame f = frames.get(i);
                along[i] = f.along();
                this.frames[i] = new double[] {f.x(), f.y(), f.z(), f.tx(), f.ty(), f.tz(), f.ux(), f.uy(), f.uz()};
            }
        }

        double length() {
            return along[along.length - 1];
        }

        /**
         * The frame {@code s} blocks along, as {x, y, z, tx, ty, tz, ux, uy, uz}: between samples interpolated (and made
         * square again), before the start and past the end carried on straight, on a loop wrapped around.
         */
        void at(double s, double[] out) {
            int n = along.length;
            double length = length();
            if (closed && length > EPSILON) {
                s = s - StrictMath.floor(s / length) * length;
            }
            if (n == 1 || s <= along[0]) {
                straight(frames[0], s - along[0], out);
                return;
            }
            if (s >= along[n - 1]) {
                straight(frames[n - 1], s - along[n - 1], out);
                return;
            }
            int lo = 0, hi = n - 1;
            while (hi - lo > 1) {
                int mid = (lo + hi) >>> 1;
                if (along[mid] <= s) lo = mid;
                else hi = mid;
            }
            double span = along[hi] - along[lo];
            double w = span <= EPSILON ? 0 : (s - along[lo]) / span;
            double[] p = frames[lo], q = frames[hi];
            for (int i = 0; i < 9; i++) out[i] = p[i] + w * (q[i] - p[i]);
            normalize(out, 3);
            // u square to t again.
            double d = out[3] * out[6] + out[4] * out[7] + out[5] * out[8];
            out[6] -= d * out[3];
            out[7] -= d * out[4];
            out[8] -= d * out[5];
            if (!normalize(out, 6)) {
                out[6] = p[6];
                out[7] = p[7];
                out[8] = p[8];
            }
        }

        private static void straight(double[] frame, double by, double[] out) {
            System.arraycopy(frame, 0, out, 0, 9);
            out[0] += frame[3] * by;
            out[1] += frame[4] * by;
            out[2] += frame[5] * by;
        }

        private static boolean normalize(double[] v, int offset) {
            double length = StrictMath.sqrt(v[offset] * v[offset] + v[offset + 1] * v[offset + 1]
                    + v[offset + 2] * v[offset + 2]);
            if (length < EPSILON) return false;
            v[offset] /= length;
            v[offset + 1] /= length;
            v[offset + 2] /= length;
            return true;
        }
    }

    static int floor(double v) {
        return (int) StrictMath.floor(v);
    }
}
