package dev.sculptory.server.engine.impl;

import dev.sculptory.core.Box;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.Regions;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import java.util.Arrays;

/**
 * The chunk columns whose entities can belong to a region, packed like {@link ColumnPlan#pack}. An entity belongs to a
 * block (a hanging entity to its attachment block, any other to the block holding its position), and its position
 * lies within {@value #MARGIN} blocks of that block, so looking {@value #MARGIN} blocks around a region finds them all.
 */
public final class EntityColumns {
    /** How far (blocks) an entity's position may lie from the block it belongs to (a large painting's centre). */
    public static final int MARGIN = 2;

    private EntityColumns() {}

    /**
     * The chunk columns whose entities can belong to a region of these bounds (their chunks hold positions within
     * {@link #MARGIN} of the bounds), x fastest.
     */
    public static long[] of(Box bounds) {
        int cx0 = (bounds.min().x() - MARGIN) >> 4, cx1 = (bounds.max().x() + MARGIN) >> 4;
        int cz0 = (bounds.min().z() - MARGIN) >> 4, cz1 = (bounds.max().z() + MARGIN) >> 4;
        long count = ((long) cx1 - cx0 + 1) * ((long) cz1 - cz0 + 1);
        if (count > Integer.MAX_VALUE - 16) throw new IllegalArgumentException("Too many chunk columns");
        long[] out = new long[(int) count];
        int n = 0;
        for (int cz = cz0; cz <= cz1; cz++) {
            for (int cx = cx0; cx <= cx1; cx++) out[n++] = ColumnPlan.pack(cx, cz);
        }
        return out;
    }

    /**
     * The chunk columns whose entities can belong to {@code region} (cells with y in {@code [minY, maxY]}): for a box,
     * {@link #of(Box) every column of its bounds} with the margin; for a shape or a cell set, only the chunks holding its
     * cells, and a neighbouring chunk only where a cell lies within {@link #MARGIN} blocks of the edge between them (an
     * entity belonging to that cell may be stored there). A sparse selection spanning unloaded chunks inside its bounds
     * then needs only its own chunks loaded.
     *
     * @throws IllegalStateException for an unresolved {@code Region.Uploaded}
     */
    public static long[] of(Region region, int minY, int maxY) {
        if (region instanceof Region.Cuboid cuboid) return of(cuboid.box()); // columns only: heights do not matter
        LongLinkedOpenHashSet out = new LongLinkedOpenHashSet();
        var cells = Regions.columns(region, minY, maxY);
        long[] chunks = cells.keySet().toLongArray();
        Arrays.sort(chunks);
        for (long chunk : chunks) {
            int cx = Regions.columnX(chunk);
            int cz = Regions.columnZ(chunk);
            long[] bits = cells.get(chunk);
            boolean west = false, east = false, north = false, south = false;
            boolean northWest = false, northEast = false, southWest = false, southEast = false;
            for (int i = 0; i < 256; i++) {
                if ((bits[i >>> 6] & (1L << i)) == 0) continue;
                int x = i & 15, z = i >>> 4;
                boolean w = x < MARGIN, e = x >= 16 - MARGIN, n = z < MARGIN, s = z >= 16 - MARGIN;
                west |= w;
                east |= e;
                north |= n;
                south |= s;
                northWest |= w && n;
                northEast |= e && n;
                southWest |= w && s;
                southEast |= e && s;
            }
            out.add(ColumnPlan.pack(cx, cz));
            if (west) out.add(ColumnPlan.pack(cx - 1, cz));
            if (east) out.add(ColumnPlan.pack(cx + 1, cz));
            if (north) out.add(ColumnPlan.pack(cx, cz - 1));
            if (south) out.add(ColumnPlan.pack(cx, cz + 1));
            if (northWest) out.add(ColumnPlan.pack(cx - 1, cz - 1));
            if (northEast) out.add(ColumnPlan.pack(cx + 1, cz - 1));
            if (southWest) out.add(ColumnPlan.pack(cx - 1, cz + 1));
            if (southEast) out.add(ColumnPlan.pack(cx + 1, cz + 1));
        }
        return out.toLongArray();
    }
}
