package dev.sculptory.fabric.client.mixin;

import dev.sculptory.fabric.client.editor.biomes.BiomeView;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.ChunkBiomeDataS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells Biome view ({@link BiomeView#changed}) that the server sent biomes (protocol 6: a painted or filled biome,
 * through the game's own biome packet), so its tint and legend are taken again.
 *
 * <p>Target checked with javap against yarn 1.21.1+build.3: {@code ClientPlayNetworkHandler.onChunkBiomeData(
 * ChunkBiomeDataS2CPacket)}. It forces itself onto the client thread first, so the tail runs there, after the chunks'
 * biomes are read.
 */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientBiomeDataMixin {
    @Inject(method = "onChunkBiomeData", at = @At("TAIL"))
    private void sculptory$biomesArrived(ChunkBiomeDataS2CPacket packet, CallbackInfo ci) {
        BiomeView.changed();
    }
}
