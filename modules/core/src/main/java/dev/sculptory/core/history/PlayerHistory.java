package dev.sculptory.core.history;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One undo/redo stack per player, across all tools. Not thread-safe (server thread only). The server saves every change
 * to it through {@code core.history.store.HistoryStore}; {@link #restore} rebuilds one from disk.
 *
 * <p>Entries are kept oldest first. The first {@code applied} entries are done (the newest of them is the
 * undo candidate); the rest have been undone (the oldest of them is the redo candidate).
 */
public final class PlayerHistory {
    /** An entry, counted as {@code bytes}, of which {@code grown} were added by {@link #replace} (folded trails). */
    private record Slot(HistoryEntry entry, long bytes, long grown) {}

    private final HistoryLimits limits;
    private final ArrayList<Slot> slots = new ArrayList<>();
    private int applied;
    private long bytes;
    /** The part of {@link #bytes} that {@link #replace} added. */
    private long grown;

    public PlayerHistory(HistoryLimits l) {
        this.limits = Objects.requireNonNull(l);
    }

    public HistoryLimits limits() {
        return limits;
    }

    /**
     * Pushes a new entry, clearing the redo side and evicting the oldest entries over
     * {@link HistoryLimits#maxEntries()} or {@link HistoryLimits#maxBytesPerPlayer()}. An entry that alone
     * exceeds the byte cap is evicted too.
     *
     * @return the evicted entries, oldest first (empty when nothing was evicted), so the caller can tell
     *     the player that the oldest steps were discarded; it contains {@code e} itself if {@code e} was too large
     * @throws IllegalArgumentException if the entry records no cells, or its id is already in this history
     */
    public List<HistoryEntry> push(HistoryEntry e) {
        Objects.requireNonNull(e);
        return push(e, e.record().estimatedBytes());
    }

    /**
     * {@link #push(HistoryEntry)} counting the entry as {@code bytes} (its size as measured when it was first pushed,
     * for an entry restored from disk).
     */
    public List<HistoryEntry> push(HistoryEntry e, long bytes) {
        Objects.requireNonNull(e);
        if (e.record().isEmpty()) throw new IllegalArgumentException("History entry records nothing");
        for (Slot slot : slots) {
            if (slot.entry().id().equals(e.id())) throw new IllegalArgumentException("Duplicate history entry " + e.id());
        }
        while (slots.size() > applied) remove(slots.size() - 1);
        Slot slot = new Slot(e, Math.max(0, bytes), 0);
        slots.add(slot);
        this.bytes += slot.bytes();
        applied++;
        List<HistoryEntry> evicted = new ArrayList<>();
        while (!slots.isEmpty() && (slots.size() > limits.maxEntries() || this.bytes > limits.maxBytesPerPlayer())) {
            evicted.add(remove(0).entry());
            applied--;
        }
        return List.copyOf(evicted);
    }

    /**
     * A history holding exactly {@code entries} (oldest first, each counted as its {@code bytes}), the first
     * {@code applied} undoable, without applying the caps (see {@link #trimToLimits()}): a history restored from disk.
     *
     * @throws IllegalArgumentException for mismatched lengths, an empty or duplicate entry, or a bad {@code applied}
     */
    public static PlayerHistory restore(HistoryLimits limits, List<HistoryEntry> entries, long[] bytes, int applied) {
        PlayerHistory history = new PlayerHistory(limits);
        if (entries.size() != bytes.length) throw new IllegalArgumentException("Entries and sizes differ");
        if (applied < 0 || applied > entries.size()) throw new IllegalArgumentException("applied " + applied);
        java.util.Set<UUID> ids = new java.util.HashSet<>();
        for (int i = 0; i < entries.size(); i++) {
            HistoryEntry e = Objects.requireNonNull(entries.get(i));
            if (e.record().isEmpty()) throw new IllegalArgumentException("History entry records nothing");
            if (!ids.add(e.id())) throw new IllegalArgumentException("Duplicate history entry " + e.id());
            Slot slot = new Slot(e, Math.max(0, bytes[i]), 0);
            history.slots.add(slot);
            history.bytes += slot.bytes();
        }
        history.applied = applied;
        return history;
    }

    /**
     * Evicts entries ({@link #evictOldest()} order) until the history fits {@link HistoryLimits#maxEntries()} and
     * {@link HistoryLimits#maxBytesPerPlayer()}.
     *
     * @return the evicted entries, in eviction order
     */
    public List<HistoryEntry> trimToLimits() {
        List<HistoryEntry> evicted = new ArrayList<>();
        while (!slots.isEmpty() && (slots.size() > limits.maxEntries() || bytes > limits.maxBytesPerPlayer())) {
            evicted.add(evictOldest().orElseThrow());
        }
        return List.copyOf(evicted);
    }

    /**
     * Replaces the entry with {@code e}'s id by {@code e}, in its place on its side (an entry whose record grew by what
     * its fluid did since, {@link TrailFold}). It is counted as its old size plus the growth of its estimate
     * ({@link #bytes()}), while {@link #pushedBytes()} leaves the growth out. The caps are not applied here: the next
     * push or trim applies them.
     *
     * @return false, changing nothing, when no entry with that id is held
     * @throws IllegalArgumentException if {@code e} records nothing
     */
    public boolean replace(HistoryEntry e) {
        Objects.requireNonNull(e);
        if (e.record().isEmpty()) throw new IllegalArgumentException("History entry records nothing");
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            if (!slot.entry().id().equals(e.id())) continue;
            long growth = Math.max(0, e.record().estimatedBytes() - slot.entry().record().estimatedBytes());
            slots.set(i, new Slot(e, slot.bytes() + growth, slot.grown() + growth));
            bytes += growth;
            grown += growth;
            return true;
        }
        return false;
    }

    /** The bytes {@code id} is counted as, or -1 when it is not held. */
    public long bytesOf(UUID id) {
        for (Slot slot : slots) {
            if (slot.entry().id().equals(id)) return slot.bytes();
        }
        return -1;
    }

    /** The entry the next undo would revert. */
    public Optional<HistoryEntry> undoCandidate() {
        return applied > 0 ? Optional.of(slots.get(applied - 1).entry()) : Optional.empty();
    }

    /** The entry the next redo would re-apply. */
    public Optional<HistoryEntry> redoCandidate() {
        return applied < slots.size() ? Optional.of(slots.get(applied).entry()) : Optional.empty();
    }

    /**
     * Moves the undo candidate to the redo side, after its undo job finished.
     *
     * <p>The caller must serialize a player's history operations: no push, undo or redo may finish while an
     * undo or redo of that player is running. Then the id is always the candidate. A {@code false} return
     * means that rule was broken: the undo's writes are in the world but the stack no longer matches them.
     * The caller must not retry the mark or re-run the job. It should log the event as an internal error,
     * leave the stack as it is and send the player a fresh history state. The next undo of the same entry
     * then finds its cells already restored and writes nothing.
     *
     * @return false, changing nothing, if {@code id} is not the current undo candidate
     */
    public boolean markUndone(UUID id) {
        Objects.requireNonNull(id);
        if (applied == 0 || !slots.get(applied - 1).entry().id().equals(id)) return false;
        applied--;
        return true;
    }

    /**
     * Moves the redo candidate back to the undo side, after its redo job finished. The same serialization
     * rule and handling of a {@code false} return apply as for {@link #markUndone}.
     *
     * @return false, changing nothing, if {@code id} is not the current redo candidate
     */
    public boolean markRedone(UUID id) {
        Objects.requireNonNull(id);
        if (applied == slots.size() || !slots.get(applied).entry().id().equals(id)) return false;
        applied++;
        return true;
    }

    /**
     * Evicts one entry to relieve a global memory cap: the oldest undoable entry, or when nothing is
     * undoable, the redo entry furthest from the present (so the remaining redo chain stays valid).
     */
    public Optional<HistoryEntry> evictOldest() {
        if (slots.isEmpty()) return Optional.empty();
        if (applied > 0) {
            applied--;
            return Optional.of(remove(0).entry());
        }
        return Optional.of(remove(slots.size() - 1).entry());
    }

    /** Undoable entries, next undo first. */
    public List<HistoryEntry> undoEntries() {
        List<HistoryEntry> entries = new ArrayList<>(applied);
        for (int i = applied - 1; i >= 0; i--) entries.add(slots.get(i).entry());
        return List.copyOf(entries);
    }

    /** Redoable entries, next redo first. */
    public List<HistoryEntry> redoEntries() {
        List<HistoryEntry> entries = new ArrayList<>(slots.size() - applied);
        for (int i = applied; i < slots.size(); i++) entries.add(slots.get(i).entry());
        return List.copyOf(entries);
    }

    /** Number of entries held (undoable plus redoable). */
    public int size() {
        return slots.size();
    }

    /**
     * Estimated bytes held by all entries (each measured once, when pushed, plus what {@link #replace} added since):
     * what the caps count.
     */
    public long bytes() {
        return bytes;
    }

    /**
     * {@link #bytes()} without what {@link #replace} added: the size the player's client is shown. It changes only when
     * an entry comes or goes, so the client can take a change of it for an eviction (a trail folded into an entry at a
     * chunk save, or by the step in flight, is not one).
     */
    public long pushedBytes() {
        return bytes - grown;
    }

    private Slot remove(int index) {
        Slot slot = slots.remove(index);
        bytes -= slot.bytes();
        grown -= slot.grown();
        return slot;
    }
}
