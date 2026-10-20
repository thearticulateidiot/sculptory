package dev.sculptory.fabric.engine.impl;

import dev.sculptory.fabric.world.WorldChecks;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * A server world as a {@link LevelReader} that never loads a chunk, for vanilla (and modded) rules asked while planning,
 * such as {@code BlockState.canPlaceAt}: a chunk that is not loaded reads as void air, with no fluid, no block entity
 * and an empty chunk. Everything else is the world's. One cell may be {@linkplain #assume assumed} to hold another state
 * (a scatter column's cell below the one asked about). Server thread only.
 */
final class LoadedOnlyView implements LevelReader {
    private final ServerLevel world;
    /** The assumed cell, or {@code null}. */
    private BlockPos assumedPos;
    private BlockState assumedState;

    LoadedOnlyView(ServerLevel world) {
        this.world = Objects.requireNonNull(world);
    }

    /** Whether chunk (cx, cz) is loaded (asking never loads it). */
    boolean loaded(int cx, int cz) {
        return WorldChecks.isChunkLoaded(world, cx, cz);
    }

    private boolean loaded(BlockPos pos) {
        return loaded(pos.getX() >> 4, pos.getZ() >> 4);
    }

    /**
     * Reads cell {@code pos} as {@code state} (with its fluid and no block entity) until {@link #forget}; one cell at a
     * time.
     */
    void assume(BlockPos pos, BlockState state) {
        assumedPos = pos.immutable();
        assumedState = Objects.requireNonNull(state);
    }

    /** Reads the assumed cell from the world again. */
    void forget() {
        assumedPos = null;
        assumedState = null;
    }

    private boolean assumed(BlockPos pos) {
        return assumedPos != null && assumedPos.equals(pos);
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        if (assumed(pos)) return assumedState;
        return loaded(pos) ? world.getBlockState(pos) : Blocks.VOID_AIR.defaultBlockState();
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        if (assumed(pos)) return assumedState.getFluidState();
        return loaded(pos) ? world.getFluidState(pos) : Fluids.EMPTY.defaultFluidState();
    }

    @Override
    @Nullable
    public BlockEntity getBlockEntity(BlockPos pos) {
        if (assumed(pos)) return null;
        return loaded(pos) ? world.getBlockEntity(pos) : null;
    }

    @Override
    @Nullable
    public ChunkAccess getChunk(int chunkX, int chunkZ, ChunkStatus leastStatus, boolean create) {
        if (loaded(chunkX, chunkZ)) return world.getChunk(chunkX, chunkZ, leastStatus, false);
        if (!create) return null;
        Holder<Biome> plains = world.registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS);
        return new EmptyLevelChunk(world, new ChunkPos(chunkX, chunkZ), plains);
    }

    @Override
    @Deprecated
    public boolean hasChunk(int chunkX, int chunkZ) {
        return loaded(chunkX, chunkZ);
    }

    @Override
    @Nullable
    public BlockGetter getChunkForCollisions(int chunkX, int chunkZ) {
        return loaded(chunkX, chunkZ) ? world.getChunkForCollisions(chunkX, chunkZ) : null;
    }

    @Override
    public int getHeight(Heightmap.Types heightmap, int x, int z) {
        return loaded(x >> 4, z >> 4) ? world.getHeight(heightmap, x, z) : world.getMinBuildHeight();
    }

    @Override
    public List<VoxelShape> getEntityCollisions(@Nullable Entity entity, AABB box) {
        return world.getEntityCollisions(entity, box);
    }

    @Override
    public int getSkyDarken() {
        return world.getSkyDarken();
    }

    /** The world's biome access reading through this view, so a biome lookup never loads a chunk either. */
    @Override
    public BiomeManager getBiomeManager() {
        return world.getBiomeManager().withDifferentSource(this);
    }

    @Override
    public Holder<Biome> getUncachedNoiseBiome(int biomeX, int biomeY, int biomeZ) {
        return world.getUncachedNoiseBiome(biomeX, biomeY, biomeZ);
    }

    @Override
    public boolean isClientSide() {
        return false;
    }

    @Override
    @Deprecated
    public int getSeaLevel() {
        return world.getSeaLevel();
    }

    @Override
    public DimensionType dimensionType() {
        return world.dimensionType();
    }

    @Override
    public RegistryAccess registryAccess() {
        return world.registryAccess();
    }

    @Override
    public FeatureFlagSet enabledFeatures() {
        return world.enabledFeatures();
    }

    @Override
    public float getShade(Direction direction, boolean shaded) {
        return world.getShade(direction, shaded);
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return world.getLightEngine();
    }

    @Override
    public WorldBorder getWorldBorder() {
        return world.getWorldBorder();
    }

    @Override
    public int getHeight() {
        return world.getHeight();
    }

    @Override
    public int getMinBuildHeight() {
        return world.getMinBuildHeight();
    }
}
