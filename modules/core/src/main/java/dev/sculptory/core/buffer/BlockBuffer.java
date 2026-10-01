package dev.sculptory.core.buffer;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.Arrays;
import java.util.Objects;

/**
 * A sparse set of block cells in world coordinates, stored as {@link SectionBuffer}s keyed by
 * {@link #key(int, int, int)}. Absent cells read as -1. Not thread-safe.
 */
public final class BlockBuffer {
    /** Section x/z range: 22-bit two's complement. */
    public static final int MIN_SECTION_XZ = -(1 << 21);
    public static final int MAX_SECTION_XZ = (1 << 21) - 1;
    /** Section y range: 20-bit two's complement. */
    public static final int MIN_SECTION_Y = -(1 << 19);
    public static final int MAX_SECTION_Y = (1 << 19) - 1;

    private final Long2ObjectOpenHashMap<SectionBuffer> sections = new Long2ObjectOpenHashMap<>();

    /**
     * Packs signed section coordinates into one long, with the same layout as vanilla
     * {@code ChunkSectionPos.asLong}: bits 63..42 hold sx (22-bit two's complement), bits 41..20 hold sz
     * (22 bits) and bits 19..0 hold sy (20 bits).
     *
     * @throws IllegalArgumentException if a coordinate is outside the packable range
     */
    public static long key(int sx, int sy, int sz) {
        if (sx < MIN_SECTION_XZ || sx > MAX_SECTION_XZ || sz < MIN_SECTION_XZ || sz > MAX_SECTION_XZ
                || sy < MIN_SECTION_Y || sy > MAX_SECTION_Y) {
            throw new IllegalArgumentException("Section out of range: " + sx + "," + sy + "," + sz);
        }
        return ((sx & 0x3FFFFFL) << 42) | ((sz & 0x3FFFFFL) << 20) | (sy & 0xFFFFFL);
    }

    /** Key of the section containing block (x, y, z). */
    public static long keyOfBlock(int x, int y, int z) {
        return key(x >> 4, y >> 4, z >> 4);
    }

    public static int keyX(long key) {
        return (int) (key >> 42);
    }

    public static int keyY(long key) {
        return (int) (key << 44 >> 44);
    }

    public static int keyZ(long key) {
        return (int) (key << 22 >> 42);
    }

    /** The handle at (x, y, z), or -1 if absent. */
    public int get(int x, int y, int z) {
        SectionBuffer section = sections.get(keyOfBlock(x, y, z));
        return section == null ? -1 : section.get(SectionBuffer.index(x & 15, y & 15, z & 15));
    }

    public boolean has(int x, int y, int z) {
        SectionBuffer section = sections.get(keyOfBlock(x, y, z));
        return section != null && section.has(SectionBuffer.index(x & 15, y & 15, z & 15));
    }

    public void set(int x, int y, int z, int h) {
        sectionOrCreate(keyOfBlock(x, y, z)).set(SectionBuffer.index(x & 15, y & 15, z & 15), h);
    }

    /** Marks a cell absent (dropping its tile). Empty sections are kept until {@link #compact()}. */
    public void clear(int x, int y, int z) {
        SectionBuffer section = sections.get(keyOfBlock(x, y, z));
        if (section != null) section.clear(SectionBuffer.index(x & 15, y & 15, z & 15));
    }

    public BlockEntityData tile(int x, int y, int z) {
        SectionBuffer section = sections.get(keyOfBlock(x, y, z));
        return section == null ? null : section.tile(SectionBuffer.index(x & 15, y & 15, z & 15));
    }

    /** Sets or (with {@code null}) removes a tile; the cell must be present to set one. */
    public void setTile(int x, int y, int z, BlockEntityData d) {
        SectionBuffer section = d == null ? sections.get(keyOfBlock(x, y, z)) : sectionOrCreate(keyOfBlock(x, y, z));
        if (section != null) section.setTile(SectionBuffer.index(x & 15, y & 15, z & 15), d);
    }

    /** The section at {@code key}, or {@code null}. */
    public SectionBuffer section(long key) {
        return sections.get(key);
    }

    public SectionBuffer sectionOrCreate(long key) {
        SectionBuffer section = sections.get(key);
        if (section == null) {
            section = new SectionBuffer();
            sections.put(key, section);
        }
        return section;
    }

    /** Stores {@code section} at {@code key}, replacing any existing section. */
    public void putSection(long key, SectionBuffer section) {
        sections.put(key, Objects.requireNonNull(section));
    }

    /** Removes and returns the section at {@code key}, or {@code null}. */
    public SectionBuffer removeSection(long key) {
        return sections.remove(key);
    }

    /** Unmodifiable live view of the section keys. Iteration order is unspecified; see {@link #sortedKeys()}. */
    public LongSet keys() {
        return LongSets.unmodifiable(sections.keySet());
    }

    /** Section keys in ascending numeric order, for deterministic iteration. */
    public long[] sortedKeys() {
        long[] keys = sections.keySet().toLongArray();
        Arrays.sort(keys);
        return keys;
    }

    public int sectionCount() {
        return sections.size();
    }

    /** Exact bounds of the present cells, or {@code null} if there are none. */
    public Box bounds() {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        int[] local = new int[6];
        for (Long2ObjectMap.Entry<SectionBuffer> entry : sections.long2ObjectEntrySet()) {
            SectionBuffer section = entry.getValue();
            if (section.isEmpty()) continue;
            if (section.isDense()) {
                local[0] = 0; local[1] = 0; local[2] = 0;
                local[3] = 15; local[4] = 15; local[5] = 15;
            } else {
                Arrays.fill(local, 0, 3, 16);
                Arrays.fill(local, 3, 6, -1);
                section.forEachPresent(i -> {
                    int x = SectionBuffer.localX(i), y = SectionBuffer.localY(i), z = SectionBuffer.localZ(i);
                    local[0] = Math.min(local[0], x);
                    local[1] = Math.min(local[1], y);
                    local[2] = Math.min(local[2], z);
                    local[3] = Math.max(local[3], x);
                    local[4] = Math.max(local[4], y);
                    local[5] = Math.max(local[5], z);
                });
            }
            long key = entry.getLongKey();
            int ox = keyX(key) << 4, oy = keyY(key) << 4, oz = keyZ(key) << 4;
            minX = Math.min(minX, ox + local[0]);
            minY = Math.min(minY, oy + local[1]);
            minZ = Math.min(minZ, oz + local[2]);
            maxX = Math.max(maxX, ox + local[3]);
            maxY = Math.max(maxY, oy + local[4]);
            maxZ = Math.max(maxZ, oz + local[5]);
        }
        if (minX == Integer.MAX_VALUE) return null;
        return new Box(new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ));
    }

    /** Number of present cells. */
    public long cellCount() {
        long cells = 0;
        for (SectionBuffer section : sections.values()) cells += section.presentCount();
        return cells;
    }

    public boolean isEmpty() {
        for (SectionBuffer section : sections.values()) {
            if (!section.isEmpty()) return false;
        }
        return true;
    }

    /** Approximate heap footprint, including tiles. */
    public long estimatedBytes() {
        long bytes = 64 + 24L * sections.size();
        for (SectionBuffer section : sections.values()) bytes += section.estimatedBytes();
        return bytes;
    }

    /** A copy holding {@link SectionBuffer#compact() compacted} copies of the non-empty sections. */
    public BlockBuffer compact() {
        BlockBuffer out = new BlockBuffer();
        for (Long2ObjectMap.Entry<SectionBuffer> entry : sections.long2ObjectEntrySet()) {
            if (!entry.getValue().isEmpty()) out.sections.put(entry.getLongKey(), entry.getValue().compact());
        }
        return out;
    }

    @Override
    public String toString() {
        return "BlockBuffer[sections=" + sections.size() + ", cells=" + cellCount() + "]";
    }
}
