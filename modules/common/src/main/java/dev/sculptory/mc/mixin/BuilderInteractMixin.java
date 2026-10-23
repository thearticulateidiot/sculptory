package dev.sculptory.mc.mixin;

import dev.sculptory.mc.engine.impl.EditServiceHost;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Builder mode: a right-click or a punch on an item frame, painting or armor stand is one history step when it changed
 * the entity (an item put in or taken out, a frame turned, a stand dressed, the entity broken). The entity is read
 * once the packet runs on the server thread (after {@code ensureRunningOnSameThread}, which on the network thread
 * hands the packet over and throws, so nothing happens there) and compared when the handler returns. A handler that
 * throws leaves the read behind: the next packet replaces it. Descriptors verified with javap against Minecraft 1.21.1
 * (Mojang names), Fabric's and NeoForge's.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class BuilderInteractMixin {
    @Shadow
    public ServerPlayer player;

    /** What the entity was before the handler ran, while builder mode records it ({@code BuilderService.EntityTouch}). */
    @Unique
    private Object sculptory$touch;

    @Inject(method = "handleInteract", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
            shift = At.Shift.AFTER))
    private void sculptory$beforeInteract(ServerboundInteractPacket packet, CallbackInfo ci) {
        sculptory$touch = EditServiceHost.beforeEntityInteract(player, packet.getTarget(player.serverLevel()));
    }

    @Inject(method = "handleInteract", at = @At("RETURN"))
    private void sculptory$afterInteract(ServerboundInteractPacket packet, CallbackInfo ci) {
        Object touch = sculptory$touch;
        sculptory$touch = null;
        if (touch != null) EditServiceHost.afterEntityInteract(touch);
    }
}
