package dev.sculptory.core.generate;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.edit.Pattern;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Generate's Smart Surface: a terrain-like sheet through points the builder placed. The outline points, in order, form
 * a closed loop with straight edges (seen from above); every column inside it gets one top surface, which passes
 * through every point, outline and inside alike. The sheet is then filled down as a top layer over a body.
 *
 * <p><b>Points.</b> Each point is the cell the surface passes through (the cell on top of the block clicked). The
 * outline is an ordered loop (the last point joins the first; a repeated last point is dropped, as are consecutive
 * repeats). Inside points only bend the sheet; one that lies outside the outline is ignored and listed
 * ({@link Surface#ignored}). Two points in one column at different heights are refused; at the same height they are
 * one point.
 *
 * <p><b>Columns.</b> A column belongs to the sheet when its centre lies inside the outline or on it, so every outline
 * point's own column is in. An outline that crosses itself fills by the even-odd rule: a column is in when a ray from
 * it crosses the outline an odd number of times (or it lies on the outline).
 *
 * <p><b>Heights.</b> Faceted: flat triangles of the constrained Delaunay triangulation of the points (the outline edges
 * kept as triangle edges, so the sheet runs straight from point to point along the outline; for an outline that
 * crosses itself, the plain Delaunay triangulation of the points' convex hull). Each column takes the plane of the
 * triangle under its centre, rounded half up, exactly (integer arithmetic). Smooth (the default): straight edges,
 * smooth inside. The outline's columns run straight in height from point to point, as Faceted, and the rim
 * follows the edges' triangles; inside, the faceted sheet relaxed (its creases rounded off) blends into the rim,
 * passes through every point and never rises above the highest point or below the lowest, while a peak (or pit) keeps
 * its full height with a rounded top; points that lie on one plane give that plane (fixed-order arithmetic and
 * {@code StrictMath}, so the same points give the same heights everywhere). Both modes expose the triangulation for drawing ({@link Surface#triangles}).
 *
 * <p><b>Fill</b> ({@link #generate}). Each column is filled from its surface down to its bottom: a sheet
 * {@code thickness} cells thick, or with Extend to Ground down to the cell above the column's ground (the
 * shared ground rule, {@link GroundFill#groundBelow} from under the surface cell: looking through plants, leaves and
 * trunks, so a sheet over a forest reaches the ground under it and one under an overhang the ground beneath; a column
 * whose ground is at or above its surface gets its surface cell only, one without ground the sheet). Gap-free
 * reaches every column down to at least one above its lowest neighbour's surface (the four beside it in the sheet),
 * so a steep face shows no holes. The top {@code topDepth} cells are the top
 * pattern, the rest the body. Only blocks are written (never air); Keep Existing (fill air only) and masks are the
 * paste's job. Cells outside the build height are dropped.
 */
public final class SmartSurfaceKernel {
    public static final int MIN_OUTLINE_POINTS = 3;
    /** Outline and inside points together. */
    public static final int MAX_POINTS = 256;
    /** The outline's extent along x and along z, in columns. */
    public static final int MAX_SIDE = 512;
    public static final int MAX_TOP_DEPTH = 64;
    public static final int MIN_THICKNESS = 1;
    public static final int MAX_THICKNESS = 64;

    private SmartSurfaceKernel() {}

    /** Why points make no surface. */
    public enum Problem {
        /** Fewer than {@value #MIN_OUTLINE_POINTS} different outline points. */
        TOO_FEW_POINTS,
        /** More than {@value #MAX_POINTS} points. */
        TOO_MANY_POINTS,
        /** The outline spans more than {@value #MAX_SIDE} columns along x or z. */
        TOO_WIDE,
        /** Two points in one column at different heights. */
        SHARED_COLUMN,
        /** The outline points lie on one line: it encloses nothing. */
        NO_AREA
    }

    /** Points that make no surface, with the reason. */
    public static final class RefusedException extends IllegalArgumentException {
        private final Problem problem;

        public RefusedException(Problem problem, String message) {
            super(message);
            this.problem = Objects.requireNonNull(problem);
        }

        public Problem problem() {
            return problem;
        }
    }

    /**
     * The points and how the sheet runs between them.
     *
     * @param outline the closed loop, in order (world cells the surface passes through)
     * @param inside points that bend the sheet inside the loop
     * @param faceted flat triangles instead of the smooth surface
     */
    public record Shape(List<BlockPos> outline, List<BlockPos> inside, boolean faceted) {
        public Shape {
            outline = List.copyOf(outline);
            inside = List.copyOf(inside);
        }
    }

    /**
     * What the sheet is made of.
     *
     * @param top the top layer's blocks (a block or a mix)
     * @param topDepth how many cells from the surface down are the top layer, 0-{@value #MAX_TOP_DEPTH}
     * @param body the blocks under the top layer
     * @param extendToGround fill each column down to the terrain (else a sheet {@code thickness} thick)
     * @param thickness the sheet's cells per column, {@value #MIN_THICKNESS}-{@value #MAX_THICKNESS} (also for a
     *     column without ground under Extend to Ground)
     * @param gapFree fill each column down to at least one above its lowest neighbour's surface
     */
    public record Fill(Pattern top, int topDepth, Pattern body, boolean extendToGround, int thickness, boolean gapFree) {
        public Fill {
            checkPattern(top, "top");
            checkPattern(body, "body");
            if (topDepth < 0 || topDepth > MAX_TOP_DEPTH) {
                throw new IllegalArgumentException("Top depth must be 0-" + MAX_TOP_DEPTH);
            }
            if (thickness < MIN_THICKNESS || thickness > MAX_THICKNESS) {
                throw new IllegalArgumentException("Thickness must be " + MIN_THICKNESS + "-" + MAX_THICKNESS);
            }
        }

        /** One block or mix throughout: a top layer one deep, a sheet one thick, gap-free, not to the ground. */
        public static Fill of(Pattern block) {
            return new Fill(block, 1, block, false, 1, true);
        }

        private static void checkPattern(Pattern pattern, String what) {
            Objects.requireNonNull(pattern);
            // A block choice: a pattern reading the cell's state has no cell to read here, nor a steepness to measure.
            boolean ok = pattern instanceof Pattern.Single || pattern instanceof Pattern.Weighted
                    || pattern instanceof Pattern.Arranged arranged && !arranged.steepness();
            if (!ok) throw new IllegalArgumentException("A Smart Surface " + what + " is a block or a mix: " + pattern);
        }
    }

    /**
     * The sheet's surface: which columns it covers and the surface y of each, the points it passes through and their
     * triangulation. Immutable.
     */
    public static final class Surface {
        private static final int OUTSIDE = Integer.MIN_VALUE;

        private final int minX;
        private final int minZ;
        private final int sizeX;
        private final int sizeZ;
        /** Surface y per column of the bounds, row by row from the north; {@link #OUTSIDE} for a column not covered. */
        private final int[] heights;
        private final int columnCount;
        private final List<BlockPos> sites;
        private final int[] triangles;
        private final List<BlockPos> ignored;
        private final boolean simpleOutline;
        private final boolean faceted;

        private Surface(int minX, int minZ, int sizeX, int sizeZ, int[] heights, int columnCount, List<BlockPos> sites,
                        int[] triangles, List<BlockPos> ignored, boolean simpleOutline, boolean faceted) {
            this.minX = minX;
            this.minZ = minZ;
            this.sizeX = sizeX;
            this.sizeZ = sizeZ;
            this.heights = heights;
            this.columnCount = columnCount;
            this.sites = List.copyOf(sites);
            this.triangles = triangles;
            this.ignored = List.copyOf(ignored);
            this.simpleOutline = simpleOutline;
            this.faceted = faceted;
        }

        /** The westmost column x of the outline's bounds. */
        public int minX() {
            return minX;
        }

        /** The northmost column z of the outline's bounds. */
        public int minZ() {
            return minZ;
        }

        public int sizeX() {
            return sizeX;
        }

        public int sizeZ() {
            return sizeZ;
        }

        /** Whether column (x, z) is covered by the sheet. */
        public boolean covers(int x, int z) {
            return surfaceAt(x, z) != OUTSIDE;
        }

        /**
         * The surface y of column (x, z).
         *
         * @throws IllegalArgumentException for a column the sheet does not cover
         */
        public int surfaceY(int x, int z) {
            int y = surfaceAt(x, z);
            if (y == OUTSIDE) throw new IllegalArgumentException("Column " + x + ", " + z + " is outside the sheet");
            return y;
        }

        private int surfaceAt(int x, int z) {
            int lx = x - minX, lz = z - minZ;
            if (lx < 0 || lz < 0 || lx >= sizeX || lz >= sizeZ) return OUTSIDE;
            return heights[lz * sizeX + lx];
        }

        /** How many columns the sheet covers. */
        public int columnCount() {
            return columnCount;
        }

        /** The covered columns as {@link GroundMap#column} keys, row by row from the north, west to east. */
        public long[] columns() {
            long[] out = new long[columnCount];
            int k = 0;
            for (int lz = 0; lz < sizeZ; lz++) {
                for (int lx = 0; lx < sizeX; lx++) {
                    if (heights[lz * sizeX + lx] != OUTSIDE) out[k++] = GroundMap.column(minX + lx, minZ + lz);
                }
            }
            return out;
        }

        /**
         * Each covered column's surface y ({@link GroundMap#column} keys): where Extend to Ground looks down from
         * ({@link GroundFill#groundUnder}, from under the surface cell).
         */
        public Long2IntOpenHashMap tops() {
            Long2IntOpenHashMap tops = new Long2IntOpenHashMap(
                    columnCount);
            for (int lz = 0; lz < sizeZ; lz++) {
                for (int lx = 0; lx < sizeX; lx++) {
                    int y = heights[lz * sizeX + lx];
                    if (y != OUTSIDE) tops.put(GroundMap.column(minX + lx, minZ + lz), y);
                }
            }
            return tops;
        }

        /** The points the surface passes through: the outline's (in loop order), then the inside points used. */
        public List<BlockPos> sites() {
            return sites;
        }

        /**
         * The triangulation of {@link #sites} seen from above, three site indices per triangle, wound the same way
         * (clockwise seen from above with north up): the faceted sheet's facets, the smooth sheet's underlying mesh.
         * Copied.
         */
        public int[] triangles() {
            return triangles.clone();
        }

        /** Inside points that lie outside the outline, which the sheet does not use. */
        public List<BlockPos> ignored() {
            return ignored;
        }

        /** Whether the outline does not cross or touch itself (else it fills by the even-odd rule). */
        public boolean simpleOutline() {
            return simpleOutline;
        }

        public boolean faceted() {
            return faceted;
        }
    }

    /**
     * What {@link #generate} made: the cells, and the columns (as {@link GroundMap#column} keys) left out because
     * their ground was {@link SurfaceReader#UNLOADED} (Extend to Ground only).
     */
    public record Result(GeneratedSource source, long[] unloaded) {
        public Result {
            Objects.requireNonNull(source);
            unloaded = unloaded.clone();
        }

        @Override
        public long[] unloaded() {
            return unloaded.clone();
        }
    }

    // ------------------------------------------------------------------------------------------------- surface

    /**
     * Works out the sheet's surface.
     *
     * @throws RefusedException for points that make no surface
     */
    public static Surface surface(Shape shape) {
        Objects.requireNonNull(shape);
        if (shape.outline().size() + shape.inside().size() > MAX_POINTS) {
            throw new RefusedException(Problem.TOO_MANY_POINTS, "At most " + MAX_POINTS + " points");
        }
        List<BlockPos> outline = loop(shape.outline());
        if (outline.size() < MIN_OUTLINE_POINTS) {
            throw new RefusedException(Problem.TOO_FEW_POINTS, "The outline needs at least "
                    + MIN_OUTLINE_POINTS + " points");
        }
        // One height per column across every point.
        Map<Long, Integer> heightOf = new HashMap<>();
        for (List<BlockPos> list : List.of(outline, shape.inside())) {
            for (BlockPos p : list) {
                Integer before = heightOf.putIfAbsent(GroundMap.column(p.x(), p.z()), p.y());
                if (before != null && before != p.y()) {
                    throw new RefusedException(Problem.SHARED_COLUMN, "Two points stand in column " + p.x() + ", "
                            + p.z() + " at different heights");
                }
            }
        }
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : outline) {
            minX = Math.min(minX, p.x());
            maxX = Math.max(maxX, p.x());
            minZ = Math.min(minZ, p.z());
            maxZ = Math.max(maxZ, p.z());
        }
        if ((long) maxX - minX + 1 > MAX_SIDE || (long) maxZ - minZ + 1 > MAX_SIDE) {
            throw new RefusedException(Problem.TOO_WIDE, "The outline spans more than " + MAX_SIDE + " blocks");
        }
        int sizeX = maxX - minX + 1, sizeZ = maxZ - minZ + 1;
        int n = outline.size();
        long[] ox = new long[n], oz = new long[n];
        for (int i = 0; i < n; i++) {
            ox[i] = outline.get(i).x() - minX;
            oz[i] = outline.get(i).z() - minZ;
        }
        if (collinear(ox, oz)) {
            throw new RefusedException(Problem.NO_AREA, "The outline points lie on one line");
        }
        boolean simple = isSimple(ox, oz);
        boolean[] in = footprint(ox, oz, sizeX, sizeZ);

        // Sites: the outline's distinct columns in loop order, then the inside points the sheet covers.
        List<BlockPos> sites = new ArrayList<>();
        Map<Long, Integer> siteOf = new HashMap<>();
        int[] polygon = new int[n];
        for (int i = 0; i < n; i++) {
            BlockPos p = outline.get(i);
            Integer index = siteOf.get(GroundMap.column(p.x(), p.z()));
            if (index == null) {
                index = sites.size();
                siteOf.put(GroundMap.column(p.x(), p.z()), index);
                sites.add(p);
            }
            polygon[i] = index;
        }
        int outlineSites = sites.size();
        List<BlockPos> ignored = new ArrayList<>();
        for (BlockPos p : shape.inside()) {
            long column = GroundMap.column(p.x(), p.z());
            if (siteOf.containsKey(column)) continue;
            int lx = p.x() - minX, lz = p.z() - minZ;
            boolean covered = lx >= 0 && lz >= 0 && lx < sizeX && lz < sizeZ && in[lz * sizeX + lx];
            if (!covered) {
                ignored.add(p);
                continue;
            }
            siteOf.put(column, sites.size());
            sites.add(p);
        }
        int m = sites.size();
        long[] sx = new long[m], sz = new long[m];
        int[] sy = new int[m];
        for (int i = 0; i < m; i++) {
            sx[i] = sites.get(i).x() - minX;
            sz[i] = sites.get(i).z() - minZ;
            sy[i] = sites.get(i).y();
        }

        int[] triangles = triangulate(sx, sz, polygon, outlineSites, simple);
        int[] heights = new int[sizeX * sizeZ];
        Arrays.fill(heights, Surface.OUTSIDE);
        int count = 0;
        for (boolean b : in) if (b) count++;
        if (shape.faceted()) {
            facetHeights(sx, sz, sy, triangles, in, sizeX, sizeZ, heights);
        } else {
            smoothHeights(sx, sz, sy, polygon, outlineSites, triangles, in, sizeX, sizeZ, heights);
        }
        return new Surface(minX, minZ, sizeX, sizeZ, heights, count, sites, triangles, ignored, simple,
                shape.faceted());
    }

    /**
     * The columns a sheet over {@code outline} covers, as {@link GroundMap#column} keys row by row (what
     * {@link Surface#columns} gives, worked out without the heights: cheap, so a host can capture the ground of exactly
     * these columns first). Empty for an outline {@link #surface} would refuse for its outline alone (too few points,
     * too wide, on one line).
     */
    public static long[] footprintColumns(List<BlockPos> outline) {
        List<BlockPos> loop = loop(outline);
        if (loop.size() < MIN_OUTLINE_POINTS || loop.size() > MAX_POINTS) return new long[0];
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : loop) {
            minX = Math.min(minX, p.x());
            maxX = Math.max(maxX, p.x());
            minZ = Math.min(minZ, p.z());
            maxZ = Math.max(maxZ, p.z());
        }
        if ((long) maxX - minX + 1 > MAX_SIDE || (long) maxZ - minZ + 1 > MAX_SIDE) return new long[0];
        int sizeX = maxX - minX + 1, sizeZ = maxZ - minZ + 1, n = loop.size();
        long[] xs = new long[n], zs = new long[n];
        for (int i = 0; i < n; i++) {
            xs[i] = loop.get(i).x() - minX;
            zs[i] = loop.get(i).z() - minZ;
        }
        if (collinear(xs, zs)) return new long[0];
        boolean[] in = footprint(xs, zs, sizeX, sizeZ);
        int count = 0;
        for (boolean b : in) if (b) count++;
        long[] out = new long[count];
        int k = 0;
        for (int lz = 0; lz < sizeZ; lz++) {
            for (int lx = 0; lx < sizeX; lx++) {
                if (in[lz * sizeX + lx]) out[k++] = GroundMap.column(minX + lx, minZ + lz);
            }
        }
        return out;
    }

    /** The loop without consecutive repeats (the last point repeating the first included). */
    private static List<BlockPos> loop(List<BlockPos> points) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos p : points) {
            Objects.requireNonNull(p);
            if (out.isEmpty() || !out.get(out.size() - 1).equals(p)) out.add(p);
        }
        while (out.size() > 1 && out.get(0).equals(out.get(out.size() - 1))) out.remove(out.size() - 1);
        return out;
    }

    private static boolean collinear(long[] xs, long[] zs) {
        for (int i = 1; i < xs.length; i++) {
            for (int j = i + 1; j < xs.length; j++) {
                if (PlaneTriangulation.orient(xs[0], zs[0], xs[i], zs[i], xs[j], zs[j]) != 0) return false;
            }
        }
        return true;
    }

    /**
     * Whether the closed polygon neither crosses nor touches itself: no two edges meet except neighbours at their
     * shared corner (and those do not fold back over each other), and no column is visited twice.
     */
    static boolean isSimple(long[] xs, long[] zs) {
        int n = xs.length;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (xs[i] == xs[j] && zs[i] == zs[j]) return false;
            }
        }
        for (int i = 0; i < n; i++) {
            int a = i, b = (i + 1) % n, c = (i + 2) % n;
            // Neighbouring edges folding back along each other.
            if (PlaneTriangulation.orient(xs[a], zs[a], xs[b], zs[b], xs[c], zs[c]) == 0
                    && (xs[a] - xs[b]) * (xs[c] - xs[b]) + (zs[a] - zs[b]) * (zs[c] - zs[b]) > 0) {
                return false;
            }
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 2; j < n; j++) {
                if (i == 0 && j == n - 1) continue;
                int i2 = (i + 1) % n, j2 = (j + 1) % n;
                if (segmentsMeet(xs[i], zs[i], xs[i2], zs[i2], xs[j], zs[j], xs[j2], zs[j2])) return false;
            }
        }
        return true;
    }

    /** Whether closed segments ab and cd share a point. */
    private static boolean segmentsMeet(long ax, long az, long bx, long bz, long cx, long cz, long dx, long dz) {
        long d1 = PlaneTriangulation.orient(cx, cz, dx, dz, ax, az);
        long d2 = PlaneTriangulation.orient(cx, cz, dx, dz, bx, bz);
        long d3 = PlaneTriangulation.orient(ax, az, bx, bz, cx, cz);
        long d4 = PlaneTriangulation.orient(ax, az, bx, bz, dx, dz);
        if (Long.signum(d1) * Long.signum(d2) < 0 && Long.signum(d3) * Long.signum(d4) < 0) return true;
        return d1 == 0 && onSegment(cx, cz, dx, dz, ax, az)
                || d2 == 0 && onSegment(cx, cz, dx, dz, bx, bz)
                || d3 == 0 && onSegment(ax, az, bx, bz, cx, cz)
                || d4 == 0 && onSegment(ax, az, bx, bz, dx, dz);
    }

    /** Whether p, on the line through ab, lies within the segment's box. */
    private static boolean onSegment(long ax, long az, long bx, long bz, long px, long pz) {
        return Math.min(ax, bx) <= px && px <= Math.max(ax, bx) && Math.min(az, bz) <= pz && pz <= Math.max(az, bz);
    }

    /**
     * The columns of the bounds the closed polygon covers (local coordinates, row by row): even-odd inside, plus every
     * column on an edge. Exact: crossings are compared as fractions.
     */
    static boolean[] footprint(long[] xs, long[] zs, int sizeX, int sizeZ) {
        int n = xs.length;
        boolean[] in = new boolean[sizeX * sizeZ];
        long[] num = new long[n], den = new long[n];
        Integer[] order = new Integer[n];
        for (int z = 0; z < sizeZ; z++) {
            int count = 0;
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                long z1 = zs[i], z2 = zs[j];
                if (z1 == z2 || z < Math.min(z1, z2) || z >= Math.max(z1, z2)) continue;
                // x = x1 + (z - z1)(x2 - x1) / (z2 - z1), as num / den with den > 0.
                long d = z2 - z1, nu = xs[i] * d + (z - z1) * (xs[j] - xs[i]);
                if (d < 0) {
                    d = -d;
                    nu = -nu;
                }
                num[count] = nu;
                den[count] = d;
                order[count] = count;
                count++;
            }
            final long[] fn = num, fd = den;
            Arrays.sort(order, 0, count, (p, q) -> Long.compare(fn[p] * fd[q], fn[q] * fd[p]));
            for (int k = 0; k + 1 < count; k += 2) {
                int a = order[k], b = order[k + 1];
                long from = Math.max(0, Math.ceilDiv(num[a], den[a]));
                long to = Math.min(sizeX - 1L, Math.floorDiv(num[b], den[b]));
                for (long x = from; x <= to; x++) in[z * sizeX + (int) x] = true;
            }
        }
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            long dx = xs[j] - xs[i], dz = zs[j] - zs[i];
            long g = gcd(Math.abs(dx), Math.abs(dz));
            if (g == 0) {
                in[(int) (zs[i] * sizeX + xs[i])] = true;
                continue;
            }
            for (long k = 0; k <= g; k++) {
                long x = xs[i] + dx / g * k, z = zs[i] + dz / g * k;
                in[(int) (z * sizeX + x)] = true;
            }
        }
        return in;
    }

    private static long gcd(long a, long b) {
        while (b != 0) {
            long t = a % b;
            a = b;
            b = t;
        }
        return a;
    }

    /**
     * The constrained Delaunay triangulation of a simple outline with the inside sites added, or the Delaunay
     * triangulation of all sites' hull when the outline is not simple.
     */
    private static int[] triangulate(long[] xs, long[] zs, int[] polygon, int outlineSites, boolean simple) {
        PlaneTriangulation mesh = new PlaneTriangulation(xs, zs);
        int m = xs.length;
        boolean placed = false;
        if (simple) {
            int[] ring = polygon.clone();
            long area = 0;
            for (int i = 0; i < ring.length; i++) {
                int a = ring[i], b = ring[(i + 1) % ring.length];
                area += xs[a] * zs[b] - xs[b] * zs[a];
            }
            if (area < 0) {
                for (int i = 0, j = ring.length - 1; i < j; i++, j--) {
                    int t = ring[i];
                    ring[i] = ring[j];
                    ring[j] = t;
                }
            }
            try {
                mesh.earClip(ring);
            } catch (IllegalStateException noEar) {
                // Straight corners (on the line through their neighbours) can leave no strict ear: cut the outline
                // without them, then put them back on their edges, which stay held. A simple outline always has ears
                // then, so a second failure is a bug, not a fallback.
                int[] corners = withoutStraightCorners(xs, zs, ring);
                mesh = new PlaneTriangulation(xs, zs);
                mesh.earClip(corners);
                for (int v : ring) {
                    if (Arrays.stream(corners).noneMatch(c -> c == v) && !mesh.insert(v)) {
                        throw new IllegalStateException("A straight corner off its edge");
                    }
                }
            }
            placed = true;
        }
        boolean[] in = new boolean[m];
        if (placed) {
            for (int i = 0; i < outlineSites; i++) in[i] = true;
        } else {
            int[] all = new int[m];
            for (int i = 0; i < m; i++) all[i] = i;
            int[] hull = PlaneTriangulation.convexHull(xs, zs, all);
            mesh.fan(hull);
            for (int h : hull) in[h] = true;
        }
        for (int i = 0; i < m; i++) {
            if (in[i]) continue;
            if (!mesh.insert(i)) throw new IllegalStateException("A point outside the triangulation");
        }
        mesh.legalize();
        return mesh.triangles();
    }

    /**
     * Each covered column's height on the triangle under its centre (first triangle wins), as an exact fraction:
     * {@code num[i] / den[i]}, {@code den[i] > 0}.
     */
    private static void facetFractions(long[] xs, long[] zs, int[] ys, int[] triangles, boolean[] in, int sizeX,
                                       int sizeZ, long[] num, long[] den) {
        for (int t = 0; t < triangles.length; t += 3) {
            int a = triangles[t], b = triangles[t + 1], c = triangles[t + 2];
            long area = PlaneTriangulation.orient(xs[a], zs[a], xs[b], zs[b], xs[c], zs[c]);
            long x0 = Math.max(0, Math.min(xs[a], Math.min(xs[b], xs[c])));
            long x1 = Math.min(sizeX - 1L, Math.max(xs[a], Math.max(xs[b], xs[c])));
            long z0 = Math.max(0, Math.min(zs[a], Math.min(zs[b], zs[c])));
            long z1 = Math.min(sizeZ - 1L, Math.max(zs[a], Math.max(zs[b], zs[c])));
            for (long z = z0; z <= z1; z++) {
                for (long x = x0; x <= x1; x++) {
                    int i = (int) (z * sizeX + x);
                    if (!in[i] || den[i] != 0) continue;
                    long wa = PlaneTriangulation.orient(xs[b], zs[b], xs[c], zs[c], x, z);
                    long wb = PlaneTriangulation.orient(xs[c], zs[c], xs[a], zs[a], x, z);
                    long wc = PlaneTriangulation.orient(xs[a], zs[a], xs[b], zs[b], x, z);
                    if (wa < 0 || wb < 0 || wc < 0) continue;
                    num[i] = wa * ys[a] + wb * ys[b] + wc * ys[c];
                    den[i] = area;
                }
            }
        }
        for (int i = 0; i < in.length; i++) {
            if (in[i] && den[i] == 0) throw new IllegalStateException("A column under no triangle");
        }
    }

    /** {@code ring} without its straight corners (each on the line through its neighbours), keeping three at least. */
    static int[] withoutStraightCorners(long[] xs, long[] zs, int[] ring) {
        List<Integer> left = new ArrayList<>();
        for (int v : ring) left.add(v);
        boolean removed = true;
        while (removed && left.size() > 3) {
            removed = false;
            for (int i = 0; i < left.size() && left.size() > 3; i++) {
                int a = left.get((i + left.size() - 1) % left.size()), b = left.get(i), c = left.get((i + 1) % left.size());
                if (PlaneTriangulation.orient(xs[a], zs[a], xs[b], zs[b], xs[c], zs[c]) == 0) {
                    left.remove(i);
                    removed = true;
                    i--;
                }
            }
        }
        return left.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Faceted: each covered column takes its triangle's plane at its centre, rounded half up exactly. */
    private static void facetHeights(long[] xs, long[] zs, int[] ys, int[] triangles, boolean[] in, int sizeX,
                                     int sizeZ, int[] heights) {
        long[] num = new long[in.length], den = new long[in.length];
        facetFractions(xs, zs, ys, triangles, in, sizeX, sizeZ, num, den);
        for (int i = 0; i < in.length; i++) {
            if (in[i]) heights[i] = (int) Math.floorDiv(2 * num[i] + den[i], 2 * den[i]);
        }
    }

    /** Where an inside point's own plane weighs as much as the relaxed sheet (blocks from the point). */
    private static final double POINT_REACH = 3;
    /**
     * How far an inside point's plane reaches at all (blocks): past it the point adds nothing, so however many points
     * there are, each counts only near itself. Points also fade out over this distance from the sheet's rim, so the rim
     * meets the relaxed sheet with no step.
     */
    private static final double POINT_RANGE = 2 * POINT_REACH;

    /**
     * Smooth: straight edges, smooth inside.
     *
     * <ul>
     *   <li>Held columns: those on the outline take its straight profile between its two points (where a crossing
     *       outline meets itself, the first edge in loop order); the others with a neighbour outside the sheet keep
     *       the faceted height (so the sheet's rim follows the edges' triangles); inside points' columns take their
     *       own y. All rounded half up exactly.</li>
     *   <li>The rest starts from the faceted heights and is relaxed (each column repeatedly the mean of its four
     *       neighbours, the held ones fixed) about as many times as the outline is wide: creases round off, a plane
     *       stays a plane, and nothing rises above the highest held value or below the lowest.</li>
     *   <li>Near each inside point its own plane blends in (weight {@code 1 / d²} against {@code 1 / }{@value
     *       #POINT_REACH}{@code ²} for the relaxed sheet, less {@code 1 / }{@value #POINT_RANGE}{@code ²} so it ends
     *       there, and faded out over the same distance toward the rim): the plane through the point with the mean slope of its
     *       triangles, or level when the point is a peak or a pit (at or above, or at or below, all its mesh
     *       neighbours), kept within the points' range. So a peak keeps its full height with a rounded top.</li>
     * </ul>
     */
    private static void smoothHeights(long[] xs, long[] zs, int[] ys, int[] polygon, int outlineSites,
                                      int[] triangles, boolean[] in, int sizeX, int sizeZ, int[] heights) {
        int m = xs.length, size = in.length;
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int y : ys) {
            lo = Math.min(lo, y);
            hi = Math.max(hi, y);
        }
        long[] num = new long[size], den = new long[size];
        facetFractions(xs, zs, ys, triangles, in, sizeX, sizeZ, num, den);
        boolean[] held = new boolean[size];
        outlineProfile(xs, zs, ys, polygon, sizeX, num, den, held);
        boolean[] edge = held.clone();
        // Every point's column its own y: an outline that crosses itself may run another edge through a point.
        for (int s = 0; s < m; s++) {
            int i = (int) (zs[s] * sizeX + xs[s]);
            num[i] = ys[s];
            den[i] = 1;
            held[i] = true;
        }
        double[] value = new double[size];
        int free = 0;
        for (int z = 0; z < sizeZ; z++) {
            for (int x = 0; x < sizeX; x++) {
                int i = z * sizeX + x;
                if (!in[i]) continue;
                value[i] = (double) num[i] / den[i];
                boolean rim = x == 0 || z == 0 || x == sizeX - 1 || z == sizeZ - 1
                        || !in[i - 1] || !in[i + 1] || !in[i - sizeX] || !in[i + sizeX];
                if (rim) edge[i] = true;
                if (rim || held[i]) {
                    held[i] = true;
                    heights[i] = (int) Math.floorDiv(2 * num[i] + den[i], 2 * den[i]);
                } else {
                    free++;
                }
            }
        }
        if (free == 0) return;
        int[] cells = new int[free];
        int f = 0;
        for (int i = 0; i < size; i++) if (in[i] && !held[i]) cells[f++] = i;
        // Relax: Jacobi sweeps, every free column from the previous sweep's values.
        int sweeps = Math.max(16, Math.max(sizeX, sizeZ));
        double[] next = value.clone();
        for (int sweep = 0; sweep < sweeps; sweep++) {
            for (int i : cells) next[i] = 0.25 * (value[i - 1] + value[i + 1] + value[i - sizeX] + value[i + sizeX]);
            double[] t = value;
            value = next;
            next = t;
        }
        // Inside points' planes: slope from their triangles, level at a peak or a pit.
        int k = m - outlineSites;
        double[] sumX = new double[m], sumZ = new double[m], sumArea = new double[m];
        int[] nLo = ys.clone(), nHi = ys.clone();
        for (int t = 0; t < triangles.length; t += 3) {
            int a = triangles[t], b = triangles[t + 1], c = triangles[t + 2];
            double area = PlaneTriangulation.orient(xs[a], zs[a], xs[b], zs[b], xs[c], zs[c]);
            double dyb = ys[b] - ys[a], dyc = ys[c] - ys[a];
            double gx = (dyb * (zs[c] - zs[a]) - dyc * (zs[b] - zs[a])) / area;
            double gz = ((xs[b] - xs[a]) * dyc - (xs[c] - xs[a]) * dyb) / area;
            for (int v : new int[] {a, b, c}) {
                sumX[v] += gx * area;
                sumZ[v] += gz * area;
                sumArea[v] += area;
                nLo[v] = Math.min(nLo[v], Math.min(ys[a], Math.min(ys[b], ys[c])));
                nHi[v] = Math.max(nHi[v], Math.max(ys[a], Math.max(ys[b], ys[c])));
            }
        }
        double[] px = new double[k], pz = new double[k], py = new double[k], pgx = new double[k], pgz = new double[k];
        for (int j = 0; j < k; j++) {
            int s = outlineSites + j;
            px[j] = xs[s];
            pz[j] = zs[s];
            py[j] = ys[s];
            boolean extreme = ys[s] >= nHi[s] || ys[s] <= nLo[s];
            if (!extreme && sumArea[s] > 0) {
                pgx[j] = sumX[s] / sumArea[s];
                pgz[j] = sumZ[s] / sumArea[s];
            }
        }
        double sheetWeight = 1 / (POINT_REACH * POINT_REACH), cutoff = 1 / (POINT_RANGE * POINT_RANGE);
        int[] rimDistance = rimDistance(edge, in, sizeX, sizeZ);
        for (int i : cells) {
            int x = i % sizeX, z = i / sizeX;
            double t = Math.min(1, rimDistance[i] / POINT_RANGE);
            double fade = t * t * (3 - 2 * t);
            double weights = sheetWeight, sum = sheetWeight * value[i];
            for (int j = 0; j < k; j++) {
                double dx = x - px[j], dz = z - pz[j];
                double w = (1 / (dx * dx + dz * dz) - cutoff) * fade;
                if (w <= 0) continue;
                weights += w;
                sum += w * clamp(py[j] + pgx[j] * dx + pgz[j] * dz, lo, hi);
            }
            heights[i] = (int) StrictMath.floor(sum / weights + 0.5);
        }
    }

    /**
     * Each covered column's distance in steps (4-neighbour) from the nearest {@code edge} column (the outline and the
     * rim); 0 on them.
     */
    private static int[] rimDistance(boolean[] edge, boolean[] in, int sizeX, int sizeZ) {
        int[] distance = new int[in.length];
        Arrays.fill(distance, Integer.MAX_VALUE);
        int[] queue = new int[in.length];
        int head = 0, tail = 0;
        for (int i = 0; i < in.length; i++) {
            if (in[i] && edge[i]) {
                distance[i] = 0;
                queue[tail++] = i;
            }
        }
        while (head < tail) {
            int i = queue[head++];
            int x = i % sizeX, z = i / sizeX;
            int[] next = {x > 0 ? i - 1 : -1, x + 1 < sizeX ? i + 1 : -1, z > 0 ? i - sizeX : -1,
                    z + 1 < sizeZ ? i + sizeX : -1};
            for (int n : next) {
                if (n < 0 || !in[n] || distance[n] != Integer.MAX_VALUE) continue;
                distance[n] = distance[i] + 1;
                queue[tail++] = n;
            }
        }
        return distance;
    }

    private static double clamp(double v, int lo, int hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    /**
     * Holds the outline's columns at the straight profile along each edge, as exact fractions; a column on two edges
     * keeps the first edge's (in loop order).
     */
    private static void outlineProfile(long[] xs, long[] zs, int[] ys, int[] polygon, int sizeX, long[] num,
                                       long[] den, boolean[] held) {
        int n = polygon.length;
        for (int e = 0; e < n; e++) {
            int a = polygon[e], b = polygon[(e + 1) % n];
            long dx = xs[b] - xs[a], dz = zs[b] - zs[a];
            long g = gcd(Math.abs(dx), Math.abs(dz));
            for (long step = 0; step <= g; step++) {
                long x = g == 0 ? xs[a] : xs[a] + dx / g * step;
                long z = g == 0 ? zs[a] : zs[a] + dz / g * step;
                int i = (int) (z * sizeX + x);
                if (held[i]) continue;
                num[i] = g == 0 ? ys[a] : (long) ys[a] * g + step * (long) (ys[b] - ys[a]);
                den[i] = g == 0 ? 1 : g;
                held[i] = true;
            }
        }
    }

    // ------------------------------------------------------------------------------------------------- cells

    /**
     * The sheet's cells, within {@code [bottomY, topYExclusive)}. With Extend to Ground, {@code ground} gives each
     * covered column's ground ({@link GroundFill#groundUnder} over {@link Surface#tops}); a column whose ground is
     * {@link SurfaceReader#UNLOADED} writes nothing and is listed. Without it {@code ground} is not read (may be null).
     * The cell count is worked out before any cell is made.
     *
     * @throws GeneratedTooLargeException over {@code maxCells}
     */
    public static Result generate(Surface surface, Fill fill, SurfaceReader ground, int bottomY, int topYExclusive,
                                  long maxCells) {
        Objects.requireNonNull(surface);
        Objects.requireNonNull(fill);
        if (fill.extendToGround()) Objects.requireNonNull(ground);
        int sizeX = surface.sizeX, sizeZ = surface.sizeZ;
        int[] heights = surface.heights;
        int[] bottoms = new int[heights.length];
        long[] unloaded = new long[surface.columnCount];
        int unloadedCount = 0;
        long total = 0;
        for (int lz = 0; lz < sizeZ; lz++) {
            for (int lx = 0; lx < sizeX; lx++) {
                int i = lz * sizeX + lx;
                int s = heights[i];
                if (s == Surface.OUTSIDE) continue;
                int x = surface.minX + lx, z = surface.minZ + lz;
                int bottom = s - fill.thickness() + 1;
                if (fill.extendToGround()) {
                    int g = ground.ground(x, z);
                    if (g == SurfaceReader.UNLOADED) {
                        unloaded[unloadedCount++] = GroundMap.column(x, z);
                        bottoms[i] = Integer.MAX_VALUE;
                        continue;
                    }
                    if (SurfaceReader.known(g)) bottom = g < s ? g + 1 : s;
                }
                if (fill.gapFree()) {
                    int lowest = Integer.MAX_VALUE;
                    if (lx > 0) lowest = lower(lowest, heights[i - 1]);
                    if (lx + 1 < sizeX) lowest = lower(lowest, heights[i + 1]);
                    if (lz > 0) lowest = lower(lowest, heights[i - sizeX]);
                    if (lz + 1 < sizeZ) lowest = lower(lowest, heights[i + sizeX]);
                    if (lowest != Integer.MAX_VALUE) bottom = Math.min(bottom, lowest + 1);
                }
                bottom = Math.min(bottom, s);
                bottoms[i] = bottom;
                long from = Math.max(bottom, bottomY), to = Math.min(s, topYExclusive - 1L);
                if (from <= to) total += to - from + 1;
            }
        }
        if (total > maxCells) throw new GeneratedTooLargeException(maxCells);
        GeneratedSource.Builder out = GeneratedSource.builder(maxCells);
        int topDepth = fill.topDepth();
        for (int lz = 0; lz < sizeZ; lz++) {
            for (int lx = 0; lx < sizeX; lx++) {
                int i = lz * sizeX + lx;
                int s = heights[i];
                if (s == Surface.OUTSIDE || bottoms[i] == Integer.MAX_VALUE) continue;
                int x = surface.minX + lx, z = surface.minZ + lz;
                int from = Math.max(bottoms[i], bottomY), to = (int) Math.min(s, topYExclusive - 1L);
                for (int y = from; y <= to; y++) {
                    // Top and body admit only position patterns, which read neither the state space nor the cell.
                    Pattern pattern = s - y < topDepth ? fill.top() : fill.body();
                    out.set(x, y, z, pattern.apply(null, x, y, z, -1));
                }
            }
        }
        return new Result(out.build(), Arrays.copyOf(unloaded, unloadedCount));
    }

    /** The lower of {@code lowest} and a neighbour's surface (ignored when not covered). */
    private static int lower(int lowest, int neighbour) {
        return neighbour == Surface.OUTSIDE ? lowest : Math.min(lowest, neighbour);
    }
}
