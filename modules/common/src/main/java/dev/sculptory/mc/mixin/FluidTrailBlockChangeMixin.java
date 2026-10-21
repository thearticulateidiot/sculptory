package dev.sculptory.mc.mixin;

import dev.sculptory.mc.world.FluidTrails;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fluid trails: every block change of a server world passes {@link FluidTrails}, which
 * records it during a tick it follows and otherwise clears the cell's mark. All of vanilla's block changes of a world
 * go through this overload. Descriptor verified with javap against Minecraft 1.21.1 (Mojang names).
 */
@Mixin(Level.class)
public abstract class FluidTrailBlockChangeMixin {
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"))
    private void sculptory$beforeBlockChange(BlockPos pos, BlockState state, int flags, int maxUpdateDepth,
                                                CallbackInfoReturnable<Boolean> cir) {
        FluidTrails.beforeSetBlockState((Level) (Object) this, pos, state);
    }
}
