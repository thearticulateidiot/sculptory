package dev.sculptory.core.edit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockBuffer;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SectionOrderTest {
    @Test
    void sortMatchesBoxOrderWithNegativeCoordinates() {
        Box box = Box.of(new BlockPos(-300, -64, -45), new BlockPos(70, 319, 180));
        LongArrayList expected = new LongArrayList();
        box.forEachSectionKey(expected::add);
        long[] shuffled = expected.toLongArray();
        Random random = new Random(7);
        for (int i = shuffled.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            long swap = shuffled[i];
            shuffled[i] = shuffled[j];
            shuffled[j] = swap;
        }
        SectionOrder.sort(shuffled);
        assertArrayEquals(expected.toLongArray(), shuffled);
        assertEquals(expected.size(), SectionOrder.sectionCount(box));
    }

    @Test
    void extremeKeysSortSigned() {
        long[] keys = {
                BlockBuffer.key(BlockBuffer.MAX_SECTION_XZ, 0, 0),
                BlockBuffer.key(0, BlockBuffer.MIN_SECTION_Y, BlockBuffer.MAX_SECTION_XZ),
                BlockBuffer.key(0, BlockBuffer.MAX_SECTION_Y, BlockBuffer.MIN_SECTION_XZ),
                BlockBuffer.key(BlockBuffer.MIN_SECTION_XZ, 0, 0),
                BlockBuffer.key(0, BlockBuffer.MIN_SECTION_Y, BlockBuffer.MIN_SECTION_XZ),
        };
        SectionOrder.sort(keys);
        assertArrayEquals(new long[] {
                BlockBuffer.key(BlockBuffer.MIN_SECTION_XZ, 0, 0),
                BlockBuffer.key(0, BlockBuffer.MIN_SECTION_Y, BlockBuffer.MIN_SECTION_XZ),
                BlockBuffer.key(0, BlockBuffer.MAX_SECTION_Y, BlockBuffer.MIN_SECTION_XZ),
                BlockBuffer.key(0, BlockBuffer.MIN_SECTION_Y, BlockBuffer.MAX_SECTION_XZ),
                BlockBuffer.key(BlockBuffer.MAX_SECTION_XZ, 0, 0),
        }, keys);
    }

    @Test
    void sectionCountSaturates() {
        int low = Integer.MIN_VALUE / 2 + 1;
        Box everything = Box.of(new BlockPos(low, low, low),
                new BlockPos(Integer.MAX_VALUE / 2, Integer.MAX_VALUE / 2, Integer.MAX_VALUE / 2));
        assertEquals(Long.MAX_VALUE, SectionOrder.sectionCount(everything));
    }
}
