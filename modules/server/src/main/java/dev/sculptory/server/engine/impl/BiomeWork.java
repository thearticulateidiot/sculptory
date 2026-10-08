package dev.sculptory.server.engine.impl;

import dev.sculptory.server.platform.WorldBiomes;
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

    /** What biome work may use and what it counts, for one job. */
    final class Context {
        final WorldBiomes biomes;
        private final PermitSource permits;
        private final RecordSink records;
        private final EntityWork.InWorld inWorld;
        private final Object2IntOpenHashMap<String> handles = new Object2IntOpenHashMap<>();
        /** Per centre column ({@link ColumnPlan#pack} of x, z): whether the player may change it. */
        private final it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap allowed =
                new it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap();
        /** Quarts whose biome changed. */
        long changed;
        /** Quarts left alone because their column may not be changed (protection, the world border). */
        long protectedQuarts;
        /** Quarts kept because they changed since the step (undo, redo), or whose biome the world does not know. */
        long conflicts;
        /** Quarts visited (written or not): the work's progress. */
        long visited;

        Context(WorldBiomes biomes, PermitSource permits, RecordSink records, EntityWork.InWorld inWorld) {
            this.biomes = Objects.requireNonNull(biomes);
            this.permits = Objects.requireNonNull(permits);
            this.records = Objects.requireNonNull(records);
            this.inWorld = Objects.requireNonNull(inWorld);
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
         * Whether the job may change quart {@code (qx, qy, qz)}: its centre column ({@code 4q + 2}) may be changed by the
         * player and lies inside the world border, and its centre cell inside the build height.
         */
        boolean mayChange(int qx, int qy, int qz) {
            int x = (qx << 2) + 2, y = (qy << 2) + 2, z = (qz << 2) + 2;
            long column = ColumnPlan.pack(x, z);
            boolean may;
            if (allowed.containsKey(column)) {
                may = allowed.get(column);
            } else {
                may = permits.mayChangeColumn(x, z);
                allowed.put(column, may);
            }
            return may && inWorld.contains(x, y, z);
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
