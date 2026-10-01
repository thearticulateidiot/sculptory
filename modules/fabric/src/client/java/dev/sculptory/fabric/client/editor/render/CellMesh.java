package dev.sculptory.fabric.client.editor.render;

import dev.sculptory.core.buffer.BlockBuffer;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * The outline of a set of cells, for drawing a shaped or magic selection exactly: the exposed faces (a face of a
 * selected cell whose neighbour across it is not selected), merged into rectangles per plane, and the outline edges
 * where those faces fold or end. Built per 16³ world section. Pure (no Minecraft types): the renderer turns the
 * packed quads and edges into vertices.
 *
 * <p><b>Edges.</b> Every outline edge lies where two exposed faces of different axes meet, so each is drawn from one
 * of them only: faces across X draw their edges along Y and Z, faces across Y their edges along X, faces across Z
 * none. That draws each edge once, except where two cells touch only along an edge (a magic selection with diagonals,
 * for instance): there both cells' faces meet the edge and it is drawn twice. Collinear edges within a section are
 * merged.
 *
 * <p><b>Caps.</b> {@link #build} stops once the quads pass {@link Caps}: the result is then {@link #tooDetailed()}
 * and the renderer draws the bounds instead. Past the edge cap it keeps the faces and drops every edge.
 */
public final class CellMesh {
    /** The six face directions, in the order of the packed {@code dir} field. */
    public static final int POS_X = 0, NEG_X = 1, POS_Y = 2, NEG_Y = 3, POS_Z = 4, NEG_Z = 5;

    /** Which cells are in the set, one row of 18 cells at a time. */
    @FunctionalInterface
    public interface Rows {
        /** Bits 0 to 17: whether cells {@code x0 - 1} to {@code x0 + 16} of row (y, z) are in the set. */
        int row(int x0, int y, int z);

        /** Rows from a per-cell test (18 tests per row). */
        static Rows of(CellTest test) {
            Objects.requireNonNull(test);
            return (x0, y, z) -> {
                int bits = 0;
                for (int i = 0; i < 18; i++) {
                    if (test.contains(x0 - 1 + i, y, z)) bits |= 1 << i;
                }
                return bits;
            };
        }

        /** Rows of a set whose row (y, z) is one interval of x (or none), as convex shapes are. */
        static Rows ofSpans(SpanSource spans) {
            Objects.requireNonNull(spans);
            return (x0, y, z) -> {
                long span = spans.span(y, z);
                if (span == NO_SPAN) return 0;
                long min = Math.max((long) x0 - 1, spanMin(span));
                long max = Math.min((long) x0 + 16, spanMax(span));
                if (min > max) return 0;
                int from = (int) (min - (x0 - 1));
                int to = (int) (max - (x0 - 1));
                return (int) (((1L << (to + 1)) - 1) & ~((1L << from) - 1));
            };
        }
    }

    /** Whether one cell is in the set. */
    @FunctionalInterface
    public interface CellTest {
        boolean contains(int x, int y, int z);
    }

    /** The x interval of each row, packed with {@link #span} (or {@link #NO_SPAN}). */
    @FunctionalInterface
    public interface SpanSource {
        long span(int y, int z);
    }

    /** A row without cells. */
    public static final long NO_SPAN = Long.MIN_VALUE;

    /** Packs the inclusive interval {@code [min, max]} of a row. */
    public static long span(int min, int max) {
        return ((long) min << 32) | (max & 0xFFFFFFFFL);
    }

    public static int spanMin(long span) {
        return (int) (span >> 32);
    }

    public static int spanMax(long span) {
        return (int) span;
    }

    /**
     * The most quads a mesh may hold before the renderer draws the bounds instead, and the most edges before it draws
     * the faces without their edges.
     */
    public record Caps(int maxQuads, int maxEdges) {
        /** At most about 16.8 MB of face and 15.7 MB of edge vertices (see {@link #vertexBytes}). */
        public static final Caps DEFAULT = new Caps(262_144, 196_608);

        public Caps {
            if (maxQuads < 0 || maxEdges < 0) throw new IllegalArgumentException("Negative caps");
        }
    }

    /** Vertex bytes of a quad as the renderer uploads it: 4 vertices of position and colour. */
    public static final int QUAD_BYTES = 4 * 16;
    /**
     * Vertex bytes of an edge: 2 vertices of position, colour and normal (20 bytes each), which 1.21.1's
     * {@code BufferBuilder} writes twice in {@code LINES} mode (the lines shader widens each segment into a quad).
     */
    public static final int EDGE_BYTES = 4 * 20;

    /**
     * The mesh of one world section. {@code quads}: {@code dir(3) | plane(4) | u0(4) | v0(4) | u1(4) | v1(4)}, low bits
     * first, with (u, v) = (z, y) across X, (x, z) across Y and (x, y) across Z; {@code edges}: {@code axis(2) | a(5) |
     * b(5) | from(5) | to(5)}, a line along {@code axis} from {@code from} to {@code to} at the other two coordinates
     * (a, b) in x, y, z order. All coordinates are section-local block units (edges 0 to 16).
     */
    public record Section(int sectionX, int sectionY, int sectionZ, int[] quads, int[] edges) {
        public Section {
            Objects.requireNonNull(quads);
            Objects.requireNonNull(edges);
        }
    }

    private final List<Section> sections;
    private final long quadCount;
    private final long edgeCount;
    private final boolean tooDetailed;
    private final boolean edgesDropped;

    private CellMesh(List<Section> sections, long quadCount, long edgeCount, boolean tooDetailed, boolean edgesDropped) {
        this.sections = List.copyOf(sections);
        this.quadCount = quadCount;
        this.edgeCount = edgeCount;
        this.tooDetailed = tooDetailed;
        this.edgesDropped = edgesDropped;
    }

    /** An empty mesh. */
    public static CellMesh empty() {
        return new CellMesh(List.of(), 0, 0, false, false);
    }

    /** A mesh not built because its region is too large: nothing to draw, {@link #tooDetailed()}. */
    public static CellMesh tooLarge() {
        return new CellMesh(List.of(), 0, 0, true, false);
    }

    /**
     * Meshes the sections {@code sectionKeys} ({@link BlockBuffer#key}) of the set. Sections without an exposed face
     * are left out. Stops early, {@link #tooDetailed()}, when the quads pass their cap; leaves every edge out,
     * {@link #edgesDropped()}, when the edges pass theirs. Returns null when {@code cancelled} says so (checked
     * between sections).
     */
    public static CellMesh build(Rows rows, long[] sectionKeys, Caps caps, BooleanSupplier cancelled) {
        Objects.requireNonNull(rows);
        Objects.requireNonNull(caps);
        Objects.requireNonNull(cancelled);
        List<Section> sections = new ArrayList<>();
        long quads = 0;
        long edges = 0;
        boolean keepEdges = true;
        for (long key : sectionKeys) {
            if (cancelled.getAsBoolean()) return null;
            Section section = meshSection(rows, BlockBuffer.keyX(key), BlockBuffer.keyY(key), BlockBuffer.keyZ(key));
            if (section.quads().length == 0) continue; // no faces, no edges
            quads += section.quads().length;
            if (quads > caps.maxQuads()) return new CellMesh(List.of(), quads, 0, true, false);
            if (keepEdges && edges + section.edges().length > caps.maxEdges()) {
                keepEdges = false;
                edges = 0;
                sections.replaceAll(kept -> withoutEdges(kept));
            }
            if (keepEdges) {
                edges += section.edges().length;
            } else {
                section = withoutEdges(section);
            }
            sections.add(section);
        }
        return new CellMesh(sections, quads, edges, false, !keepEdges);
    }

    private static Section withoutEdges(Section section) {
        return new Section(section.sectionX(), section.sectionY(), section.sectionZ(), section.quads(), new int[0]);
    }

    public List<Section> sections() {
        return sections;
    }

    /** Quads in the mesh (when too detailed: the count when building stopped). */
    public long quadCount() {
        return quadCount;
    }

    /** Edges in the mesh (0 when they were dropped or the mesh is too detailed). */
    public long edgeCount() {
        return edgeCount;
    }

    /** Whether the quads passed their cap: the mesh holds nothing then, and the renderer draws the bounds. */
    public boolean tooDetailed() {
        return tooDetailed;
    }

    /** Whether the edges passed their cap: the mesh holds the faces only. */
    public boolean edgesDropped() {
        return edgesDropped;
    }

    /** The vertex bytes the renderer uploads for this mesh. */
    public long vertexBytes() {
        return tooDetailed ? 0 : quadCount * QUAD_BYTES + edgeCount * EDGE_BYTES;
    }

    // ---- One section ----

    /** Meshes one world section. */
    public static Section meshSection(Rows rows, int sectionX, int sectionY, int sectionZ) {
        int x0 = sectionX << 4;
        int y0 = sectionY << 4;
        int z0 = sectionZ << 4;
        // r[(y + 1) * 18 + (z + 1)]: bits 0..17 = cells x0 - 1 .. x0 + 16 of row (y0 + y, z0 + z), y and z in -1..16.
        int[] r = new int[18 * 18];
        boolean inside = false;
        for (int y = -1; y <= 16; y++) {
            for (int z = -1; z <= 16; z++) {
                int bits = rows.row(x0, y0 + y, z0 + z) & 0x3FFFF;
                r[(y + 1) * 18 + (z + 1)] = bits;
                if (y >= 0 && y < 16 && z >= 0 && z < 16 && (bits & 0x1FFFE) != 0) inside = true;
            }
        }
        IntArrayList quads = new IntArrayList();
        IntArrayList edges = new IntArrayList();
        if (inside) {
            int[] grid = new int[18];
            for (int p = 0; p < 16; p++) {
                // Across Y: (u, v) = (x, z), rows of 18 bits over x directly.
                for (int sign = 0; sign < 2; sign++) {
                    int other = sign == 0 ? p + 1 : p - 1;
                    for (int v = -1; v <= 16; v++) grid[v + 1] = row(r, p, v) & ~row(r, other, v);
                    int dir = sign == 0 ? POS_Y : NEG_Y;
                    faces(grid, dir, p, quads);
                    edgesAlongU(grid, dir, p, edges);
                }
                // Across Z: (u, v) = (x, y).
                for (int sign = 0; sign < 2; sign++) {
                    int other = sign == 0 ? p + 1 : p - 1;
                    for (int v = -1; v <= 16; v++) grid[v + 1] = row(r, v, p) & ~row(r, v, other);
                    faces(grid, sign == 0 ? POS_Z : NEG_Z, p, quads);
                }
            }
            // Across X: (u, v) = (z, y); the grids are the rows transposed.
            int[][] plus = new int[16][18];
            int[][] minus = new int[16][18];
            for (int y = -1; y <= 16; y++) {
                for (int z = -1; z <= 16; z++) {
                    int bits = row(r, y, z);
                    int exposedPlus = (bits & ~(bits >>> 1)) >>> 1 & 0xFFFF;
                    int exposedMinus = (bits & ~(bits << 1)) >>> 1 & 0xFFFF;
                    for (int x = 0; x < 16; x++) {
                        if ((exposedPlus >>> x & 1) != 0) plus[x][y + 1] |= 1 << (z + 1);
                        if ((exposedMinus >>> x & 1) != 0) minus[x][y + 1] |= 1 << (z + 1);
                    }
                }
            }
            for (int p = 0; p < 16; p++) {
                faces(plus[p], POS_X, p, quads);
                edgesAlongU(plus[p], POS_X, p, edges);
                edgesAlongV(plus[p], POS_X, p, edges);
                faces(minus[p], NEG_X, p, quads);
                edgesAlongU(minus[p], NEG_X, p, edges);
                edgesAlongV(minus[p], NEG_X, p, edges);
            }
        }
        return new Section(sectionX, sectionY, sectionZ, quads.toIntArray(), edges.toIntArray());
    }

    private static int row(int[] r, int y, int z) {
        return r[(y + 1) * 18 + (z + 1)];
    }

    /** Greedy rectangles over the inner 16 × 16 of an exposure grid (18 rows of 18 bits, both with a border). */
    private static void faces(int[] grid, int dir, int plane, IntArrayList out) {
        int[] rows = new int[16];
        for (int v = 0; v < 16; v++) rows[v] = grid[v + 1] >>> 1 & 0xFFFF;
        for (int v = 0; v < 16; v++) {
            while (rows[v] != 0) {
                int u0 = Integer.numberOfTrailingZeros(rows[v]);
                int u1 = u0;
                while (u1 + 1 < 16 && (rows[v] >>> (u1 + 1) & 1) != 0) u1++;
                int run = ((1 << (u1 + 1)) - 1) & ~((1 << u0) - 1);
                int v1 = v;
                while (v1 + 1 < 16 && (rows[v1 + 1] & run) == run) v1++;
                for (int w = v; w <= v1; w++) rows[w] &= ~run;
                out.add(dir | plane << 3 | u0 << 7 | v << 11 | u1 << 15 | v1 << 19);
            }
        }
    }

    /**
     * The edges at v boundaries (lines along u) of this section's exposed faces: between rows v - 1 and v where
     * exactly one is exposed, and at the section's own border only where the inside one is.
     */
    private static void edgesAlongU(int[] grid, int dir, int plane, IntArrayList out) {
        for (int b = 0; b <= 16; b++) {
            int below = grid[b] >>> 1 & 0xFFFF;
            int above = grid[b + 1] >>> 1 & 0xFFFF;
            int edge = below ^ above;
            if (b == 0) edge &= above;
            if (b == 16) edge &= below;
            while (edge != 0) {
                int from = Integer.numberOfTrailingZeros(edge);
                int to = from;
                while (to + 1 < 16 && (edge >>> (to + 1) & 1) != 0) to++;
                edge &= ~(((1 << (to + 1)) - 1) & ~((1 << from) - 1));
                out.add(edge(dir, plane, true, b, from, to + 1));
            }
        }
    }

    /** The edges at u boundaries (lines along v), as {@link #edgesAlongU} with the axes swapped. */
    private static void edgesAlongV(int[] grid, int dir, int plane, IntArrayList out) {
        int[] atBoundary = new int[17];
        for (int v = 0; v < 16; v++) {
            int m = grid[v + 1];
            int edge = (m ^ (m >>> 1)) & 0x1FFFF;
            if ((m & 2) == 0) edge &= ~1;
            if ((m & (1 << 16)) == 0) edge &= ~(1 << 16);
            while (edge != 0) {
                int b = Integer.numberOfTrailingZeros(edge);
                edge &= edge - 1;
                atBoundary[b] |= 1 << v;
            }
        }
        for (int b = 0; b <= 16; b++) {
            int vs = atBoundary[b];
            while (vs != 0) {
                int from = Integer.numberOfTrailingZeros(vs);
                int to = from;
                while (to + 1 < 16 && (vs >>> (to + 1) & 1) != 0) to++;
                vs &= ~(((1 << (to + 1)) - 1) & ~((1 << from) - 1));
                out.add(edge(dir, plane, false, b, from, to + 1));
            }
        }
    }

    /**
     * Packs an edge of a face plane: along u ({@code alongU}) at v boundary {@code at}, or along v at u boundary
     * {@code at}, from {@code from} to {@code to} (boundaries).
     */
    private static int edge(int dir, int plane, boolean alongU, int at, int from, int to) {
        int n = plane + (dir % 2 == 0 ? 1 : 0); // the face's own coordinate across its axis
        int axisN = dir / 2;
        // (u, v) axes per normal axis: X -> (z, y), Y -> (x, z), Z -> (x, y).
        int axisU = axisN == 0 ? 2 : 0;
        int axisV = axisN == 1 ? 2 : 1;
        int along = alongU ? axisU : axisV;
        int[] fixed = new int[3];
        fixed[axisN] = n;
        fixed[alongU ? axisV : axisU] = at;
        int a;
        int b;
        switch (along) {
            case 0 -> {
                a = fixed[1];
                b = fixed[2];
            }
            case 1 -> {
                a = fixed[0];
                b = fixed[2];
            }
            default -> {
                a = fixed[0];
                b = fixed[1];
            }
        }
        return along | a << 2 | b << 7 | from << 12 | to << 17;
    }

    // ---- Decoding, for the renderer and tests ----

    public static int quadDir(int quad) {
        return quad & 7;
    }

    public static int quadPlane(int quad) {
        return quad >>> 3 & 15;
    }

    public static int quadU0(int quad) {
        return quad >>> 7 & 15;
    }

    public static int quadV0(int quad) {
        return quad >>> 11 & 15;
    }

    public static int quadU1(int quad) {
        return quad >>> 15 & 15;
    }

    public static int quadV1(int quad) {
        return quad >>> 19 & 15;
    }

    /** The cells a quad covers. */
    public static int quadArea(int quad) {
        return (quadU1(quad) - quadU0(quad) + 1) * (quadV1(quad) - quadV0(quad) + 1);
    }

    /**
     * The quad's four corners in section-local block units, pushed {@code inflate} outwards along its normal, as
     * {x, y, z} × 4 in order around its edge.
     */
    public static void quadCorners(int quad, double inflate, double[] out) {
        int dir = quadDir(quad);
        int axisN = dir / 2;
        boolean positive = dir % 2 == 0;
        double n = quadPlane(quad) + (positive ? 1 : 0) + (positive ? inflate : -inflate);
        int axisU = axisN == 0 ? 2 : 0;
        int axisV = axisN == 1 ? 2 : 1;
        int u0 = quadU0(quad);
        int u1 = quadU1(quad) + 1;
        int v0 = quadV0(quad);
        int v1 = quadV1(quad) + 1;
        int[][] corners = {{u0, v0}, {u1, v0}, {u1, v1}, {u0, v1}};
        for (int i = 0; i < 4; i++) {
            out[i * 3 + axisN] = n;
            out[i * 3 + axisU] = corners[i][0];
            out[i * 3 + axisV] = corners[i][1];
        }
    }

    public static int edgeAxis(int edge) {
        return edge & 3;
    }

    /** The edge's two ends in section-local block units, as {x, y, z} × 2. */
    public static void edgeEnds(int edge, double[] out) {
        int axis = edgeAxis(edge);
        int a = edge >>> 2 & 31;
        int b = edge >>> 7 & 31;
        int from = edge >>> 12 & 31;
        int to = edge >>> 17 & 31;
        int first = axis == 0 ? 1 : 0;
        int second = axis == 2 ? 1 : 2;
        out[axis] = from;
        out[first] = a;
        out[second] = b;
        out[3 + axis] = to;
        out[3 + first] = a;
        out[3 + second] = b;
    }

    /** The edge's length in blocks. */
    public static int edgeLength(int edge) {
        return (edge >>> 17 & 31) - (edge >>> 12 & 31);
    }
}
