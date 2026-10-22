package dev.sculptory.core.generate;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.brush.SurfaceScan;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.path.CatmullRom;
import dev.sculptory.core.world.WorldReader;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The Path generator: a road along a centripetal Catmull-Rom spline ({@link CatmullRom}) through the builder's nodes. {@link #path} lays the centre line and the footprint (pure geometry, for the
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
        /**
         * The nodes' heights interpolated along the path, through the air if they are there; the world is read only for
         * fill below (to see where it meets the ground).
         */
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
     * @param fillBelow cells of material written under the surface, 0-8; Straight: only where it meets the ground (see
     *     {@link #deckGround}), so a road through the air is a deck standing alone
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

    /**
     * What {@link #generate} made: the cells, the footprint columns whose ground was unloaded (Follow terrain: nothing
     * written), and how many columns of a Straight road with fill below were unloaded (written as a bare deck).
     */
    public record Result(GeneratedSource source, List<Column> unloaded, int bareColumns) {
        public Result {
            Objects.requireNonNull(source);
            unloaded = List.copyOf(unloaded);
            if (bareColumns < 0) throw new IllegalArgumentException("Negative bare columns");
        }

        public Result(GeneratedSource source, List<Column> unloaded) {
            this(source, unloaded, 0);
        }
    }

    /**
     * The centre line (a polyline sampled from the spline, with arc lengths) and the footprint columns.
     *
     * <p>Everything is worked out relative to the first node (its block is the origin), and only whole blocks are added
     * back: rounding noise in the spline grows with the size of its coordinates, and a road's ties (a deck exactly on a
     * half block, a column centre exactly on an end cap's plane, an even-width centre line on a block boundary) fell
     * one way or the other depending on where in the world the road was. Relative to its first node the same road
     * moved by whole blocks does the very same arithmetic, so it rounds every tie the same way wherever it lies.
     */
    public static final class Path {
        private final List<BlockPos> nodes;
        /** The first node's block (0, 0, 0 without nodes): {@link #xs}, {@link #zs} and heights are relative to it. */
        private final int originX;
        private final int originY;
        private final int originZ;
        private final double[] xs;
        private final double[] zs;
        private final double[] along;
        private final double[] nodeAlong;
        private final int[] nodeVertex;
        private final boolean closed;
        private final List<Column> columns;
        /** Columns nearest a straight segment (world column keys): their exact relative heights and climb axes. */
        private final Long2DoubleOpenHashMap straightHeights;
        private final Long2IntOpenHashMap straightAxes;

        private Path(List<BlockPos> nodes, int originX, int originY, int originZ, double[] xs, double[] zs, double[] along,
                     double[] nodeAlong, int[] nodeVertex, boolean closed, Footprint footprint) {
            this.nodes = nodes;
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
            this.xs = xs;
            this.zs = zs;
            this.along = along;
            this.nodeAlong = nodeAlong;
            this.nodeVertex = nodeVertex;
            this.closed = closed;
            this.columns = footprint.columns();
            this.straightHeights = footprint.straightHeights();
            this.straightAxes = footprint.straightAxes();
        }

        public List<BlockPos> nodes() {
            return nodes;
        }

        /** Polyline vertices. */
        public int vertexCount() {
            return xs.length;
        }

        /** A vertex's x in the world (for drawing; the road's own arithmetic stays relative). */
        public double x(int vertex) {
            return originX + xs[vertex];
        }

        /** A vertex's z in the world (for drawing; the road's own arithmetic stays relative). */
        public double z(int vertex) {
            return originZ + zs[vertex];
        }

        /** The vertex at node {@code node} (the first of its segment; the last node's is the last vertex). */
        public int nodeVertex(int node) {
            return nodeVertex[node];
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
            for (int k = 0; k < samples; k++) keys[k] = sampleColumn(k, length);
            return keys;
        }

        /** The column centre-line sample {@code k} lies in (arc length {@code k}, clamped to {@code length}). */
        private long sampleColumn(int k, double length) {
            double[] point = relativePointAt(Math.min(k, length));
            return GroundMap.column(originX + (int) Math.floor(point[0]), originZ + (int) Math.floor(point[1]));
        }

        /** The nodes' y interpolated along the arc length (clamped past the ends). */
        public double nodeHeightAt(double s) {
            return originY + relativeHeightAt(s);
        }

        /** {@link #nodeHeightAt}, less the first node's y. */
        private double relativeHeightAt(double s) {
            if (nodes.isEmpty()) return 0;
            if (nodes.size() == 1 || s <= nodeAlong[0]) return 0;
            for (int i = 1; i < nodeAlong.length; i++) {
                if (s <= nodeAlong[i]) {
                    double span = nodeAlong[i] - nodeAlong[i - 1];
                    int from = nodes.get(i - 1).y() - originY, to = nodes.get(i).y() - originY;
                    if (span <= 0) return to;
                    // (from (span - u) + to u) / span: exact when the arc lengths are (an axis-aligned straight
                    // segment). A column nearest a straight segment takes its height from its exact projection instead
                    // (deckHeight); this serves curved segments and the drawing.
                    double u = s - nodeAlong[i - 1];
                    return (from * (span - u) + to * u) / span;
                }
            }
            return nodes.get(nodes.size() - 1).y() - originY;
        }

        /** A height relative to the first node, rounded to a block (a half up), as a y in the world. */
        private int roundedHeight(double relative) {
            return originY + (int) Math.floor(relative + 0.5);
        }

        /**
         * Per footprint column, the axis the road runs along there ({@link SlopeSteps#AXIS_X} or {@link SlopeSteps#AXIS_Z}:
         * the larger part of the centre line's direction at the column's arc length), for its stairs and slabs.
         */
        public Long2IntMap climbAxes() {
            Long2IntOpenHashMap axes = new Long2IntOpenHashMap();
            for (Column column : columns) {
                long key = GroundMap.column(column.x(), column.z());
                // A straight segment's axis is its chord's (exact, so a 45-degree road takes the same axis both ways).
                if (straightAxes.containsKey(key)) {
                    axes.put(key, straightAxes.get(key));
                    continue;
                }
                double[] a = relativePointAt(column.along() - 0.5), b = relativePointAt(column.along() + 0.5);
                axes.put(key, SlopeSteps.axisOf(b[0] - a[0], 0, b[1] - a[1]));
            }
            return axes;
        }

        /** The centre-line point at arc length {@code s} (clamped), as {x, z} in the world. */
        public double[] pointAt(double s) {
            double[] point = relativePointAt(s);
            return new double[] {originX + point[0], originZ + point[1]};
        }

        /** {@link #pointAt}, relative to the first node's block. */
        private double[] relativePointAt(double s) {
            if (xs.length == 0) return new double[] {0, 0};
            if (xs.length == 1 || s <= 0) return new double[] {xs[0], zs[0]};
            if (s >= along[xs.length - 1]) return new double[] {xs[xs.length - 1], zs[zs.length - 1]};
            // The first vertex at or past s (a binary search: the arc lengths ascend), then the segment before it.
            int found = Arrays.binarySearch(along, s);
            int i = found >= 0 ? Math.max(1, found) : -found - 1;
            double span = along[i] - along[i - 1];
            if (span <= 0) return new double[] {xs[i], zs[i]};
            double u = s - along[i - 1], v = along[i] - s;
            return new double[] {(xs[i - 1] * v + xs[i] * u) / span, (zs[i - 1] * v + zs[i] * u) / span};
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
        // Relative to the first node's block (Path says why).
        int originX = n == 0 ? 0 : nodes.get(0).x(), originY = n == 0 ? 0 : nodes.get(0).y();
        int originZ = n == 0 ? 0 : nodes.get(0).z();
        double[] px = new double[points], pz = new double[points];
        for (int i = 0; i < points; i++) {
            px[i] = (nodes.get(i).x() - originX) + offset;
            pz[i] = (nodes.get(i).z() - originZ) + offset;
        }
        List<double[]> vertices = new ArrayList<>();
        boolean[] straightSegments = new boolean[Math.max(0, n)];
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
                double[] c0 = {px[i0], pz[i0]}, c1 = {px[i1], pz[i1]}, c2 = {px[i2], pz[i2]}, c3 = {px[i3], pz[i3]};
                // A straight segment's vertices are put on its line exactly (CatmullRom.lerp). They serve the drawing,
                // the arc lengths and Follow terrain; the footprint and the deck measure a straight segment from its
                // nodes directly (footprint), since its arc lengths are not exact off the axes.
                boolean straight = CatmullRom.straight(c0, c1, c2, c3);
                straightSegments[s] = straight;
                for (int j = 0; j < steps; j++) {
                    double[] vertex = new double[2];
                    if (straight) {
                        CatmullRom.lerp(c1, c2, j, steps, vertex);
                    } else {
                        CatmullRom.at(c0, c1, c2, c3, (double) j / steps, vertex);
                    }
                    vertices.add(vertex);
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
        Footprint footprint = n >= 2
                ? footprint(pieces(px, pz, xs, zs, along, nodeVertex, straightSegments, nodes, originY, closed),
                        along[along.length - 1], px, pz, closed, spec.width(), originX, originZ)
                : new Footprint(List.of(), new Long2DoubleOpenHashMap(), new Long2IntOpenHashMap());
        return new Path(nodes, originX, originY, originZ, xs, zs, along, nodeAlong, nodeVertex, closed, footprint);
    }

    private static int control(int index, int points, boolean closed) {
        if (closed) return Math.floorMod(index, points);
        return Math.max(0, Math.min(points - 1, index));
    }

    /**
     * A piece of the centre line the footprint is measured from: one step of a curved segment's polyline, or a whole
     * straight segment ({@link CatmullRom#straight}) with its nodes' heights. Relative to the first node.
     */
    private record Piece(double ax, double az, double bx, double bz, double alongA, double alongB, boolean straight,
                         int fromY, int toY) {}

    /** The footprint, and for the columns nearest a straight segment their exact relative heights and climb axes. */
    private record Footprint(List<Column> columns, Long2DoubleOpenHashMap straightHeights,
                             Long2IntOpenHashMap straightAxes) {}

    /** The pieces of the centre line: a straight segment whole, a curved one step by step. */
    private static List<Piece> pieces(double[] px, double[] pz, double[] xs, double[] zs, double[] along, int[] nodeVertex,
                                      boolean[] straight, List<BlockPos> nodes, int originY, boolean closed) {
        int points = px.length;
        int segments = closed ? points : points - 1;
        List<Piece> pieces = new ArrayList<>();
        for (int s = 0; s < segments; s++) {
            int first = nodeVertex[s], next = nodeVertex[s + 1];
            if (straight[s]) {
                int i2 = Math.floorMod(s + 1, points);
                pieces.add(new Piece(px[s], pz[s], px[i2], pz[i2], along[first], along[next], true,
                        nodes.get(s).y() - originY, nodes.get(s + 1).y() - originY));
            } else {
                for (int i = first; i < next; i++) {
                    pieces.add(new Piece(xs[i], zs[i], xs[i + 1], zs[i + 1], along[i], along[i + 1], false, 0, 0));
                }
            }
        }
        return pieces;
    }

    /**
     * Every column whose centre lies strictly within {@code width / 2} of the centre line, with its nearest point. An
     * open path ends square at its first and last node: a column whose centre lies behind the start (or past the end),
     * along the line's direction there, is not part of the end piece (it may still be near an earlier one).
     *
     * <p>A straight segment is measured exactly: a column centre's projection on it, {@code P = (c - a) . d}, the cross
     * product and {@code |d|^2} are whole multiples of a quarter block, so whether the column is inside, on the border
     * and on which side of a cap it lies are decided exactly, and its height, {@code (from (|d|^2 - P) + to P) / |d|^2},
     * is exact where it is a whole or half block, as is its arc length on an axis-aligned piece. The caps' directions are
     * the first and last chords between nodes: an open spline leaves and reaches its ends along them (an empty end chord
     * leaves that end round).
     */
    private static Footprint footprint(List<Piece> pieces, double total, double[] px, double[] pz, boolean closed,
                                       int width, int originX, int originZ) {
        double radius = width / 2.0;
        double radiusSquared = radius * radius;
        double radius2 = radiusSquared - EPSILON;
        double inner = Math.max(0, radius - 1);
        double borderFrom = radius - 1 - EPSILON;
        Long2DoubleOpenHashMap distance = new Long2DoubleOpenHashMap();
        distance.defaultReturnValue(Double.MAX_VALUE);
        Long2DoubleOpenHashMap nearest = new Long2DoubleOpenHashMap();
        Long2DoubleOpenHashMap straightHeights = new Long2DoubleOpenHashMap();
        Long2IntOpenHashMap straightAxes = new Long2IntOpenHashMap();
        LongOpenHashSet border = new LongOpenHashSet();
        LongArrayList keys = new LongArrayList();
        int reach = (int) Math.ceil(radius) + 1;
        // The end caps of an open path: the planes through the first and last node, square to the first and last
        // chords (the open spline's end tangents). An end whose chord is empty (its two nodes stacked in one column) has
        // no cap: that end stays round, as it always was.
        int last = px.length - 1;
        double startDx = px[Math.min(1, last)] - px[0], startDz = pz[Math.min(1, last)] - pz[0];
        double endDx = px[last] - px[Math.max(0, last - 1)], endDz = pz[last] - pz[Math.max(0, last - 1)];
        // An empty piece (stacked nodes, or a curve's repeated point) only repeats the point of the pieces either side:
        // it would win their exact distance ties and give its columns no direction (no stairs), so it is left out
        // unless the whole road is one point.
        boolean anyLength = false;
        for (Piece piece : pieces) {
            double dx = piece.bx() - piece.ax(), dz = piece.bz() - piece.az();
            if (dx * dx + dz * dz > 0) anyLength = true;
        }
        for (Piece piece : pieces) {
            double ax = piece.ax(), az = piece.az(), bx = piece.bx(), bz = piece.bz();
            double dx = bx - ax, dz = bz - az;
            double length2 = dx * dx + dz * dz;
            if (length2 <= 0 && anyLength) continue;
            boolean nearStart = !closed && piece.alongA() < radius;
            boolean nearEnd = !closed && total - piece.alongB() < radius;
            int minX = (int) Math.floor(Math.min(ax, bx)) - reach, maxX = (int) Math.floor(Math.max(ax, bx)) + reach;
            int minZ = (int) Math.floor(Math.min(az, bz)) - reach, maxZ = (int) Math.floor(Math.max(az, bz)) + reach;
            for (int cz = minZ; cz <= maxZ; cz++) {
                double cpz = cz + 0.5;
                int fromX = minX, toX = maxX;
                if (piece.straight() && length2 > 0) {
                    // Only the band within reach of a long straight piece, not its whole bounding box: a cell within
                    // reach lies within reach (in x) of the piece's points within reach (in z) of its row. A superset
                    // with a block to spare; every cell in it is still classified exactly below.
                    double lo, hi;
                    if (dz == 0) {
                        if (Math.abs(az - cpz) > reach) continue;
                        lo = Math.min(ax, bx);
                        hi = Math.max(ax, bx);
                    } else {
                        double t0 = (cpz - reach - az) / dz, t1 = (cpz + reach - az) / dz;
                        double ta = Math.max(0, Math.min(t0, t1)), tb = Math.min(1, Math.max(t0, t1));
                        if (ta > tb) continue;
                        double xa = ax + ta * dx, xb = ax + tb * dx;
                        lo = Math.min(xa, xb);
                        hi = Math.max(xa, xb);
                    }
                    fromX = Math.max(minX, (int) Math.floor(lo - reach) - 1);
                    toX = Math.min(maxX, (int) Math.floor(hi + reach) + 1);
                }
                for (int cx = fromX; cx <= toX; cx++) {
                    double cpx = cx + 0.5;
                    double ex = cpx - ax, ez = cpz - az;
                    double projection = ex * dx + ez * dz;
                    // A column behind the start (or past the end) is not part of a piece that only reaches it through
                    // the cap's node: the road ends square at its nodes.
                    if (nearStart && projection <= 0 && (cpx - px[0]) * startDx + (cpz - pz[0]) * startDz < 0) continue;
                    if (nearEnd && projection >= length2
                            && (cpx - px[last]) * endDx + (cpz - pz[last]) * endDz > 0) {
                        continue;
                    }
                    double d2;
                    double t;
                    boolean inside;
                    boolean onBorder;
                    if (piece.straight()) {
                        // Exact: every product here is a whole number of sixteenths of a block.
                        if (projection <= 0 || length2 <= 0) {
                            d2 = ex * ex + ez * ez;
                            inside = d2 < radiusSquared;
                            onBorder = d2 >= inner * inner;
                            t = 0;
                        } else if (projection >= length2) {
                            double fx = cpx - bx, fz = cpz - bz;
                            d2 = fx * fx + fz * fz;
                            inside = d2 < radiusSquared;
                            onBorder = d2 >= inner * inner;
                            t = 1;
                        } else {
                            double cross = ex * dz - ez * dx;
                            inside = cross * cross < radiusSquared * length2;
                            onBorder = cross * cross >= inner * inner * length2;
                            d2 = cross * cross / length2;
                            t = projection / length2;
                        }
                    } else {
                        t = length2 <= 0 ? 0 : Math.max(0, Math.min(1, projection / length2));
                        double qx = ax + t * dx - cpx, qz = az + t * dz - cpz;
                        d2 = qx * qx + qz * qz;
                        inside = d2 < radius2;
                        onBorder = Math.sqrt(d2) >= borderFrom;
                    }
                    if (!inside) continue;
                    long key = GroundMap.column(cx, cz);
                    double known = distance.get(key);
                    if (known == Double.MAX_VALUE) keys.add(key);
                    if (d2 < known - EPSILON) {
                        distance.put(key, d2);
                        double span = piece.alongB() - piece.alongA();
                        if (piece.straight() && length2 > 0) {
                            // The arc length as (span P) / |d|^2: exact where it is a whole or half block (an axis-aligned
                            // piece), so Follow terrain's centre heights are read where the column truly is.
                            double clamped = Math.max(0, Math.min(length2, projection));
                            nearest.put(key, piece.alongA() + span * clamped / length2);
                        } else {
                            nearest.put(key, piece.alongA() + t * span);
                        }
                        if (onBorder) {
                            border.add(key);
                        } else {
                            border.remove(key);
                        }
                        long world = GroundMap.column(originX + cx, originZ + cz);
                        if (piece.straight()) {
                            double clamped = Math.max(0, Math.min(length2, projection));
                            straightHeights.put(world, length2 <= 0 ? piece.toY()
                                    : (piece.fromY() * (length2 - clamped) + piece.toY() * clamped) / length2);
                            straightAxes.put(world, SlopeSteps.axisOf(dx, 0, dz));
                        } else {
                            straightHeights.remove(world);
                            straightAxes.remove(world);
                        }
                    }
                }
            }
        }
        // The columns found were relative to the first node; in the world they are ordered by their world keys.
        List<Column> columns = new ArrayList<>(keys.size());
        for (long key : keys) {
            double d = Math.sqrt(distance.get(key));
            columns.add(new Column(originX + GroundMap.columnX(key), originZ + GroundMap.columnZ(key), nearest.get(key), d,
                    border.contains(key)));
        }
        columns.sort((a, b) -> Long.compare(GroundMap.column(a.x(), a.z()), GroundMap.column(b.x(), b.z())));
        return new Footprint(List.copyOf(columns), straightHeights, straightAxes);
    }

    /** {@link #generate(Spec, Path, SurfaceReader, int, int, long)} of {@link #path(Spec)}. */
    public static Result generate(Spec spec, SurfaceReader ground, int bottomY, int topYExclusive, long maxCells) {
        return generate(spec, path(spec), ground, bottomY, topYExclusive, maxCells);
    }

    /**
     * A Straight road's deck height in a footprint column: the nodes' heights interpolated where the column projects on
     * the centre line, rounded to a block (a half up) relative to the first node, so the same anywhere ({@link Path}).
     * Next to a straight segment the height is the exact one of its projection, so a true half rounds up.
     */
    public static int deckHeight(Path path, Column column) {
        long key = GroundMap.column(column.x(), column.z());
        if (path.straightHeights.containsKey(key)) return path.roundedHeight(path.straightHeights.get(key));
        return path.roundedHeight(path.relativeHeightAt(column.along()));
    }

    /**
     * What a Straight road's fill below meets ({@link #generate}'s {@code ground} for that mode): per footprint column, the
     * y of the first cell under the deck that is not open ({@link SurfaceScan#open}: air, replaceable, plants, fluid),
     * looking at most {@code fillBelow + 1} cells down; {@link SurfaceReader#NONE} when there is none within that (or
     * no fill below), {@link SurfaceReader#UNLOADED} for a chunk {@code world} does not hold. Read on the world's thread.
     */
    public static GroundMap deckGround(Spec spec, Path path, WorldReader world) {
        Objects.requireNonNull(spec);
        Objects.requireNonNull(path);
        Objects.requireNonNull(world);
        GroundMap map = new GroundMap();
        if (spec.fillBelow() == 0) return map;
        int bottom = world.bottomY(), top = world.topYExclusive();
        for (Column column : path.columns()) {
            int x = column.x(), z = column.z();
            if (!world.isLoaded(x >> 4, z >> 4)) {
                map.put(x, z, SurfaceReader.UNLOADED);
                continue;
            }
            int deck = deckHeight(path, column);
            int found = SurfaceReader.NONE;
            for (int y = deck - 1; y >= deck - spec.fillBelow() - 1 && y >= bottom; y--) {
                if (y >= top) continue;
                if (!SurfaceScan.open(world.states().flags(world.get(x, y, z)))) {
                    found = y;
                    break;
                }
            }
            map.put(x, z, found);
        }
        return map;
    }

    /**
     * The road's cells: per footprint column the surface at the column's height, {@code fillBelow} cells of material
     * under it and {@code clearAbove} cells of air over it, within {@code [bottomY, topYExclusive)}.
     *
     * <p>Follow terrain reads {@code ground} as each column's surface ({@link SurfaceReader}); an unloaded column is
     * listed in {@link Result#unloaded} and left out. Straight reads it only for its fill below, as {@link #deckGround}
     * gives it: the fill reaches down only where something under the deck lies within its depth, else the deck stands
     * alone (a road through the air); an unloaded column is a bare deck, counted in {@link Result#bareColumns}.
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
        int bare = 0;
        for (Column column : columns) {
            int y;
            int fill = spec.fillBelow();
            if (centre == null) {
                y = deckHeight(path, column);
                if (fill > 0) {
                    int under = ground.ground(column.x(), column.z());
                    if (under == SurfaceReader.UNLOADED) bare++;
                    // Fill below reaches down only where it meets the ground within its depth: else the deck stands alone.
                    if (!SurfaceReader.known(under) || under < y - fill - 1) fill = 0;
                }
            } else {
                int own = ground.ground(column.x(), column.z());
                if (own == SurfaceReader.UNLOADED) {
                    unloaded.add(column);
                    continue;
                }
                y = spec.levelAcross() || own == SurfaceReader.NONE
                        ? path.roundedHeight(sampleAt(centre, column.along()))
                        : own;
            }
            Pattern material = spec.materialFor(column);
            for (int k = -fill; k <= spec.clearAbove(); k++) {
                int yy = y + k;
                if (yy < bottomY || yy >= topYExclusive) continue;
                // The Spec admits only Single and Weighted, which read neither the state space nor the cell.
                int state = k > 0 ? spec.air() : material.apply(null, column.x(), yy, column.z(), -1);
                out.set(column.x(), yy, column.z(), state);
            }
        }
        return new Result(out.build(), unloaded, bare);
    }

    /**
     * The centre line's ground every block of arc length (sample {@code k} at {@code s = k}, the last at the end),
     * unknown samples interpolated from their neighbours, then smoothed with a window {@code width} samples wide. The
     * heights are relative to the first node ({@link Path}), so their averages are the same wherever the road lies.
     */
    private static double[] centreHeights(Spec spec, Path path, SurfaceReader ground) {
        double length = path.length();
        int samples = (int) Math.ceil(length) + 1;
        double[] raw = new double[samples];
        boolean[] known = new boolean[samples];
        for (int k = 0; k < samples; k++) {
            long column = path.sampleColumn(k, length);
            int y = ground.ground(GroundMap.columnX(column), GroundMap.columnZ(column));
            known[k] = SurfaceReader.known(y);
            raw[k] = known[k] ? y - path.originY : 0;
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
            for (int k = 0; k < n; k++) raw[k] = path.relativeHeightAt(Math.min(k, length));
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
