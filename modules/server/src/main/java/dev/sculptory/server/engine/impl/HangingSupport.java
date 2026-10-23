package dev.sculptory.server.engine.impl;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.history.EntityState;
import dev.sculptory.core.region.Region;
import dev.sculptory.server.platform.WorldEntities;
import dev.sculptory.server.platform.WriteOptions;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An edit that takes away what an item frame or a painting hangs on takes the entity along, into the edit's history.
 * Left alone, the game would drop it off with its item at its next check, and nothing could bring it back.
 *
 * <p>The sweep runs once the edit's blocks are written. It looks at the hanging entities whose cell (the space in front
 * of the wall, {@link WorldEntities#cell}) lies within the written bounds grown by one block, so the wall of a frame
 * hanging just outside the bounds is inside them. Each one that no longer stays ({@link WorldEntities#hangsUnsupported})
 * is removed in the same tick and handed to the edit's sink ({@link RecordSink#entityUnsupported}): a forward edit
 * records it as removed, so its undo brings it back after the wall; an undo or redo amends the entry it applies. One the
 * player may not change at its cell is left to the game, as before, and counted as a conflict. Only chunks already
 * loaded with their entities are looked at: the sweep never loads a chunk.
 */
public final class HangingSupport {
    private static final Logger LOG = LoggerFactory.getLogger("sculptory");

    private HangingSupport() {}

    /** {@code written} grown by one block on every side: the cells whose hanging entities the sweep looks at. */
    public static Box around(Box written) {
        Objects.requireNonNull(written);
        return new Box(new BlockPos(written.min().x() - 1, written.min().y() - 1, written.min().z() - 1),
                new BlockPos(written.max().x() + 1, written.max().y() + 1, written.max().z() + 1));
    }

    /** What a whole sweep did: entities taken along, and entities left to the game because they are protected. */
    public record Result(long removed, long conflicts) {
        public static final Result NONE = new Result(0, 0);
    }

    /**
     * The sweep of one edit, a chunk column at a time and a step at a time ({@link EntityWork.Cursor}), for a job that
     * checks its budget between steps.
     */
    static final class Sweep {
        private final Region region;
        private final long[] columns;
        private final EntityWork.Cursor cursor = new EntityWork.Cursor();

        Sweep(Box written) {
            Box grown = around(written);
            this.region = new Region.Cuboid(grown);
            this.columns = EntityColumns.of(grown);
        }

        /** The chunk columns to look in, in order. */
        long[] columns() {
            return columns;
        }

        /**
         * Looks at one column while {@link EntityWork.Context#take} allows; true when the column is done. A column whose
         * entities are not loaded is done at once.
         */
        <E> boolean column(long column, EntityWork.Context<E> ctx) {
            int cx = ColumnPlan.unpackX(column), cz = ColumnPlan.unpackZ(column);
            if (!ctx.world.loaded(cx, cz)) return true;
            return cursor.walk(column, ctx, () -> ctx.world.inRegion(region, EntityFilter.DECORATIONS, cx, cz),
                    entity -> take(entity, ctx));
        }
    }

    /** How far (blocks) a hanging entity's own blocks and the wall behind them reach from its cell (a 4 × 4 painting). */
    static final int REACH = 3;

    /**
     * The early sweep a job makes as soon as it has written one section: the hanging entities around the section that
     * no longer stay are taken along now, before the game's own check (every 100 ticks per entity) can drop them while a
     * long job is still writing, but only those whose blocks are {@code settled}: every cell within {@link #REACH} of
     * the entity's cell lies where the job writes nothing more. The others wait for the job's last sweep. Only columns
     * loaded with their entities are looked at.
     */
    static <E> void section(Box section, EntityWork.Context<E> ctx, java.util.function.Predicate<BlockPos> settled) {
        Box grown = around(section);
        Region region = new Region.Cuboid(grown);
        for (long column : EntityColumns.of(grown)) {
            int cx = ColumnPlan.unpackX(column), cz = ColumnPlan.unpackZ(column);
            if (!ctx.world.loaded(cx, cz)) continue;
            for (E entity : ctx.world.inRegion(region, EntityFilter.DECORATIONS, cx, cz)) {
                if (ctx.world.hangsUnsupported(entity) && settled.test(ctx.world.cell(entity))) take(entity, ctx);
            }
        }
    }

    /** Takes one entity along if it no longer stays where it hangs. */
    private static <E> void take(E entity, EntityWork.Context<E> ctx) {
        // Fetched when the column started: it may have gone since.
        if (ctx.world.removed(entity) || ctx.world.riding(entity) || !ctx.world.hangsUnsupported(entity)) return;
        if (!ctx.mayChange(ctx.world.cell(entity))) {
            ctx.conflicts++; // the game drops it off, as it did before
            return;
        }
        EntityState before = ctx.world.live(entity);
        if (before == null) {
            LOG.warn("Sculptory: a {} lost what it hangs on but cannot be recorded for undo; it is left to the game",
                    ctx.world.typeId(entity));
            return;
        }
        if (!ctx.records.entityUnsupported(ctx.world.id(entity), before)) {
            ctx.conflicts++; // no history could keep it: left to the game
            return;
        }
        ctx.writer.remove(entity, before);
    }

    /**
     * The whole sweep at once, for edits written in one go outside the job executor (a builder action, a brush step):
     * their bounds are small, so the entities near them are few.
     *
     * @param written the bounds of the cells the edit wrote
     * @param permits the player's right to change each entity's cell
     * @param inWorld the world border and build limit
     */
    public static <E> Result sweep(WorldEntities<E> world, WriteOptions options, PermitSource permits,
                                   EntityWork.InWorld inWorld, RecordSink records, Box written) {
        EntityWork.Context<E> ctx = EntityWork.Context.of(world, options, permits, records, inWorld);
        Sweep sweep = new Sweep(written);
        for (long column : sweep.columns()) {
            boolean done;
            do {
                ctx.allowance = Integer.MAX_VALUE;
                done = sweep.column(column, ctx);
            } while (!done);
        }
        return new Result(ctx.writer.removed(), ctx.conflicts);
    }
}
