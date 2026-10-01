package dev.sculptory.core.generate;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.Objects;

/**
 * A snapshot of the ground of chosen columns ({@link SurfaceReader}), so a generator can run off the thread the
 * world belongs to: the client captures the footprint's columns on its thread, the kernel reads the map anywhere.
 * A column not captured reads as {@link SurfaceReader#UNLOADED}.
 */
public final class GroundMap implements SurfaceReader {
    private final Long2IntOpenHashMap heights = new Long2IntOpenHashMap();

    public GroundMap() {
        heights.defaultReturnValue(UNLOADED);
    }

    /** The key of column (x, z). */
    public static long column(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public static int columnX(long column) {
        return (int) (column >> 32);
    }

    public static int columnZ(long column) {
        return (int) column;
    }

    /** Records the ground of a column. */
    public void put(int x, int z, int ground) {
        heights.put(column(x, z), ground);
    }

    /** Captures {@code columns} (keys as {@link #column}) from {@code source}. */
    public GroundMap capture(SurfaceReader source, long[] columns) {
        Objects.requireNonNull(source);
        for (long column : columns) {
            int x = columnX(column), z = columnZ(column);
            heights.put(column, source.ground(x, z));
        }
        return this;
    }

    public int size() {
        return heights.size();
    }

    @Override
    public int ground(int x, int z) {
        return heights.get(column(x, z));
    }
}
