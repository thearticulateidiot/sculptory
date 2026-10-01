package dev.sculptory.core.buffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SectionBufferTest {
    private static final BlockEntityData CHEST = new NbtBytes("minecraft:chest", new byte[] {10, 0, 0, 0});

    @Test
    void newBufferIsEmpty() {
        SectionBuffer s = new SectionBuffer();
        assertTrue(s.isEmpty());
        assertFalse(s.isDense());
        assertEquals(0, s.presentCount());
        assertEquals(0, s.bits());
        for (int i = 0; i < SectionBuffer.SIZE; i++) {
            assertEquals(-1, s.get(i));
            assertFalse(s.has(i));
        }
    }

    @Test
    void setGetClear() {
        SectionBuffer s = new SectionBuffer();
        s.set(0, 5);
        s.set(4095, 9);
        s.set(1234, 5);
        assertEquals(5, s.get(0));
        assertEquals(9, s.get(4095));
        assertEquals(5, s.get(1234));
        assertEquals(-1, s.get(1));
        assertEquals(3, s.presentCount());
        s.set(0, 9);
        assertEquals(9, s.get(0));
        assertEquals(3, s.presentCount());
        s.clear(1234);
        assertEquals(-1, s.get(1234));
        assertFalse(s.has(1234));
        assertEquals(2, s.presentCount());
        s.clear(1234);
        assertEquals(2, s.presentCount());
        assertThrows(IndexOutOfBoundsException.class, () -> s.get(4096));
        assertThrows(IndexOutOfBoundsException.class, () -> s.set(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> s.set(0, -1));
    }

    @Test
    void indexHelpersUseVanillaOrder() {
        assertEquals(0, SectionBuffer.index(0, 0, 0));
        assertEquals(1, SectionBuffer.index(1, 0, 0));
        assertEquals(16, SectionBuffer.index(0, 0, 1));
        assertEquals(256, SectionBuffer.index(0, 1, 0));
        int i = SectionBuffer.index(3, 7, 11);
        assertEquals(3, SectionBuffer.localX(i));
        assertEquals(7, SectionBuffer.localY(i));
        assertEquals(11, SectionBuffer.localZ(i));
    }

    @Test
    void paletteGrowsThroughEveryBitWidth() {
        SectionBuffer s = new SectionBuffer();
        int[] expectedBits = new int[301];
        for (int n = 1; n <= 300; n++) {
            expectedBits[n] = n <= 1 ? 0 : n <= 2 ? 1 : n <= 4 ? 2 : n <= 16 ? 4 : n <= 256 ? 8 : 16;
        }
        List<Integer> widths = new ArrayList<>();
        for (int n = 1; n <= 300; n++) {
            // Cell n-1 gets handle 1000+n; also rewrite a spread of cells to keep indices moving.
            s.set(n - 1, 1000 + n);
            s.set(4095 - (n % 50), 1000 + n);
            assertEquals(expectedBits[n], s.bits(), "bits after " + n + " handles");
            if (widths.isEmpty() || widths.get(widths.size() - 1) != s.bits()) widths.add(s.bits());
            for (int m = 1; m <= n; m++) assertEquals(1000 + m, s.get(m - 1), "cell " + (m - 1) + " after " + n);
        }
        assertEquals(List.of(0, 1, 2, 4, 8, 16), widths);
        assertEquals(300, s.paletteSize());
    }

    @Test
    void uniformSection() {
        SectionBuffer s = SectionBuffer.uniform(7);
        assertTrue(s.isDense());
        assertEquals(0, s.bits());
        assertEquals(SectionBuffer.SIZE, s.presentCount());
        for (int i = 0; i < SectionBuffer.SIZE; i++) assertEquals(7, s.get(i));
        s.set(100, 8);
        assertEquals(1, s.bits());
        assertEquals(8, s.get(100));
        assertEquals(7, s.get(99));
        assertEquals(7, s.get(101));
        long uniformBytes = SectionBuffer.uniform(7).estimatedBytes();
        assertTrue(uniformBytes < s.estimatedBytes());
        assertThrows(IllegalArgumentException.class, () -> SectionBuffer.uniform(-1));
    }

    @Test
    void presenceBecomesDenseAndBack() {
        SectionBuffer s = new SectionBuffer();
        for (int i = 0; i < SectionBuffer.SIZE; i++) {
            assertFalse(s.isDense());
            s.set(i, i % 3);
        }
        assertTrue(s.isDense());
        assertEquals(SectionBuffer.SIZE, s.presentCount());
        s.clear(2000);
        assertFalse(s.isDense());
        assertEquals(SectionBuffer.SIZE - 1, s.presentCount());
        assertFalse(s.has(2000));
        assertTrue(s.has(1999));
        List<Integer> visited = new ArrayList<>();
        s.forEachPresent(visited::add);
        assertEquals(SectionBuffer.SIZE - 1, visited.size());
        assertFalse(visited.contains(2000));
        for (int k = 1; k < visited.size(); k++) assertTrue(visited.get(k - 1) < visited.get(k), "ascending order");
    }

    @Test
    void tiles() {
        SectionBuffer s = new SectionBuffer();
        assertThrows(IllegalStateException.class, () -> s.setTile(10, CHEST));
        s.set(10, 3);
        s.set(5, 3);
        s.setTile(10, CHEST);
        s.setTile(5, new NbtBytes("minecraft:barrel", new byte[] {1}));
        assertSame(CHEST, s.tile(10));
        assertNull(s.tile(11));
        assertEquals(2, s.tileCount());
        List<Integer> order = new ArrayList<>();
        s.forEachTile((index, data) -> order.add(index));
        assertEquals(List.of(5, 10), order);
        s.set(10, 4);
        assertSame(CHEST, s.tile(10), "changing the state keeps the tile");
        s.clear(10);
        assertNull(s.tile(10));
        s.setTile(5, null);
        assertEquals(0, s.tileCount());
    }

    @Test
    void compactionDropsUnusedPaletteEntries() {
        SectionBuffer s = new SectionBuffer();
        for (int i = 0; i < 300; i++) s.set(i, i + 1);
        assertEquals(16, s.bits());
        for (int i = 0; i < 300; i++) s.set(i, i % 2 == 0 ? 50 : 60);
        s.setTile(7, CHEST);
        assertEquals(300, s.paletteSize());
        SectionBuffer c = s.compact();
        assertEquals(2, c.paletteSize());
        assertEquals(1, c.bits());
        assertFalse(c.isDense());
        assertEquals(300, c.presentCount());
        for (int i = 0; i < SectionBuffer.SIZE; i++) assertEquals(s.get(i), c.get(i));
        assertSame(CHEST, c.tile(7));
        assertTrue(c.estimatedBytes() < s.estimatedBytes());
        assertEquals(300, s.paletteSize(), "compact leaves the original unchanged");
    }

    @Test
    void compactionOfUniformContentIsUniform() {
        SectionBuffer s = new SectionBuffer();
        for (int i = 0; i < SectionBuffer.SIZE; i++) s.set(i, i == 0 ? 1 : 2);
        s.set(0, 2);
        assertEquals(1, s.bits());
        SectionBuffer c = s.compact();
        assertEquals(0, c.bits());
        assertTrue(c.isDense());
        assertEquals(2, c.get(0));
        assertTrue(new SectionBuffer().compact().isEmpty());
    }

    @Test
    void paletteOverflowCompactsInPlace() {
        SectionBuffer s = new SectionBuffer();
        s.set(1, 42);
        for (int h = 0; h < SectionBuffer.MAX_PALETTE + 10; h++) s.set(0, h + 100);
        assertEquals(SectionBuffer.MAX_PALETTE + 109, s.get(0));
        assertEquals(42, s.get(1));
        assertTrue(s.paletteSize() <= SectionBuffer.MAX_PALETTE);
    }

    @Test
    void copyIsIndependent() {
        SectionBuffer s = new SectionBuffer();
        for (int i = 0; i < 40; i++) s.set(i, i);
        s.setTile(3, CHEST);
        SectionBuffer copy = s.copy();
        copy.set(0, 99);
        copy.clear(3);
        copy.set(100, 77);
        assertEquals(0, s.get(0));
        assertSame(CHEST, s.tile(3));
        assertEquals(-1, s.get(100));
        assertEquals(99, copy.get(0));
        assertEquals(77, copy.get(100));
        assertEquals(20, copy.get(20));
    }

    @Test
    void clearAllResets() {
        SectionBuffer s = SectionBuffer.uniform(3);
        s.setTile(0, CHEST);
        s.clearAll();
        assertTrue(s.isEmpty());
        assertEquals(0, s.tileCount());
        assertEquals(-1, s.get(0));
    }

    @Test
    void nbtBytesIsDefensiveAndComparesContent() {
        byte[] raw = {1, 2, 3};
        NbtBytes a = new NbtBytes("minecraft:chest", raw);
        raw[0] = 9;
        assertEquals(1, a.nbtBytes()[0]);
        a.nbtBytes()[1] = 9;
        assertEquals(2, a.nbtBytes()[1]);
        NbtBytes b = new NbtBytes("minecraft:chest", new byte[] {1, 2, 3});
        assertEquals(a, b);
        assertTrue(a.sameContent(b));
        assertFalse(a.sameContent(null));
        assertFalse(a.sameContent(new NbtBytes("minecraft:barrel", new byte[] {1, 2, 3})));
        assertNotEquals(a, new NbtBytes("minecraft:chest", new byte[] {1, 2}));
    }

    /** setDense (bulk section capture) equals setting every cell in index order on an empty buffer. */
    @Test
    void setDenseEqualsSettingEveryCellInOrder() {
        java.util.Random random = new java.util.Random(5);
        for (int distinct : new int[] {1, 2, 3, 4, 5, 16, 17, 200, 256, 257, 1000, 4096}) {
            for (boolean runs : new boolean[] {false, true}) {
                int[] handles = new int[SectionBuffer.SIZE];
                for (int i = 0; i < handles.length; i++) {
                    handles[i] = runs ? 7 * ((i / 37) % distinct) : 3 + random.nextInt(distinct) * 11;
                }
                if (distinct == 4096 && !runs) {
                    for (int i = 0; i < handles.length; i++) handles[i] = 4095 - i; // every cell its own state
                }
                SectionBuffer expected = new SectionBuffer();
                for (int i = 0; i < handles.length; i++) expected.set(i, handles[i]);
                SectionBuffer actual = new SectionBuffer();
                actual.set(9, 12345); // replaced, as is its tile
                actual.setTile(9, CHEST);
                actual.setDense(handles);
                String what = distinct + " states" + (runs ? " in runs" : "");
                assertTrue(actual.isDense(), what);
                assertEquals(SectionBuffer.SIZE, actual.presentCount(), what);
                assertEquals(expected.paletteSize(), actual.paletteSize(), what);
                assertEquals(expected.bits(), actual.bits(), what);
                assertEquals(expected.estimatedBytes(), actual.estimatedBytes(), what);
                assertEquals(0, actual.tileCount(), what);
                for (int i = 0; i < handles.length; i++) assertEquals(handles[i], actual.get(i), what + " at " + i);
                // Still an ordinary buffer: later edits work.
                actual.set(100, 99_999);
                assertEquals(99_999, actual.get(100));
                assertEquals(handles[101], actual.get(101));
            }
        }
    }

    @Test
    void setDenseRefusesBadInput() {
        SectionBuffer s = new SectionBuffer();
        assertThrows(IllegalArgumentException.class, () -> s.setDense(new int[10]));
        int[] handles = new int[SectionBuffer.SIZE];
        handles[4000] = -1;
        assertThrows(IllegalArgumentException.class, () -> s.setDense(handles));
        int[] first = new int[SectionBuffer.SIZE];
        first[0] = -1; // the first cell has no cell before it to be compared with
        assertThrows(IllegalArgumentException.class, () -> s.setDense(first));
        int[] all = new int[SectionBuffer.SIZE];
        java.util.Arrays.fill(all, -1);
        assertThrows(IllegalArgumentException.class, () -> s.setDense(all));
    }
}
