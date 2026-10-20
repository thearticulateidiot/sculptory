package dev.sculptory.fabric.world;

import dev.sculptory.core.edit.NeighbourShapes;
import dev.sculptory.core.world.WorldReader;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.particles.ParticleOptions;
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
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunk;
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
import net.minecraft.world.ticks.LevelTickAccess;
import net.minecraft.world.ticks.ScheduledTick;

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
    private final ServerLevel world;
    private final FabricStateSpace states;
    private final View view;

    public FabricNeighbourShapes(ServerLevel world, FabricStateSpace states) {
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
            next = Block.updateFromNeighbourShapes(current, view, new BlockPos(x, y, z));
        } catch (RuntimeException | LinkageError e) {
            return state;
        }
        if (next == null || next == current) return state;
        int handle = states.handle(next);
        return handle < 0 ? state : handle;
    }

    /** Scheduled ticks asked for through the view: dropped. */
    private static final class NoTicks<T> implements LevelTickAccess<T> {
        @Override
        public boolean willTickThisTick(BlockPos pos, T type) {
            return false;
        }

        @Override
        public void schedule(ScheduledTick<T> tick) {
        }

        @Override
        public boolean hasScheduledTick(BlockPos pos, T type) {
            return false;
        }

        @Override
        public int count() {
            return 0;
        }
    }

    /**
     * The world as neighbour updates see it: blocks, fluids and block entities of loaded chunks (air elsewhere),
     * everything else read from the world; no writes, ticks, sounds, particles or events.
     */
    static final class View implements LevelAccessor {
        private final ServerLevel world;
        private final LevelTickAccess<Block> blockTicks = new NoTicks<>();
        private final LevelTickAccess<Fluid> fluidTicks = new NoTicks<>();
        /** The job's reader, whose chunk cache is renewed every slice; {@code null}: ask the chunk manager. */
        FabricWorldReader reader;

        View(ServerLevel world) {
            this.world = world;
        }

        LevelChunk chunk(int cx, int cz) {
            return reader != null ? reader.chunkOrNull(cx, cz) : world.getChunkSource().getChunkNow(cx, cz);
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            if (world.isOutsideBuildHeight(pos)) return Blocks.VOID_AIR.defaultBlockState();
            LevelChunk chunk = chunk(pos.getX() >> 4, pos.getZ() >> 4);
            return chunk == null ? Blocks.AIR.defaultBlockState() : chunk.getBlockState(pos);
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            if (world.isOutsideBuildHeight(pos)) return null;
            LevelChunk chunk = chunk(pos.getX() >> 4, pos.getZ() >> 4);
            return chunk == null ? null : chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK);
        }

        @Override
        @SuppressWarnings("unchecked") // the type's own entities are of its class
        public <T extends BlockEntity> Optional<T> getBlockEntity(BlockPos pos, BlockEntityType<T> type) {
            BlockEntity entity = getBlockEntity(pos);
            return entity != null && entity.getType() == type ? Optional.of((T) entity) : Optional.empty();
        }

        @Override
        public boolean isStateAtPosition(BlockPos pos, Predicate<BlockState> test) {
            return test.test(getBlockState(pos));
        }

        @Override
        public boolean isFluidAtPosition(BlockPos pos, Predicate<FluidState> test) {
            return test.test(getFluidState(pos));
        }

        // ---- Writes, ticks and effects: none.

        @Override
        public boolean setBlock(BlockPos pos, BlockState state, int flags, int maxUpdateDepth) {
            return false;
        }

        @Override
        public boolean removeBlock(BlockPos pos, boolean move) {
            return false;
        }

        @Override
        public boolean destroyBlock(BlockPos pos, boolean drop, Entity breakingEntity, int maxUpdateDepth) {
            return false;
        }

        @Override
        public LevelTickAccess<Block> getBlockTicks() {
            return blockTicks;
        }

        @Override
        public LevelTickAccess<Fluid> getFluidTicks() {
            return fluidTicks;
        }

        @Override
        public void playSound(Player source, BlockPos pos, SoundEvent sound, SoundSource category, float volume,
                              float pitch) {
        }

        @Override
        public void addParticle(ParticleOptions parameters, double x, double y, double z, double velocityX,
                                double velocityY, double velocityZ) {
        }

        @Override
        public void levelEvent(Player player, int eventId, BlockPos pos, int data) {
        }

        @Override
        public void gameEvent(Holder<GameEvent> event, Vec3 emitterPos, GameEvent.Context emitter) {
        }

        // ---- Reads: the world's.

        @Override
        public long nextSubTickCount() {
            return world.nextSubTickCount();
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
        public MinecraftServer getServer() {
            return world.getServer();
        }

        @Override
        public ChunkSource getChunkSource() {
            return world.getChunkSource();
        }

        @Override
        public RandomSource getRandom() {
            return world.getRandom();
        }

        @Override
        public long dayTime() {
            return world.dayTime();
        }

        @Override
        public ChunkAccess getChunk(int chunkX, int chunkZ, ChunkStatus leastStatus, boolean create) {
            // Loaded chunks only: the world's own getChunk may wait for one to load.
            return chunk(chunkX, chunkZ);
        }

        @Override
        public boolean hasChunk(int chunkX, int chunkZ) {
            return chunk(chunkX, chunkZ) != null;
        }

        @Override
        public int getHeight(Heightmap.Types heightmap, int x, int z) {
            LevelChunk chunk = chunk(x >> 4, z >> 4);
            return chunk == null ? world.getMinBuildHeight() : chunk.getHeight(heightmap, x & 15, z & 15) + 1;
        }

        @Override
        public int getSkyDarken() {
            return world.getSkyDarken();
        }

        @Override
        public BiomeManager getBiomeManager() {
            return world.getBiomeManager();
        }

        @Override
        public Holder<Biome> getUncachedNoiseBiome(int biomeX, int biomeY, int biomeZ) {
            return world.getUncachedNoiseBiome(biomeX, biomeY, biomeZ);
        }

        @Override
        public Holder<Biome> getNoiseBiome(int biomeX, int biomeY, int biomeZ) {
            return world.getNoiseBiome(biomeX, biomeY, biomeZ);
        }

        @Override
        public boolean isClientSide() {
            return false;
        }

        @Override
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
        public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
            return world.getBlockTint(pos, colorResolver);
        }

        @Override
        public WorldBorder getWorldBorder() {
            return world.getWorldBorder();
        }

        @Override
        public BlockGetter getChunkForCollisions(int chunkX, int chunkZ) {
            return chunk(chunkX, chunkZ);
        }

        @Override
        public List<VoxelShape> getEntityCollisions(Entity entity, AABB box) {
            return world.getEntityCollisions(entity, box);
        }

        @Override
        public List<Entity> getEntities(Entity except, AABB box, Predicate<? super Entity> predicate) {
            return world.getEntities(except, box, predicate);
        }

        @Override
        public <T extends Entity> List<T> getEntities(EntityTypeTest<Entity, T> filter, AABB box,
                                                            Predicate<? super T> predicate) {
            return world.getEntities(filter, box, predicate);
        }

        @Override
        public List<? extends Player> players() {
            return world.players();
        }

        @Override
        public BlockPos getHeightmapPos(Heightmap.Types heightmap, BlockPos pos) {
            return new BlockPos(pos.getX(), getHeight(heightmap, pos.getX(), pos.getZ()), pos.getZ());
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
}
