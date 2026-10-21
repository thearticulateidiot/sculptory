package dev.sculptory.mc.world;

import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.world.WorldReader;
import dev.sculptory.server.platform.LiveReader;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.BitStorage;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * {@link WorldReader} over a {@link ServerLevel}, for the server thread only. Never loads chunks: reading an
 * unloaded chunk throws {@link IllegalStateException} (callers check {@link #isLoaded} first).
 *
 * <p>The last {@link LevelChunk} is cached so per-cell reads resolve each chunk once; call {@link #invalidate()}
 * when chunks may have unloaded (the executor does so at the start of every slice).
 */
public final class McWorldReader implements LiveReader {
    private final ServerLevel world;
    private final McStateSpace states;
    private final HolderLookup.Provider registries;
    private final int bottomY;
    private final int topY;
    /** The palette containers' data, read through the platform's seam ({@link McInternals}). */
    private final McInternals internals = McInternals.get();

    private long cachedPos = Long.MIN_VALUE;
    private LevelChunk cachedChunk;
    /**
     * Palettes {@link #getColumn} read, and their entries' handles (-1 until looked up), in slots by section position
     * modulo 8 on each axis ({@link #paletteSlot}): sections less than 8 apart (all a brush's box reaches) never share
     * one, and a slot that holds another palette is taken over.
     */
    private final Palette<?>[] cachedPalettes = new Palette<?>[512];
    private final int[][] cachedHandles = new int[512][];

    public McWorldReader(ServerLevel world, McStateSpace states) {
        this.world = Objects.requireNonNull(world);
        this.states = Objects.requireNonNull(states);
        this.registries = world.registryAccess();
        this.bottomY = world.getMinBuildHeight();
        this.topY = world.getMaxBuildHeight();
    }

    public ServerLevel world() {
        return world;
    }

    @Override
    public McStateSpace states() {
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
        LevelChunk chunk = chunk(x >> 4, z >> 4);
        LevelChunkSection section = chunk.getSection(world.getSectionIndexFromSectionY(y >> 4));
        if (section == null) return states.air();
        McInternals.PaletteData<BlockState> data = internals.paletteData(section.getStates());
        BitStorage storage = data.storage();
        if (storage.getSize() != SectionBuffer.SIZE) {
            return Block.getId(section.getBlockState(x & 15, y & 15, z & 15));
        }
        Palette<BlockState> palette = data.palette();
        int slot = paletteSlot(palette, x >> 4, y >> 4, z >> 4);
        return handle(slot, palette, storage.get(((y & 15) << 8) | ((z & 15) << 4) | (x & 15)));
    }

    /**
     * The column section by section from the containers' own palettes and packed indices (through
     * {@link McInternals}, as {@link #copyStates}): what {@link #get} gives for each cell, without finding the chunk,
     * the section and the handle again for every one. A {@link SimpleBitStorage}'s words are read directly (vanilla's layout: {@code 64 / bits}
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
            LevelChunk chunk = chunk(x >> 4, z >> 4);
            int lx = x & 15, lz = z & 15;
            while (y < end && y < topY) {
                int sectionEnd = Math.min(Math.min(end, topY), ((y >> 4) + 1) << 4);
                LevelChunkSection section = chunk.getSection(world.getSectionIndexFromSectionY(y >> 4));
                if (section == null) {
                    for (; y < sectionEnd; y++) into[offset + y - y0] = air;
                    continue;
                }
                PalettedContainer<BlockState> container = section.getStates();
                McInternals.PaletteData<BlockState> data = internals.paletteData(container);
                BitStorage storage = data.storage();
                if (storage.getSize() != SectionBuffer.SIZE) {
                    // Not vanilla's layout: the plain way.
                    for (; y < sectionEnd; y++) into[offset + y - y0] = handleOf(container.get(lx, y & 15, lz));
                    continue;
                }
                Palette<BlockState> palette = data.palette();
                int slot = paletteSlot(palette, x >> 4, y >> 4, z >> 4);
                // Storage index (y << 8) | (z << 4) | x: 256 apart up the column.
                int index = ((y & 15) << 8) | (lz << 4) | lx;
                if (storage instanceof SimpleBitStorage packed) {
                    long[] words = packed.getRaw();
                    int bits = packed.getBits(), perWord = 64 / bits;
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
        if (h < 0) h = handles[entry] = handleOf(palette.valueFor(entry));
        return h;
    }

    @Override
    public BlockEntityData tile(int x, int y, int z) {
        if (y < bottomY || y >= topY) return null;
        BlockEntity entity = chunk(x >> 4, z >> 4).getBlockEntities().get(new BlockPos(x, y, z));
        return entity == null ? null : McTile.capture(entity, registries);
    }

    @Override
    public void copySection(int sx, int sy, int sz, SectionBuffer into) {
        into.clearAll();
        int minSection = world.getMinSection();
        if (sy < minSection || sy >= world.getMaxSection()) {
            fill(into, states.air());
            return;
        }
        LevelChunk chunk = chunk(sx, sz);
        LevelChunkSection section = chunk.getSection(sy - minSection);
        if (section == null) {
            fill(into, states.air());
        } else {
            copyStates(section.getStates(), into);
        }
        copyTiles(chunk, sx, sy, sz, into);
    }

    /**
     * The chunk's {@code WORLD_SURFACE} heightmap: one above the column's highest non-air block, so every cell at
     * or above it is air. Checked with javap (Minecraft 1.21.1, Mojang names): the heightmap's predicate is {@code Heightmap.NOT_AIR}
     * ({@code !state.isAir()}), {@code LevelChunk.setBlockState} updates it on every state change (physics off
     * too), and {@code ChunkAccess.getHeight} returns {@code Heightmap.getFirstAvailable(x & 15, z & 15) - 1}. Air states have
     * no block entity and carry {@code StateFlags.AIR}, which is what the brush kernel's scan skips. The type's
     * purpose is {@code CLIENT}, so client chunks keep the same heightmap. The chunk must be loaded.
     */
    @Override
    public int heightHint(int x, int z) {
        return chunk(x >> 4, z >> 4).getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 1;
    }

    /** Drops the cached chunk and palettes. */
    @Override
    public void invalidate() {
        cachedPos = Long.MIN_VALUE;
        cachedChunk = null;
        Arrays.fill(cachedPalettes, null);
        Arrays.fill(cachedHandles, null);
    }

    /** The loaded chunk, or {@code null}; never loads. */
    public LevelChunk chunkOrNull(int cx, int cz) {
        long pos = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
        if (pos == cachedPos && cachedChunk != null) return cachedChunk;
        LevelChunk chunk = world.getChunkSource().getChunkNow(cx, cz);
        if (chunk != null) {
            cachedPos = pos;
            cachedChunk = chunk;
        }
        return chunk;
    }

    private LevelChunk chunk(int cx, int cz) {
        LevelChunk chunk = chunkOrNull(cx, cz);
        if (chunk == null) throw new IllegalStateException("Chunk " + cx + "," + cz + " is not loaded");
        return chunk;
    }

    /**
     * Copies the states from the container's own palette and packed indices (through {@link McInternals}; vanilla's
     * storage order {@code (y << 8) | (z << 4) | x} is the buffer's), mapping each palette entry to its handle once.
     * Stale palette entries are never looked at unless a cell uses them. About ten times faster than reading every
     * cell through {@code container.get}, which matters for copies (one server tick for the whole area).
     */
    private void copyStates(PalettedContainer<BlockState> container, SectionBuffer into) {
        McInternals.PaletteData<BlockState> data = internals.paletteData(container);
        Palette<BlockState> palette = data.palette();
        BitStorage storage = data.storage();
        if (storage.getSize() != SectionBuffer.SIZE) {
            copyStatesCellByCell(container, into);
            return;
        }
        int[] cells = new int[SectionBuffer.SIZE];
        storage.unpack(cells);
        int entries = palette.getSize();
        if (entries <= SectionBuffer.SIZE) {
            int[] handles = new int[entries];
            Arrays.fill(handles, -1);
            for (int i = 0; i < cells.length; i++) {
                int p = cells[i];
                int h = handles[p];
                if (h < 0) {
                    h = handleOf(palette.valueFor(p));
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
                    lastHandle = handleOf(palette.valueFor(p));
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

    private void copyTiles(LevelChunk chunk, int sx, int sy, int sz, SectionBuffer into) {
        Map<BlockPos, BlockEntity> entities = chunk.getBlockEntities();
        if (entities.isEmpty()) return;
        for (Map.Entry<BlockPos, BlockEntity> entry : entities.entrySet()) {
            BlockPos pos = entry.getKey();
            if (pos.getY() >> 4 != sy || pos.getX() >> 4 != sx || pos.getZ() >> 4 != sz) continue;
            BlockEntity entity = entry.getValue();
            if (entity == null || entity.isRemoved()) continue;
            into.setTile(SectionBuffer.index(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15),
                    McTile.capture(entity, registries));
        }
    }

    private int handleOf(BlockState state) {
        int h = state == null ? -1 : Block.getId(state);
        if (h < 0) throw new IllegalStateException("Block state not in the state space: " + state);
        return h;
    }

    private static void fill(SectionBuffer into, int handle) {
        int[] cells = new int[SectionBuffer.SIZE];
        Arrays.fill(cells, handle);
        into.setDense(cells);
    }
}
