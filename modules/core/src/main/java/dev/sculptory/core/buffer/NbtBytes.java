package dev.sculptory.core.buffer;

import java.util.Arrays;
import java.util.Objects;

/** Block-entity data held as uncompressed binary NBT with no x/y/z. The array is copied in and out. */
public record NbtBytes(String typeId, byte[] nbtBytes) implements BlockEntityData {
    public NbtBytes {
        Objects.requireNonNull(typeId);
        Objects.requireNonNull(nbtBytes);
        if (typeId.isEmpty()) throw new IllegalArgumentException("Empty block-entity type id");
        nbtBytes = nbtBytes.clone();
    }

    @Override
    public byte[] nbtBytes() {
        return nbtBytes.clone();
    }

    @Override
    public int estimatedBytes() {
        return 48 + 2 * typeId.length() + nbtBytes.length;
    }

    @Override
    public boolean sameContent(BlockEntityData o) {
        if (o == null) return false;
        if (o instanceof NbtBytes other) {
            return typeId.equals(other.typeId) && Arrays.equals(nbtBytes, other.nbtBytes);
        }
        return typeId.equals(o.typeId()) && Arrays.equals(nbtBytes, o.nbtBytes());
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof NbtBytes other && typeId.equals(other.typeId) && Arrays.equals(nbtBytes, other.nbtBytes);
    }

    @Override
    public int hashCode() {
        return 31 * typeId.hashCode() + Arrays.hashCode(nbtBytes);
    }

    @Override
    public String toString() {
        return "NbtBytes[" + typeId + ", " + nbtBytes.length + " bytes]";
    }
}
