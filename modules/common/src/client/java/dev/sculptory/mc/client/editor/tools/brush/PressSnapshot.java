package dev.sculptory.mc.client.editor.tools.brush;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.core.brush.HeightmapReach;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.world.WorldReader;
import dev.sculptory.mc.client.editor.tool.RayOverlay;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The world as it was before one press of a stamping brush (the Shape brush, the Heightmap brush) wrote into it, for
 * the press's cursor ray: just before each dab goes out, {@link #cover} (or {@link #coverHeightmap}) copies every 16³
 * section the dab and its copies can write that the press has not copied yet, and the ray then sees those sections as
 * copied ({@link RayOverlay}). So the press never aims at what it placed or raised, predicted or written by the server,
 * and the other cells of those sections stay as they were when first copied.
 *
 * <p>A section of loaded chunks within the build height is copied whole ({@link WorldReader#copySection}: a uniform
 * section is not read cell by cell), at most {@value #MAX_BYTES} bytes per press as the copies estimate themselves
 * ({@link SectionBuffer#estimatedBytes}: about 4,000 sections of ordinary terrain, many more of air or solid rock); past
 * that the ray sees the further sections as they are. A Heightmap dab's sections are copied nearest the dab first, so
 * those are the far ones. Client thread only.
 */
final class PressSnapshot implements RayOverlay {
    /** The most one press keeps, in {@link SectionBuffer#estimatedBytes}. */
    static final long MAX_BYTES = 8L << 20;

    private final Long2ObjectOpenHashMap<SectionBuffer> sections = new Long2ObjectOpenHashMap<>();
    private final long maxBytes;
    private long bytes;
    /** The section asked for last (a ray asks along a run of cells), or {@link Long#MIN_VALUE}. */
    private long lastKey = Long.MIN_VALUE;
    private SectionBuffer last;

    PressSnapshot() {
        this(MAX_BYTES);
    }

    /** A snapshot that keeps at most {@code maxBytes} (tests). */
    PressSnapshot(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    /** Copies the sections {@code box} touches that are not copied yet, of loaded chunks within the build height. */
    void cover(WorldReader world, Box box) {
        int minY = Math.max(box.min().y(), world.bottomY());
        int maxY = Math.min(box.max().y(), world.topYExclusive() - 1);
        if (minY > maxY) return;
        forget();
        for (int sx = box.min().x() >> 4; sx <= box.max().x() >> 4; sx++) {
            for (int sz = box.min().z() >> 4; sz <= box.max().z() >> 4; sz++) {
                if (!world.isLoaded(sx, sz)) continue;
                for (int sy = minY >> 4; sy <= maxY >> 4; sy++) {
                    long key = key(sx, sy, sz);
                    if (sections.containsKey(key)) continue;
                    if (bytes >= maxBytes) return;
                    SectionBuffer copy = new SectionBuffer();
                    world.copySection(sx, sy, sz, copy);
                    sections.put(key, copy);
                    bytes += copy.estimatedBytes();
                }
            }
        }
    }

    /**
     * Copies the sections a Heightmap dab of {@code spec} and its copies can write ({@link HeightmapReach}: near the
     * surface of each column of their stamps, not the kernel's whole scan window), section columns nearest the dab
     * first. A dab or copy beyond the world copies nothing (the server refuses it).
     */
    void coverHeightmap(WorldReader world, BrushSpec spec, Dab dab) {
        // Per section column (x and z in the key, y 0): the lowest and highest cell that may be written.
        Long2ObjectOpenHashMap<int[]> columns = new Long2ObjectOpenHashMap<>();
        try {
            HeightmapReach.forEach(spec, dab, world, (x, z, minY, maxY) -> {
                long key = key(x >> 4, 0, z >> 4);
                int[] range = columns.get(key);
                if (range == null) {
                    columns.put(key, new int[] {x >> 4, z >> 4, minY, maxY});
                } else {
                    range[2] = Math.min(range[2], minY);
                    range[3] = Math.max(range[3], maxY);
                }
            });
        } catch (IllegalArgumentException outOfRange) {
            return;
        }
        int sx = dab.blockX() >> 4;
        int sz = dab.blockZ() >> 4;
        List<int[]> nearestFirst = new ArrayList<>(columns.values());
        nearestFirst.sort(Comparator.comparingLong(
                c -> (long) (c[0] - sx) * (c[0] - sx) + (long) (c[1] - sz) * (c[1] - sz)));
        for (int[] c : nearestFirst) {
            if (bytes >= maxBytes) return;
            cover(world, new Box(new BlockPos(c[0] << 4, c[2], c[1] << 4), new BlockPos((c[0] << 4) + 15, c[3],
                    (c[1] << 4) + 15)));
        }
    }

    /** The state the cell had when its section was copied, or {@link #WORLD} outside the copied sections. */
    @Override
    public int stateAt(int x, int y, int z) {
        long key = key(x >> 4, y >> 4, z >> 4);
        if (key != lastKey) {
            lastKey = key;
            last = sections.get(key);
        }
        if (last == null) return WORLD;
        int state = last.get(SectionBuffer.index(x & 15, y & 15, z & 15));
        return state < 0 ? WORLD : state;
    }

    /** Sections copied. */
    int size() {
        return sections.size();
    }

    /** What the copies take, in {@link SectionBuffer#estimatedBytes}. */
    long bytes() {
        return bytes;
    }

    boolean isEmpty() {
        return sections.isEmpty();
    }

    /** The press ended: nothing is kept. */
    void clear() {
        sections.clear();
        sections.trim();
        bytes = 0;
        forget();
    }

    private void forget() {
        lastKey = Long.MIN_VALUE;
        last = null;
    }

    /** A section's key: x and z in 22 bits each (the world's ±30,000,000 blocks), y in 20. */
    private static long key(int sx, int sy, int sz) {
        return ((long) sx & 0x3FFFFFL) << 42 | ((long) sz & 0x3FFFFFL) << 20 | (sy & 0xFFFFFL);
    }
}
