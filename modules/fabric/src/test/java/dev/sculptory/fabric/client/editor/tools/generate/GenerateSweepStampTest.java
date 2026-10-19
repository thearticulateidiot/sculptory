package dev.sculptory.fabric.client.editor.tools.generate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.generate.GeneratedSource;
import dev.sculptory.core.generate.SweepKernel;
import dev.sculptory.core.path.PathKind;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.core.transform.Orientation;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.core.world.WorldReader;
import dev.sculptory.fabric.client.editor.EditorBackend;
import dev.sculptory.fabric.client.editor.EditorContext;
import dev.sculptory.fabric.client.editor.input.EditorKeymap;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.mock.MockEditorSession;
import dev.sculptory.fabric.client.editor.render.ghost.GhostPlacement;
import dev.sculptory.fabric.client.editor.render.ghost.GhostVolume;
import dev.sculptory.fabric.client.editor.settings.SettingDef;
import dev.sculptory.fabric.client.editor.tool.EditorAction;
import dev.sculptory.fabric.client.editor.tool.FrameInfo;
import dev.sculptory.fabric.client.editor.tool.PointerEvent;
import dev.sculptory.fabric.client.editor.tool.ToolContext;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tool.WorldCursor;
import dev.sculptory.fabric.client.editor.tools.place.GhostBaker;
import dev.sculptory.fabric.client.editor.world.Ray;
import dev.sculptory.fabric.client.session.ClipboardCache;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.Notice;
import dev.sculptory.fabric.client.session.Permissions;
import dev.sculptory.fabric.client.session.Reply;
import dev.sculptory.fabric.client.session.ToolAction;
import dev.sculptory.protocol.v2.Limits;
import dev.sculptory.protocol.v2.OpLabel;
import dev.sculptory.server.engine.Perm;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;

/**
 * Generate's Sweep and Stamp: the selection swept along clicked points as one "Sweep" paste, and the clipboard or a
 * Library asset stamped on clicked blocks as one "Stamp" multi-paste; points, ghosts, R, refusals.
 */
class GenerateSweepStampTest {
    private static final long MS = 1_000_000L;
    private static final int GROUND = 63;
    private static final String HASH = "ab".repeat(32);

    private final FakeStateSpace states = new FakeStateSpace();
    private final int stone = states.state("minecraft:stone");

    private static WorldCursor on(int x, int y, int z) {
        return new WorldCursor(new BlockPos(x, y, z), WorldCursor.Face.UP, x + 0.5, y + 1, z + 0.5, false);
    }

    // ---------------------------------------------------------------- sweep

    /** A wall slice at x = 30: z -1..1, y 64..65, selected. */
    private Box profile(Rig rig) {
        Box box = new Box(new BlockPos(30, 64, -1), new BlockPos(30, 65, 1));
        rig.world.fill(box, stone);
        rig.view.setSelection(box);
        return box;
    }

    @Test
    void extendToGroundStandsASweepThroughTheAirOnTheTerrain() {
        Rig rig = new Rig(GenerateSettings.Kind.SWEEP);
        profile(rig);
        rig.click(on(0, GROUND + 6, 0));
        rig.click(on(8, GROUND + 6, 0));
        rig.frame(on(8, GROUND + 6, 0));
        GeneratedSource alone = rig.tool.sweep().preview().orElseThrow().source();
        rig.set(GenerateSettings.SWEEP_EXTEND_TO_GROUND, true);
        rig.frame(on(8, GROUND + 6, 0));
        GeneratedSource extended = rig.tool.sweep().preview().orElseThrow().source();
        assertTrue(extended.cells() > alone.cells());
        alone.forEach((x, y, z, state) -> {
            assertEquals(state, extended.get(x, y, z), "the sweep's own cells stay");
            if (alone.contains(x, y - 1, z)) return;
            for (int below = GROUND + 1; below < y; below++) {
                assertEquals(state, extended.get(x, below, z), "carried down to the ground at " + x + "," + below + "," + z);
            }
        });
    }

    @Test
    void sweepTheSelectionAlongThePointsAndEnterBuildsOneSweepStep() {
        Rig rig = new Rig(GenerateSettings.Kind.SWEEP);
        Box box = profile(rig);
        rig.click(on(0, GROUND, 0));
        rig.click(on(8, GROUND, 0));
        rig.frame(on(8, GROUND, 0));
        LineDraft.Built built = rig.tool.sweep().preview().orElseThrow();
        int[] xs = new int[6], ys = new int[6], zs = new int[6], s = new int[6];
        int i = 0;
        for (int y = 64; y <= 65; y++) {
            for (int z = -1; z <= 1; z++) {
                xs[i] = 30;
                ys[i] = y;
                zs[i] = z;
                s[i++] = stone;
            }
        }
        GeneratedSource expected = SweepKernel.generate(new SweepKernel.Spec(new SweepKernel.Profile(xs, ys, zs, s),
                List.of(new BlockPos(0, GROUND, 0), new BlockPos(8, GROUND, 0)), PathKind.CURVE, 0, false), states,
                1 << 20, -64, 320).source();
        assertEquals(9 * 6, expected.cells());
        assertEquals(expected.cells(), built.cells());
        assertNotNull(built.ghost());
        assertEquals(1, rig.services.shown.size());
        assertTrue(rig.tool.onAction(rig.view, EditorAction.COMMIT));
        rig.frame(on(8, GROUND, 0));
        Box bounds = new Box(new BlockPos(0, 64, -1), new BlockPos(8, 65, 1));
        assertEquals(List.of(new MockEditorSession.Call("generate", bounds)), rig.mock.calls());
        ToolAction.RunOp run = assertInstanceOf(ToolAction.RunOp.class, rig.mock.sent().get(0));
        assertEquals(OpLabel.SWEEP, run.label());
        assertInstanceOf(OpSpec.Paste.class, run.op());
        assertEquals(List.of(GenerateTool.SENT_SWEEP), rig.noticeKeys());
        rig.mock.finishJobs();
        assertEquals("Sweep", rig.mock.history().undoLabel());
        // Another selection: the preview follows it.
        rig.view.setSelection(new Box(box.min(), box.max().offset(0, -1, 0)));
        rig.frame(on(8, GROUND, 0));
        assertEquals(9 * 3, rig.tool.sweep().preview().orElseThrow().cells());
    }

    @Test
    void sweepRefusesWithoutASelectionOrWithOneTooLarge() {
        Rig none = new Rig(GenerateSettings.Kind.SWEEP);
        none.click(on(0, GROUND, 0));
        none.click(on(8, GROUND, 0));
        none.frame(on(8, GROUND, 0));
        assertTrue(none.tool.sweep().preview().isEmpty());
        assertEquals(List.of(GenerateTool.SWEEP_NEEDS_SELECTION), none.noticeKeys());

        Rig large = new Rig(GenerateSettings.Kind.SWEEP);
        Box vast = new Box(new BlockPos(-40, 50, -40), new BlockPos(40, GROUND, 40));
        large.view.setSelection(vast);
        large.click(on(0, GROUND, 0));
        large.click(on(8, GROUND, 0));
        large.frame(on(8, GROUND, 0));
        assertEquals(List.of(GenerateTool.SWEEP_TOO_LARGE), large.noticeKeys());

        Rig chest = new Rig(GenerateSettings.Kind.SWEEP);
        profile(chest);
        chest.world.set(30, 65, 0, states.state("minecraft:chest[facing=north]"));
        chest.click(on(0, GROUND, 0));
        chest.click(on(8, GROUND, 0));
        chest.frame(on(8, GROUND, 0));
        assertEquals(List.of(GenerateTool.SWEEP_LEFT_OUT), chest.noticeKeys(),
                "told once, though the preview was made for each point");
        assertEquals(9 * 5, chest.tool.sweep().preview().orElseThrow().cells());
        for (Rig rig : List.of(none, large)) {
            assertTrue(rig.tool.onAction(rig.view, EditorAction.COMMIT));
            assertTrue(rig.mock.calls().isEmpty(), "nothing was uploaded");
        }
    }

    // ---------------------------------------------------------------- stamp

    /** A clipboard of three stone blocks in a row along x, anchored at its west end, with its preview. */
    private ClipboardCache.Entry clipboard(Rig rig) {
        Box box = new Box(new BlockPos(0, 64, 0), new BlockPos(2, 64, 0));
        ClipboardCache.Entry entry = ((Reply.Ok<ClipboardCache.Entry>) rig.mock.copy(new Region.Cuboid(box), box.min(), false,
                EntityFilter.NONE).toCompletableFuture().join()).value();
        rig.mock.putPreview(new SourceRef.Clipboard(entry.clipboardId()), preview(entry.dims(), entry.anchor()));
        return entry;
    }

    private ClipboardCache.Preview preview(BlockPos dims, BlockPos anchor) {
        GeneratedSource.Builder blocks = GeneratedSource.builder(10);
        for (int x = 0; x < dims.x(); x++) blocks.set(x, 0, 0, stone);
        GhostVolume volume = GhostBaker.fromSparse(blocks.build(), states);
        return new ClipboardCache.Preview("stamp", dims, anchor, dims.x(), volume, 100, 0);
    }

    @Test
    void stampCopiesTheClipboardOnEachPointTurnedAlongThePathAsOneStampStep() {
        Rig rig = new Rig(GenerateSettings.Kind.STAMP);
        ClipboardCache.Entry entry = clipboard(rig);
        rig.click(on(0, GROUND, 0));
        rig.click(on(10, GROUND, 0));
        rig.click(on(10, GROUND, 20));
        rig.frame(on(10, GROUND, 20));
        assertEquals(3, rig.services.shown.size(), "a ghost per copy");
        OpSpec.PasteMany op = rig.tool.stamp().op(rig.view).orElseThrow();
        assertEquals(new SourceRef.Clipboard(entry.clipboardId()), op.src());
        // The first copy as copied, its bottom middle on the clicked block's top; the last turned to head south.
        assertEquals(new OpSpec.PasteMany.Placement(new BlockPos(-1, 64, 0), Orientation.IDENTITY), op.placements().get(0));
        assertEquals(new OpSpec.PasteMany.Placement(new BlockPos(10, 64, 19), Orientation.of(Transform.rotation(1))),
                op.placements().get(2));
        assertTrue(rig.tool.onAction(rig.view, EditorAction.ROTATE_CW), "R turns every copy");
        assertEquals(Orientation.of(Transform.rotation(1)), rig.tool.stamp().op(rig.view).orElseThrow().placements()
                .get(0).orientation());
        assertTrue(rig.tool.onAction(rig.view, EditorAction.ROTATE_CCW));
        assertTrue(rig.tool.onAction(rig.view, EditorAction.COMMIT));
        ToolAction.RunOp run = assertInstanceOf(ToolAction.RunOp.class, rig.mock.sent().get(0));
        assertEquals(OpLabel.STAMP, run.label());
        OpSpec.PasteMany sent = assertInstanceOf(OpSpec.PasteMany.class, run.op());
        assertEquals(3, sent.placements().size());
        assertEquals(new PasteOptions(false, false, true), sent.o(), "entities come along");
        assertEquals(List.of(StampDraft.SENT), rig.noticeKeys());
        assertEquals(List.of("3", "9"), rig.notices.get(0).args());
        assertTrue(rig.mock.calls().stream().noneMatch(call -> call.kind().equals("generate")),
                "no upload: the server pastes its own copy");
        rig.mock.finishJobs();
        assertEquals("Stamp", rig.mock.history().undoLabel(), "one undo step, named Stamp");
        assertTrue(rig.tool.onAction(rig.view, EditorAction.CANCEL), "Esc deselects the last point");
        assertTrue(rig.tool.onAction(rig.view, EditorAction.CANCEL), "then clears them");
        assertTrue(rig.tool.stamp().points().isEmpty());
    }

    @Test
    void aCopyTurnsByThePathsOwnAngleClockwiseSeenFromAbove() {
        Rig rig = new Rig(GenerateSettings.Kind.STAMP);
        clipboard(rig);
        rig.click(on(0, GROUND, 0));
        rig.click(on(5, GROUND, 0));
        rig.click(on(10, GROUND, 20));
        // The middle copy heads from (0, 0) to (10, 20): 63.43 degrees clockwise of east, the first heading.
        assertEquals(Orientation.free(Transform.IDENTITY, 6_343, 0, 0), rig.tool.stamp().orientation(1));
        assertEquals(Orientation.IDENTITY, rig.tool.stamp().orientation(0));
        assertTrue(rig.tool.onAction(rig.view, EditorAction.ROTATE_CW));
        assertEquals(Orientation.free(Transform.rotation(1), 6_343, 0, 0), rig.tool.stamp().orientation(1),
                "R adds a quarter turn");
    }

    @Test
    void aClickOnTheStampPathInsertsACopyOnTheBlockUnderIt() {
        Rig rig = new Rig(GenerateSettings.Kind.STAMP);
        clipboard(rig);
        rig.click(on(0, GROUND, 0));
        rig.click(on(10, GROUND, 0));
        rig.tool.onAction(rig.view, EditorAction.CANCEL); // deselect: no gizmo in the way
        rig.services.projector = (x, y, z, out) -> {
            out[0] = 20 * x + 8 * z;
            out[1] = -20 * y + 8 * z;
            return true;
        };
        rig.services.eye = new double[] {5.5, GROUND + 20.5, 20.5};
        // The path is drawn over the blocks' tops (y GROUND + 1): aim at it there, a little past halfway.
        double sx = 20 * 6.5 + 8 * 0.5, sy = -20 * (GROUND + 1) + 8 * 0.5;
        WorldCursor far = on(6, GROUND - 6, 3);
        rig.tool.frame(rig.view, new FrameInfo(rig.now += 16 * MS, 0f, sx, sy, far));
        rig.tool.onPointer(rig.view, new PointerEvent(PointerEvent.Kind.PRESS, PointerEvent.LEFT, sx, sy, 0, far));
        assertEquals(List.of(new BlockPos(0, GROUND, 0), new BlockPos(6, GROUND, 0), new BlockPos(10, GROUND, 0)),
                rig.tool.stamp().points().nodes(), "on the block, as a clicked copy is, not a block over it");
    }

    @Test
    void draggingAPointNeverGrowsTheBakesHeld() {
        Rig rig = new Rig(GenerateSettings.Kind.STAMP);
        clipboard(rig);
        int[][] curve = {{0, 0}, {10, 0}, {15, 8}, {20, 20}, {30, 25}};
        for (int[] p : curve) rig.click(on(p[0], GROUND, p[1]));
        rig.frame(on(30, GROUND, 25));
        StampDraft stamp = rig.tool.stamp();
        int held = stamp.liveBakes();
        assertTrue(held > 0 && held < curve.length, "a bake per turned copy: " + held);
        // Grabbed from straight above, 17 blocks up, and dragged about at that distance.
        rig.services.ray = new Ray(0.5, GROUND + 17.5, 0.5, 0, -1, 0);
        rig.press(on(0, GROUND, 0));
        for (int k = 1; k <= 12; k++) {
            rig.services.ray = new Ray(0.5, GROUND + 17.5, 0.5, -k, -17, k % 4);
            rig.drag(on(-k, GROUND, k % 4));
            rig.frame(on(-k, GROUND, k % 4));
            assertTrue(stamp.liveBakes() <= held, "move " + k + ": " + stamp.liveBakes() + " bakes held");
            assertEquals(curve.length, rig.services.shown.size(), "every copy still shows");
        }
        rig.release(on(-12, GROUND, 0));
        rig.frame(on(-12, GROUND, 0));
        assertTrue(stamp.liveBakes() < curve.length, "once it rests, only the turns the copies have: " + stamp.liveBakes());
        assertTrue(rig.services.released > 0, "bakes no copy uses are released");
    }

    @Test
    void stampTakesALibraryAssetPickedInTheLibrary() {
        Rig rig = new Rig(GenerateSettings.Kind.STAMP);
        rig.set(GenerateSettings.STAMP_SOURCE, GenerateSettings.StampSource.LIBRARY);
        rig.click(on(0, GROUND, 0));
        rig.frame(on(0, GROUND, 0));
        assertTrue(rig.tool.onAction(rig.view, EditorAction.COMMIT));
        assertEquals(List.of(StampDraft.NO_ASSET), rig.noticeKeys());
        SourceRef asset = new SourceRef.Asset(HASH);
        rig.mock.putPreview(asset, preview(new BlockPos(5, 1, 1), BlockPos.ORIGIN));
        rig.tool.useStampAsset("builds/lamp.schem", HASH);
        rig.frame(on(0, GROUND, 0));
        assertEquals(1, rig.services.shown.size());
        assertTrue(rig.tool.onAction(rig.view, EditorAction.COMMIT));
        OpSpec.PasteMany sent = assertInstanceOf(OpSpec.PasteMany.class,
                assertInstanceOf(ToolAction.RunOp.class, rig.mock.sent().get(0)).op());
        assertEquals(asset, sent.src());
        assertEquals(new BlockPos(-2, 64, 0), sent.placements().get(0).origin());
    }

    @Test
    void aStampOfAnAssetTheServerLetGoOfReloadsItAndAFailedPreviewIsAskedForAgain() {
        Rig rig = new Rig(GenerateSettings.Kind.STAMP);
        rig.set(GenerateSettings.STAMP_SOURCE, GenerateSettings.StampSource.LIBRARY);
        SourceRef asset = new SourceRef.Asset(HASH);
        rig.mock.putPreview(asset, preview(new BlockPos(5, 1, 1), BlockPos.ORIGIN));
        rig.tool.useStampAsset("builds/lamp.schem", HASH);
        rig.click(on(0, GROUND, 0));
        rig.frame(on(0, GROUND, 0));
        rig.mock.rejectNextOp(dev.sculptory.protocol.v2.RejectReason.ASSET_NOT_LOADED);
        assertTrue(rig.tool.onAction(rig.view, EditorAction.COMMIT));
        assertEquals(List.of(StampDraft.RELOADING), rig.noticeKeys(), "reloads instead of refusing");
        long previews = rig.mock.calls().stream().filter(call -> call.kind().equals("preview")).count();
        assertEquals(2, previews, "asked for the preview again");
        // That preview failed (the mock had forgotten it): nothing waits on it; Enter asks again.
        rig.frame(on(0, GROUND, 0));
        assertTrue(rig.tool.stamp().hints(rig.view, "LMB").contains(
                dev.sculptory.fabric.client.editor.tool.KeyHint.text(StampDraft.PREVIEW_FAILED)));
        rig.mock.putPreview(asset, preview(new BlockPos(5, 1, 1), BlockPos.ORIGIN));
        assertTrue(rig.tool.onAction(rig.view, EditorAction.COMMIT));
        rig.frame(on(0, GROUND, 0));
        assertTrue(rig.tool.onAction(rig.view, EditorAction.COMMIT));
        assertEquals(2, rig.mock.sent().size(), "the second stamp went out");
        assertEquals(List.of(StampDraft.RELOADING, StampDraft.RELOADING, StampDraft.SENT), rig.noticeKeys());
    }

    @Test
    void deleteWithNoPointLeftNeverErasesTheProfile() {
        for (GenerateSettings.Kind kind : List.of(GenerateSettings.Kind.SWEEP, GenerateSettings.Kind.STAMP)) {
            Rig rig = new Rig(kind);
            profile(rig);
            assertTrue(rig.tool.onAction(rig.view, EditorAction.REMOVE_NODE), kind + ": Backspace is taken");
            assertTrue(rig.tool.onAction(rig.view, EditorAction.ERASE_SELECTION), kind + ": Delete is taken");
            assertTrue(rig.view.selection().isPresent());
        }
    }

    @Test
    void stampRefusalsSayWhyAndSendNothing() {
        Rig empty = new Rig(GenerateSettings.Kind.STAMP);
        empty.click(on(0, GROUND, 0));
        assertTrue(empty.tool.onAction(empty.view, EditorAction.COMMIT));
        assertEquals(List.of(StampDraft.NO_CLIPBOARD), empty.noticeKeys());

        Rig few = new Rig(GenerateSettings.Kind.STAMP);
        clipboard(few);
        assertTrue(few.tool.onAction(few.view, EditorAction.COMMIT));
        assertEquals(List.of(StampDraft.NEEDS_POINT), few.noticeKeys());

        Rig many = new Rig(GenerateSettings.Kind.STAMP);
        clipboard(many);
        for (int i = 0; i < OpSpec.PasteMany.MAX_PLACEMENTS; i++) many.click(on(-40 + i % 64, GROUND, -40 + i / 64));
        assertTrue(many.noticeKeys().isEmpty());
        many.click(on(30, GROUND, 30));
        assertEquals(List.of(StampDraft.TOO_MANY), many.noticeKeys());
        assertEquals(OpSpec.PasteMany.MAX_PLACEMENTS, many.tool.stamp().points().size());

        Rig small = new Rig(GenerateSettings.Kind.STAMP);
        small.mock.setPermissions(new Permissions(Perm.mask(EnumSet.of(Perm.USE, Perm.CLIPBOARD, Perm.REGION)),
                new Limits(5, 2_097_152L, 32, 20, 32L << 20, 2)));
        clipboard(small);
        small.click(on(0, GROUND, 0));
        small.click(on(6, GROUND, 0));
        assertTrue(small.tool.onAction(small.view, EditorAction.COMMIT));
        assertEquals(List.of("sculptory.notice.too_large"), small.noticeKeys());

        Rig noClipboardNode = new Rig(GenerateSettings.Kind.STAMP);
        clipboard(noClipboardNode);
        noClipboardNode.mock.setPermissions(new Permissions(Perm.mask(EnumSet.of(Perm.USE, Perm.REGION)), Limits.DEFAULTS));
        noClipboardNode.click(on(0, GROUND, 0));
        assertTrue(noClipboardNode.tool.onAction(noClipboardNode.view, EditorAction.COMMIT));
        assertEquals(List.of("sculptory.notice.needs_permission"), noClipboardNode.noticeKeys());
        assertEquals(List.of(Perm.CLIPBOARD.node()), noClipboardNode.notices.get(0).args());

        for (Rig rig : List.of(empty, few, many, small, noClipboardNode)) {
            assertTrue(rig.mock.sent().isEmpty(), "nothing was sent");
        }
    }

    @Test
    void theEnglishTextCoversSweepAndStamp() throws IOException {
        JsonObject lang;
        try (InputStream in = getClass().getResourceAsStream("/assets/sculptory/lang/en_us.json")) {
            assertNotNull(in);
            lang = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        List<String> keys = new ArrayList<>(List.of(GenerateTool.OP_SWEEP, GenerateTool.SENT_SWEEP,
                GenerateTool.SWEEP_NEEDS_SELECTION, GenerateTool.SWEEP_TOO_LARGE, GenerateTool.SWEEP_UNLOADED,
                GenerateTool.SWEEP_EMPTY, GenerateTool.SWEEP_LEFT_OUT, StampDraft.NEEDS_POINT, StampDraft.NO_CLIPBOARD,
                StampDraft.NO_ASSET, StampDraft.TOO_MANY, StampDraft.WAITING, StampDraft.SENT, StampDraft.OP,
                StampDraft.PREVIEW_FAILED, StampDraft.RELOADING,
                "sculptory.hint.sweep.needs_selection", "sculptory.hint.sweep.profile", "sculptory.hint.sweep.build",
                "sculptory.hint.stamp.source", "sculptory.hint.stamp.add", "sculptory.hint.stamp.add_or_select",
                "sculptory.hint.stamp.turn", "sculptory.hint.stamp.build", "sculptory.library.stamp",
                "sculptory.library.stamp.tooltip", "sculptory.setting.generate.sweep_section",
                "sculptory.setting.generate.stamp_section"));
        for (SettingDef<?> def : List.<SettingDef<?>>of(GenerateSettings.SWEEP_PATH, GenerateSettings.SWEEP_SAG,
                GenerateSettings.SWEEP_FOLLOW_SLOPE, GenerateSettings.STAMP_SOURCE)) {
            keys.add(def.labelKey());
            keys.add(def.labelKey() + ".tooltip");
        }
        for (GenerateSettings.StampSource source : GenerateSettings.StampSource.values()) {
            keys.add(GenerateSettings.STAMP_SOURCE.labelKey() + "." + source.name().toLowerCase(java.util.Locale.ROOT));
        }
        for (String key : keys) assertTrue(lang.has(key), key);
        assertFalse(lang.get("sculptory.setting.generate.kind.tooltip").getAsString().isEmpty());
    }

    // ---------------------------------------------------------------- fixtures

    private final class Rig {
        final FakeWorld world = flatWorld();
        final MockEditorSession mock = new MockEditorSession();
        final List<Notice> notices = new ArrayList<>();
        final FakeServices services = new FakeServices();
        final EditorContext ctx;
        final GenerateTool tool;
        final ToolContext view;
        long now = 1_000 * MS;

        Rig(GenerateSettings.Kind kind) {
            EditorBackend backend = new EditorBackend() {
                @Override
                public Optional<EditorSession> session() {
                    return Optional.of(mock);
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
            ctx = new EditorContext(() -> backend, notices::add);
            tool = new GenerateTool(services);
            ctx.tools().register(tool);
            view = ctx.contextFor(ToolId.GENERATE);
            assertTrue(ctx.tools().activate(ToolId.GENERATE, view));
            set(GenerateSettings.KIND, kind);
        }

        void frame(WorldCursor cursor) {
            now += 16 * MS;
            tool.frame(view, new FrameInfo(now, 0f, 0, 0, cursor));
        }

        void click(WorldCursor cursor) {
            press(cursor);
            release(cursor);
        }

        void press(WorldCursor cursor) {
            tool.onPointer(view, new PointerEvent(PointerEvent.Kind.PRESS, PointerEvent.LEFT, 0, 0, 0, cursor));
        }

        void drag(WorldCursor cursor) {
            tool.onPointer(view, new PointerEvent(PointerEvent.Kind.DRAG, PointerEvent.LEFT, 0, 0, 0, cursor));
        }

        void release(WorldCursor cursor) {
            tool.onPointer(view, new PointerEvent(PointerEvent.Kind.RELEASE, PointerEvent.LEFT, 0, 0, 0, cursor));
        }

        <T> void set(SettingDef<T> def, T value) {
            ctx.updateSettings(ToolId.GENERATE, ctx.settings(ToolId.GENERATE).with(def, value));
        }

        List<String> noticeKeys() {
            return notices.stream().map(Notice::key).toList();
        }
    }

    /** Grass on stone at {@link #GROUND}, x and z within ±48. */
    private FakeWorld flatWorld() {
        FakeWorld world = new FakeWorld(states);
        int grass = states.state("minecraft:grass_block");
        for (int x = -48; x <= 48; x++) {
            for (int z = -48; z <= 48; z++) {
                for (int y = 50; y <= GROUND; y++) world.set(x, y, z, y == GROUND ? grass : stone);
            }
        }
        return world;
    }

    private static final class FakeServices implements GenerateTool.Services {
        final EditorKeymap keymap = EditorKeymap.defaults();
        List<GhostPlacement> shown = List.of();
        int released;
        Ray ray;
        dev.sculptory.fabric.client.editor.world.ScreenProjector projector;
        double[] eye;

        @Override
        public Optional<Ray> cursorRay() {
            return Optional.ofNullable(ray);
        }

        @Override
        public Optional<dev.sculptory.fabric.client.editor.world.ScreenProjector> projector() {
            return Optional.ofNullable(projector);
        }

        @Override
        public Optional<double[]> eye() {
            return Optional.ofNullable(eye);
        }

        @Override
        public void showGhosts(List<GhostPlacement> placements) {
            shown = List.copyOf(placements);
        }

        @Override
        public void releaseGhost(GhostVolume volume) {
            released++;
        }

        @Override
        public Executor background() {
            return Runnable::run;
        }

        @Override
        public String keyLabel(KeyAction action) {
            return keymap.display(action);
        }

        @Override
        public void confirm(String opNameKey, long blocks, Runnable onConfirm) {
            onConfirm.run();
        }
    }
}
