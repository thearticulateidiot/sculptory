package dev.sculptory.neoforge.client.mixin;

import dev.sculptory.neoforge.client.NeoForgeClientLoader;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code ClientLoader.onClientStopping}: in {@code Minecraft.destroy}, right after its "Stopping!" log line, where Fabric
 * API's {@code MinecraftClientMixin} fires {@code CLIENT_STOPPING} (fabric-lifecycle-events-v1 in Fabric API 0.116.4,
 * read with javap: INVOKE {@code Logger.info(String)}, shift AFTER, in Yarn's {@code MinecraftClient.stop}, Mojang's
 * {@code destroy}). The game loop has ended, so no tick or frame follows; the world is still there (it is disconnected
 * next) and GL is current. NeoForge's {@code GameShuttingDownEvent} comes from {@code Minecraft.stop}, which only asks
 * the loop to end: a tick and a frame still run after it. NeoForge 21.1 leaves {@code destroy} as vanilla has it, with
 * that one {@code Logger.info(String)} call (javap).
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "destroy", at = @At(value = "INVOKE", target = "Lorg/slf4j/Logger;info(Ljava/lang/String;)V",
            ordinal = 0, shift = At.Shift.AFTER))
    private void sculptory$clientStopping(CallbackInfo ci) {
        NeoForgeClientLoader.clientStopping();
    }
}
