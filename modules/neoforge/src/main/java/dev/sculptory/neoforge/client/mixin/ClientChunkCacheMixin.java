package dev.sculptory.neoforge.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.sculptory.neoforge.client.NeoForgeClientLoader;
import java.util.function.Consumer;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@code ClientLoader.onChunkUnload} for the two ways a client column leaves the world that NeoForge posts no
 * {@code ChunkEvent.Unload} for, at the points where Fabric API's {@code ClientChunkManagerMixin}
 * (fabric-lifecycle-events-v1 in Fabric API 0.116.4, read with javap) fires {@code CHUNK_UNLOAD}:
 * <ul>
 *   <li>{@code replaceWithPacketData}, just before it builds a new column for a storage slot that still holds a column
 *       at another position (the view moved on before that column's forget packet came): the old column is replaced
 *       and its later forget packet finds nothing to drop;</li>
 *   <li>{@code updateViewRadius}, for each column outside the new, smaller radius: it is left out of the new storage,
 *       and its later forget packet is ignored as out of range.</li>
 * </ul>
 * The third way, {@code drop} (a forget packet for a loaded column), is NeoForge's own {@code ChunkEvent.Unload}, so
 * nothing is injected there. NeoForge 21.1 leaves both methods as vanilla has them (its only change to the class is the
 * two chunk events, read in the patched source and checked with javap).
 */
@Mixin(ClientChunkCache.class)
public abstract class ClientChunkCacheMixin {
    @Shadow
    @Final
    ClientLevel level;

    @Inject(method = "replaceWithPacketData",
            at = @At(value = "NEW", target = "net/minecraft/world/level/chunk/LevelChunk"))
    private void sculptory$replacingOtherColumn(int x, int z, FriendlyByteBuf buffer, CompoundTag tag,
                                                Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> consumer,
                                                CallbackInfoReturnable<LevelChunk> cir, @Local LevelChunk previous) {
        // Null when the slot was empty; otherwise a column at another position (the same position is updated in place).
        if (previous != null) NeoForgeClientLoader.chunkUnloaded(level, previous);
    }

    @ModifyExpressionValue(method = "updateViewRadius", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientChunkCache$Storage;inRange(II)Z"))
    private boolean sculptory$outsideNewRadius(boolean inRange, @Local LevelChunk chunk) {
        if (!inRange) NeoForgeClientLoader.chunkUnloaded(level, chunk);
        return inRange;
    }
}
