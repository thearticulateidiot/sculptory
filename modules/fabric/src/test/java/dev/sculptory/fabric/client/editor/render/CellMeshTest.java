package dev.sculptory.fabric.client.editor.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.buffer.BlockBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class CellMeshTest {
    private static final int[][] DIRS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    /** A set of cells, with the sections it touches. */
    private record Cells(Set<List<Integer>> cells) implements CellMesh.CellTest {
        @Override
        public boolean contains(int x, int y, int z) {
            return cells.contains(List.of(x, y, z));
        }

        long[] sectionKeys() {
            Set<Long> keys = new TreeSet<>();
            for (List<Integer> c : cells) keys.add(BlockBuffer.keyOfBlock(c.get(0), c.get(1), c.get(2)));
            return keys.stream().mapToLong(Long::longValue).toArray();
        }
    }

    private static Cells box(int x0, int y0, int z0, int x1, int y1, int z1) {
        Set<List<Integer>> cells = new HashSet<>();
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) cells.add(List.of(x, y, z));
            }
        }
        return new Cells(cells);
    }

    /** A random lumpy blob in [-20, 20)³, so it crosses section borders on every axis. */
    private static Cells blob(long seed, double density) {
        Random random = new Random(seed);
        Set<List<Integer>> cells = new HashSet<>();
        for (int x = -20; x < 20; x++) {
            for (int y = -20; y < 20; y++) {
                for (int z = -20; z < 20; z++) {
                    double r = Math.sqrt(x * x + y * y + z * z) / 20.0;
                    if (random.nextDouble() < density * (1.2 - r)) cells.add(List.of(x, y, z));
                }
            }
        }
        return new Cells(cells);
    }

    private static CellMesh mesh(Cells cells) {
        return CellMesh.build(CellMesh.Rows.of(cells), cells.sectionKeys(), new CellMesh.Caps(1 << 30, 1 << 30), () -> false);
    }

    // ---- Brute force ----

    /** Every exposed unit face as (x, y, z, dir). */
    private static Set<List<Integer>> exposedFaces(Cells cells) {
        Set<List<Integer>> faces = new HashSet<>();
        for (List<Integer> c : cells.cells()) {
            for (int d = 0; d < 6; d++) {
                if (!cells.contains(c.get(0) + DIRS[d][0], c.get(1) + DIRS[d][1], c.get(2) + DIRS[d][2])) {
                    faces.add(List.of(c.get(0), c.get(1), c.get(2), d));
                }
            }
        }
        return faces;
    }

    /**
     * Every outline unit edge as (axis, x, y, z), the segment from (x, y, z) one block along the axis: a side of an
     * exposed face whose neighbour face in the same plane, across that side, is not exposed.
     */
    private static Set<List<Integer>> outlineEdges(Cells cells) {
        Set<List<Integer>> faces = exposedFaces(cells);
        Set<List<Integer>> edges = new HashSet<>();
        for (List<Integer> f : faces) {
            int d = f.get(3);
            int n = d / 2;
            for (int e = 0; e < 6; e++) {
                int m = e / 2;
                if (m == n) continue;
                List<Integer> neighbour = List.of(f.get(0) + DIRS[e][0], f.get(1) + DIRS[e][1], f.get(2) + DIRS[e][2], d);
                if (faces.contains(neighbour)) continue;
                int along = 3 - n - m;
                int[] p = {f.get(0), f.get(1), f.get(2)};
                if (d % 2 == 0) p[n] += 1;
                if (e % 2 == 0) p[m] += 1;
                edges.add(List.of(along, p[0], p[1], p[2]));
            }
        }
        return edges;
    }

    // ---- Mesh to unit faces and edges ----

    private static Map<List<Integer>, Integer> meshFaces(CellMesh mesh) {
        Map<List<Integer>, Integer> faces = new HashMap<>();
        for (CellMesh.Section section : mesh.sections()) {
            int[] base = {section.sectionX() << 4, section.sectionY() << 4, section.sectionZ() << 4};
            for (int quad : section.quads()) {
                int dir = CellMesh.quadDir(quad);
                int axisN = dir / 2;
                int axisU = axisN == 0 ? 2 : 0;
                int axisV = axisN == 1 ? 2 : 1;
                for (int u = CellMesh.quadU0(quad); u <= CellMesh.quadU1(quad); u++) {
                    for (int v = CellMesh.quadV0(quad); v <= CellMesh.quadV1(quad); v++) {
                        int[] cell = new int[3];
                        cell[axisN] = base[axisN] + CellMesh.quadPlane(quad);
                        cell[axisU] = base[axisU] + u;
                        cell[axisV] = base[axisV] + v;
                        faces.merge(List.of(cell[0], cell[1], cell[2], dir), 1, Integer::sum);
                    }
                }
            }
        }
        return faces;
    }

    private static Map<List<Integer>, Integer> meshEdges(CellMesh mesh) {
        Map<List<Integer>, Integer> edges = new HashMap<>();
        double[] ends = new double[6];
        for (CellMesh.Section section : mesh.sections()) {
            int[] base = {section.sectionX() << 4, section.sectionY() << 4, section.sectionZ() << 4};
            for (int edge : section.edges()) {
                int axis = CellMesh.edgeAxis(edge);
                CellMesh.edgeEnds(edge, ends);
                for (int k = (int) ends[axis]; k < (int) ends[3 + axis]; k++) {
                    int[] p = {base[0] + (int) ends[0], base[1] + (int) ends[1], base[2] + (int) ends[2]};
                    p[axis] = base[axis] + k;
                    edges.merge(List.of(axis, p[0], p[1], p[2]), 1, Integer::sum);
                }
            }
        }
        return edges;
    }

    private static void assertExact(Cells cells) {
        CellMesh mesh = mesh(cells);
        Map<List<Integer>, Integer> faces = meshFaces(mesh);
        assertEquals(exposedFaces(cells), faces.keySet(), "the quads cover exactly the exposed faces");
        assertTrue(faces.values().stream().allMatch(count -> count == 1), "no face is covered twice");
        assertEquals(outlineEdges(cells), meshEdges(mesh).keySet(), "the edges are exactly the outline");
    }

    // ---- Tests ----

    @Test
    void oneCellIsSixQuadsAndTwelveEdges() {
        CellMesh mesh = mesh(box(3, 4, 5, 3, 4, 5));
        assertEquals(6, mesh.quadCount());
        assertEquals(12, mesh.edgeCount());
        assertExact(box(3, 4, 5, 3, 4, 5));
    }

    @Test
    void aBoxInOneSectionMergesIntoSixQuadsAndTwelveEdges() {
        CellMesh mesh = mesh(box(0, 0, 0, 15, 15, 15));
        assertEquals(6, mesh.quadCount());
        assertEquals(12, mesh.edgeCount());
        int area = 0;
        for (int quad : mesh.sections().get(0).quads()) area += CellMesh.quadArea(quad);
        assertEquals(6 * 256, area);
    }

    @Test
    void aBarAcrossTwoSectionsSplitsAtTheBorderAndDrawsEachEdgeOnce() {
        Cells bar = box(0, 0, 0, 31, 0, 0);
        CellMesh mesh = mesh(bar);
        assertEquals(10, mesh.quadCount());
        assertEquals(16, mesh.edgeCount());
        int length = 0;
        for (CellMesh.Section section : mesh.sections()) {
            for (int edge : section.edges()) length += CellMesh.edgeLength(edge);
        }
        assertEquals(4 * 32 + 8, length);
        Map<List<Integer>, Integer> edges = meshEdges(mesh);
        assertTrue(edges.values().stream().allMatch(count -> count == 1), "convex outlines draw each edge once");
        assertExact(bar);
    }

    @Test
    void randomBlobsAcrossSectionBordersMatchTheBruteForceOutline() {
        for (long seed = 1; seed <= 6; seed++) {
            assertExact(blob(seed, seed % 2 == 0 ? 0.9 : 0.55));
        }
    }

    @Test
    void anLShapedStepHasItsConcaveEdge() {
        Cells step = box(0, 0, 0, 3, 0, 3);
        step.cells().addAll(box(0, 1, 0, 1, 1, 3).cells());
        assertExact(step);
    }

    @Test
    void spanRowsMatchPerCellRows() {
        // A sphere of radius 11.5 around (0.5, 0.5, 0.5): per-cell tests against x intervals per row.
        CellMesh.CellTest sphere = (x, y, z) -> (x - 0.5) * (x - 0.5) + (y - 0.5) * (y - 0.5) + (z - 0.5) * (z - 0.5) <= 132.25;
        CellMesh.SpanSource spans = (y, z) -> {
            double rest = 132.25 - (y - 0.5) * (y - 0.5) - (z - 0.5) * (z - 0.5);
            if (rest < 0) return CellMesh.NO_SPAN;
            double half = Math.sqrt(rest);
            int min = (int) Math.ceil(0.5 - half);
            int max = (int) Math.floor(0.5 + half);
            return min > max ? CellMesh.NO_SPAN : CellMesh.span(min, max);
        };
        List<Long> keys = new ArrayList<>();
        for (int sx = -1; sx <= 0; sx++) {
            for (int sz = -1; sz <= 0; sz++) {
                for (int sy = -1; sy <= 0; sy++) keys.add(BlockBuffer.key(sx, sy, sz));
            }
        }
        long[] sectionKeys = keys.stream().mapToLong(Long::longValue).toArray();
        for (long key : sectionKeys) {
            int sx = BlockBuffer.keyX(key);
            int sy = BlockBuffer.keyY(key);
            int sz = BlockBuffer.keyZ(key);
            CellMesh.Section bySpan = CellMesh.meshSection(CellMesh.Rows.ofSpans(spans), sx, sy, sz);
            CellMesh.Section byCell = CellMesh.meshSection(CellMesh.Rows.of(sphere), sx, sy, sz);
            assertArrayEquals(byCell.quads(), bySpan.quads());
            assertArrayEquals(byCell.edges(), bySpan.edges());
        }
    }

    @Test
    void spanRowsClipToTheEighteenCellWindow() {
        CellMesh.Rows rows = CellMesh.Rows.ofSpans((y, z) -> y == 0 ? CellMesh.span(-1000, 1000) : CellMesh.NO_SPAN);
        assertEquals(0x3FFFF, rows.row(0, 0, 0));
        assertEquals(0, rows.row(0, 1, 0));
        CellMesh.Rows partial = CellMesh.Rows.ofSpans((y, z) -> CellMesh.span(3, 5));
        assertEquals(0b111 << 4, partial.row(0, 0, 0)); // cells 3..5 are bits 4..6
        assertEquals(0, partial.row(32, 0, 0));
    }

    @Test
    void theCapsStopTheBuildAndLeaveNothingToDraw() {
        Cells checker = new Cells(new HashSet<>());
        for (int x = 0; x < 32; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    if (((x + y + z) & 1) == 0) checker.cells().add(List.of(x, y, z));
                }
            }
        }
        CellMesh capped = CellMesh.build(CellMesh.Rows.of(checker), checker.sectionKeys(), new CellMesh.Caps(1000, 1 << 20),
                () -> false);
        assertTrue(capped.tooDetailed());
        assertTrue(capped.sections().isEmpty());
        assertTrue(capped.quadCount() > 1000);

        CellMesh full = mesh(checker);
        assertFalse(full.tooDetailed());
        assertEquals(checker.cells().size() * 6L, full.quadCount(), "a checkerboard merges nothing");
    }

    @Test
    void tooManyEdgesLeaveTheFacesWithoutEdges() {
        Cells bars = new Cells(new HashSet<>());
        for (int x = 0; x < 40; x += 2) bars.cells().addAll(box(x, 0, 0, x, 0, 40).cells());
        CellMesh full = mesh(bars);
        CellMesh faces = CellMesh.build(CellMesh.Rows.of(bars), bars.sectionKeys(),
                new CellMesh.Caps(1 << 20, (int) full.edgeCount() - 1), () -> false);
        assertFalse(faces.tooDetailed());
        assertTrue(faces.edgesDropped());
        assertEquals(0, faces.edgeCount());
        assertEquals(full.quadCount(), faces.quadCount());
        assertTrue(faces.sections().stream().allMatch(section -> section.edges().length == 0));
        assertEquals(full.quadCount() * CellMesh.QUAD_BYTES, faces.vertexBytes());
    }

    @Test
    void aCancelledBuildReturnsNull() {
        Cells cube = box(0, 0, 0, 40, 40, 40);
        assertNull(CellMesh.build(CellMesh.Rows.of(cube), cube.sectionKeys(), CellMesh.Caps.DEFAULT, () -> true));
    }

    @Test
    void quadCornersAreOnTheFaceAndPushedOutwards() {
        CellMesh mesh = mesh(box(0, 0, 0, 1, 2, 0));
        double[] corners = new double[12];
        for (int quad : mesh.sections().get(0).quads()) {
            CellMesh.quadCorners(quad, 0.01, corners);
            int dir = CellMesh.quadDir(quad);
            int axis = dir / 2;
            double expected = switch (dir) {
                case CellMesh.POS_X -> 2.01;
                case CellMesh.POS_Y -> 3.01;
                case CellMesh.POS_Z -> 1.01;
                default -> -0.01;
            };
            for (int i = 0; i < 4; i++) assertEquals(expected, corners[i * 3 + axis], 1e-9);
        }
    }
}
