package dev.sculptory.server.engine.impl;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.biome.BiomeGrid;
import dev.sculptory.core.biome.BiomeMapping;
import dev.sculptory.core.biome.QuartSelection;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.history.BiomeChanges;
import dev.sculptory.core.history.BiomeHistory;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.Regions;
import dev.sculptory.core.transform.Orientation;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.engine.EditRejected;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** The {@link BiomeWork}s of the jobs that write biomes (protocol 6). */
final class BiomeJobs {
    private BiomeJobs() {}

    /** A biome fill: every quart of {@code quarts} set to {@code biomeId}. */
    static BiomeWork fill(QuartSelection quarts, String biomeId) {
        Objects.requireNonNull(quarts);
        Objects.requireNonNull(biomeId);
        long[] columns = quarts.chunkColumns();
        return new BiomeWork() {
            @Override
            public long[] columns() {
                return columns.clone();
            }

            @Override
            public void apply(long column, Context ctx) {
                quarts.forEachIn(column, (qx, qy, qz) -> ctx.write(qx, qy, qz, biomeId));
            }

            @Override
            public long quarts() {
                return quarts.count();
            }
        };
    }

    /**
     * Quarts with a biome each, planned at admission (a paste's, move's or stack's carried biomes), grouped by chunk
     * column in the order planned; a quart planned twice keeps its first biome, as the blocks' claiming does (the
     * original copy, then the earlier placement, win).
     */
    static final class Plan implements BiomeMapping.QuartSink {
        /**
         * Most quarts a paste's biomes may plan, even under {@code limit.bypass}: planning runs on the server thread at
         * admission (about 12 bytes a quart).
         */
        static final long MAX_QUARTS = 1L << 23;

        private final Long2ObjectLinkedOpenHashMap<LongArrayList> keys = new Long2ObjectLinkedOpenHashMap<>();
        private final Long2ObjectLinkedOpenHashMap<IntArrayList> ids = new Long2ObjectLinkedOpenHashMap<>();
        private final List<String> palette = new ArrayList<>();
        private final Object2IntOpenHashMap<String> index = new Object2IntOpenHashMap<>();
        private long count;
        /** Every quart planned ({@link BiomeHistory#key}), so a later plan of one is dropped. */
        private final LongOpenHashSet planned = new LongOpenHashSet();

        Plan() {
            index.defaultReturnValue(-1);
        }

        @Override
        public void set(int qx, int qy, int qz, String biomeId) {
            long key = BiomeHistory.key(qx, qy, qz);
            if (!planned.add(key)) return;
            int id = index.getInt(biomeId);
            if (id < 0) {
                id = palette.size();
                palette.add(biomeId);
                index.put(biomeId, id);
            }
            long column = ColumnPlan.pack(qx >> 2, qz >> 2);
            keys.computeIfAbsent(column, c -> new LongArrayList()).add(key);
            ids.computeIfAbsent(column, c -> new IntArrayList()).add(id);
            count++;
        }

        /** Distinct quarts planned. */
        long count() {
            return count;
        }

        /** The distinct biome ids planned. */
        Set<String> biomeIds() {
            return Set.copyOf(palette);
        }

        boolean isEmpty() {
            return count == 0;
        }

        /** The work writing the plan. */
        BiomeWork work() {
            long[] columns = keys.keySet().toLongArray();
            String[] names = palette.toArray(new String[0]);
            return new BiomeWork() {
                @Override
                public long[] columns() {
                    return columns.clone();
                }

                @Override
                public void apply(long column, Context ctx) {
                    LongArrayList quarts = keys.get(column);
                    IntArrayList biomes = ids.get(column);
                    if (quarts == null) return;
                    for (int i = 0; i < quarts.size(); i++) {
                        long key = quarts.getLong(i);
                        ctx.write(BiomeHistory.keyX(key), BiomeHistory.keyY(key), BiomeHistory.keyZ(key),
                                names[biomes.getInt(i)]);
                    }
                }

                @Override
                public long quarts() {
                    return count;
                }
            };
        }
    }

    /**
     * Plans into {@code plan} a symmetric copy of a free paste placement's biomes, as its blocks are written (the image of
     * what the original writes, {@code PasteProgram.compileImage}): each quart the image of the result box
     * {@code target} touches takes its centre, clamped into that image box, back to the cell it is the image of, then
     * maps that cell as {@link BiomeMapping#map} maps the original's ({@code orientation}'s sampler into {@code grid}, a
     * source of {@code size}).
     */
    static void mapImage(Plan plan, BiomeGrid grid, BlockPos size, Orientation orientation, Box target,
                         Symmetry symmetry, Symmetry.Image image, int bottomY, int topYExclusive) {
        if (grid == null) return;
        if (image == Symmetry.Image.IDENTITY) {
            map(plan, grid, size, orientation, target.min(), bottomY, topYExclusive);
            return;
        }
        Box imaged = symmetry.imageBox(image, target);
        Symmetry.Image back = image.inverse();
        Orientation.Sampler sampler = orientation.sampler(size.x(), size.y(), size.z());
        int[] cell = new int[3];
        BlockPos min = imaged.min(), max = imaged.max();
        int qy0 = Math.max(bottomY >> 2, min.y() >> 2), qy1 = Math.min((topYExclusive >> 2) - 1, max.y() >> 2);
        for (int qy = qy0; qy <= qy1; qy++) {
            for (int qz = min.z() >> 2; qz <= max.z() >> 2; qz++) {
                for (int qx = min.x() >> 2; qx <= max.x() >> 2; qx++) {
                    int x = Math.max(min.x(), Math.min(max.x(), (qx << 2) + 2));
                    int y = Math.max(min.y(), Math.min(max.y(), (qy << 2) + 2));
                    int z = Math.max(min.z(), Math.min(max.z(), (qz << 2) + 2));
                    long px = symmetry.cellX(back, x, z) - target.min().x(), pz = symmetry.cellZ(back, x, z)
                            - target.min().z();
                    int py = y - target.min().y();
                    if (px < 0 || pz < 0 || px >= target.sizeX() || pz >= target.sizeZ()) continue;
                    if (!sampler.source((int) px, py, (int) pz, cell)) continue;
                    String id = grid.biomeAt(cell[0], cell[1], cell[2]);
                    if (id != null) plan.set(qx, qy, qz, id);
                }
            }
        }
    }

    /**
     * Plans {@code grid} (of a source of {@code size}) mapped through {@code orientation} onto the result box at
     * {@code resultMin} ({@link BiomeMapping#map}) into {@code plan}.
     */
    static void map(Plan plan, BiomeGrid grid, BlockPos size, Orientation orientation, BlockPos resultMin, int bottomY,
                    int topYExclusive) {
        if (grid == null) return;
        BiomeMapping.map(grid, size, orientation, resultMin, bottomY, topYExclusive, plan);
    }

    /**
     * An undo, redo or re-apply of biomes: per quart of {@code steps} (before: what the step left; after: the target),
     * decided by {@link BiomeHistory#decide}: written when the world holds what the step left (or always with
     * {@link ConflictPolicy#OVERWRITE}), left alone when it holds the target, kept and counted otherwise. Nothing is
     * recorded (the job's sink decides; undo and redo record nothing).
     */
    static BiomeWork history(BiomeChanges steps, ConflictPolicy policy) {
        Objects.requireNonNull(steps);
        Objects.requireNonNull(policy);
        Long2ObjectLinkedOpenHashMap<IntArrayList> byColumn = new Long2ObjectLinkedOpenHashMap<>();
        for (int i = 0; i < steps.size(); i++) {
            byColumn.computeIfAbsent(ColumnPlan.pack(steps.qx(i) >> 2, steps.qz(i) >> 2), c -> new IntArrayList()).add(i);
        }
        long[] columns = byColumn.keySet().toLongArray();
        return new BiomeWork() {
            @Override
            public long[] columns() {
                return Arrays.copyOf(columns, columns.length);
            }

            @Override
            public void apply(long column, Context ctx) {
                IntArrayList indices = byColumn.get(column);
                if (indices == null) return;
                for (int k = 0; k < indices.size(); k++) {
                    int i = indices.getInt(k);
                    int qx = steps.qx(i), qy = steps.qy(i), qz = steps.qz(i);
                    if (!ctx.mayChange(qx, qy, qz)) {
                        ctx.visited++;
                        ctx.protectedQuarts++;
                        continue;
                    }
                    int liveHandle = ctx.biomes.get(qx, qy, qz);
                    if (liveHandle < 0) {
                        // A biome the registry does not list: kept and counted, never written over.
                        ctx.visited++;
                        ctx.conflicts++;
                        continue;
                    }
                    String live = ctx.biomes.id(liveHandle);
                    switch (BiomeHistory.decide(steps.before(i), steps.after(i), live, policy)) {
                        case SKIP -> ctx.visited++;
                        case APPLY -> ctx.write(qx, qy, qz, steps.after(i));
                        case CONFLICT -> {
                            ctx.visited++;
                            ctx.conflicts++;
                        }
                    }
                }
            }

            @Override
            public long quarts() {
                return steps.size();
            }
        };
    }

    /**
     * A move's or stack's carried biomes (protocol 6), read when the job's biome stage runs, not at admission. The stage
     * first visits the source chunk columns ({@link #readColumns}: the columns the source regions' cells are in) and
     * reads the biome of every quart a region cell is in; each chunk is ticketed and loaded like any other column, so an
     * unloaded source is read, not skipped. Only then does it visit the target columns ({@link #columns}) and write them,
     * so a target overlapping its source reads the source as it was.
     *
     * <p>A result quart (one the target's result box touches) takes the biome of the source quart its centre, clamped
     * into the result box, maps back to through the orientation's sampler (the blocks' own), when a source cell is in
     * that quart; otherwise it is left alone: exactly as a paste maps a copy's biomes ({@link BiomeMapping#map}). The
     * target columns are those the moved or stacked cells land in, widened by the reach of that rule (a quart, two for a
     * free orientation), so no such quart is missed. Each target column is mapped when its step comes, every quart looked
     * at charged to the tick budget.
     */
    static final class Carried implements BiomeWork {
        /**
         * Most target columns a job's targets may list in all (a stack's copies times its source columns, every symmetric
         * copy included), even under {@code limit.bypass}: listing them runs on the server thread at admission.
         */
        static final long MAX_LISTED_COLUMNS = 1L << 22;

        /** A source region and what the stage read of it. */
        private static final class Source {
            final Region region;
            final Box from;
            /** The quart rows read: {@code from}'s, cut to the build height. */
            final int qy0;
            final int qy1;
            /**
             * Per chunk column ({@link ColumnPlan#pack}), the handles read: 16 quarts per quart row, each the handle plus
             * one, 0 for a quart no region cell is in (or holding a biome the registry does not list).
             */
            final Long2ObjectOpenHashMap<char[]> read = new Long2ObjectOpenHashMap<>();

            Source(Region region, Box from, int bottomY, int topYExclusive) {
                this.region = region;
                this.from = from;
                qy0 = Math.max(bottomY, from.min().y()) >> 2;
                qy1 = Math.min(topYExclusive - 1, from.max().y()) >> 2;
            }

            int index(int qx, int qy, int qz) {
                return ((qy - qy0) << 4) | ((qz & 3) << 2) | (qx & 3);
            }

            /** The handle read for quart {@code (qx, qy, qz)}, or -1. */
            int handle(int qx, int qy, int qz) {
                if (qy < qy0 || qy > qy1) return -1;
                char[] column = read.get(ColumnPlan.pack(qx >> 2, qz >> 2));
                return column == null ? -1 : column[index(qx, qy, qz)] - 1;
            }
        }

        /**
         * One carried target: the result box ({@code min}..{@code max}) a source box at {@code sourceMin} maps onto
         * through {@code sampler}, read from {@code source}. With an {@code image}, a symmetric copy of a free move, as
         * its blocks are ({@code MoveProgram}): it writes the image box ({@code imageMin}..{@code imageMax}); each quart
         * there takes its clamped centre back to the original's result cell, samples the original's source cell, and
         * reads the image of that cell from {@code source}, the copy's own (imaged) source.
         */
        private record Target(Source source, BlockPos sourceMin, Orientation.Sampler sampler, BlockPos min,
                              BlockPos max, Symmetry symmetry, Symmetry.Image image, BlockPos imageMin,
                              BlockPos imageMax, int[] cell) {}

        private final int bottomY;
        private final int topYExclusive;
        private final Long2ObjectLinkedOpenHashMap<List<Source>> sourcesByColumn;
        private final Long2ObjectLinkedOpenHashMap<List<Target>> targetsByColumn;
        private final long bound;
        private final int[] rows = new int[Regions.ROWS];

        private Carried(Builder b) {
            bottomY = b.bottomY;
            topYExclusive = b.topYExclusive;
            sourcesByColumn = b.sourcesByColumn;
            targetsByColumn = b.targetsByColumn;
            bound = b.bound;
        }

        @Override
        public long[] readColumns() {
            return sourcesByColumn.keySet().toLongArray();
        }

        @Override
        public long[] columns() {
            return targetsByColumn.keySet().toLongArray();
        }

        @Override
        public long quarts() {
            return bound;
        }

        /** Reads the quarts that cells of each source region in chunk column {@code column} are in. */
        @Override
        public void read(long column, Context ctx) {
            List<Source> sources = sourcesByColumn.get(column);
            if (sources == null) return;
            int cx = ColumnPlan.unpackX(column), cz = ColumnPlan.unpackZ(column);
            for (Source source : sources) {
                if (source.read.containsKey(column)) continue;
                int minY = Math.max(bottomY, source.from.min().y());
                int maxY = Math.min(topYExclusive - 1, source.from.max().y());
                char[] handles = null;
                for (int sy = minY >> 4; sy <= maxY >> 4; sy++) {
                    if (Regions.rows(source.region, BlockBuffer.key(cx, sy, cz), minY, maxY, rows) == 0) continue;
                    if (handles == null) handles = new char[(source.qy1 - source.qy0 + 1) << 4];
                    for (int r = 0; r < Regions.ROWS; r++) {
                        int bits = rows[r];
                        if (bits == 0) continue;
                        int qy = ((sy << 4) + (r >>> 4)) >> 2, qz = ((cz << 4) + (r & 15)) >> 2;
                        for (int q = 0; q < 4; q++) {
                            if (((bits >>> (q << 2)) & 0xF) == 0) continue;
                            int qx = (cx << 2) + q;
                            int index = source.index(qx, qy, qz);
                            if (handles[index] != 0) continue;
                            int handle = ctx.biomes.get(qx, qy, qz);
                            ctx.visited++;
                            // A biome the registry does not list (-1) is not carried.
                            if (handle >= 0 && handle < Character.MAX_VALUE) handles[index] = (char) (handle + 1);
                        }
                    }
                }
                if (handles != null) source.read.put(column, handles);
            }
        }

        /** Maps and writes the target quarts of chunk column {@code column} (every read is done by now). */
        @Override
        public void apply(long column, Context ctx) {
            List<Target> targets = targetsByColumn.get(column);
            if (targets == null) return;
            int cx = ColumnPlan.unpackX(column), cz = ColumnPlan.unpackZ(column);
            int minQy = bottomY >> 2, maxQy = (topYExclusive >> 2) - 1;
            // In planned order: a later stack copy wins a quart two copies write.
            for (Target t : targets) {
                boolean imaged = t.image() != null;
                BlockPos min = imaged ? t.imageMin() : t.min(), max = imaged ? t.imageMax() : t.max();
                int qy0 = Math.max(minQy, min.y() >> 2), qy1 = Math.min(maxQy, max.y() >> 2);
                int qx0 = Math.max(min.x() >> 2, cx << 2), qx1 = Math.min(max.x() >> 2, (cx << 2) + 3);
                int qz0 = Math.max(min.z() >> 2, cz << 2), qz1 = Math.min(max.z() >> 2, (cz << 2) + 3);
                for (int qy = qy0; qy <= qy1; qy++) {
                    for (int qz = qz0; qz <= qz1; qz++) {
                        for (int qx = qx0; qx <= qx1; qx++) {
                            // The quart's centre clamped into the box it is written in (the image box for a copy).
                            int x = clamp((qx << 2) + 2, min.x(), max.x());
                            int y = clamp((qy << 2) + 2, min.y(), max.y());
                            int z = clamp((qz << 2) + 2, min.z(), max.z());
                            int handle = imaged ? imagedHandle(t, x, y, z) : handle(t, x, y, z);
                            if (handle < 0) {
                                // Looked at, nothing to carry: still charged to the budget.
                                ctx.visited++;
                            } else {
                                ctx.write(qx, qy, qz, ctx.biomes.id(handle));
                            }
                        }
                    }
                }
            }
        }

        /** The handle target {@code t} carries into its result cell {@code (x, y, z)} (world), or -1. */
        private static int handle(Target t, int x, int y, int z) {
            int[] cell = t.cell();
            if (!t.sampler().source(x - t.min().x(), y - t.min().y(), z - t.min().z(), cell)) return -1;
            BlockPos origin = t.sourceMin();
            return t.source().handle((origin.x() + cell[0]) >> 2, (origin.y() + cell[1]) >> 2,
                    (origin.z() + cell[2]) >> 2);
        }

        /**
         * The handle a copy {@code t} of a free move carries into its cell {@code (x, y, z)} (world, in the image box):
         * the exact cell it is the image of, sampled in the original's result box, and the image of that source cell
         * read from the copy's own source; -1 for none.
         */
        private static int imagedHandle(Target t, int x, int y, int z) {
            Symmetry symmetry = t.symmetry();
            Symmetry.Image back = t.image().inverse();
            long px = symmetry.cellX(back, x, z) - t.min().x(), pz = symmetry.cellZ(back, x, z) - t.min().z();
            long sx = (long) t.max().x() - t.min().x(), sz = (long) t.max().z() - t.min().z();
            if (px < 0 || pz < 0 || px > sx || pz > sz) return -1;
            int[] cell = t.cell();
            if (!t.sampler().source((int) px, y - t.min().y(), (int) pz, cell)) return -1;
            BlockPos origin = t.sourceMin();
            int wx = origin.x() + cell[0], wy = origin.y() + cell[1], wz = origin.z() + cell[2];
            long ix = symmetry.cellX(t.image(), wx, wz), iz = symmetry.cellZ(t.image(), wx, wz);
            if (ix != (int) ix || iz != (int) iz) return -1;
            return t.source().handle((int) ix >> 2, wy >> 2, (int) iz >> 2);
        }

        private static int clamp(int v, int min, int max) {
            return Math.max(min, Math.min(max, v));
        }

        /**
         * The chunk columns of {@code landed} ({@link Regions#columns} bitmaps) and their neighbours that a landed block
         * column lies within {@code reach} blocks of (reach below 16).
         */
        static LongOpenHashSet widened(Long2ObjectMap<long[]> landed, int reach) {
            LongOpenHashSet out = new LongOpenHashSet(landed.size() * 2);
            for (Long2ObjectMap.Entry<long[]> chunk : landed.long2ObjectEntrySet()) {
                long key = chunk.getLongKey();
                int cx = ColumnPlan.unpackX(key), cz = ColumnPlan.unpackZ(key);
                int minX = 16, maxX = -1, minZ = 16, maxZ = -1;
                long[] words = chunk.getValue();
                for (int w = 0; w < words.length; w++) {
                    for (long bits = words[w]; bits != 0; bits &= bits - 1) {
                        int bit = (w << 6) | Long.numberOfTrailingZeros(bits);
                        int x = bit & 15, z = bit >>> 4;
                        minX = Math.min(minX, x);
                        maxX = Math.max(maxX, x);
                        minZ = Math.min(minZ, z);
                        maxZ = Math.max(maxZ, z);
                    }
                }
                if (maxX < 0) continue;
                int dx0 = minX < reach ? -1 : 0, dx1 = maxX > 15 - reach ? 1 : 0;
                int dz0 = minZ < reach ? -1 : 0, dz1 = maxZ > 15 - reach ? 1 : 0;
                for (int dz = dz0; dz <= dz1; dz++) {
                    for (int dx = dx0; dx <= dx1; dx++) out.add(ColumnPlan.pack(cx + dx, cz + dz));
                }
            }
            return out;
        }

        /** {@code columns} ({@link Regions#columns} bitmaps) shifted by {@code dx}, {@code dz} blocks. */
        static Long2ObjectOpenHashMap<long[]> shifted(Long2ObjectMap<long[]> columns, int dx, int dz) {
            Long2ObjectOpenHashMap<long[]> out = new Long2ObjectOpenHashMap<>(columns.size() * 2);
            for (Long2ObjectMap.Entry<long[]> chunk : columns.long2ObjectEntrySet()) {
                long key = chunk.getLongKey();
                if ((dx & 15) == 0 && (dz & 15) == 0) {
                    out.put(ColumnPlan.pack(ColumnPlan.unpackX(key) + (dx >> 4), ColumnPlan.unpackZ(key) + (dz >> 4)),
                            chunk.getValue());
                    continue;
                }
                int ox = ColumnPlan.unpackX(key) << 4, oz = ColumnPlan.unpackZ(key) << 4;
                long[] words = chunk.getValue();
                for (int w = 0; w < words.length; w++) {
                    for (long bits = words[w]; bits != 0; bits &= bits - 1) {
                        int bit = (w << 6) | Long.numberOfTrailingZeros(bits);
                        int x = ox + (bit & 15) + dx, z = oz + (bit >>> 4) + dz;
                        long[] into = out.computeIfAbsent(ColumnPlan.pack(x >> 4, z >> 4), c -> new long[4]);
                        int b = ((z & 15) << 4) | (x & 15);
                        into[b >>> 6] |= 1L << (b & 63);
                    }
                }
            }
            return out;
        }

        /**
         * Collects a job's sources and targets at admission from the chunk columns of each source region (listed under
         * the job's column cap), refusing {@code TOO_LARGE} as soon as the target columns pass that cap, the quarts the
         * targets may receive pass the volume limit, or the columns listed pass {@link #MAX_LISTED_COLUMNS}: nothing is
         * listed by bounding box.
         */
        static final class Builder {
            private final int bottomY;
            private final int topYExclusive;
            private final int maxColumns;
            private final long maxQuarts;
            private final List<Source> sources = new ArrayList<>();
            private final List<Long2ObjectOpenHashMap<long[]>> sourceColumns = new ArrayList<>();
            private final Long2ObjectLinkedOpenHashMap<List<Source>> sourcesByColumn =
                    new Long2ObjectLinkedOpenHashMap<>();
            private final Long2ObjectLinkedOpenHashMap<List<Target>> targetsByColumn =
                    new Long2ObjectLinkedOpenHashMap<>();
            private long bound;
            private long listed;

            /**
             * @param maxColumns the job's chunk column cap (target columns beyond it are refused)
             * @param maxQuarts the volume limit the target quarts count against ({@code Long.MAX_VALUE} under
             *     {@code limit.bypass})
             */
            Builder(int bottomY, int topYExclusive, int maxColumns, long maxQuarts) {
                this.bottomY = bottomY;
                this.topYExclusive = topYExclusive;
                this.maxColumns = maxColumns;
                this.maxQuarts = maxQuarts;
            }

            /**
             * Adds a source: the quarts that cells of {@code region} inside {@code from} (inside the build height) are
             * in. {@code columns} are the region's chunk columns there ({@link Regions#columns}, capped).
             *
             * @return its index, for the targets
             */
            int source(Region region, Box from, Long2ObjectOpenHashMap<long[]> columns) {
                Source source = new Source(Objects.requireNonNull(region), Objects.requireNonNull(from), bottomY,
                        topYExclusive);
                sources.add(source);
                sourceColumns.add(columns);
                for (long column : columns.keySet()) {
                    sourcesByColumn.computeIfAbsent(column, c -> new ArrayList<>(1)).add(source);
                }
                return sources.size() - 1;
            }

            /**
             * Carries source {@code source} through the exact transform {@code t} onto the result box at
             * {@code resultMin}, its cells landing as {@link Regions#movedColumns} moves them.
             *
             * @throws EditRejected {@code TOO_LARGE} past the column cap, the volume limit or the listing ceiling
             */
            void target(int source, Transform t, BlockPos resultMin) throws EditRejected {
                stack(source, t, List.of(resultMin));
            }

            /**
             * Carries source {@code source} through the exact transform {@code t} onto each result box at
             * {@code resultMins} (a stack's copies, the same shape shifted): the landed columns are worked out once and
             * shifted per copy.
             *
             * @throws EditRejected {@code TOO_LARGE} past the column cap, the volume limit or the listing ceiling
             */
            void stack(int source, Transform t, List<BlockPos> resultMins) throws EditRejected {
                if (resultMins.isEmpty()) return;
                Source s = sources.get(source);
                Long2ObjectOpenHashMap<long[]> columns = sourceColumns.get(source);
                list((long) resultMins.size() * columns.size());
                BlockPos first = resultMins.get(0);
                Long2ObjectOpenHashMap<long[]> base = Regions.movedColumns(columns, s.from, first, t);
                // Each copy is the first shifted: by a remainder within a chunk (at most 16 distinct pairs along a
                // stack, each worked out once, bit by bit) and then by whole chunks (one key move per column).
                java.util.Map<Long, LongOpenHashSet> byRemainder = new java.util.HashMap<>();
                Orientation orientation = Orientation.of(t);
                for (BlockPos resultMin : resultMins) {
                    int dx = resultMin.x() - first.x(), dz = resultMin.z() - first.z();
                    int rx = dx & 15, rz = dz & 15;
                    LongOpenHashSet widenedAtRemainder = byRemainder.computeIfAbsent(((long) rx << 4) | rz,
                            r -> widened(rx == 0 && rz == 0 ? base : shifted(base, rx, rz), 4));
                    int chunksX = (dx - rx) >> 4, chunksZ = (dz - rz) >> 4;
                    LongOpenHashSet landed;
                    if (chunksX == 0 && chunksZ == 0) {
                        landed = widenedAtRemainder;
                    } else {
                        landed = new LongOpenHashSet(widenedAtRemainder.size());
                        for (long column : widenedAtRemainder) {
                            landed.add(ColumnPlan.pack(ColumnPlan.unpackX(column) + chunksX,
                                    ColumnPlan.unpackZ(column) + chunksZ));
                        }
                    }
                    add(s, s.from, orientation, resultMin, landed, null, Symmetry.Image.IDENTITY);
                }
            }

            /**
             * Carries a free move's copy: the original's box {@code from} maps through {@code orientation} (the sampler
             * the blocks use) onto the result box at {@code resultMin}, its cells landing in {@code landed} (the copy's
             * landing cells' columns, worked out once at admission). For the identity {@code source} is the original's
             * own source; for another {@code image} it is the copy's own imaged source, and the copy writes the image of
             * the original's result box, as its blocks do.
             *
             * @throws EditRejected {@code TOO_LARGE} past the column cap, the volume limit or the listing ceiling
             */
            void target(int source, Box from, Orientation orientation, BlockPos resultMin,
                        Long2ObjectOpenHashMap<long[]> landed, Symmetry symmetry, Symmetry.Image image)
                    throws EditRejected {
                list(landed.size());
                // A free rotation moves a cell up to about 5.2 blocks from its quart's centre: two quarts of reach.
                add(sources.get(source), from, orientation, resultMin, widened(landed, orientation.isExact() ? 4 : 8),
                        symmetry, image);
            }

            private void list(long columns) throws EditRejected {
                listed = listed > Long.MAX_VALUE - columns ? Long.MAX_VALUE : listed + columns;
                if (listed > MAX_LISTED_COLUMNS) {
                    throw new EditRejected(RejectReason.TOO_LARGE, "carried biomes: " + listed + " columns to list > "
                            + MAX_LISTED_COLUMNS);
                }
            }

            private void add(Source s, Box from, Orientation orientation, BlockPos resultMin, LongOpenHashSet landed,
                             Symmetry symmetry, Symmetry.Image image) throws EditRejected {
                BlockPos size = new BlockPos(from.sizeX(), from.sizeY(), from.sizeZ());
                Orientation.Sampler sampler = orientation.sampler(size.x(), size.y(), size.z());
                BlockPos result = sampler.size();
                BlockPos max = new BlockPos(resultMin.x() + result.x() - 1, resultMin.y() + result.y() - 1,
                        resultMin.z() + result.z() - 1);
                // A landed column receives at most 16 quart columns of the result box's height.
                long height = Math.max(0, Math.min((long) (topYExclusive >> 2) - 1, max.y() >> 2)
                        - Math.max(bottomY >> 2, resultMin.y() >> 2) + 1);
                // Wholly outside the build height: nothing to write, no column to ticket.
                if (height == 0) return;
                long quarts = Math.min(BiomeMapping.bound(size, orientation), landed.size() * 16L * height);
                bound = bound > Long.MAX_VALUE - quarts ? Long.MAX_VALUE : bound + quarts;
                if (bound > maxQuarts) {
                    throw new EditRejected(RejectReason.TOO_LARGE, "carried biomes: " + bound + " biome cells > "
                            + maxQuarts);
                }
                boolean imaged = image != Symmetry.Image.IDENTITY;
                Box written;
                try {
                    written = imaged ? symmetry.imageBox(image, new Box(resultMin, max)) : new Box(resultMin, max);
                } catch (IllegalArgumentException e) {
                    throw new EditRejected(RejectReason.INVALID, e.getMessage());
                }
                Target target = new Target(s, from.min(), sampler, resultMin, max, symmetry, imaged ? image : null,
                        written.min(), written.max(), new int[3]);
                // Only columns the written box reaches hold quarts it writes.
                int cx0 = written.min().x() >> 4, cx1 = written.max().x() >> 4;
                int cz0 = written.min().z() >> 4, cz1 = written.max().z() >> 4;
                for (long column : landed) {
                    int cx = ColumnPlan.unpackX(column), cz = ColumnPlan.unpackZ(column);
                    if (cx < cx0 || cx > cx1 || cz < cz0 || cz > cz1) continue;
                    targetsByColumn.computeIfAbsent(column, c -> new ArrayList<>(1)).add(target);
                    if (targetsByColumn.size() > maxColumns) {
                        throw new EditRejected(RejectReason.TOO_LARGE, "carried biomes: more than " + maxColumns
                                + " chunk columns");
                    }
                }
            }

            /** The most quarts the targets may receive: what admission counts against the volume limit. */
            long bound() {
                return bound;
            }

            boolean isEmpty() {
                return targetsByColumn.isEmpty();
            }

            BiomeWork build() {
                return new Carried(this);
            }
        }
    }
}
