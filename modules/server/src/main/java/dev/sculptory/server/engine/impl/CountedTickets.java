package dev.sculptory.server.engine.impl;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reference-counts chunk tickets per column for one world. A vanilla ticket is identified by (type, level,
 * argument), so every job's {@code sculptory:edit} ticket on a column is the same ticket: without counting,
 * one job releasing it would drop another job's hold. The underlying ticket is added on the first hold and
 * removed with the last.
 */
public final class CountedTickets implements TicketWindow.Tickets {
    private static final Logger LOG = LoggerFactory.getLogger("sculptory");

    private final TicketWindow.Tickets underlying;
    private final Long2IntOpenHashMap holders = new Long2IntOpenHashMap();

    public CountedTickets(TicketWindow.Tickets underlying) {
        this.underlying = Objects.requireNonNull(underlying);
    }

    @Override
    public void add(int cx, int cz) {
        if (holders.addTo(ColumnPlan.pack(cx, cz), 1) == 0) underlying.add(cx, cz);
    }

    @Override
    public void remove(int cx, int cz) {
        long column = ColumnPlan.pack(cx, cz);
        int count = holders.get(column);
        if (count <= 0) {
            LOG.warn("Sculptory released an edit ticket it does not hold at chunk {},{}", cx, cz);
            return;
        }
        if (count == 1) {
            holders.remove(column);
            underlying.remove(cx, cz);
        } else {
            holders.put(column, count - 1);
        }
    }

    /** Restarts the expiry timer of a held ticket; does not change the count. */
    @Override
    public void refresh(int cx, int cz) {
        if (holders.get(ColumnPlan.pack(cx, cz)) > 0) underlying.refresh(cx, cz);
    }

    /** Holders of the column's ticket (0 when no ticket is held). */
    public int holders(int cx, int cz) {
        return holders.get(ColumnPlan.pack(cx, cz));
    }

    /** Distinct tickets currently held. */
    public int held() {
        return holders.size();
    }
}
