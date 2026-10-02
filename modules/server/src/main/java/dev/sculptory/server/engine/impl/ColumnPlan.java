package dev.sculptory.server.engine.impl;

import dev.sculptory.core.buffer.BlockBuffer;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;

/**
 * The chunk columns a list of section keys touches: distinct columns in first-use order, the column of each
 * section, and the last section index that uses each column (when its chunk ticket can be released).
 * Columns are packed like {@code ChunkPos.toLong}.
 */
public final class ColumnPlan {
    private final long[] columns;
    private final int[] columnOfSection;
    private final int[] lastUse;
    /** Packed column to its index in {@link #columns}; -1 for a column not in the plan. */
    private final Long2IntOpenHashMap index;

    private ColumnPlan(long[] columns, int[] columnOfSection, int[] lastUse, Long2IntOpenHashMap index) {
        this.columns = columns;
        this.columnOfSection = columnOfSection;
        this.lastUse = lastUse;
        this.index = index;
    }

    public static ColumnPlan of(long[] sectionKeys) {
        Long2IntOpenHashMap index = new Long2IntOpenHashMap();
        index.defaultReturnValue(-1);
        LongArrayList columns = new LongArrayList();
        IntArrayList lastUse = new IntArrayList();
        int[] columnOfSection = new int[sectionKeys.length];
        for (int s = 0; s < sectionKeys.length; s++) {
            long key = sectionKeys[s];
            long column = pack(BlockBuffer.keyX(key), BlockBuffer.keyZ(key));
            int c = index.get(column);
            if (c < 0) {
                c = columns.size();
                index.put(column, c);
                columns.add(column);
                lastUse.add(s);
            } else {
                lastUse.set(c, s);
            }
            columnOfSection[s] = c;
        }
        return new ColumnPlan(columns.toLongArray(), columnOfSection, lastUse.toIntArray(), index);
    }

    /** Same layout as {@code ChunkPos.toLong(cx, cz)}. */
    public static long pack(int cx, int cz) {
        return (cx & 0xFFFFFFFFL) | ((cz & 0xFFFFFFFFL) << 32);
    }

    public static int unpackX(long column) {
        return (int) column;
    }

    public static int unpackZ(long column) {
        return (int) (column >>> 32);
    }

    public int sectionCount() {
        return columnOfSection.length;
    }

    public int columnCount() {
        return columns.length;
    }

    public long column(int c) {
        return columns[c];
    }

    /** The index of packed column {@code column}, or -1 when no section of the plan is in it. */
    public int indexOf(long column) {
        return index.get(column);
    }

    public int columnOfSection(int s) {
        return columnOfSection[s];
    }

    public int lastUse(int c) {
        return lastUse[c];
    }

    /** Distinct packed columns in first-use order. */
    public long[] columns() {
        return columns.clone();
    }
}
