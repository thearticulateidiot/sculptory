package dev.sculptory.mc.client.mixin;

import dev.sculptory.mc.client.editor.biomes.BiomeView;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells Biome view ({@link BiomeView#changed}) that the server sent biomes (protocol 6: a painted or filled biome,
 * through the game's own biome packet), so its tint and legend are taken again.
 *
 * <p>Target checked with javap against Minecraft 1.21.1 (Mojang names): {@code ClientPacketListener.handleChunksBiomes(
 * ClientboundChunksBiomesPacket)}. It forces itself onto the client thread first, so the tail runs there, after the chunks'
 * biomes are read.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientBiomeDataMixin {
    @Inject(method = "handleChunksBiomes", at = @At("TAIL"))
    private void sculptory$biomesArrived(ClientboundChunksBiomesPacket packet, CallbackInfo ci) {
        BiomeView.changed();
    }
}
