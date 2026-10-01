package dev.sculptory.fabric.client.editor.tools.scatter;

import dev.sculptory.core.world.WorldReader;
import dev.sculptory.fabric.client.editor.brush.ReaderTerrainProbe;
import dev.sculptory.fabric.client.editor.world.SurfaceSampler;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

/**
 * Surface heights of the columns the Scatter tool draws over (the painted area's tint and outline, the cursor ring),
 * with the brush cursor's notion of ground ({@link ReaderTerrainProbe}, {@link SurfaceSampler#scanColumn}). Scans
 * start at the world's height hint, so a column costs a few block reads.
 *
 * <p>Columns are sampled on demand within a per-frame budget ({@link #beginFrame}); one not sampled yet reads
 * {@link #UNKNOWN}. When the world's change stamp moves, cached heights are still returned but count as stale and are
 * sampled again as the budget allows, so the tint catches up with edits without a hitch. Client thread only.
 */
final class SurfaceHeights {
    /** Not sampled yet (and no budget left this frame). */
    static final int UNKNOWN = Integer.MIN_VALUE + 1;
    /** No ground in the scan window, or the chunk is not loaded. */
    static final int NONE = SurfaceSampler.NONE;
    /** How far below the height hint a column is scanned. */
    static final int SCAN_DEPTH = 96;
    /** Columns sampled per frame. */
    static final int FRAME_BUDGET = 4096;
    /** Cached columns kept at most; beyond this the cache starts again. */
    static final int MAX_CACHED = 1 << 20;

    /** Column key → (generation << 32) | (height as unsigned int). */
    private final Long2LongOpenHashMap cache = new Long2LongOpenHashMap();
    private WorldReader world;
    private ReaderTerrainProbe probe;
    private long stamp = Long.MIN_VALUE;
    private int generation;
    private int budget;

    /** Starts a frame over {@code world}: resets the sampling budget and notices world changes. */
    void beginFrame(WorldReader reader, long changeStamp, int frameBudget) {
        if (reader != world) {
            world = reader;
            probe = reader == null ? null : new ReaderTerrainProbe(reader);
            cache.clear();
        }
        if (changeStamp != stamp) {
            stamp = changeStamp;
            generation++;
        }
        if (cache.size() > MAX_CACHED) cache.clear();
        budget = frameBudget;
    }

    /** The y of column (x, z)'s ground (its top face is at y + 1), {@link #NONE}, or {@link #UNKNOWN}. */
    int height(int x, int z) {
        if (world == null) return UNKNOWN;
        long key = (long) x << 32 | (z & 0xFFFFFFFFL);
        boolean known = cache.containsKey(key);
        long packed = known ? cache.get(key) : 0;
        boolean fresh = known && (int) (packed >>> 32) == generation;
        if (fresh) return (int) packed;
        if (budget <= 0) return known ? (int) packed : UNKNOWN;
        budget--;
        int height = sample(x, z);
        cache.put(key, (long) generation << 32 | (height & 0xFFFFFFFFL));
        return height;
    }

    private int sample(int x, int z) {
        if (!world.isLoaded(x >> 4, z >> 4)) return NONE;
        int hint = world.heightHint(x, z);
        int top = Math.min(hint, world.topYExclusive()) - 1;
        // Without a hint (the world's top), the whole column is scanned.
        int bottom = hint >= world.topYExclusive() ? world.bottomY() : Math.max(world.bottomY(), top - SCAN_DEPTH);
        if (top < bottom) return NONE;
        return SurfaceSampler.scanColumn(probe, x, z, top, bottom);
    }

    /** Forgets everything (e.g. when the tool is put away). */
    void clear() {
        cache.clear();
        world = null;
        probe = null;
    }
}
