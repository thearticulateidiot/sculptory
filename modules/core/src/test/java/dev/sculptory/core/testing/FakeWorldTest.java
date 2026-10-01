package dev.sculptory.core.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.NbtBytes;
import dev.sculptory.core.buffer.SectionBuffer;
import org.junit.jupiter.api.Test;

class FakeWorldTest {
    private final FakeStateSpace states = new FakeStateSpace();
    private final FakeWorld world = new FakeWorld(states);

    @Test
    void absentCellsReadAsAir() {
        assertEquals(states.air(), world.get(5, 64, 5));
        int stone = states.state("minecraft:stone");
        world.set(-5, -64, -5, stone);
        assertEquals(stone, world.get(-5, -64, -5));
        assertEquals(states.air(), world.get(-5, -65, -5));
        assertEquals(states.air(), world.get(0, 320, 0));
        assertThrows(IllegalArgumentException.class, () -> world.set(0, 320, 0, stone));
        assertEquals(-64, world.bottomY());
        assertEquals(320, world.topYExclusive());
        assertSame(states, world.states());
    }

    @Test
    void loadedChunkControl() {
        assertTrue(world.isLoaded(100, -100));
        world.setLoaded(2, -3, false);
        assertFalse(world.isLoaded(2, -3));
        assertThrows(IllegalStateException.class, () -> world.get(32, 64, -48));
        assertEquals(states.air(), world.get(31, 64, -48));
        world.setLoadedByDefault(false);
        assertFalse(world.isLoaded(0, 0));
        world.setLoaded(0, 0, true);
        assertTrue(world.isLoaded(0, 0));
        assertThrows(IllegalStateException.class, () -> world.copySection(5, 0, 5, new SectionBuffer()));
    }

    @Test
    void copySectionIsDenseWithTiles() {
        int chest = states.state("minecraft:chest[facing=east]");
        int dirt = states.state("minecraft:dirt");
        world.fill(Box.of(new BlockPos(16, 0, 16), new BlockPos(17, 1, 16)), dirt);
        world.set(20, 5, 20, chest);
        NbtBytes nbt = new NbtBytes("minecraft:chest", new byte[] {10, 0});
        world.setTile(20, 5, 20, nbt);
        assertSame(nbt, world.tile(20, 5, 20));
        SectionBuffer into = new SectionBuffer();
        into.set(0, 99);
        world.copySection(1, 0, 1, into);
        assertTrue(into.isDense());
        assertEquals(dirt, into.get(SectionBuffer.index(0, 0, 0)));
        assertEquals(dirt, into.get(SectionBuffer.index(1, 1, 0)));
        assertEquals(chest, into.get(SectionBuffer.index(4, 5, 4)));
        assertEquals(states.air(), into.get(SectionBuffer.index(2, 0, 0)));
        assertSame(nbt, into.tile(SectionBuffer.index(4, 5, 4)));
        assertEquals(1, into.tileCount());
        world.copySection(-3, -4, 7, into);
        assertTrue(into.isDense());
        assertEquals(0, into.bits());
        assertEquals(states.air(), into.get(0));
        assertNull(into.tile(SectionBuffer.index(4, 5, 4)));
    }

    @Test
    void tileNeedsAState() {
        assertThrows(IllegalStateException.class,
                () -> world.setTile(1, 1, 1, new NbtBytes("minecraft:chest", new byte[0])));
    }
}
