package dev.sculptory.mc.mixin;

import dev.sculptory.mc.builder.BuilderCapture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Builder mode: every block change of a world passes {@link BuilderCapture},
 * which notes the cells a builder action changes and, for Keep shape, makes its writes physics-free. All of vanilla's
 * block changes of a world go through this overload. Descriptor verified with javap against Minecraft 1.21.1 (Mojang names).
 */
@Mixin(Level.class)
public abstract class BuilderCaptureMixin {
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"))
    private void sculptory$noteBuilderChange(BlockPos pos, BlockState state, int flags, int maxUpdateDepth,
                                                CallbackInfoReturnable<Boolean> cir) {
        BuilderCapture.beforeSetBlockState((Level) (Object) this, pos, state);
    }

    @ModifyVariable(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int sculptory$keepShapeFlags(int flags) {
        return BuilderCapture.flags((Level) (Object) this, flags);
    }

    @ModifyVariable(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private int sculptory$keepShapeDepth(int maxUpdateDepth) {
        return BuilderCapture.depth((Level) (Object) this, maxUpdateDepth);
    }
}
