package dev.sculptory.fabric.engine.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockBuffer;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TicketWindowTest {
    /** Records ticket operations and the maximum held at once. */
    private static final class FakeTickets implements TicketWindow.Tickets {
        final Set<Long> held = new HashSet<>();
        final List<String> log = new ArrayList<>();
        int maxHeld;

        @Override
        public void add(int cx, int cz) {
            held.add(ColumnPlan.pack(cx, cz));
            maxHeld = Math.max(maxHeld, held.size());
            log.add("+" + cx + "," + cz);
        }

        @Override
        public void remove(int cx, int cz) {
            assertTrue(held.remove(ColumnPlan.pack(cx, cz)), "removed a ticket that was not held");
            log.add("-" + cx + "," + cz);
        }

        @Override
        public void refresh(int cx, int cz) {
            assertTrue(held.contains(ColumnPlan.pack(cx, cz)), "refreshed a ticket that is not held");
            log.add("~" + cx + "," + cz);
        }
    }

    /** M2: two windows on one column share a single underlying ticket, released only by the last holder. */
    @Test
    void countedTicketsKeepASharedColumnUntilTheLastHolder() {
        FakeTickets vanilla = new FakeTickets();
        CountedTickets counted = new CountedTickets(vanilla);
        long a = BlockBuffer.key(3, 0, 4), c = BlockBuffer.key(3, 5, 4);
        TicketWindow first = new TicketWindow(ColumnPlan.of(new long[] {a}), counted, 8);
        TicketWindow second = new TicketWindow(ColumnPlan.of(new long[] {c}), counted, 8);
        first.advance(0);
        second.advance(0);
        assertEquals(1, vanilla.held.size());
        assertEquals(2, counted.holders(3, 4));
        first.releaseAll();
        assertEquals(1, vanilla.held.size(), "releasing one job dropped the other job's ticket");
        assertEquals(1, counted.holders(3, 4));
        second.refresh();
        assertEquals(1, counted.holders(3, 4), "refresh changed the holder count");
        second.releaseAll();
        assertTrue(vanilla.held.isEmpty());
        assertEquals(0, counted.held());
        assertEquals(List.of("+3,4", "~3,4", "-3,4"), vanilla.log);
        counted.remove(3, 4); // an unbalanced release is ignored, not passed on
        assertEquals(List.of("+3,4", "~3,4", "-3,4"), vanilla.log);
    }

    private static long[] sections(Box box) {
        LongArrayList keys = new LongArrayList();
        box.forEachSectionKey(keys::add);
        return keys.toLongArray();
    }

    @Test
    void planListsColumnsInFirstUseOrderWithLastUse() {
        long[] keys = sections(new Box(new BlockPos(-16, 0, 0), new BlockPos(15, 31, 15)));
        ColumnPlan plan = ColumnPlan.of(keys);
        assertEquals(4, plan.sectionCount());
        assertEquals(2, plan.columnCount());
        assertEquals(-1, ColumnPlan.unpackX(plan.column(0)));
        assertEquals(0, ColumnPlan.unpackZ(plan.column(0)));
        assertEquals(1, plan.lastUse(0));
        assertEquals(3, plan.lastUse(1));
        assertEquals(1, plan.columnOfSection(2));
        assertEquals(BlockBuffer.keyX(keys[0]), -1);
    }

    @Test
    void windowNeverExceedsItsLimitAndReleasesEveryColumn() {
        long[] keys = sections(new Box(new BlockPos(0, 0, 0), new BlockPos(16 * 10 - 1, 47, 16 * 10 - 1)));
        ColumnPlan plan = ColumnPlan.of(keys);
        FakeTickets tickets = new FakeTickets();
        TicketWindow window = new TicketWindow(plan, tickets, 8);
        for (int s = 0; s <= keys.length; s++) {
            window.advance(s);
            if (s < keys.length) {
                long column = plan.column(plan.columnOfSection(s));
                assertTrue(tickets.held.contains(column), "current column not ticketed at section " + s);
            }
        }
        assertEquals(8, tickets.maxHeld);
        assertEquals(0, window.inFlight());
        assertTrue(tickets.held.isEmpty());
        assertEquals(2L * plan.columnCount(), tickets.log.size(), "each column added and removed once");
    }

    @Test
    void revisitedColumnsStayTicketedUntilTheirLastUse() {
        long a = BlockBuffer.key(0, 0, 0), b = BlockBuffer.key(1, 0, 0);
        ColumnPlan plan = ColumnPlan.of(new long[] {a, b, a});
        FakeTickets tickets = new FakeTickets();
        TicketWindow window = new TicketWindow(plan, tickets, 1);
        window.advance(0);
        window.advance(1); // column b forced in even though the window is full: a is needed again later
        assertEquals(2, window.inFlight());
        window.advance(2);
        assertEquals(1, window.inFlight(), "b released after its last use");
        window.advance(3);
        assertEquals(0, window.inFlight());
        window.refresh();
        window.releaseAll();
        assertTrue(tickets.held.isEmpty());
    }

    /** {@code holds} answers from the window's held set, and follows advancing and releasing. */
    @Test
    void holdsFollowsTheWindow() {
        FakeTickets vanilla = new FakeTickets();
        long[] sections = {BlockBuffer.key(0, 0, 0), BlockBuffer.key(1, 0, 0), BlockBuffer.key(2, 0, 0)};
        TicketWindow window = new TicketWindow(ColumnPlan.of(sections), vanilla, 2);
        window.advance(0);
        assertTrue(window.holds(ColumnPlan.pack(0, 0)) && window.holds(ColumnPlan.pack(1, 0)));
        assertTrue(!window.holds(ColumnPlan.pack(2, 0)), "beyond the window");
        assertTrue(!window.holds(ColumnPlan.pack(7, 7)), "not in the plan");
        window.advance(1);
        assertTrue(!window.holds(ColumnPlan.pack(0, 0)) && window.holds(ColumnPlan.pack(2, 0)));
        window.releaseAll();
        assertTrue(!window.holds(ColumnPlan.pack(1, 0)) && !window.holds(ColumnPlan.pack(2, 0)));
    }
}
