package dev.sculptory.mc.world;

import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.server.engine.impl.RecordSink;
import dev.sculptory.server.platform.ClientUpdates;
import dev.sculptory.server.platform.WorldWriter;
import dev.sculptory.server.platform.WriteOptions;
import dev.sculptory.server.schem.SanitizedTile;
import dev.sculptory.server.schem.TileSanitizer;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Clearable;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickContainerAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The single world write path, server thread only. Per cell:
 * <ol>
 *   <li>Physics off: inside {@link EditScope#suppressPhysics()}, remove the old block entity and
 *       {@code setBlockState(pos, state, FORCE_STATE, 0)} ({@code onBlockAdded}/{@code onStateReplaced} cancelled).
 *       The old block entity is <em>not</em> {@link Clearable#clearContent cleared}: nothing can drop with
 *       {@code onStateReplaced} cancelled, and clearing has side effects the scope cannot suppress (lectern and
 *       jukebox clearing update comparators and neighbours). The light checks the cell asks are collected
 *       ({@link LightBatch}) and go to the light engine about a thousand at a time, not as a task per cell.</li>
 *   <li>Physics on: {@link Clearable#clearContent} the old block entity inside an {@link EditScope} (so nothing drops),
 *       remove it, then {@code NOTIFY_ALL} with update depth 512.</li>
 *   <li>Load the tile with {@code BlockEntity.createFromNbt}, replacing the default block entity through
 *       {@code removeBlockEntity} so its game-event listener is unregistered. A failing load is counted and
 *       logged; the block keeps its default block entity.</li>
 *   <li>Operator-only NBT is stripped (and counted) unless the options allow it or the tile is
 *       {@link McTile#serverCaptured() server-captured} and captured tiles are trusted.</li>
 *   <li>{@code markForUpdate(pos)} so the change reaches clients, or, for physics-off writes of a writer
 *       {@link #syncThrough synced through} a {@link ClientSync} (bulk jobs), the cell noted there.</li>
 * </ol>
 * Physics-off writes remember the cells they wrote; {@link #clearTicksAtWrittenCells()} then hands their light
 * checks to the light engine and removes scheduled block and fluid ticks at exactly those cells (the executor calls
 * it after each section; brush code after each part of a dab). Checks not handed on by then go before the next world
 * tick.
 *
 * <p>The recorded {@link #write(int, int, int, int, BlockEntityData, RecordSink)} reads the live cell right before
 * mutating it, skips no-ops, and records every cell it touches, even when the write throws. Its {@link Guard} form
 * lets the caller refuse the write from that live state and block entity (a scatter commit never overwrites a cell
 * built on since; undo keeps a chest filled since).
 *
 * <p>The target chunk must be loaded; {@code World.setBlockState} would otherwise load it synchronously.
 * Instances keep per-job counters and are not thread-safe.
 */
public final class BlockWriter implements WorldWriter {
    private static final Logger LOG = LoggerFactory.getLogger("sculptory");
    private static final int MAX_LOGGED_FAILURES = 5;

    private final ServerLevel world;
    private final McStateSpace states;
    private final WriteOptions options;
    private final HolderLookup.Provider registries;
    /** The light checks of physics-off writes, handed to the light engine per chunk column ({@code null}: per cell). */
    private final LightBatch light;
    /** Physics-off writes not yet passed to {@link #clearTicksAtWrittenCells()}: section key → 4096-bit set. */
    private final Long2ObjectOpenHashMap<long[]> writtenCells = new Long2ObjectOpenHashMap<>();
    /** Where physics-off writes are sent to clients from; {@code null}: vanilla block updates per cell. */
    private ClientSync clientSync;
    /** Told about cells written through vanilla block updates, for its light follow-ups; {@code null}: nobody. */
    private ClientSync vanillaWatch;

    private long written;
    private long changed;
    private long refused;
    private long strippedNbt;
    private long tileFailures;
    private String firstTileFailure;

    public BlockWriter(ServerLevel world, McStateSpace states, WriteOptions options) {
        this.world = Objects.requireNonNull(world);
        this.states = Objects.requireNonNull(states);
        this.options = Objects.requireNonNull(options);
        this.registries = world.registryAccess();
        this.light = LightBatch.of(world);
    }

    public ServerLevel world() {
        return world;
    }

    public WriteOptions options() {
        return options;
    }

    /**
     * Sends physics-off writes to clients through {@code sync} (bulk jobs: whole columns when heavily changed) instead of
     * marking each cell for vanilla's block updates at once. The caller must {@link ClientSync#flush flush} it. Physics-on
     * writes always use vanilla's updates. Returns this writer.
     */
    @Override
    public BlockWriter syncThrough(ClientUpdates sync) {
        ClientSync own = ownSync(sync);
        this.clientSync = own;
        this.vanillaWatch = own;
        return this;
    }

    /**
     * Tells {@code sync} about every cell this writer sends as a vanilla block update ({@link ClientSync#vanillaWrite}),
     * so light follow-ups near it wait for the light engine to process it (brush strokes; {@link #syncThrough} does this
     * for physics-on writes too). Returns this writer.
     */
    @Override
    public BlockWriter watchVanillaWrites(ClientUpdates sync) {
        this.vanillaWatch = ownSync(sync);
        return this;
    }

    /** {@code sync} as this world's {@link ClientSync} (the only kind this platform makes), or {@code null}. */
    private ClientSync ownSync(ClientUpdates sync) {
        if (sync == null) return null;
        if (!(sync instanceof ClientSync own) || own.world() != world) {
            throw new IllegalArgumentException("ClientSync of another world");
        }
        return own;
    }

    /**
     * Writes one cell unconditionally, without recording it.
     *
     * @param tile block-entity content for the new state, or {@code null} for the state's default block entity
     * @return the tile actually applied ({@code null} when none was given, it was stripped, or it failed to load)
     */
    public BlockEntityData write(int x, int y, int z, int handle, BlockEntityData tile) {
        if (y < world.getMinBuildHeight() || y >= world.getMaxBuildHeight()) return null;
        if (shouldStrip(handle, tile)) {
            tile = null;
            strippedNbt++;
        }
        BlockPos pos = new BlockPos(x, y, z);
        return apply(pos, world.getBlockState(pos), handle, tile);
    }

    /**
     * Writes one cell and records it. The cell's current state and block entity are read right before the write
     * (never from an earlier snapshot); when they already equal the target, nothing is written or recorded. The
     * record is made even if the write throws, with the cell's state after the failure as {@code after}.
     *
     * @return true when the cell was written (and recorded)
     */
    @Override
    public boolean write(int x, int y, int z, int handle, BlockEntityData tile, RecordSink sink) {
        return write(x, y, z, handle, tile, sink, null);
    }

    /**
     * {@link #write(int, int, int, int, BlockEntityData, RecordSink)}, but first asks {@code guard} (if not
     * {@code null}) about the cell's live state and block entity; a refused cell is neither written nor recorded, and
     * counted in {@link #refused()}.
     *
     * @return true when the cell was written (and recorded)
     */
    @Override
    public boolean write(int x, int y, int z, int handle, BlockEntityData tile, RecordSink sink, Guard guard) {
        Objects.requireNonNull(sink);
        if (y < world.getMinBuildHeight() || y >= world.getMaxBuildHeight()) return false;
        BlockPos pos = new BlockPos(x, y, z);
        BlockState old = world.getBlockState(pos);
        int before = Block.getId(old);
        // Captured before the guard, which may compare it (undo keeps a chest filled since), and recorded as before.
        BlockEntityData beforeTile = captureLive(pos, old);
        if (guard != null && !guard.mayReplace(before, beforeTile)) {
            refused++;
            return false;
        }
        boolean strip = shouldStrip(handle, tile);
        BlockEntityData target = strip ? null : tile;
        if (before == handle && sameTile(beforeTile, target)) return false;
        if (strip) strippedNbt++;

        BlockEntityData applied = null;
        boolean completed = false;
        try {
            applied = apply(pos, old, handle, target);
            completed = true;
        } finally {
            changed++;
            if (completed) {
                sink.record(x, y, z, before, beforeTile, handle, applied);
            } else {
                recordAfterFailure(pos, before, beforeTile, sink);
            }
        }
        return true;
    }

    /**
     * Removes scheduled block and fluid ticks at exactly the cells written with physics off since the last call.
     * Ticks at cells this writer skipped are kept.
     */
    @Override
    public void clearTicksAtWrittenCells() {
        // The light checks of the cells written so far go to the light engine now, about a thousand a task.
        if (light != null) light.flush();
        if (writtenCells.isEmpty()) return;
        LongOpenHashSet columns = new LongOpenHashSet();
        for (long key : writtenCells.keySet()) columns.add(columnOf(key));
        List<BlockPos> blockTicks = new ArrayList<>();
        List<BlockPos> fluidTicks = new ArrayList<>();
        for (long column : columns) {
            int cx = (int) (column >> 32), cz = (int) column;
            LevelChunk chunk = world.getChunkSource().getChunkNow(cx, cz);
            if (chunk == null) continue;
            collectTicks(chunk.getBlockTicks(), blockTicks);
            collectTicks(chunk.getFluidTicks(), fluidTicks);
        }
        LevelTicks<Block> blockScheduler = world.getBlockTicks();
        for (BlockPos pos : blockTicks) blockScheduler.clearArea(new BoundingBox(pos));
        LevelTicks<Fluid> fluidScheduler = world.getFluidTicks();
        for (BlockPos pos : fluidTicks) fluidScheduler.clearArea(new BoundingBox(pos));
        writtenCells.clear();
    }

    /**
     * Clears scheduled block and fluid ticks in the whole inclusive box, including cells this writer did not
     * write.
     *
     * @deprecated clears ticks of skipped (protected) cells too; use {@link #clearTicksAtWrittenCells()}
     */
    @Deprecated
    public void clearScheduledTicks(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        BoundingBox box = new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
        world.getBlockTicks().clearArea(box);
        world.getFluidTicks().clearArea(box);
    }

    /**
     * Light-engine tasks this writer's light checks took so far (one per chunk column and batch, {@link LightBatch}), or
     * -1 when its checks go to the light engine one per cell.
     */
    public long lightTasks() {
        return light == null ? -1 : light.flushedTasks();
    }

    /** Cells written so far (both write methods). */
    public long written() {
        return written;
    }

    /** Cells written and recorded by the recorded {@code write}, including one whose write threw. */
    @Override
    public long changed() {
        return changed;
    }

    /** Cells a {@link Guard} refused (not written, not recorded). */
    public long refused() {
        return refused;
    }

    /** Tiles removed because their state needs operator rights. */
    @Override
    public long strippedNbt() {
        return strippedNbt;
    }

    /** Tiles that could not be loaded (the block kept its default block entity). */
    @Override
    public long tileFailures() {
        return tileFailures;
    }

    /** A description of the first tile failure, or {@code null}. */
    @Override
    public String firstTileFailure() {
        return firstTileFailure;
    }

    private BlockEntityData apply(BlockPos pos, BlockState old, int handle, BlockEntityData tile) {
        BlockState state = states.state(handle);
        // Clients hear about the cell even when something below throws after (or while) changing it.
        try {
            if (options.physics()) {
                if (old.hasBlockEntity()) {
                    BlockEntity entity = existingEntity(pos);
                    if (entity != null) {
                        try (EditScope scope = EditScope.suppressPhysics()) {
                            Clearable.tryClear(entity);
                        }
                    }
                    removeBlockEntity(pos);
                }
                world.setBlock(pos, state, Block.UPDATE_ALL, Level.LONG_PARTICLE_CLIP_RANGE);
            } else {
                try (EditScope scope = EditScope.suppressPhysics()) {
                    if (old.hasBlockEntity()) removeBlockEntity(pos);
                    try (LightBatch.Scope batch = light == null ? null : light.collect()) {
                        world.setBlock(pos, state, Block.UPDATE_KNOWN_SHAPE, 0);
                    }
                }
                rememberWritten(pos);
            }
            written++;

            BlockEntityData applied = null;
            if (state.hasBlockEntity() && world.getBlockState(pos) == state) {
                if (tile != null && loadTile(pos, state, tile)) applied = tile;
                // Creates the default block entity when the state was unchanged (setBlockState returned early).
                world.getBlockEntity(pos);
            } else if (tile != null) {
                fail(pos, tile.typeId(), "the written state " + state + " has no block entity");
            }
            return applied;
        } finally {
            if (clientSync != null && !options.physics()) {
                clientSync.changed(pos);
            } else {
                world.getChunkSource().blockChanged(pos);
                if (vanillaWatch != null) vanillaWatch.vanillaWrite(pos.getX() >> 4, pos.getZ() >> 4);
            }
        }
    }

    /**
     * Operator-only NBT is kept for players allowed to write it, for trusted server-captured tiles, and for signs a
     * {@link TileSanitizer} reduced to plain text ({@link SanitizedTile}; its type must still fit the state, which
     * {@link #loadTile} checks). Everything else on an operator-NBT state is stripped.
     */
    private boolean shouldStrip(int handle, BlockEntityData tile) {
        if (tile == null || options.allowOperatorNbt()) return false;
        if ((states.flags(handle) & StateFlags.OPERATOR_NBT) == 0) return false;
        if (tile instanceof SanitizedTile) return false;
        return !(options.trustCapturedTiles() && McTile.isServerCaptured(tile));
    }

    private static boolean sameTile(BlockEntityData a, BlockEntityData b) {
        if (a == null || b == null) return a == b;
        return a.sameContent(b);
    }

    /** The existing block entity of a block-entity state, captured; never creates one. */
    private BlockEntityData captureLive(BlockPos pos, BlockState state) {
        if (!state.hasBlockEntity()) return null;
        BlockEntity entity = existingEntity(pos);
        return entity == null || entity.isRemoved() ? null : McTile.capture(entity, registries);
    }

    private BlockEntity existingEntity(BlockPos pos) {
        return world.getChunkAt(pos).getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK);
    }

    private void recordAfterFailure(BlockPos pos, int before, BlockEntityData beforeTile, RecordSink sink) {
        int after = before;
        BlockEntityData afterTile = beforeTile;
        try {
            BlockState now = world.getBlockState(pos);
            after = Block.getId(now);
            afterTile = captureLive(pos, now);
        } catch (RuntimeException e) {
            LOG.error("Could not read cell {} after a failed write", pos.toShortString(), e);
        }
        sink.record(pos.getX(), pos.getY(), pos.getZ(), before, beforeTile, after, afterTile);
    }

    private void rememberWritten(BlockPos pos) {
        long key = BlockBuffer.keyOfBlock(pos.getX(), pos.getY(), pos.getZ());
        long[] bits = writtenCells.get(key);
        if (bits == null) {
            bits = new long[SectionBuffer.SIZE / 64];
            writtenCells.put(key, bits);
        }
        int i = SectionBuffer.index(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
        bits[i >>> 6] |= 1L << i;
    }

    private boolean wasWritten(BlockPos pos) {
        long[] bits = writtenCells.get(BlockBuffer.keyOfBlock(pos.getX(), pos.getY(), pos.getZ()));
        if (bits == null) return false;
        int i = SectionBuffer.index(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
        return (bits[i >>> 6] & (1L << i)) != 0;
    }

    /** Positions of the chunk's scheduled ticks that sit on written cells. */
    private <T> void collectTicks(TickContainerAccess<T> scheduler, List<BlockPos> out) {
        if (scheduler instanceof LevelChunkTicks<T> chunkTicks) {
            if (chunkTicks.count() == 0) return;
            chunkTicks.getAll().map(ScheduledTick::pos).filter(this::wasWritten).forEach(out::add);
            return;
        }
        // Not a vanilla chunk scheduler: clear every written cell of this chunk individually.
        for (Long2ObjectMap.Entry<long[]> entry : writtenCells.long2ObjectEntrySet()) {
            long key = entry.getLongKey();
            int ox = BlockBuffer.keyX(key) << 4, oy = BlockBuffer.keyY(key) << 4, oz = BlockBuffer.keyZ(key) << 4;
            long[] bits = entry.getValue();
            for (int w = 0; w < bits.length; w++) {
                long word = bits[w];
                while (word != 0) {
                    int i = (w << 6) | Long.numberOfTrailingZeros(word);
                    word &= word - 1;
                    out.add(new BlockPos(ox + SectionBuffer.localX(i), oy + SectionBuffer.localY(i),
                            oz + SectionBuffer.localZ(i)));
                }
            }
        }
    }

    private static long columnOf(long sectionKey) {
        return ((long) BlockBuffer.keyX(sectionKey) << 32) | (BlockBuffer.keyZ(sectionKey) & 0xFFFFFFFFL);
    }

    private boolean loadTile(BlockPos pos, BlockState state, BlockEntityData tile) {
        CompoundTag nbt;
        try {
            nbt = McTile.from(tile).copyNbt();
        } catch (IOException | RuntimeException e) {
            fail(pos, tile.typeId(), "unreadable NBT: " + e);
            return false;
        }
        String typeId = nbt.getString("id");
        ResourceLocation id = ResourceLocation.tryParse(typeId);
        BlockEntityType<?> type = id == null ? null : BuiltInRegistries.BLOCK_ENTITY_TYPE.getOptional(id).orElse(null);
        if (type == null) {
            fail(pos, typeId, "unknown block-entity type");
            return false;
        }
        if (!type.isValid(state)) {
            fail(pos, typeId, "type does not fit " + state);
            return false;
        }
        BlockEntity entity;
        try {
            entity = BlockEntity.loadStatic(pos, state, nbt, registries);
        } catch (RuntimeException e) {
            fail(pos, typeId, e.toString());
            return false;
        }
        if (entity == null) {
            fail(pos, typeId, "createFromNbt returned null (see the log above)");
            return false;
        }
        // Unregisters the default block entity's game-event listener and ticker before the replacement is added.
        removeBlockEntity(pos);
        world.setBlockEntity(entity);
        // No markDirty(): it would update comparators. The state may be unchanged, so flag the chunk directly.
        world.getChunkAt(pos).setUnsaved(true);
        return true;
    }

    /**
     * Removes the block entity at {@code pos}: {@link #removeBlockEntityQuietly} with physics off. Physics-on writes
     * keep the loader's {@code Level.removeBlockEntity}: they update neighbours and comparators anyway
     * ({@code UPDATE_ALL}), and on NeoForge its notification is what other mods expect when a block entity goes.
     */
    private void removeBlockEntity(BlockPos pos) {
        if (options.physics()) {
            world.removeBlockEntity(pos);
        } else {
            removeBlockEntityQuietly(world, pos);
        }
    }

    /**
     * Removes the block entity at {@code pos} for a physics-free write, through the chunk. NeoForge patches
     * {@code Level.removeBlockEntity} to also call {@code updateNeighbourForOutputSignal}, so comparators next to the
     * cell would get a neighbour update and schedule a tick, which a physics-free write must not cause and an
     * {@link EditScope} does not suppress. Vanilla's {@code Level.removeBlockEntity} is exactly this chunk call behind
     * the build-height check, so nothing changes on Fabric.
     */
    public static void removeBlockEntityQuietly(Level world, BlockPos pos) {
        if (!world.isOutsideBuildHeight(pos)) {
            world.getChunkAt(pos).removeBlockEntity(pos);
        }
    }

    private void fail(BlockPos pos, String typeId, String reason) {
        tileFailures++;
        String message = typeId + " at " + pos.toShortString() + ": " + reason;
        if (firstTileFailure == null) firstTileFailure = message;
        if (tileFailures <= MAX_LOGGED_FAILURES) LOG.warn("Block entity not restored, keeping the default: {}", message);
    }
}
