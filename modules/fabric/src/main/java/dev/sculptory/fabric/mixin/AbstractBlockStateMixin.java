package dev.sculptory.fabric.mixin;

import dev.sculptory.fabric.world.EditScope;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Physics-off writes: while the current thread holds an {@link EditScope}, block
 * add/replace callbacks do nothing, so no ticks are scheduled, nothing drops and no neighbours are touched.
 * Descriptors verified with javap against yarn 1.21.1+build.3.
 */
@Mixin(AbstractBlock.AbstractBlockState.class)
public abstract class AbstractBlockStateMixin {
    @Inject(method = "onBlockAdded(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;Z)V",
            at = @At("HEAD"), cancellable = true)
    private void sculptory$suppressOnBlockAdded(World world, BlockPos pos, BlockState oldState, boolean notify,
                                                   CallbackInfo ci) {
        if (EditScope.isSuppressing()) ci.cancel();
    }

    @Inject(method = "onStateReplaced(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;Z)V",
            at = @At("HEAD"), cancellable = true)
    private void sculptory$suppressOnStateReplaced(World world, BlockPos pos, BlockState newState, boolean moved,
                                                      CallbackInfo ci) {
        if (EditScope.isSuppressing()) ci.cancel();
    }
}
