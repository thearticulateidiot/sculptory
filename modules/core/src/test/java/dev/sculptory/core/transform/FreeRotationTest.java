package dev.sculptory.core.transform;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The free-rotation machinery on top of the frozen {@link Orientation} contract: the cached sampler, the exact symmetry
 * of its trigonometry, composing with symmetry images, the nearest cube turn, and resampling without holes.
 */
class FreeRotationTest {
    private static final int Q = Orientation.QUARTER_TURN;

    private static Orientation random(Random random) {
        return Orientation.free(Transform.allWithFlips().get(random.nextInt(16)), random.nextInt(Orientation.FULL_TURN),
                random.nextInt(Orientation.FULL_TURN), random.nextInt(Orientation.FULL_TURN));
    }

    @Test
    void theSamplerTakesExactlyTheCellsInverseCellTakes() {
        Random random = new Random(11);
        for (int i = 0; i < 300; i++) {
            int[] size = {1 + random.nextInt(8), 1 + random.nextInt(8), 1 + random.nextInt(8)};
            Orientation o = i % 5 == 0 ? Orientation.of(Transform.allWithFlips().get(random.nextInt(16))) : random(random);
            Orientation.Sampler sampler = o.sampler(size[0], size[1], size[2]);
            assertEquals(o.bounds(size[0], size[1], size[2]).size(), sampler.size());
            assertEquals(o.bounds(size[0], size[1], size[2]).offset(), sampler.offset());
            int[] out = new int[3];
            BlockPos s = sampler.size();
            for (int x = -1; x <= s.x(); x++) {
                for (int y = -1; y <= s.y(); y++) {
                    for (int z = -1; z <= s.z(); z++) {
                        BlockPos expected = o.inverseCell(x, y, z, size[0], size[1], size[2]);
                        boolean found = sampler.source(x, y, z, out);
                        assertEquals(expected != null, found, o + " at " + x + "," + y + "," + z);
                        if (found) assertEquals(expected, new BlockPos(out[0], out[1], out[2]));
                    }
                }
            }
        }
    }

    @Test
    void anExactOrientationsSamplerIsTheInverseTransform() {
        for (Transform t : Transform.allWithFlips()) {
            Orientation.Sampler sampler = Orientation.of(t).sampler(3, 2, 5);
            int[] out = new int[3];
            for (int x = 0; x < 3; x++) {
                for (int y = 0; y < 2; y++) {
                    for (int z = 0; z < 5; z++) {
                        BlockPos to = t.apply(x, y, z, 3, 2, 5);
                        assertTrue(sampler.source(to.x(), to.y(), to.z(), out));
                        assertArrayEquals(new int[] {x, y, z}, out, t.toString());
                    }
                }
            }
        }
    }

    @Test
    void sinesAndCosinesAreSymmetricToTheLastBit() {
        for (int a = 0; a < Orientation.FULL_TURN; a += 7) {
            assertEquals(0.0 - Orientation.sin(a), Orientation.sin(-a), "sin(-a) at " + a);
            assertEquals(Orientation.cos(a), Orientation.cos(-a), "cos(-a) at " + a);
            assertEquals(Orientation.cos(a), Orientation.sin(a + Q), "sin(a + 90) at " + a);
            assertEquals(Orientation.sin(a), Orientation.cos(Q - a), "cos(90 - a) at " + a);
        }
        assertEquals(0, Orientation.sin(0));
        assertEquals(1, Orientation.sin(Q));
        assertEquals(-1, Orientation.cos(2 * Q));
        assertEquals(Math.sin(Math.toRadians(30)), Orientation.sin(3_000), 1e-15);
        assertEquals(Math.sin(Math.toRadians(73.25)), Orientation.sin(7_325), 1e-15);
    }

    @Test
    void followingASymmetryImageIsExactlyTheImageTimesTheMatrix() {
        Random random = new Random(3);
        for (int i = 0; i < 200; i++) {
            Orientation o = random(random);
            for (Transform image : Transform.allWithFlips()) {
                Orientation then = o.then(image);
                double[] expected = times(Orientation.linear(image), o.matrix());
                assertArrayEquals(expected, then.matrix(), 0.0, o + " then " + image);
            }
        }
        // Exact orientations compose as their transforms do.
        Transform t = new Transform(1, Mirror.X);
        assertEquals(Orientation.of(t.compose(Transform.rotation(2))), Orientation.of(t).then(Transform.rotation(2)));
    }

    @Test
    void conjugatingByAnImageGivesTheImagesTurn() {
        Random random = new Random(5);
        for (int i = 0; i < 200; i++) {
            Orientation o = random(random);
            for (Transform image : Transform.all()) {
                Orientation conjugated = o.conjugated(image);
                int[] li = Orientation.linear(image);
                double[] expected = times(times(li, o.matrix()), transpose(li));
                assertArrayEquals(expected, conjugated.matrix(), 2e-4, o + " conjugated by " + image);
            }
        }
        // A mirror negates angles exactly.
        Orientation o = Orientation.free(Transform.IDENTITY, 1_234, 2_345, 3_456);
        Orientation mirrored = o.conjugated(new Transform(0, Mirror.X));
        assertEquals(Orientation.free(Transform.IDENTITY, -1_234, 2_345, -3_456), mirrored);
    }

    @Test
    void theNearestAnglesOfARotationAreItsOwn() {
        Random random = new Random(9);
        for (int i = 0; i < 500; i++) {
            int yaw = random.nextInt(Orientation.FULL_TURN);
            int pitch = random.nextInt(17_000) - 8_500; // away from the gimbal
            int roll = random.nextInt(Orientation.FULL_TURN);
            Orientation o = Orientation.free(Transform.IDENTITY, yaw, pitch, roll);
            assertEquals(o, Orientation.nearest(Transform.IDENTITY, Orientation.rotation(yaw, pitch, roll)));
        }
    }

    @Test
    void aTurnedBoxTooLargeForTheIntRangeIsRefused() {
        Orientation o = Orientation.free(Transform.IDENTITY, 4_500, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> o.bounds(Integer.MAX_VALUE, 1, Integer.MAX_VALUE));
        assertEquals(1, o.bounds(1, 1, 1).size().y());
    }

    @Test
    void everyQuarterTurnOnEveryAxisIsAnExactCellPermutation() {
        for (int yaw = 0; yaw < 4; yaw++) {
            for (int pitch = 0; pitch < 4; pitch++) {
                for (int roll = 0; roll < 4; roll++) {
                    for (Transform t : Transform.allWithFlips()) {
                        Orientation o = Orientation.free(t, yaw * Q, pitch * Q, roll * Q);
                        int[] size = {4, 3, 5};
                        Orientation.Sampler sampler = o.sampler(size[0], size[1], size[2]);
                        BlockPos s = sampler.size();
                        assertEquals(60L, (long) s.x() * s.y() * s.z(), o.toString());
                        Set<BlockPos> taken = new HashSet<>();
                        int[] out = new int[3];
                        for (int x = 0; x < s.x(); x++) {
                            for (int y = 0; y < s.y(); y++) {
                                for (int z = 0; z < s.z(); z++) {
                                    assertTrue(sampler.source(x, y, z, out), o + " leaves a hole");
                                    assertTrue(taken.add(new BlockPos(out[0], out[1], out[2])), o + " takes a cell twice");
                                    // The forward map sends the source cell back to this cell.
                                    assertEquals(new BlockPos(x, y, z), o.mapCell(out[0], out[1], out[2], 4, 3, 5));
                                }
                            }
                        }
                        // Where the turn keeps up vertical it is the old transform, cell for cell.
                        if (o.isExact()) {
                            for (int x = 0; x < s.x(); x++) {
                                for (int z = 0; z < s.z(); z++) {
                                    assertTrue(sampler.source(x, 0, z, out));
                                    assertEquals(new BlockPos(x, 0, z), o.transform().apply(out[0], out[1], out[2], 4, 3, 5));
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void freeAnglesLeaveNoHolesInsideTheTurnedSolid() {
        Random random = new Random(17);
        for (int i = 0; i < 150; i++) {
            int[] size = {2 + random.nextInt(10), 2 + random.nextInt(10), 2 + random.nextInt(10)};
            Orientation o = random(random);
            Orientation.Sampler sampler = o.sampler(size[0], size[1], size[2]);
            BlockPos s = sampler.size();
            boolean[] filled = new boolean[s.x() * s.y() * s.z()];
            int[] out = new int[3];
            for (int x = 0; x < s.x(); x++) {
                for (int y = 0; y < s.y(); y++) {
                    for (int z = 0; z < s.z(); z++) filled[(x * s.y() + y) * s.z() + z] = sampler.source(x, y, z, out);
                }
            }
            // Every empty cell must reach the outside of the result box through empty cells: no enclosed cavity.
            boolean[] outside = new boolean[filled.length];
            ArrayDeque<int[]> queue = new ArrayDeque<>();
            for (int x = 0; x < s.x(); x++) {
                for (int y = 0; y < s.y(); y++) {
                    for (int z = 0; z < s.z(); z++) {
                        boolean border = x == 0 || y == 0 || z == 0 || x == s.x() - 1 || y == s.y() - 1 || z == s.z() - 1;
                        int index = (x * s.y() + y) * s.z() + z;
                        if (border && !filled[index]) {
                            outside[index] = true;
                            queue.add(new int[] {x, y, z});
                        }
                    }
                }
            }
            int[][] steps = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
            while (!queue.isEmpty()) {
                int[] c = queue.poll();
                for (int[] d : steps) {
                    int x = c[0] + d[0], y = c[1] + d[1], z = c[2] + d[2];
                    if (x < 0 || y < 0 || z < 0 || x >= s.x() || y >= s.y() || z >= s.z()) continue;
                    int index = (x * s.y() + y) * s.z() + z;
                    if (filled[index] || outside[index]) continue;
                    outside[index] = true;
                    queue.add(new int[] {x, y, z});
                }
            }
            for (int index = 0; index < filled.length; index++) {
                assertTrue(filled[index] || outside[index], o + " " + List.of(size[0], size[1], size[2]) + " has a hole");
            }
        }
    }

    @Test
    void eachResultCellTakesTheSourceCellNearestItsTurnedBackCentre() {
        Random random = new Random(23);
        for (int i = 0; i < 100; i++) {
            int[] size = {1 + random.nextInt(7), 1 + random.nextInt(7), 1 + random.nextInt(7)};
            Orientation o = random(random);
            // Forward then back: a source cell's mapped cell takes a source cell next to it (within one on each axis).
            for (int x = 0; x < size[0]; x++) {
                for (int y = 0; y < size[1]; y++) {
                    for (int z = 0; z < size[2]; z++) {
                        BlockPos to = o.mapCell(x, y, z, size[0], size[1], size[2]);
                        BlockPos back = o.inverseCell(to.x(), to.y(), to.z(), size[0], size[1], size[2]);
                        if (back == null) continue;
                        assertTrue(Math.abs(back.x() - x) <= 1 && Math.abs(back.y() - y) <= 1
                                && Math.abs(back.z() - z) <= 1, o + ": " + x + "," + y + "," + z + " -> " + back);
                    }
                }
            }
        }
        assertNull(Orientation.free(Transform.IDENTITY, 4_500, 0, 0).inverseCell(-5, 0, 0, 3, 3, 3));
        assertNotNull(Orientation.free(Transform.IDENTITY, 4_500, 0, 0).inverseCell(1, 1, 1, 3, 3, 3));
    }

    // ---------------------------------------------------------------- cube turns

    @Test
    void theNearestCubeTurnSnapsToTheNearestQuarterTurns() {
        assertEquals(CubeTurn.IDENTITY, CubeTurn.nearest(Orientation.free(Transform.IDENTITY, 3_000, 0, 0)));
        assertEquals(new CubeTurn(Transform.rotation(1), CubeTurn.Tip.NONE),
                CubeTurn.nearest(Orientation.free(Transform.IDENTITY, 6_000, 0, 0)));
        CubeTurn pitched = CubeTurn.nearest(Orientation.free(Transform.IDENTITY, 0, 8_000, 0));
        assertEquals(CubeTurn.Tip.PITCH, pitched.tip());
        assertTrue(pitched.first().isIdentity());
        CubeTurn rolled = CubeTurn.nearest(Orientation.free(Transform.IDENTITY, 0, 0, Q));
        assertEquals(CubeTurn.Tip.ROLL, rolled.tip());
        // A 45 degree tie goes to the first candidate: the identity.
        assertEquals(CubeTurn.IDENTITY, CubeTurn.nearest(Orientation.free(Transform.IDENTITY, 4_500, 0, 0)));
        // An exact orientation is its own transform.
        Transform t = new Transform(3, Mirror.Z, true);
        assertEquals(new CubeTurn(t, CubeTurn.Tip.NONE), CubeTurn.nearest(Orientation.of(t)));
    }

    @Test
    void everyQuarterTurnIsItsOwnCubeTurnAndEverySignedPermutationIsOne() {
        for (int yaw = 0; yaw < 4; yaw++) {
            for (int pitch = 0; pitch < 4; pitch++) {
                for (int roll = 0; roll < 4; roll++) {
                    for (Transform t : Transform.allWithFlips()) {
                        Orientation o = Orientation.free(t, yaw * Q, pitch * Q, roll * Q);
                        int[] expected = new int[9];
                        double[] m = o.matrix();
                        for (int i = 0; i < 9; i++) expected[i] = (int) Math.round(m[i]);
                        assertArrayEquals(expected, CubeTurn.nearest(o).matrix(), o.toString());
                    }
                }
            }
        }
        Set<List<Integer>> seen = new HashSet<>();
        for (int yaw = 0; yaw < 4; yaw++) {
            for (int pitch = 0; pitch < 4; pitch++) {
                for (int roll = 0; roll < 4; roll++) {
                    for (Transform t : Transform.allWithFlips()) {
                        CubeTurn turn = CubeTurn.nearest(Orientation.free(t, yaw * Q, pitch * Q, roll * Q));
                        assertEquals(turn, CubeTurn.of(turn.matrix()));
                        seen.add(java.util.Arrays.stream(turn.matrix()).boxed().toList());
                    }
                }
            }
        }
        assertEquals(48, seen.size(), "every cube symmetry is reached");
        assertThrows(IllegalArgumentException.class, () -> CubeTurn.of(new int[] {1, 1, 0, 0, 1, 0, 0, 0, 1}));
        assertFalse(new CubeTurn(Transform.IDENTITY, CubeTurn.Tip.ROLL).upright());
    }

    // ---------------------------------------------------------------- helpers

    private static double[] times(int[] a, double[] b) {
        double[] m = new double[9];
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                double sum = 0;
                for (int k = 0; k < 3; k++) sum += a[3 * row + k] * b[3 * k + col];
                m[3 * row + col] = sum;
            }
        }
        return m;
    }

    private static double[] times(double[] a, int[] b) {
        double[] m = new double[9];
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                double sum = 0;
                for (int k = 0; k < 3; k++) sum += a[3 * row + k] * b[3 * k + col];
                m[3 * row + col] = sum;
            }
        }
        return m;
    }

    private static int[] transpose(int[] m) {
        return new int[] {m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8]};
    }
}
