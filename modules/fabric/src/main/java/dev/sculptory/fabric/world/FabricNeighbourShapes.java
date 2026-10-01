package dev.sculptory.fabric.world;

import dev.sculptory.core.edit.NeighbourShapes;
import dev.sculptory.core.world.WorldReader;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.resource.featuretoggle.FeatureSet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.Heightmap;
import net.minecraft.world.LocalDifficulty;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.WorldProperties;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.ColorResolver;
import net.minecraft.world.biome.source.BiomeAccess;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkManager;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.light.LightingProvider;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.event.GameEvent;
import net.minecraft.world.tick.OrderedTick;
import net.minecraft.world.tick.QueryableTickScheduler;

/**
 * Update blocks on Fabric: a cell's state after vanilla's
 * post-processing, {@code Block.postProcessState}, which asks {@code getStateForNeighborUpdate} once for each of the six
 * directions (what world generation does to structure blocks). The neighbours are read from the live world through a
 * view ({@link View}) that never loads a chunk (a cell beside an unloaded chunk keeps its state), writes nothing,
 * discards every tick the blocks schedule (a falling block or an observer never fires) and makes no sound, particle or
 * game event. A block that throws keeps its state. The caller keeps a result only when the block is the same
 * ({@code UpdateProgram}). Server thread only.
 */
public final class FabricNeighbourShapes implements NeighbourShapes {
    private final ServerWorld world;
    private final FabricStateSpace states;
    private final View view;

    public FabricNeighbourShapes(ServerWorld world, FabricStateSpace states) {
        this.world = Objects.requireNonNull(world);
        this.states = Objects.requireNonNull(states);
        this.view = new View(world);
    }

    @Override
    public int reshape(int x, int y, int z, int state, WorldReader reader) {
        // The job's reader knows which chunks are loaded this tick (it is invalidated every slice).
        view.reader = reader instanceof FabricWorldReader fabric ? fabric : null;
        int cx = x >> 4, cz = z >> 4, lx = x & 15, lz = z & 15;
        if (view.chunk(cx, cz) == null) return state;
        // A neighbour in an unloaded chunk is unknown: the cell keeps its shape.
        if ((lx == 0 && view.chunk(cx - 1, cz) == null) || (lx == 15 && view.chunk(cx + 1, cz) == null)
                || (lz == 0 && view.chunk(cx, cz - 1) == null) || (lz == 15 && view.chunk(cx, cz + 1) == null)) {
            return state;
        }
        BlockState current = states.state(state);
        BlockState next;
        try {
            next = Block.postProcessState(current, view, new BlockPos(x, y, z));
        } catch (RuntimeException | LinkageError e) {
            return state;
        }
        if (next == null || next == current) return state;
        int handle = states.handle(next);
        return handle < 0 ? state : handle;
    }

    /** Scheduled ticks asked for through the view: dropped. */
    private static final class NoTicks<T> implements QueryableTickScheduler<T> {
        @Override
        public boolean isTicking(BlockPos pos, T type) {
            return false;
        }

        @Override
        public void scheduleTick(OrderedTick<T> tick) {
        }

        @Override
        public boolean isQueued(BlockPos pos, T type) {
            return false;
        }

        @Override
        public int getTickCount() {
            return 0;
        }
    }

    /**
     * The world as neighbour updates see it: blocks, fluids and block entities of loaded chunks (air elsewhere),
     * everything else read from the world; no writes, ticks, sounds, particles or events.
     */
    static final class View implements WorldAccess {
        private final ServerWorld world;
        private final QueryableTickScheduler<Block> blockTicks = new NoTicks<>();
        private final QueryableTickScheduler<Fluid> fluidTicks = new NoTicks<>();
        /** The job's reader, whose chunk cache is renewed every slice; {@code null}: ask the chunk manager. */
        FabricWorldReader reader;

        View(ServerWorld world) {
            this.world = world;
        }

        WorldChunk chunk(int cx, int cz) {
            return reader != null ? reader.chunkOrNull(cx, cz) : world.getChunkManager().getWorldChunk(cx, cz);
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            if (world.isOutOfHeightLimit(pos)) return Blocks.VOID_AIR.getDefaultState();
            WorldChunk chunk = chunk(pos.getX() >> 4, pos.getZ() >> 4);
            return chunk == null ? Blocks.AIR.getDefaultState() : chunk.getBlockState(pos);
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            if (world.isOutOfHeightLimit(pos)) return null;
            WorldChunk chunk = chunk(pos.getX() >> 4, pos.getZ() >> 4);
            return chunk == null ? null : chunk.getBlockEntity(pos, WorldChunk.CreationType.CHECK);
        }

        @Override
        @SuppressWarnings("unchecked") // the type's own entities are of its class
        public <T extends BlockEntity> Optional<T> getBlockEntity(BlockPos pos, BlockEntityType<T> type) {
            BlockEntity entity = getBlockEntity(pos);
            return entity != null && entity.getType() == type ? Optional.of((T) entity) : Optional.empty();
        }

        @Override
        public boolean testBlockState(BlockPos pos, Predicate<BlockState> test) {
            return test.test(getBlockState(pos));
        }

        @Override
        public boolean testFluidState(BlockPos pos, Predicate<FluidState> test) {
            return test.test(getFluidState(pos));
        }

        // ---- Writes, ticks and effects: none.

        @Override
        public boolean setBlockState(BlockPos pos, BlockState state, int flags, int maxUpdateDepth) {
            return false;
        }

        @Override
        public boolean removeBlock(BlockPos pos, boolean move) {
            return false;
        }

        @Override
        public boolean breakBlock(BlockPos pos, boolean drop, Entity breakingEntity, int maxUpdateDepth) {
            return false;
        }

        @Override
        public QueryableTickScheduler<Block> getBlockTickScheduler() {
            return blockTicks;
        }

        @Override
        public QueryableTickScheduler<Fluid> getFluidTickScheduler() {
            return fluidTicks;
        }

        @Override
        public void playSound(PlayerEntity source, BlockPos pos, SoundEvent sound, SoundCategory category, float volume,
                              float pitch) {
        }

        @Override
        public void addParticle(ParticleEffect parameters, double x, double y, double z, double velocityX,
                                double velocityY, double velocityZ) {
        }

        @Override
        public void syncWorldEvent(PlayerEntity player, int eventId, BlockPos pos, int data) {
        }

        @Override
        public void emitGameEvent(RegistryEntry<GameEvent> event, Vec3d emitterPos, GameEvent.Emitter emitter) {
        }

        // ---- Reads: the world's.

        @Override
        public long getTickOrder() {
            return world.getTickOrder();
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
        public MinecraftServer getServer() {
            return world.getServer();
        }

        @Override
        public ChunkManager getChunkManager() {
            return world.getChunkManager();
        }

        @Override
        public Random getRandom() {
            return world.getRandom();
        }

        @Override
        public long getLunarTime() {
            return world.getLunarTime();
        }

        @Override
        public Chunk getChunk(int chunkX, int chunkZ, ChunkStatus leastStatus, boolean create) {
            // Loaded chunks only: the world's own getChunk may wait for one to load.
            return chunk(chunkX, chunkZ);
        }

        @Override
        public boolean isChunkLoaded(int chunkX, int chunkZ) {
            return chunk(chunkX, chunkZ) != null;
        }

        @Override
        public int getTopY(Heightmap.Type heightmap, int x, int z) {
            WorldChunk chunk = chunk(x >> 4, z >> 4);
            return chunk == null ? world.getBottomY() : chunk.sampleHeightmap(heightmap, x & 15, z & 15) + 1;
        }

        @Override
        public int getAmbientDarkness() {
            return world.getAmbientDarkness();
        }

        @Override
        public BiomeAccess getBiomeAccess() {
            return world.getBiomeAccess();
        }

        @Override
        public RegistryEntry<Biome> getGeneratorStoredBiome(int biomeX, int biomeY, int biomeZ) {
            return world.getGeneratorStoredBiome(biomeX, biomeY, biomeZ);
        }

        @Override
        public RegistryEntry<Biome> getBiomeForNoiseGen(int biomeX, int biomeY, int biomeZ) {
            return world.getBiomeForNoiseGen(biomeX, biomeY, biomeZ);
        }

        @Override
        public boolean isClient() {
            return false;
        }

        @Override
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
        public int getColor(BlockPos pos, ColorResolver colorResolver) {
            return world.getColor(pos, colorResolver);
        }

        @Override
        public WorldBorder getWorldBorder() {
            return world.getWorldBorder();
        }

        @Override
        public BlockView getChunkAsView(int chunkX, int chunkZ) {
            return chunk(chunkX, chunkZ);
        }

        @Override
        public List<VoxelShape> getEntityCollisions(Entity entity, Box box) {
            return world.getEntityCollisions(entity, box);
        }

        @Override
        public List<Entity> getOtherEntities(Entity except, Box box, Predicate<? super Entity> predicate) {
            return world.getOtherEntities(except, box, predicate);
        }

        @Override
        public <T extends Entity> List<T> getEntitiesByType(TypeFilter<Entity, T> filter, Box box,
                                                            Predicate<? super T> predicate) {
            return world.getEntitiesByType(filter, box, predicate);
        }

        @Override
        public List<? extends PlayerEntity> getPlayers() {
            return world.getPlayers();
        }

        @Override
        public BlockPos getTopPosition(Heightmap.Type heightmap, BlockPos pos) {
            return new BlockPos(pos.getX(), getTopY(heightmap, pos.getX(), pos.getZ()), pos.getZ());
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
}
