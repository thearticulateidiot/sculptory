package dev.sculptory.core.buffer;

/** Block-entity content for one cell. Implementations are immutable. */
public interface BlockEntityData {
    /** Block-entity type id, e.g. {@code minecraft:chest}. */
    String typeId();

    /** Uncompressed binary NBT without x/y/z. May be computed lazily. */
    byte[] nbtBytes();

    int estimatedBytes();

    /** True when {@code o} has the same type and NBT content. {@code null} is never the same. */
    boolean sameContent(BlockEntityData o);
}
