package dev.sculptory.core.edit;

import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.Regions;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.util.Objects;

/**
 * A region's cells with y in {@code [minY, maxY]}, section by section as {@link Regions#rows rows}, computed when first
 * asked for and kept for the {@value #CAPACITY} sections used last (a program reads a section's neighbours, and its
 * write order visits neighbouring columns close together). Sections outside the region's bounds answer
 * {@link #EMPTY} at once, without taking a place. The arrays it returns are shared: never change them. Not
 * thread-safe; a program computes one section at a time.
 */
final class RegionRows {
    /** Rows of a section without cells. */
    static final int[] EMPTY = new int[Regions.ROWS];
    /** Sections kept: 512 KiB of rows. */
    static final int CAPACITY = 512;

    private final Region region;
    private final int minY;
    private final int maxY;
    /** The sections the region's bounds reach: {minSx, minSy, minSz, maxSx, maxSy, maxSz}. */
    private final int[] reach;
    private final Long2ObjectLinkedOpenHashMap<int[]> cache = new Long2ObjectLinkedOpenHashMap<>();

    RegionRows(Region region, int minY, int maxY) {
        this.region = Objects.requireNonNull(region);
        this.minY = minY;
        this.maxY = maxY;
        Box bounds = region.bounds();
        this.reach = new int[] {bounds.min().x() >> 4, Math.max(bounds.min().y(), minY) >> 4, bounds.min().z() >> 4,
                bounds.max().x() >> 4, Math.min(bounds.max().y(), maxY) >> 4, bounds.max().z() >> 4};
    }

    Region region() {
        return region;
    }

    /** The rows of section (sx, sy, sz); {@link #EMPTY} outside the region's bounds. */
    int[] rows(int sx, int sy, int sz) {
        if (sx < reach[0] || sy < reach[1] || sz < reach[2] || sx > reach[3] || sy > reach[4] || sz > reach[5]) {
            return EMPTY;
        }
        return rows(BlockBuffer.key(sx, sy, sz));
    }

    int[] rows(long key) {
        int[] rows = cache.getAndMoveToLast(key);
        if (rows != null) return rows;
        rows = new int[Regions.ROWS];
        if (Regions.rows(region, key, minY, maxY, rows) == 0) rows = EMPTY;
        if (cache.size() >= CAPACITY) cache.removeFirst();
        cache.putAndMoveToLast(key, rows);
        return rows;
    }
}
