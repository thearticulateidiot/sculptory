package dev.sculptory.core.testing;

import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.world.WorldReader;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * An in-memory {@link WorldReader} backed by a {@link BlockBuffer}. Cells never set read as air, and so
 * does everything outside the build height. Chunks are loaded unless marked otherwise; reading an unloaded
 * chunk throws {@link IllegalStateException}, to catch callers that skip {@link #isLoaded}.
 */
public final class FakeWorld implements WorldReader {
    private final StateSpace states;
    private final int bottomY;
    private final int topYExclusive;
    private final BlockBuffer blocks = new BlockBuffer();
    private final Map<Long, Boolean> loaded = new HashMap<>();
    private boolean loadedByDefault = true;
    /** Per column (chunk-key packing of x, z): the highest y ever given a non-air state or a tile. */
    private final Long2IntOpenHashMap columnTop = new Long2IntOpenHashMap();
    private boolean heightHints;

    /** A world with the vanilla overworld height, y -64 to 319. */
    public FakeWorld(StateSpace states) {
        this(states, -64, 320);
    }

    public FakeWorld(StateSpace states, int bottomY, int topYExclusive) {
        this.states = Objects.requireNonNull(states);
        if (bottomY >= topYExclusive) throw new IllegalArgumentException("Empty world height");
        this.bottomY = bottomY;
        this.topYExclusive = topYExclusive;
    }

    public void set(int x, int y, int z, int h) {
        checkHeight(y);
        Objects.checkIndex(h, states.size());
        blocks.set(x, y, z, h);
        if (!StateFlags.has(states.flags(h), StateFlags.AIR)) raiseTop(x, y, z);
    }

    /** Sets or removes a tile; set the cell's state first. */
    public void setTile(int x, int y, int z, BlockEntityData data) {
        checkHeight(y);
        blocks.setTile(x, y, z, data);
        if (data != null) raiseTop(x, y, z);
    }

    /**
     * Whether {@link #heightHint} answers (one above the highest cell ever set to a non-air state or given a tile
     * in the column, which never undershoots) instead of the default {@link #topYExclusive()}. Off by default.
     */
    public void setHeightHints(boolean on) {
        heightHints = on;
    }

    @Override
    public int heightHint(int x, int z) {
        if (!heightHints) return topYExclusive;
        long key = chunkKey(x, z);
        return columnTop.containsKey(key) ? columnTop.get(key) + 1 : bottomY;
    }

    private void raiseTop(int x, int y, int z) {
        long key = chunkKey(x, z);
        if (!columnTop.containsKey(key) || columnTop.get(key) < y) columnTop.put(key, y);
    }

    /** Sets every cell in {@code box} to {@code h}. */
    public void fill(Box box, int h) {
        for (int x = box.min().x(); x <= box.max().x(); x++) {
            for (int y = box.min().y(); y <= box.max().y(); y++) {
                for (int z = box.min().z(); z <= box.max().z(); z++) set(x, y, z, h);
            }
        }
    }

    public void setLoaded(int cx, int cz, boolean isLoaded) {
        loaded.put(chunkKey(cx, cz), isLoaded);
    }

    /** The loaded state of every chunk not set with {@link #setLoaded}. */
    public void setLoadedByDefault(boolean isLoaded) {
        loadedByDefault = isLoaded;
    }

    /** The backing buffer, for assertions. */
    public BlockBuffer blocks() {
        return blocks;
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
        return topYExclusive;
    }

    @Override
    public boolean isLoaded(int cx, int cz) {
        return loaded.getOrDefault(chunkKey(cx, cz), loadedByDefault);
    }

    @Override
    public int get(int x, int y, int z) {
        checkLoaded(x >> 4, z >> 4);
        if (y < bottomY || y >= topYExclusive) return states.air();
        int h = blocks.get(x, y, z);
        return h < 0 ? states.air() : h;
    }

    @Override
    public BlockEntityData tile(int x, int y, int z) {
        checkLoaded(x >> 4, z >> 4);
        return blocks.tile(x, y, z);
    }

    @Override
    public void copySection(int sx, int sy, int sz, SectionBuffer into) {
        checkLoaded(sx, sz);
        into.clearAll();
        SectionBuffer stored = blocks.section(BlockBuffer.key(sx, sy, sz));
        for (int i = 0; i < SectionBuffer.SIZE; i++) {
            int y = (sy << 4) + SectionBuffer.localY(i);
            int h = stored == null || y < bottomY || y >= topYExclusive ? -1 : stored.get(i);
            into.set(i, h < 0 ? states.air() : h);
        }
        if (stored != null) stored.forEachTile(into::setTile);
    }

    private void checkLoaded(int cx, int cz) {
        if (!isLoaded(cx, cz)) throw new IllegalStateException("Chunk not loaded: " + cx + "," + cz);
    }

    private void checkHeight(int y) {
        if (y < bottomY || y >= topYExclusive) throw new IllegalArgumentException("Outside build height: " + y);
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }
}
