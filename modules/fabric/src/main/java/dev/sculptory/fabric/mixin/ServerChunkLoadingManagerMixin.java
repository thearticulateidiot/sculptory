package dev.sculptory.fabric.mixin;

import dev.sculptory.fabric.SculptoryMod;
import dev.sculptory.fabric.engine.impl.EditServiceHost;
import net.minecraft.server.world.ServerChunkLoadingManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Crash-safe undo history: every chunk save of 1.21.1 goes through
 * {@code ServerChunkLoadingManager.save(Chunk)} (autosave, {@code /save-all}, the incremental save of up to 20 chunks a
 * tick, unloading, shutdown; checked with javap against yarn 1.21.1+build.3). Right before it serializes the chunk,
 * {@link EditServiceHost#beforeChunkSave} journals the history of the engine's changes in that chunk and waits (briefly)
 * until the journal has reached the operating system, so after a crash the chunk on disk is never newer than its
 * history. Not required ({@code require = 0}): if another mod replaces the method, history is still saved every few
 * seconds, only without this ordering.
 */
@Mixin(ServerChunkLoadingManager.class)
public abstract class ServerChunkLoadingManagerMixin {
    @Shadow
    @Final
    ServerWorld world;

    @Inject(method = "save(Lnet/minecraft/world/chunk/Chunk;)Z", at = @At("HEAD"), require = 0)
    private void sculptory$journalHistoryFirst(Chunk chunk, CallbackInfoReturnable<Boolean> cir) {
        try {
            EditServiceHost.beforeChunkSave(world, chunk.getPos().x, chunk.getPos().z);
        } catch (RuntimeException | LinkageError e) {
            SculptoryMod.LOG.error("Sculptory: saving undo history before a chunk save failed", e);
        }
    }
}
