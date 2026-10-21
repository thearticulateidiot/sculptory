package dev.sculptory.mc.mixin;

import dev.sculptory.mc.Sculptory;
import dev.sculptory.mc.engine.impl.EditServiceHost;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Crash-safe undo history: every chunk save of 1.21.1 goes through
 * {@code ChunkMap.save(ChunkAccess)} (autosave, {@code /save-all}, the incremental save of up to 20 chunks a
 * tick, unloading, shutdown; checked with javap against Minecraft 1.21.1 (Mojang names)). Right before it serializes the chunk,
 * {@link EditServiceHost#beforeChunkSave} journals the history of the engine's changes in that chunk and waits (briefly)
 * until the journal has reached the operating system, so after a crash the chunk on disk is never newer than its
 * history. Not required ({@code require = 0}): if another mod replaces the method, history is still saved every few
 * seconds, only without this ordering.
 */
@Mixin(ChunkMap.class)
public abstract class ServerChunkLoadingManagerMixin {
    @Shadow
    @Final
    ServerLevel level;

    @Inject(method = "save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z", at = @At("HEAD"), require = 0)
    private void sculptory$journalHistoryFirst(ChunkAccess chunk, CallbackInfoReturnable<Boolean> cir) {
        try {
            EditServiceHost.beforeChunkSave(level, chunk.getPos().x, chunk.getPos().z);
        } catch (RuntimeException | LinkageError e) {
            Sculptory.LOG.error("Sculptory: saving undo history before a chunk save failed", e);
        }
    }
}
