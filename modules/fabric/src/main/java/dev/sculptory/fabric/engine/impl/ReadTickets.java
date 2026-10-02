package dev.sculptory.fabric.engine.impl;

import dev.sculptory.core.edit.EditProgram;
import dev.sculptory.server.engine.impl.TicketWindow;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Objects;

/**
 * The extra chunk tickets a bulk job takes on the columns a section's decisions read ({@link EditProgram#readColumns}),
 * at most {@code max} at any moment. Server thread only.
 *
 * <ul>
 *   <li>{@link #begin} a section: tickets from the section before that it reads too are kept, the others released
 *       (and so are those the job's ticket window now holds: its own ticket covers them). Columns loaded or held by
 *       the window need no ticket. The rest are ticketed, in order, while the job holds fewer than {@code max};
 *       those left over are dropped from the section, which is decided without them ({@link #cappedOut}).</li>
 *   <li>{@link #ready} is asked until every column kept is loaded. A column loaded at {@code begin} that unloads
 *       meanwhile is ticketed while there is room, else dropped too.</li>
 * </ul>
 */
final class ReadTickets {
    /** What the job knows about a column. */
    interface Chunks {
        boolean loaded(long column);

        /** Whether the job's ticket window holds the column's ticket (it is loading or loaded). */
        boolean windowHolds(long column);
    }

    private final TicketWindow.Tickets tickets;
    private final int max;
    private final LongOpenHashSet held = new LongOpenHashSet();
    /** The section's columns still waited for (loaded, window-held, ticketed, or to be ticketed while room). */
    private long[] columns = EditProgram.NO_COLUMNS;
    private boolean cappedOut;
    private long missing;

    ReadTickets(TicketWindow.Tickets tickets, int max) {
        this.tickets = Objects.requireNonNull(tickets);
        if (max < 0) throw new IllegalArgumentException("Negative ticket cap");
        this.max = max;
    }

    /** Starts a section that reads {@code sectionColumns} (packed with {@link EditProgram#column}). */
    void begin(long[] sectionColumns, Chunks chunks) {
        LongOpenHashSet reads = new LongOpenHashSet(sectionColumns);
        held.removeIf((long column) -> {
            if (reads.contains(column) && !chunks.windowHolds(column)) return false;
            remove(column);
            return true;
        });
        LongArrayList kept = new LongArrayList(reads.size());
        LongOpenHashSet seen = new LongOpenHashSet(reads.size());
        cappedOut = false;
        for (long column : sectionColumns) {
            if (!seen.add(column)) continue; // listed twice
            if (chunks.loaded(column) || chunks.windowHolds(column) || held.contains(column)) {
                kept.add(column);
            } else if (held.size() < max) {
                take(column);
                kept.add(column);
            } else {
                cappedOut = true; // no room: decided without it
            }
        }
        columns = kept.toLongArray();
    }

    /**
     * Whether every column the section still waits for is loaded; when not, {@link #missing} names one. Tickets a
     * column that unloaded since {@link #begin} while there is room, and drops it when there is none.
     */
    boolean ready(Chunks chunks) {
        boolean ready = true;
        LongArrayList kept = null; // made at the first column dropped
        for (int k = 0; k < columns.length; k++) {
            long column = columns[k];
            boolean drop = false;
            if (!chunks.loaded(column)) {
                if (!chunks.windowHolds(column) && !held.contains(column)) {
                    if (held.size() >= max) {
                        drop = true; // no room: decided with what is loaded
                    } else {
                        take(column);
                    }
                }
                if (!drop) {
                    if (ready) missing = column;
                    ready = false;
                }
            }
            if (drop && kept == null) {
                kept = new LongArrayList(columns.length);
                for (int j = 0; j < k; j++) kept.add(columns[j]);
            } else if (!drop && kept != null) {
                kept.add(column);
            }
        }
        if (kept != null) {
            cappedOut = true;
            columns = kept.toLongArray();
        }
        return ready;
    }

    /** A column {@link #ready} found not loaded. */
    long missing() {
        return missing;
    }

    /** Whether the current section dropped columns for want of tickets (placements reaching them are skipped). */
    boolean cappedOut() {
        return cappedOut;
    }

    /** The most tickets held at once allowed. */
    int max() {
        return max;
    }

    void refresh() {
        for (long column : held) tickets.refresh(EditProgram.columnX(column), EditProgram.columnZ(column));
    }

    void releaseAll() {
        for (long column : held) remove(column);
        held.clear();
        columns = EditProgram.NO_COLUMNS;
    }

    private void take(long column) {
        if (held.add(column)) tickets.add(EditProgram.columnX(column), EditProgram.columnZ(column));
    }

    private void remove(long column) {
        tickets.remove(EditProgram.columnX(column), EditProgram.columnZ(column));
    }
}
