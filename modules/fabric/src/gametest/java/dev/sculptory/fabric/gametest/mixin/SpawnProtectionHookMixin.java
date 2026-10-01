package dev.sculptory.fabric.gametest.mixin;

import dev.sculptory.fabric.gametest.ProtectionHook;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Test mod only (sculptory-gametest): lets a test protect columns for chosen players the way spawn protection or a
 * claim mod does, through {@code ServerWorld.canPlayerModifyAt} ({@link ProtectionHook}); the test server has no spawn
 * protection of its own. {@code MinecraftServer.isSpawnProtected(ServerWorld, BlockPos, PlayerEntity)} checked with
 * javap against yarn 1.21.1+build.3.
 */
@Mixin(MinecraftServer.class)
public abstract class SpawnProtectionHookMixin {
    @Inject(method = "isSpawnProtected", at = @At("HEAD"), cancellable = true)
    private void sculptory$protectForTests(ServerWorld world, BlockPos pos, PlayerEntity player,
                                              CallbackInfoReturnable<Boolean> cir) {
        if (ProtectionHook.protects(world, pos, player)) cir.setReturnValue(true);
    }
}
