package dev.sculptory.fabric.client.editor.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

class GizmoPickTest {
    /**
     * Oblique fake projection, 20 px per block: X to the right, Y up, Z down-screen at half rate.
     * With scale 1 the arrows run from (100,100) to X (120,100), Y (100,80), Z (100,110); the up ring is the ellipse
     * (100 + 15 cos a, 100 + 7.5 sin a), the north-south ring the circle (100 + 15 cos a, 100 - 15 sin a) and the
     * east-west ring the segment x = 100 from y = 83.2 to 116.8.
     */
    private static final ScreenProjector OBLIQUE = (x, y, z, out) -> {
        out[0] = 100 + 20 * x;
        out[1] = 100 - 20 * y + 10 * z;
        return true;
    };

    private static GizmoPick.Picked pick(double cursorX, double cursorY) {
        return GizmoPick.pick(OBLIQUE, 0, 0, 0, 1.0, cursorX, cursorY, GizmoPick.PICK_RADIUS_PX).orElseThrow();
    }

    @Test
    void picksEachAxisNearItsProjectedSegment() {
        GizmoPick.Picked x = pick(106, 101);
        assertEquals(GizmoPick.Handle.AXIS_X, x.handle());
        assertEquals(1, x.distancePx(), 1e-9);

        GizmoPick.Picked y = pick(102, 88);
        assertEquals(GizmoPick.Handle.AXIS_Y, y.handle());
        assertEquals(2, y.distancePx(), 1e-9);

        GizmoPick.Picked z = pick(101, 104);
        assertEquals(GizmoPick.Handle.AXIS_Z, z.handle());
        assertEquals(1, z.distancePx(), 1e-9);
    }

    @Test
    void picksTheRotationRing() {
        GizmoPick.Picked ring = pick(89, 105);
        assertEquals(GizmoPick.Handle.ROTATE_Y, ring.handle());
        assertTrue(ring.distancePx() < 1.5);
    }

    @Test
    void respectsThePickRadius() {
        assertEquals(GizmoPick.Handle.AXIS_Y, pick(96, 79).handle()); // 4.1 px from the Y arrow, farther from the rings
        assertTrue(GizmoPick.pick(OBLIQUE, 0, 0, 0, 1.0, 93, 78, 6).isEmpty()); // 7.3 px
        assertTrue(GizmoPick.pick(OBLIQUE, 0, 0, 0, 1.0, 160, 160, 6).isEmpty());
    }

    @Test
    void nothingIsPickedBehindTheCamera() {
        ScreenProjector behind = (x, y, z, out) -> false;
        assertTrue(GizmoPick.pick(behind, 0, 0, 0, 1.0, 100, 100, 6).isEmpty());
    }

    @Test
    void picksThroughARealCameraAtConstantScreenSize() {
        // Camera 10 blocks from the gizmo, looking down at it from the south.
        Matrix4f view = new Matrix4f().lookAt(0, 0, 0, 0, -6, -8, 0, 1, 0);
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(70), 1f, 0.05f, 1024f);
        CameraSnapshot camera = new CameraSnapshot(projection, view, 0, 6, 8, 1000, 1000, 1);
        double scale = GizmoPick.scale(10);
        double[] onAxis = new double[2];
        assertTrue(camera.projector(1000, 1000).project(0.3 * scale, 0, 0, onAxis));

        GizmoPick.Picked picked = GizmoPick.pick(camera, 0, 0, 0, onAxis[0], onAxis[1] + 2, 1000, 1000).orElseThrow();
        assertEquals(GizmoPick.Handle.AXIS_X, picked.handle());
        assertEquals(2, picked.distancePx(), 0.05);
    }

    @Test
    void closestPointOnTheAxisFollowsTheCursorRay() {
        // Straight down onto the X axis at x = 3.4.
        assertEquals(3.4, GizmoPick.closestAxisParameter(0, 64, 0, 0, new Ray(3.4, 80, 0.5, 0, -1, 0)), 1e-9);
        // Obliquely through (2.6, 0, 0).
        assertEquals(2.6, GizmoPick.closestAxisParameter(0, 0, 0, 0, new Ray(2.6, 10, 10, 0, -10, -10)), 1e-9);
        // Horizontally at the Y axis, 7.4 above its origin.
        assertEquals(7.4, GizmoPick.closestAxisParameter(0, 60, 0, 1, new Ray(5, 67.4, 0, -1, 0, 0)), 1e-9);
        // Parallel to the axis: no answer.
        assertTrue(Double.isNaN(GizmoPick.closestAxisParameter(0, 0, 0, 0, new Ray(0, 5, 0, 1, 0, 0))));
    }

    @Test
    void axisDragSnapsToWholeBlocks() {
        assertEquals(3, GizmoPick.snapBlocks(0, 3.4));
        assertEquals(4, GizmoPick.snapBlocks(0, 3.6));
        assertEquals(-3, GizmoPick.snapBlocks(0.5, -2.1));
        assertEquals(0, GizmoPick.snapBlocks(Double.NaN, 3));

        GizmoPick.AxisDrag drag = GizmoPick.AxisDrag.begin(GizmoPick.Handle.AXIS_X, 10, 64, 10, new Ray(10.3, 80, 10, 0, -1, 0))
                .orElseThrow();
        assertEquals(0, drag.update(new Ray(10.7, 80, 10, 0, -1, 0)));
        assertEquals(2, drag.update(new Ray(12.2, 80, 10, 0, -1, 0)));
        assertEquals(-5, drag.update(new Ray(5.1, 80, 12, 0, -1, -0.1)));
        // A ray parallel to the axis keeps the last offset.
        assertEquals(-5, drag.update(new Ray(0, 64, 10, 1, 0, 0)));
        assertEquals(-5, drag.blocks());

        assertTrue(GizmoPick.AxisDrag.begin(GizmoPick.Handle.ROTATE_Y, 0, 0, 0, new Ray(0, 5, 0, 0, -1, 0)).isEmpty());
        assertTrue(GizmoPick.AxisDrag.begin(GizmoPick.Handle.AXIS_Z, 0, 0, 0, new Ray(0, 0, -5, 0, 0, 1)).isEmpty());
    }

    @Test
    void ringDragSnapsToQuarterTurns() {
        double east = GizmoPick.ringAngle(0, 64, 0, new Ray(1, 70, 0, 0, -1, 0));
        double south = GizmoPick.ringAngle(0, 64, 0, new Ray(0, 70, 1, 0, -1, 0));
        double west = GizmoPick.ringAngle(0, 64, 0, new Ray(-1, 70, 0, 0, -1, 0));
        assertEquals(0, east, 1e-9);
        assertEquals(1, GizmoPick.snapQuarterTurns(east, south));
        assertEquals(2, Math.abs(GizmoPick.snapQuarterTurns(east, west)));
        assertEquals(0, GizmoPick.snapQuarterTurns(0.9 * Math.PI, -0.9 * Math.PI)); // across the ±π seam
        assertTrue(Double.isNaN(GizmoPick.ringAngle(0, 64, 0, new Ray(0, 70, 0, 1, 0, 0))));
    }

    @Test
    void picksTheEastWestAndNorthSouthRings() {
        // The north-south ring: the circle, here at 200 degrees (85.9, 105.1), away from the up ring.
        assertEquals(GizmoPick.Handle.ROTATE_Z, pick(86, 105).handle());
        // The east-west ring: the segment x = 100, below the Z arrow's tip.
        assertEquals(GizmoPick.Handle.ROTATE_X, pick(101, 113).handle());
        assertEquals(1, GizmoPick.Handle.ROTATE_Y.ringAxis());
        assertEquals(GizmoPick.Handle.ROTATE_X, GizmoPick.Handle.ofRing(0));
        assertTrue(GizmoPick.Handle.ROTATE_Z.isRing() && !GizmoPick.Handle.ROTATE_Z.isAxis());
    }

    @Test
    void eachRingMeasuresItsAngleTheWayItsOrientationAngleTurns() {
        // Up ring: east 0, south +90 (yaw: east to south).
        assertEquals(Math.PI / 2, GizmoPick.ringAngle(1, 0, 64, 0, new Ray(0, 70, 1, 0, -1, 0)), 1e-9);
        // East-west ring (pitch: up to south): from the east, up is 0 and south +90.
        assertEquals(0, GizmoPick.ringAngle(0, 0, 64, 0, new Ray(5, 65, 0, -1, 0, 0)), 1e-9);
        assertEquals(Math.PI / 2, GizmoPick.ringAngle(0, 0, 64, 0, new Ray(5, 64, 1, -1, 0, 0)), 1e-9);
        // North-south ring (roll: east to up): from the south, east is 0 and up +90.
        assertEquals(0, GizmoPick.ringAngle(2, 0, 64, 0, new Ray(1, 64, 5, 0, 0, -1)), 1e-9);
        assertEquals(Math.PI / 2, GizmoPick.ringAngle(2, 0, 64, 0, new Ray(0, 65, 5, 0, 0, -1)), 1e-9);
        // A ray along the plane, or pointing away from it, gives none.
        assertTrue(Double.isNaN(GizmoPick.ringAngle(0, 0, 64, 0, new Ray(5, 65, 0, 0, 0, 1))));
        assertTrue(Double.isNaN(GizmoPick.ringAngle(0, 0, 64, 0, new Ray(5, 65, 0, 1, 0, 0))));
    }

    @Test
    void ringDragsSnapEveryFifteenDegreesAndTurnFreelyWithControl() {
        assertEquals(1_500, GizmoPick.snapTurn(Math.toRadians(14), false));
        assertEquals(0, GizmoPick.snapTurn(Math.toRadians(7), false));
        assertEquals(-4_500, GizmoPick.snapTurn(Math.toRadians(-47), false));
        assertEquals(1_423, GizmoPick.snapTurn(Math.toRadians(14.23), true));
        // Grabbed east of the up ring, dragged round past a half turn: the turn keeps counting.
        GizmoPick.RingDrag drag = GizmoPick.RingDrag.begin(GizmoPick.Handle.ROTATE_Y, 0, 64, 0,
                new Ray(1, 70, 0, 0, -1, 0)).orElseThrow();
        assertEquals(1, drag.axis());
        assertEquals(3_000, drag.update(ray(29), false));
        assertEquals(2_900, drag.update(ray(29), true));
        assertEquals(9_000, drag.update(ray(90), false));
        assertEquals(18_000, drag.update(ray(178), false));
        assertEquals(27_000, drag.update(ray(268), false));
        // A ray missing the plane keeps the last turn.
        assertEquals(27_000, drag.update(new Ray(0, 70, 0, 1, 0, 0), false));
        assertTrue(GizmoPick.RingDrag.begin(GizmoPick.Handle.AXIS_X, 0, 64, 0, new Ray(1, 70, 0, 0, -1, 0)).isEmpty());
    }

    /** Straight down onto the up ring at {@code degrees} from east towards south. */
    @Test
    void thePointGizmoPicksTheCentreThenAPlaneSquareThenAnArrow() {
        assertEquals(GizmoPick.Handle.CENTRE, pickPoint(102, 101).handle());
        // The level square spans x 0.25-0.5 and z 0.25-0.5: screen x 105-110, y 102.5-105 (nearer the X arrow than 6 px).
        GizmoPick.Picked level = pickPoint(107.5, 103.75);
        assertEquals(GizmoPick.Handle.PLANE_Y, level.handle());
        assertEquals(0, level.distancePx());
        assertEquals(GizmoPick.Handle.PLANE_Z, pickPoint(107.5, 92.5).handle());
        assertEquals(GizmoPick.Handle.AXIS_X, pickPoint(115, 99).handle());
        assertTrue(GizmoPick.pickPoint(OBLIQUE, 0, 0, 0, 1.0, 160, 160, 6).isEmpty());
        // The square across X is seen edge on here: it holds nothing, and no ring is ever picked.
        assertTrue(GizmoPick.pickPoint(OBLIQUE, 0, 0, 0, 1.0, 89, 105, 6).isEmpty());
    }

    @Test
    void aPlaneDragMovesWholeBlocksInItsPlaneOnly() {
        GizmoPick.PlaneDrag drag = GizmoPick.PlaneDrag.begin(GizmoPick.Handle.PLANE_Y, 0.5, 64.5, 0.5,
                new Ray(0.3, 80, 0.3, 0, -1, 0)).orElseThrow();
        assertEquals(java.util.List.of(3, 0, -3), java.util.Arrays.stream(drag.update(new Ray(3.4, 80, -2.6, 0, -1, 0))).boxed().toList());
        // A ray along the plane keeps the last offset.
        assertEquals(java.util.List.of(3, 0, -3), java.util.Arrays.stream(drag.update(new Ray(0, 64.5, 0, 1, 0, 0))).boxed().toList());
        assertTrue(GizmoPick.PlaneDrag.begin(GizmoPick.Handle.PLANE_Y, 0, 64, 0, new Ray(0, 80, 0, 1, 0, 0)).isEmpty());
        assertTrue(GizmoPick.PlaneDrag.begin(GizmoPick.Handle.AXIS_X, 0, 64, 0, new Ray(0, 80, 0, 0, -1, 0)).isEmpty());
        assertTrue(GizmoPick.Handle.PLANE_Y.moves(0) && !GizmoPick.Handle.PLANE_Y.moves(1) && GizmoPick.Handle.PLANE_Y.moves(2));
        assertTrue(GizmoPick.Handle.AXIS_Y.moves(1) && !GizmoPick.Handle.AXIS_Y.moves(0));
        assertTrue(GizmoPick.Handle.CENTRE.moves(0) && !GizmoPick.Handle.ROTATE_Y.moves(1));
    }

    private static GizmoPick.Picked pickPoint(double cursorX, double cursorY) {
        return GizmoPick.pickPoint(OBLIQUE, 0, 0, 0, 1.0, cursorX, cursorY, GizmoPick.PICK_RADIUS_PX).orElseThrow();
    }

    private static Ray ray(double degrees) {
        double a = Math.toRadians(degrees);
        return new Ray(Math.cos(a), 70, Math.sin(a), 0, -1, 0);
    }
}
