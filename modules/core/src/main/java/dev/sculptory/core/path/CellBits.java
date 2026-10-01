package dev.sculptory.core.path;

import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.edit.SectionOrder;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Arrays;

/**
 * A set of world cells as one bit per cell, 4096 bits per 16³ section: what the line kernels collect cells in before
 * they give them states. Holds at most {@code maxCells} cells; the
 * cell past that is refused. Not thread-safe.
 */
public final class CellBits {
    private static final int WORDS = 4096 / 64;

    private final Long2ObjectOpenHashMap<long[]> sections = new Long2ObjectOpenHashMap<>();
    private final long maxCells;
    private long count;
    private long lastKey = Long.MIN_VALUE;
    private long[] last;

    /** Thrown when a cell would be one over the cap; the set holds what it held before. */
    public static final class FullException extends RuntimeException {
        public FullException(long maxCells) {
            super("More than " + maxCells + " cells", null, false, false);
        }
    }

    /** Receives cells. */
    @FunctionalInterface
    public interface CellVisitor {
        void visit(int x, int y, int z);
    }

    public CellBits(long maxCells) {
        if (maxCells < 0) throw new IllegalArgumentException("Negative cell cap");
        this.maxCells = maxCells;
    }

    public long count() {
        return count;
    }

    public boolean isEmpty() {
        return count == 0;
    }

    public boolean contains(int x, int y, int z) {
        long[] words = words(x, y, z, false);
        if (words == null) return false;
        int i = index(x, y, z);
        return (words[i >>> 6] & (1L << i)) != 0;
    }

    /**
     * Adds a cell; returns whether it was new.
     *
     * @throws FullException when it would be one over the cap
     */
    public boolean add(int x, int y, int z) {
        int i = index(x, y, z);
        long[] words = words(x, y, z, true);
        long bit = 1L << i;
        if ((words[i >>> 6] & bit) != 0) return false;
        if (count + 1 > maxCells) throw new FullException(maxCells);
        words[i >>> 6] |= bit;
        count++;
        return true;
    }

    /**
     * Adds the cells from {@code x0} to {@code x1} (both included) of row (y, z).
     *
     * @throws FullException when they would pass the cap (the cells before the one over it stay added)
     */
    public void addRun(int y, int z, int x0, int x1) {
        for (int x = x0; x <= x1; ) {
            int sectionEnd = Math.min(x1, (x & ~15) + 15);
            long[] words = words(x, y, z, true);
            // The run's cells in this section lie in one 16-bit field of one word: bits (y&15, z&15) × 16 + x&15.
            int base = ((y & 15) << 8) | ((z & 15) << 4);
            int word = base >>> 6;
            int shift = base & 63;
            long mask = (((1L << (sectionEnd - x + 1)) - 1) << (x & 15)) << shift;
            long fresh = mask & ~words[word];
            int added = Long.bitCount(fresh);
            if (count + added > maxCells) {
                for (int xx = x; xx <= sectionEnd; xx++) add(xx, y, z);
            }
            words[word] |= fresh;
            count += added;
            x = sectionEnd + 1;
        }
    }

    /** Every cell, sections in {@link SectionOrder}, cells within a section by {@code (y << 8) | (z << 4) | x}. */
    public void forEach(CellVisitor visitor) {
        long[] keys = sections.keySet().toLongArray();
        Arrays.sort(keys);
        SectionOrder.sort(keys);
        for (long key : keys) {
            long[] words = sections.get(key);
            int ox = BlockBuffer.keyX(key) << 4, oy = BlockBuffer.keyY(key) << 4, oz = BlockBuffer.keyZ(key) << 4;
            for (int w = 0; w < WORDS; w++) {
                for (long bits = words[w]; bits != 0; bits &= bits - 1) {
                    int i = (w << 6) | Long.numberOfTrailingZeros(bits);
                    visitor.visit(ox + (i & 15), oy + (i >>> 8), oz + ((i >>> 4) & 15));
                }
            }
        }
    }

    private static int index(int x, int y, int z) {
        return ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
    }

    private long[] words(int x, int y, int z, boolean create) {
        long key = BlockBuffer.keyOfBlock(x, y, z);
        if (key == lastKey && last != null) return last;
        long[] words = sections.get(key);
        if (words == null) {
            if (!create) return null;
            words = new long[WORDS];
            sections.put(key, words);
        }
        lastKey = key;
        last = words;
        return words;
    }
}
