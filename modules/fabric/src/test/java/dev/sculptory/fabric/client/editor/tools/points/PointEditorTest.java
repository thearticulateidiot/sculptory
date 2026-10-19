package dev.sculptory.fabric.client.editor.tools.points;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.core.world.WorldReader;
import dev.sculptory.fabric.client.editor.EditorBackend;
import dev.sculptory.fabric.client.editor.EditorContext;
import dev.sculptory.fabric.client.editor.tool.EditorAction;
import dev.sculptory.fabric.client.editor.tool.FrameInfo;
import dev.sculptory.fabric.client.editor.tool.PointerEvent;
import dev.sculptory.fabric.client.editor.tool.ToolContext;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tool.WorldCursor;
import dev.sculptory.fabric.client.editor.tools.generate.AirPoints;
import dev.sculptory.fabric.client.editor.tools.generate.PathModel;
import dev.sculptory.fabric.client.editor.world.ScreenProjector;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.Notice;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The shared point editor's frozen parts: the edits, their events and selection (outline and inside), Esc and Backspace. */
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
}
