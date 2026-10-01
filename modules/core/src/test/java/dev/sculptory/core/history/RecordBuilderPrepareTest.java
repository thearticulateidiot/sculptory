package dev.sculptory.core.history;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.buffer.NbtBytes;
import dev.sculptory.core.buffer.SectionBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * {@link RecordBuilder#build()} with its fast paths (whole sections copied when no state is both before and after,
 * sections {@link RecordBuilder#prepare prepared} early) gives exactly the record of the plain rule: first before, last
 * after, cells dropped where both states and tiles match. Exact records are what makes undo exact.
 */
class RecordBuilderPrepareTest {
    private static final NbtBytes[] TILES = {
            new NbtBytes("minecraft:chest", new byte[] {10, 1}),
            new NbtBytes("minecraft:chest", new byte[] {10, 1}), // same content as the first
            new NbtBytes("minecraft:chest", new byte[] {10, 2}),
    };

    /** The reference: per cell, the first before and last after; kept where they differ. */
    private static final class Model {
        final Map<Long, int[]> states = new HashMap<>();
        final Map<Long, BlockEntityData[]> tiles = new HashMap<>();

        void record(int x, int y, int z, int before, BlockEntityData bt, int after, BlockEntityData at) {
            long pos = pack(x, y, z);
            int[] s = states.get(pos);
            if (s == null) {
                states.put(pos, new int[] {before, after});
                tiles.put(pos, new BlockEntityData[] {bt, at});
            } else {
                s[1] = after;
                tiles.get(pos)[1] = at;
            }
        }

        void check(EditRecord record) {
            long kept = 0;
            for (Map.Entry<Long, int[]> entry : states.entrySet()) {
                long pos = entry.getKey();
                int x = unpackX(pos), y = unpackY(pos), z = unpackZ(pos);
                int[] s = entry.getValue();
                BlockEntityData[] t = tiles.get(pos);
                boolean same = s[0] == s[1] && RecordBuilder.sameTile(t[0], t[1]);
                if (same) {
                    assertEquals(-1, record.before().get(x, y, z), "dropped cell kept at " + x + "," + y + "," + z);
                    assertEquals(-1, record.after().get(x, y, z));
                    continue;
                }
                kept++;
                assertEquals(s[0], record.before().get(x, y, z), "before at " + x + "," + y + "," + z);
                assertEquals(s[1], record.after().get(x, y, z), "after at " + x + "," + y + "," + z);
                assertTile(t[0], record.before().tile(x, y, z));
                assertTile(t[1], record.after().tile(x, y, z));
            }
            assertEquals(kept, record.before().cellCount());
            assertEquals(kept, record.after().cellCount());
            for (long key : record.before().sortedKeys()) {
                SectionBuffer b = record.before().section(key), a = record.after().section(key);
                assertTrue(b.presentCount() > 0, "empty section kept");
                // Compact: nothing smaller than what compact() would make.
                assertEquals(b.compact().paletteSize(), b.paletteSize(), "before palette of " + key);
                assertEquals(a.compact().paletteSize(), a.paletteSize(), "after palette of " + key);
                assertEquals(b.compact().bits(), b.bits());
                assertEquals(a.compact().bits(), a.bits());
            }
        }

        private static void assertTile(BlockEntityData expected, BlockEntityData actual) {
            if (expected == null) {
                assertEquals(null, actual);
            } else {
                assertTrue(actual != null && expected.sameContent(actual), "tile " + expected + " vs " + actual);
            }
        }

        static long pack(int x, int y, int z) {
            return ((long) (x & 0x3FFFFF) << 42) | ((long) (y & 0xFFFFF) << 22) | (z & 0x3FFFFF);
        }

        static int unpackX(long p) {
            return (int) (p << 0 >> 42);
        }

        static int unpackY(long p) {
            return (int) (p << 22 >> 44);
        }

        static int unpackZ(long p) {
            return (int) (p << 42 >> 42);
        }
    }

    @Test
    void randomRecordsBuildExactlyWithAndWithoutPreparing() {
        for (long seed = 1; seed <= 60; seed++) {
            Random random = new Random(seed);
            RecordBuilder plain = new RecordBuilder();
            RecordBuilder preparing = new RecordBuilder();
            Model model = new Model();
            int ops = 2000 + random.nextInt(6000);
            for (int op = 0; op < ops; op++) {
                int sx = random.nextInt(3) - 1, sy = random.nextInt(2), sz = random.nextInt(2);
                int x = sx * 16 + random.nextInt(16), y = sy * 16 + random.nextInt(16), z = sz * 16 + random.nextInt(16);
                // Per section, pick how states relate: disjoint palettes (the fast path), overlapping, or identical;
                // small palettes (linear lookup, 1-4 bits) or large ones (over 16 entries: the hash lookup, 8 bits).
                int mode = Math.floorMod((int) seed + sx * 5 + sy * 3 + sz * 7, 5);
                int before = switch (mode) {
                    case 3 -> random.nextInt(40);
                    case 4 -> random.nextInt(300);
                    default -> random.nextInt(4);
                };
                int after = switch (mode) {
                    case 0 -> 10 + random.nextInt(4); // never a before state
                    case 1 -> random.nextInt(6);
                    case 3 -> 1000 + random.nextInt(300); // never a before state
                    case 4 -> random.nextInt(300);
                    default -> before;
                };
                BlockEntityData bt = random.nextInt(8) == 0 ? TILES[random.nextInt(TILES.length)] : null;
                BlockEntityData at = random.nextInt(8) == 0 ? TILES[random.nextInt(TILES.length)] : null;
                plain.record(x, y, z, before, bt, after, at);
                preparing.record(x, y, z, before, bt, after, at);
                model.record(x, y, z, before, bt, after, at);
                if (random.nextInt(50) == 0) preparing.prepare(BlockBuffer.keyOfBlock(x, y, z));
                if (random.nextInt(200) == 0) preparing.prepare(BlockBuffer.keyOfBlock(x + 16, y, z));
            }
            for (int sx = -1; sx <= 1; sx++) {
                if (random.nextBoolean()) preparing.prepare(BlockBuffer.key(sx, 0, 0));
            }
            model.check(plain.build());
            EditRecord built = preparing.build();
            model.check(built);
            // Building again gives the same record, in new buffers (nothing is shared between records).
            EditRecord again = preparing.build();
            model.check(again);
            for (long key : built.before().sortedKeys()) {
                assertNotSame(built.before().section(key), again.before().section(key));
                assertNotSame(built.after().section(key), again.after().section(key));
            }
        }
    }

    /**
     * Whole sections with large palettes: 40 before states and 600 disjoint after states (hash lookup, 16-bit
     * indices), and 200 states both before and after (8-bit, overlapping), with overwrites and prepare() at random
     * points; the records are exact and compact.
     */
    @Test
    void largePalettesBuildExactly() {
        for (long seed = 1; seed <= 6; seed++) {
            Random random = new Random(seed * 31);
            RecordBuilder builder = new RecordBuilder();
            Model model = new Model();
            for (int pass = 0; pass < 2; pass++) {
                for (int i = 0; i < SectionBuffer.SIZE; i++) {
                    int x = SectionBuffer.localX(i), y = SectionBuffer.localY(i), z = SectionBuffer.localZ(i);
                    if (pass == 1 && random.nextInt(8) != 0) continue; // overwrite an eighth of the cells
                    int disjointAfter = 1000 + (pass == 0 ? (i * 7) % 600 : random.nextInt(600));
                    record(builder, model, x, y, z, i % 40, disjointAfter); // section (0, 0, 0)
                    int before = (i * 13) % 200;
                    int after = pass == 0 ? (i * 29 + 5) % 200 : random.nextInt(200);
                    record(builder, model, x + 16, y, z, before, after); // section (1, 0, 0)
                    if (random.nextInt(1500) == 0) builder.prepare(BlockBuffer.keyOfBlock(x, y, z));
                    if (random.nextInt(1500) == 0) builder.prepare(BlockBuffer.keyOfBlock(x + 16, y, z));
                }
            }
            builder.prepare(BlockBuffer.key(0, 0, 0));
            EditRecord record = builder.build();
            model.check(record);
            assertEquals(16, record.after().section(BlockBuffer.key(0, 0, 0)).bits(), "600 after states");
            assertEquals(8, record.before().section(BlockBuffer.key(1, 0, 0)).bits(), "200 before states");
        }
    }

    private static void record(RecordBuilder builder, Model model, int x, int y, int z, int before, int after) {
        builder.record(x, y, z, before, null, after, null);
        model.record(x, y, z, before, null, after, null);
    }

    @Test
    void recordingIntoAPreparedSectionIsNotLost() {
        RecordBuilder builder = new RecordBuilder();
        builder.record(1, 1, 1, 0, null, 5, null);
        builder.prepare(BlockBuffer.keyOfBlock(1, 1, 1));
        builder.record(2, 1, 1, 0, null, 6, null); // same section, after preparing
        builder.record(1, 1, 1, 5, null, 0, null); // back to where it started: dropped
        EditRecord record = builder.build();
        assertEquals(-1, record.before().get(1, 1, 1));
        assertEquals(0, record.before().get(2, 1, 1));
        assertEquals(6, record.after().get(2, 1, 1));
        assertEquals(1, record.before().cellCount());
    }

    @Test
    void aDisjointSectionWithOverwrittenCellsIsCompacted() {
        RecordBuilder builder = new RecordBuilder();
        for (int i = 0; i < SectionBuffer.SIZE; i++) {
            int x = SectionBuffer.localX(i), y = SectionBuffer.localY(i), z = SectionBuffer.localZ(i);
            builder.record(x, y, z, 1, null, 10 + (i % 5), null); // 5 after states: 4 bits
        }
        for (int i = 0; i < SectionBuffer.SIZE; i++) {
            int x = SectionBuffer.localX(i), y = SectionBuffer.localY(i), z = SectionBuffer.localZ(i);
            builder.record(x, y, z, 99, null, 20, null); // all overwritten with one state
        }
        builder.prepare(0L);
        EditRecord record = builder.build();
        SectionBuffer after = record.after().section(0L);
        assertEquals(1, after.paletteSize());
        assertEquals(0, after.bits());
        assertArrayEquals(new int[] {1, 20}, new int[] {record.before().get(0, 0, 0), after.get(0)});
    }
}
