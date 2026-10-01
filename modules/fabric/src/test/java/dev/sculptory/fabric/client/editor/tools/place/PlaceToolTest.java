package dev.sculptory.fabric.client.editor.tools.place;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.region.CellSet;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.ShapeKind;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.core.transform.Mirror;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.core.world.WorldReader;
import dev.sculptory.fabric.client.editor.EditorBackend;
import dev.sculptory.fabric.client.editor.EditorContext;
import dev.sculptory.fabric.client.editor.input.EditorKeymap;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.mock.MockEditorSession;
import dev.sculptory.fabric.client.editor.render.ghost.GhostPlacement;
import dev.sculptory.fabric.client.editor.render.ghost.GhostVolume;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.editor.tool.DeactivateReason;
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
import dev.sculptory.fabric.client.editor.tools.select.SelectionActions;
import dev.sculptory.fabric.client.editor.world.GizmoPick;
import dev.sculptory.fabric.client.editor.world.Ray;
import dev.sculptory.fabric.client.editor.world.ScreenProjector;
import dev.sculptory.fabric.client.session.ClipboardCache;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.Notice;
import dev.sculptory.fabric.client.session.Permissions;
import dev.sculptory.fabric.client.session.Reply;
import dev.sculptory.fabric.client.session.ToolAction;
import dev.sculptory.fabric.client.session.Transfers;
import dev.sculptory.fabric.engine.Perm;
import dev.sculptory.protocol.v2.Limits;
import dev.sculptory.protocol.v2.RejectReason;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlaceToolTest {
    private static final long MS = 1_000_000L;
    private static final String HASH = "ab".repeat(32);

    private final MockEditorSession session = new MockEditorSession();
    private final FakeStateSpace states = new FakeStateSpace();
    /** What the tool bakes with: {@link #states}, failing on demand. */
    private final FailingStates bakeStates = new FailingStates(states);
    private final FakeWorld world = new FakeWorld(states, -64, 320);
    private final List<Notice> notices = new ArrayList<>();
    private final EditorBackend backend = new EditorBackend() {
        @Override
        public Optional<EditorSession> session() {
            return Optional.of(session);
        }

        @Override
        public StateSpace states() {
            return bakeStates;
        }

        @Override
        public WorldReader world() {
            return world;
        }
    };
    private final EditorContext ctx = new EditorContext(() -> backend, notices::add);

    // Fake services
    private final List<List<GhostPlacement>> shownGhosts = new ArrayList<>();
    private final List<GhostVolume> released = new ArrayList<>();
    private final List<String> confirmations = new ArrayList<>();
    private Runnable pendingConfirmation;
    private int finished;
    private Ray ray;
    private double[] gizmo;
    private boolean physicsAllowed;
    private final List<Runnable> backgroundTasks = new ArrayList<>();
    private boolean runBackgroundAtOnce = true;

    private final PlaceTool.Services services = new PlaceTool.Services() {
        @Override
        public Optional<Ray> cursorRay() {
            return Optional.ofNullable(ray);
        }

        @Override
        public Optional<ScreenProjector> projector() {
            // Seen from the south, slightly from above: x right, y up, z a little down the screen.
            return Optional.of((x, y, z, out) -> {
                out[0] = x * 10;
                out[1] = (100 - y) * 10 + z * 3;
                return true;
            });
        }

        @Override
        public Optional<double[]> eye() {
            return Optional.of(new double[] {10.5, 64, 0.5});
        }

        @Override
        public float cameraYaw() {
            return 0;
        }

        @Override
        public void showGizmo(double x, double y, double z, GizmoPick.Handle hovered) {
            gizmo = new double[] {x, y, z};
        }

        @Override
        public void clearGizmo() {
            gizmo = null;
        }

        @Override
        public void showGhosts(List<GhostPlacement> placements) {
            shownGhosts.add(List.copyOf(placements));
        }

        @Override
        public String ghostStatus() {
            return "";
        }

        @Override
        public void releaseGhost(GhostVolume volume) {
            released.add(volume);
        }

        @Override
        public String keyLabel(KeyAction action) {
            return EditorKeymap.defaults().display(action);
        }

        @Override
        public Executor background() {
            return task -> {
                if (runBackgroundAtOnce) task.run();
                else backgroundTasks.add(task);
            };
        }

        @Override
        public void confirm(String opNameKey, long blocks, Runnable onConfirm) {
            confirmations.add(opNameKey + " " + blocks);
            pendingConfirmation = onConfirm;
        }

        @Override
        public void finished() {
            finished++;
        }
    };
    private final PlaceTool tool = new PlaceTool(services, () -> physicsAllowed);
    private ToolContext view;
    private long now = 1_000 * MS;

    @BeforeEach
    void activate() {
        ctx.tools().register(tool);
        view = ctx.contextFor(ToolId.PLACE);
        assertTrue(ctx.tools().activate(ToolId.PLACE, view));
    }

    // ---- Helpers ----

    /** A clipboard of {@code dims} whose preview holds a north-facing stair at local (0, 0, 0). */
    private ClipboardCache.Entry clipboardWithPreview(BlockPos dims) {
        ClipboardCache.Entry entry = session.copy(new Box(BlockPos.ORIGIN, dims.offset(-1, -1, -1)),
                Placement.bottomCentre(dims), false).toCompletableFuture().join().toOptional().orElseThrow();
        session.putPreview(new SourceRef.Clipboard(entry.clipboardId()), preview(entry.clipboardId().toString(), dims,
                entry.anchor()));
        return entry;
    }

    private ClipboardCache.Preview preview(String key, BlockPos dims, BlockPos anchor) {
        BlockBuffer cells = new BlockBuffer();
        cells.set(0, 0, 0, states.state("minecraft:oak_stairs[facing=north]"));
        cells.set(dims.x() - 1, 0, 0, states.state("minecraft:stone"));
        GhostVolume volume = GhostVolume.of(cells, GhostBaker.air(states));
        volume.setFrame(new Box(BlockPos.ORIGIN, dims.offset(-1, -1, -1)));
        return new ClipboardCache.Preview(key, dims, anchor, 2, volume, 1024, 0);
    }

    private void frame(WorldCursor cursor) {
        tool.frame(view, new FrameInfo(now, 0f, 0, 0, cursor));
    }

    private void frameAt(int x, int y, int z) {
        frame(new WorldCursor(new BlockPos(x, y, z), WorldCursor.Face.UP, x + 0.5, y + 1, z + 0.5, false));
    }

    private static WorldCursor hit(int x, int y, int z) {
        return new WorldCursor(new BlockPos(x, y, z), WorldCursor.Face.UP, x + 0.5, y + 1, z + 0.5, false);
    }

    private void click(WorldCursor cursor) {
        tool.onPointer(view, new PointerEvent(PointerEvent.Kind.PRESS, PointerEvent.LEFT, 0, 0, 0, cursor));
        tool.onPointer(view, new PointerEvent(PointerEvent.Kind.RELEASE, PointerEvent.LEFT, 0, 0, 0, cursor));
    }

    private boolean action(EditorAction action, int modifiers) {
        ctx.setModifiers(modifiers);
        try {
            return tool.onAction(view, action);
        } finally {
            ctx.setModifiers(0);
        }
    }

    private OpSpec lastOp() {
        ToolAction.RunOp run = assertInstanceOf(ToolAction.RunOp.class, session.sent().get(session.sent().size() - 1));
        return run.op();
    }

    private List<String> noticeKeys() {
        return notices.stream().map(Notice::key).toList();
    }

    private List<GhostPlacement> lastGhosts() {
        return shownGhosts.get(shownGhosts.size() - 1);
    }

    // ---- Paste ----

    @Test
    void theGhostFollowsTheCursorOntoTheSurfaceAndEnterPastes() {
        ClipboardCache.Entry entry = clipboardWithPreview(new BlockPos(5, 3, 4));
        tool.request(new PlaceTool.Request.Paste(new SourceRef.Clipboard(entry.clipboardId()), "clipboard", Transform.IDENTITY));
        assertTrue(tool.placing());
        assertTrue(tool.following());
        frameAt(10, 63, 10);
        Placement placement = tool.placement().orElseThrow();
        assertEquals(new BlockPos(10, 64, 10), placement.pivotWorld(), "on the top face of the hit block");
        assertEquals(new BlockPos(8, 64, 8), placement.targetMin());
        GhostPlacement ghost = lastGhosts().get(0);
        assertSame(tool.sourceVolume().orElseThrow(), ghost.volume());
        assertEquals(new BlockPos(8, 64, 8), new BlockPos(ghost.originX(), ghost.originY(), ghost.originZ()));
        assertEquals(null, gizmo, "no gizmo while it follows the cursor");

        frameAt(20, 70, -3);
        assertEquals(new BlockPos(20, 71, -3), placement.pivotWorld());
        click(hit(12, 63, 12));
        assertFalse(tool.following(), "a click drops it");
        frameAt(40, 63, 40);
        assertEquals(new BlockPos(12, 64, 12), placement.pivotWorld(), "a dropped ghost stays");
        assertEquals(12.5, gizmo[0]);

        assertTrue(action(EditorAction.COMMIT, 0));
        OpSpec.Paste paste = assertInstanceOf(OpSpec.Paste.class, lastOp());
        assertEquals(new SourceRef.Clipboard(entry.clipboardId()), paste.src());
        assertEquals(new BlockPos(12, 64, 12), paste.origin(), "the anchor (bottom centre) lands on the pivot");
        assertEquals(Transform.IDENTITY, paste.t());
        assertEquals(PasteOptions.DEFAULT, paste.o());
        assertTrue(tool.following(), "ready to paste again");
        assertTrue(tool.placing());
        assertEquals(0, finished);
    }

    @Test
    void clickingAgainPicksTheGhostUp() {
        ClipboardCache.Entry entry = clipboardWithPreview(new BlockPos(3, 1, 3));
        tool.request(new PlaceTool.Request.Paste(new SourceRef.Clipboard(entry.clipboardId()), "c", Transform.IDENTITY));
        frameAt(0, 63, 0);
        click(hit(0, 63, 0));
        assertFalse(tool.following());
        click(hit(5, 63, 5));
        assertTrue(tool.following(), "a click off the gizmo picks it up");
        assertEquals(new BlockPos(5, 64, 5), tool.placement().orElseThrow().pivotWorld());
    }

    @Test
    void rotateFlipAndNudgeBuildTheTransformAndOrigin() {
        ClipboardCache.Entry entry = clipboardWithPreview(new BlockPos(5, 2, 3));
        tool.request(new PlaceTool.Request.Paste(new SourceRef.Clipboard(entry.clipboardId()), "c", Transform.IDENTITY));
        frameAt(0, 63, 0);
        click(hit(0, 63, 0));
        assertTrue(action(EditorAction.ROTATE_CW, 0));
        assertTrue(action(EditorAction.ROTATE_CW, 0));
        assertTrue(action(EditorAction.ROTATE_CCW, 0));
        assertTrue(action(EditorAction.FLIP_LEFT_RIGHT, 0));
        Transform expected = new Transform(1, Mirror.NONE).compose(new Transform(0, Mirror.X));
        assertEquals(expected, tool.placement().orElseThrow().transform());
        assertTrue(action(EditorAction.FLIP_FRONT_BACK, 0));
        expected = expected.compose(new Transform(0, Mirror.Z));
        assertEquals(expected, tool.placement().orElseThrow().transform());

        // Yaw 0 faces south (+Z): forward is +Z, right is -X; PgUp/PgDn move up and down.
        assertTrue(action(EditorAction.NUDGE_FORWARD, 0));
        assertTrue(action(EditorAction.NUDGE_RIGHT, Modifiers.SHIFT));
        assertTrue(action(EditorAction.NUDGE_UP, 0));
        assertTrue(action(EditorAction.NUDGE_UP, 0));
        assertTrue(action(EditorAction.NUDGE_DOWN, 0));
        Placement placement = tool.placement().orElseThrow();
        assertEquals(new BlockPos(-10, 65, 1), placement.pivotWorld());

        assertTrue(action(EditorAction.COMMIT, 0));
        OpSpec.Paste paste = assertInstanceOf(OpSpec.Paste.class, lastOp());
        assertEquals(expected, paste.t());
        assertEquals(placement.pasteOrigin(), paste.origin());
        // The anchor is the pivot here, so it lands on the pivot whatever the transform.
        assertEquals(new BlockPos(-10, 65, 1), paste.origin());
    }

    @Test
    void nudgingAFollowingGhostDropsIt() {
        ClipboardCache.Entry entry = clipboardWithPreview(new BlockPos(3, 1, 3));
        tool.request(new PlaceTool.Request.Paste(new SourceRef.Clipboard(entry.clipboardId()), "c", Transform.IDENTITY));
        frameAt(0, 63, 0);
        action(EditorAction.NUDGE_UP, 0);
        assertFalse(tool.following());
        frameAt(9, 63, 9);
        assertEquals(new BlockPos(0, 65, 0), tool.placement().orElseThrow().pivotWorld());
    }

    @Test
    void settingsReachThePasteAndPhysicsNeedsItsPermission() {
        ClipboardCache.Entry entry = clipboardWithPreview(new BlockPos(3, 1, 3));
        SettingsValues values = view.settings();
        assertFalse(values.isVisible(tool.settings().physics()), "hidden without the physics permission");
        physicsAllowed = true;
        assertTrue(values.isVisible(tool.settings().physics()));
        view.updateSettings(values.with(tool.settings().includeAir(), true).with(tool.settings().physics(), true));

        tool.request(new PlaceTool.Request.Paste(new SourceRef.Clipboard(entry.clipboardId()), "c", Transform.IDENTITY));
        frameAt(0, 63, 0);
        action(EditorAction.COMMIT, 0);
        ToolAction.RunOp run = assertInstanceOf(ToolAction.RunOp.class, session.sent().get(session.sent().size() - 1));
        assertEquals(new PasteOptions(true, true), ((OpSpec.Paste) run.op()).o());
        assertTrue(run.physics());

        session.setPermissions(without(Perm.PHYSICS));
        action(EditorAction.COMMIT, 0);
        run = assertInstanceOf(ToolAction.RunOp.class, session.sent().get(session.sent().size() - 1));
        assertEquals(new PasteOptions(true, false), ((OpSpec.Paste) run.op()).o(), "physics only with the permission");
        assertFalse(run.physics());
    }

    @Test
    void escCancelsAndGivesTheToolBack() {
        ClipboardCache.Entry entry = clipboardWithPreview(new BlockPos(3, 1, 3));
        tool.request(new PlaceTool.Request.Paste(new SourceRef.Clipboard(entry.clipboardId()), "c", Transform.IDENTITY));
        frameAt(0, 63, 0);
        assertTrue(action(EditorAction.CANCEL, 0));
        assertFalse(tool.placing());
        assertEquals(1, finished);
        assertTrue(lastGhosts().isEmpty());
        assertFalse(action(EditorAction.CANCEL, 0), "the next Esc goes on down the ladder");
        assertTrue(session.sent().isEmpty());
    }

    @Test
    void largePastesAskFirst() {
        session.setPermissions(new Permissions(Perm.mask(EnumSet.allOf(Perm.class)), Limits.DEFAULTS));
        ClipboardCache.Entry entry = clipboardWithPreview(new BlockPos(100, 60, 100));
        tool.request(new PlaceTool.Request.Paste(new SourceRef.Clipboard(entry.clipboardId()), "c", Transform.IDENTITY));
        frameAt(0, 63, 0);
        action(EditorAction.COMMIT, 0);
        assertEquals(List.of("sculptory.op.paste 600000"), confirmations);
        assertTrue(session.sent().isEmpty());
        pendingConfirmation.run();
        assertInstanceOf(OpSpec.Paste.class, lastOp());
    }

    @Test
    void pastesOverTheServerLimitAreRefusedHere() {
        session.setPermissions(new Permissions(Perm.mask(EnumSet.of(Perm.USE, Perm.CLIPBOARD, Perm.REGION)),
                new Limits(1_000, 1_000_000, 32, 20, 1 << 20, 2)));
        ClipboardCache.Entry entry = clipboardWithPreview(new BlockPos(10, 10, 11));
        tool.request(new PlaceTool.Request.Paste(new SourceRef.Clipboard(entry.clipboardId()), "c", Transform.IDENTITY));
        frameAt(0, 63, 0);
        action(EditorAction.COMMIT, 0);
        assertEquals(Notice.of(Notice.Level.WARNING, "sculptory.notice.too_large", "1,100", "1,000"),
                notices.get(notices.size() - 1));
        assertTrue(session.sent().isEmpty());
    }

    // ---- Library assets ----

    @Test
    void anAssetWaitsForItsPreviewThenPastesByHash() {
        session.setHoldTransfers(true);
        SourceRef asset = new SourceRef.Asset(HASH);
        tool.request(new PlaceTool.Request.Paste(asset, "trees/oak.schem", Transform.IDENTITY));
        assertTrue(tool.placing());
        assertTrue(tool.placement().isEmpty(), "its size is not known yet");
        assertEquals("sculptory.hint.place.loading", tool.hints(view).get(0).descriptionKey());
        action(EditorAction.COMMIT, 0);
        assertEquals("sculptory.notice.preview_loading", noticeKeys().get(noticeKeys().size() - 1));

        Transfers.Manual<?> transfer = session.heldTransfers().get(0);
        session.completeHeld(transfer, Reply.ok(preview("clipboard-hash", new BlockPos(7, 9, 7), new BlockPos(3, 0, 3))));
        assertTrue(tool.placement().isPresent());
        frameAt(0, 63, 0);
        action(EditorAction.COMMIT, 0);
        OpSpec.Paste paste = assertInstanceOf(OpSpec.Paste.class, lastOp());
        assertEquals(asset, paste.src());
        assertEquals(new BlockPos(0, 64, 0), paste.origin());
        List<KeyHint> hints = tool.hints(view);
        assertEquals(KeyHint.text("sculptory.hint.place.paste", "2"), hints.get(0));
    }

    @Test
    void anAssetTheServerForgotIsPreviewedAgain() {
        SourceRef asset = new SourceRef.Asset(HASH);
        session.putPreview(asset, preview("content", new BlockPos(3, 1, 3), new BlockPos(1, 0, 1)));
        tool.request(new PlaceTool.Request.Paste(asset, "trees/oak.schem", Transform.IDENTITY));
        assertEquals(1, session.calls().size(), "the cached preview needs no download");
        frameAt(0, 63, 0);
        session.rejectNextOp(RejectReason.ASSET_NOT_LOADED);
        action(EditorAction.COMMIT, 0);
        assertEquals("sculptory.notice.asset_reloading", noticeKeys().get(noticeKeys().size() - 1));
        assertEquals(List.of(new MockEditorSession.Call("preview", asset), new MockEditorSession.Call("preview", asset)),
                session.calls(), "asked for again, so the server loads it again");
        assertTrue(session.clipboards().preview(asset).isEmpty());
        assertTrue(tool.placing(), "the placement stays for another Enter");

        session.rejectNextOp(RejectReason.ASSET_NOT_LOADED);
        action(EditorAction.COMMIT, 0);
        assertEquals(Notice.of(Notice.Level.WARNING, "sculptory.reject.asset_not_loaded"),
                notices.get(notices.size() - 1), "only once per placement: the second refusal is shown as it is");
        assertEquals(2, session.calls().size(), "no third preview");
    }

    /** B3: an INVALID refusal of an asset paste is shown as it is; nothing is downloaded again. */
    @Test
    void anInvalidAssetPasteIsNotDownloadedAgain() {
        SourceRef asset = new SourceRef.Asset(HASH);
        session.putPreview(asset, preview("content", new BlockPos(3, 1, 3), new BlockPos(1, 0, 1)));
        tool.request(new PlaceTool.Request.Paste(asset, "trees/oak.schem", Transform.IDENTITY));
        frameAt(0, 63, 0);
        session.rejectNextOp(RejectReason.INVALID);
        action(EditorAction.COMMIT, 0);
        assertEquals(Notice.of(Notice.Level.WARNING, "sculptory.reject.invalid"), notices.get(notices.size() - 1));
        assertEquals(1, session.calls().size(), "no second preview for an INVALID refusal");
        assertTrue(session.clipboards().preview(asset).isPresent(), "the cached preview is kept");
    }

    @Test
    void otherRefusalsOfAPasteAreToastedPlainly() {
        ClipboardCache.Entry entry = clipboardWithPreview(new BlockPos(3, 1, 3));
        tool.request(new PlaceTool.Request.Paste(new SourceRef.Clipboard(entry.clipboardId()), "c", Transform.IDENTITY));
        frameAt(0, 63, 0);
        session.rejectNextOp(RejectReason.PROTECTED);
        action(EditorAction.COMMIT, 0);
        assertEquals(Notice.of(Notice.Level.WARNING, "sculptory.reject.protected"), notices.get(notices.size() - 1));
    }

    @Test
    void anAssetWhosePreviewIsRefusedEndsThePlacement() {
        session.setHoldTransfers(true);
        tool.request(new PlaceTool.Request.Paste(new SourceRef.Asset(HASH), "x.schem", Transform.IDENTITY));
        session.completeHeld(session.heldTransfers().get(0), Reply.refused(RejectReason.INVALID, "unknown asset"));
        assertFalse(tool.placing());
        assertEquals(1, finished);
    }

    @Test
    void escWhileLoadingCancelsTheDownload() {
        session.setHoldTransfers(true);
        tool.request(new PlaceTool.Request.Paste(new SourceRef.Asset(HASH), "x.schem", Transform.IDENTITY));
        Transfers.Manual<?> transfer = session.heldTransfers().get(0);
        assertTrue(action(EditorAction.CANCEL, 0));
        assertTrue(transfer.cancelled());
        assertFalse(tool.placing());
    }

    @Test
    void turningOrFlippingWhileThePreviewLoadsIsSwallowed() {
        session.setHoldTransfers(true);
        SourceRef asset = new SourceRef.Asset(HASH);
        tool.request(new PlaceTool.Request.Paste(asset, "x.schem", Transform.IDENTITY));
        assertTrue(tool.placement().isEmpty(), "its size is not known yet");
        // V (and R, F) with no placement yet: handled, nothing to turn, no exception (V used to throw).
        assertTrue(action(EditorAction.FLIP_UPSIDE_DOWN, 0));
        assertTrue(action(EditorAction.ROTATE_CW, 0));
        assertTrue(action(EditorAction.FLIP_LEFT_RIGHT, 0));
        assertTrue(tool.placing());
        assertFalse(noticeKeys().contains("sculptory.notice.stack_no_turn"));
        // The preview arrives untouched: the placement starts with the requested transform.
        session.completeHeld(session.heldTransfers().get(0),
                Reply.ok(preview("clipboard-hash", new BlockPos(3, 3, 3), new BlockPos(1, 0, 1))));
        assertEquals(Transform.IDENTITY, tool.placement().orElseThrow().transform());
        frameAt(0, 63, 0);
        assertTrue(action(EditorAction.FLIP_UPSIDE_DOWN, 0));
        assertEquals(Transform.UPSIDE_DOWN, tool.placement().orElseThrow().transform());
    }

    // ---- Ghost accuracy ----

    @Test
    void turnedStatesAreBakedAfterTheTransformIdles() {
        ClipboardCache.Entry entry = clipboardWithPreview(new BlockPos(4, 1, 2));
        tool.request(new PlaceTool.Request.Paste(new SourceRef.Clipboard(entry.clipboardId()), "c", Transform.IDENTITY));
        frameAt(0, 63, 0);
        GhostVolume source = tool.sourceVolume().orElseThrow();
        action(EditorAction.ROTATE_CW, 0);
        now += 100 * MS;
        frameAt(0, 63, 0);
        GhostPlacement moving = lastGhosts().get(0);
        assertSame(source, moving.volume(), "while turning, only the model matrix turns");
        assertEquals(Transform.rotation(1), moving.transform());
        assertTrue(tool.bakedVolume().isEmpty());

        now += 250 * MS;
        frameAt(0, 63, 0);
        GhostPlacement baked = lastGhosts().get(0);
        assertNotSame(source, baked.volume(), "after 300 ms idle the states are turned for real");
        assertEquals(Transform.IDENTITY, baked.transform());
        // The north-facing stair at local (0,0,0) of a 4x2 frame lands at (1,0,0) facing east.
        assertEquals(states.state("minecraft:oak_stairs[facing=east]"), baked.volume().handle(1, 0, 0));
        Placement placement = tool.placement().orElseThrow();
        assertEquals(placement.targetMin(), new BlockPos(baked.originX(), baked.originY(), baked.originZ()));

        action(EditorAction.ROTATE_CW, 0);
        now += 10 * MS;
        frameAt(0, 63, 0);
        assertSame(source, lastGhosts().get(0).volume(), "a new turn goes back to the model matrix");
        now += 400 * MS;
        frameAt(0, 63, 0);
        assertEquals(1, released.size(), "the outdated baked volume is freed when replaced");
    }

    /**
     * V flips the ghost upside down at once (model matrix), bakes the flipped states once it idles, counts the blocks
     * that stay as they are in one toast per flip, and Enter sends the flip with the anchor turned over in the box.
     */
    @Test
    void vFlipsTheGhostUpsideDownAndAToastCountsTheBlocksThatStay() {
        BlockPos dims = new BlockPos(3, 3, 2);
        ClipboardCache.Entry entry = session.copy(new Box(BlockPos.ORIGIN, dims.offset(-1, -1, -1)),
                Placement.bottomCentre(dims), false).toCompletableFuture().join().toOptional().orElseThrow();
        BlockBuffer cells = new BlockBuffer();
        cells.set(0, 0, 0, states.state("minecraft:oak_stairs[facing=north,half=bottom]"));
        cells.set(1, 0, 0, states.state("minecraft:torch"));
        cells.set(2, 0, 1, states.state("minecraft:poppy"));
        cells.set(2, 2, 1, states.state("testmod:gizmo[facing=up,spin=true]"));
        GhostVolume volume = GhostVolume.of(cells, GhostBaker.air(states));
        volume.setFrame(new Box(BlockPos.ORIGIN, dims.offset(-1, -1, -1)));
        session.putPreview(new SourceRef.Clipboard(entry.clipboardId()),
                new ClipboardCache.Preview(entry.clipboardId().toString(), dims, entry.anchor(), 4, volume, 1024, 0));
        tool.request(new PlaceTool.Request.Paste(new SourceRef.Clipboard(entry.clipboardId()), "c", Transform.IDENTITY));
        frameAt(0, 63, 0);
        click(hit(0, 63, 0));
        BlockPos min = tool.placement().orElseThrow().targetMin();
        assertTrue(action(EditorAction.FLIP_UPSIDE_DOWN, 0));
        assertEquals(Transform.UPSIDE_DOWN, tool.placement().orElseThrow().transform());
        assertEquals(min, tool.placement().orElseThrow().targetMin(), "the box stays where it is");
        now += 10 * MS;
        frameAt(0, 63, 0);
        assertEquals(Transform.UPSIDE_DOWN, lastGhosts().get(0).transform(), "at once, by the model matrix");
        assertFalse(noticeKeys().contains("sculptory.notice.flip_kept_unknown"));
        now += 400 * MS;
        frameAt(0, 63, 0);
        GhostPlacement baked = lastGhosts().get(0);
        assertEquals(Transform.IDENTITY, baked.transform());
        assertEquals(states.state("minecraft:oak_stairs[facing=north,half=top]"), baked.volume().handle(0, 2, 0));
        assertEquals(states.state("minecraft:torch"), baked.volume().handle(1, 2, 0), "a torch stays upright");
        assertEquals(states.state("testmod:gizmo[facing=down,spin=true]"), baked.volume().handle(2, 0, 1));
        assertEquals(Notice.of(Notice.Level.INFO, "sculptory.notice.flip_kept_unknown", "2", "1"),
                notices.get(notices.size() - 1));
        int toasts = notices.size();
        action(EditorAction.ROTATE_CW, 0);
        now += 400 * MS;
        frameAt(0, 63, 0);
        assertEquals(toasts, notices.size(), "one toast per flip");

        action(EditorAction.ROTATE_CCW, 0);
        assertTrue(action(EditorAction.COMMIT, 0));
        OpSpec.Paste paste = assertInstanceOf(OpSpec.Paste.class, lastOp());
        assertEquals(Transform.UPSIDE_DOWN, paste.t());
        Placement placement = tool.placement().orElseThrow();
        // The anchor (bottom centre) turns over to the top of the same box.
        assertEquals(new BlockPos(placement.pivotWorld().x(), min.y() + 2, placement.pivotWorld().z()), paste.origin());
    }

    @Test
    void aBakeFinishingAfterAnotherTurnIsDropped() {
        ClipboardCache.Entry entry = clipboardWithPreview(new BlockPos(4, 1, 2));
        tool.request(new PlaceTool.Request.Paste(new SourceRef.Clipboard(entry.clipboardId()), "c", Transform.IDENTITY));
        runBackgroundAtOnce = false;
        frameAt(0, 63, 0);
        action(EditorAction.ROTATE_CW, 0);
        now += 400 * MS;
        frameAt(0, 63, 0);
        assertEquals(1, backgroundTasks.size(), "baking off the render thread");
        action(EditorAction.ROTATE_CW, 0);
        backgroundTasks.remove(0).run();
        frameAt(0, 63, 0);
        assertTrue(tool.bakedVolume().isEmpty());
        assertEquals(1, released.size(), "the stale bake is freed");
    }

    /**
     * B5: a bake that fails never rethrows on the render thread: that transform shows outlines only and is not baked
     * again; another transform bakes as usual.
     */
    @Test
    void aFailedBakeFallsBackToOutlines() {
        ClipboardCache.Entry entry = clipboardWithPreview(new BlockPos(4, 1, 2));
        tool.request(new PlaceTool.Request.Paste(new SourceRef.Clipboard(entry.clipboardId()), "c", Transform.IDENTITY));
        frameAt(0, 63, 0);
        bakeStates.failing = true;
        action(EditorAction.ROTATE_CW, 0);
        now += 400 * MS;
        frameAt(0, 63, 0); // the bake runs at once and fails
        assertTrue(tool.bakeFailed());
        assertTrue(tool.bakedVolume().isEmpty());
        assertTrue(lastGhosts().isEmpty(), "outlines only");
        assertTrue(tool.placing(), "the placement stays and can still be committed");
        runBackgroundAtOnce = false;
        now += 400 * MS;
        frameAt(0, 63, 0);
        assertTrue(backgroundTasks.isEmpty(), "the failed transform is not baked again");

        bakeStates.failing = false;
        runBackgroundAtOnce = true;
        action(EditorAction.ROTATE_CW, 0);
        now += 400 * MS;
        frameAt(0, 63, 0);
        assertFalse(tool.bakeFailed());
        assertTrue(tool.bakedVolume().isPresent(), "another turn bakes");
        assertFalse(lastGhosts().isEmpty());
    }

    // ---- Gizmo ----

    @Test
    void theGizmoArrowDragsInWholeBlocks() {
        ClipboardCache.Entry entry = clipboardWithPreview(new BlockPos(3, 1, 3));
        tool.request(new PlaceTool.Request.Paste(new SourceRef.Clipboard(entry.clipboardId()), "c", Transform.IDENTITY));
        frameAt(10, 63, 10);
        click(hit(10, 63, 10));
        frameAt(10, 63, 10);
        assertEquals(10.5, gizmo[0]);
        // The X arrow runs right from the gizmo origin (10.5, 64, 10.5), projected at (105, 391.5).
        double scale = GizmoPick.scale(10.0);
        double pressX = 105 + scale * 10 * 0.8;
        ray = new Ray(10.8, 90, 10.5, 0, -1, 0);
        assertTrue(tool.onPointer(view, new PointerEvent(PointerEvent.Kind.PRESS, PointerEvent.LEFT, pressX, 391.5, 0,
                hit(10, 63, 10))));
        assertTrue(ctx.pointerCapture());
        ray = new Ray(13.9, 90, 10.5, 0, -1, 0);
        tool.onPointer(view, new PointerEvent(PointerEvent.Kind.DRAG, PointerEvent.LEFT, 0, 0, 0, hit(0, 0, 0)));
        assertEquals(new BlockPos(13, 64, 10), tool.placement().orElseThrow().pivotWorld(), "3.1 blocks snap to 3");
        tool.onPointer(view, new PointerEvent(PointerEvent.Kind.RELEASE, PointerEvent.LEFT, 0, 0, 0, hit(0, 0, 0)));
        assertFalse(ctx.pointerCapture());
        assertFalse(tool.following(), "dragging the gizmo does not pick the ghost up");
    }

    // ---- Move and stack ----

    @Test
    void stackCountsFollowCtrlScrollAndStackCannotTurn() {
        Box box = new Box(new BlockPos(0, 64, 0), new BlockPos(1, 64, 2));
        world.set(0, 64, 0, states.state("minecraft:stone"));
        tool.request(new PlaceTool.Request.Stack(box));
        Placement placement = tool.placement().orElseThrow();
        assertEquals(new BlockPos(0, 0, 3), placement.offset(), "the first copy is next to the box, facing south");
        assertTrue(tool.onScroll(view, new ScrollEvent(1, Modifiers.CONTROL)));
        tool.onScroll(view, new ScrollEvent(1, Modifiers.CONTROL));
        tool.onScroll(view, new ScrollEvent(1, Modifiers.CONTROL | Modifiers.SHIFT));
        assertEquals(13, placement.count());
        assertFalse(tool.onScroll(view, new ScrollEvent(1, 0)), "plain scroll is the fly speed");
        for (int i = 0; i < 30; i++) tool.onScroll(view, new ScrollEvent(-1, Modifiers.CONTROL | Modifiers.SHIFT));
        assertEquals(1, placement.count());
        for (int i = 0; i < 30; i++) tool.onScroll(view, new ScrollEvent(1, Modifiers.CONTROL | Modifiers.SHIFT));
        assertEquals(256, placement.count());
        placement.setCount(4);

        assertTrue(action(EditorAction.ROTATE_CW, 0));
        assertEquals("sculptory.notice.stack_no_turn", noticeKeys().get(noticeKeys().size() - 1));
        assertTrue(placement.transform().isIdentity());

        frame(WorldCursor.miss(0, 0, 0));
        assertEquals(4, lastGhosts().size(), "one ghost per copy");
        assertEquals(new BlockPos(0, 64, 9), new BlockPos(lastGhosts().get(2).originX(), lastGhosts().get(2).originY(),
                lastGhosts().get(2).originZ()));

        action(EditorAction.NUDGE_UP, 0);
        action(EditorAction.COMMIT, 0);
        assertEquals(new OpSpec.Stack(new Region.Cuboid(box), 0, 1, 3, 4, EntityFilter.DECORATIONS), lastOp(),
                "a stack takes decorations along by default");
        assertFalse(tool.placing());
        assertEquals(1, finished);
    }

    @Test
    void moveShowsTheSelectionsBlocksAndMovesTheSelection() {
        Box box = new Box(new BlockPos(0, 64, 0), new BlockPos(2, 65, 2));
        world.set(1, 64, 1, states.state("minecraft:oak_log[axis=x]"));
        view.setSelection(box);
        tool.request(new PlaceTool.Request.Move(box));
        assertTrue(tool.sourceVolume().isEmpty(), "the selection is read over the next frames");
        frame(WorldCursor.miss(0, 0, 0));
        assertEquals(1, tool.sourceVolume().orElseThrow().blockCount());
        assertFalse(tool.following(), "a move starts where the selection is");
        action(EditorAction.ROTATE_CW, 0);
        action(EditorAction.NUDGE_RIGHT, 0);
        action(EditorAction.COMMIT, 0);
        OpSpec.Move move = assertInstanceOf(OpSpec.Move.class, lastOp());
        assertEquals(box, move.box());
        assertEquals(Transform.rotation(1), move.t());
        Placement expected = Placement.move(box);
        expected.rotate(1);
        expected.nudge(-1, 0, 0);
        assertEquals(expected.offset(), move.offset());
        assertEquals(expected.targetBox(), view.selection().orElseThrow());
        assertEquals(1, finished);
    }

    @Test
    void movingAShapeMovesOnlyItsCellsAndTheShapeFollowsTurned() {
        Box box = new Box(new BlockPos(0, 64, 0), new BlockPos(4, 66, 2));
        world.fill(box, states.state("minecraft:stone"));
        Region.Shape cone = new Region.Shape(box, ShapeKind.CONE, Facing.EAST);
        view.setSelectionRegion(cone);
        tool.request(new PlaceTool.Request.Move(cone));
        frame(WorldCursor.miss(0, 0, 0));
        assertEquals(cone.cellCount(), tool.sourceVolume().orElseThrow().blockCount(),
                "the ghost holds the cone's blocks, not the rest of its box");
        assertTrue(tool.hints(view).contains(KeyHint.text("sculptory.hint.place.move",
                dev.sculptory.fabric.client.session.SessionNotices.count(cone.cellCount()))));
        action(EditorAction.ROTATE_CW, 0);
        action(EditorAction.NUDGE_UP, 0);
        action(EditorAction.COMMIT, 0);
        OpSpec.Move move = assertInstanceOf(OpSpec.Move.class, lastOp());
        assertEquals(cone, move.region());
        assertEquals(EntityFilter.DECORATIONS, move.entities(), "the Entities setting's default");
        assertEquals(Transform.rotation(1), move.t());
        Placement expected = Placement.move(cone);
        expected.rotate(1);
        expected.nudge(0, 1, 0);
        assertEquals(expected.offset(), move.offset());
        Region.Shape moved = assertInstanceOf(Region.Shape.class, view.selectionRegion().orElseThrow());
        assertEquals(expected.targetBox(), moved.box());
        assertEquals(Facing.SOUTH, moved.facing(), "a quarter turn clockwise turns east to south");
        assertEquals(ShapeKind.CONE, moved.kind());
    }

    @Test
    void stackingACellSetRepeatsItsCellsAndCountsThem() {
        world.set(0, 64, 0, states.state("minecraft:stone"));
        world.set(2, 65, 1, states.state("minecraft:dirt"));
        world.set(1, 64, 1, states.state("minecraft:stone")); // inside the bounds, not in the set
        CellSet.Builder builder = CellSet.builder();
        builder.add(0, 64, 0).add(2, 65, 1).add(2, 64, 0);
        Region.Cells cells = new Region.Cells(builder.build());
        tool.request(new PlaceTool.Request.Stack(cells));
        frame(WorldCursor.miss(0, 0, 0));
        assertEquals(2, tool.sourceVolume().orElseThrow().blockCount(), "the set's non-air cells only");
        tool.onScroll(view, new ScrollEvent(1, Modifiers.CONTROL));
        assertTrue(tool.hints(view).contains(KeyHint.text("sculptory.hint.place.stack", "2", "6")),
                "three cells, two copies: " + tool.hints(view));
        action(EditorAction.COMMIT, 0);
        OpSpec.Stack stack = assertInstanceOf(OpSpec.Stack.class, lastOp());
        assertEquals(cells, stack.region());
        assertEquals(2, stack.count());
        assertEquals(EntityFilter.DECORATIONS, stack.entities(), "the Entities setting's default");
    }

    @Test
    void aMovedCellSetIsSelectedWhereItsCellsLand() {
        CellSet.Builder builder = CellSet.builder();
        builder.add(0, 64, 0).add(3, 64, 0).add(3, 64, 1);
        Region.Cells cells = new Region.Cells(builder.build());
        tool.request(new PlaceTool.Request.Move(cells));
        frame(WorldCursor.miss(0, 0, 0));
        action(EditorAction.FLIP_LEFT_RIGHT, 0);
        action(EditorAction.NUDGE_UP, 0);
        action(EditorAction.COMMIT, 0);
        Region.Cells moved = assertInstanceOf(Region.Cells.class, view.selectionRegion().orElseThrow());
        assertEquals(3, moved.cellCount());
        // Mirrored east-west within its 4-wide bounds (x 0..3), one block up; the pivot stays put.
        Placement expected = Placement.move(cells);
        expected.mirror(Mirror.X);
        expected.nudge(0, 1, 0);
        BlockPos min = expected.targetMin();
        assertTrue(moved.contains(min.x() + 3, 65, min.z()), "x 0 lands on the far side");
        assertTrue(moved.contains(min.x(), 65, min.z()));
        assertTrue(moved.contains(min.x(), 65, min.z() + 1));
    }

    @Test
    void aTurnedCellSetIsMappedOnlyAfterConfirmationAndOffTheClientThread() {
        List<Runnable> work = new ArrayList<>();
        List<Runnable> client = new ArrayList<>();
        ctx.setRegionWork(new RegionWork(work::add, client::add));
        // 600,000 cells (whole sections, cheap to hold): over the confirmation threshold.
        Region.Cells cells = new Region.Cells(CellSet.of(new Region.Cuboid(
                new Box(new BlockPos(0, 64, 0), new BlockPos(99, 123, 99))), 1_000_000));
        view.setSelectionRegion(cells);
        tool.setMaxCaptureCells(10); // outline only: no ghost to read
        tool.request(new PlaceTool.Request.Move(cells));
        frame(WorldCursor.miss(0, 0, 0));
        action(EditorAction.ROTATE_CW, 0);
        action(EditorAction.NUDGE_UP, 0);
        action(EditorAction.COMMIT, 0);
        assertEquals(1, confirmations.size());
        assertTrue(work.isEmpty(), "nothing is mapped before the move is confirmed");
        Selection before = view.selectionState().orElseThrow();

        pendingConfirmation.run();
        assertInstanceOf(OpSpec.Move.class, lastOp());
        assertTrue(view.selectionState().orElseThrow() == before, "the client thread did not map the cells");
        assertEquals(1, work.size());
        work.remove(0).run();
        client.remove(0).run();
        Region moved = view.selectionRegion().orElseThrow();
        assertEquals(cells.cellCount(), moved.cellCount());
        assertEquals(64 + 1, moved.bounds().min().y());
    }

    @Test
    void anUnturnedMoveOfACellSetKeepsItsOutlineAndMovesItsOffset() {
        Region.Cells cells = new Region.Cells(CellSet.builder().add(0, 64, 0).add(3, 64, 1).build());
        view.setSelectionRegion(cells);
        Region materialized = view.selectionRegion().orElseThrow();
        tool.request(new PlaceTool.Request.Move(materialized));
        frame(WorldCursor.miss(0, 0, 0));
        action(EditorAction.NUDGE_UP, 0);
        action(EditorAction.COMMIT, 0);
        Selection after = view.selectionState().orElseThrow();
        assertTrue(after.base() == cells, "the same cell set, moved by an offset");
        assertEquals(cells.translate(0, 1, 0), after.region());
    }

    @Test
    void aLargeShapesMoveWaitsForItsCount() {
        List<Runnable> work = new ArrayList<>();
        List<Runnable> client = new ArrayList<>();
        ctx.setRegionWork(new RegionWork(work::add, client::add));
        Region.Shape sphere = new Region.Shape(new Box(new BlockPos(0, 0, 0), new BlockPos(149, 149, 149)),
                ShapeKind.ELLIPSOID, Facing.UP);
        tool.setMaxCaptureCells(10);
        tool.request(new PlaceTool.Request.Move(sphere));
        frame(WorldCursor.miss(0, 0, 0));
        assertTrue(tool.hints(view).contains(KeyHint.text("sculptory.hint.place.move", "…")), "counting");
        action(EditorAction.NUDGE_UP, 0);
        action(EditorAction.COMMIT, 0);
        assertTrue(confirmations.isEmpty());
        assertEquals("sculptory.notice.counting", noticeKeys().get(noticeKeys().size() - 1));
        while (!work.isEmpty() || !client.isEmpty()) {
            while (!work.isEmpty()) work.remove(0).run();
            while (!client.isEmpty()) client.remove(0).run();
        }
        assertEquals(List.of("sculptory.op.move " + sphere.cellCount()), confirmations);
    }

    @Test
    void largeSelectionsAreReadAFewSectionsPerFrame() {
        Box box = new Box(new BlockPos(0, 64, 0), new BlockPos(16 * 8 - 1, 64, 16 * 5 - 1));
        world.set(127, 64, 79, states.state("minecraft:stone"));
        tool.request(new PlaceTool.Request.Move(box));
        int frames = 0;
        while (tool.sourceVolume().isEmpty() && frames < 100) {
            frame(WorldCursor.miss(0, 0, 0));
            frames++;
        }
        assertEquals((40 + PlaceTool.CAPTURE_SECTIONS_PER_FRAME - 1) / PlaceTool.CAPTURE_SECTIONS_PER_FRAME, frames,
                "40 sections, a slice per frame");
        assertEquals(1, tool.sourceVolume().orElseThrow().blockCount());
    }

    @Test
    void selectionsOverTheCaptureCapShowOnlyTheirOutline() {
        tool.setMaxCaptureCells(10);
        Box box = new Box(new BlockPos(0, 64, 0), new BlockPos(2, 66, 2));
        world.set(1, 65, 1, states.state("minecraft:stone"));
        tool.request(new PlaceTool.Request.Stack(box));
        frame(WorldCursor.miss(0, 0, 0));
        assertTrue(tool.outlineOnly());
        assertTrue(tool.sourceVolume().isEmpty());
        assertTrue(lastGhosts().isEmpty());
        assertTrue(tool.hints(view).contains(KeyHint.text("sculptory.hint.place.outline_only")));
        action(EditorAction.COMMIT, 0);
        assertEquals(new OpSpec.Stack(new Region.Cuboid(box), 0, 0, 3, 1, EntityFilter.DECORATIONS), lastOp(),
                "it can still be committed");
    }

    @Test
    void replacingTheClipboardEndsItsPaste() {
        ClipboardCache.Entry first = clipboardWithPreview(new BlockPos(3, 1, 3));
        tool.request(new PlaceTool.Request.Paste(new SourceRef.Clipboard(first.clipboardId()), "c", Transform.IDENTITY));
        frameAt(0, 63, 0);
        assertTrue(tool.placing());
        clipboardWithPreview(new BlockPos(5, 1, 5)); // a new copy: the old clipboard id is dead
        assertTrue(tool.placing(), "ended at the next frame, not inside the session's callback");
        frameAt(0, 63, 0);
        assertFalse(tool.placing());
        assertEquals(Notice.of(Notice.Level.INFO, "sculptory.notice.clipboard_changed",
                EditorKeymap.defaults().display(KeyAction.PASTE)), notices.get(notices.size() - 1));
        assertEquals(1, finished);
        assertTrue(session.sent().isEmpty(), "nothing was pasted with the dead id");
    }

    @Test
    void clearingTheClipboardStopsEnterFromPastingIt() {
        ClipboardCache.Entry entry = clipboardWithPreview(new BlockPos(3, 1, 3));
        tool.request(new PlaceTool.Request.Paste(new SourceRef.Clipboard(entry.clipboardId()), "c", Transform.IDENTITY));
        frameAt(0, 63, 0);
        session.clipboards().forgetCurrent();
        action(EditorAction.COMMIT, 0);
        assertTrue(session.sent().isEmpty());
        assertFalse(tool.placing());
        assertEquals("sculptory.notice.clipboard_changed", noticeKeys().get(noticeKeys().size() - 1));
    }

    @Test
    void anotherClipboardChangingDoesNotEndAnAssetPaste() {
        SourceRef asset = new SourceRef.Asset(HASH);
        session.putPreview(asset, preview("content", new BlockPos(3, 1, 3), new BlockPos(1, 0, 1)));
        tool.request(new PlaceTool.Request.Paste(asset, "oak.schem", Transform.IDENTITY));
        clipboardWithPreview(new BlockPos(2, 2, 2));
        frameAt(0, 63, 0);
        assertTrue(tool.placing());
    }

    @Test
    void theToolAloneOffersTheClipboardAndSaysWhatToDoWithout() {
        ctx.tools().deactivate(view, DeactivateReason.SWITCHED_TOOL);
        ctx.tools().activate(ToolId.PLACE, view);
        assertFalse(tool.placing());
        assertEquals(List.of(KeyHint.text("sculptory.hint.place.empty")), tool.hints(view));
        ClipboardCache.Entry entry = clipboardWithPreview(new BlockPos(2, 2, 2));
        ctx.tools().deactivate(view, DeactivateReason.SWITCHED_TOOL);
        ctx.tools().activate(ToolId.PLACE, view);
        assertTrue(tool.placing(), "chosen from the palette, it pastes the clipboard");
        assertEquals(new SourceRef.Clipboard(entry.clipboardId()), tool.placement().orElseThrow().source());
        action(EditorAction.CANCEL, 0);
        assertEquals(0, finished, "not started from another tool: nothing to go back to");
    }

    private static Permissions without(Perm... missing) {
        EnumSet<Perm> granted = EnumSet.allOf(Perm.class);
        for (Perm perm : missing) granted.remove(perm);
        return new Permissions(Perm.mask(granted), Limits.DEFAULTS);
    }

    // ---- Symmetry ----

    private void symmetry(Symmetry.Mode mode) {
        ctx.updateSettings(ToolId.PLACE, view.settings().with(tool.settings().symmetry(), mode));
    }

    private static Box boxOf(GhostPlacement ghost) {
        return new Box(new BlockPos(ghost.originX(), ghost.originY(), ghost.originZ()),
                new BlockPos(ghost.originX(), ghost.originY(), ghost.originZ()));
    }

    @Test
    void aPasteWithSymmetryCarriesItAndShowsTheCopiesAsGhosts() {
        ClipboardCache.Entry entry = clipboardWithPreview(new BlockPos(5, 3, 4));
        tool.request(new PlaceTool.Request.Paste(new SourceRef.Clipboard(entry.clipboardId()), "clipboard", Transform.IDENTITY));
        frameAt(10, 63, 10);
        symmetry(Symmetry.Mode.MIRROR_X);
        tool.symmetryCentre().set(2 * 30 + 1, 0); // the plane x = 30.5
        frameAt(10, 63, 10);
        Placement placement = tool.placement().orElseThrow();
        Symmetry mirror = new Symmetry(Symmetry.Mode.MIRROR_X, 61, 0);
        Box target = placement.targetBox();
        Box image = mirror.imageBox(Symmetry.Image.MIRROR_X, target);
        List<GhostPlacement> ghosts = lastGhosts();
        assertEquals(2, ghosts.size(), "the paste and its mirror");
        assertEquals(target.min(), boxOf(ghosts.get(0)).min());
        assertEquals(image.min(), boxOf(ghosts.get(1)).min(), "the copy's frame lands on the image of the target");
        assertEquals(new Transform(0, Mirror.X), ghosts.get(1).transform(), "the same volume, mirrored");
        assertTrue(tool.hints(view).contains(KeyHint.text("sculptory.hint.place.paste_copies", "120", "2")), tool.hints(view).toString());
        RecordingDraw draw = new RecordingDraw();
        tool.renderWorld(view, draw);
        assertTrue(draw.boxes.contains(image), "the copy's box is outlined: " + draw.boxes);
        assertTrue(draw.lines > 0, "the centre line and plane");

        action(EditorAction.ROTATE_CW, 0);
        action(EditorAction.COMMIT, 0);
        OpSpec.Paste paste = assertInstanceOf(OpSpec.Paste.class, lastOp());
        assertEquals(mirror, paste.symmetry());
        assertEquals(Transform.rotation(1), paste.t());
        assertEquals(placement.pasteOrigin(), paste.origin());
    }

    /** After a symmetric paste the ghost follows the cursor again, still with its mirror copy; so does the next paste. */
    @Test
    void aSymmetricPastesNextGhostKeepsItsMirrorCopy() {
        ClipboardCache.Entry entry = clipboardWithPreview(new BlockPos(5, 3, 4));
        tool.request(new PlaceTool.Request.Paste(new SourceRef.Clipboard(entry.clipboardId()), "clipboard", Transform.IDENTITY));
        symmetry(Symmetry.Mode.MIRROR_X);
        tool.symmetryCentre().set(2 * 30 + 1, 0);
        frameAt(10, 63, 10);
        assertEquals(2, lastGhosts().size(), "the paste and its mirror");
        action(EditorAction.COMMIT, 0);
        assertInstanceOf(OpSpec.Paste.class, lastOp());
        session.finishJobs();
        frameAt(10, 63, 10);
        assertEquals(2, lastGhosts().size(), "still both, at the same aim: " + lastGhosts());
        frameAt(14, 63, 20);
        List<GhostPlacement> ghosts = lastGhosts();
        assertEquals(2, ghosts.size(), "the following ghost keeps its mirror copy: " + ghosts);
        Symmetry mirror = new Symmetry(Symmetry.Mode.MIRROR_X, 61, 0);
        Box image = mirror.imageBox(Symmetry.Image.MIRROR_X, tool.placement().orElseThrow().targetBox());
        assertEquals(image.min(), boxOf(ghosts.get(1)).min());
        assertEquals(new Transform(0, Mirror.X), ghosts.get(1).transform());

        // A new paste of the same clipboard (Ctrl+V again) starts with both as well.
        tool.request(new PlaceTool.Request.Paste(new SourceRef.Clipboard(entry.clipboardId()), "clipboard", Transform.IDENTITY));
        frameAt(14, 63, 20);
        assertEquals(2, lastGhosts().size(), "a new paste shows its mirror copy at once: " + lastGhosts());
    }

    @Test
    void withoutACentreASymmetricPlacementIsRefused() {
        ClipboardCache.Entry entry = clipboardWithPreview(new BlockPos(2, 2, 2));
        tool.request(new PlaceTool.Request.Paste(new SourceRef.Clipboard(entry.clipboardId()), "clipboard", Transform.IDENTITY));
        frameAt(10, 63, 10);
        symmetry(Symmetry.Mode.ROTATE_4);
        assertFalse(tool.symmetryCentre().isSet());
        assertEquals(1, lastGhosts().size(), "no copies without a centre");
        assertTrue(tool.hints(view).contains(KeyHint.text("sculptory.hint.symmetry_centre_needed",
                EditorKeymap.defaults().display(KeyAction.SET_SYMMETRY_CENTRE))), tool.hints(view).toString());
        action(EditorAction.COMMIT, 0);
        assertEquals(Notice.of(Notice.Level.WARNING, SelectionActions.SYMMETRY_CENTRE_NEEDED,
                EditorKeymap.defaults().display(KeyAction.SET_SYMMETRY_CENTRE)), notices.get(notices.size() - 1));
        assertTrue(session.sent().isEmpty());
        assertTrue(tool.placing(), "the placement stays");
        // The centre key works from the Place tool too.
        frameAt(4, 63, 6);
        assertTrue(action(EditorAction.SET_SYMMETRY_CENTRE, 0));
        assertEquals(9, tool.symmetryCentre().x2());
        assertEquals(13, tool.symmetryCentre().z2());
        frameAt(4, 63, 6);
        assertEquals(4, lastGhosts().size(), "Rotate 4 around the set centre");
    }

    @Test
    void moveAndStackCopiesReadTheImageRegionsOwnBlocks() {
        Box box = new Box(new BlockPos(0, 64, 0), new BlockPos(2, 65, 2));
        world.set(1, 64, 1, states.state("minecraft:oak_log[axis=x]"));
        // The mirror plane x = 10 (x2 = 20) puts the image at x 17..19, where a stone and a dirt block stand.
        world.set(18, 64, 1, states.state("minecraft:stone"));
        world.set(17, 65, 2, states.state("minecraft:dirt"));
        view.setSelection(box);
        tool.request(new PlaceTool.Request.Move(box));
        symmetry(Symmetry.Mode.MIRROR_X);
        tool.symmetryCentre().set(20, 0);
        int frames = 0;
        while (tool.copyVolumes().isEmpty() && frames++ < 100) frame(WorldCursor.miss(0, 0, 0));
        assertEquals(1, tool.copyVolumes().size(), "the image region was read");
        assertEquals(2, tool.copyVolumes().get(0).blockCount(), "the image's own blocks, not the selection's mirrored");
        assertEquals(1, tool.sourceVolume().orElseThrow().blockCount());
        assertFalse(tool.copiesOutlineOnly());
        action(EditorAction.ROTATE_CW, 0);
        action(EditorAction.NUDGE_UP, 0);
        frame(WorldCursor.miss(0, 0, 0));
        Placement placement = tool.placement().orElseThrow();
        Symmetry mirror = new Symmetry(Symmetry.Mode.MIRROR_X, 20, 0);
        List<GhostPlacement> ghosts = lastGhosts();
        assertEquals(2, ghosts.size());
        assertEquals(mirror.imageBox(Symmetry.Image.MIRROR_X, placement.targetBox()).min(), boxOf(ghosts.get(1)).min());
        assertEquals(Transform.rotation(3), ghosts.get(1).transform(), "a clockwise turn mirrored is a counter-clockwise one");
        assertTrue(tool.hints(view).contains(KeyHint.text("sculptory.hint.place.move_copies", "36", "2")), tool.hints(view).toString());
        action(EditorAction.COMMIT, 0);
        OpSpec.Move move = assertInstanceOf(OpSpec.Move.class, lastOp());
        assertEquals(mirror, move.symmetry());
        assertEquals(Transform.rotation(1), move.t());

        // A stack: each stack copy has its mirror image, from the image region's content.
        tool.request(new PlaceTool.Request.Stack(box));
        frame(WorldCursor.miss(0, 0, 0));
        tool.placement().orElseThrow().setCount(3);
        frames = 0;
        while (tool.copyVolumes().isEmpty() && frames++ < 100) frame(WorldCursor.miss(0, 0, 0));
        frame(WorldCursor.miss(0, 0, 0));
        assertEquals(6, lastGhosts().size(), "three stack copies and their mirrors");
        action(EditorAction.COMMIT, 0);
        assertEquals(mirror, assertInstanceOf(OpSpec.Stack.class, lastOp()).symmetry());
    }

    /** A box symmetric about the plane is one copy until it moves across it: then the server makes two, and so do we. */
    @Test
    void aMoveAcrossItsOwnMirrorPlaneCountsTwoCopies() {
        Box box = new Box(new BlockPos(0, 64, 0), new BlockPos(2, 65, 2)); // its mirror about x = 1.5 (x2 = 3) is itself
        world.set(1, 64, 1, states.state("minecraft:stone"));
        view.setSelection(box);
        tool.request(new PlaceTool.Request.Move(box));
        symmetry(Symmetry.Mode.MIRROR_X);
        tool.symmetryCentre().set(3, 0);
        frame(WorldCursor.miss(0, 0, 0));
        assertTrue(tool.hints(view).contains(KeyHint.text("sculptory.hint.place.move", "18")), "unmoved: one copy " + tool.hints(view));
        action(EditorAction.NUDGE_RIGHT, 0);
        frame(WorldCursor.miss(0, 0, 0));
        assertTrue(tool.hints(view).contains(KeyHint.text("sculptory.hint.place.move_copies", "36", "2")),
                "moved across the plane: two copies " + tool.hints(view));
        action(EditorAction.COMMIT, 0);
        OpSpec.Move move = assertInstanceOf(OpSpec.Move.class, lastOp());
        assertEquals(2, dev.sculptory.core.edit.OpSymmetry.copyCount(move), "the server's count");

        tool.request(new PlaceTool.Request.Stack(box));
        frame(WorldCursor.miss(0, 0, 0));
        assertTrue(tool.hints(view).contains(KeyHint.text("sculptory.hint.place.stack", "1", "18")),
                "a stack straight south of the plane is its own mirror: " + tool.hints(view));
        action(EditorAction.NUDGE_RIGHT, 0);
        frame(WorldCursor.miss(0, 0, 0));
        assertTrue(tool.hints(view).contains(KeyHint.text("sculptory.hint.place.stack_copies", "1", "36", "2")),
                "a stack stepping across the plane: " + tool.hints(view));
    }

    /** While a move is placed its pivot shows: an upright centre line, and a line from where the pivot was. */
    @Test
    void aMoveShowsItsPivotAndTheLineFromWhereItWas() {
        Box box = new Box(new BlockPos(0, 64, 0), new BlockPos(2, 65, 2));
        view.setSelection(box);
        tool.request(new PlaceTool.Request.Move(box));
        frame(WorldCursor.miss(0, 0, 0));
        RecordingDraw unmoved = new RecordingDraw();
        tool.renderWorld(view, unmoved);
        BlockPos pivot = tool.placement().orElseThrow().pivotWorld();
        assertEquals(new BlockPos(1, 64, 1), pivot, "the bottom centre of the box");
        RecordingDraw.Segment upright = new RecordingDraw.Segment(1.5, 63, 1.5, 1.5, 64 + 2 + 1, 1.5);
        assertTrue(unmoved.segments.contains(upright), "the centre line through the pivot: " + unmoved.segments);
        assertEquals(3, unmoved.segments.size(), "the line and its cross; nothing from a pivot that has not moved");

        action(EditorAction.NUDGE_RIGHT, 0);
        action(EditorAction.NUDGE_RIGHT, 0);
        frame(WorldCursor.miss(0, 0, 0));
        BlockPos moved = tool.placement().orElseThrow().pivotWorld();
        assertEquals(2, Math.abs(moved.x() - pivot.x()) + Math.abs(moved.z() - pivot.z()), "nudged two blocks");
        RecordingDraw draw = new RecordingDraw();
        tool.renderWorld(view, draw);
        assertTrue(draw.segments.contains(new RecordingDraw.Segment(moved.x() + 0.5, 63, moved.z() + 0.5, moved.x() + 0.5, 67, moved.z() + 0.5)),
                "the centre line follows the pivot: " + draw.segments);
        assertTrue(draw.segments.contains(new RecordingDraw.Segment(1.5, 64, 1.5, moved.x() + 0.5, 64, moved.z() + 0.5)),
                "a line from where the pivot was to where it goes: " + draw.segments);
    }

    @Test
    void copiesOverTheCaptureCapAreOutlinesWhileTheOriginalKeepsItsGhost() {
        tool.setMaxCaptureCells(20); // 18 cells fit once, not twice
        Box box = new Box(new BlockPos(0, 64, 0), new BlockPos(2, 65, 2));
        world.set(1, 64, 1, states.state("minecraft:stone"));
        view.setSelection(box);
        tool.request(new PlaceTool.Request.Move(box));
        symmetry(Symmetry.Mode.MIRROR_Z);
        tool.symmetryCentre().set(0, 40);
        int frames = 0;
        while (tool.sourceVolume().isEmpty() && frames++ < 100) frame(WorldCursor.miss(0, 0, 0));
        assertFalse(tool.outlineOnly(), "the selection alone fits the cap");
        assertTrue(tool.copiesOutlineOnly(), "the copies together do not");
        assertTrue(tool.copyVolumes().isEmpty());
        assertEquals(1, lastGhosts().size(), "the original's ghost only");
        assertTrue(tool.hints(view).contains(KeyHint.text("sculptory.hint.place.copies_outline_only")), tool.hints(view).toString());
        RecordingDraw draw = new RecordingDraw();
        tool.renderWorld(view, draw);
        Box image = new Symmetry(Symmetry.Mode.MIRROR_Z, 0, 40).imageBox(Symmetry.Image.MIRROR_Z, box);
        assertTrue(draw.boxes.contains(image), "the copy's source box is outlined: " + draw.boxes);
        // Turning the mode off drops the copies again.
        symmetry(Symmetry.Mode.OFF);
        frame(WorldCursor.miss(0, 0, 0));
        assertFalse(tool.copiesOutlineOnly());
        assertEquals(1, lastGhosts().size());
    }

    // ---- Paste into ----

    private void into(PasteOptions.Into into) {
        view.updateSettings(view.settings().with(tool.settings().into(), into));
    }

    /** The setting is Everything by default, sits with the paste options, and reaches a paste's, move's and stack's op. */
    @Test
    void pasteIntoReachesThePasteMoveAndStackOps() {
        assertEquals(PasteOptions.Into.EVERYTHING, view.settings().get(tool.settings().into()));
        assertEquals(List.of(tool.settings().includeAir(), tool.settings().into(), tool.settings().physics(),
                tool.settings().entities()), tool.settings().schema().sections().get(0).settings());
        into(PasteOptions.Into.EXISTING);
        ClipboardCache.Entry entry = clipboardWithPreview(new BlockPos(3, 1, 3));
        tool.request(new PlaceTool.Request.Paste(new SourceRef.Clipboard(entry.clipboardId()), "c", Transform.IDENTITY));
        frameAt(0, 63, 0);
        action(EditorAction.COMMIT, 0);
        assertEquals(new PasteOptions(false, false, true, PasteOptions.Into.EXISTING),
                assertInstanceOf(OpSpec.Paste.class, lastOp()).o());
        action(EditorAction.CANCEL, 0);

        Box box = new Box(new BlockPos(0, 64, 0), new BlockPos(1, 64, 1));
        view.setSelection(box);
        tool.request(new PlaceTool.Request.Move(box));
        frame(WorldCursor.miss(0, 0, 0));
        action(EditorAction.NUDGE_UP, 0);
        action(EditorAction.COMMIT, 0);
        OpSpec.Move move = assertInstanceOf(OpSpec.Move.class, lastOp());
        assertEquals(PasteOptions.Into.EXISTING, move.into());
        assertEquals(new OpSpec.Move(new Region.Cuboid(box), new BlockPos(0, 1, 0), Transform.IDENTITY, move.leave(),
                EntityFilter.DECORATIONS, Symmetry.NONE, PasteOptions.Into.EXISTING), move);

        into(PasteOptions.Into.AIR);
        tool.request(new PlaceTool.Request.Stack(box));
        frame(WorldCursor.miss(0, 0, 0));
        action(EditorAction.COMMIT, 0);
        assertEquals(new OpSpec.Stack(new Region.Cuboid(box), 0, 0, 2, 1, EntityFilter.DECORATIONS, Symmetry.NONE,
                PasteOptions.Into.AIR), lastOp());
    }

    /**
     * The ghost shows only the cells the setting would write, judged by the client world where the cells land:
     * with Only existing blocks the cell over stone stays and the cell over air goes, with Only air the other way
     * round; over a chunk the client has not loaded the ghost stays whole; Everything shows the source itself and
     * releases the filtered volumes.
     */
    @Test
    void theGhostIsCutDownToWhatPasteIntoWouldWrite() {
        ClipboardCache.Entry entry = clipboardWithPreview(new BlockPos(3, 1, 3));
        int stair = states.state("minecraft:oak_stairs[facing=north]");
        int stone = states.state("minecraft:stone");
        // The ghost's cells at local (0, 0, 0) and (2, 0, 0) land on (9, 64, 9) and (11, 64, 9).
        world.set(9, 64, 9, stone);
        tool.request(new PlaceTool.Request.Paste(new SourceRef.Clipboard(entry.clipboardId()), "c", Transform.IDENTITY));
        GhostVolume source = tool.sourceVolume().orElseThrow();

        into(PasteOptions.Into.EXISTING);
        frameAt(10, 63, 10);
        assertEquals(new BlockPos(9, 64, 9), tool.placement().orElseThrow().targetMin());
        GhostPlacement ghost = lastGhosts().get(0);
        assertNotSame(source, ghost.volume(), "a cut-down copy");
        assertEquals(stair, ghost.volume().handle(0, 0, 0), "over stone: kept");
        assertEquals(-1, ghost.volume().handle(2, 0, 0), "over air: left out");
        assertEquals(1, ghost.volume().blockCount());
        assertEquals(source.frame(), ghost.volume().frame(), "the same frame, so it lands where the source would");
        assertEquals(new BlockPos(9, 64, 9), new BlockPos(ghost.originX(), ghost.originY(), ghost.originZ()));
        assertFalse(tool.filteringGhosts(), "a small ghost is judged within the frame");
        GhostVolume existing = ghost.volume();
        frameAt(10, 63, 10);
        assertSame(existing, lastGhosts().get(0).volume(), "kept while the placement is the same");

        into(PasteOptions.Into.AIR);
        frameAt(10, 63, 10);
        ghost = lastGhosts().get(0);
        assertEquals(-1, ghost.volume().handle(0, 0, 0), "over stone: left out");
        assertEquals(stone, ghost.volume().handle(2, 0, 0), "over air: kept");
        assertTrue(released.contains(existing), "the old filtered volume is released");

        // Moved so that the cells land in an unloaded chunk: the ghost stays whole there.
        world.setLoaded(2, 2, false);
        frameAt(42, 63, 42);
        ghost = lastGhosts().get(0);
        assertSame(source, ghost.volume(), "nothing dropped: the source itself");
        assertEquals(new BlockPos(41, 64, 41), new BlockPos(ghost.originX(), ghost.originY(), ghost.originZ()));

        into(PasteOptions.Into.EVERYTHING);
        frameAt(10, 63, 10);
        assertSame(source, lastGhosts().get(0).volume());
        assertEquals(2, released.size(), "both filtered volumes were released");
    }

    /** A stack's copies are each cut down where they land, one copy per frame at most. */
    @Test
    void stackCopiesAreEachCutDownWhereTheyLand() {
        Box box = new Box(new BlockPos(0, 64, 0), new BlockPos(1, 64, 1));
        int stone = states.state("minecraft:stone");
        world.fill(box, stone);
        // Copies land 2 south of each other: the first over stone at (0, 64, 2) only, the second over nothing.
        world.set(0, 64, 2, stone);
        into(PasteOptions.Into.EXISTING);
        tool.request(new PlaceTool.Request.Stack(box));
        frame(WorldCursor.miss(0, 0, 0));
        tool.onScroll(view, new ScrollEvent(1, Modifiers.CONTROL));
        assertEquals(2, tool.placement().orElseThrow().count());
        int frames = 0;
        do {
            frame(WorldCursor.miss(0, 0, 0));
        } while (tool.filteringGhosts() && frames++ < 10);
        assertEquals(2, lastGhosts().size());
        GhostVolume first = lastGhosts().get(0).volume();
        assertEquals(1, first.blockCount(), "one cell of the first copy lands on stone");
        assertEquals(stone, first.handle(0, 0, 0));
        assertEquals(0, lastGhosts().get(1).volume().blockCount(), "the second copy lands on air only");
    }

    private static final class RecordingDraw implements WorldDraw {
        record Segment(double x1, double y1, double z1, double x2, double y2, double z2) {}

        int lines;
        final List<Box> boxes = new ArrayList<>();
        final List<Segment> segments = new ArrayList<>();

        @Override
        public void boxOutline(Box box, int argb) {
            boxes.add(box);
        }

        @Override
        public void boxFill(Box box, int argb) {}

        @Override
        public void line(double x1, double y1, double z1, double x2, double y2, double z2, int argb) {
            lines++;
            segments.add(new Segment(x1, y1, z1, x2, y2, z2));
        }

        @Override
        public void ring(double centerX, double y, double centerZ, double radius, int argb) {
            lines++;
        }

        @Override
        public void seeThrough(boolean enabled) {}
    }
}
