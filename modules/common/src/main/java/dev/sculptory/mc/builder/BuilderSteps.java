package dev.sculptory.mc.builder;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;

/**
 * How a platform carries out builder mode's world steps for {@code BuilderService}: vanilla's own placement and break
 * steps ({@link BuilderPlacement}), with every cell they change recorded for an exact undo, Keep shape made
 * physics-free, and a refused action put back without touching anything around it. Fabric and NeoForge do it with
 * mixins ({@link CapturedBuilderSteps}); Paper with CraftBukkit's block capture and the Bukkit place and break events,
 * which other plugins (protection, logging) may cancel. Server thread only; one action at a time.
 */
public interface BuilderSteps {
    /** What one step came to. */
    enum Step {
        /** It was carried out. */
        DONE,
        /** The game did not carry it out (the item's own placement refused); nothing changed. */
        NOT_DONE,
        /** Another plugin cancelled it (its block event): what it changed is already put back. */
        REFUSED
    }

    /**
     * Opens one action of {@code player} on {@code world}. With {@code keepShape} its steps are physics-free: nothing
     * around them reacts (fences don't join, plants float, sand stays), the blocks' own add and replace callbacks do not
     * run, and nothing drops.
     */
    Action open(ServerLevel world, ServerPlayer player, boolean keepShape);

    /**
     * Puts {@code changes} back as they were, newest first, physics-free (no neighbour updates, no callbacks, no
     * comparator updates, nothing dropped); block entities come back with their contents, and clients hear.
     */
    void revert(ServerLevel world, List<BuilderChange> changes);

    /** One open builder action: its steps, then {@link #finish} (always called, also after a step threw). */
    interface Action {
        /**
         * Empties the container at {@code pos} (if one is there) into the record, not onto the ground, before it is
         * replaced: the cell's contents are noted first, so undo restores them without duplicating anything.
         */
        void emptyContainer(BlockPos pos);

        /**
         * Places {@code state} into {@code context}'s cell with the item's own steps ({@link BuilderPlacement#place};
         * {@code copy}: a mirrored copy).
         */
        Step place(BlockItem item, BlockPlaceContext context, BlockState state, boolean copy);

        /**
         * Breaks the block at {@code pos} ({@link BuilderPlacement#breakBlock}), a container there emptied into the
         * record first.
         */
        Step breakBlock(BlockPos pos);

        /**
         * Ends the action (what its steps set off runs now, where the platform defers it) and gives every cell it
         * changed, in the order they were first changed: each cell's first and last state and block entity.
         */
        List<BuilderChange> finish();
    }
}
