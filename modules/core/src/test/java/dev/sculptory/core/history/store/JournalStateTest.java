package dev.sculptory.core.history.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.history.HistoryEntry;
import dev.sculptory.core.history.HistoryLimits;
import dev.sculptory.core.history.PlayerHistory;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The journal's replay model follows {@code PlayerHistory} exactly, and is lenient about records that do not apply. */
class JournalStateTest {
    private static final UUID PLAYER = new UUID(7, 7);
    private static final JournalState.Ref REF = new JournalState.Ref(0, 30);

    private static void apply(JournalState state, Journal.Op op) {
        state.apply(op, REF);
    }

    private static void begin(JournalState state, UUID id) {
        apply(state, new Journal.Begin(id, 1, "w", "label"));
    }

    private static void mark(JournalState state, Journal.Type type, UUID id) {
        apply(state, new Journal.Mark(type, id));
    }

    private static List<UUID> ids(JournalState state) {
        List<UUID> ids = new ArrayList<>();
        for (JournalState.Entry entry : state.stack) ids.add(entry.id);
        return ids;
    }

    private static List<UUID> ids(PlayerHistory history) {
        List<UUID> ids = new ArrayList<>();
        List<HistoryEntry> undo = history.undoEntries();
        for (int i = undo.size() - 1; i >= 0; i--) ids.add(undo.get(i).id());
        for (HistoryEntry e : history.redoEntries()) ids.add(e.id());
        return ids;
    }

    /** Random pushes, undos, redos and evictions through PlayerHistory, journaled as the server does, replay equal. */
    @Test
    void replayMatchesPlayerHistory() {
        Random random = new Random(1);
        for (int run = 0; run < 50; run++) {
            PlayerHistory history = new PlayerHistory(new HistoryLimits(1 + random.nextInt(8), Long.MAX_VALUE,
                    Long.MAX_VALUE));
            JournalState state = new JournalState(PLAYER);
            StoredHistory stored = null;
            for (int step = 0; step < 200; step++) {
                int op = random.nextInt(10);
                if (op < 4) {
                    HistoryEntry e = StoreTestSupport.entry(PLAYER, "e" + step, StoreTestSupport.sparse(step), step);
                    begin(state, e.id());
                    apply(state, new Journal.Seal(e.id(), e.label(), step, 10));
                    List<HistoryEntry> evicted = history.push(e);
                    mark(state, Journal.Type.PUSH, e.id());
                    for (HistoryEntry gone : evicted) mark(state, Journal.Type.EVICT, gone.id());
                } else if (op < 6) {
                    history.undoCandidate().ifPresent(e -> {
                        history.markUndone(e.id());
                        mark(state, Journal.Type.UNDONE, e.id());
                    });
                } else if (op < 8) {
                    history.redoCandidate().ifPresent(e -> {
                        mark(state, Journal.Type.REDO_BEGIN, e.id());
                        history.markRedone(e.id());
                        mark(state, Journal.Type.REDONE, e.id());
                    });
                } else if (op < 9) {
                    history.evictOldest().ifPresent(e -> mark(state, Journal.Type.EVICT, e.id()));
                } else {
                    // Records that must not apply: an undo of a non-candidate, a redo past the end, unknown entries.
                    int skipped = state.skipped;
                    mark(state, Journal.Type.UNDONE, UUID.randomUUID());
                    mark(state, Journal.Type.PUSH, UUID.randomUUID());
                    mark(state, Journal.Type.EVICT, UUID.randomUUID());
                    assertEquals(skipped + 3, state.skipped);
                }
                assertEquals(ids(history), ids(state), "run " + run + " step " + step);
                assertEquals(history.undoEntries().size(), state.applied);
                assertNull(state.redoInFlight);
                assertTrue(state.pending.isEmpty());
                stored = state.toStored();
            }
            // The stored metadata evicts like PlayerHistory.evictOldest.
            while (history.size() > 0) {
                assertEquals(history.evictOldest().map(HistoryEntry::id), stored.evictOldest().map(e -> e.id()));
            }
            assertTrue(stored.isEmpty());
        }
    }

    @Test
    void duplicatesAndOrphansAreSkipped() {
        JournalState state = new JournalState(PLAYER);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        begin(state, a);
        begin(state, a); // duplicate begin
        apply(state, new Journal.Section(b, 1, 1, 5, 0)); // section of an unknown entry
        apply(state, new Journal.Seal(b, "x", 1, 1)); // seal of an unknown entry
        mark(state, Journal.Type.PUSH, a);
        mark(state, Journal.Type.PUSH, a); // duplicate push
        mark(state, Journal.Type.REDONE, a); // nothing to redo
        mark(state, Journal.Type.REDO_BEGIN, b); // unknown
        mark(state, Journal.Type.ABORT, a); // pushed entries are not aborted
        assertEquals(7, state.skipped);
        assertEquals(List.of(a), ids(state));
        assertEquals(1, state.applied);
    }

    @Test
    void pendingEntriesAndTheRedoInFlightAreTracked() {
        JournalState state = new JournalState(PLAYER);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        begin(state, a);
        mark(state, Journal.Type.PUSH, a);
        mark(state, Journal.Type.UNDONE, a);
        mark(state, Journal.Type.REDO_BEGIN, a);
        begin(state, b);
        apply(state, new Journal.Section(b, 3, 1, 12, 0));
        begin(state, c);
        apply(state, new Journal.Seal(c, "c", 1, 1));
        assertEquals(a, state.redoInFlight);
        assertEquals(List.of(b, c), new ArrayList<>(state.pending.keySet()));
        assertTrue(state.pending.get(b).hasData());
        mark(state, Journal.Type.REDO_ABORT, a);
        assertNull(state.redoInFlight);
        mark(state, Journal.Type.ABORT, b);
        assertEquals(List.of(c), new ArrayList<>(state.pending.keySet()));
        // A push drops the redo side, whose entries' data become dead.
        mark(state, Journal.Type.PUSH, c);
        assertEquals(List.of(c), ids(state));
        assertTrue(!state.entries.containsKey(a) && !state.entries.containsKey(b));
    }
}
