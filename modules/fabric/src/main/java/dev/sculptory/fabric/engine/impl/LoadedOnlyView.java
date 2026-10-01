package dev.sculptory.fabric.engine.impl;

import dev.sculptory.fabric.world.WorldChecks;
import java.util.List;
import java.util.Objects;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.resource.featuretoggle.FeatureSet;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.Heightmap;
import net.minecraft.world.WorldView;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.world.biome.source.BiomeAccess;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraft.world.chunk.light.LightingProvider;
import net.minecraft.world.dimension.DimensionType;
import org.jetbrains.annotations.Nullable;

/**
 * A server world as a {@link WorldView} that never loads a chunk, for vanilla (and modded) rules asked while planning,
 * such as {@code BlockState.canPlaceAt}: a chunk that is not loaded reads as void air, with no fluid, no block entity
 * and an empty chunk. Everything else is the world's. One cell may be {@linkplain #assume assumed} to hold another state
 * (a scatter column's cell below the one asked about). Server thread only.
 */
final class LoadedOnlyView implements WorldView {
    private final ServerWorld world;
    /** The assumed cell, or {@code null}. */
    private BlockPos assumedPos;
    private BlockState assumedState;

    LoadedOnlyView(ServerWorld world) {
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
        assumedPos = pos.toImmutable();
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
        return loaded(pos) ? world.getBlockState(pos) : Blocks.VOID_AIR.getDefaultState();
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        if (assumed(pos)) return assumedState.getFluidState();
        return loaded(pos) ? world.getFluidState(pos) : Fluids.EMPTY.getDefaultState();
    }

    @Override
    @Nullable
    public BlockEntity getBlockEntity(BlockPos pos) {
        if (assumed(pos)) return null;
        return loaded(pos) ? world.getBlockEntity(pos) : null;
    }

    @Override
    @Nullable
    public Chunk getChunk(int chunkX, int chunkZ, ChunkStatus leastStatus, boolean create) {
        if (loaded(chunkX, chunkZ)) return world.getChunk(chunkX, chunkZ, leastStatus, false);
        if (!create) return null;
        RegistryEntry<Biome> plains = world.getRegistryManager().get(RegistryKeys.BIOME).entryOf(BiomeKeys.PLAINS);
        return new EmptyChunk(world, new ChunkPos(chunkX, chunkZ), plains);
    }

    @Override
    @Deprecated
    public boolean isChunkLoaded(int chunkX, int chunkZ) {
        return loaded(chunkX, chunkZ);
    }

    @Override
    @Nullable
    public BlockView getChunkAsView(int chunkX, int chunkZ) {
        return loaded(chunkX, chunkZ) ? world.getChunkAsView(chunkX, chunkZ) : null;
    }

    @Override
    public int getTopY(Heightmap.Type heightmap, int x, int z) {
        return loaded(x >> 4, z >> 4) ? world.getTopY(heightmap, x, z) : world.getBottomY();
    }

    @Override
    public List<VoxelShape> getEntityCollisions(@Nullable Entity entity, Box box) {
        return world.getEntityCollisions(entity, box);
    }

    @Override
    public int getAmbientDarkness() {
        return world.getAmbientDarkness();
    }

    /** The world's biome access reading through this view, so a biome lookup never loads a chunk either. */
    @Override
    public BiomeAccess getBiomeAccess() {
        return world.getBiomeAccess().withSource(this);
    }

    @Override
    public RegistryEntry<Biome> getGeneratorStoredBiome(int biomeX, int biomeY, int biomeZ) {
        return world.getGeneratorStoredBiome(biomeX, biomeY, biomeZ);
    }

    @Override
    public boolean isClient() {
        return false;
    }

    @Override
    @Deprecated
    public int getSeaLevel() {
        return world.getSeaLevel();
    }

    @Override
    public DimensionType getDimension() {
        return world.getDimension();
    }

    @Override
    public DynamicRegistryManager getRegistryManager() {
        return world.getRegistryManager();
    }

    @Override
    public FeatureSet getEnabledFeatures() {
        return world.getEnabledFeatures();
    }

    @Override
    public float getBrightness(Direction direction, boolean shaded) {
        return world.getBrightness(direction, shaded);
    }

    @Override
    public LightingProvider getLightingProvider() {
        return world.getLightingProvider();
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
    public int getBottomY() {
        return world.getBottomY();
    }
}
