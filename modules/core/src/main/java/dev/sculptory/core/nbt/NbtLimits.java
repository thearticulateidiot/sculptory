package dev.sculptory.core.nbt;

/**
 * Hard limits for reading untrusted NBT. Every limit is checked before the data it guards is allocated, so a
 * malicious file fails with {@link NbtLimitException} instead of exhausting memory.
 *
 * @param maxDepth maximum nesting of compounds and lists (the root compound is depth 1)
 * @param maxBytes maximum uncompressed bytes consumed; for gzip input this caps inflation (zip-bomb guard)
 * @param maxArrayLength maximum elements in one byte, int or long array
 * @param maxListLength maximum elements in one list
 * @param maxTags maximum tags in the whole document, which bounds the objects created
 * @param maxHeapBytes maximum estimated heap for the decoded tree. Tiny inputs can describe many small objects
 *     (a few KiB of gzip holds millions of empty compounds), so this is charged per object as it is created:
 *     {@value #COMPOUND_COST} bytes per compound, {@value #ENTRY_COST} per compound entry plus each new key,
 *     {@value #LIST_COST} per list plus 16 per element, {@value #VALUE_COST} per number, and twice the size of
 *     every string and array. Every compound is charged in full, even though all empty ones share one instance
 */
public record NbtLimits(int maxDepth, long maxBytes, int maxArrayLength, int maxListLength, long maxTags,
                        long maxHeapBytes) {
    /** Heap charged per compound, per compound entry, per list, per number tag (estimates, rounded up). */
    public static final int COMPOUND_COST = 128;
    public static final int ENTRY_COST = 40;
    public static final int LIST_COST = 64;
    public static final int VALUE_COST = 24;
    /** The heap budget of the five-argument constructor. */
    public static final long DEFAULT_MAX_HEAP_BYTES = 128L << 20;

    /** For trusted schematic files (server-owned folders): 64 MiB uncompressed, 4M tags, 128 MiB heap. */
    public static final NbtLimits DEFAULT = new NbtLimits(512, 64L << 20, 1 << 25, 1 << 22, 1L << 22);

    /** For one block entity's NBT: 8 MiB (like {@code FabricTile.MAX_DECODE_BYTES}), 256k tags, 32 MiB heap. */
    public static final NbtLimits BLOCK_ENTITY = new NbtLimits(512, 8L << 20, 1 << 21, 1 << 18, 1L << 18, 32L << 20);

    public NbtLimits {
        if (maxDepth < 1 || maxBytes < 1 || maxArrayLength < 0 || maxListLength < 0 || maxTags < 1 || maxHeapBytes < 1) {
            throw new IllegalArgumentException("NBT limits must be positive");
        }
    }

    /** Limits with the default heap budget ({@link #DEFAULT_MAX_HEAP_BYTES}). */
    public NbtLimits(int maxDepth, long maxBytes, int maxArrayLength, int maxListLength, long maxTags) {
        this(maxDepth, maxBytes, maxArrayLength, maxListLength, maxTags, DEFAULT_MAX_HEAP_BYTES);
    }

    /**
     * For files uploaded by players: 32 MiB uncompressed, depth 128, lists of at most 256k, 1M tags and 64 MiB of
     * heap. Enough for a 2M-cell schematic with tens of thousands of filled containers.
     */
    public static NbtLimits untrustedUpload() {
        return new NbtLimits(128, 32L << 20, 1 << 25, 1 << 18, 1L << 20, 64L << 20);
    }
}
