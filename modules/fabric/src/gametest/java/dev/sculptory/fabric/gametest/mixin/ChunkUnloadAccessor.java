package dev.sculptory.fabric.gametest.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Test mods only: tells when a chunk has really been unloaded. A chunk leaves {@code updatingChunkMap} first (then
 * {@code getChunkNow} answers null) and is saved a little later, when it leaves {@code pendingUnloads}; loaded again
 * before that, it is taken back from memory without being read. Fields checked with javap against Minecraft 1.21.1 (Mojang names).
 */
@Mixin(ChunkMap.class)
public interface ChunkUnloadAccessor {
    @Accessor("updatingChunkMap")
    Long2ObjectLinkedOpenHashMap<ChunkHolder> sculptory$currentChunkHolders();

    @Accessor("pendingUnloads")
    Long2ObjectLinkedOpenHashMap<ChunkHolder> sculptory$chunksToUnload();
}
