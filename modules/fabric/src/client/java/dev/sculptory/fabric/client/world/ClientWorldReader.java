package dev.sculptory.fabric.client.world;

import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.world.WorldReader;
import java.util.Objects;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientChunkManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.PalettedContainer;
import net.minecraft.world.chunk.WorldChunk;

/**
 * {@link WorldReader} over the client world, for brush prediction and cursor sampling. Handles are raw block-state
 * ids ({@code Block.STATE_IDS}), the same as the client's {@code FabricStateSpace}; {@link #blockState} and
 * {@link #handle} convert. Render thread only.
 *
 * <p>Cheap enough to call every frame: each read is a lookup in the client chunk map (no cache to go stale when a
 * chunk is resent) plus a palette read. It never loads anything: chunks the client does not have read as air,
 * {@link #isLoaded} is false for them and {@link #heightHint} says nothing about them.
 *
 * <p>The client only knows the block-entity data the server chose to send, so {@link #tile} is always
 * {@code null} and {@link #copySection} copies no tiles. Kernels tell block-entity blocks apart by the state flag
 * {@code HAS_BLOCK_ENTITY}, which does not depend on this.
 */
public final class ClientWorldReader implements WorldReader {
    private final ClientWorld world;
    private final ClientChunkManager chunks;
    private final StateSpace states;
    private final int bottomY;
    private final int topY;
    private final int bottomSection;
    private final int topSection;

    public ClientWorldReader(ClientWorld world, StateSpace states) {
        this.world = Objects.requireNonNull(world);
        this.states = Objects.requireNonNull(states);
        this.chunks = world.getChunkManager();
        this.bottomY = world.getBottomY();
        this.topY = world.getTopY();
        this.bottomSection = world.getBottomSectionCoord();
        this.topSection = world.getTopSectionCoord();
    }

    /** Whether this reader reads {@code world} in handles of {@code states}. */
    public boolean readsFrom(ClientWorld world, StateSpace states) {
        return this.world == world && this.states == states;
    }

    /** The vanilla state of a handle (air for an unknown one). */
    public static BlockState blockState(int handle) {
        return Block.getStateFromRawId(handle);
    }

    /** The handle of a vanilla state. */
    public static int handle(BlockState state) {
        return Block.getRawIdFromState(state);
    }

    @Override
    public StateSpace states() {
        return states;
    }

    @Override
    public int bottomY() {
        return bottomY;
    }

    @Override
    public int topYExclusive() {
        return topY;
    }

    @Override
    public boolean isLoaded(int cx, int cz) {
        return chunk(cx, cz) != null;
    }

    @Override
    public int get(int x, int y, int z) {
        if (y < bottomY || y >= topY) return states.air();
        WorldChunk chunk = chunk(x >> 4, z >> 4);
        if (chunk == null) return states.air();
        ChunkSection section = chunk.getSection((y >> 4) - bottomSection);
        if (section == null) return states.air();
        return handleIn(states, section.getBlockState(x & 15, y & 15, z & 15));
    }

    /** Always {@code null}: see the class comment. */
    @Override
    public BlockEntityData tile(int x, int y, int z) {
        return null;
    }

    @Override
    public void copySection(int sx, int sy, int sz, SectionBuffer into) {
        into.clearAll();
        WorldChunk chunk = sy >= bottomSection && sy < topSection ? chunk(sx, sz) : null;
        ChunkSection section = chunk == null ? null : chunk.getSection(sy - bottomSection);
        if (section == null) {
            fill(into, states.air());
        } else {
            copyStates(section.getBlockStateContainer(), states, into);
        }
    }

    /**
     * The client's {@code WORLD_SURFACE} heightmap: one above the highest non-air block of the column, which is the
     * y at and above which the column holds only air. Vanilla sends that heightmap with every chunk
     * ({@code Heightmap.Type.WORLD_SURFACE} has purpose {@code CLIENT}) and {@code WorldChunk.setBlockState} keeps it
     * current for every client-side change, predictions included (checked with javap). Columns of chunks the
     * client does not have, or without the heightmap, get {@link #topYExclusive()}, which says nothing.
     */
    @Override
    public int heightHint(int x, int z) {
        WorldChunk chunk = chunk(x >> 4, z >> 4);
        if (chunk == null || !chunk.hasHeightmap(Heightmap.Type.WORLD_SURFACE)) return topY;
        int hint = chunk.getHeightmap(Heightmap.Type.WORLD_SURFACE).get(x & 15, z & 15);
        return Math.max(bottomY, Math.min(topY, hint));
    }

    /** The chunk if the client has it, else {@code null}; never loads (create = false). */
    private WorldChunk chunk(int cx, int cz) {
        return chunks.getChunk(cx, cz, ChunkStatus.FULL, false);
    }

    /**
     * Copies a section's block states into {@code into} (cell order {@code y, z, x}, as vanilla). A section whose
     * palette holds a single state (all air, all stone...) is filled without reading its cells; otherwise each
     * cell is read once, with runs of the same state resolved once.
     */
    static void copyStates(PalettedContainer<BlockState> container, StateSpace states, SectionBuffer into) {
        BlockState first = container.get(0, 0, 0);
        if (!container.hasAny(state -> state != first)) {
            fill(into, handleIn(states, first));
            return;
        }
        BlockState last = first;
        int lastHandle = handleIn(states, first);
        int i = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++, i++) {
                    BlockState state = container.get(x, y, z);
                    if (state != last) {
                        last = state;
                        lastHandle = handleIn(states, state);
                    }
                    into.set(i, lastHandle);
                }
            }
        }
    }

    /** The handle of {@code state}, or air if it is outside {@code states} (a space built for other registries). */
    private static int handleIn(StateSpace states, BlockState state) {
        int h = Block.getRawIdFromState(state);
        return h >= 0 && h < states.size() ? h : states.air();
    }

    private static void fill(SectionBuffer into, int handle) {
        for (int i = 0; i < SectionBuffer.SIZE; i++) into.set(i, handle);
    }
}
