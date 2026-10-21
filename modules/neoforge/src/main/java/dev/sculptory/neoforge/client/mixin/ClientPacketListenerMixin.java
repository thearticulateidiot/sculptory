package dev.sculptory.neoforge.client.mixin;

import dev.sculptory.neoforge.client.NeoForgeClientLoader;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code ClientLoader.onJoin}: at the end of {@code handleLogin}, where Fabric API's networking mixin fires
 * {@code ClientPlayConnectionEvents.JOIN} (fabric-networking-api-v1 in Fabric API 0.116.4, read with javap: RETURN of
 * {@code onGameJoin}). Registry sync and the configuration phase's tags are done, the local player has its entity id and
 * is in the client world. The render thread only reaches it ({@code handleLogin} first hands the packet over). NeoForge
 * 21.1's patched method has a single return (javap), so TAIL is that return.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    @Inject(method = "handleLogin", at = @At("TAIL"))
    private void sculptory$joined(ClientboundLoginPacket packet, CallbackInfo ci) {
        NeoForgeClientLoader.joined();
    }
}
