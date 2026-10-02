package dev.sculptory.server.platform;

import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.server.engine.impl.RecordSink;

/**
 * The platform's single world write path for one job or stroke ({@link Platform#writer}), server thread only. It
 * writes with the options it was made with, strips operator-only NBT the options do not allow, and keeps per-job
 * counters; it is not thread-safe. The target chunk must be loaded.
 */
public interface WorldWriter {
    /**
     * Decides from a cell's live state and block entity (captured, or {@code null} for none), read right before a
     * recorded write, whether the write may replace it.
     */
    @FunctionalInterface
    interface Guard {
        boolean mayReplace(int liveState, BlockEntityData liveTile);
    }

    /**
     * Writes one cell and records it. The cell's current state and block entity are read right before the write
     * (never from an earlier snapshot); when they already equal the target, nothing is written or recorded. The
     * record is made even if the write throws, with the cell's state after the failure as {@code after}.
     *
     * @return true when the cell was written (and recorded)
     */
    boolean write(int x, int y, int z, int handle, BlockEntityData tile, RecordSink sink);

    /**
     * {@link #write(int, int, int, int, BlockEntityData, RecordSink)}, but first asks {@code guard} (if not
     * {@code null}) about the cell's live state and block entity; a refused cell is neither written nor recorded.
     *
     * @return true when the cell was written (and recorded)
     */
    boolean write(int x, int y, int z, int handle, BlockEntityData tile, RecordSink sink, Guard guard);

    /**
     * Removes scheduled block and fluid ticks at exactly the cells written with physics off since the last call.
     * Ticks at cells this writer skipped are kept.
     */
    void clearTicksAtWrittenCells();

    /**
     * Sends physics-off writes to clients through {@code sync} (bulk jobs: whole columns when heavily changed) instead
     * of marking each cell for vanilla's block updates at once; {@code sync} must be one this platform made for the
     * writer's world ({@link Platform#clientUpdates}), and the caller must flush it. Returns this writer.
     */
    WorldWriter syncThrough(ClientUpdates sync);

    /**
     * Tells {@code sync} (one this platform made for the writer's world) about the cells written through vanilla block
     * updates, for its light follow-ups. Returns this writer.
     */
    WorldWriter watchVanillaWrites(ClientUpdates sync);

    /** Cells written and recorded by the recorded {@code write}, including one whose write threw. */
    long changed();

    /** Tiles removed because their state needs operator rights. */
    long strippedNbt();

    /** Tiles that could not be loaded (the block kept its default block entity). */
    long tileFailures();

    /** A description of the first tile failure, or {@code null}. */
    String firstTileFailure();
}
