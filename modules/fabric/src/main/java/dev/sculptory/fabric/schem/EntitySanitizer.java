package dev.sculptory.fabric.schem;

import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.entity.EntityNbt;
import dev.sculptory.core.entity.EntitySnapshot;
import dev.sculptory.core.nbt.NbtCompound;
import dev.sculptory.core.nbt.NbtIo;
import dev.sculptory.core.nbt.NbtList;
import dev.sculptory.core.nbt.NbtTag;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Removes operator-only data from entities, the entity counterpart of
 * {@link TileSanitizer}: for entities from files (uploads, library files) for everyone, operators included; for
 * entities leaving the server with a player who may not handle operator NBT (export, saving to the library); and when
 * such a player places entity data that is not trusted.
 *
 * <p>An entity type's data is operator-only when the game says so ({@code Entity.entityDataRequiresOperator()}: in
 * vanilla the command block minecart, the spawner minecart and the falling block, whose data can run commands or place
 * any block entity); the server lists those types once ({@code EntityTypeRules}), and type ids are compared as the game
 * loads them ({@link EntityNbt#loadedId}: a passenger's {@code "command_block_minecart"} is the operator-only type). Such
 * an entity keeps only the data every entity has ({@link #KEPT_KEYS}: its place, name, flags and tags) and its
 * passengers, sanitized the same way: a command block minecart loses its {@code Command} and {@code LastOutput}. Riders
 * deeper than {@link EntityNbt#MAX_PASSENGER_DEPTH} are dropped (counted), never passed through unchecked. Everything
 * else is kept as it is. Pure Java (no Minecraft types); safe off the server thread.
 */
public final class EntitySanitizer {
    /** What {@code Entity.writeNbt} writes for every entity; an operator-only entity keeps only these. */
    public static final Set<String> KEPT_KEYS = Set.of("id", "Pos", "Motion", "Rotation", "FallDistance", "Fire", "Air",
            "OnGround", "Invulnerable", "PortalCooldown", "UUID", "CustomName", "CustomNameVisible", "Silent",
            "NoGravity", "Glowing", "TicksFrozen", "HasVisualFire", "Tags", "Passengers");

    private EntitySanitizer() {}

    /**
     * What sanitizing changed.
     *
     * @param stripped entities (passengers included) that lost operator-only data, or were dropped as riders too deep
     */
    public record Result(Clipboard clipboard, int stripped) {
        public Result {
            Objects.requireNonNull(clipboard);
        }

        public boolean changed() {
            return stripped > 0;
        }
    }

    /** The clipboard with operator-only entity data removed (the same instance when nothing needed changing). */
    public static Result sanitize(Clipboard clipboard, Predicate<String> operatorType) {
        if (clipboard.entities().isEmpty()) return new Result(clipboard, 0);
        int[] stripped = {0};
        List<EntitySnapshot> out = new ArrayList<>(clipboard.entityCount());
        boolean changed = false;
        for (EntitySnapshot entity : clipboard.entities()) {
            EntitySnapshot clean = sanitize(entity, operatorType, stripped);
            changed |= clean != entity;
            out.add(clean);
        }
        return changed ? new Result(clipboard.withEntities(out), stripped[0]) : new Result(clipboard, 0);
    }

    /**
     * One clipboard entity with operator-only data removed (the same instance when nothing needed changing). Data
     * that does not decode is dropped (the entity keeps its type and place). {@code stripped[0]} counts the entities
     * that lost data.
     */
    public static EntitySnapshot sanitize(EntitySnapshot entity, Predicate<String> operatorType, int[] stripped) {
        NbtCompound data;
        try {
            data = NbtIo.fromBytes(entity.nbt(), EntityNbt.LIMITS);
        } catch (IOException undecodable) {
            stripped[0]++;
            return entity.withNbt(NbtIo.toBytes(NbtCompound.EMPTY), entity.trusted());
        }
        int before = stripped[0];
        NbtCompound clean = strip(entity.typeId(), data, operatorType, stripped, 0);
        if (stripped[0] == before) return entity;
        return entity.withNbt(NbtIo.toBytes(clean), entity.trusted());
    }

    /**
     * A whole entity compound (with its {@code id}) with operator-only data removed, passengers included; the same
     * instance when nothing needed changing. {@code stripped[0]} counts the entities that lost data.
     */
    public static NbtCompound sanitize(NbtCompound entity, Predicate<String> operatorType, int[] stripped) {
        return strip(entity.getString("id"), entity, operatorType, stripped, 0);
    }

    /** Whether {@code data} (an entity of {@code typeId}) or any passenger of it is of an operator-only type. */
    public static boolean holdsOperatorType(String typeId, NbtCompound data, Predicate<String> operatorType) {
        if (operatorType.test(EntityNbt.loadedId(typeId))) return true;
        java.util.ArrayDeque<NbtCompound> open = new java.util.ArrayDeque<>();
        open.push(data);
        while (!open.isEmpty()) {
            for (NbtCompound rider : EntityNbt.riders(open.pop())) {
                if (operatorType.test(EntityNbt.loadedId(rider.getString("id")))) return true;
                open.push(rider);
            }
        }
        return false;
    }

    private static NbtCompound strip(String typeId, NbtCompound entity, Predicate<String> operatorType, int[] stripped,
                                     int depth) {
        NbtCompound.Builder out = null;
        if (operatorType.test(EntityNbt.loadedId(typeId))) {
            out = NbtCompound.builder();
            for (var entry : entity.entries().entrySet()) {
                if (KEPT_KEYS.contains(entry.getKey())) out.put(entry.getKey(), entry.getValue());
            }
            if (out.build().size() < entity.size()) stripped[0]++;
        }
        List<NbtCompound> riders = EntityNbt.riders(entity);
        if (!riders.isEmpty()) {
            List<NbtCompound> cleaned = new ArrayList<>(riders.size());
            boolean changed = false;
            for (NbtCompound rider : riders) {
                if (depth + 1 > EntityNbt.MAX_PASSENGER_DEPTH) {
                    stripped[0] += 1 + EntityNbt.riderCount(rider); // too deep to check: left out
                    changed = true;
                    continue;
                }
                NbtCompound clean = strip(rider.getString("id"), rider, operatorType, stripped, depth + 1);
                changed |= clean != rider;
                cleaned.add(clean);
            }
            if (changed) {
                if (out == null) out = entity.toBuilder();
                if (cleaned.isEmpty()) {
                    out.remove("Passengers");
                } else {
                    out.put("Passengers", NbtList.of(NbtTag.COMPOUND, cleaned));
                }
            }
        }
        return out == null ? entity : out.build();
    }
}
