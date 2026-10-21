package dev.sculptory.mc.client.editor.tools.extrude;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.generate.GeneratedSource;
import dev.sculptory.core.generate.PathExtrudeKernel;
import dev.sculptory.core.generate.TaperKernel;
import dev.sculptory.core.path.PathKind;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.core.world.WorldReader;
import dev.sculptory.mc.client.editor.EditorBackend;
import dev.sculptory.mc.client.editor.EditorContext;
import dev.sculptory.mc.client.editor.Translator;
import dev.sculptory.mc.client.editor.input.EditorKeymap;
import dev.sculptory.mc.client.editor.input.KeyAction;
import dev.sculptory.mc.client.editor.mock.MockEditorSession;
import dev.sculptory.mc.client.editor.render.ghost.GhostPlacement;
import dev.sculptory.mc.client.editor.render.ghost.GhostVolume;
import dev.sculptory.mc.client.editor.settings.SettingDef;
import dev.sculptory.mc.client.editor.tool.EditorAction;
import dev.sculptory.mc.client.editor.tool.FrameInfo;
import dev.sculptory.mc.client.editor.tool.KeyHint;
import dev.sculptory.mc.client.editor.tool.Modifiers;
import dev.sculptory.mc.client.editor.tool.PointerEvent;
import dev.sculptory.mc.client.editor.tool.ScrollEvent;
import dev.sculptory.mc.client.editor.tool.ToolContext;
import dev.sculptory.mc.client.editor.tool.ToolId;
import dev.sculptory.mc.client.editor.tool.WorldCursor;
import dev.sculptory.mc.client.editor.tools.brush.SymmetryCentre;
import dev.sculptory.mc.client.editor.tools.generate.AirPoints;
import dev.sculptory.mc.client.editor.tools.generate.LineDraft;
import dev.sculptory.mc.client.editor.tools.select.MagicSelect;
import dev.sculptory.mc.client.editor.tools.select.SelectionActions;
import dev.sculptory.mc.client.editor.world.Ray;
import dev.sculptory.mc.client.session.EditorSession;
import dev.sculptory.mc.client.session.Notice;
import dev.sculptory.mc.client.session.Permissions;
import dev.sculptory.mc.client.session.ToolAction;
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
 * Extrude's Along a path and To a point: a click locks the hovered face, clicks shape it, the ghost shows it, Enter
 * builds one "Extrude path" or "Taper" step; Esc clears then lets go; refusals say why and send nothing.
 */
class ExtrudeAlongTest {
    private static final long MS = 1_000_000L;
    /** The stone floor: x and z 0-4 at y 60, air above; a marker block at (2, 66, 2) and one below at (2, 55, 2). */
    private static final Box FLOOR = new Box(new BlockPos(0, 60, 0), new BlockPos(4, 60, 4));

    private final FakeStateSpace states = new FakeStateSpace();
    private final int stone = states.state("minecraft:stone");

    private static WorldCursor top(int x, int z) {
        return on(x, 60, z);
    }

    /** A hit on the top of block (x, y, z). */
    private static WorldCursor on(int x, int y, int z) {
        return new WorldCursor(new BlockPos(x, y, z), WorldCursor.Face.UP, x + 0.5, y + 1, z + 0.5, false);
    }

    @Test
    void alongAPathAClickLocksTheFacePointsMakeTheGhostAndEnterBuildsOneExtrudePathStep() {
        Rig rig = new Rig(ExtrudeSettings.Direction.ALONG_PATH);
        rig.hoverUntilFound(top(2, 2));
        rig.click(top(2, 2));
        ExtrudeTool.Face face = rig.tool.lockedFace().orElseThrow();
        assertEquals(25, face.count());
        assertTrue(rig.tool.hints(rig.view).contains(new KeyHint("Esc", "sculptory.hint.extrude.unlock")));
        rig.click(on(2, 66, 2));
        rig.frame(on(2, 66, 2));
        LineDraft draft = rig.tool.draft(ExtrudeSettings.Direction.ALONG_PATH);
        assertEquals(List.of(new BlockPos(2, 66, 2)), draft.points().nodes());
        LineDraft.Built built = draft.preview().orElseThrow();
        GeneratedSource expected = PathExtrudeKernel.generate(new PathExtrudeKernel.Spec(face.cells(),
                List.of(new BlockPos(2, 66, 2)), PathKind.CURVE, 0, false), states, 1 << 20, -64, 320).source();
        assertEquals(25 * 6, expected.cells(), "six layers straight up to the point");
        assertEquals(expected.cells(), built.cells());
        expected.forEach((x, y, z, state) -> assertEquals(state, built.source().get(x, y, z)));
        assertNotNull(built.ghost());
        assertEquals(1, rig.services.shown.size(), "the ghost is shown");
        assertTrue(rig.tool.onAction(rig.view, EditorAction.COMMIT));
        rig.frame(on(2, 66, 2));
        Box bounds = new Box(new BlockPos(0, 61, 0), new BlockPos(4, 66, 4));
        assertEquals(List.of(new MockEditorSession.Call("generate", bounds)), rig.session.calls());
        ToolAction.RunOp run = assertInstanceOf(ToolAction.RunOp.class, rig.session.sent().get(0));
        assertEquals(OpLabel.PATH_EXTRUDE, run.label());
        OpSpec.Paste paste = assertInstanceOf(OpSpec.Paste.class, run.op());
        assertEquals(bounds.min(), paste.origin());
        assertEquals(new PasteOptions(true, false, false), paste.o());
        assertEquals(List.of(ExtrudeAlong.SENT_PATH), rig.noticeKeys());
        rig.session.finishJobs();
        assertEquals("Extrude path", rig.session.history().undoLabel(), "one undo step, named Extrude path");
    }

    @Test
    void toAPointTheClickedBlockIsTheTipAndEnterBuildsOneTaperStep() {
        Rig rig = new Rig(ExtrudeSettings.Direction.TO_POINT);
        rig.set(ExtrudeSettings.TAPER, TaperKernel.Curve.PINCH);
        rig.hoverUntilFound(top(1, 1));
        rig.click(top(1, 1));
        ExtrudeTool.Face face = rig.tool.lockedFace().orElseThrow();
        rig.click(on(2, 66, 2));
        rig.frame(on(2, 66, 2));
        LineDraft draft = rig.tool.draft(ExtrudeSettings.Direction.TO_POINT);
        GeneratedSource expected = TaperKernel.generate(new TaperKernel.Spec(face.cells(), new BlockPos(2, 66, 2),
                TaperKernel.Curve.PINCH), states, 1 << 20, -64, 320).source();
        assertEquals(expected.cells(), draft.preview().orElseThrow().cells());
        assertTrue(expected.contains(2, 66, 2), "it reaches its tip");
        // A second click moves the tip.
        rig.click(on(3, 66, 3));
        assertEquals(List.of(new BlockPos(3, 66, 3)), draft.points().nodes());
        rig.frame(on(3, 66, 3));
        assertTrue(rig.tool.onAction(rig.view, EditorAction.COMMIT));
        rig.frame(on(3, 66, 3));
        ToolAction.RunOp run = assertInstanceOf(ToolAction.RunOp.class, rig.session.sent().get(0));
        assertEquals(OpLabel.TAPER, run.label());
        rig.session.finishJobs();
        assertEquals("Taper", rig.session.history().undoLabel());
    }

    @Test
    void escClearsThePointsThenLetsGoAndAnotherDirectionDropsTheLock() {
        Rig rig = new Rig(ExtrudeSettings.Direction.ALONG_PATH);
        rig.hoverUntilFound(top(2, 2));
        rig.click(top(2, 2));
        rig.click(on(2, 66, 2));
        rig.frame(on(2, 66, 2));
        assertTrue(rig.tool.onAction(rig.view, EditorAction.REMOVE_NODE), "Backspace removes the point");
        assertTrue(rig.tool.draft(ExtrudeSettings.Direction.ALONG_PATH).points().isEmpty());
        rig.click(on(2, 66, 2));
        rig.frame(on(2, 66, 2));
        assertTrue(rig.tool.onAction(rig.view, EditorAction.CANCEL), "Esc deselects the new point");
        assertEquals(1, rig.tool.draft(ExtrudeSettings.Direction.ALONG_PATH).points().size());
        assertTrue(rig.tool.onAction(rig.view, EditorAction.CANCEL), "Esc clears the points");
        assertTrue(rig.tool.draft(ExtrudeSettings.Direction.ALONG_PATH).points().isEmpty());
        assertTrue(rig.tool.lockedFace().isPresent());
        assertTrue(rig.tool.onAction(rig.view, EditorAction.CANCEL), "Esc again lets go of the face");
        assertTrue(rig.tool.lockedFace().isEmpty());
        rig.frame(top(2, 2));
        assertEquals(List.of(), rig.services.shown);
        assertFalse(rig.tool.onAction(rig.view, EditorAction.CANCEL));

        rig.hoverUntilFound(top(2, 2));
        rig.click(top(2, 2));
        assertTrue(rig.tool.lockedFace().isPresent());
        rig.set(ExtrudeSettings.DIRECTION, ExtrudeSettings.Direction.STRAIGHT);
        assertTrue(rig.tool.lockedFace().isEmpty(), "Straight drops the lock");
        assertTrue(rig.session.sent().isEmpty());
    }

    @Test
    void refusalsSayWhyAndSendNothing() {
        // Nothing to lock: aiming at air.
        Rig air = new Rig(ExtrudeSettings.Direction.ALONG_PATH);
        air.hoverUntilFound(on(20, 60, 20));
        air.click(on(20, 60, 20));
        assertTrue(air.tool.lockedFace().isEmpty());
        assertEquals(List.of(ExtrudeTool.NOTHING), air.noticeKeys());

        // Enter before any point or tip.
        for (ExtrudeSettings.Direction direction : List.of(ExtrudeSettings.Direction.ALONG_PATH,
                ExtrudeSettings.Direction.TO_POINT)) {
            Rig rig = new Rig(direction);
            rig.hoverUntilFound(top(2, 2));
            rig.click(top(2, 2));
            assertTrue(rig.tool.onAction(rig.view, EditorAction.COMMIT));
            assertEquals(List.of(direction == ExtrudeSettings.Direction.TO_POINT ? ExtrudeAlong.NEEDS_TIP
                    : ExtrudeAlong.NEEDS_POINT), rig.noticeKeys());
        }

        // A tip behind the face.
        Rig behind = new Rig(ExtrudeSettings.Direction.TO_POINT);
        behind.hoverUntilFound(top(2, 2));
        behind.click(top(2, 2));
        behind.click(on(2, 55, 2));
        behind.frame(on(2, 55, 2));
        assertTrue(behind.tool.draft(ExtrudeSettings.Direction.TO_POINT).preview().isEmpty());
        assertEquals(List.of(ExtrudeAlong.TIP_BEHIND), behind.noticeKeys());

        // Without the clipboard node (the generated upload needs it).
        Rig noClipboard = new Rig(ExtrudeSettings.Direction.ALONG_PATH);
        noClipboard.session.setPermissions(new Permissions(Perm.mask(EnumSet.of(Perm.USE, Perm.REGION)), Limits.DEFAULTS));
        noClipboard.hoverUntilFound(top(2, 2));
        noClipboard.click(top(2, 2));
        noClipboard.click(on(2, 66, 2));
        noClipboard.frame(on(2, 66, 2));
        assertTrue(noClipboard.tool.onAction(noClipboard.view, EditorAction.COMMIT));
        assertEquals(List.of("sculptory.notice.needs_permission"), noClipboard.noticeKeys());
        assertEquals(List.of(Perm.CLIPBOARD.node()), noClipboard.notices.get(0).args());

        for (Rig rig : List.of(air, behind, noClipboard)) {
            assertTrue(rig.session.calls().isEmpty() && rig.session.sent().isEmpty(), "nothing was sent");
        }
    }

    @Test
    void aClickWhileAFaceIsFoundLocksOnlyThatFace() {
        Rig rig = new Rig(ExtrudeSettings.Direction.ALONG_PATH);
        // A large floor below: finding its face takes several frames.
        rig.world.fill(new Box(new BlockPos(-60, 40, -60), new BlockPos(39, 40, 39)), stone);
        rig.frame(on(-10, 40, -10));
        assertTrue(rig.tool.searching());
        rig.click(on(-10, 40, -10));
        assertTrue(rig.tool.lockedFace().isEmpty(), "still being found");
        // The cursor goes to the sky, then to another face: that face is not the one clicked.
        rig.frame(WorldCursor.miss(0, 0, 0));
        rig.hoverUntilFound(top(2, 2));
        assertTrue(rig.tool.lockedFace().isEmpty(), "a face never clicked is not locked");
        // Waiting on the face clicked locks it once it is found.
        rig.frame(on(-10, 40, -10));
        rig.click(on(-10, 40, -10));
        rig.hoverUntilFound(on(-10, 40, -10));
        assertEquals(ExtrudeSettings.DEFAULT_SIZE, rig.tool.lockedFace().orElseThrow().count(), "cut at Size");
    }

    @Test
    void deleteWithNoPointLeftStaysWithTheLockedFace() {
        Rig rig = new Rig(ExtrudeSettings.Direction.TO_POINT);
        rig.hoverUntilFound(top(2, 2));
        rig.click(top(2, 2));
        assertTrue(rig.tool.onAction(rig.view, EditorAction.REMOVE_NODE), "taken: never the selection's erase");
        assertTrue(rig.tool.onAction(rig.view, EditorAction.ERASE_SELECTION));
        assertTrue(rig.tool.lockedFace().isPresent());
    }

    @Test
    void blocksWithContentsAreLeftOutWithACount() {
        Rig rig = new Rig(ExtrudeSettings.Direction.ALONG_PATH);
        rig.set(ExtrudeSettings.MATCH, MagicSelect.Match.ANY_BLOCK);
        rig.world.set(1, 60, 1, states.state("minecraft:chest[facing=north]"));
        rig.hoverUntilFound(top(2, 2));
        rig.click(top(2, 2));
        assertEquals(25, rig.tool.lockedFace().orElseThrow().count());
        assertEquals(List.of(ExtrudeAlong.LEFT_OUT), rig.noticeKeys());
        assertEquals(List.of("1"), rig.notices.get(0).args());
        rig.click(on(2, 66, 2));
        rig.frame(on(2, 66, 2));
        GeneratedSource built = rig.tool.draft(ExtrudeSettings.Direction.ALONG_PATH).preview().orElseThrow().source();
        assertEquals(24 * 6, built.cells());
        assertFalse(built.contains(1, 63, 1), "the chest's column is left out");
    }

    @Test
    void theEnglishTextCoversTheNewDirections() throws IOException {
        JsonObject lang;
        try (InputStream in = getClass().getResourceAsStream("/assets/sculptory/lang/en_us.json")) {
            assertNotNull(in);
            lang = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        List<String> keys = new ArrayList<>(List.of(ExtrudeAlong.NEEDS_POINT, ExtrudeAlong.NEEDS_TIP,
                ExtrudeAlong.TIP_BEHIND, ExtrudeAlong.LEFT_OUT, ExtrudeAlong.OP_PATH, ExtrudeAlong.SENT_PATH,
                ExtrudeAlong.OP_TAPER, ExtrudeAlong.SENT_TAPER, "sculptory.hint.extrude.lock_for_path",
                "sculptory.hint.extrude.lock_for_tip", "sculptory.hint.extrude.locked", "sculptory.hint.extrude.build_path",
                "sculptory.hint.extrude.click_tip", "sculptory.hint.extrude.move_tip", "sculptory.hint.extrude.build_taper",
                "sculptory.hint.extrude.clear_tip", "sculptory.hint.extrude.unlock"));
        for (SettingDef<?> def : List.<SettingDef<?>>of(ExtrudeSettings.DIRECTION, ExtrudeSettings.PATH, ExtrudeSettings.SAG,
                ExtrudeSettings.FOLLOW_SLOPE, ExtrudeSettings.TAPER)) {
            keys.add(def.labelKey());
            keys.add(def.labelKey() + ".tooltip");
        }
        for (Enum<?> value : List.<Enum<?>>of(ExtrudeSettings.Direction.STRAIGHT, ExtrudeSettings.Direction.ALONG_PATH,
                ExtrudeSettings.Direction.TO_POINT)) {
            keys.add(ExtrudeSettings.DIRECTION.labelKey() + "." + value.name().toLowerCase(java.util.Locale.ROOT));
        }
        for (TaperKernel.Curve curve : TaperKernel.Curve.values()) {
            keys.add(ExtrudeSettings.TAPER.labelKey() + "." + curve.name().toLowerCase(java.util.Locale.ROOT));
        }
        for (String key : keys) assertTrue(lang.has(key), key);
    }

    // ---------------------------------------------------------------- points in the air

    /** The ray met nothing (its end, 512 blocks out). */
    private static WorldCursor sky(Ray ray) {
        return WorldCursor.miss(ray.pointX(512), ray.pointY(512), ray.pointZ(512));
    }

    @Test
    void withTheFaceLockedAClickOnOpenSkyPutsAPathPointAtTheMarker() {
        Rig rig = new Rig(ExtrudeSettings.Direction.ALONG_PATH);
        Ray up = new Ray(2.5, 70.5, 2.5, 0, 1, 0);
        rig.services.ray = up;
        rig.frame(sky(up));
        assertNull(rig.tool.air().marker(), "no marker before a face is locked");
        rig.click(sky(up));
        assertTrue(rig.tool.lockedFace().isEmpty(), "a click on the sky locks nothing");
        rig.services.ray = null;
        rig.hoverUntilFound(top(2, 2));
        rig.click(top(2, 2));
        assertTrue(rig.tool.lockedFace().isPresent());
        rig.services.ray = up;
        rig.frame(sky(up));
        assertEquals(new BlockPos(2, 86, 2), rig.tool.air().marker(), "16 blocks above the camera");
        assertTrue(rig.tool.hints(rig.view).contains(new KeyHint("Alt+Scroll", AirPoints.HINT, List.of("16"))));
        assertTrue(rig.tool.onScroll(rig.view, new ScrollEvent(1, Modifiers.ALT | Modifiers.SHIFT)));
        assertEquals(20, rig.tool.air().distance());
        rig.click(sky(up));
        LineDraft draft = rig.tool.draft(ExtrudeSettings.Direction.ALONG_PATH);
        assertEquals(List.of(new BlockPos(2, 90, 2)), draft.points().nodes());
        rig.frame(sky(up));
        LineDraft.Built built = draft.preview().orElseThrow();
        assertEquals(25 * 30, built.cells(), "the floor carried thirty layers straight up into the sky");
    }

    @Test
    void toAPointTheTipCanFloatInTheAirAndStraightTakesNoScroll() {
        Rig rig = new Rig(ExtrudeSettings.Direction.TO_POINT);
        rig.hoverUntilFound(top(2, 2));
        rig.click(top(2, 2));
        Ray up = new Ray(2.5, 70.5, 2.5, 0, 1, 0);
        rig.services.ray = up;
        rig.frame(sky(up));
        rig.click(sky(up));
        LineDraft draft = rig.tool.draft(ExtrudeSettings.Direction.TO_POINT);
        assertEquals(List.of(new BlockPos(2, 86, 2)), draft.points().nodes(), "the tip at the marker");
        rig.frame(sky(up));
        assertTrue(draft.preview().orElseThrow().source().contains(2, 86, 2), "the taper reaches its tip in the sky");
        rig.set(ExtrudeSettings.DIRECTION, ExtrudeSettings.Direction.STRAIGHT);
        assertFalse(rig.tool.onScroll(rig.view, new ScrollEvent(1, Modifiers.ALT)), "straight extrusions take no scroll");
    }

    // ---------------------------------------------------------------- fixtures

    private final class Rig {
        final FakeWorld world = new FakeWorld(states);
        final MockEditorSession session = new MockEditorSession();
        final List<Notice> notices = new ArrayList<>();
        final FakeServices services = new FakeServices();
        final EditorContext ctx;
        final ExtrudeTool tool;
        final ToolContext view;
        long now = 1_000 * MS;

        Rig(ExtrudeSettings.Direction direction) {
            world.fill(FLOOR, stone);
            world.set(2, 66, 2, stone);
            world.set(3, 66, 3, stone);
            world.set(2, 55, 2, stone);
            EditorBackend backend = new EditorBackend() {
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
            ctx = new EditorContext(() -> backend, notices::add);
            SelectionActions actions = new SelectionActions(() -> ctx.contextFor(ToolId.SELECT), ctx::activeBlock,
                    (message, onConfirm) -> onConfirm.run(), Translator.KEYS, () -> 42L, new SymmetryCentre(),
                    action -> EditorKeymap.defaults().display(action));
            tool = new ExtrudeTool(services, actions);
            ctx.tools().register(tool);
            view = ctx.contextFor(ToolId.EXTRUDE);
            assertTrue(ctx.tools().activate(ToolId.EXTRUDE, view));
            set(ExtrudeSettings.DIRECTION, direction);
        }

        void frame(WorldCursor cursor) {
            now += 16 * MS;
            tool.frame(view, new FrameInfo(now, 0f, 0, 0, cursor));
        }

        void hoverUntilFound(WorldCursor cursor) {
            frame(cursor);
            int frames = 0;
            while (tool.searching()) {
                frame(cursor);
                if (++frames > 10_000) throw new AssertionError("never found");
            }
        }

        void click(WorldCursor cursor) {
            tool.onPointer(view, new PointerEvent(PointerEvent.Kind.PRESS, PointerEvent.LEFT, 0, 0, 0, cursor));
            tool.onPointer(view, new PointerEvent(PointerEvent.Kind.RELEASE, PointerEvent.LEFT, 0, 0, 0, cursor));
        }

        <T> void set(SettingDef<T> def, T value) {
            ctx.updateSettings(ToolId.EXTRUDE, ctx.settings(ToolId.EXTRUDE).with(def, value));
        }

        List<String> noticeKeys() {
            return notices.stream().map(Notice::key).toList();
        }

        private final class FakeServices implements ExtrudeTool.Services {
            List<GhostPlacement> shown = List.of();
            /** The cursor ray (null: none). */
            Ray ray;

            @Override
            public Optional<Ray> cursorRay() {
                return Optional.ofNullable(ray);
            }

            @Override
            public String keyLabel(KeyAction action) {
                return EditorKeymap.defaults().display(action);
            }

            @Override
            public void showGhosts(List<GhostPlacement> placements) {
                shown = List.copyOf(placements);
            }

            @Override
            public void releaseGhost(GhostVolume volume) {}

            @Override
            public Executor background() {
                return Runnable::run;
            }

            @Override
            public long nanoTime() {
                return now += MS;
            }
        }
    }
}
