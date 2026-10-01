package dev.sculptory.core.generate;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.edit.Pattern;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The Path generator: a road along a Catmull-Rom spline through the builder's nodes. {@link #path} lays the centre line and the footprint (pure geometry, for the
 * cursor and the ground snapshot); {@link #generate} gives the footprint its heights from a {@link SurfaceReader} and
 * writes the cells. Both are pure functions of their inputs.
 */
public final class RoadKernel {
    public static final int MIN_WIDTH = 1;
    public static final int MAX_WIDTH = 32;
    public static final int MAX_FILL_BELOW = 8;
    public static final int MAX_CLEAR_ABOVE = 8;
    /** The spline is sampled into a polyline about this far apart (blocks). */
    static final double SAMPLE_STEP = 0.5;
    private static final double EPSILON = 1e-9;

    private RoadKernel() {}

    /** How the road's surface height is found. */
    public enum HeightMode {
        /** Each column's ground from the world, the centre line smoothed along the path. */
        FOLLOW_TERRAIN,
        /** The nodes' heights interpolated along the path; no world is read. */
        STRAIGHT
    }

    /**
     * The road to generate.
     *
     * @param nodes the clicked ground blocks, in order; with four nodes or more, the last equal to the first closes
     *     the loop
     * @param width 1-32 blocks across
     * @param material the surface (and fill) blocks
     * @param border the outermost column on each side, or {@code null} for the material
     * @param levelAcross Follow terrain: one height across the width (else each column hugs its own ground)
     * @param fillBelow cells of material written under the surface, 0-8
     * @param clearAbove cells of air written over the surface, 0-8
     * @param air the air state handle
     */
    public record Spec(List<BlockPos> nodes, int width, Pattern material, Pattern border, HeightMode heightMode,
                       boolean levelAcross, int fillBelow, int clearAbove, int air) {
        public Spec {
            nodes = List.copyOf(nodes);
            Objects.requireNonNull(material);
            Objects.requireNonNull(heightMode);
            // A road's materials are block choices: a pattern that reads the cell's state has no cell to read here.
            if (!(material instanceof Pattern.Single || material instanceof Pattern.Weighted)) {
                throw new IllegalArgumentException("A road material is a block or a mix: " + material);
            }
            if (border != null && !(border instanceof Pattern.Single || border instanceof Pattern.Weighted)) {
                throw new IllegalArgumentException("A road border is a block or a mix: " + border);
            }
            if (width < MIN_WIDTH || width > MAX_WIDTH) throw new IllegalArgumentException("Width " + width);
            if (fillBelow < 0 || fillBelow > MAX_FILL_BELOW) throw new IllegalArgumentException("Fill below " + fillBelow);
            if (clearAbove < 0 || clearAbove > MAX_CLEAR_ABOVE) throw new IllegalArgumentException("Clear above " + clearAbove);
            if (air < 0) throw new IllegalArgumentException("Negative air handle");
        }

        /** The material of a column. */
        Pattern materialFor(Column column) {
            return column.border() && border != null ? border : material;
        }
    }

    /**
     * A footprint column.
     *
     * @param along the arc length of the nearest point of the centre line
     * @param distance the column centre's distance to the centre line
     * @param border whether it is the outermost column on its side
     */
    public record Column(int x, int z, double along, double distance, boolean border) {}

    /** What {@link #generate} made: the cells, and the footprint columns whose ground was unloaded (nothing written). */
    public record Result(GeneratedSource source, List<Column> unloaded) {
        public Result {
            Objects.requireNonNull(source);
            unloaded = List.copyOf(unloaded);
        }
    }

    /** The centre line (a polyline sampled from the spline, with arc lengths) and the footprint columns. */
    public static final class Path {
        private final List<BlockPos> nodes;
        private final double[] xs;
        private final double[] zs;
        private final double[] along;
        private final double[] nodeAlong;
        private final boolean closed;
        private final List<Column> columns;

        private Path(List<BlockPos> nodes, double[] xs, double[] zs, double[] along, double[] nodeAlong, boolean closed,
                     List<Column> columns) {
            this.nodes = nodes;
            this.xs = xs;
            this.zs = zs;
            this.along = along;
            this.nodeAlong = nodeAlong;
            this.closed = closed;
            this.columns = columns;
        }

        public List<BlockPos> nodes() {
            return nodes;
        }

        /** Polyline vertices. */
        public int vertexCount() {
            return xs.length;
        }

        public double x(int vertex) {
            return xs[vertex];
        }

        public double z(int vertex) {
            return zs[vertex];
        }

        /** The arc length at a vertex. */
        public double along(int vertex) {
            return along[vertex];
        }

        /** The whole arc length. */
        public double length() {
            return xs.length == 0 ? 0 : along[xs.length - 1];
        }

        public boolean closed() {
            return closed;
        }

        /** The footprint, ordered by x then z. Empty with fewer than two nodes. */
        public List<Column> columns() {
            return columns;
        }

        /** The footprint's column keys ({@link GroundMap#column}), in {@link #columns()} order. */
        public long[] columnKeys() {
            long[] keys = new long[columns.size()];
            for (int i = 0; i < keys.length; i++) keys[i] = GroundMap.column(columns.get(i).x(), columns.get(i).z());
            return keys;
        }

        /**
         * The columns the centre line is sampled in (every block of arc length, and the end): what Follow terrain reads
         * besides the footprint. A {@link GroundMap} for {@link RoadKernel#generate} captures these and the footprint.
         */
        public long[] sampleColumnKeys() {
            if (columns.isEmpty()) return new long[0];
            double length = length();
            int samples = (int) Math.ceil(length) + 1;
            long[] keys = new long[samples];
            for (int k = 0; k < samples; k++) {
                double[] point = pointAt(Math.min(k, length));
                keys[k] = GroundMap.column((int) Math.floor(point[0]), (int) Math.floor(point[1]));
            }
            return keys;
        }

        /** The nodes' y interpolated along the arc length (clamped past the ends). */
        public double nodeHeightAt(double s) {
            if (nodes.isEmpty()) return 0;
            if (nodes.size() == 1 || s <= nodeAlong[0]) return nodes.get(0).y();
            for (int i = 1; i < nodeAlong.length; i++) {
                if (s <= nodeAlong[i]) {
                    double span = nodeAlong[i] - nodeAlong[i - 1];
                    double t = span <= 0 ? 1 : (s - nodeAlong[i - 1]) / span;
                    return nodes.get(i - 1).y() + t * (nodes.get(i).y() - nodes.get(i - 1).y());
                }
            }
            return nodes.get(nodes.size() - 1).y();
        }

        /** The centre-line point at arc length {@code s} (clamped), as {x, z}. */
        public double[] pointAt(double s) {
            if (xs.length == 0) return new double[] {0, 0};
            if (xs.length == 1 || s <= 0) return new double[] {xs[0], zs[0]};
            if (s >= along[xs.length - 1]) return new double[] {xs[xs.length - 1], zs[zs.length - 1]};
            // The first vertex at or past s (a binary search: the arc lengths ascend), then the segment before it.
            int found = Arrays.binarySearch(along, s);
            int i = found >= 0 ? Math.max(1, found) : -found - 1;
            double span = along[i] - along[i - 1];
            double t = span <= 0 ? 1 : (s - along[i - 1]) / span;
            return new double[] {xs[i - 1] + t * (xs[i] - xs[i - 1]), zs[i - 1] + t * (zs[i] - zs[i - 1])};
        }
    }

    /** The centre line and footprint of {@code spec}. */
    public static Path path(Spec spec) {
        Objects.requireNonNull(spec);
        List<BlockPos> nodes = spec.nodes();
        int n = nodes.size();
        boolean closed = n >= 4 && nodes.get(0).equals(nodes.get(n - 1));
        // Odd widths run through the nodes' centres, even ones through their corners, so the road is symmetric.
        double offset = (spec.width() & 1) == 1 ? 0.5 : 1.0;
        int points = closed ? n - 1 : n;
        double[] px = new double[points], pz = new double[points];
        for (int i = 0; i < points; i++) {
            px[i] = nodes.get(i).x() + offset;
            pz[i] = nodes.get(i).z() + offset;
        }
        List<double[]> vertices = new ArrayList<>();
        double[] nodeAlong = new double[n];
        int[] nodeVertex = new int[n];
        if (n == 1) {
            vertices.add(new double[] {px[0], pz[0]});
        } else if (n >= 2) {
            int segments = closed ? points : points - 1;
            for (int s = 0; s < segments; s++) {
                int i0 = control(s - 1, points, closed), i1 = s, i2 = control(s + 1, points, closed),
                        i3 = control(s + 2, points, closed);
                double chord = Math.hypot(px[i2] - px[i1], pz[i2] - pz[i1]);
                int steps = Math.max(1, (int) Math.ceil(chord / SAMPLE_STEP));
                nodeVertex[s] = vertices.size();
                for (int j = 0; j < steps; j++) {
                    double t = (double) j / steps;
                    vertices.add(new double[] {catmullRom(px[i0], px[i1], px[i2], px[i3], t),
                            catmullRom(pz[i0], pz[i1], pz[i2], pz[i3], t)});
                }
            }
            // The last vertex: the last node (or, closed, the first again).
            nodeVertex[n - 1] = vertices.size();
            vertices.add(new double[] {px[closed ? 0 : points - 1], pz[closed ? 0 : points - 1]});
        }
        double[] xs = new double[vertices.size()], zs = new double[vertices.size()], along = new double[vertices.size()];
        for (int i = 0; i < vertices.size(); i++) {
            xs[i] = vertices.get(i)[0];
            zs[i] = vertices.get(i)[1];
            along[i] = i == 0 ? 0 : along[i - 1] + Math.hypot(xs[i] - xs[i - 1], zs[i] - zs[i - 1]);
        }
        for (int i = 0; i < n; i++) nodeAlong[i] = along.length == 0 ? 0 : along[nodeVertex[i]];
        List<Column> columns = n >= 2 ? footprint(xs, zs, along, spec.width(), closed) : List.of();
        return new Path(nodes, xs, zs, along, nodeAlong, closed, columns);
    }

    private static int control(int index, int points, boolean closed) {
        if (closed) return Math.floorMod(index, points);
        return Math.max(0, Math.min(points - 1, index));
    }

    private static double catmullRom(double p0, double p1, double p2, double p3, double t) {
        double t2 = t * t, t3 = t2 * t;
        return 0.5 * (2 * p1 + (p2 - p0) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (3 * p1 - p0 - 3 * p2 + p3) * t3);
    }

    /**
     * Every column whose centre lies strictly within {@code width / 2} of the polyline, with its nearest point. An open
     * path ends square at its first and last vertex: a column whose centre projects past an end is not part of the
     * end segment (it may still be near an earlier one).
     */
    private static List<Column> footprint(double[] xs, double[] zs, double[] along, int width, boolean closed) {
        double radius = width / 2.0;
        double radius2 = radius * radius - EPSILON;
        double borderFrom = radius - 1 - EPSILON;
        Long2DoubleOpenHashMap distance = new Long2DoubleOpenHashMap();
        distance.defaultReturnValue(Double.MAX_VALUE);
        Long2DoubleOpenHashMap nearest = new Long2DoubleOpenHashMap();
        LongArrayList keys = new LongArrayList();
        int reach = (int) Math.ceil(radius) + 1;
        int last = xs.length - 1;
        // The end caps of an open path: the planes through the first and last vertex across the path's direction.
        double startDx = xs[Math.min(1, last)] - xs[0], startDz = zs[Math.min(1, last)] - zs[0];
        double endDx = xs[last] - xs[Math.max(0, last - 1)], endDz = zs[last] - zs[Math.max(0, last - 1)];
        double total = along[last];
        for (int i = 0; i + 1 < xs.length; i++) {
            double ax = xs[i], az = zs[i];
            double bx = xs[i + 1], bz = zs[i + 1];
            double dx = bx - ax, dz = bz - az;
            double length2 = dx * dx + dz * dz;
            boolean nearStart = !closed && along[i] < radius;
            boolean nearEnd = !closed && total - along[i + 1] < radius;
            int minX = (int) Math.floor(Math.min(ax, bx)) - reach, maxX = (int) Math.floor(Math.max(ax, bx)) + reach;
            int minZ = (int) Math.floor(Math.min(az, bz)) - reach, maxZ = (int) Math.floor(Math.max(az, bz)) + reach;
            for (int cx = minX; cx <= maxX; cx++) {
                for (int cz = minZ; cz <= maxZ; cz++) {
                    double px = cx + 0.5, pz = cz + 0.5;
                    double raw = length2 <= 0 ? 0 : ((px - ax) * dx + (pz - az) * dz) / length2;
                    // A column behind the start (or past the end) is not part of a segment that only reaches it
                    // through the cap's vertex: the road ends square at its nodes.
                    if (nearStart && raw <= 0 && (px - xs[0]) * startDx + (pz - zs[0]) * startDz < 0) continue;
                    if (nearEnd && raw >= 1 && (px - xs[last]) * endDx + (pz - zs[last]) * endDz > 0) continue;
                    double t = Math.max(0, Math.min(1, raw));
                    double qx = ax + t * dx - px, qz = az + t * dz - pz;
                    double d2 = qx * qx + qz * qz;
                    if (d2 >= radius2) continue;
                    long key = GroundMap.column(cx, cz);
                    double known = distance.get(key);
                    if (known == Double.MAX_VALUE) keys.add(key);
                    if (d2 < known - EPSILON) {
                        distance.put(key, d2);
                        nearest.put(key, along[i] + t * Math.sqrt(length2));
                    }
                }
            }
        }
        long[] sorted = keys.toLongArray();
        Arrays.sort(sorted);
        List<Column> columns = new ArrayList<>(sorted.length);
        for (long key : sorted) {
            double d = Math.sqrt(distance.get(key));
            columns.add(new Column(GroundMap.columnX(key), GroundMap.columnZ(key), nearest.get(key), d, d >= borderFrom));
        }
        return List.copyOf(columns);
    }

    /** {@link #generate(Spec, Path, SurfaceReader, int, int, long)} of {@link #path(Spec)}. */
    public static Result generate(Spec spec, SurfaceReader ground, int bottomY, int topYExclusive, long maxCells) {
        return generate(spec, path(spec), ground, bottomY, topYExclusive, maxCells);
    }

    /**
     * The road's cells: per footprint column the surface at the column's height, {@code fillBelow} cells of material
     * under it and {@code clearAbove} cells of air over it, within {@code [bottomY, topYExclusive)}.
     *
     * @throws GeneratedTooLargeException over {@code maxCells}
     */
    public static Result generate(Spec spec, Path path, SurfaceReader ground, int bottomY, int topYExclusive,
                                  long maxCells) {
        Objects.requireNonNull(spec);
        Objects.requireNonNull(path);
        Objects.requireNonNull(ground);
        List<Column> columns = path.columns();
        if (columns.isEmpty()) return new Result(GeneratedSource.empty(), List.of());
        double[] centre = spec.heightMode() == HeightMode.FOLLOW_TERRAIN ? centreHeights(spec, path, ground) : null;
        GeneratedSource.Builder out = GeneratedSource.builder(maxCells);
        List<Column> unloaded = new ArrayList<>();
        for (Column column : columns) {
            int y;
            if (centre == null) {
                y = (int) Math.floor(path.nodeHeightAt(column.along()) + 0.5);
            } else {
                int own = ground.ground(column.x(), column.z());
                if (own == SurfaceReader.UNLOADED) {
                    unloaded.add(column);
                    continue;
                }
                y = spec.levelAcross() || own == SurfaceReader.NONE
                        ? (int) Math.floor(sampleAt(centre, column.along()) + 0.5)
                        : own;
            }
            Pattern material = spec.materialFor(column);
            for (int k = -spec.fillBelow(); k <= spec.clearAbove(); k++) {
                int yy = y + k;
                if (yy < bottomY || yy >= topYExclusive) continue;
                // The Spec admits only Single and Weighted, which read neither the state space nor the cell.
                int state = k > 0 ? spec.air() : material.apply(null, column.x(), yy, column.z(), -1);
                out.set(column.x(), yy, column.z(), state);
            }
        }
        return new Result(out.build(), unloaded);
    }

    /**
     * The centre line's ground every block of arc length (sample {@code k} at {@code s = k}, the last at the end),
     * unknown samples interpolated from their neighbours, then smoothed with a window {@code width} samples wide.
     */
    private static double[] centreHeights(Spec spec, Path path, SurfaceReader ground) {
        double length = path.length();
        int samples = (int) Math.ceil(length) + 1;
        double[] raw = new double[samples];
        boolean[] known = new boolean[samples];
        for (int k = 0; k < samples; k++) {
            double[] point = path.pointAt(Math.min(k, length));
            int y = ground.ground((int) Math.floor(point[0]), (int) Math.floor(point[1]));
            known[k] = SurfaceReader.known(y);
            raw[k] = known[k] ? y : 0;
        }
        fillUnknown(raw, known, path, length);
        int half = Math.max(0, spec.width() / 2);
        double[] smooth = new double[samples];
        for (int k = 0; k < samples; k++) {
            double sum = 0;
            int count = 0;
            for (int j = k - half; j <= k + half; j++) {
                int index = j;
                if (path.closed()) {
                    index = Math.floorMod(j, Math.max(1, samples - 1));
                } else if (j < 0 || j >= samples) {
                    continue;
                }
                sum += raw[index];
                count++;
            }
            smooth[k] = count == 0 ? raw[k] : sum / count;
        }
        return smooth;
    }

    /** Unknown samples take the line between their known neighbours, the nearest known one past the ends. */
    private static void fillUnknown(double[] raw, boolean[] known, Path path, double length) {
        int n = raw.length;
        int firstKnown = -1;
        for (int k = 0; k < n; k++) {
            if (known[k]) {
                firstKnown = k;
                break;
            }
        }
        if (firstKnown < 0) {
            for (int k = 0; k < n; k++) raw[k] = path.nodeHeightAt(Math.min(k, length));
            return;
        }
        int lastKnown = firstKnown;
        for (int k = 0; k < n; k++) {
            if (known[k]) {
                lastKnown = k;
                continue;
            }
            int next = -1;
            for (int j = k + 1; j < n; j++) {
                if (known[j]) {
                    next = j;
                    break;
                }
            }
            if (k < firstKnown) {
                raw[k] = raw[firstKnown];
            } else if (next < 0) {
                raw[k] = raw[lastKnown];
            } else {
                double t = (double) (k - lastKnown) / (next - lastKnown);
                raw[k] = raw[lastKnown] + t * (raw[next] - raw[lastKnown]);
            }
        }
    }

    /** The sampled heights interpolated at arc length {@code s}. */
    private static double sampleAt(double[] samples, double s) {
        if (samples.length == 1) return samples[0];
        double clamped = Math.max(0, Math.min(samples.length - 1, s));
        int k = (int) Math.floor(clamped);
        if (k >= samples.length - 1) return samples[samples.length - 1];
        double t = clamped - k;
        return samples[k] + t * (samples[k + 1] - samples[k]);
    }
}
