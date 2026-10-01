package dev.sculptory.fabric.mixin;

import dev.sculptory.fabric.world.FluidTrails;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fluid trails: every block change of a server world passes {@link FluidTrails}, which
 * records it during a tick it follows and otherwise clears the cell's mark. All of vanilla's block changes of a world
 * go through this overload. Descriptor verified with javap against yarn 1.21.1+build.3.
 */
@Mixin(World.class)
public abstract class FluidTrailBlockChangeMixin {
    @Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;II)Z",
            at = @At("HEAD"))
    private void sculptory$beforeBlockChange(BlockPos pos, BlockState state, int flags, int maxUpdateDepth,
                                                CallbackInfoReturnable<Boolean> cir) {
        FluidTrails.beforeSetBlockState((World) (Object) this, pos, state);
    }
}
