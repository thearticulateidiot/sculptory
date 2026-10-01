package dev.sculptory.fabric.mixin;

import dev.sculptory.fabric.builder.BuilderCapture;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Builder mode: every block change of a world passes {@link BuilderCapture},
 * which notes the cells a builder action changes and, for Keep shape, makes its writes physics-free. All of vanilla's
 * block changes of a world go through this overload. Descriptor verified with javap against yarn 1.21.1+build.3.
 */
@Mixin(World.class)
public abstract class BuilderCaptureMixin {
    @Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;II)Z",
            at = @At("HEAD"))
    private void sculptory$noteBuilderChange(BlockPos pos, BlockState state, int flags, int maxUpdateDepth,
                                                CallbackInfoReturnable<Boolean> cir) {
        BuilderCapture.beforeSetBlockState((World) (Object) this, pos, state);
    }

    @ModifyVariable(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;II)Z",
            at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int sculptory$keepShapeFlags(int flags) {
        return BuilderCapture.flags((World) (Object) this, flags);
    }

    @ModifyVariable(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;II)Z",
            at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private int sculptory$keepShapeDepth(int maxUpdateDepth) {
        return BuilderCapture.depth((World) (Object) this, maxUpdateDepth);
    }
}
