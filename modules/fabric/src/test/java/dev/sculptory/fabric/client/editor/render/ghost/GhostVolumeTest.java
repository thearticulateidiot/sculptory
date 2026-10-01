package dev.sculptory.fabric.client.editor.render.ghost;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.SectionBuffer;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.Test;

class GhostVolumeTest {
    /** Handle 0 plays air: cells holding it are erase cells. */
    private static final int AIR = 0;
    private static final int STONE = 1;
    private static final int DIRT = 2;
    private static final IntPredicate ERASES = handle -> handle == AIR;

    private static Box box(int x1, int y1, int z1, int x2, int y2, int z2) {
        return new Box(new BlockPos(x1, y1, z1), new BlockPos(x2, y2, z2));
    }

    @Test
    void buildsSectionsFromABlockBuffer() {
        BlockBuffer buffer = new BlockBuffer();
        buffer.set(0, 0, 0, STONE);
        buffer.set(15, 15, 15, DIRT);
        buffer.set(16, 0, 0, STONE); // next section in x
        buffer.set(-1, -1, -1, AIR); // an erase cell in section (-1, -1, -1)
        buffer.sectionOrCreate(BlockBuffer.key(5, 5, 5)); // empty sections are skipped

        GhostVolume volume = GhostVolume.of(buffer, ERASES);

        assertEquals(3, volume.sectionCount());
        assertEquals(4, volume.cellCount());
        assertEquals(3, volume.blockCount());
        assertEquals(1, volume.eraseCount());
        assertEquals(box(-1, -1, -1, 16, 15, 15), volume.bounds());
        assertEquals(volume.bounds(), volume.frame());
        assertEquals(STONE, volume.handle(0, 0, 0));
        assertEquals(DIRT, volume.handle(15, 15, 15));
        assertEquals(AIR, volume.handle(-1, -1, -1));
        assertEquals(-1, volume.handle(1, 0, 0));
        assertNull(volume.section(5, 5, 5));
        assertArrayEquals(new long[] {BlockBuffer.key(-1, -1, -1), BlockBuffer.key(0, 0, 0), BlockBuffer.key(1, 0, 0)},
                volume.sortedKeys());
    }

    @Test
    void sectionsKnowTheirCountsBoundsAndEraseCells() {
        SectionBuffer cells = new SectionBuffer();
        cells.set(SectionBuffer.index(2, 3, 4), STONE);
        cells.set(SectionBuffer.index(5, 3, 4), STONE);
        cells.set(SectionBuffer.index(7, 9, 1), AIR);
        cells.set(SectionBuffer.index(8, 9, 1), AIR);

        GhostVolume volume = new GhostVolume(ERASES);
        GhostSection section = volume.put(1, -1, 2, cells);

        assertEquals(4, section.cellCount());
        assertEquals(2, section.blockCount());
        assertEquals(2, section.eraseCount());
        assertEquals(box(16 + 2, -16 + 3, 32 + 1, 16 + 8, -16 + 9, 32 + 4), section.bounds());
        assertEquals(box(16 + 2, -16 + 3, 32 + 4, 16 + 5, -16 + 3, 32 + 4), section.blockBounds());
        assertEquals(box(16 + 7, -16 + 9, 32 + 1, 16 + 8, -16 + 9, 32 + 1), section.eraseBounds());
        assertArrayEquals(new short[] {(short) SectionBuffer.index(7, 9, 1), (short) SectionBuffer.index(8, 9, 1)},
                section.eraseCells());
        assertEquals(1, section.sectionX());
        assertEquals(-1, section.sectionY());
        assertEquals(2, section.sectionZ());
    }

    @Test
    void sectionsCopyTheirCells() {
        SectionBuffer cells = new SectionBuffer();
        cells.set(0, STONE);
        GhostVolume volume = new GhostVolume(ERASES);
        GhostSection section = volume.put(0, 0, 0, cells);
        long hash = section.hash();

        cells.set(0, DIRT);
        cells.set(1, DIRT);

        assertEquals(STONE, section.handle(0));
        assertEquals(-1, section.handle(1));
        assertEquals(hash, section.hash());
    }

    @Test
    void growsIncrementallyAndTracksBounds() {
        GhostVolume volume = new GhostVolume(ERASES);
        assertTrue(volume.isEmpty());
        assertNull(volume.bounds());
        assertNull(volume.frame());
        assertEquals(0, volume.contentHash());

        long version = volume.version();
        volume.put(0, 0, 0, SectionBuffer.uniform(STONE));
        assertTrue(volume.version() > version);
        assertEquals(box(0, 0, 0, 15, 15, 15), volume.bounds());
        assertEquals(4096, volume.blockCount());

        volume.put(2, 1, 0, SectionBuffer.uniform(DIRT));
        assertEquals(box(0, 0, 0, 47, 31, 15), volume.bounds());
        assertEquals(8192, volume.blockCount());

        // Replacing a section with a smaller one shrinks the bounds.
        SectionBuffer single = new SectionBuffer();
        single.set(SectionBuffer.index(0, 0, 0), DIRT);
        volume.put(2, 1, 0, single);
        assertEquals(box(0, 0, 0, 32, 16, 15), volume.bounds());
        assertEquals(4097, volume.blockCount());

        assertTrue(volume.remove(BlockBuffer.key(0, 0, 0)));
        assertFalse(volume.remove(BlockBuffer.key(0, 0, 0)));
        assertEquals(box(32, 16, 0, 32, 16, 0), volume.bounds());
        assertEquals(1, volume.blockCount());

        // Putting an empty section removes it.
        assertNull(volume.put(2, 1, 0, new SectionBuffer()));
        assertTrue(volume.isEmpty());
        assertEquals(0, volume.cellCount());
        assertEquals(0, volume.contentHash());
    }

    @Test
    void anExplicitFrameOverridesTheBounds() {
        GhostVolume volume = new GhostVolume(ERASES);
        volume.put(0, 0, 0, SectionBuffer.uniform(STONE));
        Box frame = box(0, 0, 0, 63, 15, 31);
        volume.setFrame(frame);
        assertEquals(frame, volume.frame());
        assertEquals(box(0, 0, 0, 15, 15, 15), volume.bounds());
        volume.clear();
        assertEquals(frame, volume.frame(), "the frame outlives the content");
        volume.setFrame(null);
        assertNull(volume.frame());
    }

    @Test
    void sameContentHashesEquallyWhateverThePaletteHistory() {
        SectionBuffer direct = new SectionBuffer();
        direct.set(10, STONE);
        direct.set(20, DIRT);

        SectionBuffer roundabout = new SectionBuffer();
        for (int i = 0; i < 40; i++) {
            roundabout.set(i, 100 + i); // grow the palette and index width
        }
        for (int i = 0; i < 40; i++) {
            roundabout.clear(i);
        }
        roundabout.set(20, DIRT);
        roundabout.set(10, STONE);

        assertEquals(GhostSection.hashCells(direct), GhostSection.hashCells(roundabout));

        SectionBuffer different = direct.copy();
        different.set(10, DIRT);
        assertNotEquals(GhostSection.hashCells(direct), GhostSection.hashCells(different));

        SectionBuffer moved = new SectionBuffer();
        moved.set(11, STONE);
        moved.set(20, DIRT);
        assertNotEquals(GhostSection.hashCells(direct), GhostSection.hashCells(moved));
    }

    @Test
    void identicalContentIsNotReplaced() {
        GhostVolume volume = new GhostVolume(ERASES);
        SectionBuffer cells = new SectionBuffer();
        cells.set(7, STONE);
        GhostSection first = volume.put(3, 0, 0, cells);
        long version = volume.version();
        long hash = volume.contentHash();

        GhostSection again = volume.put(3, 0, 0, cells.compact());

        assertSame(first, again);
        assertEquals(version, volume.version());
        assertEquals(hash, volume.contentHash());

        cells.set(8, DIRT);
        GhostSection changed = volume.put(3, 0, 0, cells);
        assertNotEquals(first.hash(), changed.hash());
        assertTrue(volume.version() > version);
        assertNotEquals(hash, volume.contentHash());
    }

    @Test
    void volumeHashIsIndependentOfInsertionOrder() {
        SectionBuffer a = SectionBuffer.uniform(STONE);
        SectionBuffer b = new SectionBuffer();
        b.set(0, DIRT);

        GhostVolume first = new GhostVolume(ERASES);
        first.put(0, 0, 0, a);
        first.put(1, 0, 0, b);
        GhostVolume second = new GhostVolume(ERASES);
        second.put(1, 0, 0, b);
        second.put(0, 0, 0, a);
        assertEquals(first.contentHash(), second.contentHash());

        // The same content at another key is a different volume.
        GhostVolume shifted = new GhostVolume(ERASES);
        shifted.put(0, 0, 0, a);
        shifted.put(0, 1, 0, b);
        assertNotEquals(first.contentHash(), shifted.contentHash());

        // Removing and re-adding returns to the same hash.
        long hash = first.contentHash();
        first.remove(BlockBuffer.key(1, 0, 0));
        assertNotEquals(hash, first.contentHash());
        first.put(1, 0, 0, b);
        assertEquals(hash, first.contentHash());
    }

    @Test
    void outOfRangeSectionLookupsAreAbsent() {
        GhostVolume volume = new GhostVolume(ERASES);
        assertNull(volume.section(BlockBuffer.MAX_SECTION_XZ + 1, 0, 0));
        assertNull(volume.section(0, BlockBuffer.MIN_SECTION_Y - 1, 0));
    }
}
