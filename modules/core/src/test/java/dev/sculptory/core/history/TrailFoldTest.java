package dev.sculptory.core.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.buffer.NbtBytes;
import dev.sculptory.core.buffer.SectionBuffer;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Folding an entry's fluid trail into its record: toward before for a done entry (the trail's first states become
 * targets where the record has none; its last states are what the cells must hold), toward after for an undone one (the
 * mirror); in pieces as whole.
 */
class TrailFoldTest {
    private static final int AIR = 0, WATER = 1, FLOWING = 2, GRASS = 3, DIRT = 4, STONE = 5, BANNER = 6;
    private static final BlockEntityData BANNER_TILE = new NbtBytes("minecraft:banner", new byte[] {10, 7});

    private static EditRecord record(int[]... cells) {
        RecordBuilder b = new RecordBuilder();
        for (int[] c : cells) b.record(c[0], c[1], c[2], c[3], null, c[4], null);
        return b.build();
    }

    private static void cell(EditRecord r, int x, int y, int z, int before, int after) {
        assertTrue(r.before().has(x, y, z), "cell " + x + "," + y + "," + z + " is recorded");
        assertEquals(before, r.before().get(x, y, z), "before at " + x + "," + y + "," + z);
        assertEquals(after, r.after().get(x, y, z), "after at " + x + "," + y + "," + z);
    }

    @Test
    void anEmptyOrMissingTrailLeavesTheRecord() {
        EditRecord fill = record(new int[] {0, 64, 0, AIR, WATER});
        assertSame(fill, TrailFold.fold(fill, null, true));
        assertSame(fill, TrailFold.fold(fill, new RecordBuilder().build(), false));
    }

    @Test
    void towardBeforeAddsTheFlowAndKeepsTheRecordsBefore() {
        // The fill wrote water at (0,64,0) and (1,64,0); water flowed to (2,64,0), killed the grass under (0,64,0), and
        // a flow changed a filled cell too (a Paint's flowing water that became a source).
        EditRecord fill = record(new int[] {0, 64, 0, AIR, WATER}, new int[] {1, 64, 0, GRASS, FLOWING});
        EditRecord trail = record(new int[] {2, 64, 0, AIR, FLOWING}, new int[] {0, 63, 0, GRASS, DIRT},
                new int[] {1, 64, 0, FLOWING, WATER});
        EditRecord folded = TrailFold.fold(fill, trail, true);
        cell(folded, 0, 64, 0, AIR, WATER);
        cell(folded, 1, 64, 0, GRASS, WATER);
        cell(folded, 2, 64, 0, AIR, FLOWING);
        cell(folded, 0, 63, 0, GRASS, DIRT);
        assertEquals(4, folded.before().cellCount());
        assertEquals(4, folded.after().cellCount());
    }

    @Test
    void towardAfterMirrorsIt() {
        // A drain (water to air) was undone: the water it put back flowed to (2,64,0) and into a drained cell's
        // neighbour the drain also wrote. Redo must take the flow back and still drain.
        EditRecord drain = record(new int[] {0, 64, 0, WATER, AIR}, new int[] {1, 64, 0, WATER, AIR});
        EditRecord trail = record(new int[] {2, 64, 0, AIR, FLOWING}, new int[] {1, 64, 0, WATER, STONE});
        EditRecord folded = TrailFold.fold(drain, trail, false);
        cell(folded, 0, 64, 0, WATER, AIR);
        cell(folded, 1, 64, 0, STONE, AIR);
        cell(folded, 2, 64, 0, FLOWING, AIR);
    }

    @Test
    void cellsThatEndWhereTheyStartedAreKeptSoTheStepFindsThemDone() {
        EditRecord fill = record(new int[] {0, 64, 0, AIR, FLOWING}, new int[] {5, 64, 5, AIR, WATER});
        // The flowing water the edit wrote at (0,64,0) drained away again: the undo finds it at its target (air), and
        // must not report it as changed since the edit (which the unfolded record would: air is not "flowing").
        EditRecord trail = record(new int[] {0, 64, 0, FLOWING, AIR});
        EditRecord folded = TrailFold.fold(fill, trail, true);
        cell(folded, 0, 64, 0, AIR, AIR);
        cell(folded, 5, 64, 5, AIR, WATER);
        assertEquals(2, folded.before().cellCount());
        // Even when that was the only cell, the entry keeps a record (PlayerHistory holds no empty entry).
        EditRecord only = TrailFold.fold(record(new int[] {0, 64, 0, AIR, FLOWING}), trail, true);
        assertFalse(only.isEmpty());
        cell(only, 0, 64, 0, AIR, AIR);
    }

    @Test
    void tilesAndEntitiesAreKeptAndUntouchedSectionsShared() {
        RecordBuilder b = new RecordBuilder();
        b.record(0, 64, 0, AIR, null, WATER, null);
        b.record(100, 64, 100, AIR, null, WATER, null); // another section, which the trail does not touch
        UUID frame = UUID.randomUUID();
        b.recordEntity(frame, null, new EntityState("minecraft:item_frame", 0.5, 64, 0.5, new byte[] {10, 0, 0, 0}));
        EditRecord fill = b.build();
        RecordBuilder t = new RecordBuilder();
        t.record(1, 64, 0, BANNER, BANNER_TILE, FLOWING, null); // water broke a banner
        EditRecord folded = TrailFold.fold(fill, t.build(), true);
        cell(folded, 1, 64, 0, BANNER, FLOWING);
        assertTrue(BANNER_TILE.sameContent(folded.before().tile(1, 64, 0)), "the banner's contents come back");
        assertNull(folded.after().tile(1, 64, 0));
        long far = BlockBuffer.keyOfBlock(100, 64, 100);
        assertSame(fill.before().section(far), folded.before().section(far));
        assertSame(fill.after().section(far), folded.after().section(far));
        assertEquals(List.copyOf(fill.entities()), List.copyOf(folded.entities()));
    }

    @Test
    void replaceSwapsTheEntryInPlaceAndCountsItsGrowth() {
        HistoryLimits roomy = new HistoryLimits(64, 1L << 30, 1L << 32);
        PlayerHistory history = new PlayerHistory(roomy);
        EditRecord small = record(new int[] {0, 64, 0, AIR, WATER});
        HistoryEntry a = new HistoryEntry(UUID.randomUUID(), UUID.randomUUID(), "minecraft:overworld", "a", small, 0);
        HistoryEntry b = new HistoryEntry(UUID.randomUUID(), a.owner(), "minecraft:overworld", "b", small, 0);
        history.push(a);
        history.push(b);
        assertTrue(history.markUndone(b.id()));
        long bytes = history.bytes();
        EditRecord bigger = TrailFold.fold(small, record(new int[] {1, 64, 0, AIR, FLOWING},
                new int[] {2, 64, 0, AIR, FLOWING}), true);
        HistoryEntry grown = new HistoryEntry(b.id(), b.owner(), b.world(), b.label(), bigger, b.createdMillis());
        assertTrue(history.replace(grown));
        assertEquals(List.of(grown), history.redoEntries(), "same place on the redo side");
        assertEquals(List.of(a), history.undoEntries());
        assertEquals(bytes + bigger.estimatedBytes() - small.estimatedBytes(), history.bytes());
        assertEquals(bytes, history.pushedBytes(), "the size the client is shown leaves the fold out");
        assertFalse(history.replace(new HistoryEntry(UUID.randomUUID(), a.owner(), a.world(), "c", small, 0)),
                "an entry not held is not added");
        assertEquals(2, history.size());
        // Once the grown entry leaves (a push drops the redo side), both counts agree again.
        history.push(new HistoryEntry(UUID.randomUUID(), a.owner(), a.world(), "d", small, 0));
        assertEquals(history.bytes(), history.pushedBytes());
        assertEquals(2, history.size());
    }

    /**
     * A trail folded in pieces gives the entry folded once with the whole trail: by chunk column (disjoint cells), and
     * over time (what the fluid did before a fold, then after it, a cell changing in both), in both directions.
     */
    @Test
    void foldingInPiecesEqualsFoldingOnce() {
        EditRecord fill = record(new int[] {0, 64, 0, AIR, WATER}, new int[] {1, 64, 0, GRASS, FLOWING},
                new int[] {40, 64, 0, AIR, WATER});
        // Before the first fold: flow at (2,64,0) and in another column (41,64,0), grass under the fill died, and the
        // edit's flowing cell became a source.
        EditRecord early = record(new int[] {2, 64, 0, AIR, FLOWING}, new int[] {41, 64, 0, AIR, FLOWING},
                new int[] {0, 63, 0, GRASS, DIRT}, new int[] {1, 64, 0, FLOWING, WATER});
        // After it: (2,64,0) and (41,64,0) became sources, and (3,64,0) got wet.
        EditRecord late = record(new int[] {2, 64, 0, FLOWING, WATER}, new int[] {41, 64, 0, FLOWING, WATER},
                new int[] {3, 64, 0, AIR, FLOWING});
        EditRecord whole = record(new int[] {2, 64, 0, AIR, WATER}, new int[] {41, 64, 0, AIR, WATER},
                new int[] {0, 63, 0, GRASS, DIRT}, new int[] {1, 64, 0, FLOWING, WATER}, new int[] {3, 64, 0, AIR,
                FLOWING});
        for (boolean towardBefore : new boolean[] {true, false}) {
            EditRecord once = TrailFold.fold(fill, whole, towardBefore);
            EditRecord byColumn = TrailFold.fold(TrailFold.fold(fill, column(early, 0), towardBefore),
                    column(early, 2), towardBefore);
            EditRecord pieces = TrailFold.fold(byColumn, late, towardBefore);
            assertSameCells(once, pieces, towardBefore ? "toward before" : "toward after");
        }
        // A cell the fluid changed before a fold and put back after it stays in the entry with the same state on both
        // sides (whole, the trail would have dropped it): the step finds it already done either way.
        EditRecord wetThenDry = TrailFold.fold(TrailFold.fold(fill, record(new int[] {5, 64, 0, AIR, FLOWING}), true),
                record(new int[] {5, 64, 0, FLOWING, AIR}), true);
        cell(wetThenDry, 5, 64, 0, AIR, AIR);
    }

    /** The cells of {@code r} in chunk column (cx, 0). */
    private static EditRecord column(EditRecord r, int cx) {
        RecordBuilder b = new RecordBuilder();
        for (long key : r.before().sortedKeys()) {
            if (BlockBuffer.keyX(key) != cx) continue;
            SectionBuffer before = r.before().section(key);
            SectionBuffer after = r.after().section(key);
            int ox = BlockBuffer.keyX(key) << 4, oy = BlockBuffer.keyY(key) << 4, oz = BlockBuffer.keyZ(key) << 4;
            before.forEachPresent(i -> b.record(ox + SectionBuffer.localX(i), oy + SectionBuffer.localY(i),
                    oz + SectionBuffer.localZ(i), before.get(i), null, after.get(i), null));
        }
        return b.build();
    }

    private static void assertSameCells(EditRecord expected, EditRecord actual, String what) {
        assertEquals(expected.before().cellCount(), actual.before().cellCount(), what + ": cells");
        for (long key : expected.before().sortedKeys()) {
            SectionBuffer before = expected.before().section(key);
            SectionBuffer after = expected.after().section(key);
            int ox = BlockBuffer.keyX(key) << 4, oy = BlockBuffer.keyY(key) << 4, oz = BlockBuffer.keyZ(key) << 4;
            before.forEachPresent(i -> {
                int x = ox + SectionBuffer.localX(i);
                int y = oy + SectionBuffer.localY(i);
                int z = oz + SectionBuffer.localZ(i);
                assertTrue(actual.before().has(x, y, z), what + ": " + x + "," + y + "," + z + " is recorded");
                assertEquals(before.get(i), actual.before().get(x, y, z), what + ": before at " + x + "," + y + "," + z);
                assertEquals(after.get(i), actual.after().get(x, y, z), what + ": after at " + x + "," + y + "," + z);
            });
        }
    }
}
