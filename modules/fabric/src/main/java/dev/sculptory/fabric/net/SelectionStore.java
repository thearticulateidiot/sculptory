package dev.sculptory.fabric.net;

import dev.sculptory.core.Sha256;
import dev.sculptory.core.region.CellSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The cell sets one connection uploaded ({@code SelectionUpload}), by hash: at
 * most {@value #MAX_SETS}, and at most a byte cap together ({@link CellSet#estimatedBytes}); the least recently used go
 * first. It lives in the {@link NetSession}, so it ends with the connection. Every use is stamped by the caller with a
 * server-wide counter, so the dispatcher can drop the least recently used set of any connection when all of them
 * together pass the server's cap ({@link #oldestStamp}, {@link #dropOldest}). Server thread only.
 */
public final class SelectionStore {
    /** Sets kept per connection. */
    public static final int MAX_SETS = 4;

    /** A kept set and when it was last used. */
    private static final class Held {
        final CellSet set;
        long stamp;

        Held(CellSet set, long stamp) {
            this.set = set;
            this.stamp = stamp;
        }
    }

    /** In access order: the first is the least recently used. */
    private final LinkedHashMap<Sha256, Held> sets = new LinkedHashMap<>(8, 0.75f, true);
    private long bytes;

    /**
     * Keeps {@code set} as the most recently used (at {@code stamp}), dropping the least recently used others beyond
     * {@value #MAX_SETS} sets or {@code maxBytes} together.
     *
     * @return false (nothing kept) when the set alone is over {@code maxBytes}
     */
    public boolean put(CellSet set, long maxBytes, long stamp) {
        Objects.requireNonNull(set);
        long size = set.estimatedBytes();
        if (size > maxBytes) return false;
        Held previous = sets.remove(set.hash());
        if (previous != null) bytes -= previous.set.estimatedBytes();
        Iterator<Map.Entry<Sha256, Held>> oldest = sets.entrySet().iterator();
        while (oldest.hasNext() && (sets.size() >= MAX_SETS || bytes + size > maxBytes)) {
            bytes -= oldest.next().getValue().set.estimatedBytes();
            oldest.remove();
        }
        sets.put(set.hash(), new Held(set, stamp));
        bytes += size;
        return true;
    }

    /** The set with this hash, marked as used at {@code stamp}. */
    public Optional<CellSet> get(Sha256 hash, long stamp) {
        Held held = sets.get(hash);
        if (held == null) return Optional.empty();
        held.stamp = stamp;
        return Optional.of(held.set);
    }

    /** Whether the set with this hash is kept (without marking it used). */
    public boolean contains(Sha256 hash) {
        return sets.containsKey(hash);
    }

    /** When the least recently used set was last used ({@link Long#MAX_VALUE} when empty). */
    public long oldestStamp() {
        return sets.isEmpty() ? Long.MAX_VALUE : sets.values().iterator().next().stamp;
    }

    /** Drops the least recently used set; returns the bytes freed (0 when empty). */
    public long dropOldest() {
        Iterator<Held> oldest = sets.values().iterator();
        if (!oldest.hasNext()) return 0;
        long freed = oldest.next().set.estimatedBytes();
        oldest.remove();
        bytes -= freed;
        return freed;
    }

    public int size() {
        return sets.size();
    }

    /** What the sets hold together ({@link CellSet#estimatedBytes}). */
    public long bytes() {
        return bytes;
    }

    public void clear() {
        sets.clear();
        bytes = 0;
    }
}
