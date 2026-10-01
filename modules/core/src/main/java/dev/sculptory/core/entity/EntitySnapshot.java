package dev.sculptory.core.entity;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.NamespacedId;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;

/**
 * An entity held by a clipboard: where it stands in the clipboard's local box,
 * which way it looks, and its data.
 *
 * <p>{@code x}, {@code y}, {@code z} are the entity's position ({@code Entity.getPos()}) relative to the local box's
 * minimum corner, so a position inside local cell (i, j, k) lies in {@code [i, i + 1)} on each axis. It may lie outside
 * the box (a painting's centre, a boat hanging over the edge): the entity belongs to the clipboard because its
 * {@link #cell()} was inside the copied region.
 *
 * <p>{@code attached} is set for entities that hang on a block (item frames, paintings, leash knots: vanilla's
 * {@code BlockAttachedEntity}): the local cell of that block, which decides where the entity belongs. It may be
 * {@code null} for such an entity read from a file that gave no usable attachment; the server then derives it from the
 * position when it places the entity.
 *
 * <p>{@code nbt} is the entity's data as uncompressed binary NBT (a root compound named {@code ""}, as
 * {@code NbtBytes}) without the keys that say which entity it is or where ({@link EntityNbt#PLACEMENT_KEYS}:
 * {@code id}, {@code UUID}, {@code Pos}, {@code Motion}, {@code Rotation}, and {@code TileX}/{@code TileY}/{@code TileZ});
 * passengers ride inside it without their {@code UUID}, {@code Pos} and {@code Motion}. Every placement gets new UUIDs.
 *
 * <p>{@code trusted} follows the block-entity trust rule: data captured from this server's world by a player who may
 * modify where it was is trusted; data from files, and data read under {@code limit.bypass} from where the player may
 * not modify, is not (operator-only entity data is then stripped for players without {@code nbt.operator}). Trust is
 * not part of {@link #equals}, the content hash or the canonical order.
 */
public record EntitySnapshot(String typeId, double x, double y, double z, float yaw, float pitch, BlockPos attached,
                             byte[] nbt, boolean trusted) {
    /** Largest {@link #nbt} kept for one entity. */
    public static final int MAX_NBT_BYTES = 2 << 20;

    /**
     * The order clipboards keep their entities in, so equal content has an equal content hash whatever order the
     * entities were found in: by cell (y, z, x), then position, then type and data.
     */
    public static final Comparator<EntitySnapshot> CANONICAL_ORDER = Comparator
            .comparingInt((EntitySnapshot e) -> e.cell().y())
            .thenComparingInt(e -> e.cell().z())
            .thenComparingInt(e -> e.cell().x())
            .thenComparingDouble(EntitySnapshot::y)
            .thenComparingDouble(EntitySnapshot::z)
            .thenComparingDouble(EntitySnapshot::x)
            .thenComparing(EntitySnapshot::typeId)
            .thenComparingDouble(EntitySnapshot::yaw)
            .thenComparingDouble(EntitySnapshot::pitch)
            .thenComparing(EntitySnapshot::nbt, Arrays::compare);

    /**
     * @throws IllegalArgumentException for an invalid type id, a position or angle that is not finite, or NBT over
     *     {@link #MAX_NBT_BYTES}
     */
    public EntitySnapshot {
        new NamespacedId(Objects.requireNonNull(typeId));
        Objects.requireNonNull(nbt);
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Entity position is not finite");
        }
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) throw new IllegalArgumentException("Entity angle is not finite");
        if (nbt.length > MAX_NBT_BYTES) throw new IllegalArgumentException("Entity NBT of " + nbt.length + " bytes");
        nbt = nbt.clone();
    }

    @Override
    public byte[] nbt() {
        return nbt.clone();
    }

    /** The local cell the entity belongs to: its {@link #attached} block, else the cell holding its position. */
    public BlockPos cell() {
        if (attached != null) return attached;
        return new BlockPos(floor(x), floor(y), floor(z));
    }

    /** This entity moved by {@code (dx, dy, dz)} (its attachment with it). */
    public EntitySnapshot offset(int dx, int dy, int dz) {
        return new EntitySnapshot(typeId, x + dx, y + dy, z + dz, yaw, pitch,
                attached == null ? null : attached.offset(dx, dy, dz), nbt, trusted);
    }

    /** The same entity with other data (and trust). */
    public EntitySnapshot withNbt(byte[] newNbt, boolean newTrusted) {
        return new EntitySnapshot(typeId, x, y, z, yaw, pitch, attached, newNbt, newTrusted);
    }

    /** The same entity marked untrusted (a copy of data the player may not vouch for). */
    public EntitySnapshot untrusted() {
        return trusted ? new EntitySnapshot(typeId, x, y, z, yaw, pitch, attached, nbt, false) : this;
    }

    /** Approximate heap footprint. */
    public long estimatedBytes() {
        return 96 + 2L * typeId.length() + nbt.length;
    }

    /** Same type, position, angles, attachment and data bytes (trust is not compared). */
    @Override
    public boolean equals(Object o) {
        return o instanceof EntitySnapshot other && typeId.equals(other.typeId)
                && Double.compare(x, other.x) == 0 && Double.compare(y, other.y) == 0 && Double.compare(z, other.z) == 0
                && Float.compare(yaw, other.yaw) == 0 && Float.compare(pitch, other.pitch) == 0
                && Objects.equals(attached, other.attached) && Arrays.equals(nbt, other.nbt);
    }

    @Override
    public int hashCode() {
        int h = typeId.hashCode();
        h = 31 * h + Double.hashCode(x);
        h = 31 * h + Double.hashCode(y);
        h = 31 * h + Double.hashCode(z);
        h = 31 * h + Float.hashCode(yaw);
        h = 31 * h + Float.hashCode(pitch);
        h = 31 * h + Objects.hashCode(attached);
        return 31 * h + Arrays.hashCode(nbt);
    }

    @Override
    public String toString() {
        return "EntitySnapshot[" + typeId + " at " + x + "," + y + "," + z + (attached == null ? "" : " on " + attached)
                + ", " + nbt.length + " bytes" + (trusted ? "" : ", untrusted") + "]";
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }
}
