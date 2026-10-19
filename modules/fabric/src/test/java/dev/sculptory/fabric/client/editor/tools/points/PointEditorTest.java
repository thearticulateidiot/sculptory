package dev.sculptory.fabric.client.editor.tools.points;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.path.PathSample;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.core.world.WorldReader;
import dev.sculptory.fabric.client.editor.EditorBackend;
import dev.sculptory.fabric.client.editor.EditorContext;
import dev.sculptory.fabric.client.editor.tool.EditorAction;
import dev.sculptory.fabric.client.editor.tool.FrameInfo;
import dev.sculptory.fabric.client.editor.tool.Modifiers;
import dev.sculptory.fabric.client.editor.tool.PointerEvent;
import dev.sculptory.fabric.client.editor.tool.ScrollEvent;
import dev.sculptory.fabric.client.editor.tool.ToolContext;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tool.WorldCursor;
import dev.sculptory.fabric.client.editor.tool.WorldDraw;
import dev.sculptory.fabric.client.editor.tools.generate.AirPoints;
import dev.sculptory.fabric.client.editor.tools.generate.PathModel;
import dev.sculptory.fabric.client.editor.world.GizmoPick;
import dev.sculptory.fabric.client.editor.world.Ray;
import dev.sculptory.fabric.client.editor.world.ScreenProjector;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.Notice;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/**
 * The shared point editor: the edits, their events and selection (outline and inside), Esc and Backspace, the gizmo's
 * drags (never onto terrain), a held point following the camera, the drawn path's insert and the insert key.
 */
class PointEditorTest {
    private final FakeStateSpace states = new FakeStateSpace();
    private final FakeWorld world = new FakeWorld(states);
    private final List<Notice> notices = new ArrayList<>();
    private final EditorContext ctx = new EditorContext(() -> new EditorBackend() {
        @Override
        public Optional<EditorSession> session() {
            return Optional.empty();
        }

        @Override
        public StateSpace states() {
            return states;
        }

        @Override
        public WorldReader world() {
            return world;
        }
    }, notices::add);
    private final ToolContext c = ctx.contextFor(ToolId.GENERATE);
    private final List<PointEditor.Edit> edits = new ArrayList<>();

    private PointEditor editor(PathModel model, int maxPoints) {
        return new PointEditor(model, new AirPoints(), Optional::empty, new PointEditor.Host() {
            @Override
            public BlockPos pointOn(WorldCursor cursor) {
                return cursor.pos().offset(0, 1, 0); // on top of the block, as Smart Surface puts them
            }

            @Override
            public int maxPoints() {
                return maxPoints;
            }

            @Override
            public void edited(ToolContext context, PointEditor.Edit edit) {
                edits.add(edit);
            }
        });
    }

    private static PathModel.PointRef outline(int index) {
        return new PathModel.PointRef(PathModel.Role.OUTLINE, index);
    }

    private static BlockPos p(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    private static WorldCursor block(int x, int y, int z) {
        return new WorldCursor(p(x, y, z), WorldCursor.Face.UP, x + 0.5, y + 1, z + 0.5, false);
    }

    private void click(PointEditor editor, WorldCursor cursor) {
        editor.onPointer(c, new PointerEvent(PointerEvent.Kind.PRESS, PointerEvent.LEFT, 10, 10, 0, cursor));
    }

    @Test
    void addingDrawsOnFromTheSelectedEndAndAMiddlePointOnlyDeselects() {
        PointEditor editor = editor(new PathModel(), 64);
        click(editor, block(0, 64, 0));
        click(editor, block(5, 64, 0));
        assertEquals(List.of(p(0, 65, 0), p(5, 65, 0)), editor.points().nodes());
        assertEquals(1, editor.selected(), "the new end point is selected");
        assertEquals(List.of(PointEditor.EditKind.ADD, PointEditor.EditKind.ADD),
                edits.stream().map(PointEditor.Edit::kind).toList());
        // Clicking a point (its column) selects it; a click away with a middle point selected only deselects.
        click(editor, block(0, 64, 0));
        assertEquals(0, editor.selected());
        click(editor, block(9, 64, 9));
        assertEquals(-1, editor.selected());
        assertEquals(2, editor.points().size());
        click(editor, block(9, 64, 9));
        assertEquals(3, editor.points().size(), "nothing selected: the click adds the next end point");
        assertEquals(2, editor.selected());
    }

    @Test
    void theGuideLineRunsFromTheSelectedEndToTheCursorsTarget() {
        PointEditor editor = editor(new PathModel(), 64);
        editor.addEnd(c, p(0, 65, 0));
        editor.frame(c, new FrameInfo(0, 0f, 10, 10, block(3, 64, 0)));
        assertEquals(List.of(0.5, 65.5, 0.5, 3.5, 65.5, 0.5),
                java.util.Arrays.stream(editor.guideLine().orElseThrow()).boxed().toList());
        editor.addEnd(c, p(6, 65, 0));
        editor.select(c, outline(0));
        editor.frame(c, new FrameInfo(0, 0f, 10, 10, block(3, 64, 0)));
        assertTrue(editor.guideLine().isEmpty(), "a middle point shows no guide line");
    }

    @Test
    void insertsKeepOrderSelectTheNewPointAndAClosedOutlineHasItsClosingSegment() {
        PathModel outline = PathModel.closedOutline();
        PointEditor editor = editor(outline, 64);
        editor.addEnd(c, p(0, 64, 0));
        editor.addEnd(c, p(10, 64, 0));
        assertEquals(1, outline.segmentCount(), "two points: one segment, closed or not");
        editor.addEnd(c, p(10, 64, 10));
        assertEquals(3, outline.segmentCount());
        assertEquals(0, outline.segmentEnd(2), "the last segment closes back to the first point");
        assertEquals(3, PointEditor.chords(outline.nodes(), true).size());
        assertEquals(1, editor.insertAfter(c, 0, p(5, 64, 0)));
        assertEquals(1, editor.selected());
        assertEquals(4, editor.insertOnSegment(c, 3, p(5, 64, 5)), "on the closing segment: after the last point");
        assertEquals(List.of(p(0, 64, 0), p(5, 64, 0), p(10, 64, 0), p(10, 64, 10), p(5, 64, 5)), outline.nodes());
        assertEquals(PointEditor.EditKind.INSERT, edits.get(edits.size() - 1).kind());
    }

    @Test
    void backspaceRemovesTheSelectedPointAndSelectsTheOneBeforeElseTheLast() {
        PointEditor editor = editor(new PathModel(), 64);
        for (int x = 0; x < 4; x++) editor.addEnd(c, p(x * 3, 64, 0));
        editor.select(c, outline(2));
        assertTrue(editor.onAction(c, EditorAction.REMOVE_NODE));
        assertEquals(List.of(p(0, 64, 0), p(3, 64, 0), p(9, 64, 0)), editor.points().nodes());
        assertEquals(1, editor.selected());
        editor.select(c, outline(0));
        assertTrue(editor.onAction(c, EditorAction.ERASE_SELECTION));
        assertEquals(0, editor.selected(), "the first removed: the new first is selected");
        editor.select(c, null);
        assertTrue(editor.onAction(c, EditorAction.REMOVE_NODE));
        assertEquals(List.of(p(3, 64, 0)), editor.points().nodes(), "nothing selected: the last goes, as today");
        PointEditor.Edit removed = edits.get(edits.size() - 1);
        assertEquals(PointEditor.EditKind.REMOVE, removed.kind());
        assertEquals(p(9, 64, 0), removed.before());
    }

    @Test
    void escDeselectsThenIsLeftToTheHost() {
        PointEditor editor = editor(new PathModel(), 64);
        editor.addEnd(c, p(0, 64, 0));
        assertTrue(editor.onAction(c, EditorAction.CANCEL));
        assertEquals(-1, editor.selected());
        assertFalse(editor.onAction(c, EditorAction.CANCEL), "nothing selected: the host's Esc (clear) runs");
        assertFalse(editor.onAction(c, EditorAction.COMMIT), "Enter is the host's");
        assertEquals(1, editor.points().size());
    }

    @Test
    void aOnePointHostMovesItsPointAndOthersRefuseOverTheirMaximum() {
        PointEditor tip = editor(new PathModel(), 1);
        tip.addEnd(c, p(0, 64, 0));
        tip.addEnd(c, p(4, 64, 0));
        assertEquals(List.of(p(4, 64, 0)), tip.points().nodes());
        assertEquals(PointEditor.EditKind.MOVE, edits.get(edits.size() - 1).kind());
        PointEditor two = editor(new PathModel(), 2);
        two.addEnd(c, p(0, 64, 0));
        two.addEnd(c, p(1, 64, 0));
        assertEquals(-1, two.addEnd(c, p(2, 64, 0)));
        assertEquals("sculptory.notice.line_max_points", notices.get(notices.size() - 1).key());
    }

    @Test
    void theSegmentNearestTheCursorOnScreenIsFound() {
        ScreenProjector top = (x, y, z, out) -> {
            out[0] = x * 10;
            out[1] = z * 10;
            return true;
        };
        List<BlockPos> path = List.of(p(0, 64, 0), p(10, 64, 0), p(10, 64, 10));
        var segments = PointEditor.chords(path, false);
        assertEquals(0, PointEditor.segmentAt(segments, top, 50, 7, 6));
        assertEquals(1, PointEditor.segmentAt(segments, top, 103, 60, 6));
        assertEquals(-1, PointEditor.segmentAt(segments, top, 50, 50, 6));
    }

    /** Smart Surface's host: a closed outline, points on top of the clicked block, clicks inside it are inside points. */
    private PointEditor surface() {
        return new PointEditor(PathModel.closedOutline(), new AirPoints(), Optional::empty, new PointEditor.Host() {
            @Override
            public BlockPos pointOn(WorldCursor cursor) {
                return cursor.pos().offset(0, 1, 0);
            }

            @Override
            public PathModel.Role roleOf(PathModel points, BlockPos point, boolean onTerrain) {
                return PointEditor.outlineOrInside(points, point, onTerrain);
            }

            @Override
            public int maxInsidePoints() {
                return 64;
            }

            @Override
            public void edited(ToolContext context, PointEditor.Edit edit) {
                edits.add(edit);
            }
        });
    }

    @Test
    void aClickInsideAClosedOutlineAddsAnInsidePointAndOneOutsideExtendsTheOutline() {
        PointEditor editor = surface();
        click(editor, block(0, 64, 0));
        click(editor, block(10, 64, 0));
        click(editor, block(10, 64, 10));
        assertEquals(List.of(p(0, 65, 0), p(10, 65, 0), p(10, 65, 10)), editor.points().nodes());
        click(editor, block(7, 66, 3));
        assertEquals(List.of(p(7, 67, 3)), editor.points().insideNodes());
        assertEquals(Optional.of(new PathModel.PointRef(PathModel.Role.INSIDE, 0)), editor.selection());
        assertEquals(-1, editor.selected(), "no outline point is selected");
        PointEditor.Edit added = edits.get(edits.size() - 1);
        assertEquals(PointEditor.EditKind.ADD, added.kind());
        assertEquals(PathModel.Role.INSIDE, added.role());
        // Outside, with the inside point selected: the outline grows after its last point.
        click(editor, block(-5, 64, 8));
        assertEquals(p(-5, 65, 8), editor.points().node(3));
        assertEquals(3, editor.selected());
        // With an outline point selected, the next outline point goes right after it.
        editor.select(c, outline(0));
        click(editor, block(5, 64, -6));
        assertEquals(p(5, 65, -6), editor.points().node(1));
        assertEquals(1, editor.selected());
    }

    @Test
    void anInsidePointIsPickedByItsColumnAndBackspaceRemovesItLeavingNothingSelected() {
        PointEditor editor = surface();
        for (BlockPos corner : List.of(p(0, 64, 0), p(12, 64, 0), p(12, 64, 12), p(0, 64, 12))) editor.addEnd(c, corner);
        editor.addInside(c, p(4, 70, 4));
        editor.addInside(c, p(8, 66, 8));
        editor.select(c, null);
        click(editor, block(4, 69, 4));
        assertEquals(Optional.of(new PathModel.PointRef(PathModel.Role.INSIDE, 0)), editor.selection());
        assertTrue(editor.onAction(c, EditorAction.REMOVE_NODE));
        assertEquals(List.of(p(8, 66, 8)), editor.points().insideNodes());
        assertTrue(editor.selection().isEmpty());
        assertEquals(4, editor.points().size(), "the outline is untouched");
        assertTrue(editor.move(c, new PathModel.PointRef(PathModel.Role.INSIDE, 0), p(8, 72, 8), true));
        assertEquals(PathModel.Role.INSIDE, edits.get(edits.size() - 1).role());
        editor.clear(c);
        assertTrue(editor.points().isEmpty());
        assertTrue(editor.points().insideNodes().isEmpty());
    }

    @Test
    void anOpenPathNeverTakesInsidePoints() {
        PointEditor line = editor(new PathModel(), 64);
        for (BlockPos corner : List.of(p(0, 64, 0), p(12, 64, 0), p(12, 64, 12))) line.addEnd(c, corner);
        click(line, block(8, 64, 4));
        assertTrue(line.points().insideNodes().isEmpty(), "the default role is the outline");
        assertEquals(4, line.points().size());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> line.addInside(c, p(1, 64, 1)));
        assertEquals(PathModel.Role.OUTLINE, PointEditor.outlineOrInside(line.points(), p(8, 64, 4), true));
    }

    @Test
    void insideTheOutlineFollowsTheEvenOddRuleSoANonConvexOutlineWorks() {
        // An L: the notch at the top right is outside.
        List<BlockPos> l = List.of(p(0, 0, 0), p(10, 0, 0), p(10, 0, 4), p(4, 0, 4), p(4, 0, 10), p(0, 0, 10));
        assertTrue(PointEditor.insideOutline(l, p(2, 64, 2)));
        assertTrue(PointEditor.insideOutline(l, p(8, 64, 2)));
        assertTrue(PointEditor.insideOutline(l, p(2, 64, 8)));
        assertFalse(PointEditor.insideOutline(l, p(8, 64, 8)), "the notch");
        assertFalse(PointEditor.insideOutline(l, p(-3, 64, 2)));
        assertFalse(PointEditor.insideOutline(l.subList(0, 2), p(2, 64, 0)), "fewer than three points");
        PathModel two = PathModel.closedOutline();
        two.add(p(0, 0, 0));
        two.add(p(10, 0, 10));
        assertEquals(PathModel.Role.OUTLINE, PointEditor.outlineOrInside(two, p(2, 0, 2), true));
    }

    // ---- Drags, the drawn path, the insert key and flying (a camera, a projection and a cursor ray) ----

    /** A camera for the editor: a fixed oblique projection, an eye and a cursor ray the test sets. */
    private static final class Camera implements PointEditor.View {
        /** Screen = (20 x + 8 z, -20 y + 8 z): every axis shows as a line of its own. */
        static final ScreenProjector OBLIQUE = (x, y, z, out) -> {
            out[0] = 20 * x + 8 * z;
            out[1] = -20 * y + 8 * z;
            return true;
        };
        double[] eye;
        Ray ray;

        Camera(double ex, double ey, double ez) {
            eye = new double[] {ex, ey, ez};
        }

        /** Aims from the eye at (x, y, z). */
        void aimAt(double x, double y, double z) {
            ray = new Ray(eye[0], eye[1], eye[2], x - eye[0], y - eye[1], z - eye[2]);
        }

        @Override
        public Optional<Ray> cursorRay() {
            return Optional.ofNullable(ray);
        }

        @Override
        public Optional<ScreenProjector> projector() {
            return Optional.of(OBLIQUE);
        }

        @Override
        public Optional<double[]> eye() {
            return Optional.of(eye);
        }
    }

    private PointEditor editor(PathModel model, Camera camera) {
        return new PointEditor(model, new AirPoints(), camera, new PointEditor.Host() {
            @Override
            public BlockPos pointOn(WorldCursor cursor) {
                return cursor.pos();
            }

            @Override
            public void edited(ToolContext context, PointEditor.Edit edit) {
                edits.add(edit);
            }
        });
    }

    private static double[] screen(double x, double y, double z) {
        double[] out = new double[2];
        Camera.OBLIQUE.project(x, y, z, out);
        return out;
    }

    private void pointer(PointEditor editor, PointerEvent.Kind kind, double[] mouse, WorldCursor cursor) {
        editor.onPointer(c, new PointerEvent(kind, PointerEvent.LEFT, mouse[0], mouse[1], 0, cursor));
    }

    private void frame(PointEditor editor, double[] mouse, WorldCursor cursor) {
        editor.frame(c, new FrameInfo(0, 0f, mouse[0], mouse[1], cursor));
    }

    /** A block far from every point: a drag over it shows the point never lands on the block under the cursor. */
    private static final WorldCursor TERRAIN = block(30, 50, -30);

    @Test
    void anArrowMovesAlongItsAxisOnlyAndNeverOntoTheBlockUnderTheCursor() {
        Camera camera = new Camera(0.5, 64.5, 20.5);
        PointEditor editor = editor(new PathModel(), camera);
        editor.addEnd(c, p(0, 64, 0));
        double[] centre = screen(0.5, 64.5, 0.5);
        double[] onX = {centre[0] + 40, centre[1]};
        camera.aimAt(0.5, 64.5, 0.5);
        frame(editor, onX, TERRAIN);
        assertEquals(Optional.of(GizmoPick.Handle.AXIS_X), editor.hoveredHandle(), "the X arrow lights up under the cursor");
        pointer(editor, PointerEvent.Kind.PRESS, onX, TERRAIN);
        assertEquals(PointEditor.DragMode.AXIS, editor.dragMode());
        assertTrue(editor.hoveredHandle().isEmpty(), "nothing is hovered while dragging");
        camera.aimAt(3.7, 64.5, 0.5);
        pointer(editor, PointerEvent.Kind.DRAG, onX, TERRAIN);
        assertEquals(p(3, 64, 0), editor.points().node(0), "three blocks east, nothing else");
        assertFalse(edits.get(edits.size() - 1).finished());
        camera.aimAt(5.5, 66.5, 0.5);
        pointer(editor, PointerEvent.Kind.RELEASE, onX, TERRAIN);
        assertEquals(p(5, 64, 0), editor.points().node(0), "the cursor above the axis still moves it along it only");
        assertTrue(edits.get(edits.size() - 1).finished());
        assertFalse(editor.dragging());
    }

    @Test
    void theGreenArrowMovesStraightUpAndTheDragDrawsItsGuideLine() {
        Camera camera = new Camera(0.5, 64.5, 20.5);
        PointEditor editor = editor(new PathModel(), camera);
        editor.addEnd(c, p(0, 64, 0));
        double[] centre = screen(0.5, 64.5, 0.5);
        double[] onY = {centre[0], centre[1] - 40};
        camera.aimAt(0.5, 64.5, 0.5);
        pointer(editor, PointerEvent.Kind.PRESS, onY, TERRAIN);
        assertEquals(Optional.of(GizmoPick.Handle.AXIS_Y), editor.activeHandle());
        camera.aimAt(0.5, 70.5, 0.5);
        pointer(editor, PointerEvent.Kind.DRAG, onY, TERRAIN);
        assertEquals(p(0, 70, 0), editor.points().node(0));
        RecordingDraw draw = new RecordingDraw();
        editor.render(draw, 0xFFFFFFFF, 0xFF00FFFF);
        assertTrue(draw.lines.stream().anyMatch(l -> l[1] <= 70.5 - 200 && l[4] >= 70.5 + 200 && l[0] == l[3]),
                "a long vertical guide line through the point");
        assertTrue(editor.guideLine().isEmpty(), "no drawing guide line while dragging");
        assertTrue(editor.onAction(c, EditorAction.CANCEL), "Esc puts it back");
        assertEquals(p(0, 64, 0), editor.points().node(0));
        assertFalse(editor.dragging());
    }

    @Test
    void theLevelSquareSlidesThePointSidewaysAtItsHeight() {
        Camera camera = new Camera(0.5, 74.5, 10.5);
        PointEditor editor = editor(new PathModel(), camera);
        editor.addEnd(c, p(0, 64, 0));
        double[] centre = screen(0.5, 64.5, 0.5);
        double[] onLevel = {centre[0] + 17, centre[1] + 5};
        camera.aimAt(0.5, 64.5, 0.5);
        pointer(editor, PointerEvent.Kind.PRESS, onLevel, TERRAIN);
        assertEquals(PointEditor.DragMode.PLANE, editor.dragMode());
        assertEquals(Optional.of(GizmoPick.Handle.PLANE_Y), editor.activeHandle());
        camera.aimAt(2.5, 64.5, -2.5);
        pointer(editor, PointerEvent.Kind.DRAG, onLevel, TERRAIN);
        assertEquals(p(2, 64, -3), editor.points().node(0), "two east, three north, at its height");
        pointer(editor, PointerEvent.Kind.RELEASE, onLevel, TERRAIN);
        assertEquals(p(2, 64, -3), editor.points().node(0));
        assertTrue(edits.get(edits.size() - 1).finished(), "the release says the drag is done");
    }

    @Test
    void theCentreAndAClickedPointDragFreelyAtTheirDistanceAndAltScrollChangesIt() {
        Camera camera = new Camera(0.5, 64.5, 20.5);
        PointEditor editor = editor(new PathModel(), camera);
        editor.addEnd(c, p(0, 64, 0));
        double[] centre = screen(0.5, 64.5, 0.5);
        camera.aimAt(0.5, 64.5, 0.5);
        pointer(editor, PointerEvent.Kind.PRESS, centre, TERRAIN);
        assertEquals(PointEditor.DragMode.FREE, editor.dragMode());
        assertEquals(Optional.of(GizmoPick.Handle.CENTRE), editor.activeHandle());
        // Aimed 0.6 east for 0.8 north: the point stays 20 blocks from the camera along the new ray.
        camera.ray = new Ray(0.5, 64.5, 20.5, 0.6, 0, -0.8);
        pointer(editor, PointerEvent.Kind.DRAG, centre, block(13, 64, 4));
        assertEquals(p(12, 64, 4), editor.points().node(0), "on the ray at 20 blocks, not on the block aimed at");
        assertTrue(editor.onScroll(c, new ScrollEvent(-1, Modifiers.ALT | Modifiers.SHIFT)));
        assertEquals(p(10, 64, 7), editor.points().node(0), "Alt+Scroll pulls it 4 blocks nearer at once");
        pointer(editor, PointerEvent.Kind.RELEASE, centre, block(13, 64, 4));
        assertFalse(editor.dragging());
        // A point grabbed by a click (not the selected one) drags the same way; a click without moving moves nothing.
        editor.addEnd(c, p(0, 64, -10));
        camera.aimAt(10.5, 64.5, 7.5);
        pointer(editor, PointerEvent.Kind.PRESS, new double[] {0, 0}, TERRAIN);
        assertEquals(0, editor.selected(), "picked along its ray");
        assertEquals(PointEditor.DragMode.FREE, editor.dragMode());
        camera.aimAt(0.5, 64.5, -9.5);
        pointer(editor, PointerEvent.Kind.RELEASE, new double[] {0, 0}, TERRAIN);
        assertEquals(p(10, 64, 7), editor.points().node(0), "pressed and released without a drag: it stays");
    }

    @Test
    void aHeldPointFollowsTheCameraWhenFlying() {
        Camera camera = new Camera(0.5, 64.5, 20.5);
        PointEditor editor = editor(new PathModel(), camera);
        editor.addEnd(c, p(0, 64, 0));
        double[] centre = screen(0.5, 64.5, 0.5);
        camera.aimAt(0.5, 64.5, 0.5);
        pointer(editor, PointerEvent.Kind.PRESS, centre, TERRAIN);
        // Flying 5 east and 3 up with the mouse held still: each frame works the drag out from the new ray.
        camera.eye = new double[] {5.5, 67.5, 20.5};
        camera.ray = new Ray(5.5, 67.5, 20.5, 0, 0, -1);
        frame(editor, centre, TERRAIN);
        assertEquals(p(5, 67, 0), editor.points().node(0));
        pointer(editor, PointerEvent.Kind.RELEASE, centre, TERRAIN);
        // An arrow drag follows the camera along its axis only.
        camera.aimAt(5.5, 67.5, 0.5);
        double[] at = screen(5.5, 67.5, 0.5);
        double[] onX = {at[0] + 40, at[1]};
        pointer(editor, PointerEvent.Kind.PRESS, onX, TERRAIN);
        assertEquals(PointEditor.DragMode.AXIS, editor.dragMode());
        camera.eye = new double[] {9.5, 70.5, 20.5};
        camera.ray = new Ray(9.5, 70.5, 20.5, 0, 0, -1);
        frame(editor, onX, TERRAIN);
        assertEquals(p(9, 67, 0), editor.points().node(0), "east with the camera, not up");
    }

    @Test
    void theDrawnPathHighlightsUnderTheCursorAndAClickInsertsAPointThere() {
        Camera camera = new Camera(5.5, 64.5, 30.5);
        PointEditor editor = editor(new PathModel(), camera);
        editor.addEnd(c, p(0, 64, 0));
        editor.addEnd(c, p(10, 64, 0));
        editor.select(c, null);
        double[] middle = screen(6.5, 64.5, 0.5);
        WorldCursor ground = block(6, 40, 0); // the block aimed at lies far below the path
        frame(editor, middle, ground);
        assertEquals(OptionalInt.of(0), editor.hoveredSegment());
        RecordingDraw draw = new RecordingDraw();
        editor.render(draw, 0xFFFFFFFF, 0xFF00FFFF);
        assertTrue(draw.colours.contains(PointEditor.HOVERED_SEGMENT_COLOUR), "the hovered stretch is lit");
        pointer(editor, PointerEvent.Kind.PRESS, middle, ground);
        assertEquals(List.of(p(0, 64, 0), p(6, 64, 0), p(10, 64, 0)), editor.points().nodes());
        assertEquals(1, editor.selected(), "the inserted point is selected");
        assertEquals(PointEditor.EditKind.INSERT, edits.get(edits.size() - 1).kind());
        frame(editor, screen(3.5, 64.5, 0.5), ground);
        assertEquals(OptionalInt.of(0), editor.hoveredSegment(), "the new stretches hover too");
        frame(editor, screen(3.5, 70.5, 0.5), ground);
        assertTrue(editor.hoveredSegment().isEmpty(), "away from the path: none");
    }

    @Test
    void theInsertKeyAddsAPointAfterTheSelectedOneThatFollowsTheCursorUntilAClick() {
        PointEditor editor = editor(new PathModel(), 64);
        editor.addEnd(c, p(0, 64, 0));
        editor.addEnd(c, p(10, 64, 0));
        editor.select(c, null);
        assertFalse(editor.onAction(c, EditorAction.EXTRUDE_POINT), "nothing selected: E stays the inventory");
        editor.select(c, outline(0));
        editor.frame(c, new FrameInfo(0, 0f, 10, 10, block(3, 63, 4)));
        assertTrue(editor.onAction(c, EditorAction.EXTRUDE_POINT));
        assertTrue(editor.placingInserted());
        assertEquals(List.of(p(0, 64, 0), p(3, 64, 4), p(10, 64, 0)), editor.points().nodes(), "right after it");
        assertFalse(edits.get(edits.size() - 1).finished());
        editor.frame(c, new FrameInfo(0, 0f, 10, 10, block(5, 63, 6)));
        assertEquals(p(5, 64, 6), editor.points().node(1), "it follows the cursor");
        assertEquals(List.of(0.5, 64.5, 0.5, 5.5, 64.5, 6.5),
                Arrays.stream(editor.guideLine().orElseThrow()).boxed().toList(), "from the selected point");
        assertEquals(0, editor.selected(), "the selected point stays selected while it follows");
        click(editor, block(6, 63, 6));
        assertFalse(editor.placingInserted());
        assertEquals(p(6, 64, 6), editor.points().node(1), "placed where clicked");
        assertEquals(1, editor.selected(), "and selected");
        PointEditor.Edit placed = edits.get(edits.size() - 2);
        assertEquals(PointEditor.EditKind.MOVE, placed.kind());
        assertTrue(placed.finished());
        // Esc drops one being placed.
        assertTrue(editor.onAction(c, EditorAction.EXTRUDE_POINT));
        assertEquals(4, editor.points().size());
        assertTrue(editor.onAction(c, EditorAction.CANCEL));
        assertEquals(List.of(p(0, 64, 0), p(6, 64, 6), p(10, 64, 0)), editor.points().nodes());
        assertEquals(1, editor.selected());
        assertEquals(PointEditor.EditKind.REMOVE, edits.get(edits.size() - 1).kind());
    }

    @Test
    void theInsertKeyOnAnInsidePointAddsAnotherInsidePoint() {
        PointEditor editor = surface();
        for (BlockPos corner : List.of(p(0, 64, 0), p(12, 64, 0), p(12, 64, 12), p(0, 64, 12))) editor.addEnd(c, corner);
        editor.addInside(c, p(4, 66, 4));
        editor.frame(c, new FrameInfo(0, 0f, 10, 10, block(7, 64, 7)));
        assertTrue(editor.onAction(c, EditorAction.EXTRUDE_POINT));
        assertEquals(List.of(p(4, 66, 4), p(7, 65, 7)), editor.points().insideNodes());
        assertEquals(4, editor.points().size(), "the outline is untouched");
        click(editor, block(8, 64, 6));
        assertEquals(p(8, 65, 6), editor.points().insidePoint(1));
        assertEquals(Optional.of(new PathModel.PointRef(PathModel.Role.INSIDE, 1)), editor.selection());
    }

    @Test
    void theSamplesOfACurveAreCutAtItsPoints() {
        List<BlockPos> points = List.of(p(0, 64, 0), p(4, 64, 0), p(4, 64, 0), p(8, 64, 4));
        List<PathSample> samples = new ArrayList<>();
        for (double x = 0.5; x <= 4.5; x += 1) samples.add(new PathSample(x, 64.5, 0.5, x));
        for (double z = 1.5; z <= 4.5; z += 1) samples.add(new PathSample(4.5 + z - 0.5, 64.5, z, 4 + z));
        var segments = PointEditor.splitAtPoints(samples, points);
        assertEquals(3, segments.size());
        assertEquals(5, segments.get(0).size(), "the first runs from the first point to the second");
        assertEquals(segments.get(1).get(0), segments.get(1).get(1), "between equal points: that point");
        assertEquals(samples.get(samples.size() - 1), segments.get(2).get(segments.get(2).size() - 1));
        assertEquals(PointEditor.chords(List.of(p(0, 64, 0), p(9, 9, 9)), false),
                PointEditor.splitAtPoints(samples, List.of(p(0, 64, 0), p(9, 9, 9))), "samples of other points: chords");
    }

    /** Lines, as {x1, y1, z1, x2, y2, z2}, and their colours. */
    private static final class RecordingDraw implements WorldDraw {
        final List<double[]> lines = new ArrayList<>();
        final List<Integer> colours = new ArrayList<>();

        @Override
        public void boxOutline(Box box, int argb) {}

        @Override
        public void boxFill(Box box, int argb) {}

        @Override
        public void line(double x1, double y1, double z1, double x2, double y2, double z2, int argb) {
            lines.add(new double[] {x1, y1, z1, x2, y2, z2});
            colours.add(argb);
        }

        @Override
        public void ring(double centerX, double y, double centerZ, double radius, int argb) {}

        @Override
        public void seeThrough(boolean enabled) {}
    }

    @Test
    void aFreeDragSnapsOntoAnotherPointTheCursorIsOnSoALoopCloses() {
        Camera camera = new Camera(5.5, 64.5, 20.5);
        PointEditor editor = editor(new PathModel(), camera);
        editor.addEnd(c, p(0, 64, 0));
        editor.addEnd(c, p(10, 64, 0));
        double[] at = screen(10.5, 64.5, 0.5);
        camera.aimAt(10.5, 64.5, 0.5);
        pointer(editor, PointerEvent.Kind.PRESS, at, TERRAIN);
        assertEquals(PointEditor.DragMode.FREE, editor.dragMode());
        camera.aimAt(0.5, 64.5, 0.5);
        pointer(editor, PointerEvent.Kind.DRAG, at, TERRAIN);
        assertEquals(p(0, 64, 0), editor.points().node(1), "onto the first point's cell, not at the drag distance");
        camera.aimAt(3.5, 70.5, 0.5);
        pointer(editor, PointerEvent.Kind.DRAG, at, TERRAIN);
        assertTrue(!editor.points().node(1).equals(p(0, 64, 0)), "off it again once the cursor leaves it");
    }

    @Test
    void theInsertKeyIsNotTakenByAHostOfOnePoint() {
        PointEditor tip = editor(new PathModel(), 1);
        tip.addEnd(c, p(0, 64, 0));
        assertEquals(0, tip.selected());
        assertFalse(tip.onAction(c, EditorAction.EXTRUDE_POINT), "Extrude's tip: E stays the inventory");
        assertTrue(notices.isEmpty(), "and no toast");
    }

    @Test
    void aStretchFromTheHostsAnchorToTheFirstPointInsertsBeforeIt() {
        Camera camera = new Camera(2.5, 64.5, 20.5);
        PointEditor editor = new PointEditor(new PathModel(), new AirPoints(), camera, new PointEditor.Host() {
            @Override
            public BlockPos pointOn(WorldCursor cursor) {
                return cursor.pos();
            }

            @Override
            public List<List<PathSample>> drawnSegments(List<BlockPos> points, boolean closed) {
                // An anchor at (0, 64, 0) before the first point, as Extrude's face is.
                List<BlockPos> drawn = new ArrayList<>(List.of(p(0, 64, 0)));
                drawn.addAll(points);
                return PointEditor.chords(drawn, false);
            }

            @Override
            public void edited(ToolContext context, PointEditor.Edit edit) {
                edits.add(edit);
            }
        });
        editor.addEnd(c, p(6, 64, 0));
        editor.select(c, null);
        double[] middle = screen(3.5, 64.5, 0.5);
        frame(editor, middle, block(3, 40, 0));
        assertEquals(OptionalInt.of(0), editor.hoveredSegment(), "the anchor's stretch is drawn segment 0");
        pointer(editor, PointerEvent.Kind.PRESS, middle, block(3, 40, 0));
        assertEquals(List.of(p(3, 64, 0), p(6, 64, 0)), editor.points().nodes(), "inserted before the first point");
    }
}
