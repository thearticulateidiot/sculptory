package dev.sculptory.fabric.mixin;

import dev.sculptory.fabric.SculptoryMod;
import dev.sculptory.fabric.engine.impl.EditServiceHost;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.storage.ChunkDataList;
import net.minecraft.world.storage.EntityChunkDataAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Crash-safe undo history of entities: a chunk's entities are saved apart from its
 * blocks, through {@code EntityChunkDataAccess.writeChunkData} (checked with javap against yarn 1.21.1+build.3), so the
 * same barrier as {@link ServerChunkLoadingManagerMixin} runs right before it: the history of the entities the engine
 * placed or removed there reaches the operating system first. Not required ({@code require = 0}).
 */
@Mixin(EntityChunkDataAccess.class)
public abstract class EntityChunkDataAccessMixin {
    @Shadow
    @Final
    private ServerWorld world;

    @Inject(method = "writeChunkData(Lnet/minecraft/world/storage/ChunkDataList;)V", at = @At("HEAD"), require = 0)
    private void sculptory$journalEntitiesFirst(ChunkDataList<Entity> dataList, CallbackInfo ci) {
        try {
            ChunkPos pos = dataList.getChunkPos();
            EditServiceHost.beforeChunkSave(world, pos.x, pos.z);
        } catch (RuntimeException | LinkageError e) {
            SculptoryMod.LOG.error("Sculptory: saving undo history before an entity chunk save failed", e);
        }
    }
}
