package dev.sculptory.core.biome;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.RegionTooLargeException;
import dev.sculptory.core.region.Regions;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A set of quarts within one world's build height (protocol 6), kept per chunk column: what a biome fill sets
 * ({@link #touched}) and what a move or stack carries. Quart coordinates are block coordinates shifted right by 2;
 * chunk columns are packed as {@link Regions#column} does. Immutable once made.
 */
public final class QuartSelection {
    /** Visits one quart. */
    @FunctionalInterface
    public interface QuartVisitor {
        void visit(int qx, int qy, int qz);
    }

    /** The tallest build height a selection spans, in quarts (a world is at most 4,064 blocks tall). */
    public static final int MAX_HEIGHT_QUARTS = 1024;

    private final int minQy;
    private final int heightQuarts;
    /** Per chunk column: bit {@code ((qy - minQy) << 4) | ((qz & 3) << 2) | (qx & 3)}. */
    private final Long2ObjectOpenHashMap<long[]> chunks;
    private final long[] order;
    private final long count;

    private QuartSelection(int minQy, int heightQuarts, Long2ObjectOpenHashMap<long[]> chunks) {
        this.minQy = minQy;
        this.heightQuarts = heightQuarts;
        this.chunks = chunks;
        this.order = chunks.keySet().toLongArray();
        Arrays.sort(order);
        long n = 0;
        for (long[] bits : chunks.values()) {
            for (long word : bits) n += Long.bitCount(word);
        }
        this.count = n;
    }

    /**
     * The quarts {@code region} touches between {@code bottomY} and {@code topYExclusive} (WorldEdit's
     * {@code //setbiome} rule): with {@code columns}, every quart over the whole build height of each quart column holding
     * a region cell; without, every quart holding a region cell.
     *
     * @throws IllegalStateException for an {@link Region.Uploaded} region (resolve it first)
     */
    public static QuartSelection touched(Region region, boolean columns, int bottomY, int topYExclusive) {
        return touched(region, columns, bottomY, topYExclusive, Integer.MAX_VALUE, Long.MAX_VALUE);
    }

    /**
     * {@link #touched(Region, boolean, int, int)}, refusing a region that reaches more than {@code maxChunks} chunk
     * columns or touches more than {@code maxQuarts} quarts as soon as that shows, before the rest is listed: what an
     * edit admits under its limits (a symmetric or very long region never builds a set past them).
     *
     * @throws RegionTooLargeException over either cap
     */
    public static QuartSelection touched(Region region, boolean columns, int bottomY, int topYExclusive, int maxChunks,
                                         long maxQuarts) {
        Objects.requireNonNull(region);
        long span = ((long) topYExclusive >> 2) - (bottomY >> 2);
        if (span <= 0) throw new IllegalArgumentException("Empty build height");
        if (span > MAX_HEIGHT_QUARTS) {
            throw new IllegalArgumentException("Biomes need a world's build height, not " + span + " quarts");
        }
        int minQy = bottomY >> 2, height = (int) span;
        Long2ObjectOpenHashMap<long[]> chunks = new Long2ObjectOpenHashMap<>();
        int words = (height * 16 + 63) >> 6;
        int maxY = topYExclusive - 1;
        if (columns) {
            long count = 0;
            for (Long2ObjectMap.Entry<long[]> chunk : Regions.columns(region, bottomY, maxY, maxChunks)
                    .long2ObjectEntrySet()) {
                long[] bits = chunk.getValue();
                long[] out = null;
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        int bit = (lz << 4) | lx;
                        if ((bits[bit >> 6] & (1L << (bit & 63))) == 0) continue;
                        if (out == null) out = new long[words];
                        int column = ((lz >> 2) << 2) | (lx >> 2);
                        for (int qy = 0; qy < height; qy++) {
                            int i = (qy << 4) | column;
                            out[i >> 6] |= 1L << (i & 63);
                        }
                    }
                }
                if (out == null) continue;
                chunks.put(chunk.getLongKey(), out);
                for (long word : out) count += Long.bitCount(word);
                if (count > maxQuarts) throw tooManyQuarts(count, maxQuarts);
            }
        } else {
            int[] rows = new int[Regions.ROWS];
            long sectionsPerColumn = ((long) maxY >> 4) - (bottomY >> 4) + 1;
            long maxSections = maxChunks >= Long.MAX_VALUE / sectionsPerColumn ? Long.MAX_VALUE
                    : maxChunks * sectionsPerColumn;
            long count = 0;
            for (long key : Regions.sectionKeysBetween(region, bottomY, maxY, maxSections)) {
                if (Regions.rows(region, key, bottomY, maxY, rows) == 0) continue;
                int sx = BlockBuffer.keyX(key), sy = BlockBuffer.keyY(key), sz = BlockBuffer.keyZ(key);
                long[] out = chunks.computeIfAbsent(Regions.column(sx, sz), c -> new long[words]);
                if (chunks.size() > maxChunks) {
                    throw new RegionTooLargeException(chunks.size() + " chunk columns > " + maxChunks, chunks.size(),
                            maxChunks);
                }
                for (int r = 0; r < Regions.ROWS; r++) {
                    int bits = rows[r];
                    if (bits == 0) continue;
                    int ly = r >>> 4, lz = r & 15;
                    int qy = (sy << 2) + (ly >> 2) - minQy;
                    if (qy < 0 || qy >= height) continue;
                    for (int g = 0; g < 4; g++) {
                        if ((bits & (0xF << (g << 2))) == 0) continue;
                        int i = (qy << 4) | ((lz >> 2) << 2) | g;
                        if ((out[i >> 6] & (1L << (i & 63))) == 0 && ++count > maxQuarts) {
                            throw tooManyQuarts(count, maxQuarts);
                        }
                        out[i >> 6] |= 1L << (i & 63);
                    }
                }
            }
            chunks.values().removeIf(QuartSelection::empty);
        }
        return new QuartSelection(minQy, height, chunks);
    }

    /**
     * How many quarts {@link #touched} would hold, without making it, for a cuboid; an upper bound (the bounds' quarts)
     * for any other region. Quick: for admission before the selection is made.
     */
    public static long estimate(Region region, boolean columns, int bottomY, int topYExclusive) {
        Box box = region.bounds();
        long qx = (box.max().x() >> 2) - (long) (box.min().x() >> 2) + 1;
        long qz = (box.max().z() >> 2) - (long) (box.min().z() >> 2) + 1;
        long height = (topYExclusive >> 2) - (bottomY >> 2);
        if (columns) return qx * qz * height;
        int minY = Math.max(bottomY, box.min().y()), maxY = Math.min(topYExclusive - 1, box.max().y());
        if (minY > maxY) return 0;
        return qx * qz * ((maxY >> 2) - (minY >> 2) + 1);
    }

    /**
     * Every quart of any of {@code parts}, which must share one build height (a symmetric fill's copies).
     *
     * @throws IllegalArgumentException for an empty list or parts of different build heights
     */
    public static QuartSelection union(List<QuartSelection> parts) {
        if (parts.isEmpty()) throw new IllegalArgumentException("Nothing to unite");
        QuartSelection first = parts.get(0);
        if (parts.size() == 1) return first;
        Long2ObjectOpenHashMap<long[]> chunks = new Long2ObjectOpenHashMap<>();
        for (QuartSelection part : parts) {
            if (part.minQy != first.minQy || part.heightQuarts != first.heightQuarts) {
                throw new IllegalArgumentException("Selections of different build heights");
            }
            for (Long2ObjectMap.Entry<long[]> chunk : part.chunks.long2ObjectEntrySet()) {
                long[] into = chunks.computeIfAbsent(chunk.getLongKey(), c -> new long[chunk.getValue().length]);
                for (int w = 0; w < into.length; w++) into[w] |= chunk.getValue()[w];
            }
        }
        return new QuartSelection(first.minQy, first.heightQuarts, chunks);
    }

    /**
     * The smallest box holding every block of every quart, or {@code null} when empty.
     */
    public Box bounds() {
        if (count == 0) return null;
        int minQx = Integer.MAX_VALUE, minQz = Integer.MAX_VALUE, maxQx = Integer.MIN_VALUE, maxQz = Integer.MIN_VALUE;
        int lowQy = Integer.MAX_VALUE, highQy = Integer.MIN_VALUE;
        for (long column : order) {
            int[] range = {Integer.MAX_VALUE, Integer.MIN_VALUE};
            int[] xz = {Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE};
            forEachIn(column, (qx, qy, qz) -> {
                range[0] = Math.min(range[0], qy);
                range[1] = Math.max(range[1], qy);
                xz[0] = Math.min(xz[0], qx);
                xz[1] = Math.max(xz[1], qx);
                xz[2] = Math.min(xz[2], qz);
                xz[3] = Math.max(xz[3], qz);
            });
            lowQy = Math.min(lowQy, range[0]);
            highQy = Math.max(highQy, range[1]);
            minQx = Math.min(minQx, xz[0]);
            maxQx = Math.max(maxQx, xz[1]);
            minQz = Math.min(minQz, xz[2]);
            maxQz = Math.max(maxQz, xz[3]);
        }
        return new Box(new BlockPos(minQx << 2, lowQy << 2, minQz << 2),
                new BlockPos((maxQx << 2) + 3, (highQy << 2) + 3, (maxQz << 2) + 3));
    }

    private static RegionTooLargeException tooManyQuarts(long count, long max) {
        return new RegionTooLargeException(count + " biome cells > " + max, count, max);
    }

    private static boolean empty(long[] bits) {
        for (long word : bits) {
            if (word != 0) return false;
        }
        return true;
    }

    /** Quarts in the selection. */
    public long count() {
        return count;
    }

    public boolean isEmpty() {
        return count == 0;
    }

    /** The chunk columns holding a quart, packed ({@link Regions#column}), in ascending order. A fresh array. */
    public long[] chunkColumns() {
        return order.clone();
    }

    /** Whether quart {@code (qx, qy, qz)} is in the selection. */
    public boolean contains(int qx, int qy, int qz) {
        long[] bits = chunks.get(Regions.column(qx >> 2, qz >> 2));
        int y = qy - minQy;
        if (bits == null || y < 0 || y >= heightQuarts) return false;
        int i = (y << 4) | ((qz & 3) << 2) | (qx & 3);
        return (bits[i >> 6] & (1L << (i & 63))) != 0;
    }

    /** Visits the quarts of chunk column {@code column} (packed), bottom up, then by z, then x. */
    public void forEachIn(long column, QuartVisitor visitor) {
        long[] bits = chunks.get(column);
        if (bits == null) return;
        int qx0 = Regions.columnX(column) << 2, qz0 = Regions.columnZ(column) << 2;
        for (int w = 0; w < bits.length; w++) {
            for (long word = bits[w]; word != 0; word &= word - 1) {
                int i = (w << 6) + Long.numberOfTrailingZeros(word);
                visitor.visit(qx0 + (i & 3), minQy + (i >> 4), qz0 + ((i >> 2) & 3));
            }
        }
    }

    /** Visits every quart, chunk column by chunk column in ascending order. */
    public void forEach(QuartVisitor visitor) {
        for (long column : order) forEachIn(column, visitor);
    }
}
