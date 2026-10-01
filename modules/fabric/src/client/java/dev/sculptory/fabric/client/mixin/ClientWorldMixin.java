package dev.sculptory.fabric.client.mixin;

import dev.sculptory.fabric.client.world.ClientBlockChanges;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Bumps {@link ClientBlockChanges} whenever the client world changes a block.
 *
 * <p>Target checked with javap against yarn 1.21.1+build.3: {@code World.setBlockState(BlockPos, BlockState, int,
 * int)} calls {@code scheduleBlockRerenderIfNeeded(BlockPos, BlockState, BlockState)} whenever the stored state
 * actually changed, and {@code ClientWorld} overrides it. Hooking {@code handleBlockUpdate} or
 * {@code ClientWorld.setBlockState} instead would miss changes: {@code handleBlockUpdate} calls
 * {@code super.setBlockState} directly (server block and chunk-delta updates), and prediction rollbacks go through
 * {@code processPendingUpdate}. All three paths end in this method.
 */
@Mixin(ClientWorld.class)
public abstract class ClientWorldMixin {
    @Inject(method = "scheduleBlockRerenderIfNeeded", at = @At("HEAD"))
    private void sculptory$blockChanged(BlockPos pos, BlockState old, BlockState updated, CallbackInfo ci) {
        ClientBlockChanges.bump(pos.getX(), pos.getY(), pos.getZ());
    }
}
