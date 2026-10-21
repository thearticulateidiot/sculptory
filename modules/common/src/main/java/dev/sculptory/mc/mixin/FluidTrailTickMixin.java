package dev.sculptory.mc.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.sculptory.mc.world.FluidTrails;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Fluid trails: the ticks that let fluid a history step wrote change the world are
 * recorded for that step's entry ({@link FluidTrails}): scheduled fluid ticks, the ice-and-snow tick (freezing), and
 * the random ticks of grass under the fluid, of the fluid itself (lava's fire) and of the ice it froze into (melting).
 * Descriptors verified with javap against Minecraft 1.21.1 (Mojang names).
 */
@Mixin(ServerLevel.class)
public abstract class FluidTrailTickMixin {
    @WrapOperation(method = "tickFluid", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/material/FluidState;tick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V"))
    private void sculptory$recordFluidTick(FluidState state, Level world, BlockPos pos, Operation<Void> original) {
        FluidTrails.Recording recording = FluidTrails.fluidTick((ServerLevel) (Object) this, pos);
        if (recording == FluidTrails.Recording.SKIPPED) {
            // A step of the fluid's entry is writing: the tick runs again once the fluid's own delay has passed.
            world.scheduleTick(pos, state.getType(), state.getType().getTickDelay(world));
            return;
        }
        try (recording) {
            original.call(state, world, pos);
        }
    }

    @WrapOperation(method = "tickChunk", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;randomTick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V"))
    private void sculptory$recordBlockRandomTick(BlockState state, ServerLevel world, BlockPos pos, RandomSource random,
                                                    Operation<Void> original) {
        FluidTrails.Recording recording = FluidTrails.blockRandomTick(world, pos, state);
        if (recording == FluidTrails.Recording.SKIPPED) return;
        try (recording) {
            original.call(state, world, pos, random);
        }
    }

    /** The world's ice-and-snow tick of a column (public; called from {@code tickChunk}): a cold biome freezes water. */
    @WrapMethod(method = "tickPrecipitation(Lnet/minecraft/core/BlockPos;)V")
    private void sculptory$recordIceAndSnow(BlockPos pos, Operation<Void> original) {
        FluidTrails.Recording recording = FluidTrails.iceAndSnowTick((ServerLevel) (Object) this, pos);
        if (recording == FluidTrails.Recording.SKIPPED) return;
        try (recording) {
            original.call(pos);
        }
    }

    @WrapOperation(method = "tickChunk", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/material/FluidState;randomTick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V"))
    private void sculptory$recordFluidRandomTick(FluidState state, Level world, BlockPos pos, RandomSource random,
                                                    Operation<Void> original) {
        FluidTrails.Recording recording = FluidTrails.fluidRandomTick((ServerLevel) (Object) this, pos);
        if (recording == FluidTrails.Recording.SKIPPED) return;
        try (recording) {
            original.call(state, world, pos, random);
        }
    }
}
