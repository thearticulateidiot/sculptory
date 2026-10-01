package dev.sculptory.core.history;

import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.buffer.SectionBuffer;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Accumulates cell changes into an {@link EditRecord}. Also the brush-stroke coalescer: a cell may be
 * recorded any number of times, and the record keeps its first {@code before} and its last {@code after}.
 * Storage is per 16³ section. Not thread-safe.
 *
 * <p>Building a large record touches every cell, so a caller that knows a section is finished (a bulk job moving on)
 * may {@link #prepare} it early: that section's part of the record is built then, and {@link #build()} only collects
 * it (unless the section was recorded into again, which discards the early result). A prepared part is a second copy
 * of the section's kept cells until {@code build()}; sections cheap to build at the end are not prepared.
 *
 * <p><b>Saving while recording.</b> The builder remembers which sections were recorded into since they were last
 * {@link #drainDirty drained}, so the history journal can save an unfinished record section by section
 * ({@link #snapshotSection}) and a crash leaves what was written undoable. Each builder has a fresh {@link #id()}, which
 * the entry built from it takes.
 *
 * <p><b>Entities.</b> {@link #recordEntity} keeps, per UUID, the first {@code before} and the last {@code after}, like
 * cells; entities recorded since they were last {@link #drainEntities drained} are saved the same way.
 */
public final class RecordBuilder {
    private static final SectionBuffer[] NOTHING_KEPT = new SectionBuffer[0];

    private final UUID id = UUID.randomUUID();
    /** Sections recorded into since they were last drained. */
    private final LongOpenHashSet dirty = new LongOpenHashSet();
    /** Per entity: {first before, last after}, in first-recorded order. */
    private final LinkedHashMap<UUID, EntityState[]> entities = new LinkedHashMap<>();
    /** Entities recorded since they were last drained. */
    private final LinkedHashSet<UUID> dirtyEntities = new LinkedHashSet<>();
    private long entityBytes;
    private final BlockBuffer before = new BlockBuffer();
    private final BlockBuffer after = new BlockBuffer();
    /** Cached section pair for the last recorded cell: consecutive cells usually share a section. */
    private long lastKey;
    private SectionBuffer lastBefore;
    private SectionBuffer lastAfter;
    /** The last section is already in {@link #overwritten}. */
    private boolean lastOverwritten;
    /**
     * Sections where a cell's after state was replaced by another: their after palette may hold unused entries. (A
     * cell's before is set once, so before palettes never do.)
     */
    private final LongOpenHashSet overwritten = new LongOpenHashSet();
    /** Sections {@link #prepare prepared} and not recorded into since: {before, after}, or {@link #NOTHING_KEPT}. */
    private final Long2ObjectOpenHashMap<SectionBuffer[]> prepared = new Long2ObjectOpenHashMap<>();

    public RecordBuilder() {}

    /**
     * Records one cell change; keeps the first {@code before} and the last {@code after} per cell, each with
     * its tile ({@code null} for none).
     *
     * @throws IllegalArgumentException if a state handle is negative
     */
    public void record(int x, int y, int z, int before, BlockEntityData bt, int after, BlockEntityData at) {
        if (before < 0 || after < 0) throw new IllegalArgumentException("Negative state handle");
        long key = BlockBuffer.keyOfBlock(x, y, z);
        if (lastBefore == null || key != lastKey) {
            lastKey = key;
            lastBefore = this.before.sectionOrCreate(key);
            lastAfter = this.after.sectionOrCreate(key);
            lastOverwritten = overwritten.contains(key);
            if (!prepared.isEmpty()) prepared.remove(key);
            dirty.add(key);
        }
        int i = SectionBuffer.index(x & 15, y & 15, z & 15);
        if (!lastBefore.has(i)) {
            lastBefore.set(i, before);
            lastBefore.setTile(i, bt);
        } else if (!lastOverwritten && lastAfter.get(i) != after) {
            overwritten.add(key);
            lastOverwritten = true;
        }
        lastAfter.set(i, after);
        lastAfter.setTile(i, at);
    }

    /**
     * Records one entity change: keeps the first {@code before} and the last {@code after} of entity {@code id}
     * ({@code null} for absent). An entity placed and removed again by the same edit changes nothing and is not kept.
     */
    public void recordEntity(UUID id, EntityState before, EntityState after) {
        java.util.Objects.requireNonNull(id);
        EntityState[] pair = entities.get(id);
        if (pair == null) {
            entities.put(id, new EntityState[] {before, after});
            if (before != null) entityBytes += before.estimatedBytes();
        } else {
            if (pair[1] != null) entityBytes -= pair[1].estimatedBytes();
            pair[1] = after;
        }
        if (after != null) entityBytes += after.estimatedBytes();
        dirtyEntities.add(id);
    }

    /** Entities recorded so far (unchanged ones included). */
    public int entityCount() {
        return entities.size();
    }

    /** The entity changes {@link #build()} would keep now, in first-recorded order (sections are not touched). */
    public List<EntityChange> entityChanges() {
        List<EntityChange> changes = new ArrayList<>(entities.size());
        for (Map.Entry<UUID, EntityState[]> entity : entities.entrySet()) {
            EntityChange change = new EntityChange(entity.getKey(), entity.getValue()[0], entity.getValue()[1]);
            if (!change.unchanged()) changes.add(change);
        }
        return changes;
    }

    /** Whether an entity was recorded since the entities were last drained. */
    public boolean hasDirtyEntities() {
        return !dirtyEntities.isEmpty();
    }

    /**
     * The entities recorded since they were last drained, each as the record holds it now (first before, last after;
     * both {@code null} for one placed and removed again), in first-recorded order, and forgets that they were: what
     * the history journal saves of an unfinished record. A later batch's change of an entity replaces an earlier one's.
     */
    public List<EntityChange> drainEntities() {
        List<EntityChange> out = new ArrayList<>(dirtyEntities.size());
        for (UUID entity : dirtyEntities) {
            EntityState[] pair = entities.get(entity);
            out.add(new EntityChange(entity, pair[0], pair[1]));
        }
        dirtyEntities.clear();
        return out;
    }

    /**
     * Records every present cell of {@code written} (one section, as produced by {@code EditProgram.compute}
     * and actually applied) against the same cells of {@code before}, the section's content before the write.
     */
    public void recordSection(long key, SectionBuffer before, SectionBuffer written) {
        int ox = BlockBuffer.keyX(key) << 4, oy = BlockBuffer.keyY(key) << 4, oz = BlockBuffer.keyZ(key) << 4;
        written.forEachPresent(i -> record(ox + SectionBuffer.localX(i), oy + SectionBuffer.localY(i),
                oz + SectionBuffer.localZ(i), before.get(i), before.tile(i), written.get(i), written.tile(i)));
    }

    /**
     * Builds section {@code key}'s part of the record now, so {@link #build()} has less to do (a bulk job calls this
     * as it finishes each section, within its tick budget). Recording into the section again later discards this.
     * Nothing happens for a section without records.
     */
    public void prepare(long key) {
        SectionBuffer b = before.section(key);
        if (b == null) return;
        SectionBuffer a = after.section(key);
        // A section with no state both before and after is copied whole by build(), which is as cheap as preparing
        // it; preparing would only hold a second copy for the rest of the job.
        if (b.paletteDisjoint(a)) {
            prepared.remove(key);
            return;
        }
        prepared.put(key, buildSection(key, b, a));
        // The next record() into this section goes through the section switch, which drops the prepared part.
        if (key == lastKey) lastBefore = null;
    }

    /**
     * How many sections {@link #build()} would still compare cell by cell: recorded, not {@link #prepare prepared} since,
     * and holding a state both before and after. The others are copied whole, next to no work. One look at each
     * section's palettes.
     */
    public int unpreparedSections() {
        int n = 0;
        for (it.unimi.dsi.fastutil.longs.LongIterator keys = before.keys().iterator(); keys.hasNext(); ) {
            long key = keys.nextLong();
            if (prepared.containsKey(key)) continue;
            SectionBuffer b = before.section(key);
            if (!b.isEmpty() && !b.paletteDisjoint(after.section(key))) n++;
        }
        return n;
    }

    /** Approximate heap held so far (grows with coalesced strokes; see {@link #build()} for the final size). */
    public long estimatedBytes() {
        return before.estimatedBytes() + after.estimatedBytes() + entityBytes + 48L * entities.size();
    }

    /** The id of the history entry this record becomes (fresh per builder). */
    public UUID id() {
        return id;
    }

    /** Whether any section was recorded into since it was last drained. */
    public boolean hasDirty() {
        return !dirty.isEmpty();
    }

    /**
     * The sections recorded into since they were last drained or taken, in ascending key order, without forgetting them
     * (take each with {@link #takeDirty} as it is saved).
     */
    public long[] dirtyKeys() {
        long[] keys = dirty.toLongArray();
        java.util.Arrays.sort(keys);
        return keys;
    }

    /** Whether a section of chunk column (cx, cz) was recorded into since it was last drained. */
    public boolean dirtyIn(int cx, int cz) {
        for (long key : dirty) {
            if (BlockBuffer.keyX(key) == cx && BlockBuffer.keyZ(key) == cz) return true;
        }
        return false;
    }

    /**
     * The sections recorded into since they were last drained, in ascending key order, and forgets them: recording
     * into one of them again makes it dirty again.
     */
    public long[] drainDirty() {
        long[] keys = dirty.toLongArray();
        dirty.clear();
        java.util.Arrays.sort(keys);
        lastBefore = null; // the next record goes through the section switch, which marks its section dirty again
        return keys;
    }

    /** {@link #drainDirty()} for the sections of chunk column (cx, cz) only. */
    public long[] drainDirty(int cx, int cz) {
        LongOpenHashSet taken = new LongOpenHashSet();
        for (long key : dirty) {
            if (BlockBuffer.keyX(key) == cx && BlockBuffer.keyZ(key) == cz) taken.add(key);
        }
        if (taken.isEmpty()) return new long[0];
        dirty.removeAll(taken);
        long[] keys = taken.toLongArray();
        java.util.Arrays.sort(keys);
        lastBefore = null;
        return keys;
    }

    /**
     * Forgets that section {@code key} changed (its current part is being saved now).
     *
     * @return whether it had changed since it was last drained
     */
    public boolean takeDirty(long key) {
        if (!dirty.remove(key)) return false;
        if (key == lastKey) lastBefore = null;
        return true;
    }

    /** Every section recorded into so far, in ascending key order. */
    public long[] sectionKeys() {
        return before.sortedKeys();
    }

    /**
     * Marks every section and entity recorded so far dirty again (the journal lost what it had saved of this record).
     */
    public void markAllDirty() {
        dirty.addAll(before.keys());
        dirtyEntities.addAll(entities.keySet());
    }

    /**
     * Section {@code key}'s part of the record as {@link #build()} would make it now: {@code {before, after}} holding the
     * kept cells, or {@code null} when the section keeps nothing (or was never recorded into). The buffers are not
     * changed afterwards (a prepared part is shared with the record {@code build()} makes), so they may be handed to
     * another thread.
     */
    public SectionBuffer[] snapshotSection(long key) {
        SectionBuffer[] kept = prepared.get(key);
        if (kept == null) {
            SectionBuffer b = before.section(key);
            if (b == null) return null;
            kept = buildSection(key, b, after.section(key));
        }
        return kept == NOTHING_KEPT ? null : kept;
    }

    /**
     * Builds the record, dropping cells where before == after with the same tile content (both absent, or
     * {@link BlockEntityData#sameContent}), and entities left as they were found. The result holds compacted copies;
     * this builder is unchanged and may keep recording (sections {@link #prepare prepared} are handed to the result and
     * are built afresh next time).
     */
    public EditRecord build() {
        BlockBuffer outBefore = new BlockBuffer();
        BlockBuffer outAfter = new BlockBuffer();
        for (long key : before.sortedKeys()) {
            SectionBuffer[] kept = prepared.remove(key);
            if (kept == null) kept = buildSection(key, before.section(key), after.section(key));
            if (kept == NOTHING_KEPT) continue;
            outBefore.putSection(key, kept[0]);
            outAfter.putSection(key, kept[1]);
        }
        return new EditRecord(outBefore, outAfter, entityChanges());
    }

    /** One section's kept cells as compact {before, after} copies, or {@link #NOTHING_KEPT}. */
    private SectionBuffer[] buildSection(long key, SectionBuffer b, SectionBuffer a) {
        if (b.isEmpty()) return NOTHING_KEPT;
        if (b.paletteDisjoint(a)) {
            // No cell can hold the same state before and after, so every recorded cell is kept (the same cells are
            // present in both). Before's palette holds only used entries, so a copy is already compact; after's too
            // unless one of its cells was overwritten with another state.
            return new SectionBuffer[] {b.copy(), overwritten.contains(key) ? a.compact() : a.copy()};
        }
        SectionBuffer keptBefore = new SectionBuffer();
        SectionBuffer keptAfter = new SectionBuffer();
        b.forEachPresent(i -> {
            int stateBefore = b.get(i), stateAfter = a.get(i);
            BlockEntityData tileBefore = b.tile(i), tileAfter = a.tile(i);
            if (stateBefore == stateAfter && sameTile(tileBefore, tileAfter)) return;
            keptBefore.set(i, stateBefore);
            keptBefore.setTile(i, tileBefore);
            keptAfter.set(i, stateAfter);
            keptAfter.setTile(i, tileAfter);
        });
        if (keptBefore.isEmpty()) return NOTHING_KEPT;
        // Filled from empty in ascending index order: the palettes hold only used entries, in first-use order, at the
        // smallest width, as compact() would make them.
        return new SectionBuffer[] {keptBefore, keptAfter};
    }

    static boolean sameTile(BlockEntityData a, BlockEntityData b) {
        if (a == null || b == null) return a == b;
        return a == b || a.sameContent(b);
    }
}
