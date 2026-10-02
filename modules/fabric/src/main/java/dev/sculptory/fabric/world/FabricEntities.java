package dev.sculptory.fabric.world;

import dev.sculptory.core.Box;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.entity.EntityNbt;
import dev.sculptory.core.entity.EntitySnapshot;
import dev.sculptory.core.history.EntityState;
import dev.sculptory.core.nbt.NbtLimits;
import dev.sculptory.core.region.Region;
import dev.sculptory.server.engine.impl.EntityColumns;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.entity.AreaEffectCloudEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EyeOfEnderEntity;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.OminousItemSpawnerEntity;
import net.minecraft.entity.TntEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.boss.dragon.EnderDragonPart;
import net.minecraft.entity.decoration.BlockAttachedEntity;
import net.minecraft.entity.mob.EvokerFangsEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

/**
 * Which entities an edit takes and how it reads them, server thread only.
 *
 * <p><b>Kinds.</b> Players and transient entities (dropped items, XP orbs, projectiles (fireworks and fishing bobbers
 * among them), falling blocks, primed TNT, lightning, area effect clouds, evoker fangs, eyes of ender, ominous item
 * spawners, and any type the game does not save or summon), withers and the ender dragon are never taken, from the
 * world or from files. Armor stands and every non-living entity are
 * decorations; every other living entity is a mob. {@link EntityFilter#DECORATIONS} takes decorations,
 * {@link EntityFilter#ALL} mobs too.
 *
 * <p><b>Membership.</b> An entity belongs to a region when its {@link #cell} is in the region: for a hanging entity
 * ({@link BlockAttachedEntity}) its attachment block (the space it hangs in, in front of its wall, which vanilla also
 * checks for protection), else the block holding its position. Only root entities (riding
 * nothing) are taken; their passengers ride along when the filter takes them (recursively, at most
 * {@link EntityNbt#MAX_RIDERS}), and the others stay where they are. An entity's position lies within
 * {@value #MARGIN} blocks of its cell ({@link EntityColumns}), so looking {@value #MARGIN} blocks around a region finds
 * them all.
 */
public final class FabricEntities {
    /** Largest entity NBT read back from bytes (a passenger chain of full chest boats is far below it). */
    static final long MAX_NBT_BYTES = EntityState.MAX_NBT_BYTES;
    private static final int MARGIN = EntityColumns.MARGIN;

    private FabricEntities() {}

    /** What a filter may take of an entity. */
    public enum Kind {
        NEVER,
        DECORATION,
        MOB
    }

    public static Kind kind(Entity entity) {
        if (entity instanceof PlayerEntity || entity.isRemoved() || !entity.getType().isSaveable()
                || !entity.getType().isSummonable()) {
            return Kind.NEVER;
        }
        // Bosses are never copied or placed, whatever the filter.
        if (entity instanceof WitherEntity || entity instanceof EnderDragonEntity) return Kind.NEVER;
        // A part of a multipart entity (the ender dragon's) has its owner's type but is no entity of its own.
        if (entity instanceof EnderDragonPart) return Kind.NEVER;
        if (entity instanceof ItemEntity || entity instanceof ExperienceOrbEntity || entity instanceof ProjectileEntity
                || entity instanceof FallingBlockEntity || entity instanceof TntEntity
                || entity instanceof LightningEntity || entity instanceof AreaEffectCloudEntity
                || entity instanceof EvokerFangsEntity || entity instanceof EyeOfEnderEntity
                || entity instanceof OminousItemSpawnerEntity) {
            return Kind.NEVER;
        }
        if (entity instanceof ArmorStandEntity) return Kind.DECORATION;
        return entity instanceof LivingEntity ? Kind.MOB : Kind.DECORATION;
    }

    /** Whether {@code filter} takes {@code entity}. */
    public static boolean takes(EntityFilter filter, Entity entity) {
        return switch (filter) {
            case NONE -> false;
            case DECORATIONS -> kind(entity) == Kind.DECORATION;
            case ALL -> kind(entity) != Kind.NEVER;
        };
    }

    /**
     * The block an entity belongs to: a hanging entity's attachment block (the space in front of its wall), else the
     * block holding its position.
     */
    public static BlockPos cell(Entity entity) {
        if (entity instanceof BlockAttachedEntity attached && attached.getAttachedBlockPos() != null) {
            return attached.getAttachedBlockPos();
        }
        return entity.getBlockPos();
    }

    /** Whether {@code entity} belongs to {@code region}. */
    public static boolean in(Region region, Entity entity) {
        BlockPos cell = cell(entity);
        return region.contains(cell.getX(), cell.getY(), cell.getZ());
    }

    /** Whether chunk (cx, cz) is loaded with its entities (vanilla loads entities after the chunk). */
    public static boolean loaded(ServerWorld world, int cx, int cz) {
        return WorldChecks.isChunkLoaded(world, cx, cz) && world.isChunkLoaded(ChunkPos.toLong(cx, cz));
    }

    /**
     * The first chunk ("cx,cz") whose entities could belong to a region of {@code bounds} and are not loaded, or
     * {@code null} when every one is: an edit must not miss entities it cannot see.
     */
    public static String firstUnloaded(ServerWorld world, Box bounds) {
        return firstUnloaded(world, EntityColumns.of(bounds));
    }

    /** {@link #firstUnloaded(ServerWorld, Box)} over packed chunk {@code columns}. */
    public static String firstUnloaded(ServerWorld world, long[] columns) {
        for (long column : columns) {
            int cx = ChunkPos.getPackedX(column), cz = ChunkPos.getPackedZ(column);
            if (!loaded(world, cx, cz)) return cx + "," + cz;
        }
        return null;
    }

    /**
     * The root entities {@code filter} takes whose cell is in {@code region}, among the loaded ones, looking only in the
     * chunk {@code columns} (as {@link EntityColumns#of(Region, int, int)} gives them, which hold every entity that can
     * belong to the region), in a stable order (by UUID).
     */
    public static List<Entity> inRegion(ServerWorld world, Region region, EntityFilter filter, long[] columns) {
        if (filter == EntityFilter.NONE) return List.of();
        List<Entity> found = new ArrayList<>();
        for (long column : columns) {
            found.addAll(inRegion(world, region, filter, ChunkPos.getPackedX(column), ChunkPos.getPackedZ(column)));
        }
        found.sort(Comparator.comparing(Entity::getUuid));
        return found;
    }

    /**
     * The root entities {@code filter} takes whose cell is in {@code region}, among those stored in chunk (cx, cz) (by
     * their position): each entity is stored in exactly one chunk, so going through a region's
     * {@link EntityColumns#of(Region, int, int) entity columns} finds each once.
     */
    public static List<Entity> inRegion(ServerWorld world, Region region, EntityFilter filter, int cx, int cz) {
        if (filter == EntityFilter.NONE) return List.of();
        Box b = region.bounds();
        double x0 = Math.max(b.min().x() - MARGIN, cx << 4), x1 = Math.min(b.max().x() + 1 + MARGIN, (cx << 4) + 16);
        double z0 = Math.max(b.min().z() - MARGIN, cz << 4), z1 = Math.min(b.max().z() + 1 + MARGIN, (cz << 4) + 16);
        if (x0 >= x1 || z0 >= z1) return List.of();
        // One block wider on every side: a zero-size entity (a display, a marker) on the chunk's edge touches the
        // query box only from outside (boxes intersect strictly). Which chunk stores it decides; the region filters.
        return collect(world, region, filter, new net.minecraft.util.math.Box(x0 - 1, b.min().y() - MARGIN - 1, z0 - 1,
                x1 + 1, b.max().y() + 1 + MARGIN + 1, z1 + 1), new ChunkPos(cx, cz));
    }

    private static List<Entity> collect(ServerWorld world, Region region, EntityFilter filter,
                                        net.minecraft.util.math.Box area, ChunkPos only) {
        List<Entity> found = new ArrayList<>();
        for (Entity entity : world.getOtherEntities(null, area, entity -> !entity.hasVehicle()
                && (only == null || only.equals(entity.getChunkPos())) && takes(filter, entity) && in(region, entity)
                && world.getEntity(entity.getUuid()) == entity)) { // a registered entity, not a part of one
            found.add(entity);
        }
        found.sort(Comparator.comparing(Entity::getUuid));
        return found;
    }

    /**
     * The entity's NBT as the game saves it ({@code saveSelfNbt}), with only the passengers {@link #takenPassengers}
     * lists (those {@code filter} takes, at most {@link EntityNbt#MAX_RIDERS}), or {@code null} for an entity the game
     * does not save.
     */
    public static NbtCompound save(Entity entity, EntityFilter filter) {
        return save(entity, filter, new int[] {EntityNbt.MAX_RIDERS}, 0);
    }

    private static NbtCompound save(Entity entity, EntityFilter filter, int[] room, int depth) {
        NbtCompound nbt = new NbtCompound();
        if (!entity.saveSelfNbt(nbt)) return null;
        nbt.remove("Passengers");
        NbtList riders = new NbtList();
        for (Entity passenger : entity.getPassengerList()) {
            if (room[0] <= 0 || depth >= EntityNbt.MAX_PASSENGER_DEPTH) break;
            if (!takes(filter, passenger)) continue;
            room[0]--;
            NbtCompound rider = save(passenger, filter, room, depth + 1);
            if (rider != null) riders.add(rider);
        }
        if (!riders.isEmpty()) nbt.put("Passengers", riders);
        return nbt;
    }

    /**
     * The passengers (at any depth) {@code filter} takes along with {@code root}: what goes when it goes. At most
     * {@link EntityNbt#MAX_RIDERS}, depth first, in the order {@link #save} keeps them; the others stay (dismounted
     * when their vehicle goes).
     */
    public static List<Entity> takenPassengers(Entity root, EntityFilter filter) {
        List<Entity> out = new ArrayList<>();
        takenPassengers(root, filter, out, 0);
        return out;
    }

    private static void takenPassengers(Entity entity, EntityFilter filter, List<Entity> out, int depth) {
        for (Entity passenger : entity.getPassengerList()) {
            if (out.size() >= EntityNbt.MAX_RIDERS || depth >= EntityNbt.MAX_PASSENGER_DEPTH) return;
            if (!takes(filter, passenger)) continue;
            out.add(passenger);
            takenPassengers(passenger, filter, out, depth + 1);
        }
    }

    /** The entities {@code filter} takes with {@code root}: itself and {@link #takenPassengers}. */
    public static int takenCount(Entity root, EntityFilter filter) {
        return 1 + takenPassengers(root, filter).size();
    }

    /**
     * The entity as history records it: its type, position and saved NBT (passengers as {@link #save}), or
     * {@code null} when the game does not save it or history could not keep it (its NBT is over
     * {@link EntityState#MAX_NBT_BYTES} or does not decode under {@link EntityNbt#LIMITS}, so undo could not compare
     * it): such an entity is neither removed nor recorded.
     */
    public static EntityState state(Entity entity, EntityFilter filter) {
        NbtCompound nbt = save(entity, filter);
        if (nbt == null) return null;
        byte[] bytes = bytes(nbt);
        if (bytes.length > EntityState.MAX_NBT_BYTES || !restorable(bytes)) return null;
        try {
            return new EntityState(typeId(entity), entity.getX(), entity.getY(), entity.getZ(), bytes);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    /**
     * The entity as a clipboard keeps it, relative to {@code min} (the region's minimum corner): its position, heading,
     * block for a hanging entity, and NBT without identity or place ({@link EntityNbt#snapshotData}), passengers as
     * {@link #save}. {@code null} for an entity the game does not save.
     *
     * @throws IllegalArgumentException if its NBT is too large for a clipboard
     */
    public static EntitySnapshot snapshot(Entity entity, EntityFilter filter, dev.sculptory.core.BlockPos min,
                                          boolean trusted) {
        NbtCompound nbt = save(entity, filter);
        if (nbt == null) return null;
        byte[] whole = bytes(nbt);
        if (!restorable(whole)) throw new IllegalArgumentException("entity NBT could not be placed back");
        dev.sculptory.core.nbt.NbtCompound core;
        try {
            core = fromBytes(whole);
        } catch (IOException e) {
            throw new IllegalArgumentException("entity NBT does not convert: " + e.getMessage(), e);
        }
        byte[] data = dev.sculptory.core.nbt.NbtIo.toBytes(EntityNbt.snapshotData(core));
        dev.sculptory.core.BlockPos attached = null;
        if (entity instanceof BlockAttachedEntity hanging && hanging.getAttachedBlockPos() != null) {
            BlockPos a = hanging.getAttachedBlockPos();
            attached = new dev.sculptory.core.BlockPos(a.getX() - min.x(), a.getY() - min.y(), a.getZ() - min.z());
        }
        return new EntitySnapshot(typeId(entity), entity.getX() - min.x(), entity.getY() - min.y(),
                entity.getZ() - min.z(), entity.getYaw(), entity.getPitch(), attached, data, trusted);
    }

    /** The entity's type id ({@code minecraft:item_frame}). */
    public static String typeId(Entity entity) {
        return EntityType.getId(entity.getType()).toString();
    }

    /**
     * {@code {width, height}} of entities of type {@code typeId} (its registered dimensions), or half a block square for
     * a type this game does not know. Reads only the registry: safe off the server thread.
     */
    public static float[] size(String typeId) {
        net.minecraft.util.Identifier id = net.minecraft.util.Identifier.tryParse(typeId);
        if (id == null) return new float[] {0.5f, 0.5f};
        return Registries.ENTITY_TYPE.getOrEmpty(id).map(type -> new float[] {type.getWidth(), type.getHeight()})
                .orElse(new float[] {0.5f, 0.5f});
    }

    /** Whether an entity type id names a type this game knows. */
    public static boolean knownType(String typeId) {
        net.minecraft.util.Identifier id = net.minecraft.util.Identifier.tryParse(typeId);
        return id != null && Registries.ENTITY_TYPE.containsId(id);
    }

    /** A live entity by UUID in this world, among the loaded ones, or {@code null}. */
    public static Entity find(ServerWorld world, UUID id) {
        return world.getEntity(id);
    }

    /**
     * Whether an entity's whole NBT can be put back: it decodes both as undo compares it (core, {@link EntityNbt#LIMITS})
     * and as {@link EntityWriter} loads it to place or restore it ({@link #compound}, the game's own size accounting,
     * which charges far more per tag than the encoded bytes). Nothing is taken (copied, removed, recorded) that could not
     * be put back.
     */
    public static boolean restorable(byte[] bytes) {
        try {
            fromBytes(bytes);
            compound(bytes);
            return true;
        } catch (IOException tooLarge) {
            return false;
        }
    }

    /** The binary NBT (a root compound named {@code ""}) of a compound, as core's {@code NbtIo} reads it. */
    public static byte[] bytes(NbtCompound nbt) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        try (DataOutputStream data = new DataOutputStream(out)) {
            NbtIo.writeCompound(nbt, data);
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
        return out.toByteArray();
    }

    /**
     * A compound from binary NBT.
     *
     * @throws IOException if it does not decode (or is larger than {@link #MAX_NBT_BYTES})
     */
    public static NbtCompound compound(byte[] bytes) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            return NbtIo.readCompound(in, NbtSizeTracker.of(MAX_NBT_BYTES));
        } catch (RuntimeException e) {
            throw new IOException("entity NBT does not decode: " + e.getMessage(), e);
        }
    }

    /** Core's form of binary NBT (entity limits). */
    public static dev.sculptory.core.nbt.NbtCompound fromBytes(byte[] bytes) throws IOException {
        return dev.sculptory.core.nbt.NbtIo.fromBytes(bytes, NbtLimits.BLOCK_ENTITY);
    }
}
