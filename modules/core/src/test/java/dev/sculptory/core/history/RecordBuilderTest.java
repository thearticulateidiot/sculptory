package dev.sculptory.core.history;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.NbtBytes;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.testing.FakeStateSpace;
import org.junit.jupiter.api.Test;

class RecordBuilderTest {
    private final FakeStateSpace states = new FakeStateSpace();
    private final int air = states.air();
    private final int stone = states.state("minecraft:stone");
    private final int dirt = states.state("minecraft:dirt");
    private final int sand = states.state("minecraft:sand");
    private final int chest = states.state("minecraft:chest[facing=north]");
    private final NbtBytes itemsA = new NbtBytes("minecraft:chest", new byte[] {10, 1, 2});
    private final NbtBytes itemsACopy = new NbtBytes("minecraft:chest", new byte[] {10, 1, 2});
    private final NbtBytes itemsB = new NbtBytes("minecraft:chest", new byte[] {10, 3});

    @Test
    void keepsFirstBeforeAndLastAfter() {
        RecordBuilder builder = new RecordBuilder();
        builder.record(1, 2, 3, air, null, stone, null);
        builder.record(1, 2, 3, stone, null, dirt, null);
        builder.record(1, 2, 3, dirt, null, sand, null);
        EditRecord record = builder.build();
        assertEquals(air, record.before().get(1, 2, 3));
        assertEquals(sand, record.after().get(1, 2, 3));
        assertEquals(1, record.before().cellCount());
        assertEquals(1, record.after().cellCount());
    }

    @Test
    void dropsCellsThatEndWhereTheyStarted() {
        RecordBuilder builder = new RecordBuilder();
        builder.record(0, 0, 0, stone, null, air, null);
        builder.record(0, 0, 0, air, null, stone, null);
        builder.record(5, 0, 0, dirt, null, dirt, null);
        builder.record(6, 0, 0, dirt, null, sand, null);
        EditRecord record = builder.build();
        assertEquals(1, record.before().cellCount());
        assertEquals(-1, record.before().get(0, 0, 0));
        assertEquals(-1, record.after().get(5, 0, 0));
        assertEquals(dirt, record.before().get(6, 0, 0));
    }

    @Test
    void tilesFollowTheSameRules() {
        RecordBuilder builder = new RecordBuilder();
        // Same state, same tile content: dropped.
        builder.record(0, 0, 0, chest, itemsA, chest, itemsACopy);
        // Same state, different tile: kept, with both tiles.
        builder.record(1, 0, 0, chest, itemsA, chest, itemsB);
        // Tile removed: kept.
        builder.record(2, 0, 0, chest, itemsA, air, null);
        // Coalesced: first before tile, last after tile.
        builder.record(3, 0, 0, air, null, chest, itemsA);
        builder.record(3, 0, 0, chest, itemsA, chest, itemsB);
        // Placed then broken again: dropped.
        builder.record(4, 0, 0, air, null, chest, itemsA);
        builder.record(4, 0, 0, chest, itemsA, air, null);
        EditRecord record = builder.build();
        assertEquals(3, record.before().cellCount());
        assertEquals(-1, record.before().get(0, 0, 0));
        assertSame(itemsA, record.before().tile(1, 0, 0));
        assertSame(itemsB, record.after().tile(1, 0, 0));
        assertSame(itemsA, record.before().tile(2, 0, 0));
        assertNull(record.after().tile(2, 0, 0));
        assertEquals(air, record.after().get(2, 0, 0));
        assertNull(record.before().tile(3, 0, 0));
        assertEquals(air, record.before().get(3, 0, 0));
        assertSame(itemsB, record.after().tile(3, 0, 0));
        assertEquals(-1, record.before().get(4, 0, 0));
    }

    @Test
    void storesPerSectionAndBothSidesHoldTheSameCells() {
        RecordBuilder builder = new RecordBuilder();
        builder.record(-1, -1, -1, stone, null, air, null);
        builder.record(0, 0, 0, stone, null, air, null);
        builder.record(40, 100, -70, stone, null, dirt, null);
        builder.record(41, 100, -70, stone, null, stone, null);
        EditRecord record = builder.build();
        long[] keys = {BlockBuffer.key(-1, -1, -1), BlockBuffer.key(0, 0, 0), BlockBuffer.key(2, 6, -5)};
        java.util.Arrays.sort(keys);
        assertArrayEquals(keys, record.before().sortedKeys());
        assertArrayEquals(keys, record.after().sortedKeys());
        assertEquals(3, record.after().cellCount());
        assertTrue(record.estimatedBytes() > 0);
        assertEquals(record.estimatedBytes(), 32 + record.before().estimatedBytes() + record.after().estimatedBytes());
    }

    @Test
    void buildIsRepeatableAndRecordingContinues() {
        RecordBuilder builder = new RecordBuilder();
        builder.record(0, 0, 0, stone, null, air, null);
        EditRecord first = builder.build();
        builder.record(0, 0, 0, air, null, dirt, null);
        builder.record(1, 0, 0, air, null, dirt, null);
        EditRecord second = builder.build();
        assertEquals(air, first.after().get(0, 0, 0));
        assertEquals(stone, second.before().get(0, 0, 0));
        assertEquals(dirt, second.after().get(0, 0, 0));
        assertEquals(2, second.before().cellCount());
        assertTrue(builder.estimatedBytes() > 0);
    }

    @Test
    void recordSectionRecordsWrittenCells() {
        SectionBuffer before = SectionBuffer.uniform(stone);
        before.set(SectionBuffer.index(1, 1, 1), chest);
        before.setTile(SectionBuffer.index(1, 1, 1), itemsA);
        SectionBuffer written = new SectionBuffer();
        written.set(SectionBuffer.index(1, 1, 1), air);
        written.set(SectionBuffer.index(15, 15, 15), dirt);
        RecordBuilder builder = new RecordBuilder();
        builder.recordSection(BlockBuffer.key(-2, 3, 1), before, written);
        EditRecord record = builder.build();
        assertEquals(2, record.before().cellCount());
        assertEquals(chest, record.before().get(-31, 49, 17));
        assertSame(itemsA, record.before().tile(-31, 49, 17));
        assertEquals(air, record.after().get(-31, 49, 17));
        assertEquals(stone, record.before().get(-17, 63, 31));
        assertEquals(dirt, record.after().get(-17, 63, 31));
    }

    @Test
    void refusesNegativeHandles() {
        RecordBuilder builder = new RecordBuilder();
        assertThrows(IllegalArgumentException.class, () -> builder.record(0, 0, 0, -1, null, stone, null));
        assertThrows(IllegalArgumentException.class, () -> builder.record(0, 0, 0, stone, null, -1, null));
        assertTrue(builder.build().before().isEmpty());
    }
}
