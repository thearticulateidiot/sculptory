package dev.sculptory.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.buffer.BlockBuffer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BoxTest {
    @Test
    void blockPosArithmetic() {
        BlockPos pos = new BlockPos(1, 2, 3);
        assertEquals(new BlockPos(0, 4, 6), pos.offset(-1, 2, 3));
        assertEquals(new BlockPos(2, 4, 6), pos.add(pos));
        assertThrows(ArithmeticException.class, () -> new BlockPos(Integer.MAX_VALUE, 0, 0).offset(1, 0, 0));
        assertTrue(new BlockPos(0, 0, 1).compareTo(new BlockPos(0, 1, 0)) < 0);
    }

    @Test
    void ofNormalizesCorners() {
        Box box = Box.of(new BlockPos(5, -2, 10), new BlockPos(-3, 4, 7));
        assertEquals(new BlockPos(-3, -2, 7), box.min());
        assertEquals(new BlockPos(5, 4, 10), box.max());
        assertEquals(9, box.sizeX());
        assertEquals(7, box.sizeY());
        assertEquals(4, box.sizeZ());
        assertEquals(9L * 7 * 4, box.volume());
        assertThrows(IllegalArgumentException.class, () -> new Box(new BlockPos(1, 0, 0), new BlockPos(0, 0, 0)));
    }

    @Test
    void volumeIsLongAndSaturates() {
        Box big = new Box(new BlockPos(-30_000_000, -64, -30_000_000), new BlockPos(29_999_999, 319, 29_999_999));
        assertEquals(60_000_000L * 384 * 60_000_000L, big.volume());
        Box huge = new Box(new BlockPos(-1_000_000_000, -1_000_000_000, -1_000_000_000),
                new BlockPos(1_000_000_000, 1_000_000_000, 1_000_000_000));
        assertEquals(Long.MAX_VALUE, huge.volume());
        assertThrows(IllegalArgumentException.class,
                () -> new Box(new BlockPos(Integer.MIN_VALUE, 0, 0), new BlockPos(Integer.MAX_VALUE, 0, 0)));
    }

    @Test
    void containsAndIntersects() {
        Box box = Box.of(new BlockPos(0, 0, 0), new BlockPos(9, 9, 9));
        assertTrue(box.contains(0, 0, 0));
        assertTrue(box.contains(new BlockPos(9, 9, 9)));
        assertFalse(box.contains(10, 5, 5));
        assertFalse(box.contains(5, -1, 5));
        assertTrue(box.contains(Box.of(new BlockPos(1, 1, 1), new BlockPos(9, 9, 9))));
        assertTrue(box.intersects(Box.of(new BlockPos(9, 9, 9), new BlockPos(20, 20, 20))));
        assertFalse(box.intersects(Box.of(new BlockPos(10, 0, 0), new BlockPos(20, 9, 9))));
        assertEquals(Box.of(new BlockPos(1, 2, 3), new BlockPos(10, 11, 12)), box.offset(1, 2, 3));
    }

    @Test
    void forEachSectionKeyVisitsTouchedSectionsInOrder() {
        Box box = Box.of(new BlockPos(-1, 0, 15), new BlockPos(16, 17, 16));
        List<Long> keys = new ArrayList<>();
        box.forEachSectionKey(keys::add);
        List<Long> expected = new ArrayList<>();
        for (int sx = -1; sx <= 1; sx++) {
            for (int sz = 0; sz <= 1; sz++) {
                for (int sy = 0; sy <= 1; sy++) expected.add(BlockBuffer.key(sx, sy, sz));
            }
        }
        assertEquals(expected, keys);
        List<Long> single = new ArrayList<>();
        Box.of(new BlockPos(-16, -16, -16)).forEachSectionKey(single::add);
        assertArrayEquals(new long[] {BlockBuffer.key(-1, -1, -1)}, single.stream().mapToLong(Long::longValue).toArray());
    }
}
