package dev.sculptory.fabric.engine.impl;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.BitSet;
import java.util.Objects;

/**
 * A rolling window of chunk tickets over a {@link ColumnPlan}: the column of the current section plus the next
 * columns in first-use order, at most {@code max} in flight, each released once its last section is done.
 */
public final class TicketWindow {
    /** Adds, removes or refreshes one chunk ticket. */
    public interface Tickets {
        void add(int cx, int cz);

        void remove(int cx, int cz);

        /** Restarts the expiry timer of a ticket this caller holds. */
        void refresh(int cx, int cz);
    }

    private final ColumnPlan plan;
    private final Tickets tickets;
    private final int max;
    private final IntArrayList held = new IntArrayList();
    /** The same columns as {@link #held}, by index, for {@link #holds}. */
    private final BitSet heldSet = new BitSet();
    private int next;

    public TicketWindow(ColumnPlan plan, Tickets tickets, int max) {
        this.plan = Objects.requireNonNull(plan);
        this.tickets = Objects.requireNonNull(tickets);
        if (max < 1) throw new IllegalArgumentException("max must be positive");
        this.max = max;
    }

    /**
     * Moves to section index {@code s}: releases columns whose last section is before {@code s}, makes sure the
     * column of section {@code s} is ticketed, then tickets ahead up to the limit. Indices only move forward.
     */
    public void advance(int s) {
        for (int k = held.size() - 1; k >= 0; k--) {
            int c = held.getInt(k);
            if (plan.lastUse(c) < s) {
                remove(c);
                held.removeInt(k);
                heldSet.clear(c);
            }
        }
        if (s < plan.sectionCount()) {
            int current = plan.columnOfSection(s);
            while (next <= current) take(next++, s);
        }
        while (held.size() < max && next < plan.columnCount()) take(next++, s);
    }

    /** Restarts the expiry timer of every held ticket. */
    public void refresh() {
        for (int k = 0; k < held.size(); k++) {
            long column = plan.column(held.getInt(k));
            tickets.refresh(ColumnPlan.unpackX(column), ColumnPlan.unpackZ(column));
        }
    }

    public void releaseAll() {
        for (int k = 0; k < held.size(); k++) remove(held.getInt(k));
        held.clear();
        heldSet.clear();
    }

    public int inFlight() {
        return held.size();
    }

    /** Whether this window holds the ticket of packed column {@code column}. */
    public boolean holds(long column) {
        int c = plan.indexOf(column);
        return c >= 0 && heldSet.get(c);
    }

    private void take(int c, int s) {
        if (plan.lastUse(c) < s) return;
        add(c);
        held.add(c);
        heldSet.set(c);
    }

    private void add(int c) {
        long column = plan.column(c);
        tickets.add(ColumnPlan.unpackX(column), ColumnPlan.unpackZ(column));
    }

    private void remove(int c) {
        long column = plan.column(c);
        tickets.remove(ColumnPlan.unpackX(column), ColumnPlan.unpackZ(column));
    }
}
