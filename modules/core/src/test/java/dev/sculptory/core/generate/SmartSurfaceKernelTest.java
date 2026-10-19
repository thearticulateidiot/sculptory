package dev.sculptory.core.generate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.edit.MixLayout;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.generate.SmartSurfaceKernel.Fill;
import dev.sculptory.core.generate.SmartSurfaceKernel.Problem;
import dev.sculptory.core.generate.SmartSurfaceKernel.RefusedException;
import dev.sculptory.core.generate.SmartSurfaceKernel.Shape;
import dev.sculptory.core.generate.SmartSurfaceKernel.Surface;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Smart Surface: columns, heights, triangulation and cells on synthetic points. */
class SmartSurfaceKernelTest {
    private static final Pattern TOP = new Pattern.Single(1);
    private static final Pattern BODY = new Pattern.Single(2);
    private static final int BOTTOM = -64;
    private static final int TOP_Y = 320;

    private static List<BlockPos> points(int... xyz) {
        List<BlockPos> out = new ArrayList<>();
        for (int i = 0; i < xyz.length; i += 3) out.add(new BlockPos(xyz[i], xyz[i + 1], xyz[i + 2]));
        return out;
    }

    private static Surface surface(List<BlockPos> outline, List<BlockPos> inside, boolean faceted) {
        return SmartSurfaceKernel.surface(new Shape(outline, inside, faceted));
    }

    private static GeneratedSource cells(Surface surface, Fill fill) {
        return SmartSurfaceKernel.generate(surface, fill, null, BOTTOM, TOP_Y, Long.MAX_VALUE).source();
    }

    /** The lowest and highest present cell of each column, as {x, z} key to {low, high}. */
    private static Map<Long, int[]> spans(GeneratedSource source) {
        Map<Long, int[]> out = new HashMap<>();
        source.forEach((x, y, z, state) -> {
            int[] span = out.computeIfAbsent(GroundMap.column(x, z), k -> new int[] {y, y});
            span[0] = Math.min(span[0], y);
            span[1] = Math.max(span[1], y);
        });
        return out;
    }

    /** Whether column (x, z) lies inside the closed polygon or on it: an independent even-odd test. */
    private static boolean insideOrOn(List<BlockPos> outline, int x, int z) {
        int n = outline.size();
        boolean inside = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            long xi = outline.get(i).x(), zi = outline.get(i).z(), xj = outline.get(j).x(), zj = outline.get(j).z();
            long cross = (xj - xi) * (z - zi) - (zj - zi) * (x - xi);
            if (cross == 0 && Math.min(xi, xj) <= x && x <= Math.max(xi, xj) && Math.min(zi, zj) <= z
                    && z <= Math.max(zi, zj)) {
                return true;
            }
            if ((zi > z) != (zj > z)) {
                // x of the crossing compared exactly: x < xi + (z - zi)(xj - xi) / (zj - zi).
                long lhs = (x - xi) * (zj - zi), rhs = (z - zi) * (xj - xi);
                if (zj - zi > 0 ? lhs < rhs : lhs > rhs) inside = !inside;
            }
        }
        return inside;
    }

    // ---------------------------------------------------------------- through the points

    @Test
    void theSurfacePassesThroughEveryPoint() {
        List<BlockPos> outline = points(0, 64, 0, 20, 70, -3, 28, 61, 14, 15, 80, 25, -4, 66, 18);
        List<BlockPos> inside = points(10, 90, 10, 18, 58, 8, 5, 75, 14);
        for (boolean faceted : new boolean[] {false, true}) {
            Surface surface = surface(outline, inside, faceted);
            for (List<BlockPos> list : List.of(outline, inside)) {
                for (BlockPos p : list) assertEquals(p.y(), surface.surfaceY(p.x(), p.z()), p + " faceted " + faceted);
            }
            GeneratedSource source = cells(surface, Fill.of(TOP));
            for (BlockPos p : outline) assertTrue(source.contains(p.x(), p.y(), p.z()));
            for (BlockPos p : inside) assertTrue(source.contains(p.x(), p.y(), p.z()));
            assertEquals(8, surface.sites().size());
            assertTrue(surface.ignored().isEmpty());
        }
    }

    @Test
    void smoothOutlineEdgesRunStraightBetweenTheirPoints() {
        // A non-convex outline with uneven heights and inside points pulling the sheet up and down.
        List<BlockPos> outline = points(0, 64, 0, 13, 71, 0, 13, 66, 11, 22, 80, 11, 22, 62, -2, 30, 75, -2,
                31, 70, 25, -3, 77, 24);
        List<BlockPos> inside = points(8, 95, 15, 26, 40, 15, 4, 70, 6);
        Surface smooth = surface(outline, inside, false);
        Surface faceted = surface(outline, inside, true);
        int checked = 0;
        for (int e = 0; e < outline.size(); e++) {
            BlockPos a = outline.get(e), b = outline.get((e + 1) % outline.size());
            int dx = b.x() - a.x(), dz = b.z() - a.z();
            int g = gcd(Math.abs(dx), Math.abs(dz));
            for (int k = 0; k <= g; k++) {
                int x = a.x() + dx / g * k, z = a.z() + dz / g * k;
                int expected = Math.floorDiv(2 * (a.y() * g + k * (b.y() - a.y())) + g, 2 * g);
                assertEquals(expected, smooth.surfaceY(x, z), "edge " + e + " at " + x + "," + z);
                assertEquals(expected, faceted.surfaceY(x, z), "edge " + e + " at " + x + "," + z);
                checked++;
            }
        }
        assertEquals(67, checked);
        for (BlockPos p : inside) assertEquals(p.y(), smooth.surfaceY(p.x(), p.z()));
    }

    private static int gcd(int a, int b) {
        return b == 0 ? a : gcd(b, a % b);
    }

    @Test
    void aSmoothPeakKeepsItsHeightAndFallsAway() {
        // A flat ring at 64 and one point at 84 off the centre: the top is the point itself, with nothing as high far
        // from it, and the sheet falls away from it.
        List<BlockPos> outline = points(0, 64, 0, 40, 64, 0, 40, 64, 40, 0, 64, 40);
        Surface surface = surface(outline, points(14, 84, 22), false);
        int top = Integer.MIN_VALUE, atTop = 0;
        for (long column : surface.columns()) {
            int x = GroundMap.columnX(column), z = GroundMap.columnZ(column);
            int y = surface.surfaceY(x, z);
            top = Math.max(top, y);
            if (y == 84) {
                atTop++;
                assertTrue(Math.abs(x - 14) <= 2 && Math.abs(z - 22) <= 2, "a summit column far from the peak: " + x
                        + "," + z);
            }
        }
        assertEquals(84, top);
        assertTrue(atTop <= 9, atTop + " columns at the top");
        // Walking away from the peak never climbs.
        for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}}) {
            int previous = 84;
            for (int x = 14 + d[0], z = 22 + d[1]; surface.covers(x, z); x += d[0], z += d[1]) {
                int y = surface.surfaceY(x, z);
                assertTrue(y <= previous, "climbs at " + x + "," + z);
                previous = y;
            }
            assertEquals(64, previous);
        }
        // A pit keeps its full depth as well.
        Surface pit = surface(outline, points(20, 44, 20), false);
        assertEquals(44, pit.surfaceY(20, 20));
        assertTrue(pit.surfaceY(21, 20) <= 45);
    }

    @Test
    void pointsOnOnePlaneGiveThatPlane() {
        // y = 60 + (x + 2z) / 3: never a half, so rounding has no ties.
        List<BlockPos> outline = new ArrayList<>();
        int[][] corners = {{0, 0}, {30, 0}, {30, 9}, {12, 12}, {21, 27}, {0, 24}};
        for (int[] c : corners) outline.add(new BlockPos(c[0], plane(c[0], c[1]), c[1]));
        List<BlockPos> inside = List.of(new BlockPos(6, plane(6, 9), 9), new BlockPos(24, plane(24, 3), 3));
        for (boolean faceted : new boolean[] {false, true}) {
            Surface surface = surface(outline, inside, faceted);
            for (long column : surface.columns()) {
                int x = GroundMap.columnX(column), z = GroundMap.columnZ(column);
                assertEquals(planeRounded(x, z), surface.surfaceY(x, z), x + "," + z + " faceted " + faceted);
            }
        }
    }

    private static int plane(int x, int z) {
        return 60 + (x + 2 * z) / 3;
    }

    private static int planeRounded(int x, int z) {
        return (int) Math.floor(60 + (x + 2 * z) / 3.0 + 0.5);
    }

    @Test
    void flatPointsMakeAFlatSheetEitherWay() {
        List<BlockPos> outline = points(0, 70, 0, 12, 70, 0, 12, 70, 9, 0, 70, 9);
        for (boolean faceted : new boolean[] {false, true}) {
            Surface surface = surface(outline, points(5, 70, 4), faceted);
            for (long column : surface.columns()) {
                assertEquals(70, surface.surfaceY(GroundMap.columnX(column), GroundMap.columnZ(column)));
            }
            assertEquals(13 * 10, surface.columnCount());
        }
    }

    @Test
    void facetedIsTheTrianglesPlaneRoundedHalfUp() {
        // One triangle: y = 64 + x / 2 (x 0..10), so x = 1 gives 64.5 -> 65, x = 3 gives 65.5 -> 66.
        Surface surface = surface(points(0, 64, 0, 10, 69, 0, 0, 64, 10), List.of(), true);
        for (long column : surface.columns()) {
            int x = GroundMap.columnX(column), z = GroundMap.columnZ(column);
            assertEquals(Math.floorDiv(2 * 64 * 2 + 2 * x + 2, 4), surface.surfaceY(x, z), x + "," + z);
        }
        assertEquals(66, surface.columnCount());
    }

    @Test
    void smoothAndFacetedDiffer() {
        List<BlockPos> outline = points(0, 64, 0, 30, 64, 0, 30, 64, 30, 0, 64, 30);
        List<BlockPos> inside = points(15, 84, 15);
        Surface smooth = surface(outline, inside, false);
        Surface faceted = surface(outline, inside, true);
        int differ = 0;
        for (long column : smooth.columns()) {
            int x = GroundMap.columnX(column), z = GroundMap.columnZ(column);
            if (smooth.surfaceY(x, z) != faceted.surfaceY(x, z)) differ++;
            assertTrue(smooth.surfaceY(x, z) >= 64 && smooth.surfaceY(x, z) <= 84, "kept within the points");
        }
        assertTrue(differ > 100, "differ in " + differ + " columns");
        // The faceted pyramid's ridge along the diagonal from a corner to the peak is straight.
        assertEquals(75, faceted.surfaceY(8, 8), "64 + 20 * 8 / 15 rounded");
        assertFalse(smooth.faceted());
        assertTrue(faceted.faceted());
    }

    // ---------------------------------------------------------------- columns

    @Test
    void columnsAreExactlyThoseInsideOrOnTheOutline() {
        // A non-convex outline (a U opening to the north).
        List<BlockPos> outline = points(0, 64, 0, 6, 66, 0, 6, 66, 14, 14, 68, 14, 14, 70, 0, 20, 70, 0,
                20, 72, 20, 0, 64, 20);
        for (boolean faceted : new boolean[] {false, true}) {
            Surface surface = surface(outline, List.of(), faceted);
            assertTrue(surface.simpleOutline());
            int count = 0;
            for (int x = -2; x <= 22; x++) {
                for (int z = -2; z <= 22; z++) {
                    boolean expected = insideOrOn(outline, x, z);
                    assertEquals(expected, surface.covers(x, z), x + "," + z);
                    if (expected) count++;
                }
            }
            assertEquals(count, surface.columnCount());
            assertFalse(surface.covers(10, 5), "the U's notch is open");
            GeneratedSource source = cells(surface, Fill.of(TOP));
            source.forEach((x, y, z, state) -> assertTrue(insideOrOn(outline, x, z), x + "," + z));
        }
    }

    @Test
    void theFootprintAloneMatchesTheSurfacesColumns() {
        List<BlockPos> outline = points(0, 64, 0, 6, 66, 0, 6, 66, 14, 14, 68, 14, 14, 70, 0, 20, 70, 0,
                20, 72, 20, 0, 64, 20);
        Surface surface = surface(outline, points(10, 80, 18), false);
        assertArrayEquals(surface.columns(), SmartSurfaceKernel.footprintColumns(outline));
        var tops = surface.tops();
        assertEquals(surface.columnCount(), tops.size());
        for (long column : surface.columns()) {
            assertEquals(surface.surfaceY(GroundMap.columnX(column), GroundMap.columnZ(column)), tops.get(column));
        }
        assertEquals(0, SmartSurfaceKernel.footprintColumns(points(0, 64, 0, 5, 64, 5)).length);
        assertEquals(0, SmartSurfaceKernel.footprintColumns(points(0, 64, 0, 5, 64, 5, 9, 64, 9)).length);
        assertEquals(0, SmartSurfaceKernel.footprintColumns(
                points(0, 64, 0, SmartSurfaceKernel.MAX_SIDE, 64, 0, 0, 64, 9)).length);
    }

    @Test
    void facetedOutlineEdgesRunStraightBetweenTheirPoints() {
        // The notch's inner wall from (6, 66, 14) to (14, 68, 14): the CDT keeps it, so its columns lie on that line.
        List<BlockPos> outline = points(0, 64, 0, 6, 66, 0, 6, 66, 14, 14, 68, 14, 14, 90, 0, 20, 70, 0,
                20, 72, 20, 0, 64, 20);
        Surface surface = surface(outline, points(10, 40, 18), true);
        for (int x = 6; x <= 14; x++) {
            int expected = Math.floorDiv(2 * (66 * 8 + 2 * (x - 6)) + 8, 16);
            assertEquals(expected, surface.surfaceY(x, 14), "x " + x);
        }
        for (int i = 0; i < outline.size(); i++) {
            BlockPos a = outline.get(i), b = outline.get((i + 1) % outline.size());
            assertTrue(hasEdge(surface, a, b), "outline edge " + a + " - " + b + " is a triangle edge");
        }
    }

    private static boolean hasEdge(Surface surface, BlockPos a, BlockPos b) {
        int ia = surface.sites().indexOf(a), ib = surface.sites().indexOf(b);
        int[] t = surface.triangles();
        for (int k = 0; k < t.length; k += 3) {
            for (int e = 0; e < 3; e++) {
                int u = t[k + e], v = t[k + (e + 1) % 3];
                if (u == ia && v == ib || u == ib && v == ia) return true;
            }
        }
        return false;
    }

    @Test
    void aCrossingOutlineFillsEvenOdd() {
        // A five-pointed star drawn in one stroke: its middle pentagon is crossed twice, so it is outside.
        List<BlockPos> star = new ArrayList<>();
        for (int k = 0; k < 5; k++) {
            double angle = Math.PI / 2 + k * 4 * Math.PI / 5;
            star.add(new BlockPos((int) Math.round(30 * Math.cos(angle)), 64 + k, (int) Math.round(30 * Math.sin(angle))));
        }
        for (boolean faceted : new boolean[] {false, true}) {
            Surface surface = surface(star, List.of(), faceted);
            assertFalse(surface.simpleOutline());
            assertFalse(surface.covers(0, 0), "the centre is crossed twice");
            for (int x = -31; x <= 31; x++) {
                for (int z = -31; z <= 31; z++) assertEquals(insideOrOn(star, x, z), surface.covers(x, z), x + "," + z);
            }
            for (BlockPos p : star) assertEquals(p.y(), surface.surfaceY(p.x(), p.z()));
        }
        // A bow tie: two triangles meeting where the edges cross.
        Surface tie = surface(points(0, 64, 0, 10, 64, 10, 10, 64, 0, 0, 64, 10), List.of(), true);
        assertFalse(tie.simpleOutline());
        assertTrue(tie.covers(5, 5));
        assertTrue(tie.covers(1, 5));
        assertFalse(tie.covers(5, 1));
    }

    // ---------------------------------------------------------------- odd input

    @Test
    void repeatsAreDroppedAndBadPointsRefused() {
        List<BlockPos> square = points(0, 64, 0, 9, 64, 0, 9, 64, 9, 0, 64, 9);
        List<BlockPos> repeated = points(0, 64, 0, 0, 64, 0, 9, 64, 0, 9, 64, 9, 9, 64, 9, 0, 64, 9, 0, 64, 0);
        Surface a = surface(square, List.of(), true), b = surface(repeated, List.of(), true);
        assertEquals(a.sites(), b.sites());
        assertEquals(a.columnCount(), b.columnCount());
        // An inside point on an outline point's column at its height is the same point.
        assertEquals(5, surface(square, points(9, 64, 9, 4, 64, 4, 4, 64, 4), true).sites().size());

        assertProblem(Problem.TOO_FEW_POINTS, points(0, 64, 0, 5, 64, 5), List.of());
        assertProblem(Problem.TOO_FEW_POINTS, points(0, 64, 0, 5, 64, 5, 0, 64, 0), List.of());
        assertProblem(Problem.NO_AREA, points(0, 64, 0, 5, 64, 5, 10, 70, 10, 3, 64, 3), List.of());
        assertProblem(Problem.SHARED_COLUMN, points(0, 64, 0, 9, 64, 0, 9, 65, 0, 0, 64, 9), List.of());
        assertProblem(Problem.SHARED_COLUMN, square, points(9, 70, 9));
        assertProblem(Problem.SHARED_COLUMN, square, points(4, 70, 4, 4, 71, 4));
        assertProblem(Problem.TOO_WIDE, points(0, 64, 0, SmartSurfaceKernel.MAX_SIDE, 64, 0, 0, 64, 9), List.of());
        surface(points(0, 64, 0, SmartSurfaceKernel.MAX_SIDE - 1, 64, 0, 0, 64, 9), List.of(), true);
        List<BlockPos> many = new ArrayList<>();
        for (int i = 0; i <= SmartSurfaceKernel.MAX_POINTS; i++) many.add(new BlockPos(i % 200, 64, i / 200 * 5));
        assertProblem(Problem.TOO_MANY_POINTS, many, List.of());
    }

    private static void assertProblem(Problem problem, List<BlockPos> outline, List<BlockPos> inside) {
        RefusedException e = assertThrows(RefusedException.class, () -> surface(outline, inside, false));
        assertEquals(problem, e.problem(), e.getMessage());
        assertThrows(RefusedException.class, () -> surface(outline, inside, true));
    }

    @Test
    void collinearOutlinePointsAndPointsOnEdgesWork() {
        // Points along the edges (collinear corners) and an inside point on an edge.
        List<BlockPos> outline = points(0, 64, 0, 5, 66, 0, 10, 68, 0, 10, 68, 5, 10, 68, 10, 5, 66, 10, 0, 64, 10,
                0, 64, 5);
        for (boolean faceted : new boolean[] {false, true}) {
            Surface surface = surface(outline, points(10, 68, 7, 5, 70, 5), faceted);
            assertEquals(121, surface.columnCount());
            assertEquals(70, surface.surfaceY(5, 5));
            assertEquals(68, surface.surfaceY(10, 7));
            assertEquals(10, surface.sites().size());
            if (faceted) {
                // A simple polygon of n corners with k points added inside or on edges has n - 2 + 2k triangles... on
                // an edge a point adds one: 8 corners, one inside (+2), one on an edge (+1).
                assertEquals(3 * (8 - 2 + 2 + 1), surface.triangles().length);
            }
        }
    }

    @Test
    void insidePointsOutsideTheOutlineAreIgnored() {
        List<BlockPos> outline = points(0, 64, 0, 9, 64, 0, 9, 64, 9, 0, 64, 9);
        Surface surface = surface(outline, points(4, 80, 4, 20, 90, 20, -3, 50, 4), false);
        assertEquals(points(20, 90, 20, -3, 50, 4), surface.ignored());
        assertEquals(5, surface.sites().size());
        assertTrue(surface.surfaceY(0, 4) <= 80 && surface.surfaceY(0, 4) >= 64);
    }

    @Test
    void theMeshCoversTheSheetWithCounterClockwiseTriangles() {
        List<BlockPos> outline = points(0, 64, 0, 20, 70, -3, 28, 61, 14, 15, 80, 25, -4, 66, 18);
        Surface surface = surface(outline, points(10, 90, 10, 18, 58, 8), false);
        int[] t = surface.triangles();
        assertEquals(3 * (5 - 2 + 2 * 2), t.length);
        long area = 0;
        for (int k = 0; k < t.length; k += 3) {
            BlockPos a = surface.sites().get(t[k]), b = surface.sites().get(t[k + 1]), c = surface.sites().get(t[k + 2]);
            long o = PlaneTriangulation.orient(a.x(), a.z(), b.x(), b.z(), c.x(), c.z());
            assertTrue(o > 0);
            area += o;
        }
        long polygon = 0;
        for (int i = 0; i < outline.size(); i++) {
            BlockPos a = outline.get(i), b = outline.get((i + 1) % outline.size());
            polygon += (long) a.x() * b.z() - (long) b.x() * a.z();
        }
        assertEquals(Math.abs(polygon), area, "the triangles tile the outline");
    }

    @Test
    void randomOutlinesHoldTheirProperties() {
        Random random = new Random(20261018);
        for (int round = 0; round < 300; round++) {
            int n = 3 + random.nextInt(14);
            List<BlockPos> outline = new ArrayList<>();
            boolean star = random.nextBoolean();
            for (int i = 0; i < n; i++) {
                int x, z;
                if (star) {
                    // Star-shaped around the centre, so simple unless rounding folds it.
                    double angle = 2 * Math.PI * (i + random.nextDouble() * 0.8) / n;
                    double r = 4 + random.nextInt(30);
                    x = (int) Math.round(r * Math.cos(angle));
                    z = (int) Math.round(r * Math.sin(angle));
                } else {
                    x = random.nextInt(41) - 20;
                    z = random.nextInt(41) - 20;
                }
                outline.add(new BlockPos(x, 60 + random.nextInt(20), z));
            }
            if (random.nextInt(3) == 0) {
                // Straight corners: the midpoint of every even edge (on its line when the edge's length is even).
                List<BlockPos> withMidpoints = new ArrayList<>();
                for (int i = 0; i < outline.size(); i++) {
                    BlockPos a = outline.get(i), b = outline.get((i + 1) % outline.size());
                    withMidpoints.add(a);
                    if ((a.x() + b.x()) % 2 == 0 && (a.z() + b.z()) % 2 == 0 && !a.equals(b)) {
                        withMidpoints.add(new BlockPos((a.x() + b.x()) / 2, (a.y() + b.y()) / 2, (a.z() + b.z()) / 2));
                    }
                }
                outline = withMidpoints;
            }
            List<BlockPos> inside = new ArrayList<>();
            for (int i = random.nextInt(6); i > 0; i--) {
                inside.add(new BlockPos(random.nextInt(21) - 10, 55 + random.nextInt(30), random.nextInt(21) - 10));
            }
            boolean faceted = random.nextBoolean();
            Surface surface;
            try {
                surface = surface(outline, inside, faceted);
            } catch (RefusedException refused) {
                continue;
            }
            for (BlockPos p : surface.sites()) assertEquals(p.y(), surface.surfaceY(p.x(), p.z()), "round " + round);
            if (surface.simpleOutline()) {
                // A simple outline is cut into its own triangles (never its hull's): they tile it exactly.
                assertEquals(Math.abs(shoelace(dedupe(outline))), triangleArea(surface), "round " + round + " " + outline + " " + inside);
            }
            for (int x = -35; x <= 35; x++) {
                for (int z = -35; z <= 35; z++) {
                    assertEquals(insideOrOn(dedupe(outline), x, z), surface.covers(x, z), "round " + round);
                }
            }
            int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
            for (BlockPos p : surface.sites()) {
                lo = Math.min(lo, p.y());
                hi = Math.max(hi, p.y());
            }
            for (long column : surface.columns()) {
                int y = surface.surfaceY(GroundMap.columnX(column), GroundMap.columnZ(column));
                assertTrue(y >= lo && y <= hi, "round " + round);
            }
        }
    }

    /** Twice the signed area of the closed polygon. */
    private static long shoelace(List<BlockPos> outline) {
        long area = 0;
        for (int i = 0; i < outline.size(); i++) {
            BlockPos a = outline.get(i), b = outline.get((i + 1) % outline.size());
            area += (long) a.x() * b.z() - (long) b.x() * a.z();
        }
        return area;
    }

    /** Twice the area of the surface's triangles. */
    private static long triangleArea(Surface surface) {
        int[] t = surface.triangles();
        long area = 0;
        for (int k = 0; k < t.length; k += 3) {
            BlockPos a = surface.sites().get(t[k]), b = surface.sites().get(t[k + 1]), c = surface.sites().get(t[k + 2]);
            area += PlaneTriangulation.orient(a.x(), a.z(), b.x(), b.z(), c.x(), c.z());
        }
        return area;
    }

    @Test
    void straightCornersAreLeftOutOfTheCutAndPutBackOnTheirEdges() {
        // A square with three points on each side (straight corners) and one on a straight run of two.
        long[] xs = {0, 2, 4, 6, 8, 8, 8, 4, 0, 0};
        long[] zs = {0, 0, 0, 0, 0, 4, 8, 8, 8, 4};
        int[] ring = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        assertArrayEquals(new int[] {0, 4, 6, 8}, SmartSurfaceKernel.withoutStraightCorners(xs, zs, ring));
        List<BlockPos> outline = new ArrayList<>();
        for (int i = 0; i < xs.length; i++) outline.add(new BlockPos((int) xs[i], 64 + i, (int) zs[i]));
        for (boolean faceted : new boolean[] {false, true}) {
            Surface surface = surface(outline, points(4, 70, 4), faceted);
            assertTrue(surface.simpleOutline());
            assertEquals(Math.abs(shoelace(outline)), triangleArea(surface), "the square's own triangles");
            for (BlockPos p : outline) assertEquals(p.y(), surface.surfaceY(p.x(), p.z()));
            for (int i = 0; i < outline.size(); i++) {
                assertTrue(hasEdge(surface, outline.get(i), outline.get((i + 1) % outline.size())),
                        "edge " + i + " is a triangle edge");
            }
        }
    }

    @Test
    void manyInsidePointsLeaveTheRimMeetingTheSheetWithoutAStep() {
        // A 100 x 100 square at y 64 and a grid of inside points at y 90 at least 20 blocks from the rim: the rim may
        // climb no faster than the points 20 blocks in imply (26 / 20), however many points there are.
        List<BlockPos> outline = points(0, 64, 0, 99, 64, 0, 99, 64, 99, 0, 64, 99);
        int lastStep = -1;
        for (int per : new int[] {4, 8, 14}) {
            List<BlockPos> inside = new ArrayList<>();
            for (int i = 0; i < per; i++) {
                for (int j = 0; j < per; j++) {
                    inside.add(new BlockPos(20 + i * 60 / (per - 1), 90, 20 + j * 60 / (per - 1)));
                }
            }
            Surface surface = surface(outline, inside, false);
            int step = 0;
            for (int t = 1; t < 99; t++) {
                step = Math.max(step, Math.abs(surface.surfaceY(1, t) - surface.surfaceY(0, t)));
                step = Math.max(step, Math.abs(surface.surfaceY(t, 1) - surface.surfaceY(t, 0)));
                step = Math.max(step, Math.abs(surface.surfaceY(98, t) - surface.surfaceY(99, t)));
                step = Math.max(step, Math.abs(surface.surfaceY(t, 98) - surface.surfaceY(t, 99)));
            }
            assertTrue(step <= 2, per * per + " points: a step of " + step + " at the rim");
            if (lastStep >= 0) assertTrue(step <= lastStep + 1, "more points barely change the rim");
            lastStep = step;
            for (BlockPos p : inside) assertEquals(90, surface.surfaceY(p.x(), p.z()));
        }
    }

    private static List<BlockPos> dedupe(List<BlockPos> outline) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos p : outline) if (out.isEmpty() || !out.get(out.size() - 1).equals(p)) out.add(p);
        while (out.size() > 1 && out.get(0).equals(out.get(out.size() - 1))) out.remove(out.size() - 1);
        return out;
    }

    // ---------------------------------------------------------------- fill

    /** A steep ramp: 0 to 40 over 10 columns. */
    private static Surface steep() {
        return surface(points(0, 60, 0, 10, 100, 0, 10, 100, 6, 0, 60, 6), List.of(), true);
    }

    @Test
    void gapFreeColumnsReachTheirLowestNeighbour() {
        Surface surface = steep();
        Map<Long, int[]> spans = spans(cells(surface, Fill.of(TOP)));
        int checked = 0;
        for (long column : surface.columns()) {
            int x = GroundMap.columnX(column), z = GroundMap.columnZ(column);
            int[] span = spans.get(column);
            assertEquals(surface.surfaceY(x, z), span[1]);
            for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                if (!surface.covers(x + d[0], z + d[1])) continue;
                assertTrue(span[0] <= surface.surfaceY(x + d[0], z + d[1]) + 1, "no gap beside " + x + "," + z);
                checked++;
            }
        }
        assertTrue(checked > 100);
        // Without gap-free a steep sheet one thick has holes in its face.
        Fill thin = new Fill(TOP, 1, BODY, false, 1, false);
        Map<Long, int[]> holes = spans(cells(surface, thin));
        boolean gap = false;
        for (long column : surface.columns()) {
            int x = GroundMap.columnX(column), z = GroundMap.columnZ(column);
            int[] span = holes.get(column);
            assertEquals(span[0], span[1]);
            if (surface.covers(x - 1, z) && span[0] > surface.surfaceY(x - 1, z) + 1) gap = true;
        }
        assertTrue(gap);
    }

    @Test
    void thicknessAndLayers() {
        Surface surface = surface(points(0, 70, 0, 6, 70, 0, 6, 70, 6, 0, 70, 6), List.of(), false);
        GeneratedSource source = cells(surface, new Fill(TOP, 2, BODY, false, 5, true));
        assertEquals(49L * 5, source.cells());
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 6; z++) {
                assertEquals(1, source.get(x, 70, z));
                assertEquals(1, source.get(x, 69, z));
                assertEquals(2, source.get(x, 68, z));
                assertEquals(2, source.get(x, 66, z));
                assertEquals(-1, source.get(x, 65, z));
                assertEquals(-1, source.get(x, 71, z));
            }
        }
        // Depth 0: all body.
        GeneratedSource body = cells(surface, new Fill(TOP, 0, BODY, false, 2, false));
        body.forEach((x, y, z, state) -> assertEquals(2, state));
    }

    @Test
    void extendToGroundFillsDownToTheTerrain() {
        Surface surface = surface(points(0, 70, 0, 4, 70, 0, 4, 70, 4, 0, 70, 4), List.of(), false);
        GroundMap ground = new GroundMap();
        for (long column : surface.columns()) {
            int x = GroundMap.columnX(column), z = GroundMap.columnZ(column);
            ground.put(x, z, 60 + x);
        }
        ground.put(4, 4, 75);                        // ground above the sheet: the surface cell only
        ground.put(3, 4, SurfaceReader.NONE);        // no ground: the sheet's thickness
        ground.put(2, 4, SurfaceReader.UNLOADED);    // unknown: left out, listed
        Fill fill = new Fill(TOP, 1, BODY, true, 3, false);
        SmartSurfaceKernel.Result result = SmartSurfaceKernel.generate(surface, fill, ground, BOTTOM, TOP_Y, 10_000);
        Map<Long, int[]> spans = spans(result.source());
        assertArrayEquals(new long[] {GroundMap.column(2, 4)}, result.unloaded());
        assertFalse(spans.containsKey(GroundMap.column(2, 4)));
        assertArrayEquals(new int[] {70, 70}, spans.get(GroundMap.column(4, 4)));
        assertArrayEquals(new int[] {68, 70}, spans.get(GroundMap.column(3, 4)));
        for (int x = 0; x <= 4; x++) {
            for (int z = 0; z <= 3; z++) assertArrayEquals(new int[] {61 + x, 70}, spans.get(GroundMap.column(x, z)));
        }
        assertEquals(1, result.source().get(0, 70, 0));
        assertEquals(2, result.source().get(0, 61, 0));
        assertEquals(-1, result.source().get(0, 60, 0));
        assertThrows(NullPointerException.class,
                () -> SmartSurfaceKernel.generate(surface, fill, null, BOTTOM, TOP_Y, 10_000));
    }

    @Test
    void cellsOutsideTheBuildHeightAreDropped() {
        Surface surface = surface(points(0, 0, 0, 8, 0, 0, 8, 0, 8, 0, 0, 8), List.of(), true);
        GeneratedSource source = SmartSurfaceKernel.generate(surface, new Fill(TOP, 1, BODY, false, 4, false), null,
                -1, 0, Long.MAX_VALUE).source();
        assertEquals(81, source.cells());
        source.forEach((x, y, z, state) -> assertEquals(-1, y));
    }

    @Test
    void overTheCapIsRefusedBeforeAnyCell() {
        Surface surface = surface(points(0, 70, 0, 9, 70, 0, 9, 70, 9, 0, 70, 9), List.of(), true);
        Fill fill = new Fill(TOP, 1, BODY, false, 2, false);
        assertEquals(200, SmartSurfaceKernel.generate(surface, fill, null, BOTTOM, TOP_Y, 200).source().cells());
        GeneratedTooLargeException e = assertThrows(GeneratedTooLargeException.class,
                () -> SmartSurfaceKernel.generate(surface, fill, null, BOTTOM, TOP_Y, 199));
        assertEquals(199, e.maxCells());
    }

    @Test
    void fillsTakeBlocksAndMixesOnly() {
        Pattern.Weighted mix = new Pattern.Weighted(new int[] {1, 2}, new int[] {1, 1}, 7);
        new Fill(new Pattern.Arranged(mix, new MixLayout.Patches(4)), 1, mix, false, 1, true);
        assertThrows(IllegalArgumentException.class,
                () -> new Fill(new Pattern.Arranged(mix, new MixLayout.Steepness(10)), 1, BODY, false, 1, true));
        assertThrows(IllegalArgumentException.class, () -> new Fill(new Pattern.KeepShape(TOP), 1, BODY, false, 1, true));
        assertThrows(IllegalArgumentException.class, () -> new Fill(TOP, 1, new Pattern.Dry(), false, 1, true));
        assertThrows(IllegalArgumentException.class, () -> new Fill(TOP, -1, BODY, false, 1, true));
        assertThrows(IllegalArgumentException.class,
                () -> new Fill(TOP, SmartSurfaceKernel.MAX_TOP_DEPTH + 1, BODY, false, 1, true));
        assertThrows(IllegalArgumentException.class, () -> new Fill(TOP, 1, BODY, false, 0, true));
        assertThrows(IllegalArgumentException.class,
                () -> new Fill(TOP, 1, BODY, false, SmartSurfaceKernel.MAX_THICKNESS + 1, true));
    }

    // ---------------------------------------------------------------- determinism, cost, golden

    @Test
    void theSameInputGivesTheSameCells() {
        List<BlockPos> outline = points(0, 64, 0, 20, 70, -3, 28, 61, 14, 15, 80, 25, -4, 66, 18);
        List<BlockPos> inside = points(10, 90, 10, 18, 58, 8);
        Pattern.Weighted mix = new Pattern.Weighted(new int[] {3, 4, 5}, new int[] {2, 1, 1}, 99);
        Fill fill = new Fill(mix, 2, BODY, false, 2, true);
        for (boolean faceted : new boolean[] {false, true}) {
            GeneratedSource a = cells(surface(outline, inside, faceted), fill);
            GeneratedSource b = cells(surface(outline, inside, faceted), fill);
            assertEquals(a.cells(), b.cells());
            a.forEach((x, y, z, state) -> assertEquals(state, b.get(x, y, z)));
        }
    }

    @Test
    void sixtyFourPointsOverTwoHundredFiftySixSquareStayCheap() {
        List<BlockPos> outline = new ArrayList<>();
        for (int k = 0; k < 48; k++) {
            double angle = 2 * Math.PI * k / 48;
            outline.add(new BlockPos((int) Math.round(127.5 + 127.5 * Math.cos(angle)),
                    64 + (int) Math.round(12 * Math.sin(3 * angle)), (int) Math.round(127.5 + 127.5 * Math.sin(angle))));
        }
        List<BlockPos> inside = new ArrayList<>();
        for (int k = 0; k < 16; k++) inside.add(new BlockPos(60 + (k % 4) * 40, 70 + 7 * (k % 5), 60 + (k / 4) * 40));
        Fill fill = new Fill(TOP, 1, BODY, false, 1, true);
        long[] best = {Long.MAX_VALUE, Long.MAX_VALUE};
        long cells = 0;
        for (int run = 0; run < 3; run++) {
            for (int mode = 0; mode < 2; mode++) {
                long start = System.nanoTime();
                Surface surface = surface(outline, inside, mode == 1);
                cells = cells(surface, fill).cells();
                best[mode] = Math.min(best[mode], System.nanoTime() - start);
                assertEquals(256, surface.sizeX());
                assertTrue(surface.columnCount() > 50_000);
            }
        }
        System.out.printf("Smart Surface 64 points, 256x256: smooth %.1f ms, faceted %.1f ms, %d cells%n",
                best[0] / 1e6, best[1] / 1e6, cells);
        assertTrue(best[0] < 3_000_000_000L, "smooth took " + best[0] / 1e6 + " ms");
        assertTrue(best[1] < 1_000_000_000L, "faceted took " + best[1] / 1e6 + " ms");
    }

    @Test
    void theLargestSurfaceStaysWithinSeconds() {
        // The caps at once: 256 points over 512 x 512 columns, smooth.
        int side = SmartSurfaceKernel.MAX_SIDE;
        List<BlockPos> outline = new ArrayList<>();
        for (int k = 0; k < 192; k++) {
            double angle = 2 * Math.PI * k / 192;
            outline.add(new BlockPos((int) Math.round((side - 1) / 2.0 * (1 + Math.cos(angle))),
                    64 + (int) Math.round(20 * Math.sin(5 * angle)),
                    (int) Math.round((side - 1) / 2.0 * (1 + Math.sin(angle)))));
        }
        List<BlockPos> inside = new ArrayList<>();
        for (int k = 0; k < 64; k++) inside.add(new BlockPos(110 + (k % 8) * 40, 60 + 9 * (k % 7), 110 + (k / 8) * 40));
        long start = System.nanoTime();
        Surface surface = surface(outline, inside, false);
        long cells = cells(surface, new Fill(TOP, 1, BODY, false, 1, true)).cells();
        long nanos = System.nanoTime() - start;
        System.out.printf("Smart Surface 256 points, 512x512: smooth %.1f ms, %d columns, %d cells%n", nanos / 1e6,
                surface.columnCount(), cells);
        assertEquals(256, surface.sites().size());
        assertTrue(nanos < 10_000_000_000L, "took " + nanos / 1e6 + " ms");
    }

    @Test
    void goldenSmallSurface() {
        List<BlockPos> outline = points(0, 64, 0, 8, 64, 0, 8, 70, 8, 0, 66, 8);
        List<BlockPos> inside = points(4, 72, 4);
        assertEquals(GOLDEN_FACETED, render(surface(outline, inside, true)));
        assertEquals(GOLDEN_SMOOTH, render(surface(outline, inside, false)));
        assertArrayEquals(new int[] {0, 1, 4, 0, 4, 3, 1, 2, 4, 2, 3, 4},
                sortTriangles(surface(outline, inside, true).triangles()));
    }

    private static String render(Surface surface) {
        StringBuilder out = new StringBuilder();
        for (int z = surface.minZ(); z < surface.minZ() + surface.sizeZ(); z++) {
            for (int x = surface.minX(); x < surface.minX() + surface.sizeX(); x++) {
                out.append(x == surface.minX() ? "" : " ").append(surface.covers(x, z) ? surface.surfaceY(x, z) : "..");
            }
            out.append('\n');
        }
        return out.toString();
    }

    /** Each triangle rotated to start at its lowest index, then triangles sorted. */
    private static int[] sortTriangles(int[] t) {
        List<int[]> list = new ArrayList<>();
        for (int k = 0; k < t.length; k += 3) {
            int[] tri = {t[k], t[k + 1], t[k + 2]};
            while (tri[0] != Math.min(tri[0], Math.min(tri[1], tri[2]))) tri = new int[] {tri[1], tri[2], tri[0]};
            list.add(tri);
        }
        list.sort((a, b) -> a[0] != b[0] ? a[0] - b[0] : a[1] != b[1] ? a[1] - b[1] : a[2] - b[2]);
        int[] out = new int[t.length];
        for (int i = 0; i < list.size(); i++) System.arraycopy(list.get(i), 0, out, 3 * i, 3);
        return out;
    }

    private static final String GOLDEN_FACETED = """
            64 64 64 64 64 64 64 64 64
            64 66 66 66 66 66 66 66 65
            65 66 68 68 68 68 68 67 66
            65 67 68 70 70 70 69 68 66
            65 67 69 70 72 71 70 68 67
            65 67 69 71 71 72 70 69 68
            66 67 69 70 70 71 71 70 69
            66 68 68 69 69 70 70 71 69
            66 67 67 68 68 69 69 70 70
            """;
    private static final String GOLDEN_SMOOTH = """
            64 64 64 64 64 64 64 64 64
            64 65 65 65 66 66 65 65 65
            65 65 67 68 69 68 67 66 66
            65 66 68 71 71 71 69 67 66
            65 66 69 71 72 72 70 68 67
            65 67 69 71 72 71 70 68 68
            66 67 68 69 70 70 69 69 69
            66 67 67 68 69 69 69 69 69
            66 67 67 68 68 69 69 70 70
            """;
}
