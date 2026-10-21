package dev.sculptory.mc.mixin;

import dev.sculptory.mc.world.EditScope;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Physics-off writes: while the current thread holds an {@link EditScope}, block
 * add/replace callbacks do nothing, so no ticks are scheduled, nothing drops and no neighbours are touched.
 * Descriptors verified with javap against Minecraft 1.21.1 (Mojang names).
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class AbstractBlockStateMixin {
    @Inject(method = "onPlace(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)V",
            at = @At("HEAD"), cancellable = true)
    private void sculptory$suppressOnBlockAdded(Level world, BlockPos pos, BlockState oldState, boolean notify,
                                                   CallbackInfo ci) {
        if (EditScope.isSuppressing()) ci.cancel();
    }

    @Inject(method = "onRemove(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)V",
            at = @At("HEAD"), cancellable = true)
    private void sculptory$suppressOnStateReplaced(Level world, BlockPos pos, BlockState newState, boolean moved,
                                                      CallbackInfo ci) {
        if (EditScope.isSuppressing()) ci.cancel();
    }
}
