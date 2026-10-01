package dev.sculptory.fabric.gametest.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ServerChunkLoadingManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Test mods only: tells when a chunk has really been unloaded. A chunk leaves {@code currentChunkHolders} first (then
 * {@code getWorldChunk} answers null) and is saved a little later, when it leaves {@code chunksToUnload}; loaded again
 * before that, it is taken back from memory without being read. Fields checked with javap against yarn 1.21.1+build.3.
 */
@Mixin(ServerChunkLoadingManager.class)
public interface ChunkUnloadAccessor {
    @Accessor("currentChunkHolders")
    Long2ObjectLinkedOpenHashMap<ChunkHolder> sculptory$currentChunkHolders();

    @Accessor("chunksToUnload")
    Long2ObjectLinkedOpenHashMap<ChunkHolder> sculptory$chunksToUnload();
}
