package dev.sculptory.core.region;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.Sha256;
import dev.sculptory.core.buffer.BlockBuffer;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;

/** Cell sets: building, set operations, translation, the canonical encoding and its strict decoder. */
class CellSetTest {
    private static final CellSet.Limits LIMITS = CellSet.Limits.DEFAULT;

    private record Cell(int x, int y, int z) {}

    /** Scattered cells around a few places, including negative coordinates and section edges. */
    private static Set<Cell> randomCells(Random rnd, int count) {
        Set<Cell> cells = new HashSet<>();
        int[][] centres = {{0, 0, 0}, {-17, -64, 31}, {1000, 200, -1000}};
        while (cells.size() < count) {
            int[] c = centres[rnd.nextInt(centres.length)];
            cells.add(new Cell(c[0] + rnd.nextInt(40) - 20, c[1] + rnd.nextInt(40) - 20, c[2] + rnd.nextInt(40) - 20));
        }
        return cells;
    }

    private static CellSet of(Iterable<Cell> cells) {
        CellSet.Builder builder = CellSet.builder();
        for (Cell cell : cells) builder.add(cell.x(), cell.y(), cell.z());
        return builder.build();
    }

    private static void assertHolds(Set<Cell> expected, CellSet set) {
        assertEquals(expected.size(), set.size());
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Cell cell : expected) {
            assertTrue(set.contains(cell.x(), cell.y(), cell.z()), "holds " + cell);
            minX = Math.min(minX, cell.x());
            minY = Math.min(minY, cell.y());
            minZ = Math.min(minZ, cell.z());
            maxX = Math.max(maxX, cell.x());
            maxY = Math.max(maxY, cell.y());
            maxZ = Math.max(maxZ, cell.z());
        }
        if (expected.isEmpty()) {
            assertTrue(set.isEmpty());
            return;
        }
        Box bounds = new Box(new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ));
        assertEquals(bounds, set.bounds());
        // Every expected cell is held and the sizes match, so nothing else is; the neighbours check contains() too.
        for (Cell cell : expected) {
            for (int[] d : new int[][] {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}) {
                Cell next = new Cell(cell.x() + d[0], cell.y() + d[1], cell.z() + d[2]);
                assertEquals(expected.contains(next), set.contains(next.x(), next.y(), next.z()), "at " + next);
            }
        }
    }

    // =================================================================== building

    @Test
    void theBuilderAddsRemovesAndCounts() {
        CellSet.Builder builder = CellSet.builder();
        builder.add(0, 0, 0).add(0, 0, 0).add(15, 15, 15).add(16, 0, 0).add(-1, -1, -1);
        assertEquals(4, builder.size());
        assertTrue(builder.contains(-1, -1, -1));
        builder.remove(15, 15, 15).remove(15, 15, 15).remove(99, 99, 99).remove(Integer.MAX_VALUE, 0, 0);
        assertEquals(3, builder.size());
        assertFalse(builder.contains(15, 15, 15));
        assertFalse(builder.contains(Integer.MIN_VALUE, 0, 0));
        CellSet set = builder.build();
        assertHolds(Set.of(new Cell(0, 0, 0), new Cell(16, 0, 0), new Cell(-1, -1, -1)), set);
        assertFalse(set.contains(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
        // The builder stays usable and the set does not change with it.
        builder.add(5, 5, 5);
        assertEquals(3, set.size());
        assertEquals(4, builder.build().size());
        assertThrows(IllegalArgumentException.class, () -> CellSet.builder().add(Integer.MAX_VALUE, 0, 0));
    }

    @Test
    void emptySetsHaveNoBoundsAndNoSections() {
        CellSet empty = CellSet.builder().add(1, 1, 1).remove(1, 1, 1).build();
        assertTrue(empty.isEmpty());
        assertSame(CellSet.empty(), empty);
        assertEquals(0, empty.sectionKeys().length);
        assertThrows(IllegalStateException.class, empty::bounds);
        assertFalse(empty.contains(1, 1, 1));
        assertEquals(CellSet.empty(), CellSet.builder().build());
    }

    @Test
    void randomSetsHoldExactlyTheirCells() {
        Random rnd = new Random(5);
        for (int round = 0; round < 20; round++) {
            Set<Cell> cells = randomCells(rnd, 1 + rnd.nextInt(3000));
            assertHolds(cells, of(cells));
        }
    }

    @Test
    void regionsMaterializeExactly() {
        List<Region> regions = new ArrayList<>();
        regions.add(new Region.Cuboid(new Box(new BlockPos(-20, -3, 5), new BlockPos(20, 40, 16))));
        for (ShapeKind kind : ShapeKind.values()) {
            for (Facing facing : Facing.values()) {
                regions.add(new Region.Shape(new Box(new BlockPos(-9, 60, -33), new BlockPos(24, 81, 2)), kind, facing));
            }
        }
        for (Region region : regions) {
            CellSet set = CellSet.of(region, region.cellCount());
            assertEquals(region.cellCount(), set.size(), region.toString());
            assertArrayEquals(region.sectionKeys(), set.sectionKeys(), region.toString());
            Box box = region.bounds();
            for (int x = box.min().x() - 1; x <= box.max().x() + 1; x++) {
                for (int y = box.min().y() - 1; y <= box.max().y() + 1; y++) {
                    for (int z = box.min().z() - 1; z <= box.max().z() + 1; z++) {
                        assertEquals(region.contains(x, y, z), set.contains(x, y, z), region + " at " + x + "," + y + "," + z);
                    }
                }
            }
            assertTrue(box.contains(set.bounds()));
            assertEquals(set, CellSet.of(new Region.Cells(set), set.size()));
        }
        // An even-sized cone's apex layer is empty (t = 1/20 there), so its cells stop below its box's top.
        Region.Shape cone = new Region.Shape(new Box(new BlockPos(0, 0, 0), new BlockPos(9, 9, 9)), ShapeKind.CONE, Facing.UP);
        assertEquals(new Box(new BlockPos(0, 0, 0), new BlockPos(9, 8, 9)), CellSet.of(cone, 1000).bounds());
    }

    @Test
    void materializingRefusesPastTheCapBeforeAnyWork() {
        Region huge = new Region.Cuboid(new Box(new BlockPos(0, 0, 0), new BlockPos(1_000_000, 1_000, 1_000_000)));
        RegionTooLargeException refused = assertThrows(RegionTooLargeException.class, () -> CellSet.of(huge, 100_000));
        assertEquals(huge.cellCount(), refused.cells());
        assertEquals(100_000, refused.limit());
        Region small = new Region.Cuboid(new Box(new BlockPos(0, 0, 0), new BlockPos(9, 9, 9)));
        assertThrows(RegionTooLargeException.class, () -> CellSet.of(small, 999));
        assertEquals(1000, CellSet.of(small, 1000).size());
        Region uploaded = new Region.Uploaded(Sha256.digest(new byte[0]), new Box(BlockPos.ORIGIN, BlockPos.ORIGIN), 1);
        assertThrows(IllegalStateException.class, () -> CellSet.of(uploaded, 10));
        assertThrows(IllegalStateException.class, () -> CellSet.builder().addAll(uploaded));
    }

    // =================================================================== operations

    @Test
    void unionAndSubtractMatchSetArithmetic() {
        Random rnd = new Random(9);
        for (int round = 0; round < 20; round++) {
            Set<Cell> a = randomCells(rnd, 1 + rnd.nextInt(2000));
            Set<Cell> b = randomCells(rnd, 1 + rnd.nextInt(2000));
            Set<Cell> union = new HashSet<>(a);
            union.addAll(b);
            Set<Cell> difference = new HashSet<>(a);
            difference.removeAll(b);
            assertHolds(union, of(a).union(of(b)));
            assertHolds(difference, of(a).subtract(of(b)));
            assertEquals(of(union), of(a).union(of(b)));
        }
        CellSet cube = CellSet.of(new Region.Cuboid(new Box(new BlockPos(0, 0, 0), new BlockPos(31, 15, 15))), 1 << 20);
        CellSet half = CellSet.of(new Region.Cuboid(new Box(new BlockPos(0, 0, 0), new BlockPos(15, 15, 15))), 1 << 20);
        assertEquals(8192, cube.size());
        assertEquals(4096, cube.subtract(half).size());
        assertFalse(cube.subtract(half).contains(15, 15, 15));
        assertTrue(cube.subtract(cube).isEmpty());
        assertSame(cube, cube.union(CellSet.empty()));
        assertSame(cube, CellSet.empty().union(cube));
        assertEquals(cube, half.union(cube.subtract(half)));
    }

    @Test
    void translationMovesEveryCell() {
        Random rnd = new Random(13);
        Set<Cell> cells = randomCells(rnd, 2500);
        CellSet set = of(cells);
        int[][] offsets = {{5, -17, 33}, {16, -32, 0}, {-1, 0, 0}, {0, 0, 15}, {-160, 48, 16}, {7, 7, 7}};
        for (int[] d : offsets) {
            Set<Cell> moved = new HashSet<>();
            for (Cell cell : cells) moved.add(new Cell(cell.x() + d[0], cell.y() + d[1], cell.z() + d[2]));
            CellSet translated = set.translate(d[0], d[1], d[2]);
            assertHolds(moved, translated);
            assertEquals(of(moved), translated);
            assertEquals(of(moved).hash(), translated.hash());
            assertEquals(set, translated.translate(-d[0], -d[1], -d[2]));
        }
        assertSame(set, set.translate(0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> set.translate(0, 1 << 30, 0));
    }

    // =================================================================== encoding

    @Test
    void encodingRoundTripsExactly() throws CellSetFormatException {
        Random rnd = new Random(21);
        List<CellSet> sets = new ArrayList<>();
        for (int round = 0; round < 10; round++) sets.add(of(randomCells(rnd, 1 + rnd.nextInt(5000))));
        sets.add(CellSet.empty());
        sets.add(CellSet.of(new Region.Cuboid(new Box(new BlockPos(-40, -64, -40), new BlockPos(40, 30, 40))), 1 << 21));
        sets.add(CellSet.builder().add(BlockBuffer.MIN_SECTION_XZ << 4, BlockBuffer.MIN_SECTION_Y << 4, BlockBuffer.MAX_SECTION_XZ << 4)
                .add((BlockBuffer.MAX_SECTION_XZ << 4) + 15, (BlockBuffer.MAX_SECTION_Y << 4) + 15, 0).build());
        for (CellSet set : sets) {
            byte[] encoded = set.encode();
            CellSet decoded = CellSet.decode(encoded, LIMITS);
            assertEquals(set, decoded);
            assertEquals(set.hash(), decoded.hash());
            assertArrayEquals(set.sectionKeys(), decoded.sectionKeys());
            if (!set.isEmpty()) assertEquals(set.bounds(), decoded.bounds());
            assertArrayEquals(encoded, decoded.encode());
        }
    }

    @Test
    void theHashIsOfTheCanonicalBodyAndIndependentOfHowTheSetWasMade() {
        Random rnd = new Random(34);
        List<Cell> cells = new ArrayList<>(randomCells(rnd, 4000));
        CellSet forward = of(cells);
        Collections.shuffle(cells, rnd);
        CellSet shuffled = of(cells);
        CellSet halves = of(cells.subList(0, 2000)).union(of(cells.subList(2000, cells.size())));
        assertEquals(forward, shuffled);
        assertEquals(forward, halves);
        assertEquals(forward.hashCode(), halves.hashCode());
        assertEquals(forward.hash(), shuffled.hash());
        assertEquals(forward.hash(), halves.hash());
        assertArrayEquals(forward.encode(), halves.encode());
        CellSet fewer = of(cells.subList(1, cells.size()));
        assertNotEquals(forward.hash(), fewer.hash());
        assertNotEquals(forward, fewer);
        // A full section made cell by cell or from a box is the same set.
        CellSet.Builder byCell = CellSet.builder();
        for (int i = 0; i < 4096; i++) byCell.add(16 + (i & 15), i >> 8, (i >> 4) & 15);
        CellSet box = CellSet.of(new Region.Cuboid(new Box(new BlockPos(16, 0, 0), new BlockPos(31, 15, 15))), 4096);
        assertEquals(box, byCell.build());
        assertEquals(box.hash(), byCell.build().hash());

        // The body, written by hand: one cell at (1, 2, 3), then a full section at section (-1, 0, 2).
        Body body = new Body().varlong(1).varlong(1).zigzag(0).zigzag(0).zigzag(0).u8(1);
        long[] bitmap = new long[64];
        int index = (2 << 8) | (3 << 4) | 1;
        bitmap[index >>> 6] = 1L << (index & 63);
        body.longs(bitmap);
        assertEquals(Sha256.digest(body.bytes()), CellSet.builder().add(1, 2, 3).build().hash());
        Body full = new Body().varlong(4096).varlong(1).zigzag(-1).zigzag(0).zigzag(2).u8(0);
        assertEquals(Sha256.digest(full.bytes()),
                CellSet.of(new Region.Cuboid(new Box(new BlockPos(-16, 0, 32), new BlockPos(-1, 15, 47))), 4096).hash());
    }

    @Test
    void sectionsAreEncodedInXThenYThenZOrder() throws CellSetFormatException {
        // Sections (0,1,0) and (0,0,1): in x, y, z order (0,0,1) comes first.
        Body body = new Body().varlong(2).varlong(2)
                .zigzag(0).zigzag(0).zigzag(1).u8(1).longs(oneCell())
                .zigzag(0).zigzag(1).zigzag(0).u8(1).longs(oneCell());
        CellSet set = CellSet.decode(encoded(body), LIMITS);
        assertTrue(set.contains(0, 0, 16) && set.contains(0, 16, 0));
        assertEquals(Sha256.digest(body.bytes()), set.hash());
        // Region order is x, then z, then y.
        assertArrayEquals(new long[] {BlockBuffer.key(0, 1, 0), BlockBuffer.key(0, 0, 1)}, set.sectionKeys());
    }

    @Test
    void decodingRefusesMalformedInput() {
        byte[] good = CellSet.of(new Region.Shape(new Box(new BlockPos(-5, 0, -5), new BlockPos(40, 20, 40)),
                ShapeKind.ELLIPSOID, Facing.UP), 1 << 20).encode();
        // Truncated anywhere.
        for (int length : new int[] {0, 3, 4, 5, 6, good.length / 2, good.length - 1}) {
            assertMalformed(java.util.Arrays.copyOf(good, length), "truncated to " + length);
        }
        // Magic, version, and bytes after the compressed body.
        byte[] magic = good.clone();
        magic[0] = 'X';
        assertMalformed(magic, "magic");
        byte[] version = good.clone();
        version[4] = 2;
        assertMalformed(version, "version");
        byte[] trailing = java.util.Arrays.copyOf(good, good.length + 1);
        assertMalformed(trailing, "trailing compressed bytes");
        byte[] damaged = good.clone();
        damaged[damaged.length - 1] ^= 0x55; // the zlib checksum
        assertMalformed(damaged, "damaged zlib");

        long[] one = oneCell();
        // Sections out of order, repeated, an empty bitmap, a full bitmap, a bad mode.
        assertMalformed(encoded(new Body().varlong(2).varlong(2).zigzag(1).zigzag(0).zigzag(0).u8(1).longs(one)
                .zigzag(0).zigzag(0).zigzag(0).u8(1).longs(one)), "misordered");
        assertMalformed(encoded(new Body().varlong(2).varlong(2).zigzag(0).zigzag(1).zigzag(0).u8(1).longs(one)
                .zigzag(0).zigzag(0).zigzag(5).u8(1).longs(one)), "misordered in y");
        assertMalformed(encoded(new Body().varlong(2).varlong(2).zigzag(3).zigzag(0).zigzag(0).u8(1).longs(one)
                .zigzag(3).zigzag(0).zigzag(0).u8(1).longs(one)), "duplicate");
        assertMalformed(encoded(new Body().varlong(0).varlong(1).zigzag(0).zigzag(0).zigzag(0).u8(1).longs(new long[64])),
                "empty bitmap");
        long[] all = new long[64];
        java.util.Arrays.fill(all, -1L);
        assertMalformed(encoded(new Body().varlong(4096).varlong(1).zigzag(0).zigzag(0).zigzag(0).u8(1).longs(all)),
                "full bitmap");
        assertMalformed(encoded(new Body().varlong(4096).varlong(1).zigzag(0).zigzag(0).zigzag(0).u8(2)), "mode");
        // Wrong counts, a body cut short or running on, a section out of range, a non-minimal varint.
        assertMalformed(encoded(new Body().varlong(2).varlong(1).zigzag(0).zigzag(0).zigzag(0).u8(1).longs(one)), "count high");
        assertMalformed(encoded(new Body().varlong(4095).varlong(1).zigzag(0).zigzag(0).zigzag(0).u8(0)), "count low");
        assertMalformed(encoded(new Body().varlong(1).varlong(2).zigzag(0).zigzag(0).zigzag(0).u8(1).longs(one)),
                "missing section");
        assertMalformed(encoded(new Body().varlong(1).varlong(1).zigzag(0).zigzag(0).zigzag(0).u8(1).longs(one).u8(0)),
                "trailing body bytes");
        assertMalformed(encoded(new Body().varlong(4096).varlong(1).zigzag(BlockBuffer.MAX_SECTION_XZ + 1).zigzag(0)
                .zigzag(0).u8(0)), "section out of range");
        assertMalformed(encoded(new Body().u8(0x81).u8(0x00).varlong(1).zigzag(0).zigzag(0).zigzag(0).u8(1).longs(one)),
                "non-minimal varint");
        assertMalformed(encoded(new Body().varlong(1).varlong(1).zigzag(0).zigzag(0).zigzag(0).u8(1).u8(0)), "short bitmap");
    }

    @Test
    void decodingRefusesInputOverItsLimits() throws CellSetFormatException {
        CellSet set = CellSet.of(new Region.Cuboid(new Box(new BlockPos(0, 0, 0), new BlockPos(47, 15, 3))), 1 << 20);
        byte[] encoded = set.encode();
        assertEquals(set, CellSet.decode(encoded, CellSet.Limits.of(set.size(), 3)));
        assertTooLarge(encoded, CellSet.Limits.of(set.size() - 1, 3), "cells");
        assertTooLarge(encoded, CellSet.Limits.of(set.size(), 2), "sections");
        assertTooLarge(encoded, new CellSet.Limits(set.size(), 3, encoded.length - 1, 1 << 20), "compressed bytes");
        assertTooLarge(encoded, new CellSet.Limits(set.size(), 3, 1 << 20, 64), "inflated bytes");
        // A body that inflates to exactly the cap is fine.
        Body body = new Body().varlong(1).varlong(1).zigzag(0).zigzag(0).zigzag(0).u8(1).longs(oneCell());
        int exact = body.bytes().length;
        assertEquals(1, CellSet.decode(encoded(body), new CellSet.Limits(10, 10, 1 << 20, exact)).size());
        assertTooLarge(encoded(body), new CellSet.Limits(10, 10, 1 << 20, exact - 1), "one byte over");
        // A zip bomb stops at the cap.
        Body bomb = new Body().varlong(1).varlong(1).zigzag(0).zigzag(0).zigzag(0).u8(1).longs(oneCell());
        for (int i = 0; i < 1 << 20; i++) bomb.u8(0);
        assertTooLarge(encoded(bomb), new CellSet.Limits(10, 10, 1 << 20, 1 << 16), "bomb");
        assertThrows(IllegalArgumentException.class, () -> new CellSet.Limits(0, 1, 1, 1));
    }

    @Test
    void estimatedBytesFollowTheBitmaps() {
        CellSet full = CellSet.of(new Region.Cuboid(new Box(new BlockPos(0, 0, 0), new BlockPos(15, 15, 15))), 4096);
        CellSet sparse = CellSet.builder().add(0, 0, 0).add(100, 0, 0).build();
        assertTrue(full.estimatedBytes() > 0);
        assertTrue(sparse.estimatedBytes() > full.estimatedBytes());
        assertTrue(sparse.estimatedBytes() >= 2 * 512);
    }

    // =================================================================== helpers

    private static long[] oneCell() {
        long[] bitmap = new long[64];
        bitmap[0] = 1;
        return bitmap;
    }

    private static void assertMalformed(byte[] data, String what) {
        CellSetFormatException e = assertThrows(CellSetFormatException.class, () -> CellSet.decode(data, LIMITS), what);
        assertFalse(e.tooLarge(), what + ": " + e.getMessage());
    }

    private static void assertTooLarge(byte[] data, CellSet.Limits limits, String what) {
        CellSetFormatException e = assertThrows(CellSetFormatException.class, () -> CellSet.decode(data, limits), what);
        assertTrue(e.tooLarge(), what + ": " + e.getMessage());
    }

    private static byte[] encoded(Body body) {
        byte[] raw = body.bytes();
        Deflater deflater = new Deflater();
        deflater.setInput(raw);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {'B', 'S', 'C', 'S', 1});
        byte[] chunk = new byte[8192];
        while (!deflater.finished()) out.write(chunk, 0, deflater.deflate(chunk));
        deflater.end();
        return out.toByteArray();
    }

    /** A hand-written body. */
    private static final class Body {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Body u8(int value) {
            out.write(value);
            return this;
        }

        Body varlong(long value) {
            while ((value & ~0x7FL) != 0) {
                out.write((int) (value & 0x7F) | 0x80);
                value >>>= 7;
            }
            out.write((int) value);
            return this;
        }

        Body zigzag(int value) {
            return varlong(((value << 1) ^ (value >> 31)) & 0xFFFFFFFFL);
        }

        Body longs(long[] words) {
            for (long word : words) {
                for (int shift = 56; shift >= 0; shift -= 8) out.write((int) (word >>> shift));
            }
            return this;
        }

        byte[] bytes() {
            return out.toByteArray();
        }
    }
}
