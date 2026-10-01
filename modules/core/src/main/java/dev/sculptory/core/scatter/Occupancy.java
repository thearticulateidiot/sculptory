package dev.sculptory.core.scatter;

import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.SectionBuffer;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/** The cells claimed by accepted placements: a 4096-bit set per touched 16³ section, keyed by section. */
final class Occupancy {
    private final Long2ObjectOpenHashMap<long[]> sections = new Long2ObjectOpenHashMap<>();
    private long lastKey;
    private long[] last;

    /** Coordinates must lie within the {@link BlockBuffer#key} range. */
    boolean occupied(int x, int y, int z) {
        long[] bits = bits(x, y, z, false);
        if (bits == null) return false;
        int i = SectionBuffer.index(x & 15, y & 15, z & 15);
        return (bits[i >>> 6] & (1L << i)) != 0;
    }

    void set(int x, int y, int z) {
        int i = SectionBuffer.index(x & 15, y & 15, z & 15);
        bits(x, y, z, true)[i >>> 6] |= 1L << i;
    }

    private long[] bits(int x, int y, int z, boolean create) {
        long key = BlockBuffer.keyOfBlock(x, y, z);
        if (last != null && key == lastKey) return last;
        long[] bits = sections.get(key);
        if (bits == null) {
            if (!create) return null;
            bits = new long[SectionBuffer.SIZE / 64];
            sections.put(key, bits);
        }
        lastKey = key;
        last = bits;
        return bits;
    }
}
