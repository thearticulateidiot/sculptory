package dev.sculptory.fabric.engine.impl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockBuffer;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.junit.jupiter.api.Test;

/** L6: clipping job sections to the world border and counting columns, as done at admission. */
class AdmissionCapsTest {
    private static long[] sections(Box box) {
        LongArrayList keys = new LongArrayList();
        box.forEachSectionKey(keys::add);
        return keys.toLongArray();
    }

    @Test
    void sectionsOutsideTheBorderAreDropped() {
        // Border from x = -8 to 40 and z = -100 to 100: chunk columns -1..2 touch it along x.
        long[] keys = sections(new Box(new BlockPos(-64, 0, 0), new BlockPos(127, 15, 15))); // cx -4..7
        EditExecutor.BorderSplit split = EditExecutor.splitByBorder(keys, -8.0, 40.0, -100.0, 100.0);
        assertEquals(4, split.inside().length);
        assertEquals(8, split.outside().length);
        for (long key : split.inside()) {
            int cx = BlockBuffer.keyX(key);
            assertEquals(true, cx >= -1 && cx <= 2, "kept column " + cx);
        }
        // A border edge exactly on a chunk boundary keeps only the chunks inside.
        EditExecutor.BorderSplit aligned = EditExecutor.splitByBorder(keys, 0.0, 32.0, -100.0, 100.0);
        assertArrayEquals(new long[] {BlockBuffer.key(0, 0, 0), BlockBuffer.key(1, 0, 0)}, aligned.inside());
        // Order is preserved.
        EditExecutor.BorderSplit all = EditExecutor.splitByBorder(keys, -1e9, 1e9, -1e9, 1e9);
        assertArrayEquals(keys, all.inside());
        assertEquals(0, all.outside().length);
    }

    @Test
    void columnsAreCountedOnceAcrossWritesAndSources() {
        long[] writes = sections(new Box(new BlockPos(0, 0, 0), new BlockPos(47, 63, 15))); // 3 columns, 4 high
        long[] sources = sections(new Box(new BlockPos(32, 0, 0), new BlockPos(79, 15, 15))); // 3 columns, 1 shared
        assertEquals(3, EditExecutor.distinctColumns(writes, new long[0]));
        assertEquals(5, EditExecutor.distinctColumns(writes, sources));
    }
}
