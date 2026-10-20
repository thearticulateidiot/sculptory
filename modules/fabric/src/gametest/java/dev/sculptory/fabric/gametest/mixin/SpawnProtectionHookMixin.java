package dev.sculptory.fabric.gametest.mixin;

import dev.sculptory.fabric.gametest.ProtectionHook;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Test mod only (sculptory-gametest): lets a test protect columns for chosen players the way spawn protection or a
 * claim mod does, through {@code ServerLevel.mayInteract} ({@link ProtectionHook}); the test server has no spawn
 * protection of its own. {@code MinecraftServer.isUnderSpawnProtection(ServerLevel, BlockPos, Player)} checked with
 * javap against Minecraft 1.21.1 (Mojang names).
 */
@Mixin(MinecraftServer.class)
public abstract class SpawnProtectionHookMixin {
    @Inject(method = "isUnderSpawnProtection", at = @At("HEAD"), cancellable = true)
    private void sculptory$protectForTests(ServerLevel world, BlockPos pos, Player player,
                                              CallbackInfoReturnable<Boolean> cir) {
        if (ProtectionHook.protects(world, pos, player)) cir.setReturnValue(true);
    }
}
