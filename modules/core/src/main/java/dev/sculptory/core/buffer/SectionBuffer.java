package dev.sculptory.core.buffer;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.shorts.Short2ObjectOpenHashMap;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.IntConsumer;

/**
 * One 16³ section of block-state handles, with optional per-cell presence and block-entity data.
 * Cell index is {@code (y << 8) | (z << 4) | x} with local coordinates 0..15 (vanilla order).
 *
 * <p>Storage: a palette of handles plus packed palette indices. {@code bits} grows 0 → 1 → 2 → 4 → 8 → 16
 * as the palette grows ({@code bits == 0} means uniform: at most one handle, no index array). Entries per
 * long are {@code 64 / bits}, so no index straddles two longs. The palette only grows; {@link #compact()}
 * drops unused entries.
 *
 * <p>Presence: a 4096-bit mask (64 longs), or {@code null} when every cell is present (dense). A new
 * buffer is empty; it becomes dense automatically when its last absent cell is set.
 *
 * <p>Tiles: block-entity data for present cells only, in a sparse map. Clearing a cell drops its tile.
 *
 * <p>Not thread-safe.
 */
public final class SectionBuffer {
    public static final int SIZE = 4096;
    /** Palette capacity at 16 bits; reaching it triggers an in-place compaction. */
    static final int MAX_PALETTE = 1 << 16;
    private static final int LINEAR_LOOKUP_MAX = 16;
    private static final int MASK_WORDS = SIZE / 64;

    private int[] palette;
    private int paletteSize;
    /** Handle → palette index, built once the palette outgrows a linear scan. */
    private Int2IntOpenHashMap paletteLookup;
    private int bits;
    private long[] data;
    private long[] presence;
    private int count;
    private Short2ObjectOpenHashMap<BlockEntityData> tiles;

    /** An empty (all cells absent) buffer. */
    public SectionBuffer() {
        palette = new int[4];
        presence = new long[MASK_WORDS];
    }

    /** A dense buffer with every cell set to {@code handle}. */
    public static SectionBuffer uniform(int handle) {
        checkHandle(handle);
        SectionBuffer buffer = new SectionBuffer();
        buffer.palette[0] = handle;
        buffer.paletteSize = 1;
        buffer.presence = null;
        buffer.count = SIZE;
        return buffer;
    }

    /** Cell index of local coordinates 0..15. */
    public static int index(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }

    public static int localX(int index) {
        return index & 15;
    }

    public static int localY(int index) {
        return index >>> 8;
    }

    public static int localZ(int index) {
        return (index >>> 4) & 15;
    }

    /** The handle at {@code i}, or -1 if the cell is absent. */
    public int get(int i) {
        Objects.checkIndex(i, SIZE);
        if (!present(i)) return -1;
        return bits == 0 ? palette[0] : palette[readIndex(i)];
    }

    public boolean has(int i) {
        Objects.checkIndex(i, SIZE);
        return present(i);
    }

    /** Sets a cell (marking it present). Keeps any tile already at {@code i}. */
    public void set(int i, int h) {
        Objects.checkIndex(i, SIZE);
        checkHandle(h);
        int p = lookup(h);
        if (p < 0) p = append(h);
        if (bits != 0) writeIndex(i, p);
        if (presence != null && !present(i)) {
            presence[i >>> 6] |= 1L << i;
            if (++count == SIZE) presence = null;
        }
    }

    /** Marks a cell absent and drops its tile. */
    public void clear(int i) {
        Objects.checkIndex(i, SIZE);
        if (!present(i)) return;
        if (presence == null) {
            presence = new long[MASK_WORDS];
            Arrays.fill(presence, -1L);
        }
        presence[i >>> 6] &= ~(1L << i);
        count--;
        removeTile(i);
    }

    /**
     * Replaces the whole content at once: every cell present, cell {@code i} holding {@code handles[i]}, no tiles. The
     * result equals setting each cell in index order on an empty buffer (palette in first-use order, smallest index
     * width), but in two tight passes.
     *
     * @throws IllegalArgumentException if {@code handles} is not {@value #SIZE} long or holds a negative handle
     */
    public void setDense(int[] handles) {
        if (handles.length != SIZE) throw new IllegalArgumentException("Expected " + SIZE + " handles");
        checkHandle(handles[0]); // the loop below checks a handle only when it differs from the one before
        int[] pal = new int[4];
        int n = 0;
        Int2IntOpenHashMap map = null;
        int[] indices = new int[SIZE];
        int last = -1;
        int lastIndex = -1;
        for (int i = 0; i < SIZE; i++) {
            int h = handles[i];
            if (h != last) {
                checkHandle(h);
                int p = -1;
                if (map != null) {
                    p = map.get(h);
                } else {
                    for (int q = 0; q < n; q++) {
                        if (pal[q] == h) {
                            p = q;
                            break;
                        }
                    }
                }
                if (p < 0) {
                    if (n == pal.length) pal = Arrays.copyOf(pal, n * 2);
                    p = n++;
                    pal[p] = h;
                    if (map != null) {
                        map.put(h, p);
                    } else if (n > LINEAR_LOOKUP_MAX) {
                        map = new Int2IntOpenHashMap(Math.max(32, n * 2));
                        map.defaultReturnValue(-1);
                        for (int q = 0; q < n; q++) map.put(pal[q], q);
                    }
                }
                last = h;
                lastIndex = p;
            }
            indices[i] = lastIndex;
        }
        palette = pal;
        paletteSize = n;
        paletteLookup = null;
        if (n > LINEAR_LOOKUP_MAX) buildLookup();
        bits = bitsFor(n);
        if (bits == 0) {
            data = null;
        } else {
            // The same layout as write(): 64 / bits entries per long (a power of two), low bits first.
            int perLong = 64 / bits;
            int wordShift = Integer.numberOfTrailingZeros(perLong);
            int slotMask = perLong - 1;
            long[] packed = new long[SIZE / perLong];
            for (int i = 0; i < SIZE; i++) packed[i >>> wordShift] |= (long) indices[i] << ((i & slotMask) * bits);
            data = packed;
        }
        presence = null;
        count = SIZE;
        tiles = null;
    }

    /** Resets to an empty buffer. */
    public void clearAll() {
        palette = new int[4];
        paletteSize = 0;
        paletteLookup = null;
        bits = 0;
        data = null;
        presence = new long[MASK_WORDS];
        count = 0;
        tiles = null;
    }

    /** The tile at {@code i}, or {@code null}. */
    public BlockEntityData tile(int i) {
        Objects.checkIndex(i, SIZE);
        return tiles == null ? null : tiles.get((short) i);
    }

    /**
     * Sets or (with {@code null}) removes the tile at {@code i}.
     *
     * @throws IllegalStateException when setting a tile on an absent cell
     */
    public void setTile(int i, BlockEntityData d) {
        Objects.checkIndex(i, SIZE);
        if (d == null) {
            removeTile(i);
            return;
        }
        if (!present(i)) throw new IllegalStateException("No block state at index " + i);
        if (tiles == null) tiles = new Short2ObjectOpenHashMap<>();
        tiles.put((short) i, d);
    }

    public int tileCount() {
        return tiles == null ? 0 : tiles.size();
    }

    @FunctionalInterface
    public interface TileConsumer {
        void accept(int index, BlockEntityData data);
    }

    /** Visits tiles in ascending index order. */
    public void forEachTile(TileConsumer action) {
        if (tiles == null) return;
        short[] keys = tiles.keySet().toShortArray();
        Arrays.sort(keys);
        for (short key : keys) action.accept(key, tiles.get(key));
    }

    /** Visits present cells in ascending index order. */
    public void forEachPresent(IntConsumer action) {
        if (presence == null) {
            for (int i = 0; i < SIZE; i++) action.accept(i);
            return;
        }
        long[] mask = presence;
        for (int w = 0; w < MASK_WORDS; w++) {
            long word = mask[w];
            while (word != 0) {
                action.accept((w << 6) | Long.numberOfTrailingZeros(word));
                word &= word - 1;
            }
        }
    }

    public int presentCount() {
        return count;
    }

    public boolean isEmpty() {
        return count == 0;
    }

    /** True when every cell is present (no presence mask is stored). */
    public boolean isDense() {
        return presence == null;
    }

    /** Current index width in bits: 0 (uniform), 1, 2, 4, 8 or 16. */
    public int bits() {
        return bits;
    }

    /** Palette entries, including entries no longer used until {@link #compact()}. */
    public int paletteSize() {
        return paletteSize;
    }

    /**
     * True when no handle is in both palettes (unused entries included), so no cell can hold the same handle in both
     * buffers.
     */
    public boolean paletteDisjoint(SectionBuffer other) {
        Objects.requireNonNull(other);
        SectionBuffer small = paletteSize <= other.paletteSize ? this : other;
        SectionBuffer large = small == this ? other : this;
        for (int p = 0; p < small.paletteSize; p++) {
            if (large.lookup(small.palette[p]) >= 0) return false;
        }
        return true;
    }

    /** Approximate heap footprint, including tiles. */
    public long estimatedBytes() {
        long bytes = 64 + 16 + 4L * palette.length;
        if (paletteLookup != null) bytes += 64 + 20L * paletteLookup.size();
        if (data != null) bytes += 16 + 8L * data.length;
        if (presence != null) bytes += 16 + 8L * presence.length;
        if (tiles != null) {
            bytes += 64 + 16L * tiles.size();
            for (BlockEntityData tile : tiles.values()) bytes += tile.estimatedBytes();
        }
        return bytes;
    }

    /**
     * An equivalent buffer with only the palette entries present cells use (in first-use order), the
     * smallest index width, no presence mask if every cell is present, and the same tiles. This buffer
     * is unchanged.
     */
    public SectionBuffer compact() {
        SectionBuffer out = new SectionBuffer();
        forEachPresent(i -> out.set(i, get(i)));
        if (tiles != null) out.tiles = new Short2ObjectOpenHashMap<>(tiles);
        return out;
    }

    /** A deep copy (tiles are shared; {@link BlockEntityData} is immutable). */
    public SectionBuffer copy() {
        SectionBuffer out = new SectionBuffer();
        out.palette = palette.clone();
        out.paletteSize = paletteSize;
        if (paletteLookup != null) out.buildLookup();
        out.bits = bits;
        out.data = data == null ? null : data.clone();
        out.presence = presence == null ? null : presence.clone();
        out.count = count;
        out.tiles = tiles == null ? null : new Short2ObjectOpenHashMap<>(tiles);
        return out;
    }

    @Override
    public String toString() {
        return "SectionBuffer[present=" + count + ", palette=" + paletteSize + ", bits=" + bits
                + ", tiles=" + tileCount() + "]";
    }

    private boolean present(int i) {
        return presence == null || (presence[i >>> 6] & (1L << i)) != 0;
    }

    private void removeTile(int i) {
        if (tiles == null) return;
        tiles.remove((short) i);
        if (tiles.isEmpty()) tiles = null;
    }

    private int lookup(int h) {
        if (paletteLookup != null) return paletteLookup.get(h);
        for (int p = 0; p < paletteSize; p++) {
            if (palette[p] == h) return p;
        }
        return -1;
    }

    private int append(int h) {
        if (paletteSize == MAX_PALETTE) compactInPlace();
        if (paletteSize == palette.length) palette = Arrays.copyOf(palette, paletteSize * 2);
        int p = paletteSize++;
        palette[p] = h;
        if (paletteLookup != null) {
            paletteLookup.put(h, p);
        } else if (paletteSize > LINEAR_LOOKUP_MAX) {
            buildLookup();
        }
        int needed = bitsFor(paletteSize);
        if (needed > bits) repack(needed);
        return p;
    }

    private void compactInPlace() {
        SectionBuffer compacted = compact();
        palette = compacted.palette;
        paletteSize = compacted.paletteSize;
        paletteLookup = compacted.paletteLookup;
        bits = compacted.bits;
        data = compacted.data;
        presence = compacted.presence;
        count = compacted.count;
        tiles = compacted.tiles;
    }

    private void buildLookup() {
        paletteLookup = new Int2IntOpenHashMap(Math.max(32, paletteSize * 2));
        paletteLookup.defaultReturnValue(-1);
        for (int p = 0; p < paletteSize; p++) paletteLookup.put(palette[p], p);
    }

    static int bitsFor(int paletteSize) {
        if (paletteSize <= 1) return 0;
        if (paletteSize <= 2) return 1;
        if (paletteSize <= 4) return 2;
        if (paletteSize <= 16) return 4;
        if (paletteSize <= 256) return 8;
        return 16;
    }

    private void repack(int newBits) {
        long[] next = new long[SIZE * newBits / 64];
        // With bits == 0 every cell uses palette index 0, which is what a zeroed array already holds.
        if (bits != 0) {
            for (int i = 0; i < SIZE; i++) write(next, newBits, i, readIndex(i));
        }
        data = next;
        bits = newBits;
    }

    private int readIndex(int i) {
        int perLong = 64 / bits;
        int shift = (i % perLong) * bits;
        return (int) ((data[i / perLong] >>> shift) & ((1L << bits) - 1));
    }

    private void writeIndex(int i, int p) {
        write(data, bits, i, p);
    }

    private static void write(long[] data, int bits, int i, int p) {
        int perLong = 64 / bits;
        int word = i / perLong;
        int shift = (i % perLong) * bits;
        long mask = ((1L << bits) - 1) << shift;
        data[word] = (data[word] & ~mask) | (((long) p << shift) & mask);
    }

    private static void checkHandle(int h) {
        if (h < 0) throw new IllegalArgumentException("Negative state handle: " + h);
    }
}
