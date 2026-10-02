package dev.sculptory.server.engine.impl;

import dev.sculptory.core.BlockPos;
import dev.sculptory.server.platform.EntityPlacer;
import dev.sculptory.server.platform.WorldEntities;
import dev.sculptory.server.platform.WriteOptions;
import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A job's entity work, done by {@link BulkJob} chunk column by chunk column, each
 * once its chunk is loaded with its entities: {@link #before} the job's first section (taking and removing entities, so
 * a hanging entity is gone before its wall is), {@link #after} its last (placing entities, so a hanging entity finds its
 * wall). A column's work comes in steps of at most {@link #ENTITIES_PER_STEP} entities ({@link Context#take}); the job
 * checks its tick budget between steps, so no tick spawns or removes an unbounded number of entities. Columns are packed
 * like {@code ChunkPos.toLong} ({@link ColumnPlan#pack}). Server thread only.
 *
 * <p>The work runs for any platform's entity type {@code E}: it reaches entities only through the job's
 * {@link Context}, which the job makes from the platform's {@link WorldEntities}.
 */
public interface EntityWork {
    /** Entities (each with its passengers, at most {@code EntityNbt.MAX_RIDERS}) one step handles at most. */
    int ENTITIES_PER_STEP = 256;

    /**
     * Every column the work may visit, before or after the blocks (a superset, known at admission): the executor counts
     * them toward the job's column cap and applies the unloaded-chunks policy to them as to the blocks' columns.
     */
    long[] admissionColumns();

    /** The columns visited before the blocks, in order (asked once, when the job starts). */
    long[] beforeColumns();

    /**
     * Works on one column before the blocks while {@link Context#take} allows; called again with the same column until
     * it returns true (the column is done).
     */
    <E> boolean before(long column, Context<E> ctx);

    /** The columns visited after the blocks, in order (asked once the work before the blocks is done). */
    long[] afterColumns();

    /** {@link #before}'s counterpart after the blocks. */
    <E> boolean after(long column, Context<E> ctx);

    /**
     * Walks a column's items a step at a time: fetched when the column starts, then handed out while the step allows. A
     * cursor serves one kind of item (one call site), so the items it holds are always of the type that site fetches.
     */
    final class Cursor {
        private long column;
        private List<?> items;
        private int next;

        /** Hands out {@code column}'s items (from {@code fetch}, on the column's first call); true when all are done. */
        <T> boolean walk(long column, Context<?> ctx, Supplier<List<T>> fetch, Consumer<T> each) {
            if (items == null || this.column != column) {
                this.column = column;
                items = fetch.get();
                next = 0;
            }
            @SuppressWarnings("unchecked")
            List<T> list = (List<T>) items;
            while (next < list.size()) {
                if (!ctx.take()) return false;
                each.accept(list.get(next++));
            }
            items = null;
            return true;
        }
    }

    /** Whether a cell is inside the world: its border and build limit. */
    @FunctionalInterface
    interface InWorld {
        boolean contains(int x, int y, int z);
    }

    /**
     * What entity work may use and what it counts, for one job.
     *
     * @param <E> the platform's entity type
     */
    final class Context<E> {
        /** The world's entities. */
        final WorldEntities<E> world;
        final EntityPlacer<E> writer;
        final RecordSink records;
        private final PermitSource permits;
        private final InWorld inWorld;
        private final Long2BooleanOpenHashMap seen = new Long2BooleanOpenHashMap();
        /** Entities left alone because their block may not be changed (protection, the world border). */
        long protectedEntities;
        /** Entities kept because they changed since the step (undo and redo). */
        long conflicts;
        /** Entities the current step may still handle. */
        int allowance;

        Context(WorldEntities<E> world, EntityPlacer<E> writer, PermitSource permits, RecordSink records,
                InWorld inWorld) {
            this.world = Objects.requireNonNull(world);
            this.writer = Objects.requireNonNull(writer);
            this.permits = Objects.requireNonNull(permits);
            this.records = Objects.requireNonNull(records);
            this.inWorld = Objects.requireNonNull(inWorld);
        }

        /** A job's context in {@code world}, placing with {@code options}. */
        static <E> Context<E> of(WorldEntities<E> world, WriteOptions options, PermitSource permits, RecordSink records,
                                 InWorld inWorld) {
            return new Context<>(world, world.placer(options), permits, records, inWorld);
        }

        /** Takes one entity from the step's allowance; false when the step is used up. */
        boolean take() {
            if (allowance <= 0) return false;
            allowance--;
            return true;
        }

        /**
         * Whether the job may change an entity belonging to block {@code cell}: the player's right to change that
         * column ({@link PermitSource#mayChangeColumn}, asked for the column itself, since an entity found by its UUID
         * may have wandered outside the chunks the job was admitted for), the world border and the build limit.
         */
        boolean mayChange(BlockPos cell) {
            int x = cell.x(), z = cell.z();
            long column = ColumnPlan.pack(x, z);
            boolean allowed;
            if (seen.containsKey(column)) {
                allowed = seen.get(column);
            } else {
                allowed = permits.mayChangeColumn(x, z);
                seen.put(column, allowed);
            }
            return allowed && inWorld.contains(x, cell.y(), z);
        }

        /**
         * Whether the job may place or put back {@code entity} as it would be spawned: the block it belongs to (for a
         * hanging entity the one it ended up on) and the block holding its position may both be changed.
         */
        boolean mayPlace(E entity) {
            return mayChange(world.cell(entity)) && mayChange(world.blockPos(entity));
        }

        /** Entities placed, put back or removed so far. */
        long changed() {
            return writer.placed() + writer.removed();
        }
    }
}
