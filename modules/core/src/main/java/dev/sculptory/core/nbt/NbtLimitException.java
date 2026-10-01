package dev.sculptory.core.nbt;

import java.util.Objects;

/** NBT input that exceeds one of the {@link NbtLimits}. Thrown before the oversized data is allocated. */
public final class NbtLimitException extends NbtException {
    /** Which limit was exceeded. */
    public enum Limit {
        /** {@link NbtLimits#maxDepth()}: nesting of compounds and lists. */
        DEPTH,
        /** {@link NbtLimits#maxBytes()}: uncompressed bytes read (for gzip input, the inflated size). */
        BYTES,
        /** {@link NbtLimits#maxArrayLength()}: elements of one byte, int or long array. */
        ARRAY_LENGTH,
        /** {@link NbtLimits#maxListLength()}: elements of one list. */
        LIST_LENGTH,
        /** {@link NbtLimits#maxTags()}: tags in the whole document. */
        TAGS,
        /** {@link NbtLimits#maxHeapBytes()}: estimated heap of the decoded tree. */
        HEAP
    }

    private final Limit limit;

    public NbtLimitException(Limit limit, String message) {
        super(message);
        this.limit = Objects.requireNonNull(limit);
    }

    public Limit limit() {
        return limit;
    }
}
