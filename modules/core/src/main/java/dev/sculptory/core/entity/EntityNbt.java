package dev.sculptory.core.entity;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.nbt.NbtCompound;
import dev.sculptory.core.nbt.NbtIo;
import dev.sculptory.core.nbt.NbtLimits;
import dev.sculptory.core.nbt.NbtList;
import dev.sculptory.core.nbt.NbtTag;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Entity NBT as the core model handles it (vanilla key names, checked against 1.21.1's {@code Entity.writeNbt} and
 * {@code BlockAttachedEntity}): what a clipboard keeps of an entity, the compound a placement loads, and the form undo
 * and redo compare. Pure Java; safe off the server thread.
 */
public final class EntityNbt {
    /**
     * For one entity's NBT: like a block entity's (8 MiB, 256k tags, 32 MiB heap). History keeps no entity these do not
     * decode ({@code EntityState.MAX_NBT_BYTES} is the same size), so undo can always compare what it recorded.
     */
    public static final NbtLimits LIMITS = NbtLimits.BLOCK_ENTITY;
    /** Passengers nested inside passengers kept at most this deep (vanilla limits riding chains only by use). */
    public static final int MAX_PASSENGER_DEPTH = 16;
    /**
     * Passengers one entity keeps at most, at any depth (a boat holds two, a minecart one): riders past it are left
     * out of copies and files, and data holding more is not placed. It bounds the work one placed entity can cost
     * (vanilla adds riders one by one, copying the passenger list each time).
     */
    public static final int MAX_RIDERS = 16;

    /** Keys a clipboard does not keep at the top of an entity: who it is and where (they are set per placement). */
    public static final Set<String> PLACEMENT_KEYS =
            Set.of("id", "UUID", "Pos", "Motion", "Rotation", "TileX", "TileY", "TileZ");
    /** Keys a clipboard does not keep of a passenger: its own identity and place (it rides its vehicle). */
    public static final Set<String> PASSENGER_KEYS = Set.of("UUID", "Pos", "Motion");

    /**
     * Keys undo and redo leave out when they compare an entity with what the step left there: the game rewrites them
     * from time, physics or AI without anyone touching the entity, and they hold no items, text or settings a player
     * made (the same rule as for block entities). Where an entity stands and
     * looks is among them, so a mob that wandered or a boat that drifted is still the one the step placed; health,
     * effects, memories and breeding timers of mobs too, and their attributes, which the game derives from their
     * equipment and effects on their next tick (so an armor stand placed with a helmet gains its armor bonus then).
     * Also the counters and AI state vanilla 1.21.1 mobs save and change by themselves as time passes (found in their
     * {@code writeCustomDataToNbt}: a chicken's egg timer, a trader's despawn delay, a bee's pollination, anger), and an
     * interaction entity's record of its last use. What players make stays compared: names, equipment and items, tags,
     * variants and colours, taming, trades and experience, an item frame's item.
     */
    public static final Set<String> VOLATILE_KEYS = Set.of(
            "Pos", "Motion", "Rotation", "FallDistance", "Fire", "Air", "OnGround", "PortalCooldown", "TicksFrozen",
            // Living entities.
            "HurtTime", "HurtByTimestamp", "DeathTime", "FallFlying", "Brain", "Health", "AbsorptionAmount", "attributes",
            "active_effects", "SleepingX", "SleepingY", "SleepingZ",
            // Breeding and growing up.
            "Age", "ForcedAge", "InLove", "LoveCause",
            // Anger (neutral mobs, the warden).
            "AngerTime", "AngryAt", "anger",
            // Per-kind timers and AI state.
            "EggLayTime", "DespawnDelay", "TimeInOverworld", "CannotHunt", "scute_time", "DuplicationCooldown",
            "listener", "TicksSincePollination", "CannotEnterHiveTicks", "CropsGrownSincePollination", "HasNectar",
            "hive_pos", "flower_pos", "MoreCarrotTicks", "Moistness", "GotFish", "LastPoseTick", "wasOnGround",
            "AttackTick", "RoarTick", "StunTick", "SpellTicks", "LifeTicks", "Lifetime", "SkeletonTrapTime",
            "DragonDeathTime", "InWaterTime", "DrownedConversionTime", "StrayConversionTime", "AX", "AY", "AZ", "Peek",
            "FoodLevel", "RestocksToday", "LastRestock", "LastGossipDecay", "Gossips", "Fuel",
            // Poses and plans mobs take up by themselves: a fox sleeping, sitting or crouching (a pet sitting too); a
            // turtle's home, trip and egg; a trader's wander target; a pufferfish puffing up; an armadillo rolling
            // up; what an enderman carries; a mob made persistent by the game (it picked something up). A villager's
            // profession stays compared: a player can choose it (a workstation).
            "Sleeping", "Sitting", "Crouching", "HomePosX", "HomePosY", "HomePosZ", "TravelPosX", "TravelPosY",
            "TravelPosZ", "HasEgg", "wander_target", "PuffState", "state",
            "carriedBlockState", "PersistenceRequired",
            // An interaction entity's last attack and use.
            "attack", "interaction");

    private EntityNbt() {}

    /** Decodes entity NBT under {@link #LIMITS}. */
    public static NbtCompound decode(byte[] nbt) throws IOException {
        return NbtIo.fromBytes(Objects.requireNonNull(nbt), LIMITS);
    }

    /**
     * What a clipboard keeps of a whole entity compound (as {@code Entity.saveSelfNbt} writes it): the compound without
     * {@link #PLACEMENT_KEYS}, its passengers (recursively) without {@link #PASSENGER_KEYS}, at most
     * {@link #MAX_RIDERS} of them (the first, depth first) and at most {@link #MAX_PASSENGER_DEPTH} deep.
     */
    public static NbtCompound snapshotData(NbtCompound entity) {
        return snapshotData(entity, new int[1]);
    }

    /** {@link #snapshotData(NbtCompound)}, adding the passengers (at any depth) it left out to {@code dropped[0]}. */
    public static NbtCompound snapshotData(NbtCompound entity, int[] dropped) {
        return strip(entity, PLACEMENT_KEYS, 0, new int[] {MAX_RIDERS}, dropped);
    }

    /**
     * Strips {@code keys} from {@code compound} and keeps as many of its riders as {@code room[0]} allows (each kept
     * rider takes one), depth first; a rider left out counts in {@code dropped[0]} with everything riding it.
     */
    private static NbtCompound strip(NbtCompound compound, Set<String> keys, int depth, int[] room, int[] dropped) {
        NbtCompound.Builder out = compound.toBuilder();
        for (String key : keys) out.remove(key);
        List<NbtCompound> riders = riders(compound);
        List<NbtCompound> kept = new ArrayList<>(Math.min(riders.size(), MAX_RIDERS));
        for (NbtCompound rider : riders) {
            if (room[0] > 0 && depth < MAX_PASSENGER_DEPTH) {
                room[0]--;
                kept.add(strip(rider, PASSENGER_KEYS, depth + 1, room, dropped));
            } else {
                dropped[0] += 1 + riderCount(rider);
            }
        }
        if (kept.isEmpty()) {
            out.remove("Passengers");
        } else {
            out.put("Passengers", NbtList.of(NbtTag.COMPOUND, kept));
        }
        return out.build();
    }

    /**
     * An entity type id as the game reads it when it loads an entity ({@code Identifier.of}): without a namespace, or
     * with an empty one, it is {@code minecraft:}'s ({@code "pig"} and {@code ":pig"} load as {@code minecraft:pig});
     * {@code null} gives {@code ""}. Compare type ids from files only in this form.
     */
    public static String loadedId(String id) {
        if (id == null) return "";
        int colon = id.indexOf(':');
        if (colon < 0) return "minecraft:" + id;
        return colon == 0 ? "minecraft" + id : id;
    }

    /** The compounds of an entity's {@code Passengers} list (none when it is absent or not a list of compounds). */
    public static List<NbtCompound> riders(NbtCompound entity) {
        NbtList passengers = entity.getList("Passengers");
        List<NbtCompound> riders = passengers == null ? null : passengers.compounds();
        return riders == null ? List.of() : riders;
    }

    /**
     * The passengers of an entity compound at any depth (the entity itself not counted). Iterative, so a deep or
     * crafted chain costs no stack (the NBT decoder already bounded its size).
     */
    public static int riderCount(NbtCompound entity) {
        int count = 0;
        ArrayDeque<NbtCompound> open = new ArrayDeque<>();
        open.push(entity);
        while (!open.isEmpty()) {
            for (NbtCompound rider : riders(open.pop())) {
                count++;
                open.push(rider);
            }
        }
        return count;
    }

    /** {@link #riderCount(NbtCompound)} of an entity's NBT bytes, or -1 when they do not decode. */
    public static int riderCount(byte[] nbt) {
        try {
            return riderCount(decode(nbt));
        } catch (IOException undecodable) {
            return -1;
        }
    }

    /**
     * The compound a placement loads: {@code data} (a clipboard's {@link EntitySnapshot#nbt}) with {@code id},
     * {@code Pos} (x, y, z), {@code Motion} (still) and {@code Rotation} (yaw, pitch) set, {@code TileX}/{@code TileY}/
     * {@code TileZ} set to {@code attached} when given (a hanging entity checks them against its position when it
     * loads), and every passenger placed at the vehicle's position (vanilla saves riders there too). No {@code UUID}:
     * the game gives each loaded entity a new one.
     *
     * @throws IllegalArgumentException if {@code data} holds more than {@link #MAX_RIDERS} passengers
     */
    public static NbtCompound spawnCompound(String typeId, NbtCompound data, double x, double y, double z, float yaw,
                                            float pitch, BlockPos attached) {
        Objects.requireNonNull(typeId);
        int carried = riderCount(data);
        if (carried > MAX_RIDERS) throw new IllegalArgumentException(carried + " passengers > " + MAX_RIDERS);
        NbtCompound.Builder out = NbtCompound.builder().putString("id", typeId);
        data.entries().forEach((key, value) -> {
            if (!PLACEMENT_KEYS.contains(key) && !key.equals("Passengers")) out.put(key, value);
        });
        out.put("Pos", doubles(x, y, z));
        out.put("Motion", doubles(0, 0, 0));
        out.put("Rotation", NbtList.of(NbtTag.FLOAT, List.of(new NbtTag.NbtFloat(yaw), new NbtTag.NbtFloat(pitch))));
        if (attached != null) {
            out.putInt("TileX", attached.x()).putInt("TileY", attached.y()).putInt("TileZ", attached.z());
        }
        NbtList passengers = data.getList("Passengers");
        List<NbtCompound> riders = passengers == null ? null : passengers.compounds();
        if (riders != null && !riders.isEmpty()) {
            List<NbtCompound> placed = new ArrayList<>(riders.size());
            for (NbtCompound rider : riders) placed.add(riderAt(rider, x, y, z, 0));
            out.put("Passengers", NbtList.of(NbtTag.COMPOUND, placed));
        }
        return out.build();
    }

    private static NbtCompound riderAt(NbtCompound rider, double x, double y, double z, int depth) {
        NbtCompound.Builder out = rider.toBuilder().remove("UUID");
        out.put("Pos", doubles(x, y, z));
        out.put("Motion", doubles(0, 0, 0));
        NbtList passengers = rider.getList("Passengers");
        List<NbtCompound> riders = passengers == null ? null : passengers.compounds();
        if (riders == null || riders.isEmpty() || depth >= MAX_PASSENGER_DEPTH) {
            out.remove("Passengers");
        } else {
            List<NbtCompound> placed = new ArrayList<>(riders.size());
            for (NbtCompound next : riders) placed.add(riderAt(next, x, y, z, depth + 1));
            out.put("Passengers", NbtList.of(NbtTag.COMPOUND, placed));
        }
        return out.build();
    }

    /**
     * Keys holding the face an entity is attached by, as a direction id (0 down, 1 up, 2-5 the sides): an item frame's
     * {@code Facing}, a shulker's {@code AttachFace}.
     */
    public static final List<String> ATTACHMENT_FACE_KEYS = List.of("Facing", "AttachFace");

    /**
     * The entity's data flipped upside down: an attachment face of down
     * (0) becomes up (1) and the other way round ({@link #ATTACHMENT_FACE_KEYS}); sides and everything else stay.
     * {@code data} itself when nothing changes.
     */
    public static NbtCompound flippedAttachment(NbtCompound data) {
        NbtCompound.Builder out = null;
        for (String key : ATTACHMENT_FACE_KEYS) {
            int face = verticalFace(data, key);
            if (face < 0) continue;
            if (out == null) out = data.toBuilder();
            out.putByte(key, (byte) (1 - face));
        }
        return out == null ? data : out.build();
    }

    /** The down (0) or up (1) face stored under {@code key} as a byte, else -1. */
    public static int verticalFace(NbtCompound data, String key) {
        NbtTag.NbtByte face = data.get(key, NbtTag.NbtByte.class);
        return face != null && (face.value() == 0 || face.value() == 1) ? face.value() : -1;
    }

    /** A list of three doubles, as vanilla writes {@code Pos} and {@code Motion}. */
    public static NbtList doubles(double x, double y, double z) {
        return NbtList.of(NbtTag.DOUBLE,
                List.of(new NbtTag.NbtDouble(x), new NbtTag.NbtDouble(y), new NbtTag.NbtDouble(z)));
    }

    /** {@code Pos} of a whole entity compound, or {@code null} when it has none (or not three finite doubles). */
    public static double[] position(NbtCompound entity) {
        NbtList pos = entity.getList("Pos");
        if (pos == null || pos.size() != 3 || pos.elementType() != NbtTag.DOUBLE) return null;
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) {
            out[i] = ((NbtTag.NbtDouble) pos.get(i)).value();
            if (!Double.isFinite(out[i])) return null;
        }
        return out;
    }

    /** {@code Rotation} (yaw, pitch) of an entity compound, or {@code null} when absent or malformed. */
    public static float[] rotation(NbtCompound entity) {
        NbtList rotation = entity.getList("Rotation");
        if (rotation == null || rotation.size() != 2 || rotation.elementType() != NbtTag.FLOAT) return null;
        float yaw = ((NbtTag.NbtFloat) rotation.get(0)).value();
        float pitch = ((NbtTag.NbtFloat) rotation.get(1)).value();
        return Float.isFinite(yaw) && Float.isFinite(pitch) ? new float[] {yaw, pitch} : null;
    }

    /**
     * Relative tolerance of float values in {@link #nearlyEqual}: a display entity's rotation and scale are recomputed
     * from its transformation matrix every time it loads, so the same display saves slightly different floats (the last
     * one or two bits) after an undo or redo put it back.
     */
    public static final float FLOAT_TOLERANCE = 1e-5f;

    /**
     * Whether two tags hold the same data, float values within {@link #FLOAT_TOLERANCE} of each other (relative, and at
     * least that much absolutely); everything else, doubles included, exactly. Compounds compare as maps.
     */
    public static boolean nearlyEqual(NbtTag a, NbtTag b) {
        if (a == b) return true;
        if (a == null || b == null || a.type() != b.type()) return false;
        return switch (a) {
            case NbtTag.NbtFloat x -> {
                float p = x.value(), q = ((NbtTag.NbtFloat) b).value();
                if (Float.isNaN(p) || Float.isNaN(q)) yield Float.isNaN(p) && Float.isNaN(q);
                yield Math.abs(p - q) <= FLOAT_TOLERANCE * Math.max(1f, Math.max(Math.abs(p), Math.abs(q)));
            }
            case NbtCompound x -> {
                NbtCompound y = (NbtCompound) b;
                if (!x.keys().equals(y.keys())) yield false;
                for (String key : x.keys()) {
                    if (!nearlyEqual(x.get(key), y.get(key))) yield false;
                }
                yield true;
            }
            case NbtList x -> {
                NbtList y = (NbtList) b;
                if (x.size() != y.size() || (x.size() > 0 && x.elementType() != y.elementType())) yield false;
                for (int i = 0; i < x.size(); i++) {
                    if (!nearlyEqual(x.get(i), y.get(i))) yield false;
                }
                yield true;
            }
            default -> a.equals(b);
        };
    }

    /**
     * Where an entity stands and looks: the two {@link #VOLATILE_KEYS} a step compares after all when they are all it
     * changed (a Tinker move or turn; see {@code EntityMatcher.placementAware}).
     */
    public static final Set<String> PLACEMENT_VOLATILE_KEYS = Set.of("Pos", "Rotation");

    /** The compound without {@link #VOLATILE_KEYS}, at the top and in every passenger. */
    public static NbtCompound withoutVolatile(NbtCompound entity) {
        return withoutVolatile(entity, false, 0);
    }

    /**
     * The compound without {@link #VOLATILE_KEYS} but with where the entity stands and looks
     * ({@link #PLACEMENT_VOLATILE_KEYS}), at the top and in every passenger.
     */
    public static NbtCompound withoutVolatileKeepingPlacement(NbtCompound entity) {
        return withoutVolatile(entity, true, 0);
    }

    private static NbtCompound withoutVolatile(NbtCompound entity, boolean keepPlacement, int depth) {
        NbtCompound.Builder out = entity.toBuilder();
        for (String key : VOLATILE_KEYS) {
            if (!keepPlacement || !PLACEMENT_VOLATILE_KEYS.contains(key)) out.remove(key);
        }
        NbtList passengers = entity.getList("Passengers");
        List<NbtCompound> riders = passengers == null ? null : passengers.compounds();
        if (riders != null && !riders.isEmpty() && depth < MAX_PASSENGER_DEPTH) {
            List<NbtCompound> kept = new ArrayList<>(riders.size());
            for (NbtCompound rider : riders) kept.add(withoutVolatile(rider, keepPlacement, depth + 1));
            out.put("Passengers", NbtList.of(NbtTag.COMPOUND, kept));
        }
        return out.build();
    }

    /**
     * NBT bytes in canonical form (every compound's keys sorted), so equal content gives equal bytes; the bytes as they
     * are when they do not decode.
     */
    public static byte[] canonical(byte[] nbt) {
        NbtCompound decoded;
        try {
            decoded = decode(nbt);
        } catch (IOException undecodable) {
            return nbt.clone();
        }
        return canonical(decoded);
    }

    /** A compound's bytes in canonical form (every compound's keys sorted). */
    public static byte[] canonical(NbtCompound entity) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        try {
            NbtIo.writeCanonical(out, entity);
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
        return out.toByteArray();
    }
}
