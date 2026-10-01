package dev.sculptory.core.buffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BlockBufferTest {
    @Test
    void keyMatchesVanillaSectionLayout() {
        assertEquals(0L, BlockBuffer.key(0, 0, 0));
        assertEquals(1L << 42, BlockBuffer.key(1, 0, 0));
        assertEquals(1L << 20, BlockBuffer.key(0, 0, 1));
        assertEquals(1L, BlockBuffer.key(0, 1, 0));
        assertEquals(-1L, BlockBuffer.key(-1, -1, -1));
    }

    @Test
    void keyRoundTripsSignedCoordinates() {
        int[][] cases = {
            {0, 0, 0}, {-1, -1, -1}, {1, -4, 1}, {-1_875_000, -4, 1_875_000},
            {BlockBuffer.MIN_SECTION_XZ, BlockBuffer.MIN_SECTION_Y, BlockBuffer.MIN_SECTION_XZ},
            {BlockBuffer.MAX_SECTION_XZ, BlockBuffer.MAX_SECTION_Y, BlockBuffer.MAX_SECTION_XZ},
        };
        Random random = new Random(7);
        Set<Long> keys = new HashSet<>();
        for (int n = 0; n < 2000; n++) {
            int[] c = n < cases.length ? cases[n] : new int[] {
                random.nextInt(1 << 22) - (1 << 21), random.nextInt(1 << 20) - (1 << 19), random.nextInt(1 << 22) - (1 << 21)};
            long key = BlockBuffer.key(c[0], c[1], c[2]);
            assertEquals(c[0], BlockBuffer.keyX(key));
            assertEquals(c[1], BlockBuffer.keyY(key));
            assertEquals(c[2], BlockBuffer.keyZ(key));
            keys.add(key);
        }
        assertEquals(2000, keys.size());
        assertThrows(IllegalArgumentException.class, () -> BlockBuffer.key(BlockBuffer.MAX_SECTION_XZ + 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> BlockBuffer.key(0, BlockBuffer.MIN_SECTION_Y - 1, 0));
    }

    @Test
    void negativeCoordinatesLandInTheRightSection() {
        BlockBuffer b = new BlockBuffer();
        b.set(-1, -1, -1, 5);
        b.set(0, 0, 0, 6);
        b.set(-16, -64, -17, 7);
        assertEquals(5, b.get(-1, -1, -1));
        assertEquals(6, b.get(0, 0, 0));
        assertEquals(7, b.get(-16, -64, -17));
        assertEquals(-1, b.get(-2, -1, -1));
        SectionBuffer section = b.section(BlockBuffer.key(-1, -1, -1));
        assertEquals(5, section.get(SectionBuffer.index(15, 15, 15)));
        assertEquals(7, b.section(BlockBuffer.key(-1, -4, -2)).get(SectionBuffer.index(0, 0, 15)));
        assertEquals(3, b.sectionCount());
    }

    @Test
    void cellCountBoundsAndClear() {
        BlockBuffer b = new BlockBuffer();
        assertTrue(b.isEmpty());
        assertNull(b.bounds());
        for (int x = -20; x < 20; x++) b.set(x, 64, 3, 1);
        b.set(5, -10, 40, 2);
        assertEquals(41, b.cellCount());
        assertEquals(Box.of(new BlockPos(-20, -10, 3), new BlockPos(19, 64, 40)), b.bounds());
        b.clear(5, -10, 40);
        assertEquals(40, b.cellCount());
        assertEquals(Box.of(new BlockPos(-20, 64, 3), new BlockPos(19, 64, 3)), b.bounds());
        assertFalse(b.has(5, -10, 40));
        assertTrue(b.has(0, 64, 3));
    }

    @Test
    void denseSectionBounds() {
        BlockBuffer b = new BlockBuffer();
        b.putSection(BlockBuffer.key(-2, 0, 3), SectionBuffer.uniform(4));
        assertEquals(4096, b.cellCount());
        assertEquals(Box.of(new BlockPos(-32, 0, 48), new BlockPos(-17, 15, 63)), b.bounds());
        assertEquals(4, b.get(-20, 5, 50));
    }

    @Test
    void tilesAndCompaction() {
        BlockBuffer b = new BlockBuffer();
        NbtBytes chest = new NbtBytes("minecraft:chest", new byte[] {10, 0, 0});
        assertThrows(IllegalStateException.class, () -> b.setTile(1, 2, 3, chest));
        b.set(1, 2, 3, 9);
        b.setTile(1, 2, 3, chest);
        assertSame(chest, b.tile(1, 2, 3));
        assertNull(b.tile(1, 2, 4));
        assertNull(b.tile(100, 2, 4));
        b.set(40, 2, 3, 9);
        b.clear(40, 2, 3);
        assertEquals(2, b.sectionCount(), "emptied sections stay until compaction");
        BlockBuffer compacted = b.compact();
        assertEquals(1, compacted.sectionCount());
        assertEquals(9, compacted.get(1, 2, 3));
        assertSame(chest, compacted.tile(1, 2, 3));
        assertTrue(b.estimatedBytes() > compacted.estimatedBytes());
    }

    @Test
    void keysAreReadOnlyAndSortable() {
        BlockBuffer b = new BlockBuffer();
        b.set(100, 0, 0, 1);
        b.set(-100, 0, 0, 1);
        b.set(0, 0, 0, 1);
        long[] sorted = b.sortedKeys();
        assertEquals(3, sorted.length);
        for (int i = 1; i < sorted.length; i++) assertTrue(sorted[i - 1] < sorted[i]);
        assertThrows(UnsupportedOperationException.class, () -> b.keys().add(5L));
        assertEquals(3, b.keys().size());
        SectionBuffer removed = b.removeSection(BlockBuffer.key(0, 0, 0));
        assertEquals(1, removed.get(0));
        long[] expected = {BlockBuffer.key(-7, 0, 0), BlockBuffer.key(6, 0, 0)};
        Arrays.sort(expected);
        assertArrayEquals(expected, b.sortedKeys());
    }
}
