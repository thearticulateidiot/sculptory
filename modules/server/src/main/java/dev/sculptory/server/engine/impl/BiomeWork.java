package dev.sculptory.server.engine.impl;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.server.engine.ChunkPermit;
import dev.sculptory.server.platform.WorldBiomes;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.Objects;

/**
 * A job's biome work (protocol 6), done by {@link BulkJob} after its blocks and before its entities' placement, a chunk
 * column at a time once the chunk is loaded: a biome fill's quarts, a paste's, move's or stack's carried biomes, or an
 * undo's or redo's. A column holds at most 16 quart columns of the build height, so a column is one step. Columns are
 * packed like {@code ChunkPos.toLong} ({@link ColumnPlan#pack}). Server thread only.
 *
 * <p>The work reaches the world only through its {@link Context}, made from the platform's {@link WorldBiomes}.
 */
public interface BiomeWork {
    /**
     * Every column the work visits, in order (known at admission): the executor counts them toward the job's column cap
     * and applies the unloaded-chunks policy to them as to the blocks' columns.
     */
    long[] columns();

    /** Works on one column, whose chunk is loaded. */
    void apply(long column, Context ctx);

    /**
     * The columns the work reads before it writes any (a move's or stack's source; known at admission, counted and
     * loaded like {@link #columns()}). The stage visits them all first, each ticketed only while it is read, then the
     * {@link #columns()}. None by default.
     */
    default long[] readColumns() {
        return new long[0];
    }

    /** Reads one of the {@link #readColumns()}, whose chunk is loaded. */
    default void read(long column, Context ctx) {}

    /** How many quarts the work may write at most (its progress and its cost). */
    long quarts();

    /**
     * {@code box} widened to whole quarts (x, y and z from {@code min & ~3} to {@code max | 3}), its heights clipped to
     * the build height {@code [bottomY, topYExclusive)}: every quart a job over {@code box} may touch (a move or paste
     * visits each quart its box touches) lies inside, centre included, so permits asked for these bounds answer for
     * each quart's centre. The build height is whole quarts, so the clip keeps that.
     */
    static Box quarts(Box box, int bottomY, int topYExclusive) {
        int y0 = Math.max(bottomY, box.min().y() & ~3), y1 = Math.min(topYExclusive - 1, box.max().y() | 3);
        if (y0 > y1) {
            // Nothing of the box is inside the build height: an empty answer is never asked about.
            y0 = Math.max(bottomY, Math.min(topYExclusive - 1, box.min().y()));
            y1 = y0;
        }
        return new Box(new BlockPos(box.min().x() & ~3, y0, box.min().z() & ~3),
                new BlockPos(box.max().x() | 3, y1, box.max().z() | 3));
    }

    /** What biome work may use and what it counts, for one job. */
    final class Context {
        final WorldBiomes biomes;
        private final PermitSource permits;
        private final RecordSink records;
        private final EntityWork.InWorld inWorld;
        private final Object2IntOpenHashMap<String> handles = new Object2IntOpenHashMap<>();
        /**
         * The job's bounds widened to whole quarts and clipped to the build height ({@link #quarts}): every quart the work
         * may touch has its centre inside, so the permits are asked for these bounds.
         */
        private final Box bounds;
        /**
         * Per centre column ({@link ColumnPlan#pack} of x, z): one bit per quart of {@link #bounds}' heights, from its
         * lowest, set where the quart's centre may be changed. The column's permit ({@link PermitSource#column}) is asked
         * once and dropped: a per-cell permit is far larger than these bits.
         */
        private final Long2ObjectOpenHashMap<long[]> permitted = new Long2ObjectOpenHashMap<>();
        /** Quarts whose biome changed. */
        long changed;
        /** Quarts left alone because their centre cell may not be changed (protection, the world border). */
        long protectedQuarts;
        /** Quarts kept because they changed since the step (undo, redo), or whose biome the world does not know. */
        long conflicts;
        /** Quarts visited (written or not): the work's progress. */
        long visited;

        /** @param bounds the job's bounds widened to whole quarts ({@link #quarts}) */
        Context(WorldBiomes biomes, PermitSource permits, RecordSink records, EntityWork.InWorld inWorld, Box bounds) {
            this.biomes = Objects.requireNonNull(biomes);
            this.permits = Objects.requireNonNull(permits);
            this.records = Objects.requireNonNull(records);
            this.inWorld = Objects.requireNonNull(inWorld);
            this.bounds = Objects.requireNonNull(bounds);
            handles.defaultReturnValue(Integer.MIN_VALUE);
        }

        /** The handle of biome {@code id}, or -1 when the world has no such biome (asked once per id). */
        int handle(String id) {
            int handle = handles.getInt(id);
            if (handle == Integer.MIN_VALUE) {
                handle = biomes.handle(id);
                handles.put(id, handle);
            }
            return handle;
        }

        /**
         * Whether the job may change quart {@code (qx, qy, qz)}: its centre cell ({@code 4q + 2}) may be changed by the
         * player (the permit of its column, asked once per column) and lies inside the world border and the build height.
         */
        boolean mayChange(int qx, int qy, int qz) {
            int x = (qx << 2) + 2, y = (qy << 2) + 2, z = (qz << 2) + 2;
            PermitBounds.check(bounds, null, x, y, z, "biome work");
            int lowest = bounds.min().y() >> 2, highest = bounds.max().y() >> 2;
            // A quart outside the bounds (only when the check is off) has no answer: left alone.
            if (qy < lowest || qy > highest) return false;
            long column = ColumnPlan.pack(x, z);
            long[] allowed = permitted.get(column);
            if (allowed == null) {
                int minY = bounds.min().y(), maxY = bounds.max().y();
                ChunkPermit permit = permits.column(x, z, minY, maxY);
                if (permit == null) permit = ChunkPermit.DENY;
                Box asked = new Box(new BlockPos(x, minY, z), new BlockPos(x, maxY, z));
                allowed = new long[(highest - lowest + 64) >> 6];
                for (int q = lowest; q <= highest; q++) {
                    int centre = (q << 2) + 2;
                    PermitBounds.check(asked, permit, x, centre, z, "biome work");
                    if (permit.allows(x, centre, z)) allowed[(q - lowest) >> 6] |= 1L << (q - lowest);
                }
                permitted.put(column, allowed);
            }
            int bit = qy - lowest;
            return (allowed[bit >> 6] & (1L << bit)) != 0 && inWorld.contains(x, y, z);
        }

        /**
         * Sets quart {@code (qx, qy, qz)} to {@code id} where the job may change it, recording the change; a biome the
         * world does not know is counted with the conflicts and nothing is written.
         *
         * @return whether the quart's biome changed
         */
        boolean write(int qx, int qy, int qz, String id) {
            visited++;
            if (!mayChange(qx, qy, qz)) {
                protectedQuarts++;
                return false;
            }
            int handle = handle(id);
            if (handle < 0) {
                conflicts++;
                return false;
            }
            int before = biomes.set(qx, qy, qz, handle);
            if (before < 0) {
                // The quart holds a biome the registry does not list: left alone, kept and counted.
                conflicts++;
                return false;
            }
            if (before == handle) return false;
            records.biome(qx, qy, qz, biomes.id(before), id);
            changed++;
            return true;
        }
    }
}
