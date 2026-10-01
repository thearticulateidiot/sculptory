package dev.sculptory.core.region;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.Sha256;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.edit.SectionOrder;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Objects;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * An immutable set of block cells (a magic selection), stored per 16³ section as a 4096-bit bitmap (64 longs, bit
 * {@code (y << 8) | (z << 4) | x} of the section) or as "full"; sections without cells are never stored. Equal sets
 * are {@link #equals} and have the same {@link #hash()} however they were built.
 *
 * <p><b>Encoding</b> ({@link #encode()}):
 * {@code "BSCS" | u8 version=1 | zlib(body)}, where body is {@code varlong cellCount | varint sectionCount | per
 * section in ascending (sx, sy, sz) order: zigzag sx, sy, sz | u8 mode (0 = full, 1 = bitmap) | mode 1: 64 big-endian
 * longs}. The encoding is canonical: a bitmap is neither empty nor full. {@link #hash()} is the SHA-256 of the
 * uncompressed body, since compressed bytes may differ between JDKs. {@link #decode} checks all of it.
 */
public final class CellSet {
    public static final int FORMAT_VERSION = 1;
    private static final byte[] MAGIC = {'B', 'S', 'C', 'S'};
    private static final int WORDS = 64;
    private static final int SECTION_CELLS = 4096;
    /** Stands for a full section in {@link #bitmaps} and {@link #lookup}. */
    private static final long[] FULL = new long[0];
    private static final CellSet EMPTY = new CellSet(new long[0], new long[0][], new Long2ObjectOpenHashMap<>(), 0, null);

    /** Decoding caps. All positive. */
    public record Limits(long maxCells, int maxSections, int maxCompressedBytes, int maxInflatedBytes) {
        /** 2,097,152 cells in at most 65,536 sections (32 MiB of bitmaps). */
        public static final Limits DEFAULT = of(2_097_152L, 1 << 16);

        public Limits {
            if (maxCells < 1 || maxSections < 1 || maxCompressedBytes < 1 || maxInflatedBytes < 1) {
                throw new IllegalArgumentException("Cell set limits must be positive");
            }
        }

        /** Caps on cells and sections, with the byte caps an encoding of that many sections can need. */
        public static Limits of(long maxCells, int maxSections) {
            long inflated = 15 + (long) maxSections * (15 + 1 + WORDS * 8);
            long compressed = inflated + inflated / 1000 + 64;
            return new Limits(maxCells, maxSections, (int) Math.min(compressed, Integer.MAX_VALUE - 8),
                    (int) Math.min(inflated, Integer.MAX_VALUE - 8));
        }
    }

    /** Section keys in {@link Region#sectionKeys()} order. */
    private final long[] keys;
    /** Each key's bitmap, or {@link #FULL}. */
    private final long[][] bitmaps;
    private final Long2ObjectOpenHashMap<long[]> lookup;
    private final long size;
    /** Null when empty. */
    private final Box bounds;
    private volatile Sha256 hash;
    private int hashCode;

    private CellSet(long[] keys, long[][] bitmaps, Long2ObjectOpenHashMap<long[]> lookup, long size, Box bounds) {
        this.keys = keys;
        this.bitmaps = bitmaps;
        this.lookup = lookup;
        this.size = size;
        this.bounds = bounds;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static CellSet empty() {
        return EMPTY;
    }

    /**
     * The cells of a region, materialized.
     *
     * @throws RegionTooLargeException if the region has more than {@code maxCells} cells (checked before any work)
     * @throws IllegalStateException for a {@link Region.Uploaded}, which has no cells here
     */
    public static CellSet of(Region region, long maxCells) {
        Objects.requireNonNull(region);
        if (region instanceof Region.Uploaded) throw new IllegalStateException("An uploaded region has no cells here");
        long cells = region.cellCount();
        if (cells > maxCells) throw new RegionTooLargeException(cells, maxCells);
        if (region instanceof Region.Cells set) return set.cells();
        return builder().addAll(region).build();
    }

    public boolean contains(int x, int y, int z) {
        if (bounds == null || !bounds.contains(x, y, z)) return false;
        long[] bitmap = lookup.get(BlockBuffer.keyOfBlock(x, y, z));
        if (bitmap == null) return false;
        if (bitmap == FULL) return true;
        int index = index(x, y, z);
        return (bitmap[index >>> 6] & (1L << index)) != 0;
    }

    public long size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /**
     * The exact bounds of the cells.
     *
     * @throws IllegalStateException if the set is empty
     */
    public Box bounds() {
        if (bounds == null) throw new IllegalStateException("An empty cell set has no bounds");
        return bounds;
    }

    /** The keys of the sections holding cells, in {@link Region#sectionKeys()} order. */
    public long[] sectionKeys() {
        return keys.clone();
    }

    /**
     * Copies the bitmap of section {@code key} into {@code into} (64 longs, bit {@code (y << 8) | (z << 4) | x}), all
     * zero when the set holds no cell there, and returns the section's cell count. For {@link Regions}.
     */
    int copySection(long key, long[] into) {
        long[] bitmap = lookup.get(key);
        if (bitmap == null) {
            Arrays.fill(into, 0, WORDS, 0L);
            return 0;
        }
        if (bitmap == FULL) {
            Arrays.fill(into, 0, WORDS, -1L);
            return SECTION_CELLS;
        }
        System.arraycopy(bitmap, 0, into, 0, WORDS);
        int cells = 0;
        for (long word : bitmap) cells += Long.bitCount(word);
        return cells;
    }

    /**
     * The same cells moved by (dx, dy, dz).
     *
     * @throws IllegalArgumentException or {@link ArithmeticException} if a cell would leave the coordinate range
     */
    public CellSet translate(int dx, int dy, int dz) {
        if (isEmpty() || (dx == 0 && dy == 0 && dz == 0)) return this;
        Box moved = bounds.offset(dx, dy, dz);
        if (((dx | dy | dz) & 15) == 0) {
            // Whole sections: the bitmaps stay as they are.
            long[] movedKeys = new long[keys.length];
            Long2ObjectOpenHashMap<long[]> movedLookup = new Long2ObjectOpenHashMap<>(keys.length);
            for (int i = 0; i < keys.length; i++) {
                movedKeys[i] = BlockBuffer.key(BlockBuffer.keyX(keys[i]) + (dx >> 4), BlockBuffer.keyY(keys[i]) + (dy >> 4),
                        BlockBuffer.keyZ(keys[i]) + (dz >> 4));
                movedLookup.put(movedKeys[i], bitmaps[i]);
            }
            return new CellSet(movedKeys, bitmaps, movedLookup, size, moved);
        }
        Builder builder = new Builder();
        for (int i = 0; i < keys.length; i++) {
            int x0 = (BlockBuffer.keyX(keys[i]) << 4) + dx;
            int y0 = (BlockBuffer.keyY(keys[i]) << 4) + dy;
            int z0 = (BlockBuffer.keyZ(keys[i]) << 4) + dz;
            for (int word = 0; word < WORDS; word++) {
                long bits = word(bitmaps[i], word);
                if (bits == 0) continue;
                for (int lane = 0; lane < 4; lane++) {
                    int row = (int) (bits >>> (lane << 4)) & 0xFFFF;
                    if (row != 0) builder.orRow(x0, y0 + (word >> 2), z0 + (((word & 3) << 2) | lane), row);
                }
            }
        }
        return builder.build();
    }

    /** The cells in this set or {@code other}. */
    public CellSet union(CellSet other) {
        Objects.requireNonNull(other);
        if (other.isEmpty()) return this;
        if (isEmpty()) return other;
        return new Builder().or(this).or(other).build();
    }

    /** The cells in this set and not in {@code other}. */
    public CellSet subtract(CellSet other) {
        Objects.requireNonNull(other);
        if (other.isEmpty() || isEmpty()) return this;
        return new Builder().or(this).andNot(other).build();
    }

    /** The SHA-256 of the uncompressed encoding body: equal for equal sets. */
    public Sha256 hash() {
        Sha256 value = hash;
        if (value == null) {
            value = Sha256.digest(body());
            hash = value;
        }
        return value;
    }

    /**
     * {@code "BSCS" | u8 version | zlib(body)}, deflated at {@link Deflater#BEST_SPEED} (bitmaps compress about as well
     * either way, several times faster). The body is built once for both: {@link #hash()} is known afterwards.
     */
    public byte[] encode() {
        byte[] body = body();
        if (hash == null) hash = Sha256.digest(body);
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try {
            deflater.setInput(body);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 + body.length / 4);
            out.write(MAGIC, 0, MAGIC.length);
            out.write(FORMAT_VERSION);
            byte[] chunk = new byte[8192];
            while (!deflater.finished()) {
                int got = deflater.deflate(chunk);
                out.write(chunk, 0, got);
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    /** Roughly the heap this set holds, for per-player accounting. */
    public long estimatedBytes() {
        long bytes = 96 + 48L * keys.length;
        for (long[] bitmap : bitmaps) {
            if (bitmap != FULL) bytes += 16 + WORDS * 8;
        }
        return bytes;
    }

    /**
     * Reads an {@link #encode()}d set, checking everything: the magic and version, the compressed and inflated sizes,
     * the counts against {@code limits}, section coordinates, their order and uniqueness, the modes, that no bitmap is
     * empty or full, that the cell count matches the sections, and that nothing follows.
     */
    public static CellSet decode(byte[] data, Limits limits) throws CellSetFormatException {
        Objects.requireNonNull(data);
        Objects.requireNonNull(limits);
        if (data.length > limits.maxCompressedBytes()) {
            throw tooLarge(data.length + " bytes over the cap of " + limits.maxCompressedBytes());
        }
        if (data.length < MAGIC.length + 1 || !Arrays.equals(data, 0, MAGIC.length, MAGIC, 0, MAGIC.length)) {
            throw malformed("Not a cell set");
        }
        if (data[MAGIC.length] != FORMAT_VERSION) throw malformed("Unsupported cell set version " + data[MAGIC.length]);
        Reader in = new Reader(inflate(data, MAGIC.length + 1, limits.maxInflatedBytes()));
        long cells = in.varlong();
        if (cells < 0) throw malformed("Negative cell count");
        if (cells > limits.maxCells()) throw tooLarge(cells + " cells over the cap of " + limits.maxCells());
        int count = in.varint();
        if (count < 0) throw malformed("Negative section count");
        if (count > limits.maxSections()) throw tooLarge(count + " sections over the cap of " + limits.maxSections());
        if (count > in.remaining() / 4) throw malformed("Truncated cell set");
        long[] keys = new long[count];
        long[][] bitmaps = new long[count][];
        long total = 0;
        long previous = 0;
        for (int i = 0; i < count; i++) {
            int sx = in.zigzag(), sy = in.zigzag(), sz = in.zigzag();
            if (sx < BlockBuffer.MIN_SECTION_XZ || sx > BlockBuffer.MAX_SECTION_XZ || sz < BlockBuffer.MIN_SECTION_XZ
                    || sz > BlockBuffer.MAX_SECTION_XZ || sy < BlockBuffer.MIN_SECTION_Y || sy > BlockBuffer.MAX_SECTION_Y) {
                throw malformed("Section out of range: " + sx + "," + sy + "," + sz);
            }
            long order = encodingOrder(sx, sy, sz);
            if (i > 0 && order <= previous) throw malformed("Sections out of order or repeated at " + sx + "," + sy + "," + sz);
            previous = order;
            keys[i] = BlockBuffer.key(sx, sy, sz);
            int mode = in.u8();
            if (mode == 0) {
                bitmaps[i] = FULL;
                total += SECTION_CELLS;
            } else if (mode == 1) {
                long[] bitmap = new long[WORDS];
                int bits = 0;
                for (int word = 0; word < WORDS; word++) {
                    bitmap[word] = in.i64();
                    bits += Long.bitCount(bitmap[word]);
                }
                if (bits == 0) throw malformed("Empty bitmap section");
                if (bits == SECTION_CELLS) throw malformed("A full section written as a bitmap");
                bitmaps[i] = bitmap;
                total += bits;
            } else {
                throw malformed("Unknown section mode " + mode);
            }
        }
        if (in.remaining() != 0) throw malformed(in.remaining() + " bytes after the last section");
        if (total != cells) throw malformed("Cell count " + cells + " does not match the sections' " + total);
        return assemble(keys, bitmaps, total);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CellSet other) || size != other.size || !Arrays.equals(keys, other.keys)) return false;
        for (int i = 0; i < keys.length; i++) {
            if ((bitmaps[i] == FULL) != (other.bitmaps[i] == FULL) || !Arrays.equals(bitmaps[i], other.bitmaps[i])) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int h = hashCode;
        if (h == 0) {
            h = Arrays.hashCode(keys);
            for (long[] bitmap : bitmaps) h = 31 * h + (bitmap == FULL ? 1 : Arrays.hashCode(bitmap));
            hashCode = h;
        }
        return h;
    }

    @Override
    public String toString() {
        return "CellSet[" + size + " cells in " + keys.length + " sections" + (bounds == null ? "" : ", " + bounds) + "]";
    }

    // =================================================================== building

    /** Collects cells; {@link #build()} makes the immutable set (the builder stays usable). Not thread-safe. */
    public static final class Builder {
        private final Long2ObjectOpenHashMap<long[]> sections = new Long2ObjectOpenHashMap<>();
        private long size;

        private Builder() {}

        /** @throws IllegalArgumentException if the cell is outside the storable range ({@link BlockBuffer#key}) */
        public Builder add(int x, int y, int z) {
            long[] bitmap = section(BlockBuffer.keyOfBlock(x, y, z));
            int index = index(x, y, z);
            long bit = 1L << index;
            if ((bitmap[index >>> 6] & bit) == 0) {
                bitmap[index >>> 6] |= bit;
                size++;
            }
            return this;
        }

        public Builder remove(int x, int y, int z) {
            if (!storable(x, y, z)) return this;
            long[] bitmap = sections.get(BlockBuffer.keyOfBlock(x, y, z));
            int index = index(x, y, z);
            long bit = 1L << index;
            if (bitmap != null && (bitmap[index >>> 6] & bit) != 0) {
                bitmap[index >>> 6] &= ~bit;
                size--;
            }
            return this;
        }

        public boolean contains(int x, int y, int z) {
            if (!storable(x, y, z)) return false;
            long[] bitmap = sections.get(BlockBuffer.keyOfBlock(x, y, z));
            int index = index(x, y, z);
            return bitmap != null && (bitmap[index >>> 6] & (1L << index)) != 0;
        }

        /** The cells added so far. */
        public long size() {
            return size;
        }

        /**
         * Adds every cell of a region (without a cap: see {@link CellSet#of}).
         *
         * @throws IllegalStateException for a {@link Region.Uploaded}
         */
        public Builder addAll(Region region) {
            switch (Objects.requireNonNull(region)) {
                case Region.Cuboid cuboid -> {
                    Box box = cuboid.box();
                    for (int z = box.min().z(); z <= box.max().z(); z++) {
                        for (int y = box.min().y(); y <= box.max().y(); y++) addRow(y, z, box.min().x(), box.max().x());
                    }
                }
                case Region.Shape shape -> {
                    Box box = shape.box();
                    for (int z = box.min().z(); z <= box.max().z(); z++) {
                        for (int y = box.min().y(); y <= box.max().y(); y++) {
                            long span = shape.rowSpan(y, z);
                            if (span != Region.Shape.EMPTY_ROW) addRow(y, z, Region.Shape.rowMin(span), Region.Shape.rowMax(span));
                        }
                    }
                }
                case Region.Cells cells -> or(cells.cells());
                case Region.Uploaded uploaded -> throw new IllegalStateException("An uploaded region has no cells here");
            }
            return this;
        }

        public CellSet build() {
            if (size == 0) return EMPTY;
            long[] keys = new long[sections.size()];
            long[][] bitmaps = new long[sections.size()][];
            int n = 0;
            for (Long2ObjectMap.Entry<long[]> entry : sections.long2ObjectEntrySet()) {
                int bits = 0;
                for (long word : entry.getValue()) bits += Long.bitCount(word);
                if (bits == 0) continue;
                keys[n] = entry.getLongKey();
                bitmaps[n++] = bits == SECTION_CELLS ? FULL : entry.getValue().clone();
            }
            return assemble(Arrays.copyOf(keys, n), Arrays.copyOf(bitmaps, n), size);
        }

        private long[] section(long key) {
            long[] bitmap = sections.get(key);
            if (bitmap == null) {
                bitmap = new long[WORDS];
                sections.put(key, bitmap);
            }
            return bitmap;
        }

        /** Adds x from {@code fromX} to {@code toX} of row (y, z). */
        private void addRow(int y, int z, int fromX, int toX) {
            for (int sx = fromX >> 4; sx <= toX >> 4; sx++) {
                int lo = Math.max(fromX, sx << 4) & 15, hi = Math.min(toX, (sx << 4) + 15) & 15;
                orMask(BlockBuffer.key(sx, y >> 4, z >> 4), y, z, ((1 << (hi - lo + 1)) - 1) << lo);
            }
        }

        /** Adds the 16 cells of {@code row} (bit i: x = x0 + i) of row (y, z); x0 need not be section-aligned. */
        void orRow(int x0, int y, int z, int row) {
            int shift = x0 & 15;
            orMask(BlockBuffer.keyOfBlock(x0, y, z), y, z, (row << shift) & 0xFFFF);
            int high = shift == 0 ? 0 : row >>> (16 - shift);
            if (high != 0) orMask(BlockBuffer.keyOfBlock(Math.addExact(x0, 16), y, z), y, z, high);
        }

        /** ORs a 16-bit x mask into row (y, z) of section {@code key}. */
        private void orMask(long key, int y, int z, int mask) {
            if (mask == 0) return;
            long[] bitmap = section(key);
            int word = ((y & 15) << 2) | ((z & 15) >> 2);
            long bits = (long) mask << ((z & 3) << 4);
            size += Long.bitCount(bits & ~bitmap[word]);
            bitmap[word] |= bits;
        }

        Builder or(CellSet set) {
            for (int i = 0; i < set.keys.length; i++) {
                long[] bitmap = section(set.keys[i]);
                for (int word = 0; word < WORDS; word++) {
                    long bits = word(set.bitmaps[i], word);
                    size += Long.bitCount(bits & ~bitmap[word]);
                    bitmap[word] |= bits;
                }
            }
            return this;
        }

        Builder andNot(CellSet set) {
            for (int i = 0; i < set.keys.length; i++) {
                long[] bitmap = sections.get(set.keys[i]);
                if (bitmap == null) continue;
                for (int word = 0; word < WORDS; word++) {
                    long bits = word(set.bitmaps[i], word);
                    size -= Long.bitCount(bits & bitmap[word]);
                    bitmap[word] &= ~bits;
                }
            }
            return this;
        }
    }

    // =================================================================== internals

    /** Orders the sections, computes the bounds and indexes the bitmaps. */
    private static CellSet assemble(long[] keys, long[][] bitmaps, long size) {
        if (size == 0) return EMPTY;
        Long2ObjectOpenHashMap<long[]> lookup = new Long2ObjectOpenHashMap<>(keys.length);
        for (int i = 0; i < keys.length; i++) lookup.put(keys[i], bitmaps[i]);
        long[] ordered = keys.clone();
        SectionOrder.sort(ordered);
        long[][] orderedBitmaps = new long[ordered.length][];
        for (int i = 0; i < ordered.length; i++) orderedBitmaps[i] = lookup.get(ordered[i]);
        return new CellSet(ordered, orderedBitmaps, lookup, size, bounds(ordered, orderedBitmaps));
    }

    /** Exact bounds: only the sections on the outer section planes are looked into. */
    private static Box bounds(long[] keys, long[][] bitmaps) {
        int minSx = Integer.MAX_VALUE, minSy = Integer.MAX_VALUE, minSz = Integer.MAX_VALUE;
        int maxSx = Integer.MIN_VALUE, maxSy = Integer.MIN_VALUE, maxSz = Integer.MIN_VALUE;
        for (long key : keys) {
            minSx = Math.min(minSx, BlockBuffer.keyX(key));
            maxSx = Math.max(maxSx, BlockBuffer.keyX(key));
            minSy = Math.min(minSy, BlockBuffer.keyY(key));
            maxSy = Math.max(maxSy, BlockBuffer.keyY(key));
            minSz = Math.min(minSz, BlockBuffer.keyZ(key));
            maxSz = Math.max(maxSz, BlockBuffer.keyZ(key));
        }
        int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] max = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (int i = 0; i < keys.length; i++) {
            int sx = BlockBuffer.keyX(keys[i]), sy = BlockBuffer.keyY(keys[i]), sz = BlockBuffer.keyZ(keys[i]);
            boolean[] low = {sx == minSx, sy == minSy, sz == minSz};
            boolean[] high = {sx == maxSx, sy == maxSy, sz == maxSz};
            if (!(low[0] || low[1] || low[2] || high[0] || high[1] || high[2])) continue;
            int[] extent = extent(bitmaps[i]);
            int[] base = {sx << 4, sy << 4, sz << 4};
            for (int axis = 0; axis < 3; axis++) {
                if (low[axis]) min[axis] = Math.min(min[axis], base[axis] + extent[2 * axis]);
                if (high[axis]) max[axis] = Math.max(max[axis], base[axis] + extent[2 * axis + 1]);
            }
        }
        return new Box(new BlockPos(min[0], min[1], min[2]),
                new BlockPos(max[0], max[1], max[2]));
    }

    /** A section's local extent: {minX, maxX, minY, maxY, minZ, maxZ}. */
    private static int[] extent(long[] bitmap) {
        if (bitmap == FULL) return new int[] {0, 15, 0, 15, 0, 15};
        int minY = 16, maxY = -1, xMask = 0, zMask = 0;
        for (int word = 0; word < WORDS; word++) {
            long bits = bitmap[word];
            if (bits == 0) continue;
            minY = Math.min(minY, word >> 2);
            maxY = Math.max(maxY, word >> 2);
            for (int lane = 0; lane < 4; lane++) {
                int row = (int) (bits >>> (lane << 4)) & 0xFFFF;
                if (row == 0) continue;
                xMask |= row;
                zMask |= 1 << (((word & 3) << 2) | lane);
            }
        }
        return new int[] {Integer.numberOfTrailingZeros(xMask), 31 - Integer.numberOfLeadingZeros(xMask), minY, maxY,
                Integer.numberOfTrailingZeros(zMask), 31 - Integer.numberOfLeadingZeros(zMask)};
    }

    /** The uncompressed body; see the class comment. */
    private byte[] body() {
        long[] order = new long[keys.length];
        for (int i = 0; i < keys.length; i++) {
            order[i] = encodingOrder(BlockBuffer.keyX(keys[i]), BlockBuffer.keyY(keys[i]), BlockBuffer.keyZ(keys[i]));
        }
        Arrays.sort(order);
        ByteArrayOutputStream out = new ByteArrayOutputStream(16 + keys.length * 16);
        writeVarlong(out, size);
        writeVarlong(out, keys.length);
        for (long entry : order) {
            int sx = (int) (entry >> 42);
            int sy = (int) ((entry >>> 22) & 0xFFFFF) + BlockBuffer.MIN_SECTION_Y;
            int sz = (int) (entry & 0x3FFFFF) + BlockBuffer.MIN_SECTION_XZ;
            writeVarlong(out, zigzag(sx));
            writeVarlong(out, zigzag(sy));
            writeVarlong(out, zigzag(sz));
            long[] bitmap = lookup.get(BlockBuffer.key(sx, sy, sz));
            if (bitmap == FULL) {
                out.write(0);
                continue;
            }
            out.write(1);
            for (long word : bitmap) {
                for (int shift = 56; shift >= 0; shift -= 8) out.write((int) (word >>> shift));
            }
        }
        return out.toByteArray();
    }

    /** A signed-sortable key ordering sections by (sx, sy, sz), the encoding order. */
    private static long encodingOrder(int sx, int sy, int sz) {
        return ((long) sx << 42) | ((long) (sy - BlockBuffer.MIN_SECTION_Y) << 22) | (sz - BlockBuffer.MIN_SECTION_XZ);
    }

    private static int index(int x, int y, int z) {
        return ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
    }

    private static long word(long[] bitmap, int word) {
        return bitmap == FULL ? -1L : bitmap[word];
    }

    private static boolean storable(int x, int y, int z) {
        int sx = x >> 4, sy = y >> 4, sz = z >> 4;
        return sx >= BlockBuffer.MIN_SECTION_XZ && sx <= BlockBuffer.MAX_SECTION_XZ && sz >= BlockBuffer.MIN_SECTION_XZ
                && sz <= BlockBuffer.MAX_SECTION_XZ && sy >= BlockBuffer.MIN_SECTION_Y && sy <= BlockBuffer.MAX_SECTION_Y;
    }

    private static long zigzag(int value) {
        return ((value << 1) ^ (value >> 31)) & 0xFFFFFFFFL;
    }

    private static void writeVarlong(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7FL) != 0) {
            out.write((int) (value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write((int) value);
    }

    private static byte[] inflate(byte[] data, int offset, int max) throws CellSetFormatException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(data, offset, data.length - offset);
            byte[] out = new byte[Math.min(max, 64 << 10)];
            int got = 0;
            while (!inflater.finished()) {
                if (got == out.length) {
                    if (out.length == max) {
                        if (inflater.inflate(new byte[1]) > 0) throw tooLarge("Inflates past " + max + " bytes");
                        if (inflater.finished()) break;
                        throw malformed("Truncated compressed body");
                    }
                    out = Arrays.copyOf(out, (int) Math.min(max, 2L * out.length));
                }
                int n = inflater.inflate(out, got, out.length - got);
                if (n == 0 && !inflater.finished()) throw malformed("Truncated compressed body");
                got += n;
            }
            if (inflater.getRemaining() != 0) throw malformed("Bytes after the compressed body");
            return Arrays.copyOf(out, got);
        } catch (DataFormatException e) {
            throw malformed("Damaged compressed body: " + e.getMessage());
        } finally {
            inflater.end();
        }
    }

    private static CellSetFormatException malformed(String message) {
        return new CellSetFormatException(message, false);
    }

    private static CellSetFormatException tooLarge(String message) {
        return new CellSetFormatException(message, true);
    }

    /** Bounds-checked reads of the inflated body. */
    private static final class Reader {
        private final byte[] data;
        private int position;

        Reader(byte[] data) {
            this.data = data;
        }

        int remaining() {
            return data.length - position;
        }

        int u8() throws CellSetFormatException {
            if (position >= data.length) throw malformed("Truncated cell set");
            return data[position++] & 0xFF;
        }

        long i64() throws CellSetFormatException {
            if (remaining() < 8) throw malformed("Truncated cell set");
            long value = 0;
            for (int i = 0; i < 8; i++) value = (value << 8) | (data[position++] & 0xFF);
            return value;
        }

        long varlong() throws CellSetFormatException {
            long value = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                int b = u8();
                if (shift == 63 && (b & 0x7E) != 0) throw malformed("Varint too long");
                if (shift > 0 && b == 0) throw malformed("Varint not in its shortest form");
                value |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) return value;
            }
            throw malformed("Varint too long");
        }

        int varint() throws CellSetFormatException {
            long value = varlong();
            if (value < 0 || value > Integer.MAX_VALUE) throw malformed("Count out of range");
            return (int) value;
        }

        int zigzag() throws CellSetFormatException {
            long value = varlong();
            if (value < 0 || value > 0xFFFFFFFFL) throw malformed("Coordinate out of range");
            int raw = (int) value;
            return (raw >>> 1) ^ -(raw & 1);
        }
    }
}
