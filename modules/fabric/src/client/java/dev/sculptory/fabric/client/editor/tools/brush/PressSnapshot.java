package dev.sculptory.fabric.client.editor.tools.brush;

import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.world.WorldReader;
import dev.sculptory.fabric.client.editor.tool.RayOverlay;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * The world as it was before one press of the Shape brush wrote into it, for the press's cursor ray: just before each
 * dab goes out, {@link #cover} copies every 16³ section its shape and copies can write that the press has not copied
 * yet, and the ray then sees those sections as copied ({@link RayOverlay}). So the press never aims at a shape it
 * placed, predicted or written by the server, and the other cells of those sections stay as they were when first
 * copied.
 *
 * <p>A section of loaded chunks within the build height is copied whole ({@link WorldReader#copySection}: a uniform
 * section is not read cell by cell), at most {@value #MAX_SECTIONS} per press (about 8 MB of ordinary terrain); past
 * that the ray sees the further sections as they are. Client thread only.
 */
final class PressSnapshot implements RayOverlay {
    /** The most sections one press keeps. */
    static final int MAX_SECTIONS = 4096;

    private final Long2ObjectOpenHashMap<SectionBuffer> sections = new Long2ObjectOpenHashMap<>();
    /** The section asked for last (a ray asks along a run of cells), or {@link Long#MIN_VALUE}. */
    private long lastKey = Long.MIN_VALUE;
    private SectionBuffer last;

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
                    if (sections.size() >= MAX_SECTIONS) return;
                    SectionBuffer copy = new SectionBuffer();
                    world.copySection(sx, sy, sz, copy);
                    sections.put(key, copy);
                }
            }
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

    boolean isEmpty() {
        return sections.isEmpty();
    }

    /** The press ended: nothing is kept. */
    void clear() {
        sections.clear();
        sections.trim();
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
