package dev.sculptory.core.region;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.edit.SectionOrder;
import dev.sculptory.core.transform.Mirror;
import dev.sculptory.core.transform.Transform;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.Arrays;
import java.util.Objects;

/**
 * Section-wise views of a {@link Region} for the engine: a section's cells as
 * rows, the cells and sections within a range of heights, the chunk columns holding cells, and where a move puts a
 * region. None of them visits a region's bounding box cell by cell: a cuboid is worked out from its box, a shape from
 * its {@link Region.Shape#rowSpan row spans} (one per row asked for), a cell set from its bitmaps. A
 * {@link Region.Uploaded} has no cells here ({@link IllegalStateException}).
 *
 * <p><b>Rows.</b> A section's cells are 256 rows of 16 bits: {@code rows[(ly << 4) | lz]} holds bit {@code lx} for the
 * cell at local (lx, ly, lz), as {@code SectionBuffer.index} orders cells.
 */
public final class Regions {
    /** Rows per section. */
    public static final int ROWS = 256;
    private static final int FULL_ROW = 0xFFFF;

    private Regions() {}

    /** The rows of section {@code key} ({@link #ROWS} ints, overwritten); returns the section's cell count. */
    public static int rows(Region region, long key, int[] rows) {
        return rows(region, key, Integer.MIN_VALUE, Integer.MAX_VALUE, rows);
    }

    /**
     * The rows of section {@code key} keeping only the cells with y in {@code [minY, maxY]} ({@link #ROWS} ints,
     * overwritten); returns their count.
     */
    public static int rows(Region region, long key, int minY, int maxY, int[] rows) {
        Objects.requireNonNull(region);
        if (rows.length < ROWS) throw new IllegalArgumentException("Rows need " + ROWS + " ints");
        Arrays.fill(rows, 0, ROWS, 0);
        int ox = BlockBuffer.keyX(key) << 4, oy = BlockBuffer.keyY(key) << 4, oz = BlockBuffer.keyZ(key) << 4;
        int y0 = Math.max(0, clampLocal(minY, oy)), y1 = Math.min(15, clampLocal(maxY, oy));
        if (y0 > y1) return 0;
        return switch (region) {
            case Region.Cuboid cuboid -> cuboidRows(cuboid.box(), ox, oy, oz, y0, y1, rows);
            case Region.Shape shape -> shapeRows(shape, ox, oy, oz, y0, y1, rows);
            case Region.Cells cells -> cellRows(cells.cells(), key, y0, y1, rows);
            case Region.Uploaded uploaded -> throw unresolved();
        };
    }

    /** The cells with y in {@code [minY, maxY]}, exact and saturating. */
    public static long cellsBetween(Region region, int minY, int maxY) {
        return cellsBetween(region, minY, maxY, Long.MAX_VALUE);
    }

    /**
     * The cells with y in {@code [minY, maxY]}: exact when at most {@code cap}, otherwise some count above {@code cap}
     * (counting stops there). A cuboid and a cell set wholly inside the heights answer at once; a shape is counted row
     * by row along its box's longest side (the fewest rows), stopping once past the cap, so a request over a limit
     * costs little however large its box.
     */
    public static long cellsBetween(Region region, int minY, int maxY, long cap) {
        Objects.requireNonNull(region);
        Box bounds = region.bounds();
        if (minY > maxY || bounds.max().y() < minY || bounds.min().y() > maxY) {
            if (region instanceof Region.Uploaded) throw unresolved();
            return 0;
        }
        return switch (region) {
            case Region.Cuboid cuboid -> clip(cuboid.box(), minY, maxY).volume();
            case Region.Shape shape -> scanShape(shape, minY, maxY, cap, null, 0);
            case Region.Cells cells -> {
                if (bounds.min().y() >= minY && bounds.max().y() <= maxY) yield cells.cells().size();
                long count = 0;
                long[] bitmap = new long[64];
                for (long key : cells.cells().sectionKeys()) {
                    int sy = BlockBuffer.keyY(key);
                    if ((sy << 4) + 15 < minY || (sy << 4) > maxY) continue;
                    cells.cells().copySection(key, bitmap);
                    count += layerCells(bitmap, clampLocal(minY, sy << 4), clampLocal(maxY, sy << 4));
                    if (count > cap) break;
                }
                yield count;
            }
            case Region.Uploaded uploaded -> throw unresolved();
        };
    }

    /**
     * The {@link BlockBuffer#key} of every section holding a cell with y in {@code [minY, maxY]}, in
     * {@link SectionOrder} order.
     */
    public static long[] sectionKeysBetween(Region region, int minY, int maxY) {
        return sectionKeysBetween(region, minY, maxY, Long.MAX_VALUE);
    }

    /**
     * {@link #sectionKeysBetween(Region, int, int)}, refusing a region with more than {@code maxSections} of them as soon
     * as that shows ({@link RegionTooLargeException}), before listing them all.
     */
    public static long[] sectionKeysBetween(Region region, int minY, int maxY, long maxSections) {
        Objects.requireNonNull(region);
        Box bounds = region.bounds();
        if (minY > maxY || bounds.max().y() < minY || bounds.min().y() > maxY) {
            if (region instanceof Region.Uploaded) throw unresolved();
            return new long[0];
        }
        return switch (region) {
            case Region.Cuboid cuboid -> {
                Box box = clip(cuboid.box(), minY, maxY);
                long sections = SectionOrder.sectionCount(box);
                if (sections > maxSections) throw tooManySections(sections, maxSections);
                LongArrayList keys = new LongArrayList((int) Math.min(sections, 1 << 20));
                box.forEachSectionKey(keys::add);
                yield keys.toLongArray();
            }
            case Region.Shape shape -> {
                LongArrayList keys = new LongArrayList();
                scanShape(shape, minY, maxY, Long.MAX_VALUE, keys, maxSections);
                long[] sorted = keys.toLongArray();
                SectionOrder.sort(sorted);
                yield sorted;
            }
            case Region.Cells cells -> {
                LongArrayList keys = new LongArrayList();
                long[] bitmap = new long[64];
                for (long key : cells.cells().sectionKeys()) {
                    int oy = BlockBuffer.keyY(key) << 4;
                    if (oy + 15 < minY || oy > maxY) continue;
                    if (oy < minY || oy + 15 > maxY) {
                        cells.cells().copySection(key, bitmap);
                        if (layerCells(bitmap, clampLocal(minY, oy), clampLocal(maxY, oy)) == 0) continue;
                    }
                    keys.add(key);
                    if (keys.size() > maxSections) throw tooManySections(keys.size(), maxSections);
                }
                yield keys.toLongArray();
            }
            case Region.Uploaded uploaded -> throw unresolved();
        };
    }

    /**
     * The chunk columns holding a cell with y in {@code [minY, maxY]}: per chunk ({@code EditProgram.column} packing,
     * x in the low 32 bits), 4 longs with bit {@code ((z & 15) << 4) | (x & 15)} set for each column (x, z) holding one.
     */
    public static Long2ObjectOpenHashMap<long[]> columns(Region region, int minY, int maxY) {
        return columns(region, minY, maxY, Integer.MAX_VALUE);
    }

    /**
     * {@link #columns(Region, int, int)}, refusing a region reaching more than {@code maxChunks} chunk columns as soon as
     * that shows ({@link RegionTooLargeException}). A cell set's columns are OR-ed from its bitmaps' words.
     */
    public static Long2ObjectOpenHashMap<long[]> columns(Region region, int minY, int maxY, int maxChunks) {
        Objects.requireNonNull(region);
        Long2ObjectOpenHashMap<long[]> columns = new Long2ObjectOpenHashMap<>();
        Box bounds = region.bounds();
        if (minY > maxY || bounds.max().y() < minY || bounds.min().y() > maxY) {
            if (region instanceof Region.Uploaded) throw unresolved();
            return columns;
        }
        switch (region) {
            case Region.Cuboid cuboid -> {
                Box box = cuboid.box();
                long chunks = ((long) (box.max().x() >> 4) - (box.min().x() >> 4) + 1)
                        * ((long) (box.max().z() >> 4) - (box.min().z() >> 4) + 1);
                if (chunks > maxChunks) throw tooManyColumns(chunks, maxChunks);
                for (int cx = box.min().x() >> 4; cx <= box.max().x() >> 4; cx++) {
                    int x0 = Math.max(box.min().x(), cx << 4) & 15, x1 = Math.min(box.max().x(), (cx << 4) + 15) & 15;
                    for (int cz = box.min().z() >> 4; cz <= box.max().z() >> 4; cz++) {
                        int z0 = Math.max(box.min().z(), cz << 4) & 15, z1 = Math.min(box.max().z(), (cz << 4) + 15) & 15;
                        long[] bits = columns.computeIfAbsent(column(cx, cz), c -> new long[4]);
                        for (int z = z0; z <= z1; z++) {
                            for (int x = x0; x <= x1; x++) setColumn(bits, x, z);
                        }
                    }
                }
            }
            case Region.Shape shape -> {
                long perColumn = (long) (Math.min(maxY, bounds.max().y()) >> 4) - (Math.max(minY, bounds.min().y()) >> 4) + 1;
                long maxSections = Math.min(Long.MAX_VALUE / Math.max(1, perColumn), maxChunks) * perColumn;
                int[] rows = new int[ROWS];
                for (long key : sectionKeysBetween(region, minY, maxY, maxSections)) {
                    if (rows(region, key, minY, maxY, rows) == 0) continue;
                    long[] bits = chunkOf(columns, key, maxChunks);
                    for (int lz = 0; lz < 16; lz++) {
                        int across = 0;
                        for (int ly = 0; ly < 16; ly++) across |= rows[(ly << 4) | lz];
                        // Row lz of the chunk's 16 x 16 columns is bits (lz << 4) .. (lz << 4) + 15.
                        bits[lz >> 2] |= (long) across << ((lz & 3) << 4);
                    }
                }
            }
            case Region.Cells cells -> {
                // Bitmap word (ly << 2) | g holds the rows z = 4g .. 4g + 3 of layer ly: OR-ed over the layers, they
                // are column word g (bit ((z & 3) << 4) | x) of the chunk.
                long[] bitmap = new long[64];
                for (long key : cells.cells().sectionKeys()) {
                    int oy = BlockBuffer.keyY(key) << 4;
                    if (oy + 15 < minY || oy > maxY) continue;
                    cells.cells().copySection(key, bitmap);
                    int y0 = Math.max(0, clampLocal(minY, oy)), y1 = Math.min(15, clampLocal(maxY, oy));
                    long[] across = new long[4];
                    boolean any = false;
                    for (int ly = y0; ly <= y1; ly++) {
                        for (int g = 0; g < 4; g++) {
                            across[g] |= bitmap[(ly << 2) | g];
                            any |= bitmap[(ly << 2) | g] != 0;
                        }
                    }
                    if (!any) continue;
                    long[] bits = chunkOf(columns, key, maxChunks);
                    for (int g = 0; g < 4; g++) bits[g] |= across[g];
                }
            }
            case Region.Uploaded uploaded -> throw unresolved();
        }
        return columns;
    }

    /** The column words of the chunk holding section {@code key}, added if new (refused past {@code maxChunks}). */
    private static long[] chunkOf(Long2ObjectOpenHashMap<long[]> columns, long key, int maxChunks) {
        long chunk = column(BlockBuffer.keyX(key), BlockBuffer.keyZ(key));
        long[] bits = columns.get(chunk);
        if (bits == null) {
            if (columns.size() >= maxChunks) throw tooManyColumns(columns.size() + 1L, maxChunks);
            bits = new long[4];
            columns.put(chunk, bits);
        }
        return bits;
    }

    /**
     * {@link #columns} moved like the cells of a region by {@link #moved}: each column (x, z) inside {@code pivot}'s x
     * and z range lands on {@code destinationMin} plus its transformed offset from {@code pivot}'s minimum corner.
     */
    public static Long2ObjectOpenHashMap<long[]> movedColumns(Long2ObjectMap<long[]> columns, Box pivot,
                                                              BlockPos destinationMin, Transform t) {
        Objects.requireNonNull(pivot);
        Objects.requireNonNull(destinationMin);
        Objects.requireNonNull(t);
        int sx = pivot.sizeX(), sz = pivot.sizeZ();
        Long2ObjectOpenHashMap<long[]> moved = new Long2ObjectOpenHashMap<>();
        for (Long2ObjectMap.Entry<long[]> entry : columns.long2ObjectEntrySet()) {
            int cx = columnX(entry.getLongKey()), cz = columnZ(entry.getLongKey());
            long[] bits = entry.getValue();
            for (int bit = 0; bit < 256; bit++) {
                if ((bits[bit >>> 6] & (1L << bit)) == 0) continue;
                int lx = (cx << 4) + (bit & 15) - pivot.min().x(), lz = (cz << 4) + (bit >>> 4) - pivot.min().z();
                int x = destinationMin.x() + t.mapX(lx, lz, sx, sz), z = destinationMin.z() + t.mapZ(lx, lz, sx, sz);
                setColumn(moved.computeIfAbsent(column(x >> 4, z >> 4), c -> new long[4]), x & 15, z & 15);
            }
        }
        return moved;
    }

    /**
     * Where a move puts {@code region}'s cells: {@code pivot} is the box the transform turns (its x and z range must
     * hold every cell; its y range matters only to a flip upside down, which turns the cells over within it), and it
     * lands with its minimum corner at {@code destinationMin}: y moves by {@code destinationMin.y() - pivot.min().y()},
     * or, flipped, becomes {@code destinationMin.y() + pivot.max().y() - y}. A cuboid or shape stays one (a turned or
     * flipped shape keeps its voxelization exactly: shapes are symmetric under the box's reflections and
     * transpositions); a cell set is moved cell by cell, or by whole bitmaps without a turn, mirror or flip.
     *
     * @throws IllegalArgumentException if a cell would leave the coordinate range
     */
    public static Region moved(Region region, Box pivot, BlockPos destinationMin, Transform t) {
        Objects.requireNonNull(region);
        Objects.requireNonNull(pivot);
        Objects.requireNonNull(destinationMin);
        Objects.requireNonNull(t);
        int dy = Math.subtractExact(destinationMin.y(), pivot.min().y());
        return switch (region) {
            case Region.Cuboid cuboid -> new Region.Cuboid(movedBox(cuboid.box(), pivot, destinationMin, t));
            case Region.Shape shape -> new Region.Shape(movedBox(shape.box(), pivot, destinationMin, t), shape.kind(),
                    moved(shape.facing(), t));
            case Region.Cells cells -> {
                if (t.isIdentity()) {
                    yield new Region.Cells(cells.cells().translate(Math.subtractExact(destinationMin.x(), pivot.min().x()),
                            dy, Math.subtractExact(destinationMin.z(), pivot.min().z())));
                }
                CellSet.Builder builder = CellSet.builder();
                int sx = pivot.sizeX(), sz = pivot.sizeZ();
                int[] rows = new int[ROWS];
                for (long key : cells.cells().sectionKeys()) {
                    rows(region, key, rows);
                    int ox = BlockBuffer.keyX(key) << 4, oy = BlockBuffer.keyY(key) << 4, oz = BlockBuffer.keyZ(key) << 4;
                    for (int r = 0; r < ROWS; r++) {
                        int lz = (oz + (r & 15)) - pivot.min().z();
                        int y = t.upsideDown()
                                ? Math.addExact(destinationMin.y(), Math.subtractExact(pivot.max().y(), oy + (r >>> 4)))
                                : Math.addExact(oy + (r >>> 4), dy);
                        for (int bits = rows[r]; bits != 0; bits &= bits - 1) {
                            int lx = ox + Integer.numberOfTrailingZeros(bits) - pivot.min().x();
                            builder.add(destinationMin.x() + t.mapX(lx, lz, sx, sz), y,
                                    destinationMin.z() + t.mapZ(lx, lz, sx, sz));
                        }
                    }
                }
                yield new Region.Cells(builder.build());
            }
            case Region.Uploaded uploaded -> throw unresolved();
        };
    }

    /**
     * The image of {@code region}'s cells under {@code image} about {@code symmetry}'s centre: a cuboid is the cuboid of its box's image, a shape the shape of its box's
     * image with its facing mirrored or turned (exactly its cells' images, since the voxelization is symmetric under
     * the box's reflections and transpositions), and a cell set is mapped cell by cell.
     *
     * @throws IllegalArgumentException if a cell's image leaves the coordinate range
     */
    public static Region image(Region region, Symmetry symmetry, Symmetry.Image image) {
        Objects.requireNonNull(region);
        Objects.requireNonNull(symmetry);
        Objects.requireNonNull(image);
        if (image == Symmetry.Image.IDENTITY) return region;
        return switch (region) {
            case Region.Cuboid cuboid -> new Region.Cuboid(symmetry.imageBox(image, cuboid.box()));
            case Region.Shape shape -> new Region.Shape(symmetry.imageBox(image, shape.box()), shape.kind(),
                    Symmetry.imageFacing(image, shape.facing()));
            case Region.Cells cells -> {
                symmetry.imageBox(image, cells.bounds()); // refuses a set whose image leaves the coordinate range
                CellSet.Builder builder = CellSet.builder();
                int[] rows = new int[ROWS];
                for (long key : cells.cells().sectionKeys()) {
                    rows(region, key, rows);
                    int ox = BlockBuffer.keyX(key) << 4, oy = BlockBuffer.keyY(key) << 4, oz = BlockBuffer.keyZ(key) << 4;
                    for (int r = 0; r < ROWS; r++) {
                        int y = oy + (r >>> 4), z = oz + (r & 15);
                        for (int bits = rows[r]; bits != 0; bits &= bits - 1) {
                            int x = ox + Integer.numberOfTrailingZeros(bits);
                            builder.add((int) symmetry.cellX(image, x, z), y, (int) symmetry.cellZ(image, x, z));
                        }
                    }
                }
                yield new Region.Cells(builder.build());
            }
            case Region.Uploaded uploaded -> throw unresolved();
        };
    }

    /**
     * The direction a facing points after {@code t}: a mirror flips its axis, then each quarter turn goes clockwise; the
     * flip upside down swaps up and down.
     */
    public static Facing moved(Facing facing, Transform t) {
        Objects.requireNonNull(facing);
        if (facing.axis() == 1) return t.upsideDown() ? facing.opposite() : facing;
        Facing f = facing;
        if (t.mirror() == Mirror.X && f.axis() == 0) f = f.opposite();
        if (t.mirror() == Mirror.Z && f.axis() == 2) f = f.opposite();
        for (int turn = 0; turn < t.quarterTurnsCw(); turn++) {
            f = switch (f) {
                case NORTH -> Facing.EAST;
                case EAST -> Facing.SOUTH;
                case SOUTH -> Facing.WEST;
                case WEST -> Facing.NORTH;
                case UP, DOWN -> f;
            };
        }
        return f;
    }

    /** Packs chunk column (cx, cz) like {@code ChunkPos.toLong}: x in the low 32 bits, z in the high 32. */
    public static long column(int cx, int cz) {
        return (cx & 0xFFFFFFFFL) | ((cz & 0xFFFFFFFFL) << 32);
    }

    public static int columnX(long column) {
        return (int) column;
    }

    public static int columnZ(long column) {
        return (int) (column >>> 32);
    }

    // =================================================================== internals

    private static int cuboidRows(Box box, int ox, int oy, int oz, int y0, int y1, int[] rows) {
        int x0 = Math.max(box.min().x(), ox) - ox, x1 = Math.min(box.max().x(), ox + 15) - ox;
        int ly0 = Math.max(y0, Math.max(box.min().y(), oy) - oy), ly1 = Math.min(y1, Math.min(box.max().y(), oy + 15) - oy);
        int z0 = Math.max(box.min().z(), oz) - oz, z1 = Math.min(box.max().z(), oz + 15) - oz;
        if (x0 > x1 || ly0 > ly1 || z0 > z1) return 0;
        int mask = span(x0, x1);
        for (int ly = ly0; ly <= ly1; ly++) {
            for (int lz = z0; lz <= z1; lz++) rows[(ly << 4) | lz] = mask;
        }
        return (x1 - x0 + 1) * (ly1 - ly0 + 1) * (z1 - z0 + 1);
    }

    private static int shapeRows(Region.Shape shape, int ox, int oy, int oz, int y0, int y1, int[] rows) {
        Box box = shape.box();
        if (box.max().x() < ox || box.min().x() > ox + 15) return 0;
        int ly0 = Math.max(y0, Math.max(box.min().y(), oy) - oy), ly1 = Math.min(y1, Math.min(box.max().y(), oy + 15) - oy);
        int z0 = Math.max(box.min().z(), oz) - oz, z1 = Math.min(box.max().z(), oz + 15) - oz;
        int cells = 0;
        for (int ly = ly0; ly <= ly1; ly++) {
            for (int lz = z0; lz <= z1; lz++) {
                long rowSpan = shape.rowSpan(oy + ly, oz + lz);
                if (rowSpan == Region.Shape.EMPTY_ROW) continue;
                int from = Math.max(Region.Shape.rowMin(rowSpan), ox), to = Math.min(Region.Shape.rowMax(rowSpan), ox + 15);
                if (from > to) continue;
                rows[(ly << 4) | lz] = span(from - ox, to - ox);
                cells += to - from + 1;
            }
        }
        return cells;
    }

    private static int cellRows(CellSet cells, long key, int y0, int y1, int[] rows) {
        long[] bitmap = new long[64];
        if (cells.copySection(key, bitmap) == 0) return 0;
        int count = 0;
        for (int ly = y0; ly <= y1; ly++) {
            for (int lz = 0; lz < 16; lz++) {
                int row = (int) (bitmap[(ly << 2) | (lz >> 2)] >>> ((lz & 3) << 4)) & FULL_ROW;
                rows[(ly << 4) | lz] = row;
                count += Integer.bitCount(row);
            }
        }
        return count;
    }

    /** The cells of layers {@code y0..y1} (local, possibly outside 0..15) of a section bitmap. */
    private static long layerCells(long[] bitmap, int y0, int y1) {
        long count = 0;
        for (int ly = Math.max(0, y0); ly <= Math.min(15, y1); ly++) {
            for (int word = ly << 2; word < (ly << 2) + 4; word++) count += Long.bitCount(bitmap[word]);
        }
        return count;
    }

    /**
     * The rows (lines of cells along one axis) a count or listing of the shape within {@code [minY, maxY]} goes over:
     * the product of the two shorter sides of its box cut to those heights ({@link #scanShape} runs along the longest).
     */
    public static long shapeRows(Region.Shape shape, int minY, int maxY) {
        Box box = shape.box();
        long[] extent = {box.sizeX(), (long) Math.min(box.max().y(), maxY) - Math.max(box.min().y(), minY) + 1, box.sizeZ()};
        if (extent[1] <= 0) return 0;
        Arrays.sort(extent);
        return extent[0] * extent[1];
    }

    /**
     * One pass over the shape's cells with y in {@code [minY, maxY]}, row by row along the longest side of its box (cut
     * to those heights), so it takes the fewest rows ({@link #shapeRows}). The rows along another axis come from the
     * same voxelization with the box's axes relabelled ({@link ShapeMath} is exactly symmetric under that), so they
     * agree with {@link Region.Shape#contains}. Counts the cells, stopping once past {@code cap}; when {@code keys} is
     * given, adds the key of every section they reach (per 16 × 16 slab of rows, the sections its rows span along the
     * long side, merged), refusing more than {@code maxSections} as soon as they are listed.
     */
    private static long scanShape(Region.Shape shape, int minY, int maxY, long cap, LongArrayList keys, long maxSections) {
        Box box = shape.box();
        int[] lo = {box.min().x(), Math.max(box.min().y(), minY), box.min().z()};
        int[] hi = {box.max().x(), Math.min(box.max().y(), maxY), box.max().z()};
        if (lo[1] > hi[1]) return 0;
        int[] base = {box.min().x(), box.min().y(), box.min().z()};
        long[] size = {box.sizeX(), box.sizeY(), box.sizeZ()};
        // The long side l runs along the rows; m and n are the other two.
        int l = 0;
        for (int axis = 1; axis < 3; axis++) {
            if ((long) hi[axis] - lo[axis] > (long) hi[l] - lo[l]) l = axis;
        }
        int m = l == 0 ? 1 : 0, n = l == 2 ? 1 : 2;
        Facing facing = shape.facing();
        int f = facing.axis() == l ? 0 : facing.axis() == m ? 1 : 2;
        ShapeMath math = new ShapeMath(shape.kind(), facing(f, facing.sign()), size[l], size[m], size[n], false);
        long count = 0;
        long[] ranges = keys == null ? null : new long[ROWS];
        int[] section = new int[3];
        for (int sn = lo[n] >> 4; sn <= hi[n] >> 4; sn++) {
            int nFrom = Math.max(lo[n], sn << 4), nTo = Math.min(hi[n], (sn << 4) + 15);
            for (int sm = lo[m] >> 4; sm <= hi[m] >> 4; sm++) {
                int mFrom = Math.max(lo[m], sm << 4), mTo = Math.min(hi[m], (sm << 4) + 15);
                int found = 0;
                for (int b = nFrom; b <= nTo; b++) {
                    for (int a = mFrom; a <= mTo; a++) {
                        long span = math.rowSpan((long) a - base[m], (long) b - base[n]);
                        if (span == ShapeMath.EMPTY) continue;
                        int from = Math.max(lo[l], base[l] + ShapeMath.min(span));
                        int to = Math.min(hi[l], base[l] + ShapeMath.max(span));
                        if (from > to) continue;
                        long cells = (long) to - from + 1;
                        count = count > Long.MAX_VALUE - cells ? Long.MAX_VALUE : count + cells;
                        if (count > cap) return count;
                        if (ranges != null) ranges[found++] = ((long) (from >> 4) << 32) | ((to >> 4) & 0xFFFFFFFFL);
                    }
                }
                if (ranges == null) continue;
                // Merge the rows' section ranges (sorted by their first section) into disjoint runs.
                Arrays.sort(ranges, 0, found);
                section[m] = sm;
                section[n] = sn;
                int i = 0;
                while (i < found) {
                    int first = (int) (ranges[i] >> 32), last = (int) ranges[i];
                    for (i++; i < found && (int) (ranges[i] >> 32) <= last + 1; i++) last = Math.max(last, (int) ranges[i]);
                    if (keys.size() + ((long) last - first + 1) > maxSections) {
                        throw tooManySections(keys.size() + ((long) last - first + 1), maxSections);
                    }
                    for (section[l] = first; section[l] <= last; section[l]++) {
                        keys.add(BlockBuffer.key(section[0], section[1], section[2]));
                    }
                }
            }
        }
        return count;
    }

    /** The facing on {@code axis} (0 x, 1 y, 2 z) whose end is the maximum side when {@code sign} is positive. */
    private static Facing facing(int axis, int sign) {
        return switch (axis) {
            case 0 -> sign > 0 ? Facing.EAST : Facing.WEST;
            case 1 -> sign > 0 ? Facing.UP : Facing.DOWN;
            default -> sign > 0 ? Facing.SOUTH : Facing.NORTH;
        };
    }

    private static RegionTooLargeException tooManySections(long sections, long max) {
        return new RegionTooLargeException("A region reaching " + sections + " sections > " + max, sections, max);
    }

    private static RegionTooLargeException tooManyColumns(long chunks, long max) {
        return new RegionTooLargeException("A region reaching " + chunks + " chunk columns > " + max, chunks, max);
    }

    /** {@code box} moved by a move with pivot {@code pivot} (see {@link #moved}). */
    private static Box movedBox(Box box, Box pivot, BlockPos destinationMin, Transform t) {
        int sx = pivot.sizeX(), sz = pivot.sizeZ();
        int ax = box.min().x() - pivot.min().x(), az = box.min().z() - pivot.min().z();
        int bx = box.max().x() - pivot.min().x(), bz = box.max().z() - pivot.min().z();
        int x0 = t.mapX(ax, az, sx, sz), z0 = t.mapZ(ax, az, sx, sz);
        int x1 = t.mapX(bx, bz, sx, sz), z1 = t.mapZ(bx, bz, sx, sz);
        long minX = (long) destinationMin.x() + Math.min(x0, x1), maxX = (long) destinationMin.x() + Math.max(x0, x1);
        long minZ = (long) destinationMin.z() + Math.min(z0, z1), maxZ = (long) destinationMin.z() + Math.max(z0, z1);
        long dy = (long) destinationMin.y() - pivot.min().y();
        long minY = t.upsideDown() ? (long) destinationMin.y() + pivot.max().y() - box.max().y() : box.min().y() + dy;
        long maxY = t.upsideDown() ? (long) destinationMin.y() + pivot.max().y() - box.min().y() : box.max().y() + dy;
        if (minX < Integer.MIN_VALUE || maxX > Integer.MAX_VALUE || minZ < Integer.MIN_VALUE || maxZ > Integer.MAX_VALUE
                || minY < Integer.MIN_VALUE || maxY > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("The moved region leaves the coordinate range");
        }
        return new Box(new BlockPos((int) minX, (int) minY, (int) minZ), new BlockPos((int) maxX, (int) maxY, (int) maxZ));
    }

    /** {@code box} cut to heights {@code [minY, maxY]}, which must overlap it. */
    private static Box clip(Box box, int minY, int maxY) {
        if (box.min().y() >= minY && box.max().y() <= maxY) return box;
        return new Box(new BlockPos(box.min().x(), Math.max(box.min().y(), minY), box.min().z()),
                new BlockPos(box.max().x(), Math.min(box.max().y(), maxY), box.max().z()));
    }

    /** {@code y - origin}, clamped into the int range for heights far outside the section. */
    private static int clampLocal(int y, int origin) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, (long) y - origin));
    }

    /** Bits {@code from..to} (0..15) set. */
    private static int span(int from, int to) {
        return ((1 << (to - from + 1)) - 1) << from;
    }

    private static void setColumn(long[] bits, int x, int z) {
        int bit = ((z & 15) << 4) | (x & 15);
        bits[bit >>> 6] |= 1L << bit;
    }

    private static IllegalStateException unresolved() {
        return new IllegalStateException("An uploaded region must be resolved to its cells first");
    }
}
