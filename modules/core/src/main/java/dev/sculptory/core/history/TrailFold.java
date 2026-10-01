package dev.sculptory.core.history;

import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.SectionBuffer;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Objects;

/**
 * Folds an entry's <em>trail</em> into its record: the cells the fluid the
 * entry's last step wrote changed since, by flowing (and what it did on the way: a grass block under it that died, lava
 * it turned to obsidian). The trail holds, per cell, the state before the fluid first changed it and the state it left
 * there last.
 *
 * <p>The trail describes a change from the side of the entry the world holds, so the direction follows the entry's place
 * in the history, and the folded record describes the entry's next step exactly:
 * <ul>
 *   <li><b>Toward before</b> (the entry is done: its next step is an undo; Redo anyway of it): a trail cell the record
 *       holds keeps its {@code before} and takes the trail's last state as {@code after}; any other trail cell gets the
 *       trail's first state as {@code before} and its last as {@code after}. Undo then writes {@code before} where the
 *       cell still holds what the fluid left.</li>
 *   <li><b>Toward after</b> (the entry is undone, for fluid an undo put back: its next step is a redo; Undo anyway of
 *       it): a trail cell takes the trail's last state as {@code before} and keeps the record's {@code after}, or, when
 *       the record does not hold it, gets the trail's first state as {@code after}.</li>
 * </ul>
 * A cell can end up with the same state and contents before and after (a flowing cell the edit wrote that drained away
 * again): it is kept, so the step finds it already at its target instead of reporting it changed. Sections the trail
 * does not touch are shared with the record, not copied (records are never written to once built): a caller can tell
 * the changed sections by identity. Folding a trail in pieces (a chunk column at a time as the game saves them, or what
 * the fluid did before a fold and after it) gives the same record as folding it whole in the same direction, since
 * each cell keeps the first state it had and takes the last; only a cell the fluid changed before a fold and put back
 * after it stays in the record, with the same state on both sides (the whole trail would have dropped it), which a step
 * finds already done either way.
 */
public final class TrailFold {
    private TrailFold() {}

    /**
     * The record with the trail folded in, or {@code record} itself when the trail is {@code null} or empty.
     */
    public static EditRecord fold(EditRecord record, EditRecord trail, boolean towardBefore) {
        Objects.requireNonNull(record);
        if (trail == null || trail.before().isEmpty()) return record;
        BlockBuffer before = new BlockBuffer();
        BlockBuffer after = new BlockBuffer();
        LongOpenHashSet keys = new LongOpenHashSet(record.before().keys());
        keys.addAll(trail.before().keys());
        for (long key : keys) {
            SectionBuffer rb = record.before().section(key), ra = record.after().section(key);
            SectionBuffer tb = trail.before().section(key), ta = trail.after().section(key);
            if (tb == null || tb.isEmpty()) {
                before.putSection(key, rb);
                after.putSection(key, ra);
                continue;
            }
            SectionBuffer nb = new SectionBuffer(), na = new SectionBuffer();
            if (rb != null) {
                rb.forEachPresent(i -> {
                    nb.set(i, rb.get(i));
                    nb.setTile(i, rb.tile(i));
                    na.set(i, ra.get(i));
                    na.setTile(i, ra.tile(i));
                });
            }
            tb.forEachPresent(i -> {
                boolean held = rb != null && rb.has(i);
                if (towardBefore) {
                    if (!held) {
                        nb.set(i, tb.get(i));
                        nb.setTile(i, tb.tile(i));
                    }
                    na.set(i, ta.get(i));
                    na.setTile(i, ta.tile(i));
                } else {
                    nb.set(i, ta.get(i));
                    nb.setTile(i, ta.tile(i));
                    if (!held) {
                        na.set(i, tb.get(i));
                        na.setTile(i, tb.tile(i));
                    }
                }
            });
            before.putSection(key, nb.compact());
            after.putSection(key, na.compact());
        }
        return new EditRecord(before, after, record.entities());
    }
}
