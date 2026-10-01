package dev.sculptory.fabric.mixin;

import com.mojang.authlib.GameProfile;
import dev.sculptory.fabric.SculptoryMod;
import dev.sculptory.fabric.engine.impl.EditServiceHost;
import dev.sculptory.fabric.net.ServerNet;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies permission changes as they happen (verified with javap against yarn 1.21.1+build.3):
 * <ul>
 *   <li>Vanilla resends a player's command tree ({@code sendCommandTree(ServerPlayerEntity)}) whenever their rights
 *       may have changed: op and deop, joining, respawning, and changing world ({@code ServerPlayerEntity.teleportTo},
 *       before the player is moved, so the player's world is still the old one). There
 *       {@link ServerNet#permissionsChanged} re-reads the player's nodes and limits and pushes
 *       {@code PermissionsChanged} when they changed, and {@link EditServiceHost#permissionsChanged} re-checks the
 *       player's jobs <em>in the world they are in</em>: a permissions mod may grant a node in one world only, and a
 *       job the player left behind in another world must not be cancelled because the check ran from there.</li>
 *   <li>{@code addToOperators} and {@code removeFromOperators} ({@code /op}, {@code /deop}) change rights that hold in
 *       every world, so after them the player's jobs are re-checked in every world.</li>
 * </ul>
 * Each call runs on its own: a failure in one never skips another or reaches the vanilla code ({@code /deop}).
 */
@Mixin(PlayerManager.class)
public abstract class PlayerManagerMixin {
    @Shadow
    public abstract ServerPlayerEntity getPlayer(java.util.UUID uuid);

    @Inject(method = "sendCommandTree(Lnet/minecraft/server/network/ServerPlayerEntity;)V", at = @At("TAIL"))
    private void sculptory$recheckEditorPermissions(ServerPlayerEntity player, CallbackInfo ci) {
        try {
            ServerNet.permissionsChanged(player);
        } catch (RuntimeException | LinkageError e) {
            SculptoryMod.LOG.error("Sculptory: pushing the editor permissions failed", e);
        }
        try {
            EditServiceHost.permissionsChanged(player, false);
        } catch (RuntimeException | LinkageError e) {
            SculptoryMod.LOG.error("Sculptory: re-checking the editor jobs' permissions failed", e);
        }
    }

    @Inject(method = "addToOperators(Lcom/mojang/authlib/GameProfile;)V", at = @At("TAIL"))
    private void sculptory$recheckAfterOp(GameProfile profile, CallbackInfo ci) {
        recheckEveryWorld(profile);
    }

    @Inject(method = "removeFromOperators(Lcom/mojang/authlib/GameProfile;)V", at = @At("TAIL"))
    private void sculptory$recheckAfterDeop(GameProfile profile, CallbackInfo ci) {
        recheckEveryWorld(profile);
    }

    private void recheckEveryWorld(GameProfile profile) {
        try {
            ServerPlayerEntity player = profile == null || profile.getId() == null ? null : getPlayer(profile.getId());
            if (player != null) EditServiceHost.permissionsChanged(player, true);
        } catch (RuntimeException | LinkageError e) {
            SculptoryMod.LOG.error("Sculptory: re-checking the editor jobs' permissions after an op change failed", e);
        }
    }
}
