package dev.sculptory.server.engine.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.history.EditRecord;
import dev.sculptory.core.history.HistoryEntry;
import dev.sculptory.core.history.HistoryLimits;
import dev.sculptory.core.history.RecordBuilder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HistoryServiceTest {
    private static final UUID P1 = new UUID(0, 1);
    private static final UUID P2 = new UUID(0, 2);

    /** Records callbacks as strings. */
    private static final class Events implements HistoryService.Listener {
        final List<String> log = new ArrayList<>();

        @Override
        public void changed(UUID player) {
            log.add("changed " + player.getLeastSignificantBits());
        }

        @Override
        public void evicted(UUID player, int steps, boolean includesNewest) {
            log.add("evicted " + player.getLeastSignificantBits() + " " + steps + (includesNewest ? " newest" : ""));
        }
    }

    private final Events events = new Events();

    /** An entry of {@code cells} changed cells (same shape, so the same size, for equal counts). */
    private static HistoryEntry entry(UUID owner, String label, long created, int cells) {
        RecordBuilder builder = new RecordBuilder();
        for (int i = 0; i < cells; i++) builder.record(i, 64, 0, 0, null, 1, null);
        return new HistoryEntry(UUID.randomUUID(), owner, "minecraft:overworld", label, builder.build(), created);
    }

    private static List<String> labels(List<HistoryEntry> entries) {
        return entries.stream().map(HistoryEntry::label).toList();
    }

    private HistoryService service(HistoryLimits limits) {
        return new HistoryService(limits, events);
    }

    /**
     * An edit started after the undo (the only kind that can push during it, see the next test) is newer than the
     * undo: its push waits, then clears the undone entry like any new edit.
     */
    @Test
    void newerEditDuringUndoWaitsThenClearsTheUndoneEntry() {
        HistoryService h = service(HistoryLimits.DEFAULTS);
        HistoryService.Session s = h.session(P1);
        HistoryEntry a = entry(P1, "a", 1, 3);
        HistoryEntry b = entry(P1, "b", 2, 3);
        h.push(s, a);
        h.push(s, b);
        h.begin(s, HistoryService.Op.UNDO, b);
        assertTrue(s.busy());
        h.editStarted(s);
        HistoryEntry c = entry(P1, "c", 3, 3);
        h.editFinished(s, c);
        assertEquals(1, s.deferredCount());
        assertEquals(0, s.editsRunning());
        assertEquals(List.of("b", "a"), labels(h.undoEntries(P1)), "the in-flight entry stays the candidate");
        assertEquals(3 * c.record().estimatedBytes(), h.totalBytes(), "deferred pushes count toward the total");
        h.finish(s, true);
        assertFalse(s.busy());
        assertEquals(List.of("c", "a"), labels(h.undoEntries(P1)));
        assertEquals(List.of(), labels(h.redoEntries(P1)), "the newer edit cleared the undone entry");
    }

    /** Regression (review H1): an edit admitted before the undo would land after it and lose the undone entry. */
    @Test
    void noUndoWhileAnEditIsRunning() {
        HistoryService h = service(HistoryLimits.DEFAULTS);
        HistoryService.Session s = h.session(P1);
        HistoryEntry a = entry(P1, "a", 1, 3);
        h.push(s, a);
        h.editStarted(s);
        assertEquals(1, s.editsRunning());
        assertThrows(IllegalStateException.class, () -> h.begin(s, HistoryService.Op.UNDO, a));
        HistoryEntry job = entry(P1, "job", 2, 3);
        h.editFinished(s, job);
        h.begin(s, HistoryService.Op.UNDO, job);
        h.finish(s, true);
        assertEquals(List.of("a"), labels(h.undoEntries(P1)));
        assertEquals(List.of("job"), labels(h.redoEntries(P1)));
        h.editStarted(s);
        h.editFinished(s, null); // a job that changed nothing pushes nothing and keeps the redo side
        assertEquals(0, s.editsRunning());
        assertEquals(List.of("job"), labels(h.redoEntries(P1)));
    }

    @Test
    void appliedRedoNeverLosesItsEntry() {
        HistoryService h = service(HistoryLimits.DEFAULTS);
        HistoryService.Session s = h.session(P1);
        HistoryEntry a = entry(P1, "a", 1, 3);
        h.push(s, a);
        h.begin(s, HistoryService.Op.UNDO, a);
        h.finish(s, true);
        assertEquals(List.of("a"), labels(h.redoEntries(P1)));
        h.begin(s, HistoryService.Op.REDO, a);
        h.push(s, entry(P1, "stroke", 2, 3)); // e.g. a stroke ending while the redo runs
        h.finish(s, true);
        assertEquals(List.of("stroke", "a"), labels(h.undoEntries(P1)));
        assertTrue(h.redoEntries(P1).isEmpty());
    }

    @Test
    void oneOperationAtATime() {
        HistoryService h = service(HistoryLimits.DEFAULTS);
        HistoryService.Session s = h.session(P1);
        HistoryEntry a = entry(P1, "a", 1, 3);
        HistoryEntry b = entry(P1, "b", 2, 3);
        h.push(s, a);
        h.push(s, b);
        h.begin(s, HistoryService.Op.UNDO, b);
        assertThrows(IllegalStateException.class, () -> h.begin(s, HistoryService.Op.UNDO, b));
        h.finish(s, true);
        assertThrows(IllegalStateException.class, () -> h.begin(s, HistoryService.Op.UNDO, b), "not the candidate");
        h.begin(s, HistoryService.Op.UNDO, a);
        h.finish(s, true);
        assertEquals(List.of("a", "b"), labels(h.redoEntries(P1)));
    }

    @Test
    void cancelledUndoLeavesTheStack() {
        HistoryService h = service(HistoryLimits.DEFAULTS);
        HistoryService.Session s = h.session(P1);
        HistoryEntry a = entry(P1, "a", 1, 3);
        h.push(s, a);
        h.begin(s, HistoryService.Op.UNDO, a);
        h.finish(s, false);
        assertEquals(List.of("a"), labels(h.undoEntries(P1)));
        assertFalse(s.busy());
    }

    @Test
    void globalCapEvictsTheOldestEntryAcrossPlayers() {
        long size = entry(P1, "probe", 0, 5).record().estimatedBytes();
        HistoryService h = service(new HistoryLimits(64, Long.MAX_VALUE, size * 5 / 2));
        HistoryService.Session s1 = h.session(P1);
        HistoryService.Session s2 = h.session(P2);
        h.push(s1, entry(P1, "p1 old", 10, 5));
        h.push(s2, entry(P2, "p2 mid", 20, 5));
        events.log.clear();
        h.push(s1, entry(P1, "p1 new", 30, 5));
        assertEquals(List.of("p1 new"), labels(h.undoEntries(P1)));
        assertEquals(List.of("p2 mid"), labels(h.undoEntries(P2)));
        assertTrue(events.log.contains("evicted 1 1"), events.log.toString());
        assertTrue(h.totalBytes() <= size * 5 / 2);

        events.log.clear();
        h.push(s1, entry(P1, "p1 newest", 40, 5));
        assertEquals(List.of("p1 newest", "p1 new"), labels(h.undoEntries(P1)));
        assertTrue(h.undoEntries(P2).isEmpty(), "p2's entry was the oldest");
        assertTrue(events.log.contains("evicted 2 1") && events.log.contains("changed 2"), events.log.toString());
    }

    @Test
    void globalCapNeverEvictsAPlayerWithAnUndoInFlight() {
        long size = entry(P1, "probe", 0, 5).record().estimatedBytes();
        HistoryService h = service(new HistoryLimits(64, Long.MAX_VALUE, size * 5 / 2));
        HistoryService.Session s1 = h.session(P1);
        HistoryService.Session s2 = h.session(P2);
        HistoryEntry oldest = entry(P1, "p1 oldest", 1, 5);
        h.push(s1, oldest);
        h.begin(s1, HistoryService.Op.UNDO, oldest);
        h.push(s2, entry(P2, "p2 a", 2, 5));
        h.push(s2, entry(P2, "p2 b", 3, 5));
        assertEquals(List.of("p1 oldest"), labels(h.undoEntries(P1)));
        assertEquals(List.of("p2 b"), labels(h.undoEntries(P2)));
        h.finish(s1, true);
        assertEquals(List.of("p1 oldest"), labels(h.redoEntries(P1)), "within the cap again");
    }

    @Test
    void perPlayerCapsReportEvictions() {
        HistoryService h = service(new HistoryLimits(2, Long.MAX_VALUE, Long.MAX_VALUE));
        HistoryService.Session s = h.session(P1);
        h.push(s, entry(P1, "a", 1, 3));
        h.push(s, entry(P1, "b", 2, 3));
        h.push(s, entry(P1, "c", 3, 3));
        assertEquals(List.of("c", "b"), labels(h.undoEntries(P1)));
        assertTrue(events.log.contains("evicted 1 1"), events.log.toString());

        HistoryService small = service(new HistoryLimits(64, entry(P1, "probe", 0, 3).record().estimatedBytes() + 1,
                Long.MAX_VALUE));
        HistoryService.Session t = small.session(P2);
        events.log.clear();
        small.push(t, entry(P2, "huge", 1, 500));
        assertTrue(small.undoEntries(P2).isEmpty());
        assertEquals("evicted 2 1 newest", events.log.get(0));
    }

    @Test
    void closedSessionsDropTheirPushesAndFinishes() {
        HistoryService h = service(HistoryLimits.DEFAULTS);
        HistoryService.Session s = h.session(P1);
        HistoryEntry a = entry(P1, "a", 1, 3);
        h.push(s, a);
        h.begin(s, HistoryService.Op.UNDO, a);
        h.clear(P1);
        assertTrue(s.closed());
        h.push(s, entry(P1, "late", 2, 3));
        h.finish(s, true);
        assertTrue(h.undoEntries(P1).isEmpty() && h.redoEntries(P1).isEmpty());
        HistoryService.Session fresh = h.session(P1);
        assertFalse(fresh.busy());
        assertEquals(HistorySnapshot.EMPTY, h.snapshot(P2));
    }

    @Test
    void snapshotCapsLabels() {
        HistoryService h = service(new HistoryLimits(100, Long.MAX_VALUE, Long.MAX_VALUE));
        HistoryService.Session s = h.session(P1);
        String longLabel = "Fill · " + "é".repeat(200) + "😀😀";
        for (int i = 0; i < 70; i++) h.push(s, entry(P1, i == 69 ? longLabel : "edit " + i, i, 1));
        HistorySnapshot snapshot = h.snapshot(P1);
        assertTrue(snapshot.canUndo());
        assertFalse(snapshot.canRedo());
        assertEquals(HistorySnapshot.MAX_LABELS, snapshot.undoLabels().size());
        String first = snapshot.undoLabel();
        assertTrue(longLabel.startsWith(first));
        int bytes = first.getBytes(StandardCharsets.UTF_8).length;
        assertTrue(bytes <= 256 && bytes >= 253, "label is " + bytes + " bytes");
        assertEquals("edit 68", snapshot.undoLabels().get(1));
        assertEquals("edit 6", snapshot.undoLabels().get(63));
    }

    @Test
    void truncateUtf8KeepsWholeCharacters() {
        assertEquals("abc", HistorySnapshot.truncateUtf8("abc", 3));
        assertEquals("ab", HistorySnapshot.truncateUtf8("abc", 2));
        assertEquals("a", HistorySnapshot.truncateUtf8("aé", 2), "é needs 2 bytes");
        assertEquals("aé", HistorySnapshot.truncateUtf8("aé", 3));
        assertEquals("a", HistorySnapshot.truncateUtf8("a😀", 4), "never half a surrogate pair");
        assertEquals("a😀", HistorySnapshot.truncateUtf8("a😀", 5));
        assertEquals("", HistorySnapshot.truncateUtf8("€", 2));
        String broken = "x\uD83D"; // an unpaired high surrogate encodes as one '?'
        assertEquals(broken, HistorySnapshot.truncateUtf8(broken, 2));
    }

    @Test
    void recordSizesAreEqualForEqualShapes() {
        EditRecord a = entry(P1, "a", 0, 5).record();
        EditRecord b = entry(P2, "b", 0, 5).record();
        assertEquals(a.estimatedBytes(), b.estimatedBytes());
    }

    // ---------------------------------------------------------------- runs (Undo anyway)

    /** Undoes (or redoes) the candidate as a completed step that skipped {@code conflicts} cells. */
    private static void step(HistoryService h, HistoryService.Session s, HistoryService.Op op, long conflicts) {
        HistoryEntry entry = h.candidate(s, op).orElseThrow();
        h.begin(s, op, entry);
        h.finish(s, true, true, conflicts);
    }

    private static List<String> runLabels(HistoryService.Session s) {
        return s.run().map(run -> labels(run.entries())).orElse(List.of());
    }

    /** Pushes a, b, c (oldest first). */
    private HistoryService pushed(HistoryService.Session[] session) {
        HistoryService h = service(HistoryLimits.DEFAULTS);
        HistoryService.Session s = h.session(P1);
        for (String label : List.of("a", "b", "c")) h.push(s, entry(P1, label, label.charAt(0), 3));
        session[0] = s;
        return h;
    }

    @Test
    void completedStepsInOneDirectionMakeARunAndTheOtherDirectionStartsANewOne() {
        HistoryService.Session[] holder = new HistoryService.Session[1];
        HistoryService h = pushed(holder);
        HistoryService.Session s = holder[0];
        assertTrue(s.run().isEmpty());
        step(h, s, HistoryService.Op.UNDO, 0);
        step(h, s, HistoryService.Op.UNDO, 4);
        step(h, s, HistoryService.Op.UNDO, 1);
        HistoryService.Run run = s.run().orElseThrow();
        assertEquals(HistoryService.Op.UNDO, run.op());
        assertEquals(List.of("c", "b", "a"), labels(run.entries()), "in the order they were applied");
        assertEquals(5, run.conflicts());
        assertNull(h.overwriteRefusal(s, HistoryService.Op.UNDO, 3));

        step(h, s, HistoryService.Op.REDO, 2);
        assertEquals(List.of("a"), runLabels(s), "a step the other way starts a new run");
        assertEquals(HistoryService.Op.REDO, s.run().orElseThrow().op());
        assertEquals(2, s.run().orElseThrow().conflicts());
        step(h, s, HistoryService.Op.REDO, 0);
        assertEquals(List.of("a", "b"), runLabels(s));
        assertNull(h.overwriteRefusal(s, HistoryService.Op.REDO, 2));
    }

    @Test
    void pushesEvictionsAndStepsThatDidNotCompleteEndTheRun() {
        HistoryService.Session[] holder = new HistoryService.Session[1];
        HistoryService h = pushed(holder);
        HistoryService.Session s = holder[0];
        step(h, s, HistoryService.Op.UNDO, 3);
        h.push(s, entry(P1, "d", 'd', 3));
        assertTrue(s.run().isEmpty(), "a push ends the run");

        step(h, s, HistoryService.Op.UNDO, 3);
        HistoryEntry next = h.candidate(s, HistoryService.Op.UNDO).orElseThrow();
        h.begin(s, HistoryService.Op.UNDO, next);
        h.finish(s, false, false, 7);
        assertTrue(s.run().isEmpty(), "a cancelled undo (not applied) ends the run");

        step(h, s, HistoryService.Op.UNDO, 3);
        next = h.candidate(s, HistoryService.Op.UNDO).orElseThrow();
        h.begin(s, HistoryService.Op.UNDO, next);
        h.push(s, entry(P1, "stroke", 'e', 3)); // deferred behind the undo
        h.finish(s, true, true, 2);
        assertTrue(s.run().isEmpty(), "a push deferred behind the step lands right after it and ends the run");

        step(h, s, HistoryService.Op.UNDO, 3);
        HistoryEntry redo = h.candidate(s, HistoryService.Op.REDO).orElseThrow();
        h.begin(s, HistoryService.Op.REDO, redo);
        h.finish(s, true, false, 1);
        assertTrue(s.run().isEmpty(), "a cancelled redo that changed something is redone, but ends the run");

        step(h, s, HistoryService.Op.UNDO, 3);
        h.finish(s, true, true, 0); // nothing in flight: ignored
        assertEquals(1, runLabels(s).size());
    }

    @Test
    void anyEvictionOfThePlayersEntriesEndsTheirRun() {
        long size = entry(P1, "probe", 0, 5).record().estimatedBytes();
        HistoryService h = service(new HistoryLimits(64, Long.MAX_VALUE, size * 7 / 2));
        HistoryService.Session s1 = h.session(P1);
        HistoryService.Session s2 = h.session(P2);
        h.push(s1, entry(P1, "p1 a", 1, 5));
        h.push(s1, entry(P1, "p1 b", 2, 5));
        h.push(s2, entry(P2, "p2 a", 3, 5));
        step(h, s1, HistoryService.Op.UNDO, 4);
        assertEquals(List.of("p1 b"), runLabels(s1));
        h.push(s2, entry(P2, "p2 b", 4, 5)); // over the cap: p1's oldest entry goes, not one of the run
        assertEquals(List.of("p1 b"), labels(h.redoEntries(P1)), "the run's entry is still there");
        assertTrue(s1.run().isEmpty(), "yet the eviction ended the run: the client sees every eviction");
        assertEquals("the history changed since those undo steps", h.overwriteRefusal(s1, HistoryService.Op.UNDO, 1));
    }

    @Test
    void overwriteRefusals() {
        HistoryService.Session[] holder = new HistoryService.Session[1];
        HistoryService h = pushed(holder);
        HistoryService.Session s = holder[0];
        assertEquals("the history changed since those undo steps", h.overwriteRefusal(s, HistoryService.Op.UNDO, 1),
                "no run");
        step(h, s, HistoryService.Op.UNDO, 0);
        step(h, s, HistoryService.Op.UNDO, 0);
        assertEquals("those steps kept no changed blocks", h.overwriteRefusal(s, HistoryService.Op.UNDO, 2));
        step(h, s, HistoryService.Op.UNDO, 6);
        assertEquals("the history changed since those undo steps", h.overwriteRefusal(s, HistoryService.Op.UNDO, 2),
                "the client counted fewer steps than the server");
        assertEquals("the history changed since those undo steps", h.overwriteRefusal(s, HistoryService.Op.UNDO, 4));
        assertEquals("the history changed since those redo steps", h.overwriteRefusal(s, HistoryService.Op.REDO, 3),
                "the other direction");
        assertNull(h.overwriteRefusal(s, HistoryService.Op.UNDO, 3));

        // Entries of the run made in two worlds: the overwrite runs in one world, so it is refused.
        HistoryService other = service(HistoryLimits.DEFAULTS);
        HistoryService.Session t = other.session(P2);
        other.push(t, entry(P2, "overworld", 1, 3));
        RecordBuilder builder = new RecordBuilder();
        builder.record(0, 64, 0, 0, null, 1, null);
        other.push(t, new HistoryEntry(UUID.randomUUID(), P2, "minecraft:the_nether", "nether", builder.build(), 2));
        step(other, t, HistoryService.Op.UNDO, 1);
        step(other, t, HistoryService.Op.UNDO, 1);
        assertEquals("those steps were made in more than one world", other.overwriteRefusal(t, HistoryService.Op.UNDO, 2));
    }

    @Test
    void anOverwriteIsOneHistoryOperationAndMovesNoEntry() {
        HistoryService.Session[] holder = new HistoryService.Session[1];
        HistoryService h = pushed(holder);
        HistoryService.Session s = holder[0];
        step(h, s, HistoryService.Op.UNDO, 2);
        step(h, s, HistoryService.Op.UNDO, 3);
        HistoryService.Run run = h.beginOverwrite(s);
        assertEquals(List.of("c", "b"), labels(run.entries()));
        assertTrue(s.busy() && s.overwriting());
        assertThrows(IllegalStateException.class, () -> h.beginOverwrite(s), "one operation at a time");
        HistoryEntry a = h.candidate(s, HistoryService.Op.UNDO).orElseThrow();
        assertThrows(IllegalStateException.class, () -> h.begin(s, HistoryService.Op.UNDO, a));

        h.finishOverwrite(s, false);
        assertFalse(s.busy());
        assertEquals(List.of("a"), labels(h.undoEntries(P1)), "no entry moved");
        assertEquals(List.of("b", "c"), labels(h.redoEntries(P1)));
        assertEquals(List.of("c", "b"), runLabels(s), "a cancelled overwrite keeps the run, to be tried again");

        h.beginOverwrite(s);
        h.push(s, entry(P1, "late", 'z', 3)); // waits for the overwrite
        assertEquals(1, s.deferredCount());
        h.finishOverwrite(s, true);
        assertTrue(s.run().isEmpty(), "a completed overwrite ends the run");
        assertEquals(List.of("late", "a"), labels(h.undoEntries(P1)));

        HistoryService.Session[] again = new HistoryService.Session[1];
        HistoryService g = pushed(again);
        step(g, again[0], HistoryService.Op.UNDO, 1);
        g.beginOverwrite(again[0]);
        g.finish(again[0], true, true, 0); // the job's finish routes to the overwrite
        assertFalse(again[0].busy());
        assertEquals(List.of("b", "a"), labels(g.undoEntries(P1)), "still no entry moved");
        assertTrue(again[0].run().isEmpty());

        // A failed overwrite (the job's finish, not completed) keeps the run, like a cancelled one.
        HistoryService.Session[] failed = new HistoryService.Session[1];
        HistoryService f = pushed(failed);
        step(f, failed[0], HistoryService.Op.UNDO, 3);
        f.beginOverwrite(failed[0]);
        f.finish(failed[0], false, false, 0);
        assertFalse(failed[0].busy());
        assertEquals(List.of("c"), runLabels(failed[0]), "a failed overwrite keeps the run");
        assertNull(f.overwriteRefusal(failed[0], HistoryService.Op.UNDO, 1));

        HistoryService.Session[] other = new HistoryService.Session[1];
        HistoryService n = pushed(other);
        assertThrows(IllegalStateException.class, () -> n.beginOverwrite(other[0]), "no run");
        step(n, other[0], HistoryService.Op.UNDO, 5);
        n.editStarted(other[0]);
        assertThrows(IllegalStateException.class, () -> n.beginOverwrite(other[0]), "an edit of the player is running");
        n.editFinished(other[0], null);
        n.beginOverwrite(other[0]);
        n.finishOverwrite(other[0], true);
    }
}
