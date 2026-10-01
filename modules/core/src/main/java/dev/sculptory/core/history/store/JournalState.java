package dev.sculptory.core.history.store;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One player's history as a journal file describes it: the stack (entries in push order, the first {@code applied}
 * of them undoable), the entries begun but not pushed ({@code pending}: jobs still running, pushes waiting for an undo
 * or redo, or work interrupted by a crash) and a redo in flight. Built by replaying the file's records in order, with
 * the same stack semantics as {@code PlayerHistory}: a push drops the redo side; undo and redo apply only to the
 * current candidate; an eviction removes the entry wherever it is. Replay is lenient: a record that does not apply
 * (an unknown entry, a duplicate, a mark for an entry that is not the candidate) is skipped and counted, never fatal,
 * since every stack is safe to undo (conflicting cells are skipped).
 *
 * <p>For each live entry it keeps where its data records are ({@link Ref}), so an entry's record can be read without
 * reading the rest of the file, and the file can be compacted by copying only live records. Confined to one thread.
 */
final class JournalState {
    /** A record's place in the file: the offset of its frame and its whole length (frame and payload). */
    record Ref(long offset, int length) {}

    /** A section record of an entry: where it is, what data version it holds and how many cells it keeps. */
    record SectionRef(Ref ref, int dataVersion, int cells) {}

    /** An entities record of an entry: where it is, what data version it holds and how many changes. */
    record EntitiesRef(Ref ref, int dataVersion, int changes) {}

    static final class Entry {
        final UUID id;
        final Ref begin;
        final long created;
        final String world;
        final String baseLabel;
        /** Latest section record per section key, in first-recorded order. */
        final LinkedHashMap<Long, SectionRef> sections = new LinkedHashMap<>();
        /** Every entities record, in file order (a later change of an entity replaces an earlier one when read). */
        final List<EntitiesRef> entities = new ArrayList<>();
        Ref seal;
        String label;
        long sealedCreated;
        long bytes;
        boolean pushed;
        /** Order in which entries were sealed (a push waiting behind an undo is sealed when its job finished). */
        long sealOrder;

        Entry(UUID id, Ref begin, long created, String world, String baseLabel) {
            this.id = id;
            this.begin = begin;
            this.created = created;
            this.world = world;
            this.baseLabel = baseLabel;
        }

        boolean sealed() {
            return seal != null;
        }

        /** The label to show: the sealed one, or the base label while unsealed. */
        String label() {
            return label != null ? label : baseLabel;
        }

        long createdMillis() {
            return seal != null ? sealedCreated : created;
        }

        /** Bytes of the entry's live data records: its begin, its latest sections, its entities and its seal. */
        long diskBytes() {
            long total = begin.length() + (seal == null ? 0 : seal.length());
            for (SectionRef section : sections.values()) total += section.ref().length();
            for (EntitiesRef batch : entities) total += batch.ref().length();
            return total;
        }

        /** Whether the entry recorded any cell or entity change (what an interrupted edit is kept for). */
        boolean hasData() {
            for (SectionRef section : sections.values()) {
                if (section.cells() > 0) return true;
            }
            for (EntitiesRef batch : entities) {
                if (batch.changes() > 0) return true;
            }
            return false;
        }
    }

    final UUID player;
    final Map<UUID, Entry> entries = new HashMap<>();
    final ArrayList<Entry> stack = new ArrayList<>();
    int applied;
    final LinkedHashMap<UUID, Entry> pending = new LinkedHashMap<>();
    /** The entry of a redo whose job was admitted and has not ended. */
    UUID redoInFlight;
    /** Records that did not apply. */
    int skipped;
    /** Seals applied so far (orders {@link Entry#sealOrder}). */
    private long seals;

    JournalState(UUID player) {
        this.player = player;
    }

    boolean isEmpty() {
        return stack.isEmpty() && pending.isEmpty();
    }

    /** Whether a live entry holds an entities record (a copy of the file then needs {@link Journal#FLAG_ENTITIES}). */
    boolean holdsEntities() {
        for (Entry entry : entries.values()) {
            if (!entry.entities.isEmpty()) return true;
        }
        return false;
    }

    /** Applies one record found at {@code ref}. */
    void apply(Journal.Op op, Ref ref) {
        switch (op) {
            case Journal.Begin begin -> {
                if (entries.containsKey(begin.entry())) {
                    skipped++;
                    return;
                }
                Entry entry = new Entry(begin.entry(), ref, begin.created(), begin.world(), begin.label());
                entries.put(entry.id, entry);
                pending.put(entry.id, entry);
            }
            case Journal.Section section -> {
                Entry entry = entries.get(section.entry());
                if (entry == null) {
                    skipped++;
                    return;
                }
                entry.sections.put(section.key(), new SectionRef(ref, section.dataVersion(), section.cells()));
            }
            case Journal.Entities batch -> {
                Entry entry = entries.get(batch.entry());
                if (entry == null) {
                    skipped++;
                    return;
                }
                entry.entities.add(new EntitiesRef(ref, batch.dataVersion(), batch.changes()));
            }
            case Journal.Seal seal -> {
                Entry entry = entries.get(seal.entry());
                if (entry == null) {
                    skipped++;
                    return;
                }
                entry.seal = ref;
                entry.label = seal.label();
                entry.sealedCreated = seal.created();
                entry.bytes = seal.bytes();
                entry.sealOrder = ++seals;
            }
            case Journal.Mark mark -> {
                if (!applyMark(mark.type(), mark.entry())) skipped++;
            }
        }
    }

    private boolean applyMark(Journal.Type type, UUID id) {
        Entry entry = entries.get(id);
        switch (type) {
            case PUSH -> {
                if (entry == null || entry.pushed) return false;
                while (stack.size() > applied) entries.remove(stack.remove(stack.size() - 1).id);
                if (redoInFlight != null && !entries.containsKey(redoInFlight)) redoInFlight = null;
                pending.remove(id);
                entry.pushed = true;
                stack.add(entry);
                applied++;
                return true;
            }
            case UNDONE -> {
                if (applied == 0 || !stack.get(applied - 1).id.equals(id)) return false;
                applied--;
                return true;
            }
            case REDONE -> {
                if (applied == stack.size() || !stack.get(applied).id.equals(id)) return false;
                applied++;
                if (id.equals(redoInFlight)) redoInFlight = null;
                return true;
            }
            case REDO_BEGIN -> {
                if (entry == null || !entry.pushed) return false;
                redoInFlight = id;
                return true;
            }
            case REDO_ABORT -> {
                if (!id.equals(redoInFlight)) return false;
                redoInFlight = null;
                return true;
            }
            case EVICT -> {
                if (entry == null) return false;
                if (entry.pushed) {
                    int index = stack.indexOf(entry);
                    stack.remove(index);
                    if (index < applied) applied--;
                } else {
                    pending.remove(id);
                }
                entries.remove(id);
                if (id.equals(redoInFlight)) redoInFlight = null;
                return true;
            }
            case ABORT -> {
                if (entry == null || entry.pushed) return false;
                pending.remove(id);
                entries.remove(id);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /**
     * Bytes a compacted file holding exactly this state would take: the header, every live entry's data records, a
     * push per stack entry, an undo mark per redo entry and a redo-in-flight mark.
     */
    long liveBytes() {
        long total = Journal.HEADER_BYTES;
        for (Entry entry : entries.values()) total += entry.diskBytes();
        total += (long) stack.size() * Journal.MARK_BYTES + (long) (stack.size() - applied) * Journal.MARK_BYTES;
        if (redoInFlight != null) total += Journal.MARK_BYTES;
        return total;
    }

    /** The stack as stored metadata (for the server thread). */
    StoredHistory toStored() {
        List<StoredHistory.Entry> list = new ArrayList<>(stack.size());
        for (Entry entry : stack) {
            list.add(new StoredHistory.Entry(entry.id, entry.world, entry.label(), entry.createdMillis(), entry.bytes,
                    entry.diskBytes()));
        }
        return new StoredHistory(player, list, applied);
    }
}
