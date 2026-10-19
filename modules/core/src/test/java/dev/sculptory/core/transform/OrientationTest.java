package dev.sculptory.core.transform;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.edit.PasteGeometry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The frozen orientation contract (protocol 6): the angle conventions, canonical folding into {@link Transform}, the
 * pivot and result box, and {@link Orientation#inverseCell} / {@link Orientation#mapCell}. Quarter turns are checked
 * against a reference model written from the javadoc alone: integer rotation matrices about the pivot cell.
 */
class OrientationTest {
    private static final int Q = Orientation.QUARTER_TURN;
    private static final List<int[]> SIZES = List.of(new int[] {1, 1, 1}, new int[] {4, 3, 2}, new int[] {5, 2, 7},
            new int[] {2, 6, 3}, new int[] {6, 4, 4});

    // ---------------------------------------------------------------- the reference model

    /** The documented matrices for quarter turns, as integers, row-major: Yaw · Pitch · Roll. */
    private static int[] reference(int yaw, int pitch, int roll) {
        int[] y = {cos(yaw), 0, -sin(yaw), 0, 1, 0, sin(yaw), 0, cos(yaw)};
        int[] p = {1, 0, 0, 0, cos(pitch), -sin(pitch), 0, sin(pitch), cos(pitch)};
        int[] r = {cos(roll), -sin(roll), 0, sin(roll), cos(roll), 0, 0, 0, 1};
        return times(y, times(p, r));
    }

    private static int sin(int angle) {
        return new int[] {0, 1, 0, -1}[Math.floorMod(angle / Q, 4)];
    }

    private static int cos(int angle) {
        return new int[] {1, 0, -1, 0}[Math.floorMod(angle / Q, 4)];
    }

    private static int[] times(int[] a, int[] b) {
        int[] m = new int[9];
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                for (int k = 0; k < 3; k++) m[3 * row + col] += a[3 * row + k] * b[3 * k + col];
            }
        }
        return m;
    }

    /**
     * Where the reference model puts source cell {@code c}: the exact transform first (as {@link Transform#apply}), then
     * the quarter-turn matrix about the intermediate box's true centre, in doubled coordinates (a box of size s has its
     * centre at s; cell centres are odd), the centre landing half a block lower on a result axis that the turn fills
     * from an axis of the other parity (so cell centres land on cell centres).
     */
    private static int[] referenceCell(Transform t, int[] m, int[] c, int[] size) {
        BlockPos s = t.size(size[0], size[1], size[2]);
        int[] ts = {s.x(), s.y(), s.z()};
        BlockPos q = t.apply(c[0], c[1], c[2], size[0], size[1], size[2]);
        int[] centre2 = {2 * q.x() + 1, 2 * q.y() + 1, 2 * q.z() + 1};
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) {
            int from = m[3 * i] != 0 ? 0 : m[3 * i + 1] != 0 ? 1 : 2;
            int sum = ts[i] - ((ts[i] & 1) != (ts[from] & 1) ? 1 : 0);
            for (int j = 0; j < 3; j++) sum += m[3 * i + j] * (centre2[j] - ts[j]);
            out[i] = Math.floorDiv(sum - 1, 2); // an odd doubled centre back to its cell
        }
        return out;
    }

    private static List<int[]> cells(int[] size) {
        List<int[]> cells = new ArrayList<>();
        for (int x = 0; x < size[0]; x++) {
            for (int y = 0; y < size[1]; y++) {
                for (int z = 0; z < size[2]; z++) cells.add(new int[] {x, y, z});
            }
        }
        return cells;
    }

    // ---------------------------------------------------------------- conventions and canonical form

    @Test
    void positiveYawTurnsClockwiseSeenFromAboveLikeTheTransformsQuarterTurns() {
        Orientation quarter = Orientation.free(Transform.IDENTITY, Q, 0, 0);
        assertTrue(quarter.isExact(), "a quarter turn of yaw folds into the exact transform");
        assertEquals(Orientation.of(Transform.rotation(1)), quarter);
        assertEquals(Orientation.of(Transform.rotation(3)), Orientation.free(Transform.IDENTITY, -Q, 0, 0));
        // East goes to south.
        double[] m = quarter.matrix();
        assertArrayEquals(new double[] {0, 0, -1, 0, 1, 0, 1, 0, 0}, m);
    }

    @Test
    void pitchAndRollAreRightHandedAboutEastAndSouth() {
        // Pitch: up goes to south. Roll: east goes to up.
        assertArrayEquals(new double[] {1, 0, 0, 0, 0, -1, 0, 1, 0},
                Orientation.free(Transform.IDENTITY, 0, Q, 0).matrix());
        assertArrayEquals(new double[] {0, -1, 0, 1, 0, 0, 0, 0, 1},
                Orientation.free(Transform.IDENTITY, 0, 0, Q).matrix());
        // Roll first, then pitch, then yaw: roll east to up, pitch up to south, yaw south to west.
        double[] m = Orientation.free(Transform.IDENTITY, Q, Q, Q).matrix();
        assertArrayEquals(new double[] {-1, 0, 0}, new double[] {m[0], m[3], m[6]}, "east ends up west");
    }

    @Test
    void anglesAreNormalisedAndOnlyRotationsKeepingUpVerticalFold() {
        Orientation free = Orientation.free(Transform.IDENTITY, -100, 36_100, 72_000 + 5);
        assertEquals(35_900, free.yaw());
        assertEquals(100, free.pitch());
        assertEquals(5, free.roll());
        assertFalse(free.isExact());
        assertThrows(IllegalStateException.class, free::exact);
        // A half turn of pitch is upside down plus a north-south mirror; of roll, upside down plus an east-west one.
        Orientation pitchHalf = Orientation.free(Transform.IDENTITY, 0, 2 * Q, 0);
        assertTrue(pitchHalf.isExact());
        assertArrayEquals(new double[] {1, 0, 0, 0, -1, 0, 0, 0, -1}, pitchHalf.matrix());
        assertTrue(pitchHalf.transform().upsideDown());
        Orientation rollHalf = Orientation.free(Transform.IDENTITY, 0, 0, 2 * Q);
        assertArrayEquals(new double[] {-1, 0, 0, 0, -1, 0, 0, 0, 1}, rollHalf.matrix());
        // Quarter turns of pitch or roll leave up horizontal: they stay free (the resampling is exact for them).
        for (int[] angles : List.of(new int[] {0, Q, 0}, new int[] {0, 0, 3 * Q}, new int[] {Q, Q, 0})) {
            Orientation o = Orientation.free(Transform.IDENTITY, angles[0], angles[1], angles[2]);
            assertFalse(o.isExact(), List.of(angles[0], angles[1], angles[2]).toString());
            assertEquals(angles[0], o.yaw());
        }
        // Every quarter-turn combination keeping up vertical folds, whatever the exact part.
        for (Transform t : Transform.allWithFlips()) {
            assertEquals(Orientation.of(t), Orientation.free(t, 0, 0, 0));
            assertTrue(Orientation.free(t, 2 * Q, 2 * Q, 2 * Q).isExact());
        }
        assertTrue(Orientation.IDENTITY.isIdentity());
        assertTrue(Orientation.free(Transform.IDENTITY, 2 * Q, 2 * Q, 2 * Q).isIdentity(), "three half turns cancel");
    }

    @Test
    void foldingKeepsTheMapTheFreeRotationDescribes() {
        // A folded orientation maps cells as the reference model's rotation does (up to where the box sits).
        for (Transform t : Transform.allWithFlips()) {
            for (int yaw = 0; yaw < 4; yaw++) {
                for (int half : new int[] {0, 2 * Q}) {
                    for (boolean pitch : new boolean[] {true, false}) {
                        int p = pitch ? half : 0, r = pitch ? 0 : half;
                        Orientation folded = Orientation.free(t, yaw * Q, p, r);
                        assertTrue(folded.isExact());
                        int[] m = reference(yaw * Q, p, r);
                        for (int[] size : SIZES) checkSameMap(folded, t, m, size);
                    }
                }
            }
        }
    }

    private static void checkSameMap(Orientation folded, Transform t, int[] m, int[] size) {
        int[] ref0 = referenceCell(t, m, new int[] {0, 0, 0}, size);
        BlockPos got0 = folded.mapCell(0, 0, 0, size[0], size[1], size[2]);
        for (int[] c : cells(size)) {
            int[] ref = referenceCell(t, m, c, size);
            BlockPos got = folded.mapCell(c[0], c[1], c[2], size[0], size[1], size[2]);
            assertEquals(new BlockPos(ref[0] - ref0[0], ref[1] - ref0[1], ref[2] - ref0[2]),
                    new BlockPos(got.x() - got0.x(), got.y() - got0.y(), got.z() - got0.z()),
                    folded + " on " + List.of(size[0], size[1], size[2]));
        }
    }

    // ---------------------------------------------------------------- the exact path

    @Test
    void anExactOrientationIsItsTransform() {
        for (Transform t : Transform.allWithFlips()) {
            Orientation o = Orientation.of(t);
            for (int[] size : SIZES) {
                BlockPos turned = t.size(size[0], size[1], size[2]);
                assertEquals(new Orientation.Bounds(turned, BlockPos.ORIGIN), o.bounds(size[0], size[1], size[2]));
                for (int[] c : cells(size)) {
                    BlockPos to = t.apply(c[0], c[1], c[2], size[0], size[1], size[2]);
                    assertEquals(to, o.mapCell(c[0], c[1], c[2], size[0], size[1], size[2]));
                    assertEquals(new BlockPos(c[0], c[1], c[2]), o.inverseCell(to.x(), to.y(), to.z(), size[0], size[1],
                            size[2]));
                    double[] centre = o.mapPoint(c[0] + 0.5, c[1] + 0.5, c[2] + 0.5, size[0], size[1], size[2]);
                    assertArrayEquals(new double[] {to.x() + 0.5, to.y() + 0.5, to.z() + 0.5}, centre, 1e-12);
                }
                assertNull(o.inverseCell(turned.x(), 0, 0, size[0], size[1], size[2]), "outside the result box");
                assertNull(o.inverseCell(0, -1, 0, size[0], size[1], size[2]));
            }
            // An anchor far outside the box maps affinely, as a paste's anchor does.
            BlockPos anchor = new BlockPos(-1000, 300, 70_000);
            assertEquals(PasteGeometry.pasteTarget(new BlockPos(4, 3, 2), anchor, t, new BlockPos(5, 64, -9)),
                    PasteGeometry.pasteTarget(new BlockPos(4, 3, 2), anchor, o, new BlockPos(5, 64, -9)));
        }
    }

    // ---------------------------------------------------------------- free rotation

    @Test
    void quarterTurnsAboutAnyAxisAreExactPermutationsAboutThePivotCell() {
        for (Transform t : List.of(Transform.IDENTITY, new Transform(1, Mirror.X, true))) {
            for (int yaw = 0; yaw < 4; yaw++) {
                for (int pitch = 0; pitch < 4; pitch++) {
                    for (int roll = 0; roll < 4; roll++) {
                        Orientation o = Orientation.free(t, yaw * Q, pitch * Q, roll * Q);
                        if (o.isExact()) continue;
                        int[] m = reference(yaw * Q, pitch * Q, roll * Q);
                        for (int[] size : SIZES) checkPermutation(o, t, m, size);
                    }
                }
            }
        }
    }

    private static void checkPermutation(Orientation o, Transform t, int[] m, int[] size) {
        Orientation.Bounds bounds = o.bounds(size[0], size[1], size[2]);
        BlockPos s = bounds.size();
        assertEquals((long) size[0] * size[1] * size[2], (long) s.x() * s.y() * s.z(), o + ": the box keeps its volume");
        Set<BlockPos> sources = new HashSet<>();
        for (int x = 0; x < s.x(); x++) {
            for (int y = 0; y < s.y(); y++) {
                for (int z = 0; z < s.z(); z++) {
                    BlockPos source = o.inverseCell(x, y, z, size[0], size[1], size[2]);
                    assertNotNull(source, o + ": no holes at " + x + "," + y + "," + z);
                    assertTrue(sources.add(source), o + ": " + source + " taken twice");
                    assertEquals(new BlockPos(x, y, z), o.mapCell(source.x(), source.y(), source.z(), size[0], size[1],
                            size[2]), o + ": mapCell is the inverse of inverseCell");
                }
            }
        }
        // The reference model: the rotation about the pivot cell, the result box's corner at the bounds' offset.
        for (int[] c : cells(size)) {
            int[] ref = referenceCell(t, m, c, size);
            BlockPos got = o.mapCell(c[0], c[1], c[2], size[0], size[1], size[2]);
            assertEquals(new BlockPos(ref[0] - bounds.offset().x(), ref[1] - bounds.offset().y(),
                    ref[2] - bounds.offset().z()), got, o + " on " + List.of(size[0], size[1], size[2]));
        }
    }

    @Test
    void freeAnglesResampleEveryResultCellToTheNearestSourceCell() {
        Random random = new Random(6);
        for (int i = 0; i < 200; i++) {
            int[] size = {1 + random.nextInt(9), 1 + random.nextInt(9), 1 + random.nextInt(9)};
            Orientation o = Orientation.free(Transform.allWithFlips().get(random.nextInt(16)),
                    random.nextInt(Orientation.FULL_TURN), random.nextInt(Orientation.FULL_TURN),
                    random.nextInt(Orientation.FULL_TURN));
            Orientation.Bounds bounds = o.bounds(size[0], size[1], size[2]);
            BlockPos s = bounds.size();
            long volume = (long) size[0] * size[1] * size[2];
            long taken = 0;
            for (int x = 0; x < s.x(); x++) {
                for (int y = 0; y < s.y(); y++) {
                    for (int z = 0; z < s.z(); z++) {
                        BlockPos source = o.inverseCell(x, y, z, size[0], size[1], size[2]);
                        if (source == null) continue;
                        taken++;
                        assertTrue(source.x() >= 0 && source.x() < size[0] && source.y() >= 0 && source.y() < size[1]
                                && source.z() >= 0 && source.z() < size[2], o + ": " + source);
                        // Nearest: the result cell's centre turned back lies in the source cell it takes.
                        double[] back = turnBack(o, x, y, z, size);
                        assertTrue(Math.floor(back[0]) == source.x() && Math.floor(back[1]) == source.y()
                                && Math.floor(back[2]) == source.z(), o + ": " + source);
                    }
                }
            }
            // The pivot cell always keeps its place; a turned box holds at most its bounds.
            assertTrue(taken >= 1 && taken <= (long) s.x() * s.y() * s.z(), o + " " + taken + " of " + volume);
            // Every source cell lands within one cell of the result box.
            for (int[] c : cells(size)) {
                BlockPos to = o.mapCell(c[0], c[1], c[2], size[0], size[1], size[2]);
                assertTrue(to.x() >= -1 && to.x() <= s.x() && to.y() >= -1 && to.y() <= s.y() && to.z() >= -1
                        && to.z() <= s.z(), o + ": " + to + " outside " + s);
            }
        }
    }

    /** The result cell's centre mapped back into the source box by brute force: the closest of a fine search. */
    private static double[] turnBack(Orientation o, int x, int y, int z, int[] size) {
        // The forward map is affine: solve with three probes.
        double[] origin = o.mapPoint(0, 0, 0, size[0], size[1], size[2]);
        double[] ex = o.mapPoint(1, 0, 0, size[0], size[1], size[2]);
        double[] ey = o.mapPoint(0, 1, 0, size[0], size[1], size[2]);
        double[] ez = o.mapPoint(0, 0, 1, size[0], size[1], size[2]);
        double[][] a = new double[3][3];
        for (int i = 0; i < 3; i++) {
            a[i][0] = ex[i] - origin[i];
            a[i][1] = ey[i] - origin[i];
            a[i][2] = ez[i] - origin[i];
        }
        double[] d = {x + 0.5 - origin[0], y + 0.5 - origin[1], z + 0.5 - origin[2]};
        // An orthogonal matrix: its inverse is its transpose.
        return new double[] {a[0][0] * d[0] + a[1][0] * d[1] + a[2][0] * d[2],
                a[0][1] * d[0] + a[1][1] * d[1] + a[2][1] * d[2],
                a[0][2] * d[0] + a[1][2] * d[1] + a[2][2] * d[2]};
    }

    @Test
    void aFortyFiveDegreeYawWidensTheBoxAndKeepsItsHeight() {
        Orientation o = Orientation.free(Transform.IDENTITY, 4_500, 0, 0);
        Orientation.Bounds bounds = o.bounds(4, 3, 4);
        assertEquals(3, bounds.size().y());
        assertEquals(0, bounds.offset().y());
        assertTrue(bounds.size().x() >= 5 && bounds.size().x() <= 6, bounds.toString());
        assertEquals(bounds, o.bounds(4, 3, 4), "deterministic");
        // The box's true centre (2, 1.5, 2) stays where it is.
        double[] centre = o.mapPoint(2, 1.5, 2, 4, 3, 4);
        assertEquals(2 - bounds.offset().x(), centre[0], 1e-9);
        assertEquals(1.5, centre[1], 1e-9);
        assertEquals(2 - bounds.offset().z(), centre[2], 1e-9);
        // So the turned box is centred in its result box: 4·√2 ≈ 5.66 wide, 6 cells, one each side of the centre's 4.
        assertEquals(6, bounds.size().x());
        assertEquals(-1, bounds.offset().x());
        assertThrows(IllegalArgumentException.class, () -> o.bounds(0, 1, 1));
    }

    @Test
    void aPasteOfAFreeOrientationLandsItsAnchorOnTheOrigin() {
        Orientation o = Orientation.free(Transform.IDENTITY, 0, Q, 0);
        BlockPos size = new BlockPos(3, 5, 2);
        BlockPos anchor = new BlockPos(1, 0, 1);
        BlockPos origin = new BlockPos(100, 64, -30);
        var target = PasteGeometry.pasteTarget(size, anchor, o, origin);
        assertEquals(o.bounds(3, 5, 2).size(), new BlockPos(target.sizeX(), target.sizeY(), target.sizeZ()));
        BlockPos landed = o.mapCell(anchor.x(), anchor.y(), anchor.z(), 3, 5, 2);
        assertEquals(origin, target.min().add(landed));
    }
}
