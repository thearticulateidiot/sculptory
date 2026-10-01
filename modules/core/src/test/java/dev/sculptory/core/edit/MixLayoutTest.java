package dev.sculptory.core.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import org.junit.jupiter.api.Test;

/** The mix patterns: golden outputs, distributions, order, determinism. */
class MixLayoutTest {
    private static final int A = 7, B = 3, C = 11, D = 5;
    private static final int[] ORDER = {A, B, C, D};
    private static final Pattern.Weighted MIX = new Pattern.Weighted(ORDER, new int[] {5, 1, 3, 2}, -9876543210L);

    /** The states of 24³ spread-out cells (and for Steepness a spread of angles), folded into one number. */
    private static long fingerprint(Pattern.Arranged pattern, double degrees) {
        long print = 17;
        for (int x = -12; x < 12; x++) {
            for (int y = 50; y < 74; y++) {
                for (int z = -12; z < 12; z++) {
                    print = print * 31 + pattern.apply(x * 3 + 1, y, z * 3 - 2, degrees + (x + y + z) % 45);
                }
            }
        }
        return print;
    }

    /**
     * Pinned outputs: the client predicts and the server writes from the same parameters, so a change here would make
     * two builds disagree (and old strokes and palettes look different). Change them only with the protocol version.
     */
    @Test
    void everyLayoutGivesItsGoldenOutput() {
        assertEquals(2928043042420459551L, fingerprint(new Pattern.Arranged(MIX, new MixLayout.Patches(5)), 0));
        assertEquals(8172432669370492933L, fingerprint(new Pattern.Arranged(MIX, new MixLayout.Patches(1)), 0));
        assertEquals(2404277508154665171L, fingerprint(new Pattern.Arranged(MIX, new MixLayout.Patches(32)), 0));
        assertEquals(7882060134133365859L, fingerprint(new Pattern.Arranged(MIX,
                new MixLayout.Gradient(new BlockPos(-20, 55, 3), new BlockPos(25, 70, -9), 6)), 0));
        assertEquals(-1811515724558176439L, fingerprint(new Pattern.Arranged(MIX, new MixLayout.Steepness(12)), 20));
    }

    @Test
    void theSameInputsGiveTheSameBlocksAndTheSeedChangesThem() {
        Pattern.Arranged patches = new Pattern.Arranged(MIX, new MixLayout.Patches(4));
        Pattern.Arranged again = new Pattern.Arranged(new Pattern.Weighted(ORDER, new int[] {5, 1, 3, 2}, -9876543210L),
                new MixLayout.Patches(4));
        Pattern.Arranged reseeded = new Pattern.Arranged(new Pattern.Weighted(ORDER, new int[] {5, 1, 3, 2}, 42L),
                new MixLayout.Patches(4));
        assertEquals(patches, again);
        int differ = 0;
        for (int x = 0; x < 64; x++) {
            for (int z = 0; z < 64; z++) {
                int state = patches.apply(null, x, 70, z, 0);
                assertEquals(state, again.apply(null, x, 70, z, 99), "equal patterns agree whatever the cell holds");
                assertEquals(state, patches.apply(x, 70, z, 55), "Patches read no steepness");
                if (state != reseeded.apply(null, x, 70, z, 0)) differ++;
            }
        }
        assertTrue(differ > 64 * 64 / 3, "a new seed re-rolls the patches: " + differ + " of 4096 changed");
    }

    /** The weights still set roughly how much of each block appears: each point picks its block by weight. */
    @Test
    void patchesFollowTheWeights() {
        for (int size : new int[] {1, 3, 6, 12}) {
            Pattern.Arranged patches = new Pattern.Arranged(MIX, new MixLayout.Patches(size));
            int[] counts = new int[16];
            int cells = 0;
            // A volume holding many patches of every size, sampled a third of a patch apart.
            int span = Math.max(48, size * 14);
            int step = Math.max(1, size / 3);
            for (int x = 0; x < span; x += step) {
                for (int y = 0; y < span; y += step) {
                    for (int z = 0; z < span; z += step) {
                        counts[patches.apply(null, x, y, z, 0)]++;
                        cells++;
                    }
                }
            }
            double[] expected = {5 / 11.0, 1 / 11.0, 3 / 11.0, 2 / 11.0};
            for (int i = 0; i < 4; i++) {
                double share = (double) counts[ORDER[i]] / cells;
                assertEquals(expected[i], share, 0.06, "size " + size + ", entry " + i + ": " + share);
            }
        }
    }

    /** Patches clump: neighbours agree far more often than in a random mix, and more the larger the patches. */
    @Test
    void patchesClumpMoreTheLargerTheyAre() {
        double random = sameNeighbours(MIX);
        double small = sameNeighbours(new Pattern.Arranged(MIX, new MixLayout.Patches(2)));
        double medium = sameNeighbours(new Pattern.Arranged(MIX, new MixLayout.Patches(6)));
        double large = sameNeighbours(new Pattern.Arranged(MIX, new MixLayout.Patches(16)));
        assertTrue(random < 0.4, "random: " + random);
        assertTrue(small > random + 0.1, "size 2: " + small + " vs random " + random);
        assertTrue(medium > small && medium > 0.75, "size 6: " + medium);
        assertTrue(large > medium && large > 0.9, "size 16: " + large);
    }

    /** The share of neighbouring cell pairs (along x, y and z) holding the same state. */
    private static double sameNeighbours(Pattern pattern) {
        int same = 0, pairs = 0;
        for (int x = 0; x < 40; x++) {
            for (int y = 0; y < 40; y++) {
                for (int z = 0; z < 40; z++) {
                    int here = pattern.apply(null, x, y, z, 0);
                    same += here == pattern.apply(null, x + 1, y, z, 0) ? 1 : 0;
                    same += here == pattern.apply(null, x, y + 1, z, 0) ? 1 : 0;
                    same += here == pattern.apply(null, x, y, z + 1, 0) ? 1 : 0;
                    pairs += 3;
                }
            }
        }
        return (double) same / pairs;
    }

    /**
     * A gradient without an edge: hard bands in order along the line, each as long as its weight's share (5, 1, 3, 2 of
     * 111 blocks: borders at 50.5, 60.5 and 90.8), the first before the start and the last past the end; across the line
     * nothing changes.
     */
    @Test
    void aGradientLaysTheMixInOrderAlongItsLine() {
        Pattern.Arranged gradient = new Pattern.Arranged(MIX, new MixLayout.Gradient(new BlockPos(0, 64, 0),
                new BlockPos(111, 64, 0), 0));
        for (int x = -30; x <= 140; x++) {
            int expected = x <= 50 ? A : x <= 60 ? B : x <= 90 ? C : D;
            for (int z = -5; z <= 5; z++) {
                for (int y = 60; y <= 68; y++) {
                    assertEquals(expected, gradient.apply(null, x, y, z, 0), "at " + x + "," + y + "," + z);
                }
            }
        }
    }

    /**
     * With an edge the border between two blocks is dithered over that many blocks: outside it every cell is the
     * band's block, inside it both appear and the later one's share rises along the line.
     */
    @Test
    void aGradientsEdgeDithersEachBorderOverItsWidth() {
        Pattern.Weighted two = new Pattern.Weighted(new int[] {A, B}, new int[] {1, 1}, 5L);
        Pattern.Arranged gradient = new Pattern.Arranged(two, new MixLayout.Gradient(new BlockPos(0, 64, 0),
                new BlockPos(100, 64, 0), 20));
        // The border lies at x = 50; the edge spreads it over 40 < x < 60.
        double lastShare = -1;
        for (int x = 0; x <= 100; x++) {
            int later = 0;
            for (int z = 0; z < 400; z++) {
                if (gradient.apply(null, x, 64, z, 0) == B) later++;
            }
            double share = later / 400.0;
            if (x < 40) assertEquals(0, later, "x " + x + " is before the edge");
            if (x > 60) assertEquals(400, later, "x " + x + " is past the edge");
            if (x >= 42 && x <= 58) {
                assertTrue(share > 0.02 && share < 0.98, "x " + x + " is inside the edge: " + share);
                assertTrue(share > lastShare - 0.12, "the later block's share rises: " + lastShare + " then " + share);
                lastShare = share;
            }
        }
    }

    /**
     * The line's ends are borders like any other: with a last band narrower than the edge, cells just past the end still
     * show the block before it (and just before the start, the second block), while cells farther out than half the
     * edge are clamped to the first or the last block. (The dither is applied before the clamp.)
     */
    @Test
    void aGradientsEndsDitherLikeEveryOtherBorder() {
        // Bands of 95 and 5 blocks over a 100-block line; the edge of 20 reaches 10 blocks past either end, farther
        // than the short band is wide, so the border at 95 dithers across the end (about a fifth of the cells at 101).
        Pattern.Weighted lopsided = new Pattern.Weighted(new int[] {A, B}, new int[] {19, 1}, 11L);
        Pattern.Arranged gradient = new Pattern.Arranged(lopsided, new MixLayout.Gradient(new BlockPos(0, 64, 0),
                new BlockPos(100, 64, 0), 20));
        int firstPastTheEnd = 0, secondBeforeTheStart = 0;
        for (int z = 0; z < 400; z++) {
            for (int x = 101; x <= 109; x++) {
                if (gradient.apply(null, x, 64, z, 0) == A) firstPastTheEnd++;
            }
            for (int x = -9; x <= -1; x++) {
                if (gradient.apply(null, x, 64, z, 0) == B) secondBeforeTheStart++;
            }
            for (int x = -40; x <= -11; x++) assertEquals(A, gradient.apply(null, x, 64, z, 0), "clamped before the start");
            for (int x = 111; x <= 140; x++) assertEquals(B, gradient.apply(null, x, 64, z, 0), "clamped past the end");
        }
        assertTrue(firstPastTheEnd > 100, "the end's border dithers past the end: " + firstPastTheEnd);
        // The start's border (at x = 95 here) is far from the start: nothing before the start dithers into the second block.
        assertEquals(0, secondBeforeTheStart, "the start clamps when its band is wide");
        Pattern.Arranged reversed = new Pattern.Arranged(new Pattern.Weighted(new int[] {A, B}, new int[] {1, 19}, 11L),
                new MixLayout.Gradient(new BlockPos(0, 64, 0), new BlockPos(100, 64, 0), 20));
        int secondBeforeTheStartReversed = 0;
        for (int z = 0; z < 400; z++) {
            for (int x = -9; x <= -1; x++) {
                if (reversed.apply(null, x, 64, z, 0) == B) secondBeforeTheStartReversed++;
            }
        }
        assertTrue(secondBeforeTheStartReversed > 100, "the start's border dithers before the start: "
                + secondBeforeTheStartReversed);
    }

    /** The line runs any way: up (height bands, a snow line), down, diagonally; cells before and after it clamp. */
    @Test
    void aGradientRunsAnyWay() {
        Pattern.Arranged up = new Pattern.Arranged(MIX, new MixLayout.Gradient(new BlockPos(3, 60, 3),
                new BlockPos(3, 71, 3), 0));
        for (int x = -40; x <= 40; x += 7) {
            assertEquals(A, up.apply(null, x, 40, x, 0), "below the start");
            assertEquals(A, up.apply(null, x, 64, -x, 0), "the first band");
            assertEquals(D, up.apply(null, x, 70, x, 0), "the last band");
            assertEquals(D, up.apply(null, x, 200, x, 0), "above the end");
        }
        Pattern.Arranged down = new Pattern.Arranged(MIX, new MixLayout.Gradient(new BlockPos(3, 71, 3),
                new BlockPos(3, 60, 3), 0));
        assertEquals(D, down.apply(null, 0, 40, 0, 0), "past a downward line's end");
        Pattern.Arranged diagonal = new Pattern.Arranged(MIX, new MixLayout.Gradient(new BlockPos(0, 0, 0),
                new BlockPos(30, 30, 30), 0));
        int lastEntry = -1;
        for (int i = -10; i <= 40; i++) {
            int entry = entryOf(diagonal.apply(null, i, i, i, 0));
            assertTrue(entry >= lastEntry, "the mix goes in order along the line");
            lastEntry = entry;
        }
        assertEquals(3, lastEntry);
    }

    private static int entryOf(int state) {
        for (int i = 0; i < ORDER.length; i++) {
            if (ORDER[i] == state) return i;
        }
        throw new AssertionError("not in the mix: " + state);
    }

    /**
     * Steepness: flat ground the first block, vertical faces the last, the ones between in order, each over its
     * weight's share of 0-90° (5, 1, 3, 2 of 11: to 40.9°, 49.1°, 73.6°); with an edge the borders dither.
     */
    @Test
    void steepnessGoesFromFlatToVerticalInOrder() {
        Pattern.Arranged steep = new Pattern.Arranged(MIX, new MixLayout.Steepness(0));
        assertEquals(A, steep.apply(0, 64, 0, 0));
        assertEquals(A, steep.apply(0, 64, 0, 40.8));
        assertEquals(B, steep.apply(0, 64, 0, 41));
        assertEquals(B, steep.apply(0, 64, 0, 49));
        assertEquals(C, steep.apply(0, 64, 0, 49.2));
        assertEquals(C, steep.apply(0, 64, 0, 73.6));
        assertEquals(D, steep.apply(0, 64, 0, 73.7));
        assertEquals(D, steep.apply(0, 64, 0, 90));
        assertEquals(D, steep.apply(0, 64, 0, 400), "clamped to vertical");
        assertEquals(A, steep.apply(0, 64, 0, -5), "clamped to flat");
        assertEquals(A, steep.apply(null, 0, 64, 0, 0), "without a steepness the ground counts as flat");

        Pattern.Arranged ragged = new Pattern.Arranged(new Pattern.Weighted(new int[] {A, D}, new int[] {1, 1}, 9L),
                new MixLayout.Steepness(20));
        for (int x = 0; x < 200; x++) {
            assertEquals(A, ragged.apply(x, 64, 0, 34.9), "below the edge");
            assertEquals(D, ragged.apply(x, 64, 0, 55.1), "above the edge");
        }
        int steepAt40 = 0, steepAt50 = 0;
        for (int x = 0; x < 400; x++) {
            if (ragged.apply(x, 64, 0, 40) == D) steepAt40++;
            if (ragged.apply(x, 64, 0, 50) == D) steepAt50++;
        }
        assertTrue(steepAt40 > 20 && steepAt40 < steepAt50 && steepAt50 < 380, steepAt40 + " then " + steepAt50);
    }

    @Test
    void entriesByHashAndByPlaceFollowTheWeights() {
        assertEquals(11, MIX.totalWeight());
        assertEquals(0, MIX.entryAt(-1));
        assertEquals(0, MIX.entryAt(0));
        assertEquals(1, MIX.entryAt(5 / 11.0));
        assertEquals(2, MIX.entryAt(6 / 11.0));
        assertEquals(3, MIX.entryAt(9 / 11.0));
        assertEquals(3, MIX.entryAt(1));
        assertEquals(3, MIX.entryAt(Double.POSITIVE_INFINITY));
        assertEquals(0, MIX.entryFor(0));
        assertEquals(1, MIX.entryFor(5));
        assertEquals(3, MIX.entryFor(10));
        assertEquals(0, MIX.entryFor(11));
        // Unsigned: 2^64 is 5 more than a multiple of 11, so 2^64 - 1 picks 4 (entry 0) and 2^64 - 11 picks 5 (entry 1).
        assertEquals(0, MIX.entryFor(-1L));
        assertEquals(1, MIX.entryFor(-11L));
    }

    @Test
    void layoutsRefuseValuesOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> new MixLayout.Patches(0));
        assertThrows(IllegalArgumentException.class, () -> new MixLayout.Patches(33));
        assertThrows(IllegalArgumentException.class, () -> new MixLayout.Steepness(-1));
        assertThrows(IllegalArgumentException.class, () -> new MixLayout.Steepness(46));
        BlockPos a = new BlockPos(0, 64, 0), b = new BlockPos(10, 64, 0);
        assertThrows(IllegalArgumentException.class, () -> new MixLayout.Gradient(a, a, 4), "one block is no line");
        assertThrows(IllegalArgumentException.class, () -> new MixLayout.Gradient(a, b, -1));
        assertThrows(IllegalArgumentException.class, () -> new MixLayout.Gradient(a, b, 33));
        assertThrows(IllegalArgumentException.class, () -> new MixLayout.Gradient(a, new BlockPos((1 << 25) + 1, 0, 0), 4));
        assertThrows(IllegalArgumentException.class, () -> new MixLayout.Gradient(new BlockPos(0, 4097, 0), b, 4));
        assertThrows(NullPointerException.class, () -> new Pattern.Arranged(MIX, null));
        new MixLayout.Gradient(new BlockPos(-(1 << 25), -4096, 1 << 25), new BlockPos(1 << 25, 4096, -(1 << 25)), 32);
        new MixLayout.Patches(1);
        new MixLayout.Patches(32);
        new MixLayout.Steepness(45);
    }

    @Test
    void aGradientFarOutKeepsItsPlaceExact() {
        MixLayout.Gradient far = new MixLayout.Gradient(new BlockPos(-(1 << 25), -4096, -(1 << 25)),
                new BlockPos(1 << 25, 4096, 1 << 25), 0);
        assertEquals(0.5, far.place(0, 0, 0), 1e-12);
        assertTrue(far.place(Integer.MIN_VALUE, -5000, Integer.MIN_VALUE) < 0);
        assertTrue(far.place(Integer.MAX_VALUE, 5000, Integer.MAX_VALUE) > 1);
        assertNotEquals(far.place(1, 0, 0), far.place(0, 0, 0));
    }
}
