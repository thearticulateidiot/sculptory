package dev.sculptory.core.history.store;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A player's saved history without its block data: what the server keeps in memory for a player whose history is not
 * loaded (offline), so the global caps and the age limit can evict their oldest entries. Entries are oldest first; the
 * first {@link #applied()} are undoable. Eviction follows {@code PlayerHistory.evictOldest}. Not thread-safe.
 */
public final class StoredHistory {
    /**
     * One saved entry.
     *
     * @param bytes the entry's estimated heap bytes when it was pushed (what the memory caps count)
     * @param diskBytes the bytes of its data in the journal
     */
    public record Entry(UUID id, String world, String label, long createdMillis, long bytes, long diskBytes) {
        public Entry {
            Objects.requireNonNull(id);
            Objects.requireNonNull(world);
            Objects.requireNonNull(label);
        }
    }

    private final UUID player;
    private final ArrayList<Entry> entries;
    private int applied;

    public StoredHistory(UUID player, List<Entry> entries, int applied) {
        this.player = Objects.requireNonNull(player);
        this.entries = new ArrayList<>(entries);
        if (applied < 0 || applied > entries.size()) throw new IllegalArgumentException("applied " + applied);
        this.applied = applied;
    }

    public UUID player() {
        return player;
    }

    /** All entries, oldest first. */
    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    /** How many of the oldest entries are undoable (the rest are redoable). */
    public int applied() {
        return applied;
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public long bytes() {
        long total = 0;
        for (Entry entry : entries) total += entry.bytes();
        return total;
    }

    public long diskBytes() {
        long total = 0;
        for (Entry entry : entries) total += entry.diskBytes();
        return total;
    }

    /** The entry {@link #evictOldest()} removes next: the oldest undoable one, else the redo entry furthest ahead. */
    public Optional<Entry> evictionCandidate() {
        if (entries.isEmpty()) return Optional.empty();
        return Optional.of(applied > 0 ? entries.get(0) : entries.get(entries.size() - 1));
    }

    public Optional<Entry> evictOldest() {
        if (entries.isEmpty()) return Optional.empty();
        if (applied > 0) {
            applied--;
            return Optional.of(entries.remove(0));
        }
        return Optional.of(entries.remove(entries.size() - 1));
    }

    @Override
    public String toString() {
        return "StoredHistory[" + player + ", " + entries.size() + " entries, " + applied + " undoable]";
    }
}
