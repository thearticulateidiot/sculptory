package dev.sculptory.fabric.client.editor.tools.select;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.OpSymmetry;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.region.CellSet;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.ShapeKind;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.fabric.client.editor.EditorBackend;
import dev.sculptory.fabric.client.editor.EditorContext;
import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.mock.MockEditorSession;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.editor.tool.EditorAction;
import dev.sculptory.fabric.client.editor.tool.FrameInfo;
import dev.sculptory.fabric.client.editor.tool.KeyHint;
import dev.sculptory.fabric.client.editor.tool.Modifiers;
import dev.sculptory.fabric.client.editor.tool.PointerEvent;
import dev.sculptory.fabric.client.editor.tool.RegionWork;
import dev.sculptory.fabric.client.editor.tool.ScrollEvent;
import dev.sculptory.fabric.client.editor.tool.Selection;
import dev.sculptory.fabric.client.editor.tool.ToolContext;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tool.WorldCursor;
import dev.sculptory.fabric.client.editor.tool.WorldDraw;
import dev.sculptory.fabric.client.editor.tools.brush.SymmetryCentre;
import dev.sculptory.fabric.client.editor.tools.EditorToolSet;
import dev.sculptory.fabric.client.editor.world.BoxFace;
import dev.sculptory.fabric.client.editor.world.Ray;
import dev.sculptory.fabric.client.editor.world.ScreenProjector;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.Notice;
import dev.sculptory.fabric.client.session.Permissions;
import dev.sculptory.fabric.client.session.ToolAction;
import dev.sculptory.fabric.engine.Perm;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.world.WorldReader;
import dev.sculptory.protocol.v2.Limits;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Shaped and magic selections in the Select tool and the ops built from them (on the mock session). */
class SelectRegionsTest {
    private static final Box CUBE = new Box(new BlockPos(0, 0, 0), new BlockPos(3, 3, 3));
    private static final Box TEN = new Box(new BlockPos(0, 0, 0), new BlockPos(9, 9, 9));

    private final MockEditorSession session = new MockEditorSession();
    private final FakeStateSpace states = new FakeStateSpace();
    private final FakeWorld world = new FakeWorld(states);
    private final int stone = states.state("minecraft:stone");
    private final int dirt = states.state("minecraft:dirt");
    private final List<Notice> notices = new ArrayList<>();
    private final List<String> confirmations = new ArrayList<>();
    private Ray ray;
    /** The fake clock: each read is 1 ms later, so a 2 ms frame budget runs two batches. */
    private long now;

    private final EditorBackend backend = new EditorBackend() {
        @Override
        public Optional<EditorSession> session() {
            return Optional.of(session);
        }

        @Override
        public StateSpace states() {
            return states;
        }

        @Override
        public WorldReader world() {
            return world;
        }
    };

    private final EditorContext ctx = new EditorContext(() -> backend, notices::add);
    private final SelectionActions actions = new SelectionActions(() -> ctx.contextFor(ToolId.SELECT), ctx::activeBlock,
            (message, onConfirm) -> confirmations.add(message), Translator.KEYS, () -> 42L);
    private final SelectTool tool = new SelectTool(actions, new SelectTool.Services() {
        @Override
        public Optional<Ray> cursorRay() {
            return Optional.ofNullable(ray);
        }

        @Override
        public Optional<ScreenProjector> projector() {
            return Optional.empty();
        }

        @Override
        public Optional<double[]> eye() {
            return Optional.empty();
        }

        @Override
        public float cameraYaw() {
            return 0;
        }

        @Override
        public void setHoveredFace(BoxFace face) {}

        @Override
        public String keyLabel(KeyAction action) {
            return action.id();
        }

        @Override
        public long nanoTime() {
            return now += 1_000_000;
        }
    });
    private ToolContext view;

    @BeforeEach
    void activateSelect() {
        EditorToolSet.register(ctx.tools(), tool);
        view = ctx.contextFor(ToolId.SELECT);
        assertTrue(ctx.tools().activate(ToolId.SELECT, view));
    }

    // ---- Helpers ----

    private void settings(SettingsValues values) {
        ctx.updateSettings(ToolId.SELECT, values);
    }

    private SettingsValues current() {
        return view.settings();
    }

    private void magicMode() {
        settings(current().with(SelectSettings.MODE, SelectSettings.Mode.MAGIC));
    }

    private static WorldCursor hit(int x, int y, int z) {
        return new WorldCursor(new BlockPos(x, y, z), WorldCursor.Face.UP, x + 0.5, y + 1, z + 0.5, false);
    }

    private static Ray down(double x, double z) {
        return new Ray(x, 50, z, 0, -1, 0);
    }

    private boolean pointer(PointerEvent.Kind kind, WorldCursor cursor, int modifiers) {
        int button = kind == PointerEvent.Kind.MOVE ? -1 : PointerEvent.LEFT;
        return tool.onPointer(view, new PointerEvent(kind, button, 100, 100, modifiers, cursor));
    }

    private void click(int x, int y, int z, int modifiers) {
        pointer(PointerEvent.Kind.PRESS, hit(x, y, z), modifiers);
        pointer(PointerEvent.Kind.RELEASE, hit(x, y, z), modifiers);
    }

    private Region region() {
        return ctx.selectionRegion().orElseThrow();
    }

    private List<OpSpec> sentOps() {
        return session.sent().stream().map(action -> assertInstanceOf(ToolAction.RunOp.class, action).op()).toList();
    }

    private List<String> noticeKeys() {
        return notices.stream().map(Notice::key).toList();
    }

    private static Permissions limits(long maxOp) {
        EnumSet<Perm> granted = EnumSet.allOf(Perm.class);
        granted.remove(Perm.LIMIT_BYPASS);
        return new Permissions(Perm.mask(granted), new Limits(maxOp, maxOp, 32, 20, 32L << 20, 2));
    }

    // ---- Shapes ----

    @Test
    void draggingWithAShapeSelectsTheShapeInscribedInTheBox() {
        settings(current().with(SelectSettings.SHAPE, SelectSettings.SelectShape.SPHERE));
        pointer(PointerEvent.Kind.PRESS, hit(0, 0, 0), 0);
        pointer(PointerEvent.Kind.DRAG, hit(9, 9, 9), 0);
        pointer(PointerEvent.Kind.RELEASE, hit(9, 9, 9), 0);
        assertEquals(new Region.Shape(TEN, ShapeKind.ELLIPSOID, Facing.UP), region());
        assertEquals(Optional.of(TEN), ctx.selection(), "selection() stays the bounds");
    }

    @Test
    void theAxisAndTipSettingsGiveTheShapesFacing() {
        SettingsValues cylinder = current().with(SelectSettings.SHAPE, SelectSettings.SelectShape.CYLINDER)
                .with(SelectSettings.AXIS, SelectSettings.Axis.EAST_WEST);
        assertEquals(new Region.Shape(TEN, ShapeKind.CYLINDER, Facing.EAST), SelectSettings.region(TEN, cylinder));
        SettingsValues cone = current().with(SelectSettings.SHAPE, SelectSettings.SelectShape.CONE)
                .with(SelectSettings.FACING, Facing.NORTH).with(SelectSettings.AXIS, SelectSettings.Axis.NORTH_SOUTH);
        assertEquals(new Region.Shape(TEN, ShapeKind.CONE, Facing.NORTH), SelectSettings.region(TEN, cone));
        SettingsValues pyramid = cone.with(SelectSettings.SHAPE, SelectSettings.SelectShape.PYRAMID);
        assertEquals(new Region.Shape(TEN, ShapeKind.PYRAMID, Facing.NORTH), SelectSettings.region(TEN, pyramid));
        SettingsValues sphere = cone.with(SelectSettings.SHAPE, SelectSettings.SelectShape.SPHERE);
        assertEquals(new Region.Shape(TEN, ShapeKind.ELLIPSOID, Facing.UP), SelectSettings.region(TEN, sphere),
                "a sphere ignores the tip, so it is sent facing up");
        assertEquals(new Region.Cuboid(TEN), SelectSettings.region(TEN, sphere.with(SelectSettings.SHAPE,
                SelectSettings.SelectShape.BOX)));
        assertEquals(SelectSettings.Axis.NORTH_SOUTH, SelectSettings.Axis.of(Facing.NORTH));
    }

    @Test
    void changingTheShapeReshapesABoxOrShapeButNotACellSet() {
        ctx.setSelection(TEN);
        settings(current().with(SelectSettings.SHAPE, SelectSettings.SelectShape.CONE));
        assertEquals(new Region.Shape(TEN, ShapeKind.CONE, Facing.UP), region());
        settings(current().with(SelectSettings.FACING, Facing.WEST));
        assertEquals(new Region.Shape(TEN, ShapeKind.CONE, Facing.WEST), region());
        settings(current().with(SelectSettings.SHAPE, SelectSettings.SelectShape.BOX));
        assertEquals(new Region.Cuboid(TEN), region());

        Region.Cells cells = new Region.Cells(CellSets.of(new BlockPos(1, 2, 3)));
        ctx.setSelectionRegion(cells);
        settings(current().with(SelectSettings.SHAPE, SelectSettings.SelectShape.SPHERE));
        assertEquals(cells, region());
    }

    @Test
    void resizingGrowingAndMovingAShapeKeepItsKindAndFacing() {
        Region.Shape cone = new Region.Shape(CUBE, ShapeKind.CONE, Facing.SOUTH);
        ctx.setSelectionRegion(cone);
        // Drag the east handle out by three.
        ray = down(4, 2);
        assertTrue(pointer(PointerEvent.Kind.PRESS, hit(3, 3, 2), 0));
        ray = down(7.3, 2);
        pointer(PointerEvent.Kind.DRAG, hit(7, 0, 2), 0);
        pointer(PointerEvent.Kind.RELEASE, hit(7, 0, 2), 0);
        Box wider = new Box(new BlockPos(0, 0, 0), new BlockPos(6, 3, 3));
        assertEquals(new Region.Shape(wider, ShapeKind.CONE, Facing.SOUTH), region());
        // Shift+click grows it.
        pointer(PointerEvent.Kind.PRESS, hit(6, 7, 3), Modifiers.SHIFT);
        assertEquals(new Region.Shape(new Box(new BlockPos(0, 0, 0), new BlockPos(6, 7, 3)), ShapeKind.CONE,
                Facing.SOUTH), region());
        // Ctrl+Scroll with the cursor off the box grows every face.
        ray = down(40, 40);
        tool.onScroll(view, new ScrollEvent(-1, Modifiers.CONTROL));
        assertEquals(new Region.Shape(new Box(new BlockPos(1, 1, 1), new BlockPos(5, 6, 2)), ShapeKind.CONE,
                Facing.SOUTH), region());
        // Ctrl+drag moves it.
        ray = down(2.5, 1.5);
        assertTrue(pointer(PointerEvent.Kind.PRESS, hit(2, 6, 1), Modifiers.CONTROL));
        ray = down(4.5, 1.5);
        pointer(PointerEvent.Kind.DRAG, hit(4, 6, 1), Modifiers.CONTROL);
        assertEquals(new Region.Shape(new Box(new BlockPos(3, 1, 1), new BlockPos(7, 6, 2)), ShapeKind.CONE,
                Facing.SOUTH), region());
        // Esc puts the region from before the drag back.
        assertTrue(tool.onAction(view, EditorAction.CANCEL));
        assertEquals(new Region.Shape(new Box(new BlockPos(1, 1, 1), new BlockPos(5, 6, 2)), ShapeKind.CONE,
                Facing.SOUTH), region());
    }

    @Test
    void nudgeMovesShapesAndCellSets() {
        Region.Shape sphere = new Region.Shape(TEN, ShapeKind.ELLIPSOID, Facing.UP);
        ctx.setSelectionRegion(sphere);
        tool.onAction(view, EditorAction.NUDGE_UP);
        assertEquals(sphere.translate(0, 1, 0), region());

        CellSet set = CellSets.of(new BlockPos(0, 0, 0), new BlockPos(5, 1, 0));
        ctx.setSelectionRegion(new Region.Cells(set));
        tool.onAction(view, EditorAction.NUDGE_FORWARD); // yaw 0 faces south, +z
        assertEquals(new Region.Cells(set.translate(0, 0, 1)), region());
        assertTrue(tool.onAction(view, EditorAction.DESELECT));
        assertTrue(ctx.selectionRegion().isEmpty());
    }

    // ---- Ops over regions ----

    @Test
    void everyOpCarriesTheShapeOrCellSet() {
        Region.Shape cylinder = new Region.Shape(TEN, ShapeKind.CYLINDER, Facing.EAST);
        ctx.setSelectionRegion(cylinder);
        actions.fill();
        actions.erase();
        actions.hollow();
        actions.walls();
        Region.Cells cells = new Region.Cells(CellSets.of(new BlockPos(0, 0, 0), new BlockPos(0, 1, 0)));
        ctx.setSelectionRegion(cells);
        actions.replace(dev.sculptory.core.BlockDescriptor.parse("minecraft:dirt"), SelectSettings.STONE);
        Pattern stonePattern = new Pattern.Single(stone);
        assertEquals(List.of(
                new OpSpec.Fill(cylinder, stonePattern, CellMask.ANY),
                new OpSpec.Erase(cylinder, CellMask.ANY),
                new OpSpec.Hollow(cylinder, 1, new Pattern.Single(states.air())),
                new OpSpec.Walls(cylinder, 1, stonePattern),
                new OpSpec.Replace(cells, new CellMask.Blocks(List.of(
                        new dev.sculptory.core.NamespacedId("minecraft:dirt"))), stonePattern)), sentOps());
    }

    @Test
    void sizesCountTheShapesOwnBlocks() {
        // 12³ = 1,728 cells of box, but the sphere inside holds fewer than 1,000.
        session.setPermissions(limits(1_000));
        Box twelve = new Box(new BlockPos(0, 0, 0), new BlockPos(11, 11, 11));
        Region.Shape sphere = new Region.Shape(twelve, ShapeKind.ELLIPSOID, Facing.UP);
        assertTrue(sphere.cellCount() < 1_000);
        ctx.setSelectionRegion(sphere);
        assertTrue(actions.fill());
        assertEquals(1, sentOps().size(), "sent (the mock session, like the server before the regions stream, may "
                + "still count the bounds and refuse it)");
        notices.clear();

        ctx.setSelection(twelve);
        assertFalse(actions.fill(), "the whole box is over the limit");
        assertEquals(List.of(Notice.of(Notice.Level.WARNING, "sculptory.notice.too_large", "1,728", "1,000")), notices);
    }

    @Test
    void anEmptyShapeIsExplainedInsteadOfSent() {
        Region.Shape empty = new Region.Shape(new Box(new BlockPos(0, 0, 0), new BlockPos(1, 0, 1)), ShapeKind.CONE,
                Facing.UP);
        assertEquals(0, empty.cellCount());
        ctx.setSelectionRegion(empty);
        assertFalse(actions.erase());
        assertEquals(List.of(), session.sent());
        assertEquals(List.of("sculptory.notice.empty_shape"), noticeKeys());
    }

    @Test
    void onlyABoxIsTooThinToHollow() {
        Box flat = new Box(new BlockPos(0, 0, 0), new BlockPos(9, 1, 9));
        ctx.setSelectionRegion(new Region.Shape(flat, ShapeKind.CYLINDER, Facing.UP));
        assertTrue(actions.hollow(), "a flat shape is sent: the server keeps its shell, which is all of it");
        ctx.setSelection(flat);
        assertFalse(actions.hollow());
        assertEquals(List.of("sculptory.notice.too_thin_to_hollow"), noticeKeys());
    }

    @Test
    void convertToBoxSelectsTheBounds() {
        CellSet set = CellSets.of(new BlockPos(0, 0, 0), new BlockPos(4, 2, 1));
        ctx.setSelectionRegion(new Region.Cells(set));
        assertTrue(actions.convertToBox());
        assertEquals(new Region.Cuboid(new Box(new BlockPos(0, 0, 0), new BlockPos(4, 2, 1))), region());
        assertFalse(actions.convertToBox(), "a box stays a box");
    }

    // ---- Magic select ----

    @Test
    void aClickSelectsTheConnectedMatchingBlocks() {
        world.fill(new Box(new BlockPos(0, 60, 0), new BlockPos(4, 60, 4)), stone);
        world.set(2, 61, 2, dirt);
        magicMode();
        click(2, 60, 2, 0);
        Region.Cells cells = assertInstanceOf(Region.Cells.class, region());
        assertEquals(25, cells.cellCount());
        assertFalse(cells.contains(2, 61, 2));
        assertEquals(List.of(), noticeKeys());
        assertTrue(tool.magicSelect().isEmpty(), "a small select finishes on the click");
    }

    @Test
    void shiftClickAddsAndAltClickRemoves() {
        world.fill(new Box(new BlockPos(0, 60, 0), new BlockPos(4, 60, 0)), stone);
        world.fill(new Box(new BlockPos(0, 70, 0), new BlockPos(2, 70, 0)), dirt);
        magicMode();
        click(0, 60, 0, 0);
        click(0, 70, 0, Modifiers.SHIFT);
        assertEquals(8, region().cellCount());
        click(1, 70, 0, Modifiers.ALT);
        assertEquals(5, region().cellCount());
        assertFalse(region().contains(0, 70, 0));
        click(3, 60, 0, Modifiers.ALT);
        assertTrue(ctx.selectionRegion().isEmpty(), "removing the rest clears the selection");
        assertEquals(List.of("sculptory.notice.magic_emptied"), noticeKeys());
    }

    @Test
    void addingToABoxOrShapeMaterializesIt() {
        world.set(20, 60, 20, stone);
        ctx.setSelectionRegion(new Region.Shape(TEN, ShapeKind.PYRAMID, Facing.UP));
        long pyramid = region().cellCount();
        magicMode();
        click(20, 60, 20, Modifiers.SHIFT);
        Region.Cells cells = assertInstanceOf(Region.Cells.class, region());
        assertEquals(pyramid + 1, cells.cellCount());
        assertTrue(cells.contains(20, 60, 20));
    }

    @Test
    void aLargeSelectRunsOverFramesShowsProgressAndEscStopsIt() {
        world.fill(new Box(new BlockPos(0, 0, 0), new BlockPos(63, 3, 63)), stone);
        ctx.setSelection(CUBE);
        magicMode();
        click(0, 0, 0, 0);
        MagicSelect running = tool.magicSelect().orElseThrow();
        assertTrue(running.count() > 0 && running.count() < 64 * 4 * 64);
        List<KeyHint> hints = tool.hints(view);
        assertEquals(KeyHint.text("sculptory.hint.select.selecting", SelectionModel.count(running.count())), hints.get(0));
        assertEquals(new KeyHint("Esc", "sculptory.hint.select.stop"), hints.get(1));
        assertEquals(new Region.Cuboid(CUBE), region(), "the selection changes only when the search is done");

        tool.frame(view, null);
        assertTrue(running.count() < 64 * 4 * 64);
        assertTrue(tool.onAction(view, EditorAction.CANCEL));
        assertTrue(tool.magicSelect().isEmpty());
        assertEquals(new Region.Cuboid(CUBE), region(), "Esc keeps the selection as it was");
        assertFalse(tool.onAction(view, EditorAction.CANCEL), "the next Esc goes on down the ladder");

        click(0, 0, 0, 0);
        int frames = 0;
        while (tool.magicSelect().isPresent() && frames++ < 1000) tool.frame(view, null);
        assertTrue(frames > 3, "it took frames: " + frames);
        assertEquals(64 * 4 * 64, region().cellCount());
    }

    @Test
    void theLimitAndUnloadedChunksAreReported() {
        for (int x = 0; x < 40; x++) world.set(x, 60, 0, stone);
        world.setLoaded(2, 0, false); // x 32 and on
        magicMode();
        click(0, 60, 0, 0);
        assertEquals(32, region().cellCount());
        assertEquals(List.of("sculptory.notice.magic_unloaded"), noticeKeys());

        notices.clear();
        settings(current().with(SelectSettings.LIMIT, 10));
        click(0, 60, 0, 0);
        assertEquals(10, region().cellCount());
        assertEquals(List.of(Notice.of(Notice.Level.INFO, "sculptory.notice.magic_limit", "10")), notices);
    }

    @Test
    void theServersOpLimitCapsTheLimit() {
        session.setPermissions(limits(5));
        for (int x = 0; x < 20; x++) world.set(x, 60, 0, stone);
        magicMode();
        click(0, 60, 0, 0);
        assertEquals(5, region().cellCount());
        assertEquals(List.of("sculptory.notice.magic_limit"), noticeKeys());
    }

    @Test
    void theServersSelectionCapCapsTheLimitEvenForBypassPlayers() {
        // Review fix (b): a magic selection larger than the server takes could not be used, so it stops at that cap.
        session.setPermissions(new Permissions(Perm.mask(EnumSet.allOf(Perm.class)),
                new Limits(1_000, 1_000, 32, 20, 32L << 20, 2, 7, 100)));
        for (int x = 0; x < 20; x++) world.set(x, 60, 0, stone);
        magicMode();
        click(0, 60, 0, 0);
        assertEquals(7, region().cellCount());
        assertEquals(List.of(Notice.of(Notice.Level.INFO, "sculptory.notice.magic_limit", "7")), notices);
    }

    @Test
    void aClickOnNothingSelectableSaysSo() {
        world.setLoaded(0, 0, false);
        magicMode();
        click(1, 60, 1, 0);
        assertTrue(ctx.selectionRegion().isEmpty());
        assertEquals(List.of("sculptory.notice.magic_nothing"), noticeKeys());
    }

    @Test
    void magicModeOptionsFollowTheSettings() {
        world.set(0, 60, 0, stone);
        world.set(1, 61, 0, stone); // touches only at an edge
        world.set(0, 60, 1, dirt);
        magicMode();
        click(0, 60, 0, 0);
        assertEquals(1, region().cellCount());
        settings(current().with(SelectSettings.CONNECT, MagicSelect.Connect.DIAGONALS));
        click(0, 60, 0, 0);
        assertEquals(2, region().cellCount());
        settings(current().with(SelectSettings.MATCH, MagicSelect.Match.ANY_BLOCK));
        click(0, 60, 0, 0);
        assertEquals(3, region().cellCount());
    }

    // ---- Cell sets in the Select tool ----

    @Test
    void aCellSetHasNoHandlesOrScrollResizeAndShiftClickStartsANewBox() {
        Region.Cells cells = new Region.Cells(CellSets.of(new BlockPos(0, 0, 0), new BlockPos(3, 3, 3)));
        ctx.setSelectionRegion(cells);
        // A press where the east handle would be starts a new box instead of a resize.
        ray = down(4, 2);
        pointer(PointerEvent.Kind.PRESS, hit(3, 3, 2), 0);
        assertEquals(SelectionDrag.Mode.CREATE, tool.drag().mode());
        tool.onAction(view, EditorAction.CANCEL);
        assertEquals(cells, region(), "Esc gives the cell set back");

        assertTrue(tool.onScroll(view, new ScrollEvent(1, Modifiers.CONTROL)), "Ctrl+Scroll is taken");
        assertEquals(cells, region(), "but doesn't resize a cell set");

        pointer(PointerEvent.Kind.PRESS, hit(8, 1, 1), Modifiers.SHIFT);
        assertEquals(new Region.Cuboid(Box.of(new BlockPos(8, 1, 1))), region());
        List<String> hintKeys = new ArrayList<>();
        ctx.setSelectionRegion(cells);
        tool.hints(view).forEach(hint -> hintKeys.add(hint.descriptionKey()));
        assertTrue(hintKeys.containsAll(Set.of("sculptory.hint.select.new_box", "sculptory.hint.select.cells",
                "sculptory.hint.select.move")), hintKeys.toString());
        assertFalse(hintKeys.contains("sculptory.hint.select.resize"));
    }

    @Test
    void ctrlDragMovesACellSet() {
        CellSet set = CellSets.of(new BlockPos(0, 0, 0), new BlockPos(3, 3, 3));
        ctx.setSelectionRegion(new Region.Cells(set));
        ray = down(1.5, 1.5);
        assertTrue(pointer(PointerEvent.Kind.PRESS, hit(1, 3, 1), Modifiers.CONTROL));
        ray = down(4.6, 0.2);
        pointer(PointerEvent.Kind.DRAG, hit(4, 0, 0), Modifiers.CONTROL);
        assertEquals(new Region.Cells(set.translate(3, 0, -1)), region());
    }

    @Test
    void handlesStillResizeABoxInMagicMode() {
        ctx.setSelection(CUBE);
        magicMode();
        ray = down(4, 2);
        assertTrue(pointer(PointerEvent.Kind.PRESS, hit(3, 3, 2), 0));
        assertEquals(SelectionDrag.Mode.RESIZE, tool.drag().mode());
        assertTrue(tool.magicSelect().isEmpty());
    }

    @Test
    void magicHintsNameTheClicks() {
        magicMode();
        List<String> keys = tool.hints(view).stream().map(KeyHint::descriptionKey).toList();
        assertEquals(List.of("sculptory.hint.select.magic", "sculptory.hint.select.magic_add",
                "sculptory.hint.select.magic_remove"), keys);
        settings(current().with(SelectSettings.MODE, SelectSettings.Mode.BOX)
                .with(SelectSettings.SHAPE, SelectSettings.SelectShape.PYRAMID));
        assertEquals("sculptory.hint.select.drag.pyramid", tool.hints(view).get(0).descriptionKey());
    }

    // ---- Review fixes: counts off the client thread, cheap moves, drags against magic select ----

    private final List<Runnable> background = new ArrayList<>();
    private final List<Runnable> client = new ArrayList<>();

    /** Region work as the game does it: nothing runs until the test runs the queued work. */
    private void backgroundWork() {
        ctx.setRegionWork(new RegionWork(background::add, client::add));
    }

    private void runWork() {
        while (!background.isEmpty() || !client.isEmpty()) {
            while (!background.isEmpty()) background.remove(0).run();
            while (!client.isEmpty()) client.remove(0).run();
        }
    }

    @Test
    void aLargeShapeIsCountedOffTheClientThreadBeforeItsOpIsSent() {
        backgroundWork();
        // 150³: past the inline rows, and a box volume over the confirmation threshold, so the exact count matters.
        Region.Shape sphere = new Region.Shape(new Box(BlockPos.ORIGIN, new BlockPos(149, 149, 149)),
                ShapeKind.ELLIPSOID, Facing.UP);
        ctx.setSelectionRegion(sphere);
        assertTrue(actions.fill());
        assertEquals(List.of(), session.sent(), "nothing is sent before the count is in");
        assertEquals(List.of("sculptory.notice.counting"), noticeKeys());
        assertEquals(1, background.size());
        runWork();
        assertEquals(1, confirmations.size(), "the sphere's 1.7M blocks ask for confirmation");
        assertTrue(confirmations.get(0).contains(SelectionModel.count(sphere.cellCount())), confirmations.get(0));
    }

    @Test
    void aCountThatLandsAfterTheSelectionChangedIsDropped() {
        backgroundWork();
        Region.Shape sphere = new Region.Shape(new Box(BlockPos.ORIGIN, new BlockPos(149, 149, 149)),
                ShapeKind.ELLIPSOID, Facing.UP);
        ctx.setSelectionRegion(sphere);
        actions.erase();
        ctx.setSelection(CUBE);
        runWork();
        assertEquals(List.of(), confirmations);
        assertEquals(List.of(), session.sent());
    }

    @Test
    void smallBoundsNeedNoCountAndAShapeTooLargeToCountIsRefused() {
        backgroundWork();
        // 10 × 200 × 200: 40,000 rows, but only 400,000 cells of box: under every threshold, so it goes at once.
        Region.Shape disc = new Region.Shape(new Box(BlockPos.ORIGIN, new BlockPos(9, 199, 199)), ShapeKind.CYLINDER,
                Facing.EAST);
        ctx.setSelectionRegion(disc);
        assertTrue(actions.fill());
        assertEquals(1, session.sent().size(), "sent without waiting for a count");

        Region.Shape huge = new Region.Shape(new Box(BlockPos.ORIGIN, new BlockPos(29_999, 29_999, 29_999)),
                ShapeKind.ELLIPSOID, Facing.UP);
        ctx.setSelectionRegion(huge);
        assertFalse(actions.fill());
        assertEquals("sculptory.notice.too_large", noticeKeys().get(noticeKeys().size() - 1));
    }

    @Test
    void draggingOrNudgingACellSetNeverRebuildsIt() {
        CellSet set = CellSets.of(new BlockPos(0, 0, 0), new BlockPos(3, 3, 3));
        Region.Cells cells = new Region.Cells(set);
        ctx.setSelectionRegion(cells);
        ray = down(1.5, 1.5);
        assertTrue(pointer(PointerEvent.Kind.PRESS, hit(1, 3, 1), Modifiers.CONTROL));
        for (int step = 1; step <= 5; step++) {
            ray = down(1.5 + step, 1.5);
            pointer(PointerEvent.Kind.DRAG, hit(1 + step, 0, 1), Modifiers.CONTROL);
            Selection state = ctx.selectionState().orElseThrow();
            assertTrue(state.base() == cells, "the same cell set every frame; only the offset moves");
            assertEquals(set.bounds().offset(step, 0, 0), state.bounds());
        }
        pointer(PointerEvent.Kind.RELEASE, hit(6, 0, 1), Modifiers.CONTROL);
        tool.onAction(view, EditorAction.NUDGE_UP);
        assertTrue(ctx.selectionState().orElseThrow().base() == cells, "a nudge only moves the offset too");
        assertEquals(new Region.Cells(set.translate(5, 1, 0)), region(), "applied once, when asked for");
    }

    @Test
    void escDuringACellSetDragPutsItBack() {
        Region.Cells cells = new Region.Cells(CellSets.of(new BlockPos(0, 0, 0), new BlockPos(3, 3, 3)));
        ctx.setSelectionRegion(cells);
        tool.onAction(view, EditorAction.NUDGE_UP);
        Selection before = ctx.selectionState().orElseThrow();
        ray = down(1.5, 1.5);
        assertTrue(pointer(PointerEvent.Kind.PRESS, hit(1, 4, 1), Modifiers.CONTROL));
        ray = down(9.5, 1.5);
        pointer(PointerEvent.Kind.DRAG, hit(9, 0, 1), Modifiers.CONTROL);
        assertTrue(tool.onAction(view, EditorAction.CANCEL));
        assertTrue(ctx.selectionState().orElseThrow() == before);
    }

    @Test
    void aFarDragOrNudgeStopsAtTheLimitInsteadOfCrashing() {
        Region.Cells cells = new Region.Cells(CellSets.of(new BlockPos(0, 0, 0), new BlockPos(3, 3, 3)));
        ctx.setSelectionRegion(cells);
        ray = down(1.5, 1.5);
        assertTrue(pointer(PointerEvent.Kind.PRESS, hit(1, 3, 1), Modifiers.CONTROL));
        ray = new Ray(1.5, 50, 1.5, 1, -1e-9, 0); // almost parallel to the top face: an enormous move
        pointer(PointerEvent.Kind.DRAG, hit(1, 0, 1), Modifiers.CONTROL);
        pointer(PointerEvent.Kind.RELEASE, hit(1, 0, 1), Modifiers.CONTROL);
        assertTrue(ctx.selectionState().orElseThrow().bounds().max().x() <= Selection.HORIZONTAL_LIMIT);
        assertEquals(2, region().cellCount(), "and the cells can still be applied there");
        ctx.setModifiers(Modifiers.SHIFT);
        for (int i = 0; i < 20; i++) tool.onAction(view, EditorAction.NUDGE_DOWN);
        ctx.setModifiers(0);
        assertEquals(2, region().cellCount());
    }

    @Test
    void grabbingTheSelectionDropsAMagicSelectStillSearching() {
        world.fill(new Box(new BlockPos(0, 0, 0), new BlockPos(63, 3, 63)), stone);
        ctx.setSelection(CUBE);
        magicMode();
        click(0, 0, 0, 0);
        assertTrue(tool.magicSelect().isPresent(), "still searching");
        ray = down(1.5, 1.5);
        assertTrue(pointer(PointerEvent.Kind.PRESS, hit(1, 3, 1), Modifiers.CONTROL));
        assertTrue(tool.magicSelect().isEmpty(), "the drag decides the selection now");
        for (int frame = 0; frame < 100; frame++) tool.frame(view, null);
        assertEquals(new Region.Cuboid(CUBE), region(), "the search never lands on the dragged selection");
        assertTrue(tool.onAction(view, EditorAction.CANCEL), "Esc cancels the drag");
        assertEquals(SelectionDrag.Mode.IDLE, tool.drag().mode());
    }

    @Test
    void inMagicModeShiftOrAltClickOnAHandleSelectsInsteadOfResizing() {
        world.set(3, 3, 2, stone);
        ctx.setSelection(CUBE);
        magicMode();
        ray = down(4, 2); // over the east handle
        assertTrue(pointer(PointerEvent.Kind.PRESS, hit(3, 3, 2), Modifiers.SHIFT));
        assertEquals(SelectionDrag.Mode.IDLE, tool.drag().mode());
        assertEquals(CUBE.volume(), region().cellCount(), "the stone was in the box already: added, not resized");
        assertInstanceOf(Region.Cells.class, region());
        assertTrue(pointer(PointerEvent.Kind.PRESS, hit(3, 3, 2), Modifiers.ALT));
        assertEquals(SelectionDrag.Mode.IDLE, tool.drag().mode());
        assertEquals(CUBE.volume() - 1, region().cellCount());
        assertFalse(region().contains(3, 3, 2));
    }

    @Test
    void addingToALargeShapeRunsOffTheClientThread() {
        backgroundWork();
        world.set(300, 60, 300, stone);
        Region.Shape sphere = new Region.Shape(new Box(BlockPos.ORIGIN, new BlockPos(99, 99, 99)),
                ShapeKind.ELLIPSOID, Facing.UP);
        ctx.setSelectionRegion(sphere);
        magicMode();
        click(300, 60, 300, Modifiers.SHIFT);
        assertTrue(region() == sphere, "not combined on the client thread");
        runWork(); // the magic select was small; listing the sphere's half a million cells is not
        Region.Cells joined = assertInstanceOf(Region.Cells.class, region());
        assertEquals(sphere.cellCount() + 1, joined.cellCount());
    }

    // ---- Brush select ----

    private void brushMode() {
        settings(current().with(SelectSettings.MODE, SelectSettings.Mode.BRUSH));
    }

    private void lassoMode() {
        settings(current().with(SelectSettings.MODE, SelectSettings.Mode.LASSO));
    }

    private static Set<BlockPos> sphere(int x, int y, int z, int radius) {
        return CellSets.cells(BrushSelect.sphere(new BlockPos(x, y, z), radius));
    }

    private Set<BlockPos> cells() {
        return CellSets.cells(region());
    }

    @Test
    void aBrushDragPaintsSpheresIntoANewSelection() {
        brushMode();
        settings(current().with(SelectSettings.SOLID_ONLY, false));
        assertTrue(pointer(PointerEvent.Kind.PRESS, hit(5, 60, 5), 0));
        assertEquals(sphere(5, 60, 5, 4), cells(), "the first sphere is painted on the press");
        assertTrue(tool.brushSelect().isPresent());
        List<KeyHint> hints = tool.hints(view);
        assertEquals(KeyHint.text("sculptory.hint.select.selecting", SelectionModel.count(region().cellCount())), hints.get(0));
        assertEquals(new KeyHint("Esc", "sculptory.hint.cancel_drag"), hints.get(1));

        assertTrue(pointer(PointerEvent.Kind.DRAG, hit(20, 60, 5), 0));
        assertTrue(pointer(PointerEvent.Kind.RELEASE, hit(20, 60, 5), 0));
        assertTrue(tool.brushSelect().isEmpty());
        Set<BlockPos> painted = cells();
        assertTrue(painted.containsAll(sphere(5, 60, 5, 4)));
        assertTrue(painted.containsAll(sphere(20, 60, 5, 4)));
        for (int x = 5; x <= 20; x++) assertTrue(painted.contains(new BlockPos(x, 60, 5)), "x " + x);
        assertEquals(List.of(), noticeKeys());

        assertTrue(actions.convertToBox());
        assertEquals(new Region.Cuboid(new Box(new BlockPos(1, 56, 1), new BlockPos(24, 64, 9))), region());
    }

    @Test
    void aBrushDragAddsToABoxAndAltDragRemoves() {
        brushMode();
        settings(current().with(SelectSettings.SOLID_ONLY, false));
        ctx.setSelection(CUBE);
        assertTrue(pointer(PointerEvent.Kind.PRESS, hit(20, 60, 20), 0));
        assertTrue(pointer(PointerEvent.Kind.RELEASE, hit(20, 60, 20), 0));
        Set<BlockPos> expected = CellSets.cells(new Region.Cuboid(CUBE));
        expected.addAll(sphere(20, 60, 20, 4));
        assertEquals(expected, cells(), "the box became cells and the sphere joined them");

        assertTrue(pointer(PointerEvent.Kind.PRESS, hit(1, 1, 1), Modifiers.ALT));
        assertEquals(BrushSelect.Combine.REMOVE, tool.brushSelect().orElseThrow().combine());
        assertTrue(pointer(PointerEvent.Kind.RELEASE, hit(1, 1, 1), Modifiers.ALT));
        assertEquals(sphere(20, 60, 20, 4), cells(), "the sphere over the cube took it out");

        assertTrue(pointer(PointerEvent.Kind.PRESS, hit(20, 60, 20), Modifiers.ALT));
        assertTrue(pointer(PointerEvent.Kind.RELEASE, hit(20, 60, 20), Modifiers.ALT));
        assertTrue(ctx.selectionRegion().isEmpty(), "removing everything clears the selection");

        assertTrue(pointer(PointerEvent.Kind.PRESS, hit(20, 60, 20), Modifiers.ALT));
        assertTrue(tool.brushSelect().isEmpty(), "nothing to remove from: no drag");
        assertTrue(ctx.selectionRegion().isEmpty());
        assertEquals(List.of(), noticeKeys());
    }

    @Test
    void aShapeSelectionBecomesCellsOnTheFirstBrushDrag() {
        Region.Shape shape = new Region.Shape(TEN, ShapeKind.ELLIPSOID, Facing.UP);
        ctx.setSelectionRegion(shape);
        brushMode();
        settings(current().with(SelectSettings.SOLID_ONLY, false));
        assertTrue(pointer(PointerEvent.Kind.PRESS, hit(30, 60, 30), 0));
        Set<BlockPos> expected = CellSets.cells(shape);
        expected.addAll(sphere(30, 60, 30, 4));
        assertEquals(expected, cells());
        assertTrue(pointer(PointerEvent.Kind.RELEASE, hit(30, 60, 30), 0));
        assertEquals(expected, cells());
    }

    @Test
    void solidOnlyAddsBlocksAndSaysOnceWhenChunksAreMissing() {
        for (int x = 8; x < 24; x++) world.set(x, 60, 5, stone);
        world.setLoaded(1, 0, false);
        brushMode();
        assertTrue(current().get(SelectSettings.SOLID_ONLY));
        assertTrue(pointer(PointerEvent.Kind.PRESS, hit(12, 60, 5), 0));
        assertTrue(pointer(PointerEvent.Kind.DRAG, hit(13, 60, 5), 0));
        assertTrue(pointer(PointerEvent.Kind.DRAG, hit(14, 60, 5), 0));
        assertTrue(pointer(PointerEvent.Kind.RELEASE, hit(14, 60, 5), 0));
        Set<BlockPos> expected = new HashSet<>();
        for (int x = 8; x <= 15; x++) expected.add(new BlockPos(x, 60, 5));
        assertEquals(expected, cells(), "the stone within reach, up to the unloaded chunk");
        assertEquals(List.of("sculptory.notice.select_unloaded"), noticeKeys(), "said once for the drag");

        assertTrue(pointer(PointerEvent.Kind.PRESS, hit(14, 60, 5), 0));
        assertTrue(pointer(PointerEvent.Kind.RELEASE, hit(14, 60, 5), 0));
        assertEquals(2, notices.size(), "and once for the next drag");
    }

    @Test
    void theSelectionCapStopsTheBrushAndSaysSoOnce() {
        session.setPermissions(new Permissions(Perm.mask(EnumSet.allOf(Perm.class)),
                new Limits(1_000, 1_000, 32, 20, 32L << 20, 2, 50, 100)));
        brushMode();
        settings(current().with(SelectSettings.SOLID_ONLY, false));
        assertTrue(pointer(PointerEvent.Kind.PRESS, hit(0, 60, 0), 0));
        assertEquals(50, region().cellCount());
        assertEquals(List.of(Notice.of(Notice.Level.INFO, "sculptory.notice.select_limit", "50")), notices);
        assertTrue(pointer(PointerEvent.Kind.DRAG, hit(30, 60, 0), 0));
        assertTrue(pointer(PointerEvent.Kind.RELEASE, hit(30, 60, 0), 0));
        assertEquals(50, region().cellCount(), "never over the cap");
        assertEquals(1, notices.size());
    }

    @Test
    void escDuringABrushDragPutsTheSelectionBack() {
        ctx.setSelection(CUBE);
        Selection before = ctx.selectionState().orElseThrow();
        brushMode();
        settings(current().with(SelectSettings.SOLID_ONLY, false));
        assertTrue(pointer(PointerEvent.Kind.PRESS, hit(20, 60, 20), 0));
        assertInstanceOf(Region.Cells.class, region());
        assertTrue(tool.onAction(view, EditorAction.CANCEL));
        assertTrue(tool.brushSelect().isEmpty());
        assertTrue(ctx.selectionState().orElseThrow() == before, "the state from before the press, kind included");
        assertFalse(pointer(PointerEvent.Kind.DRAG, hit(25, 60, 20), 0), "the drag is over");
        assertFalse(pointer(PointerEvent.Kind.RELEASE, hit(25, 60, 20), 0));
        assertEquals(new Region.Cuboid(CUBE), region());

        assertTrue(pointer(PointerEvent.Kind.PRESS, hit(20, 60, 20), 0));
        magicMode(); // a mode change mid-drag drops it too
        assertTrue(tool.brushSelect().isEmpty());
        assertEquals(new Region.Cuboid(CUBE), region());
    }

    @Test
    void ctrlDragStillMovesInBrushAndLassoMode() {
        for (SelectSettings.Mode mode : List.of(SelectSettings.Mode.BRUSH, SelectSettings.Mode.LASSO)) {
            Region.Cells cells = new Region.Cells(CellSets.of(new BlockPos(0, 0, 0), new BlockPos(3, 3, 3)));
            ctx.setSelectionRegion(cells);
            settings(current().with(SelectSettings.MODE, mode));
            ray = down(1.5, 1.5);
            assertTrue(pointer(PointerEvent.Kind.PRESS, hit(1, 3, 1), Modifiers.CONTROL), mode.name());
            assertEquals(SelectionDrag.Mode.MOVE, tool.drag().mode());
            assertTrue(tool.brushSelect().isEmpty() && tool.lassoSelect().isEmpty());
            ray = down(11.5, 1.5);
            pointer(PointerEvent.Kind.DRAG, hit(11, 0, 1), Modifiers.CONTROL);
            pointer(PointerEvent.Kind.RELEASE, hit(11, 0, 1), Modifiers.CONTROL);
            assertEquals(new BlockPos(10, 0, 0), ctx.selectionState().orElseThrow().bounds().min(), mode.name());
            assertEquals(2, region().cellCount());
        }
    }

    @Test
    void ctrlScrollSetsTheBrushRadiusAndAltScrollTheLassoHeight() {
        ctx.setSelection(CUBE);
        brushMode();
        assertTrue(tool.onScroll(view, new ScrollEvent(1, Modifiers.CONTROL)));
        assertEquals(5, current().get(SelectSettings.BRUSH_RADIUS));
        assertEquals(Optional.of(CUBE), ctx.selection(), "the box is not resized in Brush mode");
        assertTrue(tool.onScroll(view, new ScrollEvent(1, Modifiers.CONTROL | Modifiers.SHIFT)));
        assertEquals(9, current().get(SelectSettings.BRUSH_RADIUS));
        for (int i = 0; i < 10; i++) tool.onScroll(view, new ScrollEvent(-1, Modifiers.CONTROL | Modifiers.SHIFT));
        assertEquals(1, current().get(SelectSettings.BRUSH_RADIUS));
        for (int i = 0; i < 10; i++) tool.onScroll(view, new ScrollEvent(1, Modifiers.CONTROL | Modifiers.SHIFT));
        assertEquals(32, current().get(SelectSettings.BRUSH_RADIUS));
        assertFalse(tool.onScroll(view, new ScrollEvent(1, Modifiers.ALT)), "Alt+Scroll means nothing to the brush");
        assertFalse(tool.onScroll(view, new ScrollEvent(0, Modifiers.CONTROL)));

        settings(current().with(SelectSettings.BRUSH_RADIUS, 2).with(SelectSettings.SOLID_ONLY, false));
        assertTrue(pointer(PointerEvent.Kind.PRESS, hit(20, 60, 20), 0));
        assertTrue(tool.onScroll(view, new ScrollEvent(1, Modifiers.CONTROL)));
        assertEquals(3, tool.brushSelect().orElseThrow().radius(), "the radius changes mid-drag");
        assertTrue(pointer(PointerEvent.Kind.DRAG, hit(40, 60, 20), 0));
        assertTrue(cells().containsAll(sphere(40, 60, 20, 3)));
        assertTrue(tool.onAction(view, EditorAction.CANCEL));

        lassoMode();
        assertTrue(tool.onScroll(view, new ScrollEvent(1, Modifiers.ALT)));
        assertEquals(2, current().get(SelectSettings.LASSO_HEIGHT));
        for (int i = 0; i < 5; i++) tool.onScroll(view, new ScrollEvent(-1, Modifiers.ALT));
        assertEquals(1, current().get(SelectSettings.LASSO_HEIGHT));
        assertTrue(tool.onScroll(view, new ScrollEvent(1, Modifiers.ALT | Modifiers.SHIFT)));
        assertEquals(5, current().get(SelectSettings.LASSO_HEIGHT));
        settings(current().with(SelectSettings.LASSO_HEIGHT, 383));
        tool.onScroll(view, new ScrollEvent(1, Modifiers.ALT | Modifiers.SHIFT));
        assertEquals(384, current().get(SelectSettings.LASSO_HEIGHT));
        ray = null;
        assertTrue(tool.onScroll(view, new ScrollEvent(1, Modifiers.CONTROL)), "Ctrl+Scroll still grows a box in Lasso mode");
        assertEquals(Optional.of(SelectionModel.expandAll(CUBE, 1)), ctx.selection());
    }

    @Test
    void aLargeBoxIsListedOffTheClientThreadOnTheFirstBrushDrag() {
        backgroundWork();
        Box big = new Box(BlockPos.ORIGIN, new BlockPos(99, 99, 99));
        ctx.setSelection(big);
        brushMode();
        settings(current().with(SelectSettings.SOLID_ONLY, false));
        assertTrue(pointer(PointerEvent.Kind.PRESS, hit(200, 60, 200), 0));
        assertEquals(new Region.Cuboid(big), region(), "a million cells are not listed on the client thread");
        assertFalse(tool.brushSelect().orElseThrow().baseKnown());
        assertTrue(pointer(PointerEvent.Kind.DRAG, hit(205, 60, 200), 0));
        assertTrue(pointer(PointerEvent.Kind.RELEASE, hit(205, 60, 200), 0));
        assertEquals(new Region.Cuboid(big), region());
        runWork();
        Region.Cells joined = assertInstanceOf(Region.Cells.class, region());
        assertTrue(joined.cellCount() > big.volume());
        assertTrue(joined.contains(0, 0, 0) && joined.contains(200, 60, 200) && joined.contains(205, 60, 200));
        assertEquals(List.of(), noticeKeys());

        // Esc before the base lands: nothing changes when it does.
        ctx.setSelection(big);
        assertTrue(pointer(PointerEvent.Kind.PRESS, hit(200, 60, 200), 0));
        assertTrue(tool.onAction(view, EditorAction.CANCEL));
        runWork();
        assertEquals(new Region.Cuboid(big), region());
        assertEquals(List.of(), noticeKeys());
    }

    // ---- Lasso select ----

    /** Draws a loop through the given (x, z) points on the plane over layer y, with the chord held. */
    private void lasso(int y, int modifiers, double... xz) {
        ray = down(xz[0], xz[1]);
        assertTrue(pointer(PointerEvent.Kind.PRESS, hit((int) Math.floor(xz[0]), y, (int) Math.floor(xz[1])), modifiers));
        for (int i = 2; i < xz.length; i += 2) {
            ray = down(xz[i], xz[i + 1]);
            assertTrue(pointer(PointerEvent.Kind.DRAG, hit((int) Math.floor(xz[i]), y, (int) Math.floor(xz[i + 1])), modifiers));
        }
        assertTrue(pointer(PointerEvent.Kind.RELEASE, hit((int) Math.floor(xz[0]), y, (int) Math.floor(xz[1])), modifiers));
    }

    private static Set<BlockPos> layer(int x0, int z0, int x1, int z1, int y0, int y1) {
        Set<BlockPos> cells = new HashSet<>();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = y0; y <= y1; y++) cells.add(new BlockPos(x, y, z));
            }
        }
        return cells;
    }

    @Test
    void aLassoSelectsTheCellsInsideTheLoopHeightLayersHigh() {
        lassoMode();
        ray = down(0.5, 0.5);
        assertTrue(pointer(PointerEvent.Kind.PRESS, hit(0, 10, 0), 0));
        LassoSelect drawing = tool.lassoSelect().orElseThrow();
        assertEquals(1, drawing.size());
        assertEquals(11.0, drawing.planeY(), "the plane over the pressed block's top face");
        assertTrue(ctx.selectionRegion().isEmpty(), "nothing is selected until the loop closes");
        assertEquals(List.of(KeyHint.text("sculptory.hint.select.lasso_drawing"),
                new KeyHint("Esc", "sculptory.hint.cancel_drag")), tool.hints(view));
        ray = down(10.5, 0.5);
        assertTrue(pointer(PointerEvent.Kind.DRAG, hit(10, 10, 0), 0));
        ray = down(10.5, 10.5);
        assertTrue(pointer(PointerEvent.Kind.DRAG, hit(10, 10, 10), 0));
        ray = down(0.5, 10.5);
        assertTrue(pointer(PointerEvent.Kind.DRAG, hit(0, 10, 10), 0));
        assertEquals(4, drawing.size());
        assertTrue(pointer(PointerEvent.Kind.RELEASE, hit(0, 10, 10), 0));
        assertTrue(tool.lassoSelect().isEmpty());
        assertEquals(layer(0, 0, 9, 9, 10, 10), cells());
        assertEquals(List.of(), noticeKeys());

        settings(current().with(SelectSettings.LASSO_HEIGHT, 3));
        lasso(10, 0, 0.5, 0.5, 10.5, 0.5, 10.5, 10.5, 0.5, 10.5);
        assertEquals(layer(0, 0, 9, 9, 10, 12), cells(), "three layers up from the pressed block");
        assertTrue(actions.convertToBox());
        assertEquals(new Region.Cuboid(new Box(new BlockPos(0, 10, 0), new BlockPos(9, 12, 9))), region());
    }

    @Test
    void lassoChordsReplaceAddAndSubtract() {
        lassoMode();
        lasso(10, 0, 0.5, 0.5, 10.5, 0.5, 10.5, 10.5, 0.5, 10.5);
        assertEquals(100, region().cellCount());
        lasso(10, Modifiers.SHIFT, 20.5, 0.5, 30.5, 0.5, 30.5, 10.5, 20.5, 10.5);
        Set<BlockPos> both = layer(0, 0, 9, 9, 10, 10);
        both.addAll(layer(20, 0, 29, 9, 10, 10));
        assertEquals(both, cells(), "Shift adds");
        lasso(10, Modifiers.ALT, 0.5, 0.5, 10.5, 0.5, 10.5, 10.5, 0.5, 10.5);
        assertEquals(layer(20, 0, 29, 9, 10, 10), cells(), "Alt removes");
        lasso(10, 0, 0.5, 0.5, 4.5, 0.5, 4.5, 4.5, 0.5, 4.5);
        assertEquals(layer(0, 0, 3, 3, 10, 10), cells(), "plain replaces");
        lasso(10, Modifiers.ALT, -0.5, -0.5, 4.5, -0.5, 4.5, 4.5, -0.5, 4.5);
        assertTrue(ctx.selectionRegion().isEmpty());
        assertEquals(List.of("sculptory.notice.magic_emptied"), noticeKeys());

        ctx.setSelection(CUBE);
        lasso(10, Modifiers.SHIFT, 20.5, 0.5, 30.5, 0.5, 30.5, 10.5, 20.5, 10.5);
        Set<BlockPos> joined = CellSets.cells(new Region.Cuboid(CUBE));
        joined.addAll(layer(20, 0, 29, 9, 10, 10));
        assertEquals(joined, cells(), "adding to a box makes it cells");
    }

    @Test
    void aLoopWithFewerThanThreePointsOrNothingInsideSaysSo() {
        lassoMode();
        ray = down(0.5, 0.5);
        assertTrue(pointer(PointerEvent.Kind.PRESS, hit(0, 10, 0), 0));
        assertTrue(pointer(PointerEvent.Kind.RELEASE, hit(0, 10, 0), 0));
        assertTrue(ctx.selectionRegion().isEmpty());
        assertEquals(List.of("sculptory.notice.lasso_nothing"), noticeKeys());

        ctx.setSelection(CUBE);
        lasso(10, 0, 0.5, 0.5, 5.5, 0.5, 10.5, 0.5); // three points on a line
        assertEquals(new Region.Cuboid(CUBE), region(), "the selection is unchanged");
        lasso(10, 0, 0.1, 0.1, 0.4, 0.1, 0.4, 0.4); // a loop around no cell centre
        assertEquals(new Region.Cuboid(CUBE), region());
        assertEquals(3, notices.size());
    }

    @Test
    void escCancelsALassoWithTheSelectionUnchanged() {
        ctx.setSelection(CUBE);
        Selection before = ctx.selectionState().orElseThrow();
        lassoMode();
        ray = down(0.5, 0.5);
        assertTrue(pointer(PointerEvent.Kind.PRESS, hit(0, 10, 0), 0));
        ray = down(10.5, 0.5);
        assertTrue(pointer(PointerEvent.Kind.DRAG, hit(10, 10, 0), 0));
        ray = down(10.5, 10.5);
        assertTrue(pointer(PointerEvent.Kind.DRAG, hit(10, 10, 10), 0));
        assertTrue(tool.onAction(view, EditorAction.CANCEL));
        assertTrue(tool.lassoSelect().isEmpty());
        assertFalse(pointer(PointerEvent.Kind.RELEASE, hit(10, 10, 10), 0));
        assertTrue(ctx.selectionState().orElseThrow() == before);
        assertEquals(List.of(), noticeKeys());
    }

    @Test
    void aLargeLassoIsRasterisedOffTheClientThread() {
        backgroundWork();
        lassoMode();
        // An 800 × 600 rectangle around the press (every point within 512 of it), entered along a slit from the press.
        double[] loop = {0.5, 0.5, 0.5, 300.5, -399.5, 300.5, -399.5, -299.5, 400.5, -299.5, 400.5, 300.5, 0.5, 300.5};
        lasso(10, 0, loop);
        assertTrue(ctx.selectionRegion().isEmpty(), "480,000 cells are not listed on the client thread");
        runWork();
        assertEquals(480_000, region().cellCount());
        assertTrue(region().contains(-400, 10, -300) && region().contains(399, 10, 299) && region().contains(0, 10, 100));
        assertFalse(region().contains(400, 10, 0) || region().contains(0, 10, 300));

        // A loop reaching farther than 512 blocks from the press is clamped there.
        lasso(10, 0, 0.5, 0.5, 600.5, 0.5, 600.5, 20.5, 0.5, 20.5);
        runWork();
        assertTrue(region().contains(511, 10, 10) && !region().contains(520, 10, 10));

        // The cap cuts a large loop too, with the toast.
        session.setPermissions(new Permissions(Perm.mask(EnumSet.allOf(Perm.class)),
                new Limits(1_000_000, 1_000_000, 32, 20, 32L << 20, 2, 300_000, 100_000)));
        lasso(10, 0, loop);
        runWork();
        assertEquals(300_000, region().cellCount());
        assertEquals(List.of(Notice.of(Notice.Level.INFO, "sculptory.notice.select_limit", "300,000")), notices);
    }

    @Test
    void brushAndLassoHintsNameTheChords() {
        brushMode();
        List<String> keys = tool.hints(view).stream().map(KeyHint::descriptionKey).toList();
        assertEquals(List.of("sculptory.hint.select.brush", "sculptory.hint.select.brush_remove",
                "sculptory.hint.brush.radius"), keys);
        assertEquals("tool_size", tool.hints(view).get(2).keys());
        ctx.setSelectionRegion(new Region.Cells(CellSets.of(new BlockPos(0, 0, 0))));
        keys = tool.hints(view).stream().map(KeyHint::descriptionKey).toList();
        assertEquals(List.of("sculptory.hint.select.brush", "sculptory.hint.select.brush_remove",
                "sculptory.hint.brush.radius", "sculptory.hint.select.cells", "sculptory.hint.select.move",
                "sculptory.hint.select.erase"), keys);
        ctx.setSelection(CUBE);
        keys = tool.hints(view).stream().map(KeyHint::descriptionKey).toList();
        assertFalse(keys.contains("sculptory.hint.select.grow"), "Ctrl+Scroll is the radius in Brush mode: " + keys);

        lassoMode();
        keys = tool.hints(view).stream().map(KeyHint::descriptionKey).toList();
        assertEquals(List.of("sculptory.hint.select.lasso", "sculptory.hint.select.lasso_add",
                "sculptory.hint.select.lasso_remove", "sculptory.hint.select.lasso_height",
                "sculptory.hint.select.resize", "sculptory.hint.select.move", "sculptory.hint.select.grow",
                "sculptory.hint.select.erase"), keys);
        assertEquals("tool_strength", tool.hints(view).get(3).keys());
    }

    // ---- Symmetry ----

    private void symmetry(Symmetry.Mode mode) {
        settings(current().with(SelectSettings.SYMMETRY, mode));
    }

    @Test
    void symmetryGoesWithTheOpAroundTheSetCentre() {
        ctx.setSelection(CUBE);
        symmetry(Symmetry.Mode.MIRROR_X);
        actions.symmetryCentre().set(21, 7);
        assertTrue(actions.fill());
        assertEquals(new OpSpec.Fill(new Region.Cuboid(CUBE), new Pattern.Single(stone), CellMask.ANY,
                new Symmetry(Symmetry.Mode.MIRROR_X, 21, 7)), sentOps().get(0));
        // Rotate 4 fits a mixed centre onto block centres, as the brushes do.
        symmetry(Symmetry.Mode.ROTATE_4);
        actions.symmetryCentre().set(20, 7);
        assertTrue(actions.erase());
        assertEquals(new OpSpec.Erase(new Region.Cuboid(CUBE), CellMask.ANY, new Symmetry(Symmetry.Mode.ROTATE_4, 19, 7)),
                sentOps().get(1));
        symmetry(Symmetry.Mode.OFF);
        assertTrue(actions.walls());
        assertEquals(Symmetry.NONE, OpSymmetry.of(sentOps().get(2)), "off: no symmetry, whatever the centre");
    }

    @Test
    void withoutACentreASymmetricOpIsRefusedWithAToast() {
        ctx.setSelection(CUBE);
        symmetry(Symmetry.Mode.MIRROR_Z);
        assertFalse(actions.symmetryCentre().isSet());
        assertFalse(actions.erase());
        assertEquals(List.of(), session.sent());
        assertEquals(List.of(Notice.of(Notice.Level.WARNING, SelectionActions.SYMMETRY_CENTRE_NEEDED, "symmetry_centre")),
                notices);
        List<KeyHint> hints = tool.hints(view);
        assertTrue(hints.contains(KeyHint.text("sculptory.hint.symmetry_centre_needed", "symmetry_centre")), hints.toString());
        assertTrue(hints.contains(new KeyHint("symmetry_centre", "sculptory.hint.brush.symmetry_centre")), hints.toString());
        symmetry(Symmetry.Mode.OFF);
        assertFalse(tool.hints(view).contains(new KeyHint("symmetry_centre", "sculptory.hint.brush.symmetry_centre")));
    }

    @Test
    void sizesCountEveryCopy() {
        session.setPermissions(limits(1_000));
        Box sixHundred = new Box(new BlockPos(0, 0, 0), new BlockPos(9, 5, 9));
        ctx.setSelection(sixHundred);
        symmetry(Symmetry.Mode.MIRROR_X);
        actions.symmetryCentre().set(100, 0);
        assertFalse(actions.fill(), "600 blocks twice are over the limit");
        assertEquals(List.of(Notice.of(Notice.Level.WARNING, "sculptory.notice.too_large", "1,200", "1,000")), notices);
        assertEquals(List.of(), session.sent());
        // A box symmetric about the plane is one copy: the plane at x = 5 (x2 = 10) mirrors 0..9 onto itself.
        actions.symmetryCentre().set(10, 0);
        assertTrue(actions.fill());
        assertEquals(1, sentOps().size());
        assertEquals(1, OpSymmetry.copyCount(sentOps().get(0)));

        // Over the confirmation threshold with the copies counted: 260,000 blocks twice.
        session.setPermissions(limits(4_000_000));
        ctx.setSelection(new Box(new BlockPos(0, 0, 0), new BlockPos(99, 25, 99)));
        actions.symmetryCentre().set(1_000, 0);
        assertTrue(actions.fill());
        assertEquals(List.of("sculptory.confirm.large_op_copies[sculptory.op.fill,520,000,2]"), confirmations);
        assertEquals(1, sentOps().size(), "not sent until confirmed");
    }

    @Test
    void theOverlayDrawsThePlanesAndTheCopiesBoxes() {
        ctx.setSelection(CUBE);
        RecordingDraw off = new RecordingDraw();
        tool.renderWorld(view, off);
        assertEquals(0, off.lines + off.boxes.size(), "nothing without a mode");
        symmetry(Symmetry.Mode.MIRROR_XZ);
        RecordingDraw unset = new RecordingDraw();
        tool.renderWorld(view, unset);
        assertEquals(0, unset.lines + unset.boxes.size(), "nothing without a centre");
        actions.symmetryCentre().set(40, 40); // the planes x = 20 and z = 20
        RecordingDraw draw = new RecordingDraw();
        tool.renderWorld(view, draw);
        assertTrue(draw.lines > 0, "the centre line and planes");
        assertEquals(List.of(
                new Box(new BlockPos(36, 0, 0), new BlockPos(39, 3, 3)),
                new Box(new BlockPos(0, 0, 36), new BlockPos(3, 3, 39)),
                new Box(new BlockPos(36, 0, 36), new BlockPos(39, 3, 39))), draw.boxes, "the copies' bounds");
    }

    @Test
    void theCentreKeySetsAndClearsTheSharedCentre() {
        tool.frame(view, new FrameInfo(0, 0f, 0, 0, hit(3, 0, 4)));
        assertTrue(tool.onAction(view, EditorAction.SET_SYMMETRY_CENTRE));
        SymmetryCentre centre = actions.symmetryCentre();
        assertTrue(centre.isSet());
        assertEquals(7, centre.x2());
        assertEquals(9, centre.z2());
        assertEquals("sculptory.notice.symmetry_centre_set_off", noticeKeys().get(0), "the mode is off: the toast says so");
        symmetry(Symmetry.Mode.ROTATE_2);
        ctx.setModifiers(Modifiers.SHIFT);
        assertTrue(tool.onAction(view, EditorAction.SET_SYMMETRY_CENTRE));
        ctx.setModifiers(0);
        assertEquals(8, centre.x2(), "Shift: the nearest corner");
        assertEquals(10, centre.z2());
        assertEquals("sculptory.notice.symmetry_centre_set", noticeKeys().get(1));
        ctx.setModifiers(Modifiers.SHIFT);
        assertTrue(tool.onAction(view, EditorAction.SET_SYMMETRY_CENTRE));
        ctx.setModifiers(0);
        assertFalse(centre.isSet(), "the same point again clears it");
        assertEquals("sculptory.notice.symmetry_centre_cleared", noticeKeys().get(2));
        tool.frame(view, new FrameInfo(0, 0f, 0, 0, WorldCursor.miss(0, 0, 0)));
        assertTrue(tool.onAction(view, EditorAction.SET_SYMMETRY_CENTRE));
        assertEquals("sculptory.notice.symmetry_centre_aim", noticeKeys().get(3));
    }

    private static final class RecordingDraw implements WorldDraw {
        int lines;
        final List<Box> boxes = new ArrayList<>();

        @Override
        public void boxOutline(Box box, int argb) {
            boxes.add(box);
        }

        @Override
        public void boxFill(Box box, int argb) {}

        @Override
        public void line(double x1, double y1, double z1, double x2, double y2, double z2, int argb) {
            lines++;
        }

        @Override
        public void ring(double centerX, double y, double centerZ, double radius, int argb) {
            lines++;
        }

        @Override
        public void seeThrough(boolean enabled) {}
    }
}
