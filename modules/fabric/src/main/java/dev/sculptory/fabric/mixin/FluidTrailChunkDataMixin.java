package dev.sculptory.fabric.mixin;

import dev.sculptory.fabric.SculptoryMod;
import dev.sculptory.fabric.world.FluidTrails;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.ChunkSerializer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ProtoChunk;
import net.minecraft.world.poi.PointOfInterestStorage;
import net.minecraft.world.storage.StorageKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fluid trails survive a restart: a chunk's fluid marks are saved in the
 * chunk's own data, so they are exactly as fresh as its blocks, and read back when it loads, before it ticks. In
 * 1.21.1 both run on the server thread: {@code serialize} from {@code ServerChunkLoadingManager.save(Chunk)} (after the
 * chunk-save hook, {@link ServerChunkLoadingManagerMixin}, folded the column's trails into their entries),
 * {@code deserialize} on the chunk manager's main-thread executor (checked with javap against yarn 1.21.1+build.3). Not
 * required ({@code require = 0}): if another mod replaces them, marks are not saved and edited fluid is not followed
 * after a restart (the history itself is saved as before); {@code FluidRestartGameTest} checks the wiring.
 */
@Mixin(ChunkSerializer.class)
public abstract class FluidTrailChunkDataMixin {
    @Inject(method = "serialize", at = @At("RETURN"), require = 0)
    private static void sculptory$saveFluidMarks(ServerWorld world, Chunk chunk,
                                                    CallbackInfoReturnable<NbtCompound> cir) {
        try {
            FluidTrails.chunkSaved(world, chunk.getPos(), cir.getReturnValue(), chunk);
        } catch (RuntimeException | LinkageError e) {
            SculptoryMod.LOG.error("Sculptory: saving the fluid marks of a chunk failed", e);
        }
    }

    @Inject(method = "deserialize", at = @At("RETURN"), require = 0)
    private static void sculptory$loadFluidMarks(ServerWorld world, PointOfInterestStorage poiStorage,
                                                    StorageKey key, ChunkPos pos, NbtCompound nbt,
                                                    CallbackInfoReturnable<ProtoChunk> cir) {
        try {
            FluidTrails.chunkLoaded(world, pos, nbt);
        } catch (RuntimeException | LinkageError e) {
            SculptoryMod.LOG.error("Sculptory: reading the fluid marks of a chunk failed", e);
        }
    }
}
