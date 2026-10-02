package dev.sculptory.fabric.engine.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.edit.EditProgram;
import dev.sculptory.server.engine.impl.TicketWindow;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The extra tickets a bulk job takes on the chunks a section's decisions read: never more than the cap at any moment,
 * none for columns the ticket window holds, carried over between sections that read the same column.
 */
class ReadTicketsTest {
    private static final long A = EditProgram.column(1, 0), B = EditProgram.column(2, 0), C = EditProgram.column(3, 0);
    private static final long D = EditProgram.column(4, 0), W = EditProgram.column(9, 9);

    /** Tickets counted as they are added and removed, with the most held at once. */
    private static final class FakeTickets implements TicketWindow.Tickets {
        final Set<Long> held = new HashSet<>();
        final List<Long> added = new ArrayList<>();
        final List<Long> removed = new ArrayList<>();
        final List<Long> refreshed = new ArrayList<>();
        int peak;

        @Override
        public void add(int cx, int cz) {
            long column = EditProgram.column(cx, cz);
            if (!held.add(column)) throw new AssertionError("ticketed twice: " + cx + "," + cz);
            added.add(column);
            peak = Math.max(peak, held.size());
        }

        @Override
        public void remove(int cx, int cz) {
            long column = EditProgram.column(cx, cz);
            if (!held.remove(column)) throw new AssertionError("released a ticket not held: " + cx + "," + cz);
            removed.add(column);
        }

        @Override
        public void refresh(int cx, int cz) {
            refreshed.add(EditProgram.column(cx, cz));
        }
    }

    private static final class FakeChunks implements ReadTickets.Chunks {
        final Set<Long> loaded = new HashSet<>();
        final Set<Long> window = new HashSet<>();

        @Override
        public boolean loaded(long column) {
            return loaded.contains(column);
        }

        @Override
        public boolean windowHolds(long column) {
            return window.contains(column);
        }
    }

    private final FakeTickets tickets = new FakeTickets();
    private final FakeChunks chunks = new FakeChunks();

    @Test
    void withinTheCapEveryUnloadedColumnIsTicketedOnceAndWaitedFor() {
        ReadTickets reads = new ReadTickets(tickets, 3);
        chunks.loaded.add(D);
        reads.begin(new long[] {A, B, C, D}, chunks);
        assertFalse(reads.cappedOut());
        assertEquals(Set.of(A, B, C), tickets.held);
        assertFalse(reads.ready(chunks));
        assertFalse(reads.ready(chunks), "asking again takes no more tickets");
        assertEquals(3, tickets.added.size());
        chunks.loaded.addAll(List.of(A, B, C));
        assertTrue(reads.ready(chunks));
        assertEquals(3, tickets.peak);
        reads.releaseAll();
        assertTrue(tickets.held.isEmpty());
    }

    /** A column the job's ticket window holds is loading already: waited for, never ticketed a second time. */
    @Test
    void aColumnTheWindowHoldsIsWaitedForButNotTicketed() {
        ReadTickets reads = new ReadTickets(tickets, 1);
        chunks.window.add(W);
        reads.begin(new long[] {W, A}, chunks);
        assertFalse(reads.cappedOut(), "the window's column does not count against the cap");
        assertEquals(Set.of(A), tickets.held);
        chunks.loaded.add(A);
        assertFalse(reads.ready(chunks), "still waiting for the window's column");
        assertEquals(W, reads.missing());
        chunks.loaded.add(W);
        assertTrue(reads.ready(chunks));
        assertFalse(tickets.added.contains(W));
    }

    /**
     * With the cap already used by a ticket the section keeps, nothing new is ticketed: the columns needing a ticket are
     * dropped (the section is decided without them), while the ones already ticketed or in the window are still waited
     * for.
     */
    @Test
    void atTheCapNothingNewIsTicketedButHeldAndWindowColumnsAreStillWaitedFor() {
        ReadTickets reads = new ReadTickets(tickets, 1);
        reads.begin(new long[] {A}, chunks);
        assertEquals(Set.of(A), tickets.held);
        chunks.window.add(W);
        reads.begin(new long[] {A, B, C, W}, chunks);
        assertTrue(reads.cappedOut());
        assertEquals(Set.of(A), tickets.held, "B and C are not ticketed");
        assertFalse(reads.ready(chunks));
        chunks.loaded.add(A);
        assertFalse(reads.ready(chunks), "the window's column is still waited for");
        chunks.loaded.add(W);
        assertTrue(reads.ready(chunks), "B and C, not loaded, were dropped");
        assertEquals(1, tickets.peak);
    }

    /** Over the cap, begin is greedy: columns are ticketed in order while there is room, the rest dropped. */
    @Test
    void overTheCapBeginTicketsWhatFitsInOrder() {
        ReadTickets reads = new ReadTickets(tickets, 2);
        reads.begin(new long[] {A, B, C, D}, chunks);
        assertTrue(reads.cappedOut());
        assertEquals(Set.of(A, B), tickets.held, "the first two, in order");
        chunks.loaded.addAll(List.of(A, B));
        assertTrue(reads.ready(chunks), "C and D were dropped, not waited for");
        assertEquals(2, tickets.peak);
    }

    /** A column listed twice is ticketed once and released once (no leaked count). */
    @Test
    void aColumnListedTwiceIsTicketedOnce() {
        ReadTickets reads = new ReadTickets(tickets, 4);
        reads.begin(new long[] {A, A, B, A}, chunks);
        assertEquals(List.of(A, B), tickets.added);
        assertFalse(reads.ready(chunks));
        assertEquals(List.of(A, B), tickets.added, "ready takes no second ticket either");
        reads.begin(new long[] {B, B}, chunks);
        assertEquals(List.of(A), tickets.removed);
        reads.releaseAll();
        assertEquals(List.of(A, B), tickets.removed);
        assertTrue(tickets.held.isEmpty());
    }

    /** Tickets the next section reads too are kept; the others, and those the window now holds, are released. */
    @Test
    void ticketsCarryOverToTheNextSectionThatReadsThem() {
        ReadTickets reads = new ReadTickets(tickets, 4);
        reads.begin(new long[] {A, B}, chunks);
        reads.begin(new long[] {B, C}, chunks);
        assertEquals(Set.of(B, C), tickets.held);
        assertEquals(List.of(A), tickets.removed, "B was kept, not dropped and taken again");
        assertEquals(List.of(A, B, C), tickets.added);
        chunks.window.add(C);
        reads.begin(new long[] {C}, chunks);
        assertEquals(Set.of(), tickets.held, "the window's own ticket covers C");
        reads.refresh();
        assertTrue(tickets.refreshed.isEmpty());
    }

    /**
     * The review's case: the next section reads columns still loaded (not counted at its start) and one new one; the
     * loaded ones unload while it waits. They are ticketed only while there is room: the job never holds more than the
     * cap, and the rest are dropped.
     */
    @Test
    void columnsThatUnloadWhileTheSectionWaitsAreTicketedOnlyWithinTheCap() {
        ReadTickets reads = new ReadTickets(tickets, 2);
        chunks.loaded.addAll(List.of(A, B, C));
        reads.begin(new long[] {A, B, C, D}, chunks);
        assertFalse(reads.cappedOut());
        assertEquals(Set.of(D), tickets.held);
        chunks.loaded.removeAll(List.of(A, B, C));
        assertFalse(reads.ready(chunks));
        assertEquals(2, tickets.held.size(), "one more ticket, then the cap");
        assertTrue(reads.cappedOut());
        for (int i = 0; i < 5; i++) reads.ready(chunks);
        assertEquals(2, tickets.peak, "never more than the cap");
        chunks.loaded.addAll(tickets.held);
        assertTrue(reads.ready(chunks), "the dropped columns are not waited for");
    }
}
