package dev.sculptory.server.engine.impl;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.entity.EntityNbt;
import dev.sculptory.core.entity.EntityPlacement;
import dev.sculptory.core.entity.EntitySnapshot;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.history.EntityHistory;
import dev.sculptory.core.history.EntityMatcher;
import dev.sculptory.core.history.EntityState;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.transform.Orientation;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.server.platform.EntityPlacer;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The entity work of each kind of job, built by the edit service at admission and
 * run by {@link BulkJob} a step at a time ({@link EntityWork.Cursor}). Placements happen after the blocks, removals
 * before them. Every change goes to the job's {@link RecordSink} with both sides read from the world; an entity history
 * could not record is neither removed nor left placed. Nothing is changed where its block may not be
 * ({@link EntityWork.Context#mayChange}, {@link EntityWork.Context#mayPlace}: counted as protected).
 */
public final class EntityJobs {
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

    /** Whether a block belongs to {@code region}: an entity belonging to it does. */
    private static boolean in(Region region, BlockPos cell) {
        return region.contains(cell.x(), cell.y(), cell.z());
    }

    /** Places planned entities after the blocks, a column at a time, a step at a time. */
    private abstract static class Placing implements EntityWork {
        private Long2ObjectLinkedOpenHashMap<List<Placement>> planned;
        private final Cursor placing = new Cursor();
        final Orientation transform;

        Placing(Orientation transform) {
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
        public <E> boolean after(long column, Context<E> ctx) {
            return placing.walk(column, ctx, () -> planned.get(column), placement -> place(placement, ctx));
        }

        private <E> void place(Placement placement, Context<E> ctx) {
            EntityPlacer.Spawn<E> spawn = ctx.writer.place(placement.entity(), placement.where(), transform,
                    ctx::mayPlace);
            if (spawn.refused()) {
                ctx.protectedEntities++;
                return;
            }
            E placed = spawn.entity();
            if (placed == null) return; // counted by the writer
            EntityState after = ctx.world.live(placed);
            if (after == null) {
                ctx.writer.takeBack(placed); // history could not keep it: nothing undo does not know about stays
                return;
            }
            ctx.records.entity(ctx.world.id(placed), null, after);
        }
    }

    /**
     * A paste's entities: {@code entities} (a clipboard's, local to a box of {@code size}) placed with {@code t} into the
     * box that starts at {@code targetMin}.
     */
    public static EntityWork paste(List<EntitySnapshot> entities, BlockPos size, Transform t, BlockPos targetMin) {
        return paste(entities, size, Orientation.of(t), targetMin);
    }

    /**
     * A paste's entities with an orientation (protocol 6): {@code entities} placed into the result box that starts at
     * {@code targetMin}, positions mapped by {@code EntityPlacement.place} for the orientation.
     */
    public static EntityWork paste(List<EntitySnapshot> entities, BlockPos size, Orientation t, BlockPos targetMin) {
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
            public <E> boolean before(long column, Context<E> ctx) {
                return true;
            }

            @Override
            List<Placement> placements() {
                return placements;
            }
        };
    }

    /**
     * Several works as one (a multi-paste's placements): each column's work runs the works holding that column in
     * their order, before the blocks and after them; the columns are their unions, in first-use order.
     */
    public static EntityWork all(List<EntityWork> works) {
        List<EntityWork> list = List.copyOf(works);
        if (list.size() == 1) return list.get(0);
        return new EntityWork() {
            private final Walk before = new Walk();
            private final Walk after = new Walk();

            @Override
            public long[] admissionColumns() {
                LongLinkedOpenHashSet columns = new LongLinkedOpenHashSet();
                for (EntityWork work : list) columns.addAll(LongArrayList.wrap(work.admissionColumns()));
                return columns.toLongArray();
            }

            @Override
            public long[] beforeColumns() {
                return before.columns(list, EntityWork::beforeColumns);
            }

            @Override
            public <E> boolean before(long column, Context<E> ctx) {
                return before.walk(list, column, work -> work.before(column, ctx));
            }

            @Override
            public long[] afterColumns() {
                return after.columns(list, EntityWork::afterColumns);
            }

            @Override
            public <E> boolean after(long column, Context<E> ctx) {
                return after.walk(list, column, work -> work.after(column, ctx));
            }
        };
    }

    /** One side (before or after the blocks) of {@link #all}: which work holds which column, and how far a column got. */
    private static final class Walk {
        private LongOpenHashSet[] holding;
        private long column = Long.MIN_VALUE;
        private boolean started;
        private int next;

        long[] columns(List<EntityWork> works, Function<EntityWork, long[]> of) {
            holding = new LongOpenHashSet[works.size()];
            LongLinkedOpenHashSet all = new LongLinkedOpenHashSet();
            for (int i = 0; i < works.size(); i++) {
                long[] own = of.apply(works.get(i));
                holding[i] = new LongOpenHashSet(own);
                for (long c : own) all.add(c);
            }
            return all.toLongArray();
        }

        boolean walk(List<EntityWork> works, long column, java.util.function.Predicate<EntityWork> step) {
            if (!started || this.column != column) {
                started = true;
                this.column = column;
                next = 0;
            }
            while (next < works.size()) {
                if (holding[next].contains(column) && !step.test(works.get(next))) return false;
                next++;
            }
            started = false;
            return true;
        }
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
        private final Cursor taking = new Cursor();
        private long takenTotal;
        private boolean cappedOut;

        Taking(Region region, EntityFilter filter, Box frame, boolean remove, boolean trusted, long max, Orientation t,
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
        public <E> boolean before(long column, Context<E> ctx) {
            return taking.walk(column, ctx, () -> ctx.world.inRegion(region, filter, ColumnPlan.unpackX(column),
                    ColumnPlan.unpackZ(column)), entity -> take(entity, ctx));
        }

        private <E> void take(E entity, Context<E> ctx) {
            // Fetched when the column started: it may have gone, mounted something or walked off since.
            if (ctx.world.removed(entity) || ctx.world.riding(entity) || !in(region, ctx.world.cell(entity))) return;
            if (!seen.add(ctx.world.id(entity))) return;
            int count = ctx.world.takenCount(entity, filter);
            if (takenTotal + count > max) {
                if (!cappedOut) {
                    cappedOut = true;
                    LOG.warn("Sculptory: an edit found more than {} entities to take along; the others stay "
                            + "where they are", max);
                }
                return;
            }
            // Only what is removed must be changeable here; a stack reads under the copy rule, checked on admission.
            if (remove && !ctx.mayChange(ctx.world.cell(entity))) {
                ctx.protectedEntities++;
                return;
            }
            EntitySnapshot snapshot;
            try {
                snapshot = ctx.world.snapshot(entity, filter, min, trusted);
            } catch (IllegalArgumentException e) {
                LOG.warn("Sculptory: a {} is too large to take along and stays where it is", ctx.world.typeId(entity));
                return;
            }
            if (snapshot == null) return;
            if (remove) {
                EntityState before = ctx.world.state(entity, filter);
                if (before == null) {
                    LOG.warn("Sculptory: a {} cannot be recorded for undo and stays where it is",
                            ctx.world.typeId(entity));
                    return;
                }
                ctx.writer.remove(entity, before);
                ctx.records.entity(ctx.world.id(entity), before, null);
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
    public static EntityWork move(Region region, EntityFilter filter, Box frame, Transform t, BlockPos destinationMin,
                           long max) {
        return move(region, filter, frame, Orientation.of(t), destinationMin, max);
    }

    /**
     * {@link #move(Region, EntityFilter, Box, Transform, BlockPos, long)} with an exact orientation.
     *
     * @throws IllegalArgumentException for a free orientation: use the overload taking its landing cells
     */
    public static EntityWork move(Region region, EntityFilter filter, Box frame, Orientation t, BlockPos destinationMin,
                                  long max) {
        // A free move's landing cells are worked out once, at admission, within the bound: they must be passed in.
        if (!t.isExact()) throw new IllegalArgumentException("A free move passes its landing cells");
        return move(region, filter, frame, t, destinationMin,
                dev.sculptory.core.region.Regions.moved(region, frame, destinationMin, t.transform()), max);
    }

    /**
     * {@link #move(Region, EntityFilter, Box, Orientation, BlockPos, long)} with the cells the blocks land on worked out
     * already ({@code destination}: a free move's landing cells, for the admission columns).
     */
    public static EntityWork move(Region region, EntityFilter filter, Box frame, Orientation t, BlockPos destinationMin,
                                  Region destination, long max) {
        BlockPos size = new BlockPos(frame.sizeX(), frame.sizeY(), frame.sizeZ());
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
    public static EntityWork stack(Region region, EntityFilter filter, Box frame, int dx, int dy, int dz, int count,
                            boolean upsideDown, boolean trusted, long max) {
        BlockPos size = new BlockPos(frame.sizeX(), frame.sizeY(), frame.sizeZ());
        BlockPos min = upsideDown ? frame.flippedWithin(region.bounds()).min() : frame.min();
        Orientation t = Orientation.of(upsideDown ? Transform.UPSIDE_DOWN : Transform.IDENTITY);
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
    public static EntityWork cut(List<EntityState> taken, List<UUID> ids, EntityFilter filter, Region region,
                                 Box area) {
        List<Integer> order = new ArrayList<>(ids.size());
        for (int i = 0; i < ids.size(); i++) order.add(i);
        Long2ObjectLinkedOpenHashMap<List<Integer>> columns = byColumn(order,
                i -> columnOf(taken.get(i).x(), taken.get(i).z()));
        return new EntityWork() {
            private final Cursor cutting = new Cursor();

            @Override
            public long[] admissionColumns() {
                return columns.keySet().toLongArray();
            }

            @Override
            public long[] beforeColumns() {
                return columns.keySet().toLongArray();
            }

            @Override
            public <E> boolean before(long column, Context<E> ctx) {
                return cutting.walk(column, ctx, () -> columns.get(column), i -> cut(ids.get(i), ctx));
            }

            private <E> void cut(UUID id, Context<E> ctx) {
                E entity = ctx.world.find(id);
                if (entity == null || ctx.world.removed(entity)) return; // gone since the copy: nothing to remove
                BlockPos cell = ctx.world.cell(entity);
                if (!in(region, cell) || !area.contains(cell.x(), cell.y(), cell.z())) {
                    return; // moved out of what was cut since the copy: it stays where it is now
                }
                if (!ctx.mayChange(cell)) {
                    ctx.protectedEntities++;
                    return;
                }
                // As it is now, with the riders the copy's filter takes: exactly what goes.
                EntityState before = ctx.world.state(entity, filter);
                if (before == null) {
                    LOG.warn("Sculptory: a {} cannot be recorded for undo and stays where it is",
                            ctx.world.typeId(entity));
                    return;
                }
                ctx.writer.remove(entity, before);
                ctx.records.entity(ctx.world.id(entity), before, null);
            }

            @Override
            public long[] afterColumns() {
                return new long[0];
            }

            @Override
            public <E> boolean after(long column, Context<E> ctx) {
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
     *
     * <p>A target that cannot be put back (another plugin cancels its spawn, its data fails to load, or it is an item
     * frame or painting with nothing to hang on there any more, which the game would drop off with its item) is counted
     * refused (or as a conflict), and what the step took away for it comes back instead, so the entity stays in the world
     * and the entry stays undoable later:
     * <ul>
     *   <li>the entity of the same UUID the step replaced (a Tinker edit's, a place-then-change record's), as it
     *       was;</li>
     *   <li>for a target the step only puts back (a move's source), an entity the same step only took away that stood
     *       for it (the move's copy), by its data: first the copy as the step recorded placing it, then as it was when
     *       taken away, both exactly ({@link EntityNbt#exactCopyForm}), then as taken away but for what the game
     *       changes by itself ({@link EntityNbt#copyForm}); in the job's order, each used once. So a copy renamed or
     *       re-equipped since and undone anyway still pairs (as recorded); one whose data the move itself changed (a
     *       turned frame's facing) does not: logged as a warning, since that copy was taken away while its source
     *       could not come back.</li>
     * </ul>
     * What comes back has only the riders its removal took along (the others were dismounted and stay), is checked
     * against protection as its removal was, and is not counted as placed. It does not come back where it would not
     * last (the job has restored the blocks there by now: a hanging entity whose wall is gone, a mob inside solid
     * blocks); that, and any other failure to come back, counts as a conflict. Only a job with a step that puts an
     * entity back keeps what its steps take away, and only one that only puts some back keeps what they only take away.
     *
     * <p>Known limits: an entity that cannot come back (refused, or it would not last) is only in the history step
     * until a redo or a redo anyway puts it back. A recorded rider that was re-mounted since the step onto a newer
     * rider of the same vehicle (boat, then a new rider, then the recorded one on it) is discarded with the vehicle by
     * the removal, as recorded riders are, but not put back with it, since the newer rider between them is not the
     * step's: it too is then only in the history step.
     */
    public static EntityWork history(List<EntityHistory.Step> steps, ConflictPolicy policy, EntityMatcher matcher) {
        return history(steps, policy, matcher, (message, typeId) -> LOG.warn(message, typeId));
    }

    /** {@link #history}, its warnings about entities left only in the history going to {@code warn} (tests). */
    static EntityWork history(List<EntityHistory.Step> steps, ConflictPolicy policy, EntityMatcher matcher,
                              BiConsumer<String, String> warn) {
        Long2ObjectLinkedOpenHashMap<List<EntityHistory.Step>> columns = byColumn(steps,
                s -> columnOf(s.where().x(), s.where().z()));
        List<EntityHistory.Step> restore = new ArrayList<>();
        boolean putsBack = false;
        boolean onlyPutsBack = false;
        for (EntityHistory.Step step : steps) {
            if (step.target() == null) continue;
            putsBack = true;
            if (step.expected() == null) onlyPutsBack = true;
        }
        boolean keepTakenAway = onlyPutsBack;
        boolean anyPutBack = putsBack;
        return new EntityWork() {
            private final Cursor deciding = new Cursor();
            private final Cursor restoring = new Cursor();
            private Long2ObjectLinkedOpenHashMap<List<EntityHistory.Step>> targets;
            /** Entities of the same UUID a step replaced, by UUID, until their target is put back. */
            private final Map<UUID, Removed> replaced = new HashMap<>();
            /** Entities steps only took away (a move's copies), in the job's order. */
            private final List<Removed> takenAway = new ArrayList<>();
            private int warnings;

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
            public <E> boolean before(long column, Context<E> ctx) {
                return deciding.walk(column, ctx, () -> columns.get(column), step -> decide(step, ctx));
            }

            private <E> void decide(EntityHistory.Step step, Context<E> ctx) {
                E entity = ctx.world.find(step.id());
                if (entity != null && ctx.world.removed(entity)) entity = null;
                EntityState live = entity == null ? null : ctx.world.live(entity);
                if (entity != null && live == null) {
                    ctx.conflicts++; // there, but too large to compare: kept
                    return;
                }
                switch (EntityHistory.decide(step, live, policy, matcher)) {
                    case SKIP -> {}
                    case CONFLICT -> {
                        ctx.conflicts++;
                        if (entity != null) ctx.kept.add(step.id());
                    }
                    case APPLY -> {
                        if (entity != null) {
                            if (!ctx.mayChange(ctx.world.cell(entity))) {
                                ctx.protectedEntities++;
                                return;
                            }
                            EntityState taken = step.expected() != null ? step.expected() : live;
                            ctx.writer.remove(entity, taken);
                            if (step.target() != null) {
                                replaced.put(step.id(), new Removed(live, taken));
                            } else if (keepTakenAway) {
                                takenAway.add(new Removed(live, taken));
                            }
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
            public <E> boolean after(long column, Context<E> ctx) {
                return restoring.walk(column, ctx, () -> targets.get(column), step -> restore(step, ctx));
            }

            private <E> void restore(EntityHistory.Step step, Context<E> ctx) {
                Removed back = replaced.remove(step.id());
                E there = ctx.world.find(step.id());
                if (there != null && !ctx.world.removed(there)) {
                    ctx.conflicts++; // something took its UUID meanwhile
                    return;
                }
                EntityPlacer.Spawn<E> spawn = ctx.writer.restore(step.target(), ctx::mayPlace);
                E placed = spawn.entity();
                if (placed != null && ctx.world.supportLoaded(placed) && ctx.world.hangsUnsupported(placed)) {
                    // Its wall went since the step (a later edit, or one this job did not undo): the game would drop it
                    // off with its item at its next check, so it is not put back.
                    ctx.writer.takeBack(placed, "it has nothing to hang on there any more");
                    placed = null;
                }
                if (placed != null) return;
                if (spawn.refused()) {
                    ctx.protectedEntities++;
                } else {
                    ctx.conflicts++; // could not be put back (counted by the writer too)
                }
                if (back == null && step.expected() == null) back = counterpart(step.target());
                if (back == null) return;
                if (ctx.writer.putBack(back.putBack(), e -> ctx.mayChange(ctx.world.cell(e))).entity() == null) {
                    ctx.conflicts++;
                    warn("Sculptory: a {} an undo or redo took away could not be put back after what was to stand in "
                            + "its place was refused (protected, or it would not last there); its data stays in the "
                            + "history step", back.live.typeId());
                }
            }

            /**
             * The entity the job only took away that stood for {@code target} (a move's copy of its source), in three
             * passes over the job's order: as recorded when placed, exactly; as taken away, exactly; as taken away but
             * for what the game changes by itself. Null when none matches (a warning when the job took copies away).
             */
            private Removed counterpart(EntityState target) {
                if (anyPutBack && !takenAway.isEmpty()) {
                    byte[] exact = EntityNbt.exactCopyForm(target.nbt());
                    byte[] loose = EntityNbt.copyForm(target.nbt());
                    Removed found = find(target, exact, Removed::takenExactForm);
                    if (found == null) found = find(target, exact, Removed::liveExactForm);
                    if (found == null) found = find(target, loose, Removed::liveForm);
                    if (found != null) {
                        found.used = true;
                        return found;
                    }
                }
                boolean unused = takenAway.stream().anyMatch(copy -> !copy.used);
                if (unused) {
                    warn("Sculptory: a {} an undo or redo could not put back matches none of the entities it took away "
                            + "(a copy whose data the move turned); that copy stays only in the history step",
                            target.typeId());
                } else if (LOG.isDebugEnabled()) {
                    LOG.debug("Sculptory: a {} an undo or redo could not put back had no copy taken away with it",
                            target.typeId());
                }
                return null;
            }

            private Removed find(EntityState target, byte[] form, Function<Removed, byte[]> formOf) {
                if (form == null) return null;
                for (Removed copy : takenAway) {
                    if (copy.used || !copy.live.typeId().equals(target.typeId())) continue;
                    if (Arrays.equals(formOf.apply(copy), form)) return copy;
                }
                return null;
            }

            private void warn(String message, String typeId) {
                if (warnings++ < MAX_LOGGED_WARNINGS) warn.accept(message, typeId);
            }
        };
    }

    /** Warnings one history job logs at most about entities it could not give back. */
    static final int MAX_LOGGED_WARNINGS = 5;

    /**
     * An entity a history step took away: as it was ({@code live}) and as the step recorded it ({@code taken}). What is
     * put back ({@link #putBack}) and its forms for pairing are worked out only when needed.
     */
    private static final class Removed {
        final EntityState live;
        final EntityState taken;
        boolean used;
        private EntityState back;
        private byte[] takenExact;
        private byte[] liveExact;
        private byte[] liveLoose;

        Removed(EntityState live, EntityState taken) {
            this.live = live;
            this.taken = taken;
        }

        /** {@code live} with only the riders {@code taken} names ({@link EntityNbt#withRidersOf}). */
        EntityState putBack() {
            if (back == null) {
                if (taken == live || EntityNbt.riderCount(live.nbt()) == 0) {
                    back = live;
                } else {
                    back = new EntityState(live.typeId(), live.x(), live.y(), live.z(),
                            EntityNbt.withRidersOf(live.nbt(), taken.nbt()));
                }
            }
            return back;
        }

        byte[] takenExactForm() {
            if (takenExact == null) takenExact = orEmpty(EntityNbt.exactCopyForm(taken.nbt()));
            return takenExact;
        }

        byte[] liveExactForm() {
            if (liveExact == null) liveExact = orEmpty(EntityNbt.exactCopyForm(live.nbt()));
            return liveExact;
        }

        byte[] liveForm() {
            if (liveLoose == null) liveLoose = orEmpty(EntityNbt.copyForm(live.nbt()));
            return liveLoose;
        }

        /** Data that does not decode pairs with nothing (an empty form matches no decoded one). */
        private static byte[] orEmpty(byte[] form) {
            return form == null ? new byte[0] : form;
        }
    }
}
