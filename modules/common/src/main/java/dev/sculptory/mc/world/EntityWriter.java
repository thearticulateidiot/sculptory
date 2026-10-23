package dev.sculptory.mc.world;

import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.entity.EntityNbt;
import dev.sculptory.core.entity.EntityPlacement;
import dev.sculptory.core.entity.EntitySnapshot;
import dev.sculptory.core.entity.EntityTurn;
import dev.sculptory.core.history.EntityState;
import dev.sculptory.core.nbt.NbtIo;
import dev.sculptory.core.transform.Orientation;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.server.platform.EntityPlacer;
import dev.sculptory.server.platform.WriteOptions;
import dev.sculptory.server.schem.EntitySanitizer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.BlockAttachedEntity;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The single entity write path, the entity counterpart of {@link BlockWriter}:
 * places clipboard entities, puts recorded ones back and removes live ones. Server thread only; the chunks must be
 * loaded with their entities ({@link McEntities#loaded}).
 *
 * <p><b>Placing</b> ({@link #place}) loads the clipboard entity with {@code EntityType.loadEntityWithPassengers} at its
 * new position with new UUIDs (passengers too), turns it and every passenger as vanilla's structure placement does but
 * mirror first, as blocks turn ({@code Entity.applyMirror}, then {@code applyRotation}; they also turn a hanging
 * entity's facing), and spawns it with its passengers ({@code spawnNewEntityAndPassengers}). A hanging entity's block
 * is found from where its centre must land (a painting two wide hangs on a different block once mirrored). Flipped
 * upside down, an item frame's face (and a shulker's) turns over, the
 * pitch turns over, and any other entity is lowered by its height, so its box turned over keeps the space it had; a
 * hanging entity that keeps its face is left out. Untrusted data of an operator-only entity type is reduced as
 * {@link EntitySanitizer} does for players without the operator-NBT right. Data holding more than
 * {@link EntityNbt#MAX_RIDERS} passengers is not loaded; an entity of a type that is never placed
 * ({@link EntityTypeRules#never}, {@link McEntities#kind}) is not spawned, and such a passenger is left out with
 * its own riders (each counted as a failure).
 *
 * <p>Before it spawns anything, {@link #place} and {@link #restore} ask the caller's guard about the entity as it would
 * be spawned (its final position and, for a hanging entity, the block it ended up on): a refused entity is not spawned
 * ({@link Spawn#refused}). The entity is added through the platform ({@link McInternals#addFreshEntityWithPassengers});
 * a server may still turn it or one of its passengers away after that (on Paper another plugin cancelling its spawn
 * event, on NeoForge a mod cancelling its join event), so each one is checked to be in the world afterwards. If one is
 * not, the rest are taken out again and the entity counts as refused: an edit never records an entity that is not
 * there, nor leaves part of one behind it does not record.
 *
 * <p><b>Restoring</b> ({@link #restore}) loads a recorded entity with its own UUIDs (undo and redo); it fails when one of
 * them is taken. <b>Removing</b> ({@link #remove}) discards an entity and the passengers named in its recorded data; other
 * riders (players always) are only dismounted.
 */
public final class EntityWriter implements EntityPlacer<Entity> {
    private static final Logger LOG = LoggerFactory.getLogger("sculptory");
    private static final int MAX_LOGGED_FAILURES = 5;

    private static final Spawn<Entity> FAILED = new Spawn<>(null, false);
    private static final Spawn<Entity> REFUSED = new Spawn<>(null, true);

    private final ServerLevel world;
    private final WriteOptions options;
    private final EntityTypeRules rules;
    private long placed;
    private long removed;
    private long stripped;
    private long failures;
    private long notFlipped;
    /** Entities the server turned away after they were added ({@link #add}); they count as refused. */
    private long turnedAway;

    /**
     * @param options whether operator-only entity data may be kept ({@code allowOperatorNbt}; trusted data is kept for
     *     everyone when {@code trustCapturedTiles})
     * @param rules which types are operator-only and which are never placed ({@link EntityTypeRules})
     */
    public EntityWriter(ServerLevel world, WriteOptions options, EntityTypeRules rules) {
        this.world = Objects.requireNonNull(world);
        this.options = Objects.requireNonNull(options);
        this.rules = Objects.requireNonNull(rules);
    }

    public ServerLevel world() {
        return world;
    }

    /** Entities placed or put back. */
    @Override
    public long placed() {
        return placed;
    }

    @Override
    public long removed() {
        return removed;
    }

    /** Entities whose operator-only data was left out. */
    @Override
    public long stripped() {
        return stripped;
    }

    /**
     * Entities that could not be placed: an unknown type, bad data, a UUID in use, a type that is never placed, more
     * passengers than allowed, or a placement history could not record (taken back at once).
     */
    @Override
    public long failures() {
        return failures;
    }

    /**
     * Entities left out of a flip upside down because they cannot hang the other way up (also counted in
     * {@link #failures}).
     */
    public long notFlipped() {
        return notFlipped;
    }

    /**
     * Places a clipboard entity at {@code where} (its position after {@code t}), turned by {@code t}, with new UUIDs,
     * if {@code allowed} accepts it as it would be spawned.
     */
    @Override
    public Spawn<Entity> place(EntitySnapshot entity, EntityPlacement.Placed where, Orientation orientation,
                               Predicate<Entity> allowed) {
        Transform t = orientation.transform();
        boolean free = !orientation.isExact();
        dev.sculptory.core.nbt.NbtCompound data;
        try {
            data = EntityNbt.decode(entity.nbt());
        } catch (IOException e) {
            return failed(entity.typeId(), "its data does not decode");
        }
        if (rules.never(entity.typeId())) return failed(entity.typeId(), "entities of its type are never placed");
        if (!(entity.trusted() && options.trustCapturedTiles()) && !options.allowOperatorNbt()) {
            int[] count = {0};
            data = strip(entity.typeId(), data, count);
            stripped += count[0];
        }
        // Flipped upside down, an item frame on a floor hangs from the ceiling (a shulker on one clings to the other).
        int hangsFacing = -1;
        if (free) {
            // A free turn: the faces it hangs by and a display's own rotation are turned in its data, from the yaw and
            // pitch the exact part gives it.
            data = EntityTurn.turnedData(entity.typeId(), data, EntityPlacement.yaw(entity.yaw(), t),
                    EntityPlacement.pitch(entity.pitch(), t), orientation.matrix(), orientation.rotationMatrix());
            if (data == null) return failed(entity.typeId(), "it cannot hang on a floor or ceiling");
        } else if (t.upsideDown()) {
            data = EntityNbt.flippedAttachment(data);
            hangsFacing = EntityNbt.verticalFace(data, "Facing");
        }
        BlockPos guess = where.attached() != null
                ? new BlockPos(where.attached().x(), where.attached().y(), where.attached().z())
                : BlockPos.containing(where.x(), where.y(), where.z());
        CompoundTag nbt;
        try {
            dev.sculptory.core.nbt.NbtCompound spawn = EntityNbt.spawnCompound(entity.typeId(), data, where.x(),
                    where.y(), where.z(), entity.yaw(), entity.pitch(),
                    new dev.sculptory.core.BlockPos(guess.getX(), guess.getY(), guess.getZ()));
            nbt = McEntities.compound(NbtIo.toBytes(spawn));
        } catch (IOException | IllegalArgumentException e) {
            return failed(entity.typeId(), "its data does not convert: " + e.getMessage());
        }
        Entity root = load(nbt, entity.typeId());
        if (root == null) return FAILED;
        if (McEntities.kind(root) == McEntities.Kind.NEVER) {
            return failed(entity.typeId(), "entities of its kind are never placed");
        }
        dropNeverRiders(root);
        if (hangsFacing >= 0 && root instanceof HangingEntity decoration
                && decoration.getDirection().get3DDataValue() != hangsFacing) {
            // It ignored the flipped face: it cannot hang the other way up, so it is left out.
            notFlipped++;
            return failed(entity.typeId(), "it cannot hang upside down");
        }
        if (free) {
            double[] rotation = orientation.rotationMatrix();
            turnFree(root, t, rotation);
            for (Entity rider : root.getIndirectPassengers()) turnFree(rider, t, rotation);
        } else {
            turn(root, t);
            for (Entity rider : root.getIndirectPassengers()) turn(rider, t);
        }
        if (root instanceof BlockAttachedEntity hanging) {
            hang(hanging, where, guess);
            // Turned by a free angle, the block it hung on may not be behind it any more: it would drop off (and its item
            // with it) once the game checks, so it is left out instead.
            if (free && !hanging.survives()) {
                return failed(entity.typeId(), "it has no wall to hang on once turned");
            }
        } else {
            // Flipped, where is the image of its feet: the top of where its box goes.
            double y = t.upsideDown() && !free ? EntityPlacement.flippedFeet(where.y(), root.getBbHeight()) : where.y();
            root.moveTo(where.x(), y, where.z(), root.getYRot(), root.getXRot());
            root.setYHeadRot(root.getYRot());
            root.setYBodyRot(root.getYRot());
        }
        for (Entity rider : root.getIndirectPassengers()) {
            Entity vehicle = rider.getVehicle();
            if (vehicle != null) vehicle.positionRider(rider);
        }
        if (!allowed.test(root)) return REFUSED;
        return add(root, entity.typeId(), true);
    }

    /**
     * Puts a recorded entity back as it was, with its UUIDs (undo, redo), if {@code allowed} accepts it as it would be
     * spawned.
     */
    @Override
    public Spawn<Entity> restore(EntityState state, Predicate<Entity> allowed) {
        return restore(state, allowed, true);
    }

    /**
     * Puts back, with its UUIDs, an entity a history step of this job took away, because what was to stand in its place
     * could not be put there: the step is then a no-op for it, so it does not count as placed (its removal was counted,
     * and the refusal is the step's). The blocks around it may have changed since it was taken away (the same job
     * restored the blocks there): a hanging entity whose wall is gone, or a living one whose head would be inside solid
     * blocks, is not put back (it would pop off and drop its item, or suffocate), and that counts as a failure.
     */
    @Override
    public Spawn<Entity> putBack(EntityState state, Predicate<Entity> allowed) {
        return restore(state, allowed, false);
    }

    private Spawn<Entity> restore(EntityState state, Predicate<Entity> allowed, boolean count) {
        CompoundTag nbt;
        try {
            nbt = McEntities.compound(state.nbt());
        } catch (IOException e) {
            return failed(state.typeId(), "its data does not decode");
        }
        if (EntityNbt.riderCount(state.nbt()) > EntityNbt.MAX_RIDERS) {
            return failed(state.typeId(), "it carries more than " + EntityNbt.MAX_RIDERS + " passengers");
        }
        Entity root = load(nbt, state.typeId());
        if (root == null) return FAILED;
        if (!count && !lasts(root)) {
            return failed(state.typeId(), "it would not last where it was taken from (nothing to hang on, or inside "
                    + "solid blocks)");
        }
        if (!allowed.test(root)) return REFUSED;
        return add(root, state.typeId(), count);
    }

    /** Whether {@code entity} would last where it stands: a hanging one on its wall, a living one not inside a wall. */
    private static boolean lasts(Entity entity) {
        if (entity instanceof BlockAttachedEntity hanging) return hanging.survives();
        return !(entity instanceof net.minecraft.world.entity.LivingEntity) || !entity.isInWall();
    }

    /**
     * Adds {@code root} with its passengers and checks each is in the world now. The list is taken first: a passenger
     * the server turns away is discarded, which dismounts it. When one is missing, the others are taken out again and
     * the whole entity is refused. {@code count}: a placement or restore (counted as placed), not a put-back.
     */
    private Spawn<Entity> add(Entity root, String typeId, boolean count) {
        List<Entity> tree = root.getSelfAndPassengers().toList();
        if (!McInternals.get().addFreshEntityWithPassengers(world, root)) {
            return failed(typeId, "one of its UUIDs is in use");
        }
        Entity missing = null;
        for (Entity entity : tree) {
            if (!inWorld(entity)) {
                missing = entity;
                break;
            }
        }
        if (missing == null) {
            if (count) placed++;
            return new Spawn<>(root, false);
        }
        for (Entity entity : tree) {
            if (!entity.isRemoved()) entity.discard();
        }
        if (turnedAway++ < MAX_LOGGED_FAILURES) {
            LOG.warn("Sculptory: the server turned away a {} it was placing ({}{}); it is left out", typeId,
                    McEntities.typeId(missing), missing == root ? "" : ", a passenger");
        }
        return REFUSED;
    }

    /** Whether {@code entity} is in this writer's world: added, not removed, and the entity its UUID finds. */
    private boolean inWorld(Entity entity) {
        return !entity.isRemoved() && world.getEntity(entity.getUUID()) == entity;
    }

    /**
     * Takes back an entity this writer just spawned (with everything riding it) because it cannot be recorded, so no
     * edit leaves an entity undo does not know about; it counts as a failure.
     */
    @Override
    public void takeBack(Entity root) {
        for (Entity rider : root.getIndirectPassengers()) {
            if (!rider.isAlwaysTicking()) rider.discard();
        }
        root.discard();
        placed--;
        failed(McEntities.typeId(root), "history could not record it");
    }

    /** Leaves out passengers of a kind that is never placed (with their own riders), each counted as a failure. */
    private void dropNeverRiders(Entity root) {
        List<Entity> refused = new ArrayList<>();
        for (Entity rider : root.getIndirectPassengers()) {
            if (McEntities.kind(rider) == McEntities.Kind.NEVER || rules.never(McEntities.typeId(rider))) {
                refused.add(rider);
            }
        }
        for (Entity rider : refused) {
            if (rider.isPassenger()) rider.stopRiding();
            failed(McEntities.typeId(rider), "passengers of its kind are never placed");
        }
    }

    /**
     * Removes {@code root} and the passengers (at any depth) whose UUIDs {@code recorded} names (what a step took along or
     * placed with it); other riders, players always, are dismounted and stay.
     */
    @Override
    public void remove(Entity root, EntityState recorded) {
        Set<UUID> along = recorded == null ? Set.of() : riderIds(recorded);
        List<Entity> riders = new ArrayList<>();
        for (Entity rider : root.getIndirectPassengers()) {
            if (!rider.isAlwaysTicking() && along.contains(rider.getUUID())) riders.add(rider);
        }
        for (Entity rider : riders) rider.discard();
        root.discard();
        removed++;
    }

    /** The entity as it is now, with the passengers {@link EntityFilter#ALL} takes (players are never saved). */
    public static EntityState live(Entity entity) {
        return McEntities.state(entity, EntityFilter.ALL);
    }

    private dev.sculptory.core.nbt.NbtCompound strip(String typeId, dev.sculptory.core.nbt.NbtCompound data,
                                                        int[] count) {
        dev.sculptory.core.nbt.NbtCompound whole = data.toBuilder().putString("id", typeId).build();
        dev.sculptory.core.nbt.NbtCompound clean = EntitySanitizer.sanitize(whole, rules::operator, count);
        return clean == whole ? data : clean.toBuilder().remove("id").build();
    }

    private Entity load(CompoundTag nbt, String typeId) {
        Entity root;
        try {
            root = EntityType.loadEntityRecursive(nbt, world, entity -> entity);
        } catch (RuntimeException e) {
            failed(typeId, "loading it failed: " + e);
            return null;
        }
        if (root == null) failed(typeId, "its type is unknown or it failed to load");
        return root;
    }

    /**
     * Mirror first, then turn, as blocks do: vanilla turns the heading (and a hanging entity's facing). Flipped upside
     * down, the pitch turns over (the heading stays), except for a decoration (an item frame, a painting): its pitch
     * follows its face, which {@link EntityNbt#flippedAttachment} already swapped, so turning it again would undo that.
     */
    private static void turn(Entity entity, Transform t) {
        if (t.upsideDown() && !(entity instanceof HangingEntity)) {
            entity.setXRot(EntityPlacement.pitch(entity.getXRot(), t));
        }
        if (t.mirror() != dev.sculptory.core.transform.Mirror.NONE) {
            Mirror mirror = t.mirror() == dev.sculptory.core.transform.Mirror.X ? Mirror.FRONT_BACK
                    : Mirror.LEFT_RIGHT;
            entity.setYRot(entity.mirror(mirror));
        }
        if (t.quarterTurnsCw() != 0) {
            Rotation rotation = switch (t.quarterTurnsCw()) {
                case 1 -> Rotation.CLOCKWISE_90;
                case 2 -> Rotation.CLOCKWISE_180;
                default -> Rotation.COUNTERCLOCKWISE_90;
            };
            entity.setYRot(entity.rotate(rotation));
        }
    }

    /**
     * A free turn: a decoration's face is already turned in its data; anything else turns by the exact part as
     * {@link #turn} does, then a heading turns by the free rotation ({@link EntityTurn#yaw}), except a display's, whose
     * own rotation took the turn.
     */
    private static void turnFree(Entity entity, Transform t, double[] rotation) {
        if (entity instanceof HangingEntity) return;
        turn(entity, t);
        if (entity instanceof net.minecraft.world.entity.Display) return;
        entity.setYRot(EntityTurn.yaw(entity.getYRot(), rotation));
    }

    /**
     * Hangs an entity (its facing already turned) on the block that puts its centre where the transform puts it: its
     * centre is a fixed offset from its block for a given facing and size, so one trial placement finds the block.
     */
    private static void hang(BlockAttachedEntity hanging, EntityPlacement.Placed where, BlockPos guess) {
        hanging.setPos(guess.getX(), guess.getY(), guess.getZ());
        Vec3 at = hanging.position();
        BlockPos block = guess.offset((int) Math.round(where.x() - at.x), (int) Math.round(where.y() - at.y),
                (int) Math.round(where.z() - at.z));
        hanging.setPos(block.getX(), block.getY(), block.getZ());
        if (hanging instanceof HangingEntity decoration) {
            Direction facing = decoration.getDirection();
            if (facing.getAxis().isHorizontal()) decoration.setYRot(facing.get2DDataValue() * 90f);
        }
        hanging.setOldPosAndRot();
    }

    /** The UUIDs of the passengers (at any depth) a recorded entity's data names. */
    static Set<UUID> riderIds(EntityState state) {
        Set<UUID> ids = new HashSet<>();
        try {
            collectRiders(McEntities.compound(state.nbt()), ids, 0);
        } catch (IOException e) {
            // No riders known: they are dismounted, never lost.
        }
        return ids;
    }

    private static void collectRiders(CompoundTag nbt, Set<UUID> ids, int depth) {
        if (depth > EntityNbt.MAX_PASSENGER_DEPTH || !nbt.contains("Passengers", Tag.TAG_LIST)) return;
        ListTag riders = nbt.getList("Passengers", Tag.TAG_COMPOUND);
        for (int i = 0; i < riders.size(); i++) {
            CompoundTag rider = riders.getCompound(i);
            if (rider.hasUUID("UUID")) ids.add(NbtUtils.loadUUID(rider.get("UUID")));
            collectRiders(rider, ids, depth + 1);
        }
    }

    private Spawn<Entity> failed(String typeId, String why) {
        if (failures++ < MAX_LOGGED_FAILURES) {
            LOG.warn("Sculptory could not place a {} ({}); it is left out", typeId, why);
        }
        return FAILED;
    }
}
