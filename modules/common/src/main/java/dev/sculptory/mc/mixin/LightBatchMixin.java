package dev.sculptory.mc.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.sculptory.mc.world.LightBatch;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The light check a block change asks of the light engine goes to the {@link LightBatch} collecting the current write,
 * if any (one of the writer's physics-off writes), else to the light engine as usual. Optional ({@code require = 0}):
 * where another mod has rewritten the method so the call is not there, nothing is wrapped and every check goes the
 * vanilla way. Descriptors verified with javap against Minecraft 1.21.1 (Mojang names).
 */
@Mixin(LevelChunk.class)
public abstract class LightBatchMixin {
    @WrapOperation(method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/lighting/LevelLightEngine;checkBlock(Lnet/minecraft/core/BlockPos;)V"),
            require = 0)
    private void sculptory$batchLightCheck(LevelLightEngine light, BlockPos pos, Operation<Void> original) {
        if (!LightBatch.take(light, pos)) original.call(light, pos);
    }
}
