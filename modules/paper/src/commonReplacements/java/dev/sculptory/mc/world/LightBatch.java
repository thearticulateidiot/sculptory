package dev.sculptory.mc.world;

import net.minecraft.server.level.ServerLevel;

/**
 * Paper's stand-in for modules/common's {@code LightBatch}, with the members the common code calls. Paper 1.21.1 runs
 * Moonrise's (Starlight's) light engine, not vanilla's {@code ThreadedLevelLightEngine} providers: the common class
 * reads the widened {@code blockEngine}/{@code skyEngine} fields, which Paper's {@code LevelLightEngine} does not have,
 * and it already turns batching off when Moonrise is loaded. So on Paper there is never a batch: {@link #of} is
 * {@code null} and every light check a write asks goes to Paper's light engine as it is made (Moonrise queues them per
 * chunk itself rather than as a task per cell).
 */
public final class LightBatch {
    private static final Scope NONE = new Scope();

    private LightBatch() {
    }

    /** Always {@code null}: no batching on Paper. */
    public static LightBatch of(ServerLevel world) {
        return null;
    }

    /** Nothing is ever batched. */
    public static void flushAll() {
    }

    public Scope collect() {
        return NONE;
    }

    public void flush() {
    }

    public long flushedTasks() {
        return -1;
    }

    /** Ends {@link #collect}; nothing to end on Paper. */
    public static final class Scope implements AutoCloseable {
        private Scope() {
        }

        @Override
        public void close() {
        }
    }
}
