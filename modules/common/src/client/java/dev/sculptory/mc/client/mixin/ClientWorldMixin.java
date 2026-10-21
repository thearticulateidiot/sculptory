package dev.sculptory.mc.client.mixin;

import dev.sculptory.mc.client.world.ClientBlockChanges;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Bumps {@link ClientBlockChanges} whenever the client world changes a block.
 *
 * <p>Target checked with javap against Minecraft 1.21.1 (Mojang names): {@code Level.setBlock(BlockPos, BlockState, int,
 * int)} calls {@code setBlocksDirty(BlockPos, BlockState, BlockState)} whenever the stored state
 * actually changed, and {@code ClientLevel} overrides it. Hooking {@code setServerVerifiedBlockState} or
 * {@code ClientLevel.setBlock} instead would miss changes: {@code setServerVerifiedBlockState} calls
 * {@code super.setBlock} directly (server block and chunk-delta updates), and prediction rollbacks go through
 * {@code handleBlockChangedAck}. All three paths end in this method.
 */
@Mixin(ClientLevel.class)
public abstract class ClientWorldMixin {
    @Inject(method = "setBlocksDirty", at = @At("HEAD"))
    private void sculptory$blockChanged(BlockPos pos, BlockState old, BlockState updated, CallbackInfo ci) {
        ClientBlockChanges.bump(pos.getX(), pos.getY(), pos.getZ());
    }
}
