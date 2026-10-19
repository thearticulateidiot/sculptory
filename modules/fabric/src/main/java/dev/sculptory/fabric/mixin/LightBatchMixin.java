package dev.sculptory.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.sculptory.fabric.world.LightBatch;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.light.LightingProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The light check a block change asks of the light engine goes to the {@link LightBatch} collecting the current write,
 * if any (one of the writer's physics-off writes), else to the light engine as usual. Optional ({@code require = 0}):
 * where another mod has rewritten the method so the call is not there, nothing is wrapped and every check goes the
 * vanilla way. Descriptors verified with javap against yarn 1.21.1+build.3.
 */
@Mixin(WorldChunk.class)
public abstract class LightBatchMixin {
    @WrapOperation(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;Z)Lnet/minecraft/block/BlockState;",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/chunk/light/LightingProvider;checkBlock(Lnet/minecraft/util/math/BlockPos;)V"),
            require = 0)
    private void sculptory$batchLightCheck(LightingProvider light, BlockPos pos, Operation<Void> original) {
        if (!LightBatch.take(light, pos)) original.call(light, pos);
    }
}
