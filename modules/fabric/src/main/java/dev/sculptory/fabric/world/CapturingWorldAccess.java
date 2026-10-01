package dev.sculptory.fabric.world;

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
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.resource.featuretoggle.FeatureSet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.state.property.Properties;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.Heightmap;
import net.minecraft.world.LocalDifficulty;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.WorldProperties;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.world.biome.source.BiomeAccess;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkManager;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraft.world.chunk.light.LightingProvider;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.event.GameEvent;
import net.minecraft.world.tick.EmptyTickSchedulers;
import net.minecraft.world.tick.QueryableTickScheduler;
import net.minecraft.world.tick.TickPriority;
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
 * above, entities (none are spawned or seen), sounds, particles and game events. {@link #toServerWorld} is the one
 * way through to the live world; no feature of {@code FeatureCatalog} calls it (they were checked against the
 * 1.21.1 classes).
 */
public final class CapturingWorldAccess implements StructureWorldAccess {
    private final ServerWorld world;
    private final FabricStateSpace states;
    private final ScatterPlanner.GrownView grown;
    private final Random random;
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
    public CapturingWorldAccess(ServerWorld world, FabricStateSpace states, ScatterPlanner.GrownView grown, BlockPos origin,
                                int radius, int down, int up, long seed) {
        this.world = Objects.requireNonNull(world);
        this.states = Objects.requireNonNull(states);
        this.grown = Objects.requireNonNull(grown);
        if (radius < 0 || down < 0 || up < 0) throw new IllegalArgumentException("Negative span");
        this.minX = origin.getX() - radius;
        this.maxX = origin.getX() + radius;
        this.minZ = origin.getZ() - radius;
        this.maxZ = origin.getZ() + radius;
        this.minY = Math.max(world.getBottomY(), origin.getY() - down);
        this.maxY = Math.min(world.getTopY() - 1, origin.getY() + up);
        this.random = Random.create(seed);
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
            BlockPos pos = BlockPos.fromLong(key);
            BlockState state = entry.getValue();
            BlockEntity entity = entities.get(key);
            FabricTile tile = entity == null ? null : FabricTile.capture(entity, world.getRegistryManager());
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
        if (world.isOutOfHeightLimit(pos)) return Blocks.VOID_AIR.getDefaultState();
        int earlier = grown.get(pos.getX(), pos.getY(), pos.getZ());
        if (earlier >= 0) return states.state(earlier);
        if (!loaded(pos.getX() >> 4, pos.getZ() >> 4)) {
            unloaded = true;
            return Blocks.VOID_AIR.getDefaultState();
        }
        return world.getBlockState(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public boolean testBlockState(BlockPos pos, Predicate<BlockState> test) {
        return test.test(getBlockState(pos));
    }

    @Override
    public boolean testFluidState(BlockPos pos, Predicate<FluidState> test) {
        return test.test(getFluidState(pos));
    }

    @Override
    public boolean setBlockState(BlockPos pos, BlockState state, int flags, int maxUpdateDepth) {
        if (world.isOutOfHeightLimit(pos)) return false;
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
        if (state.hasBlockEntity() && state.getBlock() instanceof BlockEntityProvider provider) {
            if (entity != null && entity.getType().supports(state)) {
                entity.setCachedState(state);
            } else {
                BlockEntity created = provider.createBlockEntity(pos.toImmutable(), state);
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
        if (!old.contains(Properties.DOUBLE_BLOCK_HALF) || now.isOf(old.getBlock())) return;
        BlockPos other = old.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER ? pos.up() : pos.down();
        BlockState half = getBlockState(other);
        if (half.isOf(old.getBlock()) && half.contains(Properties.DOUBLE_BLOCK_HALF)
                && half.get(Properties.DOUBLE_BLOCK_HALF) != old.get(Properties.DOUBLE_BLOCK_HALF)) {
            setBlockState(other, half.getFluidState().getBlockState(), Block.NOTIFY_ALL, 512);
        }
    }

    @Override
    public boolean removeBlock(BlockPos pos, boolean move) {
        return setBlockState(pos, getFluidState(pos).getBlockState(), Block.NOTIFY_ALL, 512);
    }

    @Override
    public boolean breakBlock(BlockPos pos, boolean drop, @Nullable Entity breakingEntity, int maxUpdateDepth) {
        BlockState state = getBlockState(pos);
        if (state.isAir()) return false;
        // No drops, no break effects: the cell just takes what its fluid leaves.
        return setBlockState(pos, state.getFluidState().getBlockState(), Block.NOTIFY_ALL, maxUpdateDepth);
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
    public int getTopY(Heightmap.Type heightmap, int x, int z) {
        if (!loaded(x >> 4, z >> 4)) {
            unloaded = true;
            return world.getBottomY();
        }
        return world.getTopY(heightmap, x, z);
    }

    @Override
    public BlockPos getTopPosition(Heightmap.Type heightmap, BlockPos pos) {
        return new BlockPos(pos.getX(), getTopY(heightmap, pos.getX(), pos.getZ()), pos.getZ());
    }

    // ================================================================== chunks, never live ones

    @Override
    @Nullable
    public Chunk getChunk(int chunkX, int chunkZ, ChunkStatus leastStatus, boolean create) {
        if (!loaded(chunkX, chunkZ)) unloaded = true;
        RegistryEntry<Biome> plains = world.getRegistryManager().get(RegistryKeys.BIOME).entryOf(BiomeKeys.PLAINS);
        return new EmptyChunk(world, new ChunkPos(chunkX, chunkZ), plains);
    }

    @Override
    public boolean isChunkLoaded(int chunkX, int chunkZ) {
        return loaded(chunkX, chunkZ);
    }

    @Override
    @Nullable
    public BlockView getChunkAsView(int chunkX, int chunkZ) {
        return null;
    }

    @Override
    public ChunkManager getChunkManager() {
        return world.getChunkManager();
    }

    // ================================================================== dropped: ticks, entities, effects

    @Override
    public QueryableTickScheduler<Block> getBlockTickScheduler() {
        return EmptyTickSchedulers.getClientTickScheduler();
    }

    @Override
    public QueryableTickScheduler<Fluid> getFluidTickScheduler() {
        return EmptyTickSchedulers.getClientTickScheduler();
    }

    @Override
    public void scheduleBlockTick(BlockPos pos, Block block, int delay, TickPriority priority) {}

    @Override
    public void scheduleBlockTick(BlockPos pos, Block block, int delay) {}

    @Override
    public void scheduleFluidTick(BlockPos pos, Fluid fluid, int delay, TickPriority priority) {}

    @Override
    public void scheduleFluidTick(BlockPos pos, Fluid fluid, int delay) {}

    @Override
    public void updateNeighbors(BlockPos pos, Block block) {}

    @Override
    public boolean spawnEntity(Entity entity) {
        return false;
    }

    @Override
    public void spawnEntityAndPassengers(Entity entity) {}

    @Override
    public List<Entity> getOtherEntities(@Nullable Entity except, Box box, Predicate<? super Entity> predicate) {
        return List.of();
    }

    @Override
    public <T extends Entity> List<T> getEntitiesByType(TypeFilter<Entity, T> filter, Box box,
                                                        Predicate<? super T> predicate) {
        return List.of();
    }

    @Override
    public List<? extends PlayerEntity> getPlayers() {
        return List.of();
    }

    @Override
    public List<VoxelShape> getEntityCollisions(@Nullable Entity entity, Box box) {
        return List.of();
    }

    @Override
    public void playSound(@Nullable PlayerEntity source, BlockPos pos, SoundEvent sound, SoundCategory category,
                          float volume, float pitch) {}

    @Override
    public void addParticle(ParticleEffect parameters, double x, double y, double z, double velocityX,
                            double velocityY, double velocityZ) {}

    @Override
    public void syncWorldEvent(@Nullable PlayerEntity player, int eventId, BlockPos pos, int data) {}

    @Override
    public void emitGameEvent(RegistryEntry<GameEvent> event, Vec3d emitterPos, GameEvent.Emitter emitter) {}

    // ================================================================== the world's, read only

    @Override
    public ServerWorld toServerWorld() {
        return world;
    }

    @Override
    public long getSeed() {
        return world.getSeed();
    }

    @Override
    public Random getRandom() {
        return random;
    }

    @Override
    public long getTickOrder() {
        return 0;
    }

    @Override
    public WorldProperties getLevelProperties() {
        return world.getLevelProperties();
    }

    @Override
    public LocalDifficulty getLocalDifficulty(BlockPos pos) {
        return world.getLocalDifficulty(pos);
    }

    @Override
    @Nullable
    public MinecraftServer getServer() {
        return world.getServer();
    }

    @Override
    public int getAmbientDarkness() {
        return world.getAmbientDarkness();
    }

    @Override
    public BiomeAccess getBiomeAccess() {
        return world.getBiomeAccess().withSource(this);
    }

    /** The loaded chunk's biome, else the generator's (no chunk is loaded either way). */
    @Override
    public RegistryEntry<Biome> getBiomeForNoiseGen(int biomeX, int biomeY, int biomeZ) {
        if (loaded(biomeX >> 2, biomeZ >> 2)) return world.getBiomeForNoiseGen(biomeX, biomeY, biomeZ);
        return world.getGeneratorStoredBiome(biomeX, biomeY, biomeZ);
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
