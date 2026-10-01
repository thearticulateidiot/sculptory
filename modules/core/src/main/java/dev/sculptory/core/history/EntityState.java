package dev.sculptory.core.history;

import dev.sculptory.core.NamespacedId;
import java.util.Arrays;
import java.util.Objects;

/**
 * An entity as the world held it when a step recorded it: its type, its position ({@code Pos}, repeated here so undo
 * finds its chunk without decoding) and its complete NBT as the game saves it ({@code Entity.saveSelfNbt}: {@code id},
 * {@code UUID}, {@code Pos}, passengers...), as uncompressed binary NBT (a root compound named {@code ""}). Always read
 * from the server's own world (what was there before, or what a placement left there right after it), so it is
 * restored as it is.
 */
public record EntityState(String typeId, double x, double y, double z, byte[] nbt) {
    /**
     * Largest NBT kept for one entity (a riding chain of full chest boats is far below it): what
     * {@code EntityNbt.LIMITS} decodes, so undo can compare every entity it recorded.
     */
    public static final int MAX_NBT_BYTES = 8 << 20;

    /** @throws IllegalArgumentException for an invalid type id, a position that is not finite, or NBT too large */
    public EntityState {
        new NamespacedId(Objects.requireNonNull(typeId));
        Objects.requireNonNull(nbt);
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Entity position is not finite");
        }
        if (nbt.length > MAX_NBT_BYTES) throw new IllegalArgumentException("Entity NBT of " + nbt.length + " bytes");
        nbt = nbt.clone();
    }

    @Override
    public byte[] nbt() {
        return nbt.clone();
    }

    /** The NBT's length (without copying it). */
    public int nbtLength() {
        return nbt.length;
    }

    /** The chunk column holding the position (x, z shifted right by 4). */
    public int chunkX() {
        return ((int) Math.floor(x)) >> 4;
    }

    public int chunkZ() {
        return ((int) Math.floor(z)) >> 4;
    }

    /** Approximate heap footprint. */
    public long estimatedBytes() {
        return 80 + 2L * typeId.length() + nbt.length;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof EntityState other && typeId.equals(other.typeId) && Double.compare(x, other.x) == 0
                && Double.compare(y, other.y) == 0 && Double.compare(z, other.z) == 0 && Arrays.equals(nbt, other.nbt);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * typeId.hashCode() + Double.hashCode(x)) + Arrays.hashCode(nbt);
    }

    @Override
    public String toString() {
        return "EntityState[" + typeId + " at " + x + "," + y + "," + z + ", " + nbt.length + " bytes]";
    }
}
