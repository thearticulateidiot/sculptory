package dev.sculptory.core.history;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.edit.ComputeContext;
import dev.sculptory.core.edit.EditProgram;
import dev.sculptory.core.edit.SectionOrder;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Undo and redo as ordinary edit programs over the entry's recorded sections.
 *
 * <p>Per recorded cell, with {@code expected} the state and tile the edit (or undo) left there ({@code after} for an
 * undo, {@code before} for a redo) and {@code target} the state and tile being restored:
 * <ul>
 *   <li>a cell already holding the target state with the same contents is left alone;</li>
 *   <li>a cell still holding the expected state with the expected contents is written: the target state and its
 *       tile;</li>
 *   <li>any other cell is a conflict: another state, or the expected state holding other contents (a chest filled
 *       since the edit, a sign edited, a chest of the same state placed since), so nothing made after the step is
 *       destroyed.</li>
 * </ul>
 * Contents are compared by a {@link TileMatcher} (on the server, in the game's canonical form; see there). Under
 * {@link ConflictPolicy#SKIP_CONFLICTS} conflicting cells are skipped and reported through
 * {@link ComputeContext#conflicts}; under {@link ConflictPolicy#OVERWRITE} they are written. The test is made in
 * {@code compute} against the captured section and again, through {@link EditProgram#mayReplace}, against each cell's
 * live state and block entity right before it is written.
 */
public final class HistoryPrograms {
    private HistoryPrograms() {}

    /** {@link #undo(HistoryEntry, ConflictPolicy, TileMatcher)} comparing contents byte for byte. */
    public static EditProgram undo(HistoryEntry e, ConflictPolicy p) {
        return undo(e, p, TileMatcher.EXACT);
    }

    /**
     * Writes {@code before} where the cell still holds {@code after} (or everywhere with OVERWRITE). The entry's
     * entities are not the program's business: the runner decides them with {@link EntityHistory}. An entry that
     * records only entities gives a program with no sections whose bounds hold those entities.
     */
    public static EditProgram undo(HistoryEntry e, ConflictPolicy p, TileMatcher tiles) {
        Objects.requireNonNull(e);
        return new Program("Undo " + e.label(), e.record().after(), e.record().before(), Objects.requireNonNull(p),
                Objects.requireNonNull(tiles), e.record().bounds());
    }

    /** {@link #redo(HistoryEntry, ConflictPolicy, TileMatcher)} comparing contents byte for byte. */
    public static EditProgram redo(HistoryEntry e, ConflictPolicy p) {
        return redo(e, p, TileMatcher.EXACT);
    }

    /** Writes {@code after} where the cell still holds {@code before} (or everywhere with OVERWRITE); see {@link #undo}. */
    public static EditProgram redo(HistoryEntry e, ConflictPolicy p, TileMatcher tiles) {
        Objects.requireNonNull(e);
        return new Program("Redo " + e.label(), e.record().before(), e.record().after(), Objects.requireNonNull(p),
                Objects.requireNonNull(tiles), e.record().bounds());
    }

    /**
     * Undo anyway (or Redo anyway): re-applies, with {@link ConflictPolicy#OVERWRITE}, a run of undo (or redo) steps
     * that were already made. {@code run} lists the entries in the order their steps were applied. Every recorded cell
     * gets the target of the last entry of the run that recorded it (its {@code before} for an undo run, its
     * {@code after} for a redo run), so the world ends exactly as applying each step of the run with OVERWRITE, one
     * after the other, would have left it: the last writer wins. A cell already holding its target (state and tile)
     * is left alone; every other recorded cell is written, whatever it holds. Nothing is reported as a conflict.
     *
     * @throws IllegalArgumentException if the run is empty or an entry records no cells
     */
    public static EditProgram reapply(List<HistoryEntry> run, boolean redo) {
        Objects.requireNonNull(run);
        if (run.isEmpty()) throw new IllegalArgumentException("Empty history run");
        BlockBuffer[] targets = new BlockBuffer[run.size()];
        Box bounds = null;
        for (int i = 0; i < targets.length; i++) {
            EditRecord record = Objects.requireNonNull(run.get(i)).record();
            targets[i] = redo ? record.after() : record.before();
            Box box = record.bounds();
            if (box == null) throw new IllegalArgumentException("History entry records nothing");
            bounds = bounds == null ? box : Reapply.union(bounds, box);
        }
        String steps = run.size() == 1 ? "1 step" : run.size() + " steps";
        return new Reapply((redo ? "Redo anyway (" : "Undo anyway (") + steps + ")", targets, bounds);
    }

    private static final class Program implements EditProgram {
        private final String label;
        private final BlockBuffer expected;
        private final BlockBuffer target;
        private final ConflictPolicy policy;
        private final TileMatcher tiles;
        private final Box bounds;
        private final long cells;
        private final long[] order;

        Program(String label, BlockBuffer expected, BlockBuffer target, ConflictPolicy policy, TileMatcher tiles,
                Box bounds) {
            this.label = label;
            this.expected = expected;
            this.target = target;
            this.policy = policy;
            this.tiles = tiles;
            this.bounds = bounds;
            if (bounds == null) throw new IllegalArgumentException("History entry records nothing");
            this.cells = target.cellCount();
            this.order = target.sortedKeys();
            SectionOrder.sort(order);
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public Box bounds() {
            return bounds;
        }

        @Override
        public long estimatedCells() {
            return cells;
        }

        @Override
        public long[] sourceSections() {
            return new long[0];
        }

        @Override
        public long[] sectionOrder() {
            return order.clone();
        }

        @Override
        public void compute(long key, SectionBuffer before, SectionBuffer out, ComputeContext ctx) {
            SectionBuffer restore = target.section(key);
            SectionBuffer expect = expected.section(key);
            // A runner writes a section's cells before computing the next, so the last one's checks are over.
            tiles.section(key);
            if (restore == null) return;
            int[] conflicts = {0};
            restore.forEachPresent(i -> {
                int current = before.get(i);
                BlockEntityData currentTile = before.tile(i);
                int wanted = restore.get(i);
                BlockEntityData wantedTile = restore.tile(i);
                // Already restored: nothing to write, and nothing was lost.
                if (current == wanted && tiles.matches(current, currentTile, wantedTile)) return;
                // Otherwise only a cell still holding what the edit left, state and contents, may be written. A cell
                // whose state happens to equal the target but holds other contents (a new chest placed since) and a
                // cell whose contents changed (a chest filled since) are conflicts too.
                if (policy == ConflictPolicy.SKIP_CONFLICTS && !asLeft(expect, i, current, currentTile)) {
                    conflicts[0]++;
                    return;
                }
                out.set(i, wanted);
                out.setTile(i, wantedTile);
            });
            if (conflicts[0] > 0) ctx.conflicts(conflicts[0]);
        }

        /** Whether cell {@code i} holds the state and contents the step being undone or redone left there. */
        private boolean asLeft(SectionBuffer expect, int i, int state, BlockEntityData tile) {
            return expect != null && expect.has(i) && state == expect.get(i) && tiles.matches(state, tile, expect.tile(i));
        }

        /**
         * The state-only part of the write-time test, for a runner that cannot read the live block entity (every
         * runner here uses {@link #mayReplace(long, int, int, BlockEntityData, ComputeContext)}).
         */
        @Override
        public boolean mayReplace(long key, int index, int liveState, ComputeContext ctx) {
            if (policy != ConflictPolicy.SKIP_CONFLICTS) return true;
            SectionBuffer expect = expected.section(key);
            if (expect != null && expect.has(index) && liveState == expect.get(index)) return true;
            ctx.conflicts(1);
            return false;
        }

        /**
         * The same conflict test at write time, against the cell's live state and block entity: a section can be
         * written over several ticks after {@code compute}, and a cell changed in between (a block placed or a chest
         * filled while the undo runs) must not be overwritten under {@link ConflictPolicy#SKIP_CONFLICTS}. It is
         * refused and reported as a conflict.
         */
        @Override
        public boolean mayReplace(long key, int index, int liveState, BlockEntityData liveTile, ComputeContext ctx) {
            if (policy != ConflictPolicy.SKIP_CONFLICTS) return true;
            if (asLeft(expected.section(key), index, liveState, liveTile)) return true;
            ctx.conflicts(1);
            return false;
        }
    }

    /** {@link #reapply}: the targets of a run's entries, in the order their steps were applied. */
    private static final class Reapply implements EditProgram {
        private final String label;
        private final BlockBuffer[] targets;
        private final Box bounds;
        private final long cells;
        private final long[] order;

        /** {@code bounds} holds every entry's cells and entities. */
        Reapply(String label, BlockBuffer[] targets, Box bounds) {
            this.label = label;
            this.targets = targets;
            LongOpenHashSet keys = new LongOpenHashSet();
            for (BlockBuffer target : targets) keys.addAll(target.keys());
            this.bounds = bounds;
            this.order = keys.toLongArray();
            SectionOrder.sort(order);
            long total = 0;
            for (long key : order) total += cellsIn(key);
            this.cells = total;
        }

        static Box union(Box a, Box b) {
            return new Box(new BlockPos(Math.min(a.min().x(), b.min().x()), Math.min(a.min().y(), b.min().y()),
                    Math.min(a.min().z(), b.min().z())), new BlockPos(Math.max(a.max().x(), b.max().x()),
                    Math.max(a.max().y(), b.max().y()), Math.max(a.max().z(), b.max().z())));
        }

        /** The distinct cells the run records in section {@code key}. */
        private long cellsIn(long key) {
            SectionBuffer only = null;
            int holders = 0;
            for (BlockBuffer target : targets) {
                SectionBuffer section = target.section(key);
                if (section == null || section.isEmpty()) continue;
                if (section.isDense()) return SectionBuffer.SIZE;
                only = section;
                holders++;
            }
            if (holders <= 1) return only == null ? 0 : only.presentCount();
            long[] mask = new long[SectionBuffer.SIZE / 64];
            for (BlockBuffer target : targets) {
                SectionBuffer section = target.section(key);
                if (section != null) section.forEachPresent(i -> mask[i >>> 6] |= 1L << i);
            }
            long count = 0;
            for (long word : mask) count += Long.bitCount(word);
            return count;
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public Box bounds() {
            return bounds;
        }

        @Override
        public long estimatedCells() {
            return cells;
        }

        @Override
        public long[] sourceSections() {
            return new long[0];
        }

        @Override
        public long[] sectionOrder() {
            return order.clone();
        }

        /** Each cell gets the target of the last entry recording it: the entries are read newest first, first one wins. */
        @Override
        public void compute(long key, SectionBuffer before, SectionBuffer out, ComputeContext ctx) {
            long[] decided = new long[SectionBuffer.SIZE / 64];
            for (int t = targets.length - 1; t >= 0; t--) {
                SectionBuffer restore = targets[t].section(key);
                if (restore == null) continue;
                restore.forEachPresent(i -> {
                    long bit = 1L << i;
                    if ((decided[i >>> 6] & bit) != 0) return;
                    decided[i >>> 6] |= bit;
                    int wanted = restore.get(i);
                    BlockEntityData wantedTile = restore.tile(i);
                    if (before.get(i) == wanted && RecordBuilder.sameTile(before.tile(i), wantedTile)) return;
                    out.set(i, wanted);
                    out.setTile(i, wantedTile);
                });
            }
        }
    }
}
