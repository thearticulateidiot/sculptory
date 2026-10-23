package dev.sculptory.server.engine.impl;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.history.EntityState;
import dev.sculptory.core.region.Region;
import dev.sculptory.server.platform.WorldEntities;
import dev.sculptory.server.platform.WriteOptions;
import it.unimi.dsi.fastutil.longs.LongCollection;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Arrays;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An edit that takes away what an item frame or a painting hangs on takes the entity along, into the edit's history.
 * Left alone, the game would drop it off with its item at its next check, and nothing could bring it back.
 *
 * <p>The sweep runs once the edit's blocks are written, over the 16³ sections it wrote cells in. It looks at the
 * hanging entities whose cell ({@link WorldEntities#cell}) lies within {@value #SUPPORT} blocks of such a section: an
 * entity's own blocks and the wall behind them lie within that distance of its cell (a 4 × 4 painting's far corner).
 * Each one that no longer stays ({@link WorldEntities#hangsUnsupported}) is removed in the same tick and handed to the
 * edit's sink ({@link RecordSink#entityUnsupported}): a forward edit records it as removed, so its undo brings it back
 * after the wall; an undo or redo amends the entry it applies. One the player may not change at its cell is left to the
 * game, as before, and counted as a conflict. Nothing is loaded: only chunk columns already loaded with their entities
 * are looked at, and an entity whose blocks reach a chunk that is not fully loaded is left to the game
 * ({@link WorldEntities#supportLoaded}). A sink that keeps no entities ({@link RecordSink#recordsEntities}) gets no
 * sweep.
 */
public final class HangingSupport {
    private static final Logger LOG = LoggerFactory.getLogger("sculptory");

    /** How far (blocks) a hanging entity's own blocks and its wall lie from its cell, at most (a 4 × 4 painting). */
    static final int SUPPORT = 2;
    /**
     * How far around a section just written the early sweep looks: an entity's support box
     * ({@link WorldEntities#supportBox}, its blocks grown by one) reaches this far from its cell, so the last section
     * written that the box touches always finds it.
     */
    static final int REACH = SUPPORT + 1;

    private HangingSupport() {}

    /** What a whole sweep did: entities taken along, and entities left to the game because they are protected. */
    public record Result(long removed, long conflicts) {
        public static final Result NONE = new Result(0, 0);
    }

    /** The section key holding block (x, y, z). */
    public static long sectionOf(int x, int y, int z) {
        return BlockBuffer.key(x >> 4, y >> 4, z >> 4);
    }

    /** A section's cells. */
    static Box sectionBox(long key) {
        int x = BlockBuffer.keyX(key) << 4, y = BlockBuffer.keyY(key) << 4, z = BlockBuffer.keyZ(key) << 4;
        return new Box(new BlockPos(x, y, z), new BlockPos(x + 15, y + 15, z + 15));
    }

    static Box grow(Box box, int by) {
        return new Box(new BlockPos(box.min().x() - by, box.min().y() - by, box.min().z() - by),
                new BlockPos(box.max().x() + by, box.max().y() + by, box.max().z() + by));
    }

    /**
     * The bounds the player's permits are asked for by a sweep of these sections: every cell an entity it may take
     * belongs to lies inside. {@code null} when there are no sections.
     */
    public static Box permitBounds(LongCollection sections) {
        Box bounds = null;
        for (long key : sections) {
            Box box = sectionBox(key);
            bounds = bounds == null ? box : new Box(new BlockPos(Math.min(bounds.min().x(), box.min().x()),
                    Math.min(bounds.min().y(), box.min().y()), Math.min(bounds.min().z(), box.min().z())),
                    new BlockPos(Math.max(bounds.max().x(), box.max().x()), Math.max(bounds.max().y(), box.max().y()),
                            Math.max(bounds.max().z(), box.max().z())));
        }
        return bounds == null ? null : grow(bounds, SUPPORT);
    }

    /**
     * The sweep of one edit over the sections it wrote, a chunk column at a time and a step at a time
     * ({@link EntityWork.Cursor}), for a job that checks its budget between steps. The columns are those of the written
     * sections and the columns around them (where an entity belonging near them can be stored), not every column of
     * the edit's bounds.
     */
    static final class Sweep {
        private final LongOpenHashSet written;
        private final Region region;
        private final long[] columns;
        private final EntityWork.Cursor cursor = new EntityWork.Cursor();

        Sweep(LongCollection writtenSections) {
            this.written = new LongOpenHashSet(writtenSections);
            Box bounds = permitBounds(written);
            this.region = bounds == null ? null : new Region.Cuboid(bounds);
            LongOpenHashSet chunks = new LongOpenHashSet();
            for (long key : written) {
                for (long column : EntityColumns.of(grow(sectionBox(key), SUPPORT))) chunks.add(column);
            }
            long[] sorted = chunks.toLongArray();
            Arrays.sort(sorted);
            this.columns = sorted;
        }

        /** The chunk columns to look in, in order. */
        long[] columns() {
            return columns;
        }

        /** Whether {@code cell} lies within {@link #SUPPORT} blocks of a written section. */
        boolean near(BlockPos cell) {
            for (int sy = (cell.y() - SUPPORT) >> 4; sy <= (cell.y() + SUPPORT) >> 4; sy++) {
                for (int sz = (cell.z() - SUPPORT) >> 4; sz <= (cell.z() + SUPPORT) >> 4; sz++) {
                    for (int sx = (cell.x() - SUPPORT) >> 4; sx <= (cell.x() + SUPPORT) >> 4; sx++) {
                        if (written.contains(BlockBuffer.key(sx, sy, sz))) return true;
                    }
                }
            }
            return false;
        }

        /**
         * Looks at one column while {@link EntityWork.Context#take} allows; true when the column is done. A column whose
         * entities are not loaded is done at once.
         */
        <E> boolean column(long column, EntityWork.Context<E> ctx) {
            int cx = ColumnPlan.unpackX(column), cz = ColumnPlan.unpackZ(column);
            if (region == null || !ctx.world.loaded(cx, cz)) return true;
            return cursor.walk(column, ctx, () -> ctx.world.inRegion(region, EntityFilter.DECORATIONS, cx, cz),
                    entity -> {
                        if (near(ctx.world.cell(entity))) take(entity, ctx);
                    });
        }
    }

    /**
     * The early sweep a job makes as soon as it has written section {@code key}: the hanging entities whose cell lies
     * within {@link #REACH} of the section and that no longer stay are taken along now, before the game's own check
     * (every 100 ticks per entity) can drop them while a long job is still writing, but only those whose support box
     * ({@link WorldEntities#supportBox}) is {@code settled}: it lies where the job writes nothing more, so the job cannot
     * give the entity its support back. The others are looked at again when the last section their box touches is
     * written, and by the job's last sweep. Only columns loaded with their entities are looked at.
     */
    static <E> void section(long key, EntityWork.Context<E> ctx, Predicate<Box> settled) {
        Box grown = grow(sectionBox(key), REACH);
        Region region = new Region.Cuboid(grown);
        for (long column : EntityColumns.of(grown)) {
            int cx = ColumnPlan.unpackX(column), cz = ColumnPlan.unpackZ(column);
            if (!ctx.world.loaded(cx, cz)) continue;
            for (E entity : ctx.world.inRegion(region, EntityFilter.DECORATIONS, cx, cz)) {
                if (ctx.world.removed(entity) || !ctx.world.supportLoaded(entity)) continue;
                if (ctx.world.hangsUnsupported(entity) && settled.test(ctx.world.supportBox(entity))) take(entity, ctx);
            }
        }
    }

    /** Takes one entity along if it no longer stays where it hangs. */
    private static <E> void take(E entity, EntityWork.Context<E> ctx) {
        // Fetched when the column started: it may have gone since.
        if (ctx.world.removed(entity) || ctx.world.riding(entity)) return;
        // What it hangs on is not all loaded: asking would load it. Left to the game.
        if (!ctx.world.supportLoaded(entity) || !ctx.world.hangsUnsupported(entity)) return;
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
        // An undo kept it because it changed since the step (a conflict); now it is in the step: no longer kept.
        if (ctx.kept.remove(ctx.world.id(entity))) ctx.conflicts--;
    }

    /**
     * The whole sweep at once, for edits written in one go outside the job executor (a builder action, a brush step):
     * the sections they wrote are few, so the entities near them are few. Nothing when {@code records} keeps no
     * entities.
     *
     * @param writtenSections the section keys ({@link #sectionOf}) the edit wrote cells in
     * @param permits the player's right to change each entity's cell
     * @param inWorld the world border and build limit
     */
    public static <E> Result sweep(WorldEntities<E> world, WriteOptions options, PermitSource permits,
                                   EntityWork.InWorld inWorld, RecordSink records, LongCollection writtenSections) {
        if (!records.recordsEntities() || writtenSections.isEmpty()) return Result.NONE;
        EntityWork.Context<E> ctx = EntityWork.Context.of(world, options, permits, records, inWorld);
        Sweep sweep = new Sweep(writtenSections);
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
