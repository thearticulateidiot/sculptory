package dev.sculptory.core.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.buffer.BlockBuffer;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlayerHistoryTest {
    private static final UUID PLAYER = new UUID(1, 2);
    private static final HistoryLimits ROOMY = new HistoryLimits(64, 1L << 30, 1L << 32);

    private static HistoryEntry entry(String label, int cells) {
        RecordBuilder builder = new RecordBuilder();
        for (int i = 0; i < cells; i++) builder.record(i, 0, 0, 1, null, 2, null);
        return new HistoryEntry(UUID.randomUUID(), PLAYER, "minecraft:overworld", label, builder.build(), 0L);
    }

    @Test
    void emptyHistoryHasNoCandidates() {
        PlayerHistory history = new PlayerHistory(ROOMY);
        assertEquals(Optional.empty(), history.undoCandidate());
        assertEquals(Optional.empty(), history.redoCandidate());
        assertEquals(0, history.bytes());
        assertEquals(0, history.size());
        assertFalse(history.markUndone(UUID.randomUUID()));
        assertFalse(history.markRedone(UUID.randomUUID()));
        assertEquals(Optional.empty(), history.evictOldest());
    }

    @Test
    void undoRedoTransitions() {
        PlayerHistory history = new PlayerHistory(ROOMY);
        HistoryEntry a = entry("a", 1), b = entry("b", 2);
        assertEquals(List.of(), history.push(a));
        assertEquals(List.of(), history.push(b));
        assertEquals(Optional.of(b), history.undoCandidate());
        assertEquals(Optional.empty(), history.redoCandidate());

        assertFalse(history.markUndone(a.id()), "only the newest applied entry can be undone");
        assertEquals(Optional.of(b), history.undoCandidate());
        assertFalse(history.markRedone(b.id()), "nothing to redo yet");

        assertTrue(history.markUndone(b.id()));
        assertEquals(Optional.of(a), history.undoCandidate());
        assertEquals(Optional.of(b), history.redoCandidate());
        assertTrue(history.markUndone(a.id()));
        assertEquals(Optional.empty(), history.undoCandidate());
        assertEquals(Optional.of(a), history.redoCandidate());
        assertEquals(List.of(a, b), history.redoEntries());

        assertFalse(history.markRedone(b.id()), "redo goes oldest first");
        assertTrue(history.markRedone(a.id()));
        assertEquals(Optional.of(a), history.undoCandidate());
        assertEquals(Optional.of(b), history.redoCandidate());
        assertEquals(List.of(a), history.undoEntries());
        assertEquals(List.of(b), history.redoEntries());
        assertEquals(2, history.size());
    }

    @Test
    void pushClearsRedo() {
        PlayerHistory history = new PlayerHistory(ROOMY);
        HistoryEntry a = entry("a", 1), b = entry("b", 1), c = entry("c", 1);
        history.push(a);
        history.push(b);
        long bytesAB = history.bytes();
        history.markUndone(b.id());
        history.push(c);
        assertEquals(Optional.empty(), history.redoCandidate());
        assertEquals(List.of(c, a), history.undoEntries());
        assertEquals(2, history.size());
        assertEquals(bytesAB - b.record().estimatedBytes() + c.record().estimatedBytes(), history.bytes());
        assertFalse(history.markRedone(b.id()));
    }

    @Test
    void evictsOldestByEntryCount() {
        PlayerHistory history = new PlayerHistory(new HistoryLimits(3, 1L << 30, 1L << 32));
        HistoryEntry[] entries = new HistoryEntry[5];
        for (int i = 0; i < entries.length; i++) entries[i] = entry("e" + i, 1);
        history.push(entries[0]);
        history.push(entries[1]);
        history.push(entries[2]);
        assertEquals(List.of(entries[0]), history.push(entries[3]));
        assertEquals(List.of(entries[1]), history.push(entries[4]));
        assertEquals(List.of(entries[4], entries[3], entries[2]), history.undoEntries());
        long expected = 0;
        for (int i = 2; i < 5; i++) expected += entries[i].record().estimatedBytes();
        assertEquals(expected, history.bytes());
    }

    @Test
    void evictsOldestByBytes() {
        HistoryEntry a = entry("a", 100), b = entry("b", 100), c = entry("c", 100);
        long each = a.record().estimatedBytes();
        assertEquals(each, b.record().estimatedBytes());
        PlayerHistory history = new PlayerHistory(new HistoryLimits(64, 2 * each + each / 2, 1L << 32));
        history.push(a);
        assertEquals(List.of(), history.push(b));
        assertEquals(List.of(a), history.push(c));
        assertEquals(2 * each, history.bytes());
        assertEquals(List.of(c, b), history.undoEntries());
    }

    @Test
    void entryLargerThanTheCapEvictsEverything() {
        HistoryEntry small = entry("small", 1), big = entry("big", 4000);
        PlayerHistory history = new PlayerHistory(new HistoryLimits(64, small.record().estimatedBytes() * 2, 1L << 32));
        history.push(small);
        assertEquals(List.of(small, big), history.push(big));
        assertEquals(0, history.size());
        assertEquals(0, history.bytes());
        assertEquals(Optional.empty(), history.undoCandidate());
    }

    @Test
    void evictOldestForGlobalCap() {
        PlayerHistory history = new PlayerHistory(ROOMY);
        HistoryEntry a = entry("a", 1), b = entry("b", 1), c = entry("c", 1);
        history.push(a);
        history.push(b);
        history.push(c);
        history.markUndone(c.id());
        assertEquals(Optional.of(a), history.evictOldest());
        assertEquals(Optional.of(b), history.undoCandidate());
        assertEquals(Optional.of(c), history.redoCandidate());
        history.markUndone(b.id());
        // Nothing undoable: the redo entry furthest from the present goes, keeping b redoable.
        assertEquals(Optional.of(c), history.evictOldest());
        assertEquals(Optional.of(b), history.redoCandidate());
        assertEquals(b.record().estimatedBytes(), history.bytes());
    }

    @Test
    void refusesEmptyAndDuplicateEntries() {
        PlayerHistory history = new PlayerHistory(ROOMY);
        HistoryEntry empty = new HistoryEntry(UUID.randomUUID(), PLAYER, "w", "empty",
                new EditRecord(new BlockBuffer(), new BlockBuffer()), 0L);
        assertThrows(IllegalArgumentException.class, () -> history.push(empty));
        HistoryEntry a = entry("a", 1);
        history.push(a);
        assertThrows(IllegalArgumentException.class, () -> history.push(a));
        assertEquals(1, history.size());
    }
}
