package dev.sculptory.core.history;

import dev.sculptory.core.NamespacedId;

/**
 * One biome cell an edit changed (protocol 6): the quart at quart coordinates {@code (qx, qy, qz)} (the 4×4×4 cell whose
 * minimum block is {@code (4 qx, 4 qy, 4 qz)}, i.e. block coordinates shifted right by 2) held biome {@code before} and
 * now holds {@code after}. Ids are registry ids as text ("minecraft:plains"), so a record stays readable when the
 * registry changes. An {@link EditRecord} holds each quart at most once.
 */
public record BiomeChange(int qx, int qy, int qz, String before, String after) {
    public BiomeChange {
        new NamespacedId(before); // refuses a malformed id
        new NamespacedId(after);
    }

    /** The quart's minimum block x. */
    public int blockX() {
        return qx << 2;
    }

    /** The quart's minimum block y. */
    public int blockY() {
        return qy << 2;
    }

    /** The quart's minimum block z. */
    public int blockZ() {
        return qz << 2;
    }

    /** Rough heap size, for history budgets. */
    public long estimatedBytes() {
        return 48 + 2L * (before.length() + after.length());
    }
}
