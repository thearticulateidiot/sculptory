package dev.sculptory.fabric.engine.impl;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.entity.EntityPlacement;
import dev.sculptory.core.entity.EntitySnapshot;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.history.EntityHistory;
import dev.sculptory.core.history.EntityMatcher;
import dev.sculptory.core.history.EntityState;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.fabric.world.EntityWriter;
import dev.sculptory.fabric.world.FabricEntities;
import dev.sculptory.server.engine.impl.ColumnPlan;
import dev.sculptory.server.engine.impl.EntityColumns;
import dev.sculptory.server.platform.EntityPlacer;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The entity work of each kind of job, built by the edit service at admission and
 * run by {@link BulkJob} a step at a time ({@link EntityWork.Cursor}). Placements happen after the blocks, removals
 * before them. Every change goes to the job's {@link RecordSink} with both sides read from the world; an entity history
 * could not record is neither removed nor left placed. Nothing is changed where its block may not be
 * ({@link EntityWork.Context#mayChange}, {@link EntityWork.Context#mayPlace}: counted as protected).
 */
final class EntityJobs {
    private static final Logger LOG = LoggerFactory.getLogger("sculptory");

    private EntityJobs() {}

    /** One clipboard entity to place: its data and where it lands. */
    record Placement(EntitySnapshot entity, EntityPlacement.Placed where) {}

    /** The column a position is stored in (its chunk). */
    static long columnOf(double x, double z) {
        return ColumnPlan.pack(((int) Math.floor(x)) >> 4, ((int) Math.floor(z)) >> 4);
    }

    /** {@code items} grouped by column, columns in first-use order. */
    static <T> Long2ObjectLinkedOpenHashMap<List<T>> byColumn(List<T> items, Function<T, Long> column) {
        Long2ObjectLinkedOpenHashMap<List<T>> out = new Long2ObjectLinkedOpenHashMap<>();
        for (T item : items) out.computeIfAbsent((long) column.apply(item), c -> new ArrayList<>()).add(item);
        return out;
    }

    /** The distinct columns of {@code items}, in first-use order. */
    static <T> long[] columnsOf(List<T> items, Function<T, Long> column) {
        LongLinkedOpenHashSet out = new LongLinkedOpenHashSet();
        for (T item : items) out.add((long) column.apply(item));
        return out.toLongArray();
    }

    /** Places planned entities after the blocks, a column at a time, a step at a time. */
    private abstract static class Placing implements EntityWork {
        private Long2ObjectLinkedOpenHashMap<List<Placement>> planned;
        private final Cursor<Placement> placing = new Cursor<>();
        final Transform transform;

        Placing(Transform transform) {
            this.transform = Objects.requireNonNull(transform);
        }

        /** What to place, asked once the work before the blocks is done. */
        abstract List<Placement> placements();

        @Override
        public long[] afterColumns() {
            planned = byColumn(placements(), p -> columnOf(p.where().x(), p.where().z()));
            return planned.keySet().toLongArray();
        }

        @Override
        public boolean after(long column, Context ctx) {
            return placing.walk(column, ctx, () -> planned.get(column), placement -> place(placement, ctx));
        }

        private void place(Placement placement, Context ctx) {
            EntityPlacer.Spawn<Entity> spawn = ctx.writer.place(placement.entity(), placement.where(), transform,
                    ctx::mayPlace);
            if (spawn.refused()) {
                ctx.protectedEntities++;
                return;
            }
            Entity placed = spawn.entity();
            if (placed == null) return; // counted by the writer
            EntityState after = EntityWriter.live(placed);
            if (after == null) {
                ctx.writer.takeBack(placed); // history could not keep it: nothing undo does not know about stays
                return;
            }
            ctx.records.entity(placed.getUuid(), null, after);
        }
    }

    /**
     * A paste's entities: {@code entities} (a clipboard's, local to a box of {@code size}) placed with {@code t} into the
     * box that starts at {@code targetMin}.
     */
    static EntityWork paste(List<EntitySnapshot> entities, BlockPos size, Transform t, BlockPos targetMin) {
        List<Placement> placements = new ArrayList<>(entities.size());
        for (EntitySnapshot entity : entities) {
            placements.add(new Placement(entity, EntityPlacement.place(entity, size, t, targetMin)));
        }
        return new Placing(t) {
            @Override
            public long[] admissionColumns() {
                return columnsOf(placements, p -> columnOf(p.where().x(), p.where().z()));
            }

            @Override
            public long[] beforeColumns() {
                return new long[0];
            }

            @Override
            public boolean before(long column, Context ctx) {
                return true;
            }

            @Override
            List<Placement> placements() {
                return placements;
            }
        };
    }

    /** Where a move or stack puts what it took. */
    @FunctionalInterface
    private interface Copies {
        List<Placement> of(EntitySnapshot taken);
    }

    /**
     * Takes the entities {@code filter} takes from {@code region} before the blocks (removing them when {@code remove}),
     * then places them after the blocks: a move (removed, placed with {@code t} at {@code destinationMin}, the transformed
     * box's minimum corner) or a stack (kept, placed {@code count} times, copy k shifted by k × (dx, dy, dz)).
     */
    private static final class Taking extends Placing {
        private final Region region;
        private final EntityFilter filter;
        private final boolean remove;
        private final boolean trusted;
        /** Entities (passengers included) taken at most. */
        private final long max;
        private final Copies copies;
        private final BlockPos min;
        /** Where the taken entities land (a move's destination, a stack's copies), for the admission columns. */
        private final Region[] targets;
        private final List<EntitySnapshot> taken = new ArrayList<>();
        /** Every entity looked at, so one that walks into a later column meanwhile is not taken twice. */
        private final Set<UUID> seen = new HashSet<>();
        private final Cursor<Entity> taking = new Cursor<>();
        private long takenTotal;
        private boolean cappedOut;

        Taking(Region region, EntityFilter filter, Box frame, boolean remove, boolean trusted, long max, Transform t,
               Region[] targets, Copies copies) {
            super(t);
            this.region = region;
            this.filter = filter;
            this.remove = remove;
            this.trusted = trusted;
            this.max = max;
            this.copies = copies;
            this.min = frame.min();
            this.targets = targets;
        }

        @Override
        public long[] admissionColumns() {
            LongLinkedOpenHashSet out = new LongLinkedOpenHashSet(beforeColumns());
            for (Region target : targets) {
                Box b = target.bounds();
                for (long column : EntityColumns.of(target, b.min().y(), b.max().y())) out.add(column);
            }
            return out.toLongArray();
        }

        @Override
        public long[] beforeColumns() {
            Box b = region.bounds();
            return EntityColumns.of(region, b.min().y(), b.max().y());
        }

        @Override
        public boolean before(long column, Context ctx) {
            return taking.walk(column, ctx, () -> FabricEntities.inRegion(ctx.world, region, filter,
                    ColumnPlan.unpackX(column), ColumnPlan.unpackZ(column)), entity -> take(entity, ctx));
        }

        private void take(Entity entity, Context ctx) {
            // Fetched when the column started: it may have gone, mounted something or walked off since.
            if (entity.isRemoved() || entity.hasVehicle() || !FabricEntities.in(region, entity)) return;
            if (!seen.add(entity.getUuid())) return;
            int count = FabricEntities.takenCount(entity, filter);
            if (takenTotal + count > max) {
                if (!cappedOut) {
                    cappedOut = true;
                    LOG.warn("Sculptory: an edit found more than {} entities to take along; the others stay "
                            + "where they are", max);
                }
                return;
            }
            // Only what is removed must be changeable here; a stack reads under the copy rule, checked on admission.
            if (remove && !ctx.mayChange(FabricEntities.cell(entity))) {
                ctx.protectedEntities++;
                return;
            }
            EntitySnapshot snapshot;
            try {
                snapshot = FabricEntities.snapshot(entity, filter, min, trusted);
            } catch (IllegalArgumentException e) {
                LOG.warn("Sculptory: a {} is too large to take along and stays where it is",
                        FabricEntities.typeId(entity));
                return;
            }
            if (snapshot == null) return;
            if (remove) {
                EntityState before = FabricEntities.state(entity, filter);
                if (before == null) {
                    LOG.warn("Sculptory: a {} cannot be recorded for undo and stays where it is",
                            FabricEntities.typeId(entity));
                    return;
                }
                ctx.writer.remove(entity, before);
                ctx.records.entity(entity.getUuid(), before, null);
            }
            taken.add(snapshot);
            takenTotal += count;
        }

        @Override
        List<Placement> placements() {
            List<Placement> out = new ArrayList<>();
            for (EntitySnapshot entity : taken) out.addAll(copies.of(entity));
            return out;
        }
    }

    /**
     * A move's entities: those {@code filter} takes from {@code region} are removed before the blocks and placed after
     * them with {@code t}: {@code frame} (the box the blocks move, of which each entity keeps its place) moved to start at
     * {@code destinationMin}. Each placement names the entity it was taken from (undo pairs them).
     */
    static EntityWork move(Region region, EntityFilter filter, Box frame, Transform t, BlockPos destinationMin,
                           long max) {
        BlockPos size = new BlockPos(frame.sizeX(), frame.sizeY(), frame.sizeZ());
        Region destination = dev.sculptory.core.region.Regions.moved(region, frame, destinationMin, t);
        return new Taking(region, filter, frame, true, true, max, t, new Region[] {destination},
                taken -> List.of(new Placement(taken, EntityPlacement.place(taken, size, t, destinationMin))));
    }

    /**
     * A stack's entities: those {@code filter} takes from {@code region} are copied {@code count} times, copy k shifted by
     * k × (dx, dy, dz) from {@code frame} (the box the blocks are copied from: the region's bounds cut to the build
     * height), each turned over within the region's whole bounds when {@code upsideDown}, as {@code StackProgram} turns
     * the blocks (the cut frame lands where its image within the whole bounds would); {@code trusted} as the stack's
     * tiles. {@code max} bounds the entities taken (each placed {@code count} times).
     */
    static EntityWork stack(Region region, EntityFilter filter, Box frame, int dx, int dy, int dz, int count,
                            boolean upsideDown, boolean trusted, long max) {
        BlockPos size = new BlockPos(frame.sizeX(), frame.sizeY(), frame.sizeZ());
        BlockPos min = upsideDown ? frame.flippedWithin(region.bounds()).min() : frame.min();
        Transform t = upsideDown ? Transform.UPSIDE_DOWN : Transform.IDENTITY;
        // Where the copies land, for the admission columns: the flip keeps every column, so shifted copies will do.
        Region[] copyRegions = new Region[count];
        for (int k = 1; k <= count; k++) copyRegions[k - 1] = region.translate(k * dx, k * dy, k * dz);
        return new Taking(region, filter, frame, false, trusted, max, t, copyRegions, taken -> {
            List<Placement> out = new ArrayList<>(count);
            for (int k = 1; k <= count; k++) {
                BlockPos copyMin = new BlockPos(min.x() + k * dx, min.y() + k * dy, min.z() + k * dz);
                out.add(new Placement(taken, EntityPlacement.place(taken, size, t, copyMin)));
            }
            return out;
        });
    }

    /**
     * A cut's entities: those its clipboard took ({@code ids}, as {@code taken} when copied, with the riders
     * {@code filter} took), removed before the erase with the same riders unless protected, gone since, or no longer
     * belonging to {@code region} within {@code area} (the box the cut erases); others ride on and stay.
     */
    static EntityWork cut(List<EntityState> taken, List<UUID> ids, EntityFilter filter, Region region, Box area) {
        List<Integer> order = new ArrayList<>(ids.size());
        for (int i = 0; i < ids.size(); i++) order.add(i);
        Long2ObjectLinkedOpenHashMap<List<Integer>> columns = byColumn(order,
                i -> columnOf(taken.get(i).x(), taken.get(i).z()));
        return new EntityWork() {
            private final Cursor<Integer> cutting = new Cursor<>();

            @Override
            public long[] admissionColumns() {
                return columns.keySet().toLongArray();
            }

            @Override
            public long[] beforeColumns() {
                return columns.keySet().toLongArray();
            }

            @Override
            public boolean before(long column, Context ctx) {
                return cutting.walk(column, ctx, () -> columns.get(column), i -> cut(ids.get(i), ctx));
            }

            private void cut(UUID id, Context ctx) {
                Entity entity = FabricEntities.find(ctx.world, id);
                if (entity == null || entity.isRemoved()) return; // gone since the copy: nothing to remove
                net.minecraft.util.math.BlockPos cell = FabricEntities.cell(entity);
                if (!FabricEntities.in(region, entity) || !area.contains(cell.getX(), cell.getY(), cell.getZ())) {
                    return; // moved out of what was cut since the copy: it stays where it is now
                }
                if (!ctx.mayChange(cell)) {
                    ctx.protectedEntities++;
                    return;
                }
                // As it is now, with the riders the copy's filter takes: exactly what goes.
                EntityState before = FabricEntities.state(entity, filter);
                if (before == null) {
                    LOG.warn("Sculptory: a {} cannot be recorded for undo and stays where it is",
                            FabricEntities.typeId(entity));
                    return;
                }
                ctx.writer.remove(entity, before);
                ctx.records.entity(entity.getUuid(), before, null);
            }

            @Override
            public long[] afterColumns() {
                return new long[0];
            }

            @Override
            public boolean after(long column, Context ctx) {
                return true;
            }
        };
    }

    /**
     * Undo, redo or re-apply of recorded entities ({@link EntityHistory}): before the blocks, each entity is decided
     * against what the world holds under its UUID and removed where the rule allows; after them, the targets are put
     * back with their UUIDs. Kept entities are counted as conflicts. Nothing is recorded (history jobs push nothing). A
     * move's source is put back whether or not the moved entity was found and removed (one that walked into a chunk
     * that is not loaded is then left as a duplicate: a known limit, never a loss).
     */
    static EntityWork history(List<EntityHistory.Step> steps, ConflictPolicy policy, EntityMatcher matcher) {
        Long2ObjectLinkedOpenHashMap<List<EntityHistory.Step>> columns = byColumn(steps,
                s -> columnOf(s.where().x(), s.where().z()));
        List<EntityHistory.Step> restore = new ArrayList<>();
        return new EntityWork() {
            private final Cursor<EntityHistory.Step> deciding = new Cursor<>();
            private final Cursor<EntityHistory.Step> restoring = new Cursor<>();
            private Long2ObjectLinkedOpenHashMap<List<EntityHistory.Step>> targets;

            @Override
            public long[] admissionColumns() {
                LongLinkedOpenHashSet out = new LongLinkedOpenHashSet(columns.keySet());
                for (EntityHistory.Step step : steps) {
                    if (step.target() != null) out.add(columnOf(step.target().x(), step.target().z()));
                }
                return out.toLongArray();
            }

            @Override
            public long[] beforeColumns() {
                return columns.keySet().toLongArray();
            }

            @Override
            public boolean before(long column, Context ctx) {
                return deciding.walk(column, ctx, () -> columns.get(column), step -> decide(step, ctx));
            }

            private void decide(EntityHistory.Step step, Context ctx) {
                Entity entity = FabricEntities.find(ctx.world, step.id());
                if (entity != null && entity.isRemoved()) entity = null;
                EntityState live = entity == null ? null : EntityWriter.live(entity);
                if (entity != null && live == null) {
                    ctx.conflicts++; // there, but too large to compare: kept
                    return;
                }
                switch (EntityHistory.decide(step, live, policy, matcher)) {
                    case SKIP -> {}
                    case CONFLICT -> ctx.conflicts++;
                    case APPLY -> {
                        if (entity != null) {
                            if (!ctx.mayChange(FabricEntities.cell(entity))) {
                                ctx.protectedEntities++;
                                return;
                            }
                            ctx.writer.remove(entity, step.expected() != null ? step.expected() : live);
                        }
                        if (step.target() != null) restore.add(step);
                    }
                }
            }

            @Override
            public long[] afterColumns() {
                targets = byColumn(restore, s -> columnOf(s.target().x(), s.target().z()));
                return targets.keySet().toLongArray();
            }

            @Override
            public boolean after(long column, Context ctx) {
                return restoring.walk(column, ctx, () -> targets.get(column), step -> restore(step, ctx));
            }

            private void restore(EntityHistory.Step step, Context ctx) {
                Entity there = FabricEntities.find(ctx.world, step.id());
                if (there != null && !there.isRemoved()) {
                    ctx.conflicts++; // something took its UUID meanwhile
                    return;
                }
                EntityPlacer.Spawn<Entity> spawn = ctx.writer.restore(step.target(), ctx::mayPlace);
                if (spawn.refused()) {
                    ctx.protectedEntities++;
                } else if (spawn.entity() == null) {
                    ctx.conflicts++; // could not be put back (counted by the writer too)
                }
            }
        };
    }
}
