package dev.sculptory.fabric.client.mixin;

import dev.sculptory.fabric.client.editor.EditorClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.PlayerAbilitiesS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The fly speed (editor scroll, {@code /flyspeed}): a server abilities update sets the player's own fly speed, which the
 * multiplier then scales ({@code FlySpeedApplier.rebase}). TAIL runs on the client thread only (the handler first hands
 * the packet over to it). Target verified with javap against yarn 1.21.1+build.3:
 * {@code public void onPlayerAbilities(PlayerAbilitiesS2CPacket)}.
 */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientAbilitiesMixin {
    @Inject(method = "onPlayerAbilities", at = @At("TAIL"))
    private void sculptory$abilitiesFromServer(PlayerAbilitiesS2CPacket packet, CallbackInfo ci) {
        EditorClient.instance().ifPresent(EditorClient::abilitiesFromServer);
    }
}
