package dev.sculptory.server.schem;

import dev.sculptory.core.BlockPos;
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
import java.util.function.Predicate;

/**
 * The kind rules for entities from files (uploads, library files): a copy never
 * takes players, items, projectiles, primed TNT, lightning, withers, the ender dragon and the like, and neither may a
 * file bring them. An entity whose type is refused ({@code refused}: never placed, not summonable, or unknown to this
 * game) is left out with everything riding it; a refused passenger is left out with its own riders. {@code TileX/Y/Z}
 * name a block only for a hanging type ({@code hanging}); any other entity belongs to the block holding its position,
 * and is left out when that block is outside the clipboard's box. Everything left out is counted. Pure Java; safe off
 * the server thread.
 */
public final class FileEntities {
    private FileEntities() {}

    /** What the rules left of a clipboard's entities, and how many entities (passengers included) they left out. */
    public record Result(Clipboard clipboard, int skipped) {
        public Result {
            Objects.requireNonNull(clipboard);
        }
    }

    /** {@code clipboard} with the entities the rules refuse left out (the same instance when all are allowed). */
    public static Result clean(Clipboard clipboard, Predicate<String> refused, Predicate<String> hanging) {
        if (clipboard.entities().isEmpty()) return new Result(clipboard, 0);
        BlockPos size = clipboard.size();
        int[] skipped = {0};
        List<EntitySnapshot> kept = new ArrayList<>(clipboard.entityCount());
        boolean changed = false;
        for (EntitySnapshot entity : clipboard.entities()) {
            EntitySnapshot clean = clean(entity, size, refused, hanging, skipped);
            if (clean != entity) changed = true;
            if (clean != null) kept.add(clean);
        }
        return changed ? new Result(clipboard.withEntities(kept), skipped[0]) : new Result(clipboard, 0);
    }

    /** One entity by the rules: itself, a cleaned copy, or {@code null} when it is left out (counted). */
    static EntitySnapshot clean(EntitySnapshot entity, BlockPos size, Predicate<String> refused,
                                Predicate<String> hanging, int[] skipped) {
        NbtCompound data;
        try {
            data = NbtIo.fromBytes(entity.nbt(), EntityNbt.LIMITS);
        } catch (IOException undecodable) {
            skipped[0]++;
            return null;
        }
        if (refused.test(EntityNbt.loadedId(entity.typeId()))) {
            skipped[0] += 1 + EntityNbt.riderCount(data);
            return null;
        }
        EntitySnapshot out = entity;
        if (entity.attached() != null && !hanging.test(EntityNbt.loadedId(entity.typeId()))) {
            // Not a hanging entity: its block is the one holding its position, which must be in the box.
            out = new EntitySnapshot(entity.typeId(), entity.x(), entity.y(), entity.z(), entity.yaw(), entity.pitch(),
                    null, entity.nbt(), entity.trusted());
            BlockPos cell = out.cell();
            if (cell.x() < 0 || cell.y() < 0 || cell.z() < 0 || cell.x() >= size.x() || cell.y() >= size.y()
                    || cell.z() >= size.z()) {
                skipped[0] += 1 + EntityNbt.riderCount(data);
                return null;
            }
        }
        int before = skipped[0];
        NbtCompound cleaned = riders(data, refused, skipped);
        return skipped[0] == before ? out : out.withNbt(NbtIo.toBytes(cleaned), out.trusted());
    }

    /** {@code entity} without refused passengers (each counted with its riders). */
    private static NbtCompound riders(NbtCompound entity, Predicate<String> refused, int[] skipped) {
        List<NbtCompound> riders = EntityNbt.riders(entity);
        if (riders.isEmpty()) return entity;
        List<NbtCompound> kept = new ArrayList<>(riders.size());
        boolean changed = false;
        for (NbtCompound rider : riders) {
            if (refused.test(EntityNbt.loadedId(rider.getString("id")))) {
                skipped[0] += 1 + EntityNbt.riderCount(rider);
                changed = true;
                continue;
            }
            NbtCompound clean = riders(rider, refused, skipped);
            changed |= clean != rider;
            kept.add(clean);
        }
        if (!changed) return entity;
        NbtCompound.Builder out = entity.toBuilder();
        if (kept.isEmpty()) {
            out.remove("Passengers");
        } else {
            out.put("Passengers", NbtList.of(NbtTag.COMPOUND, kept));
        }
        return out.build();
    }
}
