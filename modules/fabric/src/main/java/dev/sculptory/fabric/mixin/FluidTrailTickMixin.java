package dev.sculptory.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.sculptory.fabric.world.FluidTrails;
import net.minecraft.block.BlockState;
import net.minecraft.fluid.FluidState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Fluid trails: the ticks that let fluid a history step wrote change the world are
 * recorded for that step's entry ({@link FluidTrails}): scheduled fluid ticks, the ice-and-snow tick (freezing), and
 * the random ticks of grass under the fluid, of the fluid itself (lava's fire) and of the ice it froze into (melting).
 * Descriptors verified with javap against yarn 1.21.1+build.3.
 */
@Mixin(ServerWorld.class)
public abstract class FluidTrailTickMixin {
    @WrapOperation(method = "tickFluid", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/fluid/FluidState;onScheduledTick(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;)V"))
    private void sculptory$recordFluidTick(FluidState state, World world, BlockPos pos, Operation<Void> original) {
        FluidTrails.Recording recording = FluidTrails.fluidTick((ServerWorld) (Object) this, pos);
        if (recording == FluidTrails.Recording.SKIPPED) {
            // A step of the fluid's entry is writing: the tick runs again once the fluid's own delay has passed.
            world.scheduleFluidTick(pos, state.getFluid(), state.getFluid().getTickRate(world));
            return;
        }
        try (recording) {
            original.call(state, world, pos);
        }
    }

    @WrapOperation(method = "tickChunk", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/block/BlockState;randomTick(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/random/Random;)V"))
    private void sculptory$recordBlockRandomTick(BlockState state, ServerWorld world, BlockPos pos, Random random,
                                                    Operation<Void> original) {
        FluidTrails.Recording recording = FluidTrails.blockRandomTick(world, pos, state);
        if (recording == FluidTrails.Recording.SKIPPED) return;
        try (recording) {
            original.call(state, world, pos, random);
        }
    }

    /** The world's ice-and-snow tick of a column (public; called from {@code tickChunk}): a cold biome freezes water. */
    @WrapMethod(method = "tickIceAndSnow(Lnet/minecraft/util/math/BlockPos;)V")
    private void sculptory$recordIceAndSnow(BlockPos pos, Operation<Void> original) {
        FluidTrails.Recording recording = FluidTrails.iceAndSnowTick((ServerWorld) (Object) this, pos);
        if (recording == FluidTrails.Recording.SKIPPED) return;
        try (recording) {
            original.call(pos);
        }
    }

    @WrapOperation(method = "tickChunk", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/fluid/FluidState;onRandomTick(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/random/Random;)V"))
    private void sculptory$recordFluidRandomTick(FluidState state, World world, BlockPos pos, Random random,
                                                    Operation<Void> original) {
        FluidTrails.Recording recording = FluidTrails.fluidRandomTick((ServerWorld) (Object) this, pos);
        if (recording == FluidTrails.Recording.SKIPPED) return;
        try (recording) {
            original.call(state, world, pos, random);
        }
    }
}
