package dev.sculptory.core.region;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.Sha256;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.edit.SectionOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** Region kinds and the exact voxelization of shapes. */
class RegionTest {
    private static final BlockPos CORNER = new BlockPos(-7, 13, 30);

    private static Region.Shape shape(int sx, int sy, int sz, ShapeKind kind, Facing facing) {
        return shape(CORNER, sx, sy, sz, kind, facing);
    }

    private static Region.Shape shape(BlockPos min, int sx, int sy, int sz, ShapeKind kind, Facing facing) {
        return new Region.Shape(new Box(min, min.offset(sx - 1, sy - 1, sz - 1)), kind, facing);
    }

    /** Cells of the shape by contains(), counted. */
    private static long counted(Region region) {
        Box box = region.bounds();
        long count = 0;
        for (int x = box.min().x(); x <= box.max().x(); x++) {
            for (int y = box.min().y(); y <= box.max().y(); y++) {
                for (int z = box.min().z(); z <= box.max().z(); z++) {
                    if (region.contains(x, y, z)) count++;
                }
            }
        }
        return count;
    }

    /** Every small box (sides 1-6), kind and facing. */
    private static List<Region.Shape> smallShapes() {
        List<Region.Shape> shapes = new ArrayList<>();
        for (int sx = 1; sx <= 6; sx++) {
            for (int sy = 1; sy <= 6; sy++) {
                for (int sz = 1; sz <= 6; sz++) {
                    for (ShapeKind kind : ShapeKind.values()) {
                        for (Facing facing : Facing.values()) shapes.add(shape(sx, sy, sz, kind, facing));
                    }
                }
            }
        }
        return shapes;
    }

    // =================================================================== known cases

    @Test
    void knownSmallShapes() {
        for (ShapeKind kind : ShapeKind.values()) {
            for (Facing facing : Facing.values()) {
                assertEquals(1, shape(1, 1, 1, kind, facing).cellCount(), kind + " " + facing + " of one cell");
            }
        }
        // 3³ ellipsoid: every cell but the 8 corners (2/3² × 3 > 1, 2/3² × 2 <= 1).
        Region.Shape ball = shape(3, 3, 3, ShapeKind.ELLIPSOID, Facing.UP);
        assertEquals(19, ball.cellCount());
        assertFalse(ball.contains(CORNER.x(), CORNER.y(), CORNER.z()));
        assertTrue(ball.contains(CORNER.x() + 1, CORNER.y(), CORNER.z()));
        assertEquals(8, shape(2, 2, 2, ShapeKind.ELLIPSOID, Facing.DOWN).cellCount());
        // 5×5 disc: every cell but the 4 corners, on each of 5 layers.
        assertEquals(105, shape(5, 5, 5, ShapeKind.CYLINDER, Facing.UP).cellCount());
        assertEquals(105, shape(5, 5, 5, ShapeKind.CYLINDER, Facing.EAST).cellCount());
        // 3³ cone up: t = 1/6, 1/2, 5/6 from the top: the centre, the centre, then a plus of 5.
        Region.Shape cone = shape(3, 3, 3, ShapeKind.CONE, Facing.UP);
        assertEquals(7, cone.cellCount());
        int x = CORNER.x(), y = CORNER.y(), z = CORNER.z();
        assertTrue(cone.contains(x + 1, y + 2, z + 1));
        assertFalse(cone.contains(x, y + 2, z + 1));
        assertFalse(cone.contains(x, y + 1, z + 1));
        assertTrue(cone.contains(x, y, z + 1));
        assertFalse(cone.contains(x, y, z));
        // Its base is at the bottom, so facing down puts the plus on top.
        Region.Shape down = shape(3, 3, 3, ShapeKind.CONE, Facing.DOWN);
        assertTrue(down.contains(x, y + 2, z + 1));
        assertFalse(down.contains(x, y, z + 1));
        // 3³ pyramid up: the centre, the centre, then the full 3×3 base.
        Region.Shape pyramid = shape(3, 3, 3, ShapeKind.PYRAMID, Facing.UP);
        assertEquals(11, pyramid.cellCount());
        assertTrue(pyramid.contains(x, y, z));
        assertFalse(pyramid.contains(x, y + 1, z));
        assertEquals(4, shape(2, 1, 2, ShapeKind.PYRAMID, Facing.UP).cellCount());
    }

    @Test
    void aShapeMayHaveNoCells() {
        Region.Shape cone = shape(2, 1, 2, ShapeKind.CONE, Facing.UP);
        assertEquals(0, cone.cellCount());
        assertEquals(0, counted(cone));
        assertEquals(0, cone.sectionKeys().length);
        assertEquals(Region.Shape.EMPTY_ROW, cone.rowSpan(CORNER.y(), CORNER.z()));
        assertTrue(Region.Shape.rowMin(Region.Shape.EMPTY_ROW) > Region.Shape.rowMax(Region.Shape.EMPTY_ROW));
        assertEquals(new Box(CORNER, CORNER.offset(1, 0, 1)), cone.bounds());
    }

    @Test
    void oneWideBoxesAreFullColumnsOrDiscs() {
        for (ShapeKind kind : ShapeKind.values()) {
            for (Facing facing : Facing.values()) {
                for (int axis = 0; axis < 3; axis++) {
                    Region.Shape column = shape(axis == 0 ? 9 : 1, axis == 1 ? 9 : 1, axis == 2 ? 9 : 1, kind, facing);
                    // A column along the facing axis (or any ellipsoid or cylinder) is whole. A cone or pyramid one
                    // block deep along its facing axis has t = 1/2 there: the middle 5 of 9 cells.
                    boolean tapers = (kind == ShapeKind.CONE || kind == ShapeKind.PYRAMID) && facing.axis() != axis;
                    assertEquals(tapers ? 5 : 9, column.cellCount(), kind + " " + facing + " along axis " + axis);
                }
            }
        }
        // A one-thick disc across the facing axis is the 5×5 disc.
        assertEquals(21, shape(5, 1, 5, ShapeKind.CYLINDER, Facing.UP).cellCount());
        assertEquals(21, shape(5, 5, 1, ShapeKind.ELLIPSOID, Facing.UP).cellCount());
    }

    // =================================================================== exactness

    @Test
    void shapesAreExactlySymmetricUnderReflectionAndTransposition() {
        for (Region.Shape s : smallShapes()) {
            Box box = s.box();
            int sx = box.sizeX(), sy = box.sizeY(), sz = box.sizeZ();
            Facing f = s.facing();
            Region.Shape mirrorX = shape(sx, sy, sz, s.kind(), f.axis() == 0 ? f.opposite() : f);
            Region.Shape mirrorY = shape(sx, sy, sz, s.kind(), f.axis() == 1 ? f.opposite() : f);
            Region.Shape mirrorZ = shape(sx, sy, sz, s.kind(), f.axis() == 2 ? f.opposite() : f);
            Region.Shape swapXz = shape(sz, sy, sx, s.kind(), swapXz(f));
            Region.Shape swapXy = shape(sy, sx, sz, s.kind(), swapXy(f));
            for (int i = 0; i < sx; i++) {
                for (int j = 0; j < sy; j++) {
                    for (int k = 0; k < sz; k++) {
                        boolean in = s.contains(CORNER.x() + i, CORNER.y() + j, CORNER.z() + k);
                        String where = s + " at " + i + "," + j + "," + k;
                        assertEquals(in, mirrorX.contains(CORNER.x() + sx - 1 - i, CORNER.y() + j, CORNER.z() + k), where);
                        assertEquals(in, mirrorY.contains(CORNER.x() + i, CORNER.y() + sy - 1 - j, CORNER.z() + k), where);
                        assertEquals(in, mirrorZ.contains(CORNER.x() + i, CORNER.y() + j, CORNER.z() + sz - 1 - k), where);
                        assertEquals(in, swapXz.contains(CORNER.x() + k, CORNER.y() + j, CORNER.z() + i), where);
                        assertEquals(in, swapXy.contains(CORNER.x() + j, CORNER.y() + i, CORNER.z() + k), where);
                    }
                }
            }
        }
    }

    private static Facing swapXz(Facing f) {
        return switch (f) {
            case EAST -> Facing.SOUTH;
            case WEST -> Facing.NORTH;
            case SOUTH -> Facing.EAST;
            case NORTH -> Facing.WEST;
            default -> f;
        };
    }

    private static Facing swapXy(Facing f) {
        return switch (f) {
            case EAST -> Facing.UP;
            case WEST -> Facing.DOWN;
            case UP -> Facing.EAST;
            case DOWN -> Facing.WEST;
            default -> f;
        };
    }

    @Test
    void rowSpansAgreeWithContainsOverWholeBoxes() {
        List<Region.Shape> shapes = new ArrayList<>(smallShapes());
        for (ShapeKind kind : ShapeKind.values()) {
            for (Facing facing : Facing.values()) {
                shapes.add(shape(new BlockPos(-20, -70, 5), 37, 21, 18, kind, facing));
                shapes.add(shape(new BlockPos(100, 0, -40), 12, 40, 33, kind, facing));
            }
        }
        for (Region.Shape s : shapes) {
            Box box = s.box();
            long total = 0;
            for (int y = box.min().y() - 1; y <= box.max().y() + 1; y++) {
                for (int z = box.min().z() - 1; z <= box.max().z() + 1; z++) {
                    long span = s.rowSpan(y, z);
                    int from = Integer.MAX_VALUE, to = Integer.MIN_VALUE, cells = 0;
                    for (int x = box.min().x() - 1; x <= box.max().x() + 1; x++) {
                        if (!s.contains(x, y, z)) continue;
                        from = Math.min(from, x);
                        to = Math.max(to, x);
                        cells++;
                    }
                    String row = s + " row " + y + "," + z;
                    if (cells == 0) {
                        assertEquals(Region.Shape.EMPTY_ROW, span, row);
                    } else {
                        assertEquals(to - from + 1, cells, row + " is one interval");
                        assertEquals(from, Region.Shape.rowMin(span), row);
                        assertEquals(to, Region.Shape.rowMax(span), row);
                    }
                    total += cells;
                }
            }
            assertEquals(total, s.cellCount(), s + " count");
        }
    }

    @Test
    void theLongAndBigIntegerPathsAgree() {
        for (Region.Shape s : smallShapes()) {
            Box box = s.box();
            ShapeMath fast = new ShapeMath(s.kind(), s.facing(), box.sizeX(), box.sizeY(), box.sizeZ(), false);
            ShapeMath exact = new ShapeMath(s.kind(), s.facing(), box.sizeX(), box.sizeY(), box.sizeZ(), true);
            for (int j = 0; j < box.sizeY(); j++) {
                for (int k = 0; k < box.sizeZ(); k++) {
                    assertEquals(fast.rowSpan(j, k), exact.rowSpan(j, k), s + " row " + j + "," + k);
                    for (int i = 0; i < box.sizeX(); i++) {
                        assertEquals(fast.contains(i, j, k), exact.contains(i, j, k), s + " at " + i + "," + j + "," + k);
                    }
                }
            }
        }
    }

    @Test
    void hugeBoxesStayExact() {
        int half = 1 << 30;
        // Sides of 2^31 - 1 and 2^20: far past long arithmetic.
        BlockPos min = new BlockPos(-half, -(1 << 19), -half);
        for (ShapeKind kind : ShapeKind.values()) {
            for (Facing facing : Facing.values()) {
                Region.Shape s = shape(min, Integer.MAX_VALUE, 1 << 20, Integer.MAX_VALUE, kind, facing);
                Box box = s.box();
                for (int y : new int[] {box.min().y(), 0, 1, box.max().y() - 7, box.max().y()}) {
                    for (int z : new int[] {box.min().z(), -12345, 0, 1, half / 3, box.max().z()}) {
                        long span = s.rowSpan(y, z);
                        if (span == Region.Shape.EMPTY_ROW) {
                            assertFalse(s.contains(box.min().x(), y, z) || s.contains(0, y, z) || s.contains(-1, y, z)
                                    || s.contains(box.max().x(), y, z), s + " row " + y + "," + z);
                            continue;
                        }
                        int from = Region.Shape.rowMin(span), to = Region.Shape.rowMax(span);
                        String row = s + " row " + y + "," + z;
                        assertTrue(s.contains(from, y, z) && s.contains(to, y, z), row);
                        assertTrue(from == box.min().x() || !s.contains(from - 1, y, z), row);
                        assertTrue(to == box.max().x() || !s.contains(to + 1, y, z), row);
                        if (kind == ShapeKind.ELLIPSOID || facing.axis() != 0) {
                            // Symmetric across the box's centre plane.
                            assertEquals((long) box.min().x() + box.max().x(), (long) from + to, row);
                        }
                    }
                }
            }
        }
        Region.Shape ball = shape(min, Integer.MAX_VALUE, 1 << 20, Integer.MAX_VALUE, ShapeKind.ELLIPSOID, Facing.UP);
        assertTrue(ball.contains(-1, 0, -1));
        assertFalse(ball.contains(ball.box().min().x(), ball.box().min().y(), ball.box().min().z()));
    }

    // =================================================================== sections, count, translate

    @Test
    void sectionKeysListEverySectionWithCellsInBoxOrder() {
        List<Region> regions = new ArrayList<>();
        for (ShapeKind kind : ShapeKind.values()) {
            for (Facing facing : Facing.values()) {
                regions.add(shape(new BlockPos(-40, -70, -3), 70, 35, 50, kind, facing));
                regions.add(shape(new BlockPos(7, 60, 9), 3, 40, 2, kind, facing));
            }
        }
        regions.add(new Region.Cuboid(new Box(new BlockPos(-17, -1, 15), new BlockPos(16, 31, 16))));
        for (Region region : regions) {
            TreeSet<Long> expected = new TreeSet<>();
            Box box = region.bounds();
            for (int x = box.min().x(); x <= box.max().x(); x++) {
                for (int y = box.min().y(); y <= box.max().y(); y++) {
                    for (int z = box.min().z(); z <= box.max().z(); z++) {
                        if (region.contains(x, y, z)) expected.add(BlockBuffer.keyOfBlock(x, y, z));
                    }
                }
            }
            long[] keys = expected.stream().mapToLong(Long::longValue).toArray();
            SectionOrder.sort(keys);
            assertArrayEquals(keys, region.sectionKeys(), region.toString());
            // Box.forEachSectionKey order: section x, then z, then y.
            long[] listed = region.sectionKeys();
            for (int i = 1; i < listed.length; i++) {
                long a = listed[i - 1], b = listed[i];
                int cmp = Integer.compare(BlockBuffer.keyX(a), BlockBuffer.keyX(b));
                if (cmp == 0) cmp = Integer.compare(BlockBuffer.keyZ(a), BlockBuffer.keyZ(b));
                if (cmp == 0) cmp = Integer.compare(BlockBuffer.keyY(a), BlockBuffer.keyY(b));
                assertTrue(cmp < 0, region + " keys in order");
            }
        }
    }

    @Test
    void cellCountIsExactAndKeptThroughTranslation() {
        Region.Shape cone = shape(new BlockPos(3, 4, 5), 20, 31, 17, ShapeKind.CONE, Facing.NORTH);
        long count = counted(cone);
        assertEquals(count, cone.cellCount());
        assertEquals(count, cone.cellCount());
        Region.Shape moved = cone.translate(-100, 7, 33);
        assertEquals(new Box(new BlockPos(-97, 11, 38), new BlockPos(-78, 41, 54)), moved.box());
        assertEquals(count, moved.cellCount());
        assertEquals(count, counted(moved));
        for (int x = 3; x < 23; x++) {
            for (int y = 4; y < 35; y++) {
                for (int z = 5; z < 22; z++) assertEquals(cone.contains(x, y, z), moved.contains(x - 100, y + 7, z + 33));
            }
        }
        assertEquals(cone, moved.translate(100, -7, -33));
        // Counting through sectionKeys caches the count too.
        Region.Shape fresh = shape(new BlockPos(3, 4, 5), 20, 31, 17, ShapeKind.CONE, Facing.NORTH);
        fresh.sectionKeys();
        assertEquals(count, fresh.cellCount());
    }

    @Test
    void shapesAreValuesOverBoxKindAndFacing() {
        Region.Shape a = shape(4, 5, 6, ShapeKind.PYRAMID, Facing.EAST);
        Region.Shape b = shape(4, 5, 6, ShapeKind.PYRAMID, Facing.EAST);
        b.cellCount();
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, shape(4, 5, 6, ShapeKind.PYRAMID, Facing.WEST));
        assertNotEquals(a, shape(4, 5, 6, ShapeKind.CONE, Facing.EAST));
        assertNotEquals(a, new Region.Cuboid(a.box()));
        assertTrue(a.toString().contains("PYRAMID"));
        assertThrows(NullPointerException.class, () -> new Region.Shape(a.box(), null, Facing.UP));
    }

    @Test
    void cuboidsAreEveryCellOfTheirBox() {
        Box box = new Box(new BlockPos(-3, 0, 14), new BlockPos(20, 2, 17));
        Region.Cuboid cuboid = new Region.Cuboid(box);
        assertEquals(box, cuboid.bounds());
        assertEquals(box.volume(), cuboid.cellCount());
        assertEquals(counted(cuboid), cuboid.cellCount());
        assertFalse(cuboid.contains(-4, 0, 14));
        assertEquals(new Region.Cuboid(box.offset(1, 2, 3)), cuboid.translate(1, 2, 3));
        List<Long> keys = new ArrayList<>();
        box.forEachSectionKey(keys::add);
        assertArrayEquals(keys.stream().mapToLong(Long::longValue).toArray(), cuboid.sectionKeys());
    }

    @Test
    void cellRegionsAreNonEmptySets() {
        assertThrows(IllegalArgumentException.class, () -> new Region.Cells(CellSet.empty()));
        CellSet set = CellSet.builder().add(1, 2, 3).add(40, 2, 3).build();
        Region.Cells cells = new Region.Cells(set);
        assertEquals(2, cells.cellCount());
        assertEquals(new Box(new BlockPos(1, 2, 3), new BlockPos(40, 2, 3)), cells.bounds());
        assertTrue(cells.contains(40, 2, 3));
        assertFalse(cells.contains(2, 2, 3));
        assertArrayEquals(set.sectionKeys(), cells.sectionKeys());
        assertTrue(cells.translate(0, 1, 0).contains(1, 3, 3));
        assertEquals(cells, new Region.Cells(CellSet.builder().add(40, 2, 3).add(1, 2, 3).build()));
    }

    @Test
    void uploadedRegionsOnlyKnowTheirBoundsAndCount() {
        Box box = new Box(new BlockPos(0, 0, 0), new BlockPos(3, 3, 3));
        Sha256 hash = Sha256.digest(new byte[] {1});
        Region.Uploaded uploaded = new Region.Uploaded(hash, box, 64);
        assertEquals(box, uploaded.bounds());
        assertEquals(64, uploaded.cellCount());
        assertThrows(IllegalStateException.class, () -> uploaded.contains(0, 0, 0));
        assertThrows(IllegalStateException.class, uploaded::sectionKeys);
        assertThrows(IllegalStateException.class, () -> uploaded.translate(1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Region.Uploaded(hash, box, 0));
        assertThrows(IllegalArgumentException.class, () -> new Region.Uploaded(hash, box, 65));
        assertThrows(NullPointerException.class, () -> new Region.Uploaded(null, box, 1));
    }

    @Test
    void facingsKnowTheirAxisSignAndOpposite() {
        for (Facing facing : Facing.values()) {
            assertEquals(facing.axis(), facing.opposite().axis());
            assertEquals(-facing.sign(), facing.opposite().sign());
            assertEquals(facing, facing.opposite().opposite());
        }
        assertEquals(1, Facing.EAST.sign());
        assertEquals(-1, Facing.NORTH.sign());
        assertEquals(1, Facing.UP.axis());
    }

    @Test
    void integerSquareRootsAreExact() {
        long[] values = {0, 1, 2, 3, 4, 15, 16, 17, 99, 100, 101, Long.MAX_VALUE, Long.MAX_VALUE - 1,
                3037000499L * 3037000499L, 3037000499L * 3037000499L - 1, (1L << 62) - 1, 1L << 62};
        for (long n : values) {
            long r = ShapeMath.isqrt(n);
            assertTrue(r * r <= n && (r + 1) * (r + 1) > n || r == 3037000499L, "isqrt " + n);
            assertEquals(java.math.BigInteger.valueOf(n).sqrt().longValueExact(), r, "isqrt " + n);
        }
    }
}
