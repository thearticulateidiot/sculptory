package dev.sculptory.fabric.world;

import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.entity.EntityNbt;
import dev.sculptory.core.entity.EntityPlacement;
import dev.sculptory.core.entity.EntitySnapshot;
import dev.sculptory.core.history.EntityState;
import dev.sculptory.core.nbt.NbtIo;
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
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.AbstractDecorationEntity;
import net.minecraft.entity.decoration.BlockAttachedEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The single entity write path, the entity counterpart of {@link BlockWriter}:
 * places clipboard entities, puts recorded ones back and removes live ones. Server thread only; the chunks must be
 * loaded with their entities ({@link FabricEntities#loaded}).
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
 * ({@link EntityTypeRules#never}, {@link FabricEntities#kind}) is not spawned, and such a passenger is left out with
 * its own riders (each counted as a failure).
 *
 * <p>Before it spawns anything, {@link #place} and {@link #restore} ask the caller's guard about the entity as it would
 * be spawned (its final position and, for a hanging entity, the block it ended up on): a refused entity is not spawned
 * ({@link Spawn#refused}).
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

    private final ServerWorld world;
    private final WriteOptions options;
    private final EntityTypeRules rules;
    private long placed;
    private long removed;
    private long stripped;
    private long failures;
    private long notFlipped;

    /**
     * @param options whether operator-only entity data may be kept ({@code allowOperatorNbt}; trusted data is kept for
     *     everyone when {@code trustCapturedTiles})
     * @param rules which types are operator-only and which are never placed ({@link EntityTypeRules})
     */
    public EntityWriter(ServerWorld world, WriteOptions options, EntityTypeRules rules) {
        this.world = Objects.requireNonNull(world);
        this.options = Objects.requireNonNull(options);
        this.rules = Objects.requireNonNull(rules);
    }

    public ServerWorld world() {
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
    public Spawn<Entity> place(EntitySnapshot entity, EntityPlacement.Placed where, Transform t,
                               Predicate<Entity> allowed) {
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
        if (t.upsideDown()) {
            data = EntityNbt.flippedAttachment(data);
            hangsFacing = EntityNbt.verticalFace(data, "Facing");
        }
        BlockPos guess = where.attached() != null
                ? new BlockPos(where.attached().x(), where.attached().y(), where.attached().z())
                : BlockPos.ofFloored(where.x(), where.y(), where.z());
        NbtCompound nbt;
        try {
            dev.sculptory.core.nbt.NbtCompound spawn = EntityNbt.spawnCompound(entity.typeId(), data, where.x(),
                    where.y(), where.z(), entity.yaw(), entity.pitch(),
                    new dev.sculptory.core.BlockPos(guess.getX(), guess.getY(), guess.getZ()));
            nbt = FabricEntities.compound(NbtIo.toBytes(spawn));
        } catch (IOException | IllegalArgumentException e) {
            return failed(entity.typeId(), "its data does not convert: " + e.getMessage());
        }
        Entity root = load(nbt, entity.typeId());
        if (root == null) return FAILED;
        if (FabricEntities.kind(root) == FabricEntities.Kind.NEVER) {
            return failed(entity.typeId(), "entities of its kind are never placed");
        }
        dropNeverRiders(root);
        if (hangsFacing >= 0 && root instanceof AbstractDecorationEntity decoration
                && decoration.getHorizontalFacing().getId() != hangsFacing) {
            // It ignored the flipped face: it cannot hang the other way up, so it is left out.
            notFlipped++;
            return failed(entity.typeId(), "it cannot hang upside down");
        }
        turn(root, t);
        for (Entity rider : root.getPassengersDeep()) turn(rider, t);
        if (root instanceof BlockAttachedEntity hanging) {
            hang(hanging, where, guess);
        } else {
            // Flipped, where is the image of its feet: the top of where its box goes.
            double y = t.upsideDown() ? EntityPlacement.flippedFeet(where.y(), root.getHeight()) : where.y();
            root.refreshPositionAndAngles(where.x(), y, where.z(), root.getYaw(), root.getPitch());
            root.setHeadYaw(root.getYaw());
            root.setBodyYaw(root.getYaw());
        }
        for (Entity rider : root.getPassengersDeep()) {
            Entity vehicle = rider.getVehicle();
            if (vehicle != null) vehicle.updatePassengerPosition(rider);
        }
        if (!allowed.test(root)) return REFUSED;
        if (!world.spawnNewEntityAndPassengers(root)) return failed(entity.typeId(), "one of its UUIDs is in use");
        placed++;
        return new Spawn<>(root, false);
    }

    /**
     * Puts a recorded entity back as it was, with its UUIDs (undo, redo), if {@code allowed} accepts it as it would be
     * spawned.
     */
    @Override
    public Spawn<Entity> restore(EntityState state, Predicate<Entity> allowed) {
        NbtCompound nbt;
        try {
            nbt = FabricEntities.compound(state.nbt());
        } catch (IOException e) {
            return failed(state.typeId(), "its data does not decode");
        }
        if (EntityNbt.riderCount(state.nbt()) > EntityNbt.MAX_RIDERS) {
            return failed(state.typeId(), "it carries more than " + EntityNbt.MAX_RIDERS + " passengers");
        }
        Entity root = load(nbt, state.typeId());
        if (root == null) return FAILED;
        if (!allowed.test(root)) return REFUSED;
        if (!world.spawnNewEntityAndPassengers(root)) return failed(state.typeId(), "one of its UUIDs is in use");
        placed++;
        return new Spawn<>(root, false);
    }

    /**
     * Takes back an entity this writer just spawned (with everything riding it) because it cannot be recorded, so no
     * edit leaves an entity undo does not know about; it counts as a failure.
     */
    @Override
    public void takeBack(Entity root) {
        for (Entity rider : root.getPassengersDeep()) {
            if (!rider.isPlayer()) rider.discard();
        }
        root.discard();
        placed--;
        failed(FabricEntities.typeId(root), "history could not record it");
    }

    /** Leaves out passengers of a kind that is never placed (with their own riders), each counted as a failure. */
    private void dropNeverRiders(Entity root) {
        List<Entity> refused = new ArrayList<>();
        for (Entity rider : root.getPassengersDeep()) {
            if (FabricEntities.kind(rider) == FabricEntities.Kind.NEVER || rules.never(FabricEntities.typeId(rider))) {
                refused.add(rider);
            }
        }
        for (Entity rider : refused) {
            if (rider.hasVehicle()) rider.stopRiding();
            failed(FabricEntities.typeId(rider), "passengers of its kind are never placed");
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
        for (Entity rider : root.getPassengersDeep()) {
            if (!rider.isPlayer() && along.contains(rider.getUuid())) riders.add(rider);
        }
        for (Entity rider : riders) rider.discard();
        root.discard();
        removed++;
    }

    /** The entity as it is now, with the passengers {@link EntityFilter#ALL} takes (players are never saved). */
    public static EntityState live(Entity entity) {
        return FabricEntities.state(entity, EntityFilter.ALL);
    }

    private dev.sculptory.core.nbt.NbtCompound strip(String typeId, dev.sculptory.core.nbt.NbtCompound data,
                                                        int[] count) {
        dev.sculptory.core.nbt.NbtCompound whole = data.toBuilder().putString("id", typeId).build();
        dev.sculptory.core.nbt.NbtCompound clean = EntitySanitizer.sanitize(whole, rules::operator, count);
        return clean == whole ? data : clean.toBuilder().remove("id").build();
    }

    private Entity load(NbtCompound nbt, String typeId) {
        Entity root;
        try {
            root = EntityType.loadEntityWithPassengers(nbt, world, entity -> entity);
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
        if (t.upsideDown() && !(entity instanceof AbstractDecorationEntity)) {
            entity.setPitch(EntityPlacement.pitch(entity.getPitch(), t));
        }
        if (t.mirror() != dev.sculptory.core.transform.Mirror.NONE) {
            BlockMirror mirror = t.mirror() == dev.sculptory.core.transform.Mirror.X ? BlockMirror.FRONT_BACK
                    : BlockMirror.LEFT_RIGHT;
            entity.setYaw(entity.applyMirror(mirror));
        }
        if (t.quarterTurnsCw() != 0) {
            BlockRotation rotation = switch (t.quarterTurnsCw()) {
                case 1 -> BlockRotation.CLOCKWISE_90;
                case 2 -> BlockRotation.CLOCKWISE_180;
                default -> BlockRotation.COUNTERCLOCKWISE_90;
            };
            entity.setYaw(entity.applyRotation(rotation));
        }
    }

    /**
     * Hangs an entity (its facing already turned) on the block that puts its centre where the transform puts it: its
     * centre is a fixed offset from its block for a given facing and size, so one trial placement finds the block.
     */
    private static void hang(BlockAttachedEntity hanging, EntityPlacement.Placed where, BlockPos guess) {
        hanging.setPosition(guess.getX(), guess.getY(), guess.getZ());
        Vec3d at = hanging.getPos();
        BlockPos block = guess.add((int) Math.round(where.x() - at.x), (int) Math.round(where.y() - at.y),
                (int) Math.round(where.z() - at.z));
        hanging.setPosition(block.getX(), block.getY(), block.getZ());
        if (hanging instanceof AbstractDecorationEntity decoration) {
            Direction facing = decoration.getHorizontalFacing();
            if (facing.getAxis().isHorizontal()) decoration.setYaw(facing.getHorizontal() * 90f);
        }
        hanging.resetPosition();
    }

    /** The UUIDs of the passengers (at any depth) a recorded entity's data names. */
    static Set<UUID> riderIds(EntityState state) {
        Set<UUID> ids = new HashSet<>();
        try {
            collectRiders(FabricEntities.compound(state.nbt()), ids, 0);
        } catch (IOException e) {
            // No riders known: they are dismounted, never lost.
        }
        return ids;
    }

    private static void collectRiders(NbtCompound nbt, Set<UUID> ids, int depth) {
        if (depth > EntityNbt.MAX_PASSENGER_DEPTH || !nbt.contains("Passengers", NbtElement.LIST_TYPE)) return;
        NbtList riders = nbt.getList("Passengers", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < riders.size(); i++) {
            NbtCompound rider = riders.getCompound(i);
            if (rider.containsUuid("UUID")) ids.add(NbtHelper.toUuid(rider.get("UUID")));
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
