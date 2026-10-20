package dev.sculptory.fabric.mixin;

import dev.sculptory.fabric.SculptoryMod;
import dev.sculptory.fabric.engine.impl.EditServiceHost;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.entity.ChunkEntities;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Crash-safe undo history of entities: a chunk's entities are saved apart from its
 * blocks, through {@code EntityStorage.storeEntities} (checked with javap against Minecraft 1.21.1 (Mojang names)), so the
 * same barrier as {@link ServerChunkLoadingManagerMixin} runs right before it: the history of the entities the engine
 * placed or removed there reaches the operating system first. Not required ({@code require = 0}).
 */
@Mixin(EntityStorage.class)
public abstract class EntityChunkDataAccessMixin {
    @Shadow
    @Final
    private ServerLevel level;

    @Inject(method = "storeEntities(Lnet/minecraft/world/level/entity/ChunkEntities;)V", at = @At("HEAD"), require = 0)
    private void sculptory$journalEntitiesFirst(ChunkEntities<Entity> dataList, CallbackInfo ci) {
        try {
            ChunkPos pos = dataList.getPos();
            EditServiceHost.beforeChunkSave(level, pos.x, pos.z);
        } catch (RuntimeException | LinkageError e) {
            SculptoryMod.LOG.error("Sculptory: saving undo history before an entity chunk save failed", e);
        }
    }
}
