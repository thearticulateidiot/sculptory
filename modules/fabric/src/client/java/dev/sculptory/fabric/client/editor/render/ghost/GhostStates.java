package dev.sculptory.fabric.client.editor.render.ghost;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;

/**
 * Handles of ghost volumes are client block-state handles: raw state ids ({@code Block.getRawIdFromState}), as in the
 * client's {@code FabricStateSpace}. Safe on any thread.
 */
public final class GhostStates {
    private GhostStates() {}

    /** The state of a handle; air for an unknown one. */
    public static BlockState state(int handle) {
        return Block.getStateFromRawId(handle);
    }

    /**
     * Whether a handle is an air state (air, cave air, void air): the erase predicate to build volumes with, e.g.
     * {@code GhostVolume.of(buffer, GhostStates::isAir)}.
     */
    public static boolean isAir(int handle) {
        return state(handle).isAir();
    }

    /** The handle of a state. */
    public static int handle(BlockState state) {
        return Block.getRawIdFromState(state);
    }
}
