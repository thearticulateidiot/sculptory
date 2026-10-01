package dev.sculptory.core.edit;

import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.buffer.SectionBuffer;

/** A compiled edit, applied section by section by the executor. Keys are {@code BlockBuffer.key}s. */
public interface EditProgram {
    /** No chunk columns ({@link #readColumns}). */
    long[] NO_COLUMNS = new long[0];

    String label();

    Box bounds();

    long estimatedCells();

    /** Sections snapshotted before any write (move/stack); read back through {@link ComputeContext#source}. */
    long[] sourceSections();

    /** Every section the program writes, in deterministic write order. */
    long[] sectionOrder();

    /**
     * Computes the writes for one section. {@code before} is the section's current content (dense);
     * {@code out} starts empty and receives only the cells to write, with their tiles.
     */
    void compute(long key, SectionBuffer before, SectionBuffer out, ComputeContext ctx);

    /**
     * The chunk columns, besides the section's own, that {@code compute(key, ...)} reads through
     * {@link ComputeContext#world()}; a runner that can load chunks has them loaded before it computes {@code key}.
     * Packed with {@link #column}. Asked right before {@code compute(key, ...)}, possibly more than once. The default
     * reads none.
     */
    default long[] readColumns(long key) {
        return NO_COLUMNS;
    }

    /**
     * Whether the runner may write the value {@code compute} gave cell {@code index} of section {@code key} over the
     * cell's live state {@code liveState}, read right before the write. A section can be written over several ticks
     * after it was computed, so the cell may have changed since {@code before}. A refused cell is neither written nor
     * recorded; the program reports what it counts through {@code ctx}. Asked only for cells of the section computed
     * last. The default allows every write.
     */
    default boolean mayReplace(long key, int index, int liveState, ComputeContext ctx) {
        return true;
    }

    /**
     * {@link #mayReplace(long, int, int, ComputeContext)} with the cell's live block entity too ({@code null} for none),
     * read right before the write like its state, for a program whose decision depends on it (undo and redo keep a
     * cell whose contents changed). Runners call this form. The default asks the state-only form.
     */
    default boolean mayReplace(long key, int index, int liveState, BlockEntityData liveTile, ComputeContext ctx) {
        return mayReplace(key, index, liveState, ctx);
    }

    /**
     * Whether the runner recomputes the light of the sections this program wrote once it is done (Update blocks'
     * "fix lighting"). Relighting is not recorded in history. The
     * default does not.
     */
    default boolean relightsAfter() {
        return false;
    }

    /** Packs chunk column (cx, cz) like {@code ChunkPos.toLong}: x in the low 32 bits, z in the high 32. */
    static long column(int cx, int cz) {
        return (cx & 0xFFFFFFFFL) | ((cz & 0xFFFFFFFFL) << 32);
    }

    static int columnX(long column) {
        return (int) column;
    }

    static int columnZ(long column) {
        return (int) (column >>> 32);
    }
}
