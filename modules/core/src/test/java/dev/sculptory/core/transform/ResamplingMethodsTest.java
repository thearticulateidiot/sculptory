package dev.sculptory.core.transform;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The three ways a free orientation fills its result box ({@link CellResampler}): exact count is a bijection that keeps
 * the block count, smooth keeps solids solid and thin walls whole, and all three agree with the nearest resampler on
 * quarter turns; an even-sized box turns about its true centre.
 */
class ResamplingMethodsTest {
    private static final int Q = Orientation.QUARTER_TURN;

    /** Random free orientations (random exact parts too) and sizes, the same every run. */
    private static List<Object[]> cases(long seed, int n, int maxSide) {
        Random random = new Random(seed);
        List<Object[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int[] size = {1 + random.nextInt(maxSide), 1 + random.nextInt(maxSide), 1 + random.nextInt(maxSide)};
            Orientation o = Orientation.free(Transform.allWithFlips().get(random.nextInt(16)),
                    random.nextInt(Orientation.FULL_TURN), random.nextInt(4) == 0 ? 0 : random.nextInt(Orientation.FULL_TURN),
                    random.nextInt(4) == 0 ? 0 : random.nextInt(Orientation.FULL_TURN));
            if (o.isExact()) continue;
            out.add(new Object[] {o, size});
        }
        return out;
    }

    // ---------------------------------------------------------------- exact count

    @Test
    void exactCountLandsEverySourceCellOnItsOwnResultCell() {
        for (Object[] c : cases(11, 400, 11)) {
            Orientation o = (Orientation) c[0];
            int[] size = (int[]) c[1];
            ShearTurn exact = new ShearTurn(o, size[0], size[1], size[2]);
            BlockPos box = exact.size();
            Set<BlockPos> landed = new HashSet<>();
            int[] to = new int[3];
            int[] back = new int[3];
            for (int x = 0; x < size[0]; x++) {
                for (int y = 0; y < size[1]; y++) {
                    for (int z = 0; z < size[2]; z++) {
                        exact.land(x, y, z, to);
                        String what = o + " " + List.of(size[0], size[1], size[2]) + " cell " + x + "," + y + "," + z;
                        assertTrue(to[0] >= 0 && to[1] >= 0 && to[2] >= 0 && to[0] < box.x() && to[1] < box.y()
                                && to[2] < box.z(), what + " landed outside the result box " + box + ": "
                                + List.of(to[0], to[1], to[2]));
                        assertTrue(landed.add(new BlockPos(to[0], to[1], to[2])), what + ": two cells landed together");
                        assertTrue(exact.source(to[0], to[1], to[2], CellResampler.ALL, back), what);
                        assertArrayEquals(new int[] {x, y, z}, back, what + ": source is the inverse of land");
                    }
                }
            }
            // No result cell takes a cell that did not land on it: the count is kept exactly.
            long taken = 0;
            for (int x = 0; x < box.x(); x++) {
                for (int y = 0; y < box.y(); y++) {
                    for (int z = 0; z < box.z(); z++) {
                        if (exact.source(x, y, z, CellResampler.ALL, back)) taken++;
                    }
                }
            }
            assertEquals((long) size[0] * size[1] * size[2], taken, o + " " + List.of(size[0], size[1], size[2]));
        }
    }

    @Test
    void exactCountStaysWithinItsBoundOfTheTrueRotation() {
        for (Object[] c : cases(12, 300, 12)) {
            Orientation o = (Orientation) c[0];
            int[] size = (int[]) c[1];
            ShearTurn exact = new ShearTurn(o, size[0], size[1], size[2]);
            int[] to = new int[3];
            double[] ideal = new double[3];
            double worst = 0;
            for (int x = 0; x < size[0]; x++) {
                for (int y = 0; y < size[1]; y++) {
                    for (int z = 0; z < size[2]; z++) {
                        exact.land(x, y, z, to);
                        exact.forward(x + 0.5, y + 0.5, z + 0.5, ideal);
                        for (int i = 0; i < 3; i++) {
                            double off = Math.abs(to[i] + 0.5 - ideal[i]);
                            assertTrue(off <= exact.error(i) + 1e-6, o + " axis " + i + ": " + off + " > "
                                    + exact.error(i));
                            worst = Math.max(worst, off);
                        }
                    }
                }
            }
            // Three single-axis turns of three shears each: never more than about four blocks off.
            assertTrue(worst <= 4, o + ": " + worst);
        }
    }

    @Test
    void aSingleAxisExactTurnStaysWithinABlockAndAHalf() {
        int[] angles = {1_000, 3_000, 4_500, 6_000, 13_000, 22_000, 31_500};
        for (int angle : angles) {
            for (int axis = 0; axis < 3; axis++) {
                Orientation o = Orientation.free(Transform.IDENTITY, axis == 0 ? angle : 0, axis == 1 ? angle : 0,
                        axis == 2 ? angle : 0);
                ShearTurn exact = new ShearTurn(o, 16, 16, 16);
                for (int i = 0; i < 3; i++) assertTrue(exact.error(i) <= 1.21, o + " axis " + i + " " + exact.error(i));
            }
        }
    }

    @Test
    void quarterTurnsResampleIdenticallyInEveryWay() {
        int[][] sizes = {{1, 1, 1}, {2, 3, 4}, {4, 4, 4}, {5, 2, 3}, {6, 5, 2}, {3, 3, 3}};
        for (Transform t : List.of(Transform.IDENTITY, new Transform(1, Mirror.X, true), new Transform(3, Mirror.Z))) {
            for (int yaw = 0; yaw < 4; yaw++) {
                for (int pitch = 0; pitch < 4; pitch++) {
                    for (int roll = 0; roll < 4; roll++) {
                        Orientation o = Orientation.free(t, yaw * Q, pitch * Q, roll * Q);
                        if (o.isExact()) continue;
                        for (int[] s : sizes) {
                            CellResampler nearest = CellResampler.nearest(o, s[0], s[1], s[2]);
                            for (CellResampler other : List.of(CellResampler.exact(o, s[0], s[1], s[2]),
                                    CellResampler.smooth(o, s[0], s[1], s[2]))) {
                                assertSame(nearest, other, o + " " + List.of(s[0], s[1], s[2]));
                            }
                        }
                    }
                }
            }
        }
    }

    /** Both fill the same result box with the same cells (every source cell present and solid). */
    private static void assertSame(CellResampler a, CellResampler b, String what) {
        assertEquals(a.size(), b.size(), what + " size");
        assertEquals(a.offset(), b.offset(), what + " offset");
        BlockPos s = a.size();
        int[] ca = new int[3];
        int[] cb = new int[3];
        for (int x = 0; x < s.x(); x++) {
            for (int y = 0; y < s.y(); y++) {
                for (int z = 0; z < s.z(); z++) {
                    boolean ta = a.source(x, y, z, CellResampler.ALL, ca);
                    boolean tb = b.source(x, y, z, CellResampler.ALL, cb);
                    assertEquals(ta, tb, what + " at " + x + "," + y + "," + z);
                    if (ta) assertArrayEquals(ca, cb, what + " at " + x + "," + y + "," + z);
                }
            }
        }
    }

    @Test
    void exactCountIsDeterministicAndPinned() {
        Orientation o = Orientation.free(new Transform(1, Mirror.X), 3_000, 2_000, 1_234);
        ShearTurn a = new ShearTurn(o, 9, 7, 5);
        ShearTurn b = new ShearTurn(o, 9, 7, 5);
        long hash = 17;
        int[] to = new int[3];
        int[] again = new int[3];
        for (int x = 0; x < 9; x++) {
            for (int y = 0; y < 7; y++) {
                for (int z = 0; z < 5; z++) {
                    a.land(x, y, z, to);
                    b.land(x, y, z, again);
                    assertArrayEquals(to, again);
                    hash = 31 * hash + ((to[0] * 1_000L + to[1]) * 1_000L + to[2]);
                }
            }
        }
        assertEquals(a.size(), b.size());
        assertEquals(a.offset(), b.offset());
        // Pinned: StrictMath and the octant-symmetric sines make this the same on every machine (client and server).
        assertEquals(EXACT_HASH, hash, "the exact-count map of the pinned case changed");
    }

    private static final long EXACT_HASH = -2596092782187261752L;

    // ---------------------------------------------------------------- the centre

    @Test
    void anEvenSizedBuildTurnsAboutItsTrueCentre() {
        // A 4x1x4 slab turned 45°: the cells it fills are symmetric under a quarter turn about the slab's middle.
        for (CellResampler r : List.of(CellResampler.nearest(Orientation.free(Transform.IDENTITY, 4_500, 0, 0), 4, 1, 4),
                CellResampler.smooth(Orientation.free(Transform.IDENTITY, 4_500, 0, 0), 4, 1, 4))) {
            Set<List<Integer>> filled = filled(r);
            BlockPos s = r.size();
            BlockPos o = r.offset();
            // About the box centre (2, 2): cell (x, z) goes to (3 - z, x) in intermediate coordinates (doubled: centre 4).
            for (List<Integer> cell : filled) {
                int ix = cell.get(0) + o.x(), iz = cell.get(2) + o.z();
                int qx = 3 - iz, qz = ix;
                assertTrue(filled.contains(List.of(qx - o.x(), cell.get(1), qz - o.z())), r.orientation() + ": " + cell
                        + " has no quarter-turned twin in " + s);
            }
        }
        // The true centre stays put: the mean of the filled cells' centres is the box's centre (2, 2).
        CellResampler r = CellResampler.nearest(Orientation.free(Transform.IDENTITY, 3_000, 0, 0), 6, 1, 4);
        double mx = 0, mz = 0;
        Set<List<Integer>> filled = filled(r);
        for (List<Integer> cell : filled) {
            mx += cell.get(0) + r.offset().x() + 0.5;
            mz += cell.get(2) + r.offset().z() + 0.5;
        }
        assertEquals(3.0, mx / filled.size(), 0.35);
        assertEquals(2.0, mz / filled.size(), 0.35);
    }

    @Test
    void aPivotStaysAWholeBlockOffsetAndOddSizesTurnAsBefore() {
        // An odd box's centre is its middle cell's: it stays where it is, as it always did.
        Orientation o = Orientation.free(Transform.IDENTITY, 3_700, 1_100, 0);
        BlockPos at = o.mapCell(2, 1, 3, 5, 3, 7);
        Orientation.Bounds b = o.bounds(5, 3, 7);
        assertEquals(new BlockPos(2 - b.offset().x(), 1 - b.offset().y(), 3 - b.offset().z()), at);
        // A quarter turn of pitch on an even/odd box lands on whole cells (half a block shifted where it must).
        Orientation pitch = Orientation.free(Transform.IDENTITY, 0, Q, 0);
        CellResampler r = CellResampler.nearest(pitch, 3, 4, 3);
        assertEquals(new BlockPos(3, 3, 4), r.size());
        assertEquals(36, filled(r).size());
    }

    private static Set<List<Integer>> filled(CellResampler r) {
        Set<List<Integer>> out = new HashSet<>();
        BlockPos s = r.size();
        int[] cell = new int[3];
        for (int x = 0; x < s.x(); x++) {
            for (int y = 0; y < s.y(); y++) {
                for (int z = 0; z < s.z(); z++) {
                    if (r.source(x, y, z, CellResampler.ALL, cell)) out.add(List.of(x, y, z));
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- smooth

    /** A source of blocks: {@code solid[x][y][z]} true for a block (state 1), else air (state 0), all present. */
    private record Grid(boolean[][][] solid) implements CellResampler.SourceCells {
        @Override
        public int state(int x, int y, int z) {
            return solid[x][y][z] ? 1 : 0;
        }

        @Override
        public boolean solid(int state) {
            return state == 1;
        }
    }

    private static boolean[][][] box(int sx, int sy, int sz) {
        boolean[][][] g = new boolean[sx][sy][sz];
        for (boolean[][] a : g) for (boolean[] b : a) java.util.Arrays.fill(b, true);
        return g;
    }

    @Test
    void smoothKeepsTheInsideOfATurnedSolidSolid() {
        int n = 9;
        Grid grid = new Grid(box(n, n, n));
        int[][] angles = {{3_000, 0, 0}, {4_500, 0, 0}, {6_000, 0, 0}, {4_500, 2_000, 0}, {1_000, 3_300, 2_500}};
        for (int[] a : angles) {
            Orientation o = Orientation.free(Transform.IDENTITY, a[0], a[1], a[2]);
            CellResampler smooth = CellResampler.smooth(o, n, n, n);
            BlockPos s = smooth.size();
            int[] cell = new int[3];
            double[] back = new double[3];
            int inside = 0;
            for (int x = 0; x < s.x(); x++) {
                for (int y = 0; y < s.y(); y++) {
                    for (int z = 0; z < s.z(); z++) {
                        smooth.back(x + 0.5, y + 0.5, z + 0.5, back);
                        boolean deep = back[0] >= 1 && back[1] >= 1 && back[2] >= 1 && back[0] <= n - 1
                                && back[1] <= n - 1 && back[2] <= n - 1;
                        boolean taken = smooth.source(x, y, z, grid, cell);
                        if (deep) {
                            inside++;
                            assertTrue(taken && grid.solid(grid.state(cell[0], cell[1], cell[2])),
                                    o + ": a hole inside at " + x + "," + y + "," + z);
                        }
                        // Nothing more than a cell outside the turned solid.
                        boolean far = back[0] < -0.9 || back[1] < -0.9 || back[2] < -0.9 || back[0] > n + 0.9
                                || back[1] > n + 0.9 || back[2] > n + 0.9;
                        if (far) assertFalse(taken, o + ": a block outside at " + x + "," + y + "," + z);
                    }
                }
            }
            assertTrue(inside > 100, o + ": " + inside);
        }
    }

    @Test
    void smoothKeepsAThinWallWhole() {
        // A wall 16 long, 4 tall and 1 thick, turned about the vertical: every layer stays one connected line (cells
        // touching by a face or a corner), from one end of the wall to the other, with no gap to see through.
        for (int yaw : new int[] {3_000, 4_500, 6_000, 1_500, 7_500}) {
            Orientation o = Orientation.free(Transform.IDENTITY, yaw, 0, 0);
            for (CellResampler r : List.of(CellResampler.smooth(o, 16, 4, 1), CellResampler.nearest(o, 16, 4, 1))) {
                Grid grid = new Grid(box(16, 4, 1));
                BlockPos s = r.size();
                for (int y = 0; y < s.y(); y++) {
                    Set<List<Integer>> layer = new HashSet<>();
                    int[] cell = new int[3];
                    for (int x = 0; x < s.x(); x++) {
                        for (int z = 0; z < s.z(); z++) {
                            if (r.source(x, y, z, grid, cell)) layer.add(List.of(x, z));
                        }
                    }
                    assertTrue(layer.size() >= 14, o + " " + r.getClass().getSimpleName() + " layer " + y + ": "
                            + layer.size());
                    assertTrue(connected(layer), o + " " + r.getClass().getSimpleName() + " layer " + y + " has a gap");
                    // The wall's ends: the cells nearest where its two ends land are both in the line.
                    double[] end = new double[3];
                    r.forward(0.5, y + 0.5, 0.5, end);
                    assertTrue(near(layer, end), o + " lost the wall's first end");
                    r.forward(15.5, y + 0.5, 0.5, end);
                    assertTrue(near(layer, end), o + " lost the wall's last end");
                }
            }
        }
    }

    private static boolean near(Set<List<Integer>> layer, double[] p) {
        for (List<Integer> c : layer) {
            if (Math.abs(c.get(0) + 0.5 - p[0]) <= 1 && Math.abs(c.get(1) + 0.5 - p[2]) <= 1) return true;
        }
        return false;
    }

    /** Whether the cells form one group, touching by an edge or a corner. */
    private static boolean connected(Set<List<Integer>> cells) {
        if (cells.isEmpty()) return true;
        Set<List<Integer>> seen = new HashSet<>();
        List<List<Integer>> stack = new ArrayList<>();
        List<Integer> start = cells.iterator().next();
        stack.add(start);
        seen.add(start);
        while (!stack.isEmpty()) {
            List<Integer> c = stack.remove(stack.size() - 1);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    List<Integer> n = List.of(c.get(0) + dx, c.get(1) + dz);
                    if (cells.contains(n) && seen.add(n)) stack.add(n);
                }
            }
        }
        return seen.size() == cells.size();
    }

    @Test
    void smoothTakesTheMostCommonBlockAndTiesGoToTheNearest() {
        // A tiny turn: all 27 points of a cell stay in its own source cell, so smooth is nearest exactly, block for block.
        Random random = new Random(3);
        int n = 7;
        int[][][] states = new int[n][n][n];
        for (int[][] a : states) for (int[] b : a) for (int i = 0; i < n; i++) b[i] = random.nextInt(4);
        CellResampler.SourceCells cells = new CellResampler.SourceCells() {
            @Override
            public int state(int x, int y, int z) {
                return states[x][y][z];
            }

            @Override
            public boolean solid(int state) {
                return state != 0;
            }
        };
        Orientation tiny = Orientation.free(Transform.IDENTITY, 1, 0, 0);
        CellResampler smooth = CellResampler.smooth(tiny, n, n, n);
        Orientation.Sampler nearest = tiny.sampler(n, n, n);
        BlockPos s = smooth.size();
        int[] a = new int[3];
        int[] b = new int[3];
        for (int x = 0; x < s.x(); x++) {
            for (int y = 0; y < s.y(); y++) {
                for (int z = 0; z < s.z(); z++) {
                    if (!nearest.source(x, y, z, b)) continue;
                    assertTrue(smooth.source(x, y, z, cells, a));
                    assertArrayEquals(b, a, "at " + x + "," + y + "," + z);
                }
            }
        }
        // A cell on the line between two blocks, half on each: the centre's block wins the tie.
        int[][][] two = new int[2][1][1];
        two[0][0][0] = 1;
        two[1][0][0] = 2;
        CellResampler.SourceCells pair = new CellResampler.SourceCells() {
            @Override
            public int state(int x, int y, int z) {
                return two[x][y][z];
            }

            @Override
            public boolean solid(int state) {
                return state != 0;
            }
        };
        CellResampler turned = CellResampler.smooth(Orientation.free(Transform.IDENTITY, 0, 0, 1), 2, 1, 1);
        Orientation.Sampler near = Orientation.free(Transform.IDENTITY, 0, 0, 1).sampler(2, 1, 1);
        BlockPos ts = turned.size();
        for (int x = 0; x < ts.x(); x++) {
            for (int y = 0; y < ts.y(); y++) {
                for (int z = 0; z < ts.z(); z++) {
                    if (!turned.source(x, y, z, pair, a)) continue;
                    assertTrue(near.source(x, y, z, b));
                    assertEquals(two[b[0]][0][0], two[a[0]][0][0]);
                }
            }
        }
    }

    @Test
    void smoothTakesAirWhereLessThanHalfIsBlockAndNothingOutsideTheSource() {
        // A 6x6x6 box of air with a single block in the middle: turned, the block's cell keeps it (most of its points
        // fall on it) and the others take air; cells beyond the source take nothing.
        boolean[][][] g = new boolean[6][6][6];
        g[3][3][3] = true;
        Grid grid = new Grid(g);
        CellResampler smooth = CellResampler.smooth(Orientation.free(Transform.IDENTITY, 2_000, 0, 0), 6, 6, 6);
        BlockPos s = smooth.size();
        int[] cell = new int[3];
        int blocks = 0, air = 0;
        for (int x = 0; x < s.x(); x++) {
            for (int y = 0; y < s.y(); y++) {
                for (int z = 0; z < s.z(); z++) {
                    if (!smooth.source(x, y, z, grid, cell)) continue;
                    if (grid.solid(grid.state(cell[0], cell[1], cell[2]))) blocks++;
                    else air++;
                }
            }
        }
        assertEquals(1, blocks);
        assertTrue(air > 150, "air " + air);
    }

    @Test
    void takersAreTheResultCellsWhoseSourceIsTheCell() {
        for (Object[] c : cases(13, 40, 6)) {
            Orientation o = (Orientation) c[0];
            int[] size = (int[]) c[1];
            for (CellResampler r : List.of(CellResampler.nearest(o, size[0], size[1], size[2]),
                    CellResampler.exact(o, size[0], size[1], size[2]), CellResampler.smooth(o, size[0], size[1], size[2]))) {
                BlockPos s = r.size();
                java.util.Map<List<Integer>, List<List<Integer>>> expected = new java.util.HashMap<>();
                int[] cell = new int[3];
                for (int y = 0; y < s.y(); y++) {
                    for (int z = 0; z < s.z(); z++) {
                        for (int x = 0; x < s.x(); x++) {
                            if (!r.source(x, y, z, CellResampler.ALL, cell)) continue;
                            expected.computeIfAbsent(List.of(cell[0], cell[1], cell[2]), k -> new ArrayList<>())
                                    .add(List.of(x, y, z));
                        }
                    }
                }
                int[] out = new int[3 * r.maxTakers()];
                for (int x = 0; x < size[0]; x++) {
                    for (int y = 0; y < size[1]; y++) {
                        for (int z = 0; z < size[2]; z++) {
                            int n = r.takers(x, y, z, CellResampler.ALL, out);
                            List<List<Integer>> got = new ArrayList<>();
                            for (int i = 0; i < n; i++) got.add(List.of(out[3 * i], out[3 * i + 1], out[3 * i + 2]));
                            assertEquals(expected.getOrDefault(List.of(x, y, z), List.of()), got,
                                    r.getClass().getSimpleName() + " " + o + " cell " + x + "," + y + "," + z);
                            // The turned box of the cell holds every taker.
                            int[] box = r.turnedBox(x, y, z, x, y, z);
                            for (List<Integer> t : got) {
                                assertTrue(box != null && t.get(0) >= box[0] && t.get(1) >= box[1] && t.get(2) >= box[2]
                                        && t.get(0) <= box[3] && t.get(1) <= box[4] && t.get(2) <= box[5],
                                        r.getClass().getSimpleName() + " " + o + ": " + t + " outside the turned box");
                            }
                        }
                    }
                }
            }
        }
    }
}
