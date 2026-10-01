package dev.sculptory.core.scatter;

import dev.sculptory.core.Sha256;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.state.StateSpace;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Footprints (a source's non-air cells relative to its anchor) shared between planners, keyed by the source's
 * content hash (which covers its cells, size and anchor), least recently used first out, bounded by entries and
 * cells. Repeated previews of the same variants then skip the per-cell pass over each source. For one state space
 * at a time (a different one clears it). Not thread-safe.
 */
public final class FootprintCache {
    private final LinkedHashMap<Sha256, Footprint> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final int maxEntries;
    private final long maxCells;
    private StateSpace states;
    private long cells;

    public FootprintCache(int maxEntries, long maxCells) {
        if (maxEntries < 1 || maxCells < 1) throw new IllegalArgumentException("Cache bounds must be positive");
        this.maxEntries = maxEntries;
        this.maxCells = maxCells;
    }

    /** The footprint of {@code source}, computed once per content hash while cached. */
    Footprint get(Clipboard source, StateSpace space) {
        Objects.requireNonNull(source);
        if (space != states) {
            entries.clear();
            cells = 0;
            states = space;
        }
        Sha256 key = source.contentHash();
        Footprint footprint = entries.get(key);
        if (footprint != null) return footprint;
        footprint = Footprint.of(source, space);
        if (footprint.cells > maxCells) return footprint;
        entries.put(key, footprint);
        cells += footprint.cells;
        Iterator<Map.Entry<Sha256, Footprint>> oldest = entries.entrySet().iterator();
        while ((entries.size() > maxEntries || cells > maxCells) && oldest.hasNext()) {
            Footprint evicted = oldest.next().getValue();
            if (evicted == footprint) break;
            cells -= evicted.cells;
            oldest.remove();
        }
        return footprint;
    }

    /** Cached footprints. */
    public int size() {
        return entries.size();
    }

    /** Cells held by the cached footprints. */
    public long cells() {
        return cells;
    }
}
