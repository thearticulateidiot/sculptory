package dev.sculptory.mc.client.editor.render.ghost;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Handles of ghost volumes are client block-state handles: raw state ids ({@code Block.getRawIdFromState}), as in the
 * client's {@code McStateSpace}. Safe on any thread.
 */
public final class GhostStates {
    private GhostStates() {}

    /** The state of a handle; air for an unknown one. */
    public static BlockState state(int handle) {
        return Block.stateById(handle);
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
        return Block.getId(state);
    }
}
