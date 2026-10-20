package dev.sculptory.fabric.mixin;

import dev.sculptory.fabric.SculptoryMod;
import dev.sculptory.fabric.world.FluidTrails;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fluid trails survive a restart: a chunk's fluid marks are saved in the
 * chunk's own data, so they are exactly as fresh as its blocks, and read back when it loads, before it ticks. In
 * 1.21.1 both run on the server thread: {@code write} from {@code ChunkMap.save(ChunkAccess)} (after the
 * chunk-save hook, {@link ServerChunkLoadingManagerMixin}, folded the column's trails into their entries),
 * {@code read} on the chunk manager's main-thread executor (checked with javap against Minecraft 1.21.1 (Mojang names)). Not
 * required ({@code require = 0}): if another mod replaces them, marks are not saved and edited fluid is not followed
 * after a restart (the history itself is saved as before); {@code FluidRestartGameTest} checks the wiring.
 */
@Mixin(ChunkSerializer.class)
public abstract class FluidTrailChunkDataMixin {
    @Inject(method = "write", at = @At("RETURN"), require = 0)
    private static void sculptory$saveFluidMarks(ServerLevel world, ChunkAccess chunk,
                                                    CallbackInfoReturnable<CompoundTag> cir) {
        try {
            FluidTrails.chunkSaved(world, chunk.getPos(), cir.getReturnValue(), chunk);
        } catch (RuntimeException | LinkageError e) {
            SculptoryMod.LOG.error("Sculptory: saving the fluid marks of a chunk failed", e);
        }
    }

    @Inject(method = "read", at = @At("RETURN"), require = 0)
    private static void sculptory$loadFluidMarks(ServerLevel world, PoiManager poiStorage,
                                                    RegionStorageInfo key, ChunkPos pos, CompoundTag nbt,
                                                    CallbackInfoReturnable<ProtoChunk> cir) {
        try {
            FluidTrails.chunkLoaded(world, pos, nbt);
        } catch (RuntimeException | LinkageError e) {
            SculptoryMod.LOG.error("Sculptory: reading the fluid marks of a chunk failed", e);
        }
    }
}
