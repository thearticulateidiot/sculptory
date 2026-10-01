package dev.sculptory.fabric.client.editor.tools.brush;

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
import dev.sculptory.core.brush.ShapeSpec;
import dev.sculptory.core.brush.ShapeSweep;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.generate.GeneratedSource;
import dev.sculptory.core.path.PathKind;
import dev.sculptory.core.path.PathSpec;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.core.world.WorldReader;
import dev.sculptory.fabric.client.editor.EditorBackend;
import dev.sculptory.fabric.client.editor.EditorContext;
import dev.sculptory.fabric.client.editor.mock.MockEditorSession;
import dev.sculptory.fabric.client.editor.settings.SettingDef;
import dev.sculptory.fabric.client.editor.tool.EditorAction;
import dev.sculptory.fabric.client.editor.tool.FrameInfo;
import dev.sculptory.fabric.client.editor.tool.PointerEvent;
import dev.sculptory.fabric.client.editor.tool.ToolContext;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tool.WorldCursor;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.Notice;
import dev.sculptory.fabric.client.session.ToolAction;
import dev.sculptory.protocol.v2.OpLabel;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The Shape brush's Line mode: clicks add points, the sweep is previewed, Enter builds one "Shape line" paste. */
class ShapeLineTest {
    private static final long MS = 1_000_000L;
    private static final int GROUND = 63;
    private static final BlockDescriptor ACTIVE = BlockDescriptor.parse("minecraft:stone");

    private final FakeStateSpace states = new FakeStateSpace();

    private static WorldCursor top(int x, int z) {
        return new WorldCursor(new BlockPos(x, GROUND, z), WorldCursor.Face.UP, x + 0.5, GROUND + 1, z + 0.5, false);
    }

    @Test
    void inLineModeClicksAddPointsAndStartNoStroke() {
        Rig rig = new Rig();
        rig.set(ShapeSettings.ANCHOR, ShapeSettings.Anchor.CENTRE);
        rig.click(top(0, 0));
        rig.click(top(12, 0));
        rig.frame(top(12, 0));
        assertEquals(List.of(new BlockPos(0, GROUND, 0), new BlockPos(12, GROUND, 0)), rig.tool.line().points().nodes());
        assertTrue(rig.mock.sent().isEmpty(), "no stroke, no op");
        assertTrue(rig.tool.controller().stroke() == null && !rig.tool.controller().pressed());
        // The preview is the sweep of the settings' sphere (radius 4) along the points.
        GeneratedSource expected = ShapeSweep.generate(new PathSpec(rig.tool.line().points().nodes(), PathKind.STRAIGHT), 4,
                new ShapeSpec(ShapeSpec.Kind.SPHERE, 9, Facing.UP, ShapeSpec.Mode.PLACE, 0),
                new Pattern.Single(states.state("minecraft:stone")), states, 1 << 20);
        assertEquals(expected.cells(), rig.tool.line().preview().orElseThrow().cells());
    }

    @Test
    void onSurfaceAPointIsWhereAClickWouldCentreTheShape() {
        Rig rig = new Rig();
        rig.set(ShapeSettings.KIND, ShapeSpec.Kind.CUBE);
        rig.set(ShapeSettings.RADIUS, 2);
        rig.click(top(3, 3));
        // A cube 5 high resting on the top face of y 63: its centre block is y 66.
        assertEquals(new BlockPos(3, GROUND + 3, 3), rig.tool.line().points().node(0));
    }

    @Test
    void enterBuildsTheSweepAsOneShapeLinePasteWithTheModesInto() {
        for (ShapeSpec.Mode mode : ShapeSpec.Mode.values()) {
            Rig rig = new Rig();
            rig.set(ShapeSettings.ANCHOR, ShapeSettings.Anchor.CENTRE);
            rig.set(ShapeSettings.RADIUS, 1);
            rig.set(ShapeSettings.MODE, mode);
            rig.click(top(0, 0));
            rig.click(top(6, 0));
            rig.frame(top(6, 0));
            assertTrue(rig.tool.onAction(rig.view, EditorAction.COMMIT));
            rig.frame(top(6, 0));
            assertEquals(1, rig.mock.calls().size(), mode + ": one upload");
            ToolAction.RunOp run = assertInstanceOf(ToolAction.RunOp.class, rig.mock.sent().get(0));
            assertEquals(OpLabel.SHAPE_LINE, run.label());
            OpSpec.Paste paste = assertInstanceOf(OpSpec.Paste.class, run.op());
            PasteOptions.Into into = switch (mode) {
                case PLACE, CARVE -> PasteOptions.Into.EVERYTHING;
                case PLACE_IN_AIR -> PasteOptions.Into.AIR;
                case PAINT -> PasteOptions.Into.EXISTING;
            };
            assertEquals(new PasteOptions(true, false, false, into), paste.o(), mode.name());
            assertEquals(List.of(ShapeBrushTool.SENT_LINE), rig.noticeKeys(), mode.name());
            GeneratedSource built = rig.tool.line().preview().orElseThrow().source();
            int air = states.air();
            built.forEach((x, y, z, state) -> assertEquals(mode == ShapeSpec.Mode.CARVE, state == air, mode + " cell"));
            rig.mock.finishJobs();
            assertEquals("Shape line", rig.mock.history().undoLabel());
        }
    }

    @Test
    void backspaceAndEscEditThePointsAndDrawingShapesAgainLeavesThem() {
        Rig rig = new Rig();
        rig.click(top(0, 0));
        rig.click(top(4, 0));
        rig.click(top(8, 0));
        assertTrue(rig.tool.onAction(rig.view, EditorAction.REMOVE_NODE));
        assertEquals(2, rig.tool.line().points().size());
        rig.frame(top(8, 0));
        assertTrue(rig.tool.line().preview().isPresent());
        rig.set(ShapeSettings.DRAW, ShapeSettings.Draw.SHAPES);
        rig.frame(top(8, 0));
        assertTrue(rig.tool.line().preview().isEmpty(), "placing shapes again hides the line");
        assertEquals(2, rig.tool.line().points().size(), "its points stay");
        assertFalse(rig.tool.onAction(rig.view, EditorAction.COMMIT), "Enter is not the line's while placing shapes");
        rig.set(ShapeSettings.DRAW, ShapeSettings.Draw.LINE);
        assertTrue(rig.tool.onAction(rig.view, EditorAction.CANCEL));
        assertTrue(rig.tool.line().points().isEmpty());
    }

    @Test
    void onlyInsideSelectionKeepsTheSweepInTheBoxAndNeedsOne() {
        Rig rig = new Rig();
        rig.set(ShapeSettings.ANCHOR, ShapeSettings.Anchor.CENTRE);
        rig.set(ShapeSettings.INSIDE_SELECTION, true);
        rig.click(top(0, 0));
        rig.click(top(10, 0));
        rig.frame(top(10, 0));
        assertTrue(rig.tool.line().preview().isEmpty());
        assertEquals(List.of(TerrainBrushTool.NEEDS_SELECTION), rig.noticeKeys());
        Box box = new Box(new BlockPos(2, 60, -2), new BlockPos(6, 70, 2));
        rig.ctx.setSelection(box);
        rig.set(ShapeSettings.RADIUS, 2);
        rig.frame(top(10, 0));
        GeneratedSource source = rig.tool.line().preview().orElseThrow().source();
        assertFalse(source.isEmpty());
        source.forEach((x, y, z, state) -> assertTrue(box.contains(x, y, z), "outside the box: " + x + "," + y + "," + z));
    }

    @Test
    void theEnglishTextCoversLineMode() throws IOException {
        JsonObject lang;
        try (InputStream in = getClass().getResourceAsStream("/assets/sculptory/lang/en_us.json")) {
            assertNotNull(in);
            lang = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        List<String> keys = new ArrayList<>(List.of(ShapeBrushTool.OP_LINE, ShapeBrushTool.SENT_LINE,
                ShapeBrushTool.BUILD_LINE_HINT));
        for (SettingDef<?> def : List.of(ShapeSettings.DRAW, ShapeSettings.LINE_PATH, ShapeSettings.LINE_SAG)) {
            keys.add(def.labelKey());
            keys.add(def.labelKey() + ".tooltip");
            if (def instanceof SettingDef.Enum<?> choice) {
                for (Object option : choice.type().getEnumConstants()) {
                    keys.add(def.labelKey() + "." + ((Enum<?>) option).name().toLowerCase(java.util.Locale.ROOT));
                }
            }
        }
        for (String key : keys) assertTrue(lang.has(key), key);
    }

    // ---------------------------------------------------------------- fixtures

    /** The Shape brush set to draw along a line, over flat ground and a mock session. */
    private final class Rig {
        final FakeWorld world = new FakeWorld(states);
        final MockEditorSession mock = new MockEditorSession();
        final List<Notice> notices = new ArrayList<>();
        final EditorContext ctx;
        final ShapeBrushTool tool;
        final ToolContext view;
        long now = 1_000 * MS;

        Rig() {
            int stone = states.state("minecraft:stone");
            world.fill(new Box(new BlockPos(-40, 50, -40), new BlockPos(40, GROUND, 40)), stone);
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
            tool = new ShapeBrushTool(BrushServices.headless(), new SymmetryCentre(), () -> ACTIVE);
            ctx.tools().register(tool);
            view = ctx.contextFor(ToolId.SHAPE);
            assertTrue(ctx.tools().activate(ToolId.SHAPE, view));
            set(ShapeSettings.DRAW, ShapeSettings.Draw.LINE);
        }

        void frame(WorldCursor cursor) {
            now += 16 * MS;
            tool.frame(view, new FrameInfo(now, 0f, 0, 0, cursor));
        }

        void click(WorldCursor cursor) {
            assertTrue(tool.onPointer(view, new PointerEvent(PointerEvent.Kind.PRESS, PointerEvent.LEFT, 0, 0, 0, cursor)));
            tool.onPointer(view, new PointerEvent(PointerEvent.Kind.RELEASE, PointerEvent.LEFT, 0, 0, 0, cursor));
        }

        <T> void set(SettingDef<T> def, T value) {
            ctx.updateSettings(ToolId.SHAPE, ctx.settings(ToolId.SHAPE).with(def, value));
        }

        List<String> noticeKeys() {
            return notices.stream().map(Notice::key).toList();
        }
    }
}
