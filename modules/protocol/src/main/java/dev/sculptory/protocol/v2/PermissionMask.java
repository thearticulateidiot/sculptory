package dev.sculptory.protocol.v2;

import java.util.Objects;

/**
 * The player's permission nodes as a bitset. The bit numbering is owned by the platform's permission enum
 * (Fabric: {@code dev.sculptory.fabric.engine.Perm#bit()}); this type only carries the bits.
 */
public record PermissionMask(long bits) {
    public static final PermissionMask NONE = new PermissionMask(0L);

    public boolean has(int bit) {
        Objects.checkIndex(bit, 64);
        return (bits & (1L << bit)) != 0;
    }

    public PermissionMask with(int bit) {
        Objects.checkIndex(bit, 64);
        return new PermissionMask(bits | (1L << bit));
    }
}
