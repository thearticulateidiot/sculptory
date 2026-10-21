package dev.sculptory.mc.world;

import dev.sculptory.core.scatter.GrownFeature;
import dev.sculptory.core.scatter.Outcome;
import dev.sculptory.core.scatter.ScatterPlanner;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.ticks.BlackholeTickAccess;
import net.minecraft.world.ticks.LevelTickAccess;
import net.minecraft.world.ticks.TickPriority;
import org.jetbrains.annotations.Nullable;

/**
 * A server world as vanilla feature code sees it while a scatter preview grows one tree or feature, which captures every change instead of making it. Server thread only;
 * one growth per instance.
 *
 * <p><b>Writes</b> ({@code setBlockState}, {@code removeBlock}, {@code breakBlock}) go into a buffer, never the world:
 * the first write of a cell remembers what it held. A block with a block entity gets its own (not in the world), which
 * the feature may fill ({@code getBlockEntity}: a bee nest's bees); it is captured with the cell. Replacing one half of
 * a two-block plant clears its other half too (the neighbour update vanilla would have made). A write outside the
 * growth's span (a box around its spot) or the build height is dropped and answered {@code false} (the span clips it;
 * vanilla leaves decaying later is a known limit), and one into a chunk that is not loaded marks the growth
 * {@link Outcome#UNLOADED}.
 *
 * <p><b>Reads</b> see the buffer, then the cells earlier accepted growths of the same plan write
 * ({@link ScatterPlanner.GrownView}), then the world, like {@code LoadedOnlyView}: nothing is ever loaded. A chunk that
 * is not loaded reads as void air and also marks the growth {@link Outcome#UNLOADED}. Block entities are only the
 * buffer's (the world's are never handed out, so nothing can change them); heightmaps are the world's (they do not
 * see the buffer), and so are light, biomes (the generator's where the chunk is not loaded), the border and the
 * registries. {@link #getChunk} gives an empty chunk, never a live one.
 *
 * <p><b>Everything else</b> is dropped: scheduled block and fluid ticks, neighbour updates beyond the two-block rule
 * above, entities (none are spawned or seen), sounds, particles and game events. {@link #getLevel} is the one
 * way through to the live world; no feature of {@code FeatureCatalog} calls it (they were checked against the
 * 1.21.1 classes).
 */
public final class CapturingWorldAccess implements WorldGenLevel {
    private final ServerLevel world;
    private final McStateSpace states;
    private final ScatterPlanner.GrownView grown;
    private final RandomSource random;
    private final int minX, maxX, minY, maxY, minZ, maxZ;
    /** Buffered states by {@link BlockPos#asLong}, in first-write order. */
    private final Long2ObjectLinkedOpenHashMap<BlockState> written = new Long2ObjectLinkedOpenHashMap<>();
    /** What each buffered cell held before its first write (a state handle). */
    private final Long2IntOpenHashMap before = new Long2IntOpenHashMap();
    private final Long2ObjectOpenHashMap<BlockEntity> entities = new Long2ObjectOpenHashMap<>();
    private boolean unloaded;
    private long reads;
    private int clipped;
    private int lastChunkX, lastChunkZ;
    private boolean lastLoaded, lastKnown;

    /**
     * @param origin the growth's spot (the cell above the surface)
     * @param radius how far from the origin, horizontally, it may write
     * @param down how far below the origin it may write
     * @param up how far above the origin it may write
     * @param seed the growth's seed (its {@link #getRandom})
     */
    public CapturingWorldAccess(ServerLevel world, McStateSpace states, ScatterPlanner.GrownView grown, BlockPos origin,
                                int radius, int down, int up, long seed) {
        this.world = Objects.requireNonNull(world);
        this.states = Objects.requireNonNull(states);
        this.grown = Objects.requireNonNull(grown);
        if (radius < 0 || down < 0 || up < 0) throw new IllegalArgumentException("Negative span");
        this.minX = origin.getX() - radius;
        this.maxX = origin.getX() + radius;
        this.minZ = origin.getZ() - radius;
        this.maxZ = origin.getZ() + radius;
        this.minY = Math.max(world.getMinBuildHeight(), origin.getY() - down);
        this.maxY = Math.min(world.getMaxBuildHeight() - 1, origin.getY() + up);
        this.random = RandomSource.create(seed);
        before.defaultReturnValue(-1);
    }

    // ================================================================== the result

    /** Cells read so far (the planner counts them as work). */
    public long reads() {
        return reads;
    }

    /** Writes dropped because they fell outside the span. */
    public int clipped() {
        return clipped;
    }

    /** Whether the growth reached a chunk that is not loaded. */
    public boolean reachedUnloaded() {
        return unloaded;
    }

    /** Cells written so far. */
    public int writes() {
        return written.size();
    }

    /**
     * The growth: {@link Outcome#UNLOADED} if it reached a chunk that is not loaded, {@link Outcome#FEATURE_FAILED} if
     * the feature said it did not grow ({@code generated} false) or changed nothing, else its cells (each with the
     * block entity the feature left there, captured).
     */
    public ScatterPlanner.Growth finish(boolean generated) {
        long work = reads + written.size();
        if (unloaded) return ScatterPlanner.Growth.failed(Outcome.UNLOADED, work);
        if (!generated) return ScatterPlanner.Growth.failed(Outcome.FEATURE_FAILED, work);
        GrownFeature.Builder builder = GrownFeature.builder();
        for (Long2ObjectLinkedOpenHashMap.Entry<BlockState> entry : written.long2ObjectEntrySet()) {
            long key = entry.getLongKey();
            BlockPos pos = BlockPos.of(key);
            BlockState state = entry.getValue();
            BlockEntity entity = entities.get(key);
            McTile tile = entity == null ? null : McTile.capture(entity, world.registryAccess());
            builder.set(pos.getX(), pos.getY(), pos.getZ(), states.handle(state), tile, before.get(key));
        }
        GrownFeature cells = builder.build();
        return cells == null ? ScatterPlanner.Growth.failed(Outcome.FEATURE_FAILED, work)
                : ScatterPlanner.Growth.grown(cells, work);
    }

    // ================================================================== loading

    private boolean loaded(int cx, int cz) {
        if (!lastKnown || cx != lastChunkX || cz != lastChunkZ) {
            lastChunkX = cx;
            lastChunkZ = cz;
            lastLoaded = WorldChecks.isChunkLoaded(world, cx, cz);
            lastKnown = true;
        }
        return lastLoaded;
    }

    private boolean inSpan(BlockPos pos) {
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    // ================================================================== blocks

    @Override
    public BlockState getBlockState(BlockPos pos) {
        reads++;
        BlockState mine = written.get(pos.asLong());
        if (mine != null) return mine;
        return underlying(pos);
    }

    /** What the cell holds without this growth's writes: an earlier growth's state, else the world's. */
    private BlockState underlying(BlockPos pos) {
        if (world.isOutsideBuildHeight(pos)) return Blocks.VOID_AIR.defaultBlockState();
        int earlier = grown.get(pos.getX(), pos.getY(), pos.getZ());
        if (earlier >= 0) return states.state(earlier);
        if (!loaded(pos.getX() >> 4, pos.getZ() >> 4)) {
            unloaded = true;
            return Blocks.VOID_AIR.defaultBlockState();
        }
        return world.getBlockState(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public boolean isStateAtPosition(BlockPos pos, Predicate<BlockState> test) {
        return test.test(getBlockState(pos));
    }

    @Override
    public boolean isFluidAtPosition(BlockPos pos, Predicate<FluidState> test) {
        return test.test(getFluidState(pos));
    }

    @Override
    public boolean setBlock(BlockPos pos, BlockState state, int flags, int maxUpdateDepth) {
        if (world.isOutsideBuildHeight(pos)) return false;
        if (!inSpan(pos)) {
            clipped++;
            return false;
        }
        if (!loaded(pos.getX() >> 4, pos.getZ() >> 4)) {
            unloaded = true;
            return false;
        }
        long key = pos.asLong();
        BlockState old = written.get(key);
        if (old == null) {
            old = underlying(pos);
            before.put(key, states.handle(old));
        }
        written.put(key, state);
        BlockEntity entity = entities.get(key);
        if (state.hasBlockEntity() && state.getBlock() instanceof EntityBlock provider) {
            if (entity != null && entity.getType().isValid(state)) {
                entity.setBlockState(state);
            } else {
                BlockEntity created = provider.newBlockEntity(pos.immutable(), state);
                if (created != null) {
                    entities.put(key, created);
                } else {
                    entities.remove(key);
                }
            }
        } else if (entity != null) {
            entities.remove(key);
        }
        clearOtherHalf(pos, old, state);
        return true;
    }

    /**
     * A two-block plant (or door) whose half at {@code pos} was replaced by another block loses its other half, as
     * vanilla's neighbour update would take it: that cell becomes what its fluid leaves (water or air).
     */
    private void clearOtherHalf(BlockPos pos, BlockState old, BlockState now) {
        if (!old.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) || now.is(old.getBlock())) return;
        BlockPos other = old.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
        BlockState half = getBlockState(other);
        if (half.is(old.getBlock()) && half.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && half.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) != old.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            setBlock(other, half.getFluidState().createLegacyBlock(), Block.UPDATE_ALL, 512);
        }
    }

    @Override
    public boolean removeBlock(BlockPos pos, boolean move) {
        return setBlock(pos, getFluidState(pos).createLegacyBlock(), Block.UPDATE_ALL, 512);
    }

    @Override
    public boolean destroyBlock(BlockPos pos, boolean drop, @Nullable Entity breakingEntity, int maxUpdateDepth) {
        BlockState state = getBlockState(pos);
        if (state.isAir()) return false;
        // No drops, no break effects: the cell just takes what its fluid leaves.
        return setBlock(pos, state.getFluidState().createLegacyBlock(), Block.UPDATE_ALL, maxUpdateDepth);
    }

    /** Only block entities this growth made; the world's are never handed out. */
    @Override
    @Nullable
    public BlockEntity getBlockEntity(BlockPos pos) {
        return entities.get(pos.asLong());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> Optional<T> getBlockEntity(BlockPos pos, BlockEntityType<T> type) {
        BlockEntity entity = getBlockEntity(pos);
        return entity != null && entity.getType() == type ? Optional.of((T) entity) : Optional.empty();
    }

    @Override
    public int getHeight(Heightmap.Types heightmap, int x, int z) {
        if (!loaded(x >> 4, z >> 4)) {
            unloaded = true;
            return world.getMinBuildHeight();
        }
        return world.getHeight(heightmap, x, z);
    }

    @Override
    public BlockPos getHeightmapPos(Heightmap.Types heightmap, BlockPos pos) {
        return new BlockPos(pos.getX(), getHeight(heightmap, pos.getX(), pos.getZ()), pos.getZ());
    }

    // ================================================================== chunks, never live ones

    @Override
    @Nullable
    public ChunkAccess getChunk(int chunkX, int chunkZ, ChunkStatus leastStatus, boolean create) {
        if (!loaded(chunkX, chunkZ)) unloaded = true;
        Holder<Biome> plains = world.registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS);
        return new EmptyLevelChunk(world, new ChunkPos(chunkX, chunkZ), plains);
    }

    @Override
    public boolean hasChunk(int chunkX, int chunkZ) {
        return loaded(chunkX, chunkZ);
    }

    @Override
    @Nullable
    public BlockGetter getChunkForCollisions(int chunkX, int chunkZ) {
        return null;
    }

    @Override
    public ChunkSource getChunkSource() {
        return world.getChunkSource();
    }

    // ================================================================== dropped: ticks, entities, effects

    @Override
    public LevelTickAccess<Block> getBlockTicks() {
        return BlackholeTickAccess.emptyLevelList();
    }

    @Override
    public LevelTickAccess<Fluid> getFluidTicks() {
        return BlackholeTickAccess.emptyLevelList();
    }

    @Override
    public void scheduleTick(BlockPos pos, Block block, int delay, TickPriority priority) {}

    @Override
    public void scheduleTick(BlockPos pos, Block block, int delay) {}

    @Override
    public void scheduleTick(BlockPos pos, Fluid fluid, int delay, TickPriority priority) {}

    @Override
    public void scheduleTick(BlockPos pos, Fluid fluid, int delay) {}

    @Override
    public void blockUpdated(BlockPos pos, Block block) {}

    @Override
    public boolean addFreshEntity(Entity entity) {
        return false;
    }

    @Override
    public void addFreshEntityWithPassengers(Entity entity) {}

    @Override
    public List<Entity> getEntities(@Nullable Entity except, AABB box, Predicate<? super Entity> predicate) {
        return List.of();
    }

    @Override
    public <T extends Entity> List<T> getEntities(EntityTypeTest<Entity, T> filter, AABB box,
                                                        Predicate<? super T> predicate) {
        return List.of();
    }

    @Override
    public List<? extends Player> players() {
        return List.of();
    }

    @Override
    public List<VoxelShape> getEntityCollisions(@Nullable Entity entity, AABB box) {
        return List.of();
    }

    @Override
    public void playSound(@Nullable Player source, BlockPos pos, SoundEvent sound, SoundSource category,
                          float volume, float pitch) {}

    @Override
    public void addParticle(ParticleOptions parameters, double x, double y, double z, double velocityX,
                            double velocityY, double velocityZ) {}

    @Override
    public void levelEvent(@Nullable Player player, int eventId, BlockPos pos, int data) {}

    @Override
    public void gameEvent(Holder<GameEvent> event, Vec3 emitterPos, GameEvent.Context emitter) {}

    // ================================================================== the world's, read only

    @Override
    public ServerLevel getLevel() {
        return world;
    }

    @Override
    public long getSeed() {
        return world.getSeed();
    }

    @Override
    public RandomSource getRandom() {
        return random;
    }

    @Override
    public long nextSubTickCount() {
        return 0;
    }

    @Override
    public LevelData getLevelData() {
        return world.getLevelData();
    }

    @Override
    public DifficultyInstance getCurrentDifficultyAt(BlockPos pos) {
        return world.getCurrentDifficultyAt(pos);
    }

    @Override
    @Nullable
    public MinecraftServer getServer() {
        return world.getServer();
    }

    @Override
    public int getSkyDarken() {
        return world.getSkyDarken();
    }

    @Override
    public BiomeManager getBiomeManager() {
        return world.getBiomeManager().withDifferentSource(this);
    }

    /** The loaded chunk's biome, else the generator's (no chunk is loaded either way). */
    @Override
    public Holder<Biome> getNoiseBiome(int biomeX, int biomeY, int biomeZ) {
        if (loaded(biomeX >> 2, biomeZ >> 2)) return world.getNoiseBiome(biomeX, biomeY, biomeZ);
        return world.getUncachedNoiseBiome(biomeX, biomeY, biomeZ);
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
