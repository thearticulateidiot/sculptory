package dev.sculptory.fabric.client.editor.tools.select;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.ShapeKind;
import dev.sculptory.core.transform.Mirror;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.fabric.client.editor.tool.EditorAction;
import dev.sculptory.fabric.client.editor.world.BoxFace;
import dev.sculptory.fabric.client.editor.world.Ray;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SelectionModelTest {
    private static final Box CUBE = new Box(new BlockPos(0, 0, 0), new BlockPos(3, 3, 3));

    private static Box box(int x1, int y1, int z1, int x2, int y2, int z2) {
        return new Box(new BlockPos(x1, y1, z1), new BlockPos(x2, y2, z2));
    }

    private static Ray down(double x, double z) {
        return new Ray(x, 50, z, 0, -1, 0);
    }

    // ---- Creating ----

    @Test
    void draggingCreatesTheBoxBetweenTheTwoBlocksInAnyDirection() {
        SelectionDrag drag = new SelectionDrag();
        assertEquals(Box.of(new BlockPos(5, 64, -2)), drag.beginCreate(null, new BlockPos(5, 64, -2)));
        assertEquals(SelectionDrag.Mode.CREATE, drag.mode());
        assertEquals(box(1, 60, -2, 5, 64, 7), drag.updateCreate(new BlockPos(1, 60, 7)));
        assertEquals(box(5, 64, -9, 12, 70, -2), drag.updateCreate(new BlockPos(12, 70, -9)));
        assertEquals(box(5, 64, -9, 12, 70, -2), drag.finish());
        assertFalse(drag.isActive());
    }

    @Test
    void cancellingADragRestoresTheBoxFromBefore() {
        SelectionDrag drag = new SelectionDrag();
        drag.beginCreate(CUBE, new BlockPos(10, 10, 10));
        drag.updateCreate(new BlockPos(20, 20, 20));
        assertEquals(Optional.of(CUBE), drag.cancel());
        assertFalse(drag.isActive());

        drag.beginCreate(null, new BlockPos(1, 1, 1));
        assertEquals(Optional.empty(), drag.cancel(), "no box before means no box after");
        assertEquals(Optional.empty(), drag.cancel(), "cancelling when idle does nothing");
        assertThrows(IllegalStateException.class, () -> drag.updateCreate(new BlockPos(0, 0, 0)));
    }

    // ---- Resizing ----

    @Test
    void movingAFaceResizesAlongItsAxisAndNeverInvertsTheBox() {
        assertEquals(box(0, 0, 0, 6, 3, 3), SelectionModel.moveFace(CUBE, BoxFace.EAST, 3));
        assertEquals(box(-2, 0, 0, 3, 3, 3), SelectionModel.moveFace(CUBE, BoxFace.WEST, -2));
        assertEquals(box(0, 0, 0, 3, 1, 3), SelectionModel.moveFace(CUBE, BoxFace.UP, -2));
        assertEquals(box(0, 0, 0, 0, 3, 3), SelectionModel.moveFace(CUBE, BoxFace.EAST, -10), "stops one block thick");
        assertEquals(box(0, 0, 3, 3, 3, 3), SelectionModel.moveFace(CUBE, BoxFace.NORTH, 10), "stops one block thick");
        assertEquals(box(0, 0, 0, 3, 5, 3), SelectionModel.expand(CUBE, BoxFace.UP, 2));
        assertEquals(box(0, -2, 0, 3, 3, 3), SelectionModel.expand(CUBE, BoxFace.DOWN, 2), "expand is outwards");
    }

    @Test
    void draggingAHandleFollowsTheCursorAlongTheFaceAxis() {
        SelectionDrag drag = new SelectionDrag();
        // Looking straight down at the east handle (x = 4 in world units, one past the last block).
        assertTrue(drag.beginResize(CUBE, BoxFace.EAST, down(4.2, 2)));
        assertEquals(SelectionDrag.Mode.RESIZE, drag.mode());
        assertEquals(BoxFace.EAST, drag.face());
        assertEquals(box(0, 0, 0, 6, 3, 3), drag.updateResize(down(6.9, 2)), "moved 2.7 blocks: rounds to 3");
        assertEquals(box(0, 0, 0, 2, 3, 3), drag.updateResize(down(2.9, 1)), "moved back past the start");
        assertEquals(box(0, 0, 0, 0, 3, 3), drag.updateResize(down(-40, 1)), "clamped at one block");
        assertEquals(box(0, 0, 0, 0, 3, 3), drag.finish());
    }

    @Test
    void draggingTheTopHandleUsesTheClosestPointOnTheVerticalAxis() {
        SelectionDrag drag = new SelectionDrag();
        // A horizontal ray towards -z passes the vertical axis through the top face at height y.
        assertTrue(drag.beginResize(CUBE, BoxFace.UP, new Ray(2, 4.1, 20, 0, 0, -1)));
        assertEquals(box(0, 0, 0, 3, 9, 3), drag.updateResize(new Ray(2, 10.3, 20, 0, 0, -1)));
    }

    @Test
    void aRayAlongTheHandleAxisCannotStartAResize() {
        SelectionDrag drag = new SelectionDrag();
        assertFalse(drag.beginResize(CUBE, BoxFace.EAST, new Ray(20, 2, 2, -1, 0, 0)));
        assertFalse(drag.isActive());
        assertTrue(SelectionModel.closestOnAxis(new Ray(20, 2, 2, -1, 0, 0), 4, 2, 2, 0).isEmpty());
    }

    // ---- Moving ----

    @Test
    void ctrlDraggingSlidesTheBoxInTheGrabbedFacePlane() {
        SelectionDrag drag = new SelectionDrag();
        assertTrue(drag.beginMove(CUBE, BoxFace.UP, down(1.5, 1.5)));
        assertEquals(SelectionDrag.Mode.MOVE, drag.mode());
        assertEquals(CUBE.offset(3, 0, -1), drag.updateMove(down(4.6, 0.2)));
        assertEquals(CUBE.offset(-2, 0, 2), drag.updateMove(down(-0.6, 3.6)));
        assertEquals(Optional.of(CUBE), drag.cancel());
    }

    @Test
    void grabbingASideFaceMovesVertically() {
        SelectionDrag drag = new SelectionDrag();
        assertTrue(drag.beginMove(CUBE, BoxFace.SOUTH, new Ray(1.5, 1.5, 30, 0, 0, -1)));
        assertEquals(CUBE.offset(2, 5, 0), drag.updateMove(new Ray(3.5, 6.4, 30, 0, 0, -1)));
    }

    // ---- Growing and nudging ----

    @Test
    void growingIncludesTheBlock() {
        assertEquals(Box.of(new BlockPos(4, 5, 6)), SelectionModel.grow(null, new BlockPos(4, 5, 6)));
        assertEquals(box(0, -1, 0, 3, 3, 9), SelectionModel.grow(CUBE, new BlockPos(2, -1, 9)));
        assertEquals(CUBE, SelectionModel.grow(CUBE, new BlockPos(1, 1, 1)), "a block inside changes nothing");
    }

    @Test
    void growingEveryFaceAndShrinkingKeepsOneBlock() {
        assertEquals(box(-1, -1, -1, 4, 4, 4), SelectionModel.expandAll(CUBE, 1));
        Box thin = SelectionModel.expandAll(CUBE, -10);
        assertEquals(1, thin.sizeX());
        assertEquals(1, thin.sizeY());
        assertEquals(1, thin.sizeZ());
    }

    @Test
    void nudgesAreCameraRelative() {
        assertArrayEquals(new int[] {0, 0, 1}, Nudge.direction(EditorAction.NUDGE_FORWARD, 0).orElseThrow(), "yaw 0 faces south");
        assertArrayEquals(new int[] {-1, 0, 0}, Nudge.direction(EditorAction.NUDGE_FORWARD, 90).orElseThrow(), "yaw 90 faces west");
        assertArrayEquals(new int[] {0, 0, -1}, Nudge.direction(EditorAction.NUDGE_FORWARD, 180).orElseThrow());
        assertArrayEquals(new int[] {1, 0, 0}, Nudge.direction(EditorAction.NUDGE_FORWARD, -90).orElseThrow());
        assertArrayEquals(new int[] {1, 0, 0}, Nudge.direction(EditorAction.NUDGE_FORWARD, 290).orElseThrow(), "snaps to the nearest axis");
        assertArrayEquals(new int[] {-1, 0, 0}, Nudge.direction(EditorAction.NUDGE_RIGHT, 0).orElseThrow(), "facing south, right is west");
        assertArrayEquals(new int[] {1, 0, 0}, Nudge.direction(EditorAction.NUDGE_LEFT, 0).orElseThrow());
        assertArrayEquals(new int[] {0, 0, -1}, Nudge.direction(EditorAction.NUDGE_BACK, 0).orElseThrow());
        assertArrayEquals(new int[] {0, 1, 0}, Nudge.direction(EditorAction.NUDGE_UP, 123).orElseThrow());
        assertArrayEquals(new int[] {0, -1, 0}, Nudge.direction(EditorAction.NUDGE_DOWN, 123).orElseThrow());
        assertTrue(Nudge.direction(EditorAction.COPY, 0).isEmpty());
        assertEquals(CUBE.offset(0, 0, 10), SelectionModel.nudge(CUBE, 0, 0, 10));
    }

    // ---- Corners and labels ----

    @Test
    void editingACornerKeepsTheBoxInOrder() {
        assertEquals(box(-5, 0, 0, 3, 3, 3), SelectionModel.withMin(CUBE, new BlockPos(-5, 0, 0)));
        assertEquals(box(3, 0, 0, 8, 3, 3), SelectionModel.withMin(CUBE, new BlockPos(8, 0, 0)), "min past max swaps");
        assertEquals(box(0, 0, 0, 3, 10, 3), SelectionModel.withMax(CUBE, new BlockPos(3, 10, 3)));
    }

    @Test
    void labelsAndGeometry() {
        assertEquals("4 × 4 × 4", SelectionModel.dimensions(CUBE));
        assertEquals("1,234,567", SelectionModel.count(1_234_567));
        var aabb = SelectionModel.toAabb(CUBE);
        assertEquals(0, aabb.minX());
        assertEquals(4, aabb.maxX());
        assertNull(SelectionModel.intersectPlane(new Ray(0, 5, 0, 1, 0, 0), 1, 4), "parallel to the plane");
        assertNull(SelectionModel.intersectPlane(new Ray(0, 5, 0, 0, 1, 0), 1, 4), "plane behind the ray");
        assertArrayEquals(new double[] {0, 4, 0}, SelectionModel.intersectPlane(new Ray(0, 5, 0, 0, -1, 0), 1, 4), 1e-9);
    }

    // ---- Regions ----

    /** Every cell of {@code region} moved as a move with {@code t} moves it: local cells mapped into the box at {@code min}. */
    private static Set<BlockPos> mappedCells(Region region, BlockPos min, Transform t) {
        Box source = region.bounds();
        Set<BlockPos> cells = new HashSet<>();
        for (BlockPos cell : CellSets.cells(region)) {
            int lx = cell.x() - source.min().x();
            int lz = cell.z() - source.min().z();
            cells.add(new BlockPos(min.x() + t.mapX(lx, lz, source.sizeX(), source.sizeZ()),
                    min.y() + t.mapY(cell.y() - source.min().y(), source.sizeY()),
                    min.z() + t.mapZ(lx, lz, source.sizeX(), source.sizeZ())));
        }
        return cells;
    }

    @Test
    void resizingKeepsABoxABoxAndAShapeItsKindAndFacing() {
        Box other = box(1, 2, 3, 9, 9, 9);
        assertEquals(new Region.Cuboid(other), SelectionModel.withBounds(new Region.Cuboid(CUBE), other));
        assertEquals(new Region.Shape(other, ShapeKind.PYRAMID, Facing.WEST),
                SelectionModel.withBounds(new Region.Shape(CUBE, ShapeKind.PYRAMID, Facing.WEST), other));
        Region.Cells cells = new Region.Cells(CellSets.of(new BlockPos(0, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> SelectionModel.withBounds(cells, other));
        assertTrue(SelectionModel.resizable(new Region.Cuboid(CUBE)));
        assertFalse(SelectionModel.resizable(cells));
        assertEquals(cells.translate(4, 5, 6), SelectionModel.movedTo(cells, new BlockPos(4, 5, 6)));
    }

    @Test
    void facingsTurnWithTheTransform() {
        assertEquals(Facing.SOUTH, SelectionModel.turned(Facing.EAST, Transform.rotation(1)));
        assertEquals(Facing.WEST, SelectionModel.turned(Facing.EAST, Transform.rotation(2)));
        assertEquals(Facing.NORTH, SelectionModel.turned(Facing.EAST, Transform.rotation(3)));
        assertEquals(Facing.EAST, SelectionModel.turned(Facing.NORTH, Transform.rotation(1)));
        assertEquals(Facing.WEST, SelectionModel.turned(Facing.EAST, new Transform(0, Mirror.X)));
        assertEquals(Facing.EAST, SelectionModel.turned(Facing.EAST, new Transform(0, Mirror.Z)));
        assertEquals(Facing.SOUTH, SelectionModel.turned(Facing.NORTH, new Transform(0, Mirror.Z)));
        for (Transform t : Transform.all()) {
            assertEquals(Facing.UP, SelectionModel.turned(Facing.UP, t));
            assertEquals(Facing.DOWN, SelectionModel.turned(Facing.DOWN, t));
            assertEquals(Facing.DOWN, SelectionModel.turned(Facing.UP, t.withUpsideDown(true)));
            assertEquals(SelectionModel.turned(Facing.EAST, t), SelectionModel.turned(Facing.EAST, t.withUpsideDown(true)));
        }
    }

    @Test
    void aMovedShapeHoldsExactlyItsCellsMovedAsTheServerMovesThem() {
        // A 5 × 3 × 7 box: every kind, facing and transform, compared cell by cell with the move's own mapping.
        Box source = box(10, 64, -3, 14, 66, 3);
        BlockPos destination = new BlockPos(-7, 70, 20);
        for (ShapeKind kind : ShapeKind.values()) {
            for (Facing facing : Facing.values()) {
                Region.Shape shape = new Region.Shape(source, kind, facing);
                for (Transform t : Transform.allWithFlips()) {
                    Region moved = SelectionModel.moved(shape, destination, t);
                    assertInstanceOf(Region.Shape.class, moved);
                    assertEquals(mappedCells(shape, destination, t), CellSets.cells(moved), kind + " " + facing + " " + t);
                }
            }
        }
    }

    @Test
    void aMovedBoxOrCellSetMovesCellByCell() {
        Box source = box(0, 0, 0, 4, 1, 2);
        BlockPos destination = new BlockPos(100, 5, -50);
        Region.Cells cells = new Region.Cells(CellSets.of(new BlockPos(0, 0, 0), new BlockPos(4, 1, 0),
                new BlockPos(2, 0, 2), new BlockPos(3, 1, 1)));
        for (Transform t : Transform.allWithFlips()) {
            Region box = SelectionModel.moved(new Region.Cuboid(source), destination, t);
            assertEquals(mappedCells(new Region.Cuboid(source), destination, t), CellSets.cells(box), t.toString());
            assertEquals(mappedCells(cells, destination, t), CellSets.cells(SelectionModel.moved(cells, destination, t)),
                    t.toString());
        }
        assertEquals(cells.translate(100, 5, -50), SelectionModel.moved(cells, destination, Transform.IDENTITY));
    }
}
