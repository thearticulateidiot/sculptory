package dev.sculptory.server.platform;

import dev.sculptory.core.state.StateFlags;

/**
 * How a {@link WorldWriter} writes.
 *
 * @param physics run vanilla block updates (needs {@code sculptory.physics})
 * @param allowOperatorNbt keep any NBT on {@link StateFlags#OPERATOR_NBT} states (creative level-2 op or
 *     {@code sculptory.nbt.operator})
 * @param trustCapturedTiles keep operator-only NBT of server-captured tiles ({@link Platform#serverCaptured}) for
 *     everyone (history, move, stack); pass {@code false} where even world-captured content must be treated as foreign
 */
public record WriteOptions(boolean physics, boolean allowOperatorNbt, boolean trustCapturedTiles) {
    public static final WriteOptions DEFAULT = new WriteOptions(false, false, true);

    /** Trusts server-captured tiles. */
    public WriteOptions(boolean physics, boolean allowOperatorNbt) {
        this(physics, allowOperatorNbt, true);
    }
}
