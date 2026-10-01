package dev.sculptory.core.region;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.Sha256;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.edit.SectionOrder;
import dev.sculptory.core.transform.Mirror;
import dev.sculptory.core.transform.Transform;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** {@link Regions}: section rows, counts, sections and columns within heights, and moved regions, against brute force. */
class RegionsTest {
    private static Box box(int x0, int y0, int z0, int x1, int y1, int z1) {
        return Box.of(new BlockPos(x0, y0, z0), new BlockPos(x1, y1, z1));
    }

    /** A cuboid, shapes of every kind and some facings, and a random cell set, all crossing section boundaries. */
    private static List<Region> regions() {
        List<Region> regions = new ArrayList<>();
        regions.add(new Region.Cuboid(box(-5, 3, 7, 20, 18, 40)));
        // Longest along y, x and z: counts and listings go along the longest side.
        for (Box shapeBox : List.of(box(-9, -4, 11, 13, 21, 30), box(-20, 2, 3, 19, 11, 14), box(5, -3, -40, 12, 8, -4))) {
            for (ShapeKind kind : ShapeKind.values()) {
                for (Facing facing : Facing.values()) {
                    if (kind != ShapeKind.ELLIPSOID || facing == Facing.UP) regions.add(new Region.Shape(shapeBox, kind, facing));
                }
            }
        }
        regions.add(new Region.Cells(randomCells(new Random(7), box(-20, -10, -20, 40, 35, 25), 3000)));
        return regions;
    }

    private static CellSet randomCells(Random random, Box within, int cells) {
        CellSet.Builder builder = CellSet.builder();
        for (int i = 0; i < cells; i++) {
            builder.add(within.min().x() + random.nextInt(within.sizeX()), within.min().y() + random.nextInt(within.sizeY()),
                    within.min().z() + random.nextInt(within.sizeZ()));
        }
        return builder.build();
    }

    /** Every cell of the region with y in [minY, maxY], by contains(). */
    private static Set<BlockPos> cells(Region region, int minY, int maxY) {
        Set<BlockPos> cells = new HashSet<>();
        Box b = region.bounds();
        for (int x = b.min().x(); x <= b.max().x(); x++) {
            for (int y = Math.max(minY, b.min().y()); y <= Math.min(maxY, b.max().y()); y++) {
                for (int z = b.min().z(); z <= b.max().z(); z++) {
                    if (region.contains(x, y, z)) cells.add(new BlockPos(x, y, z));
                }
            }
        }
        return cells;
    }

    @Test
    void rowsCountsAndSectionsAgreeWithContains() {
        int[][] ranges = {{Integer.MIN_VALUE, Integer.MAX_VALUE}, {0, 15}, {-3, 9}, {17, 17}, {100, 200}};
        for (Region region : regions()) {
            for (int[] range : ranges) {
                Set<BlockPos> expected = cells(region, range[0], range[1]);
                String what = region + " in " + Arrays.toString(range);
                assertEquals(expected.size(), Regions.cellsBetween(region, range[0], range[1]), what);
                LongOpenHashSet sections = new LongOpenHashSet();
                for (BlockPos cell : expected) sections.add(BlockBuffer.keyOfBlock(cell.x(), cell.y(), cell.z()));
                long[] keys = Regions.sectionKeysBetween(region, range[0], range[1]);
                long[] sorted = sections.toLongArray();
                SectionOrder.sort(sorted);
                assertArrayEquals(sorted, keys, what + ": exactly the sections holding cells, in order");
                Set<BlockPos> fromRows = new HashSet<>();
                int[] rows = new int[Regions.ROWS];
                for (long key : keys) {
                    int n = Regions.rows(region, key, range[0], range[1], rows);
                    int bits = 0;
                    for (int r = 0; r < Regions.ROWS; r++) {
                        bits += Integer.bitCount(rows[r]);
                        for (int lx = 0; lx < 16; lx++) {
                            if (((rows[r] >>> lx) & 1) == 0) continue;
                            fromRows.add(new BlockPos((BlockBuffer.keyX(key) << 4) + lx, (BlockBuffer.keyY(key) << 4) + (r >>> 4),
                                    (BlockBuffer.keyZ(key) << 4) + (r & 15)));
                        }
                    }
                    assertEquals(bits, n, what + ": the count rows() answers");
                    assertTrue(n > 0, what + ": a listed section holds cells");
                }
                assertEquals(expected, fromRows, what + ": rows hold exactly the cells");
            }
        }
    }

    /** Capped counts are exact up to the cap and above it past it; listings and columns refuse past their caps. */
    @Test
    void capsStopCountsAndListings() {
        for (Region region : regions()) {
            long cells = Regions.cellsBetween(region, Integer.MIN_VALUE, Integer.MAX_VALUE);
            assertEquals(cells, Regions.cellsBetween(region, Integer.MIN_VALUE, Integer.MAX_VALUE, cells), region.toString());
            if (cells > 0) {
                assertTrue(Regions.cellsBetween(region, Integer.MIN_VALUE, Integer.MAX_VALUE, cells - 1) > cells - 1);
            }
            int sections = Regions.sectionKeysBetween(region, Integer.MIN_VALUE, Integer.MAX_VALUE).length;
            assertEquals(sections, Regions.sectionKeysBetween(region, Integer.MIN_VALUE, Integer.MAX_VALUE, sections).length);
            if (sections > 1) {
                assertThrows(RegionTooLargeException.class,
                        () -> Regions.sectionKeysBetween(region, Integer.MIN_VALUE, Integer.MAX_VALUE, sections - 1));
            }
            int chunks = Regions.columns(region, Integer.MIN_VALUE, Integer.MAX_VALUE).size();
            assertEquals(chunks, Regions.columns(region, Integer.MIN_VALUE, Integer.MAX_VALUE, chunks).size());
            if (chunks > 1) {
                assertThrows(RegionTooLargeException.class,
                        () -> Regions.columns(region, Integer.MIN_VALUE, Integer.MAX_VALUE, chunks - 1));
            }
        }
    }

    /** The rows a shape's count goes over: the product of its box's two shorter sides within the heights. */
    @Test
    void shapeRowsAreTheTwoShorterSides() {
        Region.Shape shape = new Region.Shape(box(0, 0, 0, 99, 9, 29), ShapeKind.CYLINDER, Facing.UP);
        assertEquals(10L * 30, Regions.shapeRows(shape, Integer.MIN_VALUE, Integer.MAX_VALUE));
        assertEquals(5L * 30, Regions.shapeRows(shape, 5, 100));
        assertEquals(0, Regions.shapeRows(shape, 20, 30));
    }

    @Test
    void rowsOfSectionsWithoutCellsAreEmpty() {
        int[] rows = new int[Regions.ROWS];
        Arrays.fill(rows, -1);
        for (Region region : regions()) {
            assertEquals(0, Regions.rows(region, BlockBuffer.key(100, 0, 100), rows));
            for (int row : rows) assertEquals(0, row);
        }
    }

    @Test
    void columnsAreTheColumnsHoldingCells() {
        for (Region region : regions()) {
            for (int[] range : new int[][] {{Integer.MIN_VALUE, Integer.MAX_VALUE}, {0, 5}}) {
                Set<Long> expected = new HashSet<>();
                for (BlockPos cell : cells(region, range[0], range[1])) expected.add(((long) cell.x() << 32) ^ cell.z());
                assertEquals(expected, columnSet(Regions.columns(region, range[0], range[1])), region + " columns");
            }
        }
    }

    private static Set<Long> columnSet(Long2ObjectMap<long[]> columns) {
        Set<Long> set = new HashSet<>();
        for (Long2ObjectMap.Entry<long[]> entry : columns.long2ObjectEntrySet()) {
            int cx = Regions.columnX(entry.getLongKey()), cz = Regions.columnZ(entry.getLongKey());
            for (int bit = 0; bit < 256; bit++) {
                if ((entry.getValue()[bit >>> 6] & (1L << bit)) != 0) {
                    set.add(((long) ((cx << 4) + (bit & 15)) << 32) ^ ((cz << 4) + (bit >>> 4)));
                }
            }
        }
        return set;
    }

    /** Transform maps a local cell of the pivot to its cell in the moved box. */
    private static BlockPos moveCell(BlockPos cell, Box pivot, BlockPos destinationMin, Transform t) {
        int lx = cell.x() - pivot.min().x(), lz = cell.z() - pivot.min().z();
        return new BlockPos(destinationMin.x() + t.mapX(lx, lz, pivot.sizeX(), pivot.sizeZ()),
                t.upsideDown() ? destinationMin.y() + pivot.max().y() - cell.y() : cell.y() - pivot.min().y() + destinationMin.y(),
                destinationMin.z() + t.mapZ(lx, lz, pivot.sizeX(), pivot.sizeZ()));
    }

    private static List<Transform> transforms() {
        List<Transform> all = new ArrayList<>(Transform.all());
        for (int turns = 0; turns < 4; turns++) all.add(new Transform(turns, Mirror.Z));
        for (Transform t : List.copyOf(all)) all.add(t.withUpsideDown(true));
        return all;
    }

    /**
     * A moved shape stays a shape with its box and facing turned, and holds exactly the moved cells: every small box,
     * kind, facing and transform.
     */
    @Test
    void movedShapesAreExactlyTheMovedCells() {
        BlockPos corner = new BlockPos(3, -2, -8);
        BlockPos to = new BlockPos(-40, 5, 17);
        for (int sx = 1; sx <= 5; sx++) {
            for (int sy = 1; sy <= 4; sy++) {
                for (int sz = 1; sz <= 5; sz++) {
                    Box b = new Box(corner, corner.offset(sx - 1, sy - 1, sz - 1));
                    for (ShapeKind kind : ShapeKind.values()) {
                        for (Facing facing : Facing.values()) {
                            Region.Shape shape = new Region.Shape(b, kind, facing);
                            for (Transform t : transforms()) {
                                Region moved = Regions.moved(shape, b, to, t);
                                assertTrue(moved instanceof Region.Shape, "a shape stays one");
                                Set<BlockPos> expected = new HashSet<>();
                                for (BlockPos cell : cells(shape, Integer.MIN_VALUE, Integer.MAX_VALUE)) {
                                    expected.add(moveCell(cell, b, to, t));
                                }
                                assertEquals(expected, cells(moved, Integer.MIN_VALUE, Integer.MAX_VALUE),
                                        shape + " moved by " + t);
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void movedCellSetsAndCuboidsAndTheirColumns() {
        Random random = new Random(11);
        CellSet set = randomCells(random, box(-17, 0, -3, 12, 20, 25), 800);
        Region.Cells cells = new Region.Cells(set);
        Region.Cuboid cuboid = new Region.Cuboid(box(-17, 0, -3, 12, 20, 25));
        Box pivot = set.bounds();
        BlockPos to = new BlockPos(1000, -6, -2000);
        for (Transform t : transforms()) {
            Set<BlockPos> expected = new HashSet<>();
            for (BlockPos cell : cells(cells, Integer.MIN_VALUE, Integer.MAX_VALUE)) expected.add(moveCell(cell, pivot, to, t));
            Region moved = Regions.moved(cells, pivot, to, t);
            assertEquals(expected, cells(moved, Integer.MIN_VALUE, Integer.MAX_VALUE), "cells moved by " + t);
            assertEquals(columnSet(Regions.columns(moved, Integer.MIN_VALUE, Integer.MAX_VALUE)),
                    columnSet(Regions.movedColumns(Regions.columns(cells, Integer.MIN_VALUE, Integer.MAX_VALUE), pivot, to, t)),
                    "moved columns by " + t);

            Region movedBox = Regions.moved(cuboid, cuboid.box(), to, t);
            Set<BlockPos> boxCells = new HashSet<>();
            for (BlockPos cell : cells(cuboid, 0, 20)) boxCells.add(moveCell(cell, cuboid.box(), to, t));
            assertEquals(boxCells, cells(movedBox, Integer.MIN_VALUE, Integer.MAX_VALUE), "box moved by " + t);
        }
    }

    @Test
    void facingsTurnLikeDirections() {
        assertEquals(Facing.EAST, Regions.moved(Facing.NORTH, Transform.rotation(1)));
        assertEquals(Facing.WEST, Regions.moved(Facing.NORTH, Transform.rotation(3)));
        assertEquals(Facing.WEST, Regions.moved(Facing.EAST, new Transform(0, Mirror.X)));
        assertEquals(Facing.NORTH, Regions.moved(Facing.NORTH, new Transform(0, Mirror.X)));
        assertEquals(Facing.SOUTH, Regions.moved(Facing.NORTH, new Transform(0, Mirror.Z)));
        assertEquals(Facing.SOUTH, Regions.moved(Facing.EAST, new Transform(1, Mirror.NONE)));
        for (Transform t : transforms()) {
            assertEquals(t.upsideDown() ? Facing.DOWN : Facing.UP, Regions.moved(Facing.UP, t));
            assertEquals(t.upsideDown() ? Facing.UP : Facing.DOWN, Regions.moved(Facing.DOWN, t));
            for (Facing side : List.of(Facing.NORTH, Facing.EAST, Facing.SOUTH, Facing.WEST)) {
                assertEquals(Regions.moved(side, t.horizontal()), Regions.moved(side, t), "the flip keeps " + side);
            }
        }
    }

    /** A huge sparse set is worked out from its few sections, not its bounding box. */
    @Test
    void sparseSetsCostTheirSectionsNotTheirBounds() {
        CellSet set = CellSet.builder().add(-1_000_000, -60, -1_000_000).add(1_000_000, 300, 1_000_000).build();
        Region.Cells region = new Region.Cells(set);
        long start = System.nanoTime();
        assertEquals(2, Regions.cellsBetween(region, -64, 319));
        assertEquals(2, Regions.sectionKeysBetween(region, -64, 319).length);
        assertEquals(1, Regions.sectionKeysBetween(region, 0, 319).length);
        assertEquals(2, Regions.columns(region, -64, 319).size());
        Region moved = Regions.moved(region, set.bounds(), new BlockPos(0, 0, 0), Transform.rotation(1));
        assertEquals(2, moved.cellCount());
        assertTrue(System.nanoTime() - start < 1_000_000_000L, "took too long");
    }

    @Test
    void uploadedRegionsHaveNoCellsHere() {
        Region.Uploaded uploaded = new Region.Uploaded(Sha256.digest(new byte[] {1}), box(0, 0, 0, 3, 3, 3), 5);
        assertThrows(IllegalStateException.class, () -> Regions.rows(uploaded, BlockBuffer.key(0, 0, 0), new int[256]));
        assertThrows(IllegalStateException.class, () -> Regions.cellsBetween(uploaded, 0, 10));
        assertThrows(IllegalStateException.class, () -> Regions.sectionKeysBetween(uploaded, 0, 10));
        assertThrows(IllegalStateException.class, () -> Regions.columns(uploaded, 0, 10));
        assertThrows(IllegalStateException.class,
                () -> Regions.moved(uploaded, uploaded.bounds(), BlockPos.ORIGIN, Transform.IDENTITY));
    }

    /**
     * Shapes in boxes of more than 2^29 cells (counted in {@code BigInteger} arithmetic) with the longest side along y
     * and along z: the count and the section listing along that side agree with the shape's own scan along x
     * ({@link Region.Shape#cellCount}, {@link Region.Shape#sectionKeys}), whole and cut to four heights (then read
     * from its x rows).
     */
    @Test
    void bigBoxesAlongYAndZAgreeWithTheShapesOwnScan() {
        record Big(Box box, int y0, List<Region.Shape> shapes) {}
        Box alongY = box(-2048, -2000, 5, 2047, 2096, 37); // 4,096 × 4,097 × 33
        Box alongZ = box(-2048, -10, -2000, 2047, 22, 2096); // 4,096 × 33 × 4,097
        List<Big> bigs = List.of(
                new Big(alongY, 1500, List.of(new Region.Shape(alongY, ShapeKind.ELLIPSOID, Facing.UP),
                        new Region.Shape(alongY, ShapeKind.CONE, Facing.UP),
                        new Region.Shape(alongY, ShapeKind.CYLINDER, Facing.UP),
                        new Region.Shape(alongY, ShapeKind.CYLINDER, Facing.EAST),
                        new Region.Shape(alongY, ShapeKind.PYRAMID, Facing.NORTH))),
                new Big(alongZ, 3, List.of(new Region.Shape(alongZ, ShapeKind.ELLIPSOID, Facing.UP),
                        new Region.Shape(alongZ, ShapeKind.CONE, Facing.SOUTH),
                        new Region.Shape(alongZ, ShapeKind.CYLINDER, Facing.NORTH),
                        new Region.Shape(alongZ, ShapeKind.CYLINDER, Facing.UP),
                        new Region.Shape(alongZ, ShapeKind.PYRAMID, Facing.EAST))));
        for (Big big : bigs) {
            Box b = big.box();
            assertTrue(b.volume() > 1L << 29, "the BigInteger path");
            for (Region.Shape shape : big.shapes()) {
                Region.Shape own = new Region.Shape(b, shape.kind(), shape.facing()); // not sharing a cached count
                long[] ownKeys = own.sectionKeys();
                assertEquals(own.cellCount(), Regions.cellsBetween(shape, Integer.MIN_VALUE, Integer.MAX_VALUE),
                        shape.toString());
                assertArrayEquals(ownKeys, Regions.sectionKeysBetween(shape, Integer.MIN_VALUE, Integer.MAX_VALUE),
                        shape + ": sections");
                // Four heights, from the x rows there.
                int y0 = big.y0(), y1 = big.y0() + 3;
                long count = 0;
                LongOpenHashSet keys = new LongOpenHashSet();
                for (int y = y0; y <= y1; y++) {
                    for (int z = b.min().z(); z <= b.max().z(); z++) {
                        long span = shape.rowSpan(y, z);
                        if (span == Region.Shape.EMPTY_ROW) continue;
                        int min = Region.Shape.rowMin(span), max = Region.Shape.rowMax(span);
                        count += max - min + 1;
                        for (int sx = min >> 4; sx <= max >> 4; sx++) keys.add(BlockBuffer.key(sx, y >> 4, z >> 4));
                    }
                }
                long[] sorted = keys.toLongArray();
                SectionOrder.sort(sorted);
                assertEquals(count, Regions.cellsBetween(shape, y0, y1), shape + " in [" + y0 + ", " + y1 + "]");
                assertArrayEquals(sorted, Regions.sectionKeysBetween(shape, y0, y1), shape + ": sections in heights");
            }
        }
    }

    @Test
    void emptyShapesHaveNothing() {
        Region.Shape empty = new Region.Shape(box(0, 0, 0, 1, 0, 1), ShapeKind.CONE, Facing.UP);
        assertEquals(0, empty.cellCount());
        assertEquals(0, Regions.cellsBetween(empty, -64, 319));
        assertEquals(0, Regions.sectionKeysBetween(empty, -64, 319).length);
        assertTrue(Regions.columns(empty, -64, 319).isEmpty());
    }
}
