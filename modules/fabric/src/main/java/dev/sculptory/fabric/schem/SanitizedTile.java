package dev.sculptory.fabric.schem;

import dev.sculptory.core.buffer.BlockEntityData;
import java.util.Arrays;
import java.util.Objects;

/**
 * Sign or hanging-sign content from a file that {@link TileSanitizer} reduced to plain text: only the sign fields
 * are kept, every click event is gone and no component reads server data. It is therefore safe to place for
 * everyone, so {@code BlockWriter} does not strip it for players without operator rights. Only
 * {@link TileSanitizer} creates it; its NBT's {@code id} always equals {@link #typeId()}.
 */
public final class SanitizedTile implements BlockEntityData {
    private final String typeId;
    private final byte[] nbtBytes;

    SanitizedTile(String typeId, byte[] nbtBytes) {
        this.typeId = Objects.requireNonNull(typeId);
        this.nbtBytes = nbtBytes.clone();
    }

    @Override
    public String typeId() {
        return typeId;
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
        if (o == null || !typeId.equals(o.typeId())) return false;
        if (o instanceof SanitizedTile other) return Arrays.equals(nbtBytes, other.nbtBytes);
        return Arrays.equals(nbtBytes, o.nbtBytes());
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SanitizedTile other && typeId.equals(other.typeId) && Arrays.equals(nbtBytes, other.nbtBytes);
    }

    @Override
    public int hashCode() {
        return 31 * typeId.hashCode() + Arrays.hashCode(nbtBytes);
    }

    @Override
    public String toString() {
        return "SanitizedTile[" + typeId + ", " + nbtBytes.length + " bytes]";
    }
}
