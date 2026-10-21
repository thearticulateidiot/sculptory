package dev.sculptory.mc.client.mixin;

import dev.sculptory.mc.client.editor.EditorClient;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The fly speed (editor scroll, {@code /flyspeed}): a server abilities update sets the player's own fly speed, which the
 * multiplier then scales ({@code FlySpeedApplier.rebase}). TAIL runs on the client thread only (the handler first hands
 * the packet over to it). Target verified with javap against Minecraft 1.21.1 (Mojang names):
 * {@code public void handlePlayerAbilities(ClientboundPlayerAbilitiesPacket)}.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientAbilitiesMixin {
    @Inject(method = "handlePlayerAbilities", at = @At("TAIL"))
    private void sculptory$abilitiesFromServer(ClientboundPlayerAbilitiesPacket packet, CallbackInfo ci) {
        EditorClient.instance().ifPresent(EditorClient::abilitiesFromServer);
    }
}
