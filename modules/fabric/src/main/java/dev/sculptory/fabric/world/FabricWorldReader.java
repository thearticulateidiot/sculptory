package dev.sculptory.fabric.world;

import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.world.WorldReader;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.collection.PackedIntegerArray;
import net.minecraft.util.collection.PaletteStorage;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.Palette;
import net.minecraft.world.chunk.PalettedContainer;
import net.minecraft.world.chunk.WorldChunk;

/**
 * {@link WorldReader} over a {@link ServerWorld}, for the server thread only. Never loads chunks: reading an
 * unloaded chunk throws {@link IllegalStateException} (callers check {@link #isLoaded} first).
 *
 * <p>The last {@link WorldChunk} is cached so per-cell reads resolve each chunk once; call {@link #invalidate()}
 * when chunks may have unloaded (the executor does so at the start of every slice).
 */
public final class FabricWorldReader implements WorldReader {
    private final ServerWorld world;
    private final FabricStateSpace states;
    private final RegistryWrapper.WrapperLookup registries;
    private final int bottomY;
    private final int topY;

    private long cachedPos = Long.MIN_VALUE;
    private WorldChunk cachedChunk;
    /**
     * Palettes {@link #getColumn} read, and their entries' handles (-1 until looked up), in slots by section position
     * modulo 8 on each axis ({@link #paletteSlot}): sections less than 8 apart (all a brush's box reaches) never share
     * one, and a slot that holds another palette is taken over.
     */
    private final Palette<?>[] cachedPalettes = new Palette<?>[512];
    private final int[][] cachedHandles = new int[512][];

    public FabricWorldReader(ServerWorld world, FabricStateSpace states) {
        this.world = Objects.requireNonNull(world);
        this.states = Objects.requireNonNull(states);
        this.registries = world.getRegistryManager();
        this.bottomY = world.getBottomY();
        this.topY = world.getTopY();
    }

    public ServerWorld world() {
        return world;
    }

    @Override
    public FabricStateSpace states() {
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
        return chunkOrNull(cx, cz) != null;
    }

    /**
     * The state at (x, y, z): the palette entry from the section's packed indices, its handle looked up once per palette
     * ({@link #paletteSlot}) instead of in the game's state table for every cell (the brush kernels read cells one by
     * one).
     */
    @Override
    public int get(int x, int y, int z) {
        if (y < bottomY || y >= topY) return states.air();
        WorldChunk chunk = chunk(x >> 4, z >> 4);
        ChunkSection section = chunk.getSection(world.sectionCoordToIndex(y >> 4));
        if (section == null) return states.air();
        PalettedContainer.Data<BlockState> data = section.getBlockStateContainer().data;
        PaletteStorage storage = data.storage();
        if (storage.getSize() != SectionBuffer.SIZE) {
            return Block.getRawIdFromState(section.getBlockState(x & 15, y & 15, z & 15));
        }
        Palette<BlockState> palette = data.palette();
        int slot = paletteSlot(palette, x >> 4, y >> 4, z >> 4);
        return handle(slot, palette, storage.get(((y & 15) << 8) | ((z & 15) << 4) | (x & 15)));
    }

    /**
     * The column section by section from the containers' own palettes and packed indices (widened access, as
     * {@link #copyStates}): what {@link #get} gives for each cell, without finding the chunk, the section and the handle
     * again for every one. A {@link PackedIntegerArray}'s words are read directly (vanilla's layout: {@code 64 / bits}
     * entries a word from the lowest bits up, none across words, as its own {@code get} reads them); a palette entry's
     * handle is looked up once per palette ({@link #paletteSlot}).
     */
    @Override
    public void getColumn(int x, int z, int y0, int count, int[] into, int offset) {
        int air = states.air();
        int end = y0 + count;
        int y = y0;
        for (; y < end && y < bottomY; y++) into[offset + y - y0] = air;
        if (y < end && y < topY) {
            WorldChunk chunk = chunk(x >> 4, z >> 4);
            int lx = x & 15, lz = z & 15;
            while (y < end && y < topY) {
                int sectionEnd = Math.min(Math.min(end, topY), ((y >> 4) + 1) << 4);
                ChunkSection section = chunk.getSection(world.sectionCoordToIndex(y >> 4));
                if (section == null) {
                    for (; y < sectionEnd; y++) into[offset + y - y0] = air;
                    continue;
                }
                PalettedContainer<BlockState> container = section.getBlockStateContainer();
                PalettedContainer.Data<BlockState> data = container.data;
                PaletteStorage storage = data.storage();
                if (storage.getSize() != SectionBuffer.SIZE) {
                    // Not vanilla's layout: the plain way.
                    for (; y < sectionEnd; y++) into[offset + y - y0] = handleOf(container.get(lx, y & 15, lz));
                    continue;
                }
                Palette<BlockState> palette = data.palette();
                int slot = paletteSlot(palette, x >> 4, y >> 4, z >> 4);
                // Storage index (y << 8) | (z << 4) | x: 256 apart up the column.
                int index = ((y & 15) << 8) | (lz << 4) | lx;
                if (storage instanceof PackedIntegerArray packed) {
                    long[] words = packed.getData();
                    int bits = packed.getElementBits(), perWord = 64 / bits;
                    long mask = (1L << bits) - 1;
                    int word = index / perWord, inWord = index - word * perWord;
                    int wordStep = 256 / perWord, inWordStep = 256 - wordStep * perWord;
                    for (; y < sectionEnd; y++) {
                        int p = (int) (words[word] >>> (inWord * bits) & mask);
                        into[offset + y - y0] = handle(slot, palette, p);
                        word += wordStep;
                        inWord += inWordStep;
                        if (inWord >= perWord) {
                            inWord -= perWord;
                            word++;
                        }
                    }
                } else {
                    for (; y < sectionEnd; y++, index += 256) {
                        into[offset + y - y0] = handle(slot, palette, storage.get(index));
                    }
                }
            }
        }
        for (; y < end; y++) into[offset + y - y0] = air;
    }

    /** The slot of section (sx, sy, sz)'s palette {@code palette}, taken over when it holds another. */
    private int paletteSlot(Palette<BlockState> palette, int sx, int sy, int sz) {
        int slot = ((sx & 7) << 6) | ((sy & 7) << 3) | (sz & 7);
        if (cachedPalettes[slot] != palette) {
            cachedPalettes[slot] = palette;
            cachedHandles[slot] = new int[16];
            Arrays.fill(cachedHandles[slot], -1);
        }
        return slot;
    }

    /**
     * The handle of entry {@code entry} of the palette in slot {@code slot}, looked up the first time. A palette gains
     * entries in place until it is replaced (never changing one it has), so an entry's handle stays right.
     */
    private int handle(int slot, Palette<BlockState> palette, int entry) {
        int[] handles = cachedHandles[slot];
        if (entry >= handles.length) {
            int old = handles.length;
            handles = cachedHandles[slot] = Arrays.copyOf(handles, Math.max(entry + 1, 2 * old));
            Arrays.fill(handles, old, handles.length, -1);
        }
        int h = handles[entry];
        if (h < 0) h = handles[entry] = handleOf(palette.get(entry));
        return h;
    }

    @Override
    public BlockEntityData tile(int x, int y, int z) {
        if (y < bottomY || y >= topY) return null;
        BlockEntity entity = chunk(x >> 4, z >> 4).getBlockEntities().get(new BlockPos(x, y, z));
        return entity == null ? null : FabricTile.capture(entity, registries);
    }

    @Override
    public void copySection(int sx, int sy, int sz, SectionBuffer into) {
        into.clearAll();
        int minSection = world.getBottomSectionCoord();
        if (sy < minSection || sy >= world.getTopSectionCoord()) {
            fill(into, states.air());
            return;
        }
        WorldChunk chunk = chunk(sx, sz);
        ChunkSection section = chunk.getSection(sy - minSection);
        if (section == null) {
            fill(into, states.air());
        } else {
            copyStates(section.getBlockStateContainer(), into);
        }
        copyTiles(chunk, sx, sy, sz, into);
    }

    /**
     * The chunk's {@code WORLD_SURFACE} heightmap: one above the column's highest non-air block, so every cell at
     * or above it is air. Checked with javap (yarn 1.21.1): the heightmap's predicate is {@code Heightmap.NOT_AIR}
     * ({@code !state.isAir()}), {@code WorldChunk.setBlockState} updates it on every state change (physics off
     * too), and {@code Chunk.sampleHeightmap} returns {@code Heightmap.get(x & 15, z & 15) - 1}. Air states have
     * no block entity and carry {@code StateFlags.AIR}, which is what the brush kernel's scan skips. The type's
     * purpose is {@code CLIENT}, so client chunks keep the same heightmap. The chunk must be loaded.
     */
    @Override
    public int heightHint(int x, int z) {
        return chunk(x >> 4, z >> 4).sampleHeightmap(Heightmap.Type.WORLD_SURFACE, x, z) + 1;
    }

    /** Drops the cached chunk and palettes. */
    public void invalidate() {
        cachedPos = Long.MIN_VALUE;
        cachedChunk = null;
        Arrays.fill(cachedPalettes, null);
        Arrays.fill(cachedHandles, null);
    }

    /** The loaded chunk, or {@code null}; never loads. */
    public WorldChunk chunkOrNull(int cx, int cz) {
        long pos = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
        if (pos == cachedPos && cachedChunk != null) return cachedChunk;
        WorldChunk chunk = world.getChunkManager().getWorldChunk(cx, cz);
        if (chunk != null) {
            cachedPos = pos;
            cachedChunk = chunk;
        }
        return chunk;
    }

    private WorldChunk chunk(int cx, int cz) {
        WorldChunk chunk = chunkOrNull(cx, cz);
        if (chunk == null) throw new IllegalStateException("Chunk " + cx + "," + cz + " is not loaded");
        return chunk;
    }

    /**
     * Copies the states from the container's own palette and packed indices (widened access; vanilla's storage
     * order {@code (y << 8) | (z << 4) | x} is the buffer's), mapping each palette entry to its handle once. Stale
     * palette entries are never looked at unless a cell uses them. About ten times faster than reading every cell
     * through {@code container.get}, which matters for copies (one server tick for the whole area).
     */
    private void copyStates(PalettedContainer<BlockState> container, SectionBuffer into) {
        PalettedContainer.Data<BlockState> data = container.data;
        Palette<BlockState> palette = data.palette();
        PaletteStorage storage = data.storage();
        if (storage.getSize() != SectionBuffer.SIZE) {
            copyStatesCellByCell(container, into);
            return;
        }
        int[] cells = new int[SectionBuffer.SIZE];
        storage.writePaletteIndices(cells);
        int entries = palette.getSize();
        if (entries <= SectionBuffer.SIZE) {
            int[] handles = new int[entries];
            Arrays.fill(handles, -1);
            for (int i = 0; i < cells.length; i++) {
                int p = cells[i];
                int h = handles[p];
                if (h < 0) {
                    h = handleOf(palette.get(p));
                    handles[p] = h;
                }
                cells[i] = h;
            }
        } else {
            // The global palette (more than 256 states in the section): look entries up as the cells change.
            int lastIndex = -1;
            int lastHandle = -1;
            for (int i = 0; i < cells.length; i++) {
                int p = cells[i];
                if (p != lastIndex) {
                    lastIndex = p;
                    lastHandle = handleOf(palette.get(p));
                }
                cells[i] = lastHandle;
            }
        }
        into.setDense(cells);
    }

    /** The plain way, for a container of another size (not vanilla's block-state layout). */
    private void copyStatesCellByCell(PalettedContainer<BlockState> container, SectionBuffer into) {
        int[] cells = new int[SectionBuffer.SIZE];
        BlockState last = null;
        int lastHandle = -1;
        int i = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++, i++) {
                    BlockState state = container.get(x, y, z);
                    if (state != last) {
                        last = state;
                        lastHandle = handleOf(state);
                    }
                    cells[i] = lastHandle;
                }
            }
        }
        into.setDense(cells);
    }

    private void copyTiles(WorldChunk chunk, int sx, int sy, int sz, SectionBuffer into) {
        Map<BlockPos, BlockEntity> entities = chunk.getBlockEntities();
        if (entities.isEmpty()) return;
        for (Map.Entry<BlockPos, BlockEntity> entry : entities.entrySet()) {
            BlockPos pos = entry.getKey();
            if (pos.getY() >> 4 != sy || pos.getX() >> 4 != sx || pos.getZ() >> 4 != sz) continue;
            BlockEntity entity = entry.getValue();
            if (entity == null || entity.isRemoved()) continue;
            into.setTile(SectionBuffer.index(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15),
                    FabricTile.capture(entity, registries));
        }
    }

    private int handleOf(BlockState state) {
        int h = state == null ? -1 : Block.getRawIdFromState(state);
        if (h < 0) throw new IllegalStateException("Block state not in the state space: " + state);
        return h;
    }

    private static void fill(SectionBuffer into, int handle) {
        int[] cells = new int[SectionBuffer.SIZE];
        Arrays.fill(cells, handle);
        into.setDense(cells);
    }
}
