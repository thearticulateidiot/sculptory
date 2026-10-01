package dev.sculptory.fabric.client.editor.tools.select;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LassoSelectTest {
    private static final int BOTTOM = -64;
    private static final int TOP = 320;
    private static final long NO_CAP = SelectSettings.MAX_CELLS;

    private static LassoSelect loop(int layerY, double... xz) {
        LassoSelect lasso = new LassoSelect(layerY, xz[0], xz[1]);
        for (int i = 2; i < xz.length; i += 2) lasso.addPoint(xz[i], xz[i + 1]);
        return lasso;
    }

    /** The even-odd test on every cell centre of a generous box around the loop, layer {@code y}. */
    private static Set<BlockPos> bruteForce(LassoSelect lasso, int y) {
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (int i = 0; i < lasso.size(); i++) {
            minX = Math.min(minX, lasso.pointX(i));
            maxX = Math.max(maxX, lasso.pointX(i));
            minZ = Math.min(minZ, lasso.pointZ(i));
            maxZ = Math.max(maxZ, lasso.pointZ(i));
        }
        Set<BlockPos> inside = new HashSet<>();
        for (int x = (int) Math.floor(minX) - 2; x <= (int) Math.floor(maxX) + 2; x++) {
            for (int z = (int) Math.floor(minZ) - 2; z <= (int) Math.floor(maxZ) + 2; z++) {
                if (lasso.contains(x + 0.5, z + 0.5)) inside.add(new BlockPos(x, y, z));
            }
        }
        return inside;
    }

    @Test
    void aSquareLoopSelectsTheCellsWhoseCentresItEncloses() {
        LassoSelect lasso = loop(60, 0.5, 0.5, 10.5, 0.5, 10.5, 10.5, 0.5, 10.5);
        LassoSelect.Result result = lasso.rasterise(1, BOTTOM, TOP, NO_CAP);
        Set<BlockPos> expected = new HashSet<>();
        for (int x = 0; x <= 9; x++) {
            for (int z = 0; z <= 9; z++) expected.add(new BlockPos(x, 60, z));
        }
        assertEquals(expected, CellSets.cells(result.cells()));
        assertEquals(expected, bruteForce(lasso, 60));
        assertFalse(result.hitLimit());
    }

    @Test
    void rasterisationMatchesTheEvenOddTestOnRandomAndSelfIntersectingLoops() {
        Random random = new Random(20260928);
        for (int trial = 0; trial < 200; trial++) {
            int points = 3 + random.nextInt(10);
            LassoSelect lasso = new LassoSelect(0, spread(random), spread(random));
            for (int i = 1; i < points; i++) lasso.addPoint(spread(random), spread(random));
            if (!lasso.closed()) continue;
            Set<BlockPos> cells = CellSets.cells(lasso.rasterise(1, BOTTOM, TOP, NO_CAP).cells());
            assertEquals(bruteForce(lasso, 0), cells, "trial " + trial);
        }
    }

    /** A coordinate in about ±24 blocks, sometimes on a cell centre or edge to exercise the ties. */
    private static double spread(Random random) {
        double value = random.nextDouble() * 48 - 24;
        return switch (random.nextInt(4)) {
            case 0 -> Math.floor(value) + 0.5;
            case 1 -> Math.floor(value);
            default -> value;
        };
    }

    @Test
    void aPentagramLeavesItsCentreOutByTheEvenOddRule() {
        // The five tips of a star drawn in one stroke: the centre pentagon is inside twice, so outside.
        double[] xz = new double[10];
        for (int i = 0; i < 5; i++) {
            double angle = Math.PI / 2 + i * 4 * Math.PI / 5; // every second vertex
            xz[2 * i] = 20 + 15 * Math.cos(angle);
            xz[2 * i + 1] = 20 + 15 * Math.sin(angle);
        }
        LassoSelect lasso = loop(0, xz);
        Set<BlockPos> cells = CellSets.cells(lasso.rasterise(1, BOTTOM, TOP, NO_CAP).cells());
        assertEquals(bruteForce(lasso, 0), cells);
        assertFalse(cells.contains(new BlockPos(20, 0, 20)), "the centre of the star");
        assertTrue(cells.contains(new BlockPos(20, 0, 32)), "the middle of a tip");
        assertTrue(cells.size() > 50);
    }

    @Test
    void degenerateLoopsSelectNothing() {
        LassoSelect two = loop(60, 0.5, 0.5, 20.5, 0.5);
        assertFalse(two.closed());
        assertTrue(two.rasterise(1, BOTTOM, TOP, NO_CAP).cells().isEmpty());
        assertEquals(0, two.atMost(5, BOTTOM, TOP));

        LassoSelect collinear = loop(60, 0.5, 0.5, 10.5, 0.5, 20.5, 0.5);
        assertTrue(collinear.closed());
        assertTrue(collinear.rasterise(1, BOTTOM, TOP, NO_CAP).cells().isEmpty(), "no area, no cell");

        LassoSelect tiny = loop(60, 0.1, 0.1, 0.4, 0.1, 0.4, 0.4); // encloses no cell centre
        assertTrue(tiny.rasterise(1, BOTTOM, TOP, NO_CAP).cells().isEmpty());
    }

    @Test
    void pointsAreDecimatedAndClamped() {
        LassoSelect lasso = new LassoSelect(0, 0, 0);
        assertFalse(lasso.addPoint(0.2, 0.1), "closer than a quarter block to the previous point");
        assertEquals(1, lasso.size());
        assertTrue(lasso.addPoint(0.25, 0));
        assertEquals(2, lasso.size());
        assertFalse(lasso.addPoint(Double.NaN, 0));
        assertFalse(lasso.addPoint(0, Double.POSITIVE_INFINITY));
        assertEquals(2, lasso.size());

        assertTrue(lasso.addPoint(3000, 4000), "far away, clamped");
        assertEquals(512 * 0.6, lasso.pointX(2), 1e-9);
        assertEquals(512 * 0.8, lasso.pointZ(2), 1e-9);
        assertTrue(lasso.addPoint(-600, 0));
        assertEquals(-512, lasso.pointX(3), 1e-9);
        assertEquals(0, lasso.pointZ(3), 1e-9);
        assertFalse(lasso.addPoint(-700, 0.1), "clamped onto the previous point: dropped");

        for (int i = 0; i < 500; i++) assertTrue(lasso.addPoint(0.5 * i, 100 + 0.5 * i), "the point list grows");
        assertEquals(504, lasso.size());
        assertThrows(IndexOutOfBoundsException.class, () -> lasso.pointX(504));
    }

    @Test
    void heightExtrudesUpwardWithinTheBuildHeight() {
        LassoSelect lasso = loop(60, 0.5, 0.5, 4.5, 0.5, 4.5, 4.5, 0.5, 4.5); // 16 cells a layer
        assertEquals(16, lasso.rasterise(1, BOTTOM, TOP, NO_CAP).cells().size());
        assertEquals(48, lasso.rasterise(3, BOTTOM, TOP, NO_CAP).cells().size());
        Set<BlockPos> cells = CellSets.cells(lasso.rasterise(3, BOTTOM, TOP, NO_CAP).cells());
        for (BlockPos cell : cells) assertTrue(cell.y() >= 60 && cell.y() <= 62, cell.toString());
        assertEquals(25 * 3, lasso.atMost(3, BOTTOM, TOP), "at most the columns the loop touches times the layers");
        assertEquals(16 * 260, lasso.rasterise(384, BOTTOM, TOP, NO_CAP).cells().size(), "clamped to y 319");
        assertEquals(25 * 260, lasso.atMost(384, BOTTOM, TOP));

        LassoSelect top = loop(319, 0.5, 0.5, 4.5, 0.5, 4.5, 4.5, 0.5, 4.5);
        assertEquals(16, top.rasterise(10, BOTTOM, TOP, NO_CAP).cells().size(), "only the top layer is left");
        assertEquals(320.0, top.planeY());
        assertEquals(319, top.layerY());

        LassoSelect deep = loop(-70, 0.5, 0.5, 4.5, 0.5, 4.5, 4.5, 0.5, 4.5);
        assertEquals(16 * 4, deep.rasterise(10, BOTTOM, TOP, NO_CAP).cells().size(), "layers below the world are gone");

        LassoSelect above = loop(320, 0.5, 0.5, 4.5, 0.5, 4.5, 4.5, 0.5, 4.5);
        assertTrue(above.rasterise(10, BOTTOM, TOP, NO_CAP).cells().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> lasso.rasterise(0, BOTTOM, TOP, NO_CAP));
    }

    @Test
    void theCapCutsTheCellsOffExactly() {
        LassoSelect lasso = loop(60, 0.5, 0.5, 10.5, 0.5, 10.5, 10.5, 0.5, 10.5); // 100 cells a layer
        LassoSelect.Result cut = lasso.rasterise(5, BOTTOM, TOP, 234);
        assertEquals(234, cut.cells().size());
        assertTrue(cut.hitLimit());
        LassoSelect.Result exact = lasso.rasterise(5, BOTTOM, TOP, 500);
        assertEquals(500, exact.cells().size());
        assertFalse(exact.hitLimit(), "an exact fit is not cut off");
        LassoSelect.Result rows = lasso.rasterise(1, BOTTOM, TOP, 25);
        assertEquals(25, rows.cells().size(), "cut mid-row");
        assertTrue(rows.hitLimit());
        assertThrows(IllegalArgumentException.class, () -> lasso.rasterise(1, BOTTOM, TOP, 0));
    }

    @Test
    void atMostBoundsTheCellsOfAnyLoop() {
        Random random = new Random(7);
        for (int trial = 0; trial < 50; trial++) {
            LassoSelect lasso = new LassoSelect(0, spread(random), spread(random));
            for (int i = 1; i < 8; i++) lasso.addPoint(spread(random), spread(random));
            int height = 1 + random.nextInt(5);
            assertTrue(lasso.rasterise(height, BOTTOM, TOP, NO_CAP).cells().size() <= lasso.atMost(height, BOTTOM, TOP));
        }
    }
}
