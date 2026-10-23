package dev.sculptory.server.engine.impl;

import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.history.EntityState;
import dev.sculptory.core.history.RecordBuilder;
import java.util.UUID;

/**
 * Receives every cell a job or brush actually changed, in write order, on the server thread. The signature matches
 * {@code core.history.RecordBuilder#record}, so the history integration can pass {@code builder::record}. Entity
 * changes arrive through {@link #entity}, biome changes through {@link #biome}.
 *
 * @see dev.sculptory.core.history.RecordBuilder
 */
@FunctionalInterface
public interface RecordSink {
    RecordSink NONE = (x, y, z, before, beforeTile, after, afterTile) -> {};

    /**
     * @param beforeTile the captured block entity before the write, or {@code null}
     * @param afterTile the tile actually applied ({@code null} for none, stripped, or failed to load)
     */
    void record(int x, int y, int z, int before, BlockEntityData beforeTile, int after, BlockEntityData afterTile);

    /**
     * A bulk job finished writing section {@code key} and moves on. A hint for work that can be done per section
     * (recording into the section again later must still be handled).
     */
    default void sectionFinished(long key) {}

    /**
     * One entity the job changed: as it was before ({@code null}: not there) and after ({@code null}: removed), both
     * read from the world. The default records nothing.
     */
    default void entity(UUID id, EntityState before, EntityState after) {}

    /**
     * An item frame or painting the edit's writes left with nothing to hang on, which the edit removes right after this
     * call ({@link HangingSupport}); {@code state} is it as it is. A forward edit records it as removed (the default), so
     * its undo puts it back once the wall is back. An undo or redo, which records nothing of its own, amends the entry it
     * applies instead.
     *
     * @return false when the sink cannot keep it in any history: then it is not removed (the game drops it off, as
     *     before)
     */
    default boolean entityUnsupported(UUID id, EntityState state) {
        entity(id, state, null);
        return true;
    }

    /** A bulk job finished a chunk column of entity work: a hint to save the entities recorded so far. */
    default void entitiesFinished() {}

    /**
     * One biome quart the job changed (protocol 6): quart {@code (qx, qy, qz)} went from biome {@code before} to
     * {@code after}, both read from the world. The default records nothing.
     */
    default void biome(int qx, int qy, int qz, String before, String after) {}

    /** A bulk job finished a chunk column of biome work: a hint to save the biomes recorded so far. */
    default void biomesFinished() {}

    /**
     * Records into {@code builder} and {@linkplain RecordBuilder#prepare prepares} each finished section's part of the
     * record, so building it when the job ends does not stall that tick.
     */
    static RecordSink into(RecordBuilder builder) {
        return new RecordSink() {
            @Override
            public void record(int x, int y, int z, int before, BlockEntityData beforeTile, int after,
                               BlockEntityData afterTile) {
                builder.record(x, y, z, before, beforeTile, after, afterTile);
            }

            @Override
            public void sectionFinished(long key) {
                builder.prepare(key);
            }

            @Override
            public void entity(UUID id, EntityState before, EntityState after) {
                builder.recordEntity(id, before, after);
            }

            @Override
            public void biome(int qx, int qy, int qz, String before, String after) {
                builder.recordBiome(qx, qy, qz, before, after);
            }
        };
    }
}
