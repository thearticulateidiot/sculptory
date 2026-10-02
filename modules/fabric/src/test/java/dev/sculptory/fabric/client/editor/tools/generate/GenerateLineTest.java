package dev.sculptory.fabric.client.editor.tools.generate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.generate.GeneratedSource;
import dev.sculptory.core.generate.LineKernel;
import dev.sculptory.core.path.PathKind;
import dev.sculptory.core.path.PathSpec;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
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
import dev.sculptory.fabric.client.editor.tool.Modifiers;
import dev.sculptory.fabric.client.editor.tool.PointerEvent;
import dev.sculptory.fabric.client.editor.tool.ScrollEvent;
import dev.sculptory.fabric.client.editor.tool.ToolContext;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tool.WorldCursor;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.Notice;
import dev.sculptory.fabric.client.session.Permissions;
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

/** Generate's Line: points, the preview, Enter building one "Line" paste, the thickness key and refusals. */
class GenerateLineTest {
    private static final long MS = 1_000_000L;
    private static final int GROUND = 63;

    private final FakeStateSpace states = new FakeStateSpace();

    /** A hit on block (x, y, z), on its top. */
    private static WorldCursor on(int x, int y, int z) {
        return new WorldCursor(new BlockPos(x, y, z), WorldCursor.Face.UP, x + 0.5, y + 1, z + 0.5, false);
    }

    @Test
    void clickedBlocksArePointsAndThePreviewIsTheKernelsLine() {
        Rig rig = new Rig();
        rig.click(on(0, 70, 0));
        rig.frame(on(0, 70, 0));
        assertTrue(rig.tool.line().preview().isEmpty(), "one point shows just its marker");
        rig.click(on(10, 75, 4));
        rig.frame(on(10, 75, 4));
        assertEquals(List.of(new BlockPos(0, 70, 0), new BlockPos(10, 75, 4)), rig.tool.line().points().nodes());
        assertTrue(rig.tool.path().isEmpty(), "the road's nodes are apart");
        LineDraft.Built built = rig.tool.line().preview().orElseThrow();
        GeneratedSource expected = LineKernel.generate(new LineKernel.Spec(new PathSpec(List.of(new BlockPos(0, 70, 0),
                new BlockPos(10, 75, 4)), PathKind.STRAIGHT), 1, LineKernel.Profile.ROUND,
                new Pattern.Single(states.state("minecraft:stone_bricks"))), states, 1 << 20);
        assertEquals(expected.cells(), built.cells());
        expected.forEach((x, y, z, state) -> assertEquals(state, built.source().get(x, y, z)));
        assertNotNull(built.ghost());
        assertEquals(1, rig.services.shown.size());
        assertTrue(built.samples().size() > 2, "the path is drawn");

        // A thicker curve with the settings: the preview follows.
        rig.set(GenerateSettings.LINE_PATH, PathKind.CURVE);
        rig.set(GenerateSettings.LINE_THICKNESS, 3);
        rig.set(GenerateSettings.LINE_PROFILE, LineKernel.Profile.SQUARE);
        rig.click(on(20, 70, 0));
        rig.frame(on(20, 70, 0));
        GeneratedSource thick = LineKernel.generate(new LineKernel.Spec(new PathSpec(rig.tool.line().points().nodes(),
                PathKind.CURVE), 3, LineKernel.Profile.SQUARE, new Pattern.Single(states.state("minecraft:stone_bricks"))),
                states, 1 << 20);
        assertEquals(thick.cells(), rig.tool.line().preview().orElseThrow().cells());
        rig.set(GenerateSettings.LINE_PATH, PathKind.HANGING);
        assertTrue(rig.values().isVisible(GenerateSettings.LINE_SAG), "Sag shows for a hanging line");
    }

    @Test
    void enterUploadsTheLineThenPastesItAsOneLineJob() {
        Rig rig = new Rig();
        rig.click(on(0, 70, 0));
        rig.click(on(8, 70, 0));
        rig.frame(on(8, 70, 0));
        assertTrue(rig.tool.onAction(rig.view, EditorAction.COMMIT));
        rig.frame(on(8, 70, 0));
        Box bounds = new Box(new BlockPos(0, 70, 0), new BlockPos(8, 70, 0));
        assertEquals(List.of(new MockEditorSession.Call("generate", bounds)), rig.mock.calls());
        ToolAction.RunOp run = assertInstanceOf(ToolAction.RunOp.class, rig.mock.sent().get(0));
        assertEquals(OpLabel.LINE, run.label());
        OpSpec.Paste paste = assertInstanceOf(OpSpec.Paste.class, run.op());
        assertEquals(bounds.min(), paste.origin());
        assertEquals(new PasteOptions(true, false, false), paste.o());
        assertEquals(List.of(GenerateTool.SENT_LINE), rig.noticeKeys());
        assertEquals(List.of("9"), rig.notices.get(0).args());
        rig.mock.finishJobs();
        assertEquals("Line", rig.mock.history().undoLabel(), "one undo step, named Line");
        assertEquals(2, rig.tool.line().points().size(), "the points stay");
    }

    @Test
    void backspaceEscAndCtrlScrollWorkOnTheLine() {
        Rig rig = new Rig();
        rig.click(on(0, 70, 0));
        rig.click(on(5, 70, 0));
        rig.click(on(9, 72, 3));
        assertTrue(rig.tool.onAction(rig.view, EditorAction.REMOVE_NODE));
        assertEquals(2, rig.tool.line().points().size());
        rig.press(on(0, 70, 0));
        rig.drag(on(0, 70, 6));
        rig.release(on(0, 70, 6));
        assertEquals(new BlockPos(0, 70, 6), rig.tool.line().points().node(0), "a point drags like a node");
        assertTrue(rig.tool.onScroll(rig.view, new ScrollEvent(1, Modifiers.CONTROL)));
        assertEquals(2, rig.values().get(GenerateSettings.LINE_THICKNESS));
        assertTrue(rig.tool.onScroll(rig.view, new ScrollEvent(1, Modifiers.CONTROL | Modifiers.SHIFT)));
        assertEquals(6, rig.values().get(GenerateSettings.LINE_THICKNESS));
        rig.frame(on(0, 70, 6));
        assertTrue(rig.tool.onAction(rig.view, EditorAction.CANCEL), "Esc clears the points");
        assertTrue(rig.tool.line().points().isEmpty());
        rig.frame(on(0, 70, 6));
        assertTrue(rig.tool.line().preview().isEmpty());
        assertEquals(List.of(), rig.services.shown, "the ghost is gone");
        assertFalse(rig.tool.onAction(rig.view, EditorAction.CANCEL));
    }

    @Test
    void switchingKindsKeepsEachKindsPointsAndShowsOneGhost() {
        Rig rig = new Rig();
        rig.click(on(0, 70, 0));
        rig.click(on(6, 70, 0));
        rig.frame(on(6, 70, 0));
        assertEquals(1, rig.services.shown.size());
        rig.set(GenerateSettings.KIND, GenerateSettings.Kind.PATH);
        rig.frame(on(6, 70, 0));
        assertEquals(List.of(), rig.services.shown, "the road has no nodes: no ghost");
        assertEquals(2, rig.tool.line().points().size(), "the line's points stay");
        rig.set(GenerateSettings.KIND, GenerateSettings.Kind.LINE);
        rig.frame(on(6, 70, 0));
        assertEquals(1, rig.services.shown.size(), "the line's ghost is back");
    }

    @Test
    void refusalsSayWhyAndSendNothing() {
        Rig few = new Rig();
        few.click(on(0, 70, 0));
        assertTrue(few.tool.onAction(few.view, EditorAction.COMMIT));
        assertEquals(List.of(LineDraft.NEEDS_POINTS), few.noticeKeys());

        Rig small = new Rig();
        small.mock.setPermissions(new Permissions(Perm.mask(EnumSet.of(Perm.USE, Perm.CLIPBOARD, Perm.REGION)),
                new Limits(20, 20, 32, 20, 32L << 20, 2)));
        small.click(on(0, 70, 0));
        small.click(on(40, 70, 0));
        small.frame(on(40, 70, 0));
        assertTrue(small.tool.line().preview().orElseThrow().tooLarge());
        assertTrue(small.tool.onAction(small.view, EditorAction.COMMIT));
        assertEquals(List.of("sculptory.notice.too_large"), small.noticeKeys());

        Rig far = new Rig();
        far.world.setLoaded(2, 0, false);
        far.click(on(0, 70, 0));
        far.click(on(40, 70, 0));
        far.frame(on(40, 70, 0));
        assertTrue(far.tool.onAction(far.view, EditorAction.COMMIT));
        assertEquals(List.of(LineDraft.UNLOADED), far.noticeKeys());
        assertEquals(List.of("1"), far.notices.get(0).args());

        for (Perm missing : List.of(Perm.REGION, Perm.CLIPBOARD)) {
            Rig rig = new Rig();
            EnumSet<Perm> nodes = EnumSet.of(Perm.USE, Perm.CLIPBOARD, Perm.REGION);
            nodes.remove(missing);
            rig.mock.setPermissions(new Permissions(Perm.mask(nodes), Limits.DEFAULTS));
            rig.click(on(0, 70, 0));
            rig.click(on(6, 70, 0));
            rig.frame(on(6, 70, 0));
            assertTrue(rig.tool.onAction(rig.view, EditorAction.COMMIT));
            assertEquals(List.of("sculptory.notice.needs_permission"), rig.noticeKeys());
            assertEquals(List.of(missing.node()), rig.notices.get(0).args());
        }

        Rig chest = new Rig();
        chest.set(GenerateSettings.LINE_BLOCK, BlockDescriptor.parse("minecraft:chest"));
        chest.click(on(0, 70, 0));
        chest.click(on(6, 70, 0));
        chest.frame(on(6, 70, 0));
        assertTrue(chest.tool.line().preview().isEmpty());
        assertEquals(List.of(GenerateTool.BLOCK_ENTITY), chest.noticeKeys());
        for (Rig rig : List.of(few, small, far, chest)) assertTrue(rig.mock.calls().isEmpty(), "nothing was uploaded");
    }

    @Test
    void theEnglishTextCoversTheLine() throws IOException {
        JsonObject lang;
        try (InputStream in = getClass().getResourceAsStream("/assets/sculptory/lang/en_us.json")) {
            assertNotNull(in);
            lang = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        List<String> keys = new ArrayList<>(List.of(GenerateTool.OP_LINE, GenerateTool.SENT_LINE, LineDraft.NEEDS_POINTS,
                LineDraft.UNLOADED, LineDraft.NOTHING, "sculptory.notice.line_max_points", "sculptory.hint.line.add_point",
                "sculptory.hint.line.add_or_select", "sculptory.hint.line.move_point", "sculptory.hint.line.remove_point",
                "sculptory.hint.line.build", "sculptory.hint.line.clear", "sculptory.hint.line.thickness",
                "sculptory.setting.generate.line_section"));
        for (String key : keys) assertTrue(lang.has(key), key);
    }

    // ---------------------------------------------------------------- fixtures

    /** The Generate tool set to Line, over flat ground and a mock session. */
    private final class Rig {
        final FakeWorld world = flatWorld();
        final MockEditorSession mock = new MockEditorSession();
        final List<Notice> notices = new ArrayList<>();
        final FakeServices services = new FakeServices();
        final EditorContext ctx;
        final GenerateTool tool;
        final ToolContext view;
        long now = 1_000 * MS;

        Rig() {
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
            set(GenerateSettings.KIND, GenerateSettings.Kind.LINE);
        }

        void frame(WorldCursor cursor) {
            now += 16 * MS;
            tool.frame(view, new FrameInfo(now, 0f, 0, 0, cursor));
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

        void click(WorldCursor cursor) {
            press(cursor);
            release(cursor);
        }

        <T> void set(SettingDef<T> def, T value) {
            ctx.updateSettings(ToolId.GENERATE, ctx.settings(ToolId.GENERATE).with(def, value));
        }

        dev.sculptory.fabric.client.editor.settings.SettingsValues values() {
            return ctx.settings(ToolId.GENERATE);
        }

        List<String> noticeKeys() {
            return notices.stream().map(Notice::key).toList();
        }
    }

    /** Grass on stone at {@link #GROUND}, x and z within ±48. */
    private FakeWorld flatWorld() {
        FakeWorld world = new FakeWorld(states);
        int stone = states.state("minecraft:stone");
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
        public String keyLabel(KeyAction action) {
            return keymap.display(action);
        }

        @Override
        public void confirm(String opNameKey, long blocks, Runnable onConfirm) {
            onConfirm.run();
        }
    }
}
