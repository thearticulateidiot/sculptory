package dev.sculptory.mc.builder;

import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.mc.world.BlockWriter;
import dev.sculptory.mc.world.McTile;
import java.io.IOException;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.LoggerFactory;

/**
 * One cell a builder action changed: its state and block entity before the action first changed it, and after the
 * action ({@link BuilderSteps.Action#finish}).
 */
public record BuilderChange(BlockPos pos, int before, BlockEntityData beforeTile, int after, BlockEntityData afterTile) {
    /**
     * Puts the cell's block entity from before back, with its contents, after the cell's state was put back (a refused
     * action's revert): through the chunk ({@link BlockWriter#removeBlockEntityQuietly}), so no comparator next to it
     * is updated. Nothing when it had none; a block entity that cannot be loaded is logged and left out.
     */
    public void putBackTile(ServerLevel world, BlockState state) {
        if (beforeTile == null) return;
        try {
            CompoundTag nbt = McTile.from(beforeTile).copyNbt();
            BlockEntity entity = BlockEntity.loadStatic(pos, state, nbt, world.registryAccess());
            if (entity == null) return;
            BlockWriter.removeBlockEntityQuietly(world, pos);
            world.setBlockEntity(entity);
            world.getChunkAt(pos).setUnsaved(true);
        } catch (IOException | RuntimeException e) {
            LoggerFactory.getLogger("sculptory").warn("Sculptory: a block entity at {} could not be put back after a "
                    + "refused builder action", pos.toShortString(), e);
        }
    }
}
