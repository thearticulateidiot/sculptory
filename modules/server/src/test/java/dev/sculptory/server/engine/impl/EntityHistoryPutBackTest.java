package dev.sculptory.server.engine.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.entity.EntityNbt;
import dev.sculptory.core.entity.EntityPlacement;
import dev.sculptory.core.entity.EntitySnapshot;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.history.EntityHistory.Step;
import dev.sculptory.core.history.EntityMatcher;
import dev.sculptory.core.history.EntityState;
import dev.sculptory.core.nbt.NbtCompound;
import dev.sculptory.core.nbt.NbtIo;
import dev.sculptory.core.nbt.NbtTag;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.transform.Orientation;
import dev.sculptory.server.engine.ChunkPermit;
import dev.sculptory.server.platform.EntityPlacer;
import dev.sculptory.server.platform.WorldEntities;
import dev.sculptory.server.platform.WriteOptions;
import java.io.IOException;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * A history job's put-back ({@link EntityJobs#history}): what a step took away comes back when what was to stand in
 * its place cannot, over a world and a placer of plain objects. Covered: a put-back that is itself refused (a conflict,
 * the warnings capped), a copy that matches none (a warning), the choice among candidates (the copy as recorded, then
 * exactly as taken away, then but for what the game changes by itself), and the same-UUID path on the platforms' default
 * {@link EntityPlacer#putBack}.
 */
class EntityHistoryPutBackTest {
    /** An entity of the fake world: its UUID and its saved state. */
    static final class Thing {
        final UUID id;
        final EntityState state;

        Thing(UUID id, EntityState state) {
            this.id = id;
            this.state = state;
        }
    }

    static final class World implements WorldEntities<Thing> {
        final Map<UUID, Thing> things = new LinkedHashMap<>();
        /** Things that hang on nothing any more (frames whose wall is gone). */
        final Set<UUID> unsupported = new HashSet<>();
        /** Things whose wall lies in a chunk that is not fully loaded. */
        final Set<UUID> unloadedSupport = new HashSet<>();
        Placer placer;

        void add(Thing thing) {
            things.put(thing.id, thing);
        }

        @Override public boolean loaded(int cx, int cz) { return true; }
        @Override public String firstUnloaded(long[] columns) { return null; }
        @Override public List<Thing> inRegion(Region region, EntityFilter filter, long[] columns) { return List.of(); }
        @Override
        public List<Thing> inRegion(Region region, EntityFilter filter, int cx, int cz) {
            List<Thing> found = new ArrayList<>();
            for (Thing thing : things.values()) {
                BlockPos cell = cell(thing);
                if (region.contains(cell.x(), cell.y(), cell.z()) && EntityJobs.columnOf(thing.state.x(), thing.state.z())
                        == ColumnPlan.pack(cx, cz)) {
                    found.add(thing);
                }
            }
            return found;
        }
        @Override public Thing find(UUID id) { return things.get(id); }
        @Override public UUID id(Thing entity) { return entity.id; }
        @Override public boolean removed(Thing entity) { return things.get(entity.id) != entity; }
        @Override public boolean riding(Thing entity) { return false; }
        @Override public BlockPos cell(Thing entity) { return blockPos(entity); }
        @Override
        public boolean hangsUnsupported(Thing entity) {
            // Asking about an entity whose support is not loaded would load a chunk on a real server.
            if (unloadedSupport.contains(entity.id)) {
                throw new AssertionError("asked about an entity whose support is not loaded");
            }
            return unsupported.contains(entity.id);
        }

        @Override public boolean supportLoaded(Thing entity) { return !unloadedSupport.contains(entity.id); }

        /** The thing's block and the blocks around it. */
        @Override
        public dev.sculptory.core.Box supportBox(Thing entity) {
            BlockPos at = blockPos(entity);
            return new dev.sculptory.core.Box(new BlockPos(at.x() - 1, at.y() - 1, at.z() - 1),
                    new BlockPos(at.x() + 1, at.y() + 1, at.z() + 1));
        }

        @Override
        public BlockPos blockPos(Thing entity) {
            EntityState s = entity.state;
            return new BlockPos((int) Math.floor(s.x()), (int) Math.floor(s.y()), (int) Math.floor(s.z()));
        }

        @Override public int takenCount(Thing entity, EntityFilter filter) { return 1; }

        @Override
        public EntitySnapshot snapshot(Thing entity, EntityFilter filter, BlockPos min, boolean trusted) {
            throw new UnsupportedOperationException();
        }

        @Override public EntityState state(Thing entity, EntityFilter filter) { return entity.state; }
        @Override public EntityState live(Thing entity) { return entity.state; }
        @Override public String typeId(Thing entity) { return entity.state.typeId(); }
        @Override public EntityPlacer<Thing> placer(WriteOptions options) { return placer; }
    }

    /** Puts back and removes in the fake world; refuses the UUIDs it is told to. */
    static final class Placer implements EntityPlacer<Thing> {
        final World world;
        final Set<UUID> refuseRestore = new HashSet<>();
        final Set<UUID> refusePutBack = new HashSet<>();
        /** Use the platforms' default put-back (a restore), as Fabric and NeoForge do. */
        boolean defaultPutBack;
        long placed;
        long removed;
        long takenBack;

        Placer(World world) {
            this.world = world;
            world.placer = this;
        }

        @Override
        public Spawn<Thing> place(EntitySnapshot entity, EntityPlacement.Placed where, Orientation orientation,
                                  Predicate<Thing> allowed) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Spawn<Thing> restore(EntityState state, Predicate<Thing> allowed) {
            Thing thing = new Thing(uuid(state), state);
            if (refuseRestore.contains(thing.id) || !allowed.test(thing)) return new Spawn<>(null, true);
            world.add(thing);
            placed++;
            return new Spawn<>(thing, false);
        }

        @Override
        public Spawn<Thing> putBack(EntityState state, Predicate<Thing> allowed) {
            if (defaultPutBack) return EntityPlacer.super.putBack(state, allowed);
            Thing thing = new Thing(uuid(state), state);
            if (refusePutBack.contains(thing.id)) return new Spawn<>(null, false); // it would not last there
            if (!allowed.test(thing)) return new Spawn<>(null, true);
            world.add(thing);
            return new Spawn<>(thing, false);
        }

        @Override
        public void takeBack(Thing root) {
            world.things.remove(root.id);
            placed--;
            takenBack++;
        }

        @Override
        public void remove(Thing root, EntityState recorded) {
            world.things.remove(root.id);
            removed++;
        }

        @Override public long placed() { return placed; }
        @Override public long removed() { return removed; }
        @Override public long stripped() { return 0; }
        @Override public long failures() { return 0; }
    }

    private final World world = new World();
    private final Placer placer = new Placer(world);
    private final List<String> warnings = new ArrayList<>();
    /** Cells with x of 100 or more may not be changed. */
    private final PermitSource permits = new PermitSource() {
        @Override
        public ChunkPermit chunk(int cx, int cz) {
            return ChunkPermit.ALLOW;
        }

        @Override
        public boolean mayChange(int x, int y, int z) {
            return x < 100;
        }
    };

    private static int[] ints(UUID id) {
        long m = id.getMostSignificantBits(), l = id.getLeastSignificantBits();
        return new int[] {(int) (m >> 32), (int) m, (int) (l >> 32), (int) l};
    }

    private static UUID uuid(EntityState state) {
        try {
            int[] a = ((NbtTag.NbtIntArray) EntityNbt.decode(state.nbt()).get("UUID")).value();
            return new UUID(((long) a[0] << 32) | (a[1] & 0xFFFFFFFFL), ((long) a[2] << 32) | (a[3] & 0xFFFFFFFFL));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /** A pig as the world saves it. */
    private static EntityState pig(UUID id, double x, String name, int air) {
        NbtCompound nbt = NbtCompound.builder().putString("id", "minecraft:pig").putIntArray("UUID", ints(id))
                .put("Pos", EntityNbt.doubles(x, 64, 0.5)).putString("CustomName", "\"" + name + "\"")
                .putShort("Air", (short) air).build();
        return new EntityState("minecraft:pig", x, 64, 0.5, NbtIo.toBytes(nbt));
    }

    private Thing put(EntityState state) {
        Thing thing = new Thing(uuid(state), state);
        world.add(thing);
        return thing;
    }

    /** Runs the work through the job's two phases, as a job would, column by column. */
    private EntityWork.Context<Thing> run(List<Step> steps, ConflictPolicy policy) {
        EntityWork work = EntityJobs.history(steps, policy, EntityMatcher.ignoringVolatile(),
                (message, typeId) -> warnings.add(message.replace("{}", typeId)));
        EntityWork.Context<Thing> ctx = new EntityWork.Context<>(world, placer, permits, RecordSink.NONE,
                (x, y, z) -> true);
        for (long column : work.beforeColumns()) {
            do {
                ctx.allowance = 1000;
            } while (!work.before(column, ctx));
        }
        for (long column : work.afterColumns()) {
            do {
                ctx.allowance = 1000;
            } while (!work.after(column, ctx));
        }
        return ctx;
    }

    /** The undo of a move of {@code source} to {@code copy}: the copy goes, the source comes back. */
    private static List<Step> moveUndo(EntityState source, EntityState copy) {
        return List.of(new Step(uuid(copy), copy, null), new Step(uuid(source), null, source));
    }

    @Test
    void aMovedCopyComesBackWhenItsSourceIsRefused() {
        EntityState source = pig(UUID.randomUUID(), 1.5, "Wilbur", 300);
        EntityState copy = pig(UUID.randomUUID(), 11.5, "Wilbur", 300);
        put(copy);
        placer.refuseRestore.add(uuid(source));
        EntityWork.Context<Thing> ctx = run(moveUndo(source, copy), ConflictPolicy.SKIP_CONFLICTS);
        assertEquals(1, ctx.protectedEntities);
        assertEquals(0, ctx.conflicts);
        assertNotNull(world.find(uuid(copy)), "the copy is back");
        assertNull(world.find(uuid(source)));
        assertEquals(0, placer.placed, "a put-back is not a placement");
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void aPutBackThatFailsTooCountsAConflictAndItsWarningsAreCapped() {
        List<Step> steps = new ArrayList<>();
        int pairs = EntityJobs.MAX_LOGGED_WARNINGS + 2;
        for (int i = 0; i < pairs; i++) {
            EntityState source = pig(UUID.randomUUID(), 1.5 + i, "pig " + i, 300);
            EntityState copy = pig(UUID.randomUUID(), 21.5 + i, "pig " + i, 300);
            put(copy);
            placer.refuseRestore.add(uuid(source));
            placer.refusePutBack.add(uuid(copy));
            steps.addAll(moveUndo(source, copy));
        }
        EntityWork.Context<Thing> ctx = run(steps, ConflictPolicy.SKIP_CONFLICTS);
        assertEquals(pairs, ctx.protectedEntities, "each source refused");
        assertEquals(pairs, ctx.conflicts, "each copy that could not come back is a conflict");
        assertTrue(world.things.isEmpty(), "nothing came back");
        assertEquals(EntityJobs.MAX_LOGGED_WARNINGS, warnings.size(), "at most " + EntityJobs.MAX_LOGGED_WARNINGS);
    }

    @Test
    void aCopyThatMatchesNoneIsWarnedAboutAPlainRestoreIsNot() {
        EntityState source = pig(UUID.randomUUID(), 1.5, "Wilbur", 300);
        // A copy whose data the move changed with it (as a turned frame's facing): it pairs with nothing.
        EntityState copy = pig(UUID.randomUUID(), 11.5, "Wilbur, turned", 300);
        put(copy);
        placer.refuseRestore.add(uuid(source));
        EntityWork.Context<Thing> ctx = run(moveUndo(source, copy), ConflictPolicy.SKIP_CONFLICTS);
        assertEquals(1, ctx.protectedEntities);
        assertNull(world.find(uuid(copy)), "the unmatched copy stays taken away");
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("matches none"), warnings.get(0));
        // A deleted entity's undo, refused: nothing was taken away for it, so nothing to warn about.
        warnings.clear();
        EntityState deleted = pig(UUID.randomUUID(), 5.5, "Gone", 300);
        placer.refuseRestore.add(uuid(deleted));
        EntityWork.Context<Thing> plain = run(List.of(new Step(uuid(deleted), null, deleted)),
                ConflictPolicy.SKIP_CONFLICTS);
        assertEquals(1, plain.protectedEntities);
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void theCopyAsRecordedIsChosenFirstThenTheExactThenTheLooseOne() {
        EntityState source = pig(UUID.randomUUID(), 1.5, "Wilbur", 300);
        UUID looseId = UUID.randomUUID(), exactId = UUID.randomUUID();
        // First in the job's order: matches but for its air (the game changed it since): loose only.
        put(pig(looseId, 11.5, "Wilbur", 12));
        // Second: exactly as its source.
        put(pig(exactId, 12.5, "Wilbur", 300));
        placer.refuseRestore.add(uuid(source));
        List<Step> steps = List.of(new Step(looseId, pig(looseId, 11.5, "Wilbur", 12), null),
                new Step(exactId, pig(exactId, 12.5, "Wilbur", 300), null), new Step(uuid(source), null, source));
        run(steps, ConflictPolicy.SKIP_CONFLICTS);
        assertNotNull(world.find(exactId), "the exact copy comes back");
        assertNull(world.find(looseId), "the loose one stays taken away");

        // The copy as the step recorded placing it comes before one matching only as it is now.
        world.things.clear();
        EntityState source2 = pig(UUID.randomUUID(), 2.5, "Babe", 300);
        UUID nowId = UUID.randomUUID(), recordedId = UUID.randomUUID();
        put(pig(nowId, 21.5, "Babe", 300)); // recorded with air 7, its air back to 300 now
        put(pig(recordedId, 22.5, "Babe", 5)); // recorded with air 300, its air 5 now
        placer.refuseRestore.add(uuid(source2));
        run(List.of(new Step(nowId, pig(nowId, 21.5, "Babe", 7), null),
                new Step(recordedId, pig(recordedId, 22.5, "Babe", 300), null),
                new Step(uuid(source2), null, source2)), ConflictPolicy.SKIP_CONFLICTS);
        assertNotNull(world.find(recordedId), "the copy recorded as its source comes back");
        assertNull(world.find(nowId));
    }

    @Test
    void aTinkerUndoWhoseTargetIsProtectedKeepsTheEntityOnTheDefaultPlacer() {
        placer.defaultPutBack = true;
        UUID id = UUID.randomUUID();
        EntityState edited = pig(id, 1.5, "Moved", 300);
        put(edited);
        // The edit moved it from x = 120, where cells may no longer be changed.
        EntityState before = pig(id, 120.5, "Kept", 300);
        EntityWork.Context<Thing> ctx = run(List.of(new Step(id, edited, before)), ConflictPolicy.SKIP_CONFLICTS);
        assertEquals(1, ctx.protectedEntities);
        assertEquals(0, ctx.conflicts);
        Thing back = world.find(id);
        assertNotNull(back, "the edited entity is put back where it was");
        assertEquals(1.5, back.state.x());
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    /** What a sweep handed to its sink, and whether the sink keeps it. */
    private static final class Swept implements RecordSink {
        final List<UUID> unsupported = new ArrayList<>();
        boolean keeps = true;
        boolean records = true;

        @Override
        public void record(int x, int y, int z, int before, dev.sculptory.core.buffer.BlockEntityData beforeTile,
                           int after, dev.sculptory.core.buffer.BlockEntityData afterTile) {}

        @Override
        public boolean recordsEntities() {
            return records;
        }

        @Override
        public boolean entityUnsupported(UUID id, EntityState state) {
            unsupported.add(id);
            return keeps;
        }
    }

    /** The section holding block (x, 64, 0). */
    private static long section(int x) {
        return HangingSupport.sectionOf(x, 64, 0);
    }

    /** Runs a sweep over {@code sections}, as a job's last stage would, column by column, two entities a step. */
    private EntityWork.Context<Thing> sweep(List<Long> sections, RecordSink sink) {
        EntityWork.Context<Thing> ctx = new EntityWork.Context<>(world, placer, permits, sink, (x, y, z) -> true);
        return sweep(sections, ctx);
    }

    private EntityWork.Context<Thing> sweep(List<Long> sections, EntityWork.Context<Thing> ctx) {
        HangingSupport.Sweep sweep = new HangingSupport.Sweep(new LongArrayList(sections));
        for (long column : sweep.columns()) {
            do {
                ctx.allowance = 2;
            } while (!sweep.column(column, ctx));
        }
        return ctx;
    }

    /**
     * The sweep after an edit's blocks, over the sections it wrote: an entity within two blocks of them that hangs on
     * nothing any more goes, handed to the sink first; one still hanging, one farther out, one whose wall lies in a chunk
     * not fully loaded (never asked about, so nothing loads) and one the player may not change stay (the last counted as
     * a conflict, left to the game); one the sink cannot keep stays too. A sink that keeps no entities gets no sweep.
     */
    @Test
    void aSweepTakesAlongWhatLostItsWallAndLeavesTheRest() {
        Thing lost = put(pig(UUID.randomUUID(), 4.5, "lost", 300));
        Thing edge = put(pig(UUID.randomUUID(), 17.5, "two blocks beyond the section", 300));
        Thing hanging = put(pig(UUID.randomUUID(), 5.5, "still hanging", 300));
        Thing far = put(pig(UUID.randomUUID(), 18.5, "three blocks beyond", 300));
        Thing remote = put(pig(UUID.randomUUID(), 6.5, "wall in a chunk not loaded", 300));
        Thing guarded = put(pig(UUID.randomUUID(), 100.5, "protected", 300));
        world.unsupported.addAll(List.of(lost.id, edge.id, far.id, guarded.id));
        world.unloadedSupport.add(remote.id);
        Swept sink = new Swept();
        EntityWork.Context<Thing> ctx = sweep(List.of(section(0)), sink);
        assertEquals(Set.of(lost.id, edge.id), Set.copyOf(sink.unsupported));
        assertNull(world.find(lost.id), "taken along");
        assertNull(world.find(edge.id), "within two blocks of the section");
        assertNotNull(world.find(hanging.id), "still hanging");
        assertNotNull(world.find(far.id), "never looked at");
        assertNotNull(world.find(remote.id), "left to the game");
        assertEquals(2, placer.removed);
        assertEquals(0, ctx.conflicts);

        EntityWork.Context<Thing> refused = sweep(List.of(section(100)), sink);
        assertNotNull(world.find(guarded.id), "protected: left to the game");
        assertEquals(1, refused.conflicts);

        Swept full = new Swept();
        full.keeps = false;
        EntityWork.Context<Thing> unkept = sweep(List.of(section(16)), full);
        assertEquals(List.of(far.id), full.unsupported);
        assertNotNull(world.find(far.id), "no history could keep it: left to the game");
        assertEquals(1, unkept.conflicts);
        assertEquals(2, placer.removed, "nothing more removed");

        // A sink that keeps no entities gets no sweep at all: an entity taken would be recorded nowhere.
        Swept none = new Swept();
        none.records = false;
        HangingSupport.Result result = HangingSupport.sweep(world, WriteOptions.DEFAULT, permits, (x, y, z) -> true,
                none, new LongArrayList(List.of(section(16))));
        assertEquals(HangingSupport.Result.NONE, result);
        assertTrue(none.unsupported.isEmpty());
        assertNotNull(world.find(far.id));
    }

    /**
     * An entity an undo kept because it changed since the step (counted as a conflict), which the sweep then takes into
     * the step since the undo took its wall: no longer counted, so no Undo anyway is offered for nothing.
     */
    @Test
    void anEntityKeptAsAConflictAndThenTakenAlongIsNoLongerCounted() {
        Thing kept = put(pig(UUID.randomUUID(), 4.5, "changed since", 300));
        world.unsupported.add(kept.id);
        EntityWork.Context<Thing> ctx = new EntityWork.Context<>(world, placer, permits, new Swept(), (x, y, z) -> true);
        ctx.conflicts = 1;
        ctx.kept.add(kept.id);
        sweep(List.of(section(0)), ctx);
        assertNull(world.find(kept.id), "taken into the step");
        assertEquals(0, ctx.conflicts, "no longer a conflict");
        assertTrue(ctx.kept.isEmpty());
    }

    /**
     * The early sweep after one section is written: an entity near it is taken only once nothing its support box touches
     * is still to be written; the section that unsettled it, written later, takes it (it lies within three of it).
     */
    @Test
    void theEarlySweepWaitsForTheLastSectionNearAnEntity() {
        Thing between = put(pig(UUID.randomUUID(), 15.5, "on the border of two sections", 300));
        world.unsupported.add(between.id);
        EntityWork.Context<Thing> ctx = new EntityWork.Context<>(world, placer, permits, new Swept(), (x, y, z) -> true);
        Set<Long> unwritten = new HashSet<>(List.of(section(16)));
        java.util.function.Predicate<dev.sculptory.core.Box> settled = box -> {
            for (int x = box.min().x(); x <= box.max().x(); x++) {
                if (unwritten.contains(HangingSupport.sectionOf(x, 64, 0))) return false;
            }
            return true;
        };
        ctx.allowance = Integer.MAX_VALUE;
        HangingSupport.section(section(0), ctx, settled);
        assertNotNull(world.find(between.id), "its support box reaches x 16, still to be written");
        unwritten.clear();
        HangingSupport.section(section(16), ctx, settled);
        assertNull(world.find(between.id), "taken when the section beside it is written");
    }

    /** An undo whose target's wall lies in a chunk not fully loaded: put back, never asked about (nothing loads). */
    @Test
    void aTargetWhoseWallIsNotLoadedIsPutBackWithoutAsking() {
        UUID id = UUID.randomUUID();
        EntityState before = pig(id, 1.5, "As it was", 300);
        world.unloadedSupport.add(id);
        EntityWork.Context<Thing> ctx = run(List.of(new Step(id, null, before)), ConflictPolicy.SKIP_CONFLICTS);
        assertNotNull(world.find(id), "put back; the game decides once the chunk is loaded");
        assertEquals(0, ctx.conflicts);
        assertEquals(0, placer.takenBack);
    }

    /**
     * An undo whose target would hang on nothing (its wall went since the step) does not put it back: it is taken back
     * at once, counted as a conflict, and what the step replaced (the same UUID) comes back, so nothing drops off.
     */
    @Test
    void aTargetThatWouldHangOnNothingIsNotPutBack() {
        UUID id = UUID.randomUUID();
        EntityState changed = pig(id, 1.5, "Changed", 300);
        put(changed);
        EntityState before = pig(id, 1.5, "As it was", 300);
        world.unsupported.add(id);
        EntityWork.Context<Thing> ctx = run(List.of(new Step(id, changed, before)), ConflictPolicy.SKIP_CONFLICTS);
        assertEquals(1, placer.takenBack, "spawned, then taken back");
        assertEquals(1, ctx.conflicts);
        assertEquals(0, ctx.protectedEntities);
        Thing back = world.find(id);
        assertNotNull(back, "the entity the undo replaced is back");
        assertEquals(changed, back.state);
    }
}
