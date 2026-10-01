package dev.sculptory.fabric.client.editor.demo;

import static dev.sculptory.fabric.client.editor.demo.DemoCaptions.*;
import static dev.sculptory.fabric.client.editor.demo.DemoStage.*;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.Box;
import dev.sculptory.fabric.client.builder.RingScreen;
import dev.sculptory.fabric.client.editor.blocks.BlockChip;
import dev.sculptory.fabric.client.editor.check.CheckDriver;
import dev.sculptory.fabric.client.editor.hud.EditorUi;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tool.WorldCursor.Face;
import dev.sculptory.fabric.client.editor.tools.place.PlaceTool;
import dev.sculptory.fabric.client.editor.tools.scatter.ScatterTool;
import dev.sculptory.fabric.client.editor.tools.tinker.TinkerTool;
import dev.sculptory.fabric.client.editor.tutorial.TutorialRunner;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.widget.Dropdown;
import dev.sculptory.fabric.client.editor.ui.widget.Label;
import dev.sculptory.fabric.client.editor.ui.widget.ListView;
import dev.sculptory.fabric.client.editor.ui.widget.Slider;
import dev.sculptory.fabric.client.editor.ui.widget.TextInput;
import dev.sculptory.fabric.client.editor.ui.window.Window;
import dev.sculptory.fabric.client.editor.windows.EditorWindows;
import dev.sculptory.fabric.client.editor.windows.HistoryWindow;
import dev.sculptory.fabric.client.editor.windows.WikiWindow;
import dev.sculptory.fabric.client.tinker.TinkerController;
import dev.sculptory.protocol.v2.BuilderPower;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.lwjgl.glfw.GLFW;

/**
 * The demonstration itself: nineteen captioned steps through the editor and builder mode on the {@link DemoStage},
 * at a pace a viewer can follow. Each step stands on the stage as built (a retake with {@code -DemoFrom} starts at
 * any step); the few that need earlier work (a clipboard, history entries) make it themselves. The words are in
 * {@link DemoCaptions}.
 */
public final class DemoScript {
    /** A beat on a result. */
    private static final long RESULT_MS = 2_400;
    private static final long SHORT_MS = 1_600;
    private static final String BRICKS = "minecraft:bricks";
    private static final String STONE_BRICKS = "minecraft:stone_bricks";
    private static final List<String> MIX = List.of("minecraft:stone", "minecraft:cobblestone",
            "minecraft:mossy_cobblestone");
    private static final List<Integer> MIX_WEIGHTS = List.of(3, 2, 1);

    private DemoScript() {}

    /** The steps, in order. */
    public static List<DemoStep<Demo>> steps() {
        return List.of(
                DemoStep.outsideEditor("title", DemoScript::title),
                DemoStep.of("screen", DemoScript::screen),
                DemoStep.of("select", DemoScript::select),
                DemoStep.of("brushes", DemoScript::brushes),
                DemoStep.of("paint", DemoScript::paint),
                DemoStep.of("shape", DemoScript::shape),
                DemoStep.of("symmetry", DemoScript::symmetry),
                DemoStep.of("paste", DemoScript::paste),
                DemoStep.of("generate", DemoScript::generate),
                DemoStep.of("extrude", DemoScript::extrude),
                DemoStep.of("fluid", DemoScript::fluid),
                DemoStep.of("scatter", DemoScript::scatter),
                DemoStep.of("tinker", DemoScript::tinker),
                DemoStep.of("library", DemoScript::library),
                DemoStep.of("history", DemoScript::history),
                DemoStep.of("tutorial", DemoScript::tutorial),
                DemoStep.of("wiki", DemoScript::wiki),
                DemoStep.of("builder", DemoScript::builder),
                DemoStep.outsideEditor("closing", DemoScript::closing));
    }

    /** The view over the whole stage the demo opens and closes with. */
    static void overview(Demo d, long ms) {
        d.glide(d.stage().x(56) + 0.5, d.stage().y(52), d.stage().z(136) + 0.5, d.stage().x(56) + 0.5,
                d.stage().y(0), d.stage().z(52) + 0.5, ms);
    }

    // ---- 1 ----

    private static void title(Demo d) {
        d.note(TITLE, d.translate(NAME));
        d.pause(2_500);
        d.say(OPEN_EDITOR);
        d.openEditor();
        d.hold(RESULT_MS);
    }

    // ---- 2 ----

    private static void screen(Demo d) {
        DemoStage s = d.stage();
        d.defaultLayout();
        d.view(s.x(19) + 0.5, s.y(10), s.z(34) + 0.5, s.x(19) + 0.5, s.y(1), s.z(19) + 0.5);
        Rect slot = d.onClient(() -> d.ui().paletteSlotBounds(1).orElse(Rect.EMPTY));
        d.note(SCREEN_PALETTE);
        if (!slot.isEmpty()) {
            d.hoverUi(slot.x() + slot.width() / 2.0, slot.y() + slot.height() / 2.0, 300);
            Rect last = d.onClient(() -> d.ui().paletteSlotBounds(14).orElse(slot));
            d.hoverUi(last.x() + last.width() / 2.0, last.y() + last.height() / 2.0, SHORT_MS);
        }
        d.note(SCREEN_SETTINGS);
        Window settings = d.window(EditorWindows.TOOL_SETTINGS);
        Rect settingsRect = d.onClient(settings::rect);
        d.hoverUi(settingsRect.x() + settingsRect.width() / 2.0, settingsRect.y() + settingsRect.height() / 2.0,
                SHORT_MS);
        d.note(SCREEN_TOP_BAR);
        Rect bar = d.onClient(() -> d.ui().topBarRow().bounds());
        d.hoverUi(bar.x() + 40, bar.y() + bar.height() / 2.0, 400);
        d.hoverUi(bar.right() - 60, bar.y() + bar.height() / 2.0, SHORT_MS);

        // View > Opacity: the Panels slider down and up, then Tool outlines.
        d.say(SCREEN_OPACITY);
        d.clickMenuRow(EditorUi.MENU_VIEW, "sculptory.command.view.opacity");
        List<Slider> sliders = d.popupNodes(Slider.class);
        if (sliders.size() >= 2) {
            dragSlider(d, sliders.get(0), 25);
            d.hold(SHORT_MS);
            dragSlider(d, sliders.get(0), 100);
            d.pause(800);
            d.say(SCREEN_OUTLINES);
            dragSlider(d, sliders.get(1), 20);
            d.hold(SHORT_MS);
            dragSlider(d, sliders.get(1), 100);
        }
        d.closePopup();
        d.park();

        d.say(SCREEN_UI_SIZE);
        d.key(KeyAction.UI_LARGER);
        d.hold(SHORT_MS);
        d.key(KeyAction.UI_SMALLER);
        d.hold(SHORT_MS);

        d.say(SCREEN_DRAG_WINDOW);
        Rect title = d.onClient(() -> settings.titleBarRect(d.ui().windows().context().theme()));
        double[] grab = d.screen(title.x() + title.width() / 2.0, title.y() + title.height() / 2.0);
        d.moveTo(grab[0], grab[1], Demo.POINTER_MS);
        d.press(0);
        d.dragScreen(grab[0] - 90, grab[1] + 70, 900);
        d.release();
        d.hold(SHORT_MS);
        d.say(SCREEN_RESET_LAYOUT);
        d.clickMenuRow(EditorUi.MENU_VIEW, "sculptory.command.view.reset_layout");
        d.park();
        d.hold(SHORT_MS);

        d.say(SCREEN_KEYS);
        d.key(KeyAction.HELP);
        d.pause(3_000);
        d.key(KeyAction.HELP);
        d.pause(600);
        d.say(SCREEN_FIND);
        d.key(KeyAction.COMMAND_SEARCH);
        d.pause(500);
        d.typeSlowly("roof", 160);
        d.hold(SHORT_MS);
        d.keyCode(GLFW.GLFW_KEY_ESCAPE, 0);
        d.pause(500);
    }

    /** Drags a slider's knob to {@code value}, as a hand would. */
    private static void dragSlider(Demo d, Slider slider, double value) {
        double[] from = knob(d, slider, d.onClient(slider::value));
        double[] to = knob(d, slider, value);
        d.moveTo(from[0], from[1], Demo.POINTER_MS);
        d.press(0);
        d.dragScreen(to[0], to[1], 700);
        d.release();
    }

    private static double[] knob(Demo d, Slider slider, double value) {
        Rect bounds = d.onClient(() -> {
            d.driver().relayout();
            return slider.bounds();
        });
        double t = (value - slider.min()) / (slider.max() - slider.min());
        return d.screen(bounds.x() + 1 + t * (bounds.width() - 2), bounds.y() + bounds.height() / 2.0);
    }

    // ---- 3 ----

    private static void select(Demo d) {
        DemoStage s = d.stage();
        d.view(s.x(19) + 0.5, s.y(10), s.z(34) + 0.5, s.x(19) + 0.5, s.y(1), s.z(19) + 0.5);
        d.say(SELECT_DRAW);
        d.selectTool(ToolId.SELECT);
        d.aim(BOX_X, -1, BOX_Z, Face.UP);
        d.press(0);
        d.dragTo(POST_X + 0.5, POST_TOP + 1, POST_Z + 0.5, 1_000);
        d.release();
        d.hold(SHORT_MS);
        d.say(SELECT_FILL);
        d.activeBlock(BRICKS);
        d.edit(() -> d.clickButton(EditorWindows.SELECTION, "sculptory.op.fill"));
        d.hold(RESULT_MS);
        d.say(SELECT_HOLLOW);
        d.edit(() -> d.clickButton(EditorWindows.SELECTION, "sculptory.op.hollow"));
        d.hold(RESULT_MS);
        d.say(SELECT_WALLS);
        d.edit(() -> d.clickButton(EditorWindows.SELECTION, "sculptory.op.walls"));
        d.hold(RESULT_MS);
        d.say(SELECT_REPLACE);
        d.activeBlock(BRICKS);
        d.clickButton(EditorWindows.SELECTION, "sculptory.op.replace_dots");
        List<BlockChip> chips = d.popupNodes(BlockChip.class);
        if (chips.size() >= 2) {
            d.onClient(() -> chips.get(1).setBlock(BlockDescriptor.parse(STONE_BRICKS)));
        }
        d.hold(SHORT_MS);
        String replace = d.translate("sculptory.op.replace");
        Node confirm = d.popupNodes(dev.sculptory.fabric.client.editor.ui.widget.Button.class).stream()
                .filter(b -> b.text().equals(replace)).findFirst()
                .orElseThrow(() -> new CheckDriver.Failed("no Replace button in the popup"));
        d.edit(() -> d.clickNode(confirm));
        d.park();
        d.hold(RESULT_MS);
        d.say(SELECT_UNDO);
        d.edit(() -> d.key(KeyAction.UNDO));
        d.pause(1_000);
        d.edit(() -> d.key(KeyAction.UNDO));
        d.hold(RESULT_MS);
        d.onClient(() -> d.ctx().setSelection(null));
    }

    // ---- 4 ----

    private static void brushes(Demo d) {
        DemoStage s = d.stage();
        faceView(d, 8, 40);
        d.say(BRUSH_RAISE);
        d.selectTool(ToolId.RAISE);
        d.setting(ToolId.RAISE, "radius", 4);
        d.setting(ToolId.RAISE, "mode", "SURFACE");
        d.stroke(CLIFF_X, 8, 40, Face.WEST, 350);
        d.hold(SHORT_MS);
        // The held stroke and Smooth work on the lump the first stroke made, at the same spot on the face.
        d.say(BRUSH_RAISE_HELD);
        d.holdAt(s.x(CLIFF_X), s.y(8) + 0.5, s.z(40) + 0.5, 2_200);
        d.hold(RESULT_MS);
        d.say(BRUSH_SMOOTH);
        d.selectTool(ToolId.SMOOTH);
        d.setting(ToolId.SMOOTH, "radius", 4);
        d.setting(ToolId.SMOOTH, "mode", "SURFACE");
        d.holdAt(s.x(CLIFF_X), s.y(8) + 0.5, s.z(40) + 0.5, 900);
        d.hold(RESULT_MS);
        d.say(BRUSH_FLATTEN);
        d.selectTool(ToolId.FLATTEN);
        d.setting(ToolId.FLATTEN, "radius", 3);
        d.setting(ToolId.FLATTEN, "mode", "SURFACE");
        faceView(d, 8, FLAT_BUMP_Z);
        d.dragStroke(CLIFF_X, 8, FLAT_BUMP_Z - 3, Face.WEST, CLIFF_X - 0.5, 8.5, FLAT_BUMP_Z + 3.5, 1_000, 0);
        d.hold(RESULT_MS);
        d.say(BRUSH_LOWER);
        d.selectTool(ToolId.LOWER);
        d.setting(ToolId.LOWER, "radius", 3);
        d.setting(ToolId.LOWER, "mode", "SURFACE");
        faceView(d, 8, 68);
        d.stroke(CLIFF_X, 8, 68, Face.WEST, 600);
        d.hold(SHORT_MS);
        d.say(BRUSH_SURFACE);
        d.selectTool(ToolId.RAISE);
        d.aim(CLIFF_X, 10, 72, Face.WEST);
        d.hold(RESULT_MS);
        d.say(BRUSH_TERRAIN);
        d.setting(ToolId.RAISE, "mode", "TERRAIN");
        // From the west, the cliff behind: the plank wall and the roofless box stay at the sides.
        d.view(s.x(MOUND_X - 12) + 0.5, s.y(8), s.z(MOUND_Z) + 0.5, s.x(MOUND_X) + 0.5, s.y(0), s.z(MOUND_Z) + 0.5);
        d.stroke(MOUND_X, -1, MOUND_Z, Face.UP, 1_100);
        d.hold(RESULT_MS);
        d.setting(ToolId.RAISE, "mode", "SURFACE");
    }

    /** Looks at the cliff's face at height {@code y} and depth {@code z} from 15 blocks west. */
    private static void faceView(Demo d, int y, int z) {
        DemoStage s = d.stage();
        d.view(s.x(CLIFF_X - 15) + 0.5, s.y(y) + 2.5, s.z(z) + 0.5, s.x(CLIFF_X), s.y(y) + 0.5, s.z(z) + 0.5);
    }

    // ---- 5 ----

    private static void paint(Demo d) {
        DemoStage s = d.stage();
        d.view(s.x(PAINT_X + 9) + 0.5, s.y(9), s.z(PAINT_Z + 16) + 0.5, s.x(PAINT_X + 9) + 0.5, s.y(0),
                s.z(PAINT_Z + 4) + 0.5);
        d.say(PAINT_MIX);
        d.selectTool(ToolId.PALETTE);
        d.mix(ToolId.PALETTE, "palette", MIX, MIX_WEIGHTS);
        d.setting(ToolId.PALETTE, "pattern", "RANDOM");
        d.setting(ToolId.PALETTE, "radius", 3);
        d.dragStroke(PAINT_X, -1, PAINT_Z, Face.UP, PAINT_X + 18.5, 0, PAINT_Z + 0.5, 1_300, 0);
        d.hold(SHORT_MS);
        d.say(PAINT_PATCHES);
        d.setting(ToolId.PALETTE, "pattern", "PATCHES");
        d.dragStroke(PAINT_X, -1, PAINT_Z + 6, Face.UP, PAINT_X + 18.5, 0, PAINT_Z + 6.5, 1_300, 0);
        d.hold(SHORT_MS);
        d.say(PAINT_GRADIENT_LINE);
        d.setting(ToolId.PALETTE, "pattern", "GRADIENT");
        d.aim(PAINT_X, -1, PAINT_Z + 12, Face.UP);
        d.press(GLFW.GLFW_MOD_ALT);
        d.dragTo(PAINT_X + 18.5, 0, PAINT_Z + 12.5, 1_000);
        d.release();
        d.hold(SHORT_MS);
        d.say(PAINT_GRADIENT);
        d.dragStroke(PAINT_X, -1, PAINT_Z + 12, Face.UP, PAINT_X + 18.5, 0, PAINT_Z + 12.5, 1_300, 0);
        d.hold(RESULT_MS);
        d.say(PAINT_SAVED);
        Node saveAs = d.onClient(() -> d.ui().toolSettings().presetBar()
                .orElseThrow(() -> new CheckDriver.Failed("no preset bar")).saveAsButton());
        d.clickNode(saveAs);
        d.pause(500);
        List<TextInput> fields = d.popupNodes(TextInput.class);
        if (!fields.isEmpty()) {
            d.onClient(() -> fields.get(0).selectAll());
        }
        d.typeSlowly("Stone mix", 120);
        d.pause(800);
        d.keyCode(GLFW.GLFW_KEY_ENTER, 0);
        d.park();
        d.hold(RESULT_MS);
        d.setting(ToolId.PALETTE, "pattern", "RANDOM");
    }

    // ---- 6 ----

    private static void shape(Demo d) {
        DemoStage s = d.stage();
        d.view(s.x(SHAPE_X + 6) + 0.5, s.y(10), s.z(SHAPE_Z) + 16.5, s.x(SHAPE_X + 6) + 0.5, s.y(1),
                s.z(SHAPE_Z) + 0.5);
        d.say(SHAPE_SPHERE);
        d.selectTool(ToolId.SHAPE);
        d.mix(ToolId.SHAPE, "palette", MIX, MIX_WEIGHTS);
        d.setting(ToolId.SHAPE, "blocks", "PALETTE");
        d.setting(ToolId.SHAPE, "kind", "SPHERE");
        d.setting(ToolId.SHAPE, "radius", 4);
        d.setting(ToolId.SHAPE, "hollow", false);
        d.aim(SHAPE_X, -1, SHAPE_Z, Face.UP);
        d.pause(800);
        d.edit(d::click);
        d.hold(RESULT_MS);
        d.say(SHAPE_CYLINDER);
        d.setting(ToolId.SHAPE, "kind", "CYLINDER");
        d.setting(ToolId.SHAPE, "hollow", true);
        d.aim(SHAPE_X + 12, -1, SHAPE_Z, Face.UP);
        d.pause(800);
        d.edit(d::click);
        d.hold(RESULT_MS);
        d.setting(ToolId.SHAPE, "hollow", false);
        d.setting(ToolId.SHAPE, "kind", "SPHERE");
    }

    // ---- 7 ----

    private static void symmetry(Demo d) {
        DemoStage s = d.stage();
        d.view(s.x(SYM_X) + 0.5, s.y(12), s.z(SYM_Z) + 16.5, s.x(SYM_X) + 0.5, s.y(0), s.z(SYM_Z) + 0.5);
        d.say(SYMMETRY_CENTRE);
        d.selectTool(ToolId.RAISE);
        d.setting(ToolId.RAISE, "mode", "TERRAIN");
        d.setting(ToolId.RAISE, "radius", 4);
        d.aim(SYM_X, -1, SYM_Z, Face.UP);
        d.key(KeyAction.SET_SYMMETRY_CENTRE);
        d.hold(SHORT_MS);
        d.say(SYMMETRY_MIRROR);
        d.setting(ToolId.RAISE, "symmetry", "MIRROR_X");
        d.stroke(SYM_X - 7, -1, SYM_Z, Face.UP, 900);
        d.hold(RESULT_MS);
        d.setting(ToolId.RAISE, "symmetry", "OFF");
        d.setting(ToolId.RAISE, "mode", "SURFACE");
        d.aim(SYM_X, -1, SYM_Z, Face.UP);
        d.key(KeyAction.SET_SYMMETRY_CENTRE);
        d.frames(3);
    }

    // ---- 8 ----

    private static void paste(Demo d) {
        DemoStage s = d.stage();
        houseView(d);
        d.say(PASTE_SELECT);
        selectHouse(d);
        d.hold(SHORT_MS);
        d.say(PASTE_COPY);
        copy(d);
        d.hold(SHORT_MS);
        d.say(PASTE_GHOST);
        PlaceTool place = startPaste(d);
        d.pointAt(s.x(PASTE_X - 6) + 0.5, s.y(0), s.z(PASTE_Z) + 0.5);
        d.pause(600);
        d.pointAt(s.x(PASTE_X) + 0.5, s.y(0), s.z(PASTE_Z) + 0.5);
        d.hold(SHORT_MS);
        d.say(PASTE_ROTATE);
        d.key(KeyAction.ROTATE_CW);
        d.hold(SHORT_MS);
        d.say(PASTE_MIRROR);
        d.key(KeyAction.FLIP_LEFT_RIGHT);
        d.hold(SHORT_MS);
        d.say(PASTE_FLIP);
        d.key(KeyAction.FLIP_UPSIDE_DOWN);
        d.hold(RESULT_MS);
        d.say(PASTE_PLACE);
        d.click();
        d.pause(800);
        d.edit(() -> d.key(KeyAction.COMMIT));
        d.hold(RESULT_MS);
        d.say(PASTE_STACK);
        d.onClient(() -> d.ctx().setSelection(null));
        houseView(d);
        selectHouse(d);
        d.clickButton(EditorWindows.SELECTION, "sculptory.selection.stack");
        PlaceTool stack = d.onClient(() -> d.driver().controller().placeTool().orElseThrow());
        if (!d.driver().until(10_000, () -> d.driver().controller().activePlaceTool().isPresent()
                && stack.placement().isPresent())) {
            throw new CheckDriver.Failed("Stack did not start the Place tool");
        }
        // From the north-west and above: the house and its pasted copy (east of it) stay out of the line of sight
        // to the floor north of the house, where the copies go.
        d.view(s.x(HOUSE_X0 - 10) + 0.5, s.y(14), s.z(HOUSE_Z0 - 16) + 0.5, s.x(HOUSE_X0 + 3) + 0.5, s.y(0),
                s.z(HOUSE_Z0 - 4) + 0.5);
        d.aim(HOUSE_X0 + 3, -1, HOUSE_Z0 - 5, Face.UP);
        d.pause(800);
        d.click();
        d.pause(800);
        d.scroll(1, GLFW.GLFW_MOD_CONTROL);
        d.hold(SHORT_MS);
        d.edit(() -> d.key(KeyAction.COMMIT));
        d.hold(RESULT_MS);
        d.onClient(() -> d.ctx().setSelection(null));
    }

    private static void houseView(Demo d) {
        DemoStage s = d.stage();
        d.view(s.x(HOUSE_X0 + 3) + 0.5, s.y(11), s.z(HOUSE_Z1 + 15) + 0.5, s.x(HOUSE_X0 + 3) + 0.5, s.y(2),
                s.z(HOUSE_Z1 - 3) + 0.5);
    }

    /** Drags from the south wall's bottom corner to the chimney's top: the whole house. */
    private static void selectHouse(Demo d) {
        DemoStage s = d.stage();
        d.selectTool(ToolId.SELECT);
        d.aim(HOUSE_X0, 0, HOUSE_Z1, Face.SOUTH);
        d.press(0);
        d.dragTo(HOUSE_X1 + 0.5, CHIMNEY_TOP + 1, HOUSE_Z0 + 0.5, 1_100);
        d.release();
        Box house = s.box(HOUSE_X0, 0, HOUSE_Z0, HOUSE_X1, CHIMNEY_TOP, HOUSE_Z1);
        Optional<Box> selected = d.onClient(() -> d.ctx().selection());
        if (!selected.equals(Optional.of(house))) {
            d.driver().client().execute(() -> {});
            d.onClient(() -> d.ctx().setSelection(house));
            d.frames(2);
        }
    }

    /** Ctrl+C, and the "Copied" toast. */
    private static void copy(Demo d) {
        int mark = d.driver().toastMark();
        d.key(KeyAction.COPY);
        if (d.driver().awaitToast(mark, "sculptory.notice.copied", 20_000).isEmpty()) {
            throw new CheckDriver.Failed("Ctrl+C did not copy" + d.driver().toastNote());
        }
    }

    /** Ctrl+V, and the Place tool with the ghost. */
    private static PlaceTool startPaste(Demo d) {
        d.key(KeyAction.PASTE);
        PlaceTool place = d.onClient(() -> d.driver().controller().placeTool().orElseThrow());
        if (!d.driver().until(20_000, () -> d.driver().controller().activePlaceTool().isPresent()
                && place.placement().isPresent())) {
            throw new CheckDriver.Failed("Ctrl+V did not start the Place tool" + d.driver().toastNote());
        }
        return place;
    }

    // ---- 9 ----

    private static void generate(Demo d) {
        DemoStage s = d.stage();
        d.view(s.x(78) + 0.5, s.y(24), s.z(ROAD_Z + 12) + 0.5, s.x(78) + 0.5, s.y(0), s.z(ROAD_Z) + 0.5);
        d.say(GENERATE_ROAD);
        d.selectTool(ToolId.GENERATE);
        d.setting(ToolId.GENERATE, "kind", "PATH");
        d.setting(ToolId.GENERATE, "path.material", "BLOCK");
        d.setting(ToolId.GENERATE, "path.block", "minecraft:cobblestone");
        d.aim(ROAD_X0, -1, ROAD_Z, Face.UP);
        d.click();
        d.pause(600);
        d.aim(ROAD_X1, -1, ROAD_Z, Face.UP);
        d.click();
        d.hold(RESULT_MS);
        d.say(GENERATE_ROAD_BUILT);
        d.edit(() -> d.key(KeyAction.COMMIT));
        d.hold(RESULT_MS);
        d.keyCode(GLFW.GLFW_KEY_ESCAPE, 0);

        d.say(GENERATE_ROOF);
        d.selectTool(ToolId.SELECT);
        d.view(s.x(ROOF_X0 - 7) + 0.5, s.y(11), s.z(ROOF_Z1 + 11) + 0.5, s.x(ROOF_X0 + 3) + 0.5, s.y(2),
                s.z(ROOF_Z0 + 3) + 0.5);
        d.aim(ROOF_X0, ROOF_TOP, ROOF_Z0, Face.UP);
        d.press(0);
        d.dragTo(ROOF_X1 + 0.5, ROOF_TOP + 1, ROOF_Z1 + 0.5, 1_000);
        d.release();
        d.pause(800);
        d.selectTool(ToolId.GENERATE);
        d.setting(ToolId.GENERATE, "kind", "ROOF");
        d.park();
        d.hold(RESULT_MS);
        d.say(GENERATE_ROOF_BUILT);
        d.edit(() -> d.key(KeyAction.COMMIT));
        d.hold(RESULT_MS);
        d.setting(ToolId.GENERATE, "kind", "PATH");
        d.onClient(() -> d.ctx().setSelection(null));
    }

    // ---- 10 ----

    private static void extrude(Demo d) {
        DemoStage s = d.stage();
        d.view(s.x(WALL_X0 + 3) + 0.5, s.y(7), s.z(WALL_Z + 14) + 0.5, s.x(WALL_X0 + 3) + 0.5, s.y(2),
                s.z(WALL_Z) + 1);
        d.say(EXTRUDE_OUT);
        d.selectTool(ToolId.EXTRUDE);
        d.aim(WALL_X0 + 3, 2, WALL_Z, Face.SOUTH);
        d.pause(900);
        d.dragStroke(WALL_X0 + 3, 2, WALL_Z, Face.SOUTH, WALL_X0 + 3.5, 2.5, WALL_Z + 4.2, 900, 0);
        d.hold(RESULT_MS);
        d.edit(() -> d.key(KeyAction.UNDO));
        d.pause(600);
        d.say(EXTRUDE_CARVE);
        d.dragStroke(WALL_X0 + 3, 2, WALL_Z, Face.SOUTH, WALL_X0 + 3.5, 2.5, WALL_Z - 0.8, 900, 0);
        d.hold(RESULT_MS);
        d.edit(() -> d.key(KeyAction.UNDO));
        d.pause(600);
        d.say(EXTRUDE_SMEAR);
        d.dragStroke(WALL_X0 + 3, 2, WALL_Z, Face.SOUTH, WALL_X0 + 6.5, 2.5, WALL_Z + 1, 900, GLFW.GLFW_MOD_ALT);
        d.hold(RESULT_MS);
    }

    // ---- 11 ----

    private static void fluid(Demo d) {
        DemoStage s = d.stage();
        d.view(s.x(BASIN_X0 + 3) + 1.0, s.y(9), s.z(BASIN_Z0 - 8) + 0.5, s.x(BASIN_X0 + 3) + 1.0, s.y(0),
                s.z(BASIN_Z1) + 0.5);
        d.say(FLUID_FLOOD);
        d.selectTool(ToolId.FLUID);
        d.setting(ToolId.FLUID, "mode", "FLOOD");
        d.setting(ToolId.FLUID, "fluid", "WATER");
        d.aim(BASIN_X0 + 3, 1, BASIN_Z1, Face.NORTH);
        d.hold(SHORT_MS);
        d.edit(d::click);
        d.hold(RESULT_MS);
        d.say(FLUID_DRAIN);
        d.setting(ToolId.FLUID, "mode", "DRAIN");
        d.aim(BASIN_X0 + 3, 1, BASIN_Z0 + 3, Face.UP);
        d.hold(SHORT_MS);
        d.edit(d::click);
        d.hold(RESULT_MS);
        d.say(FLUID_BALL);
        d.setting(ToolId.FLUID, "mode", "BALL");
        d.setting(ToolId.FLUID, "radius", 3);
        d.view(s.x(BALL_X) + 0.5, s.y(10), s.z(BALL_Z + 11) + 0.5, s.x(BALL_X) + 0.5, s.y(0), s.z(BALL_Z) + 0.5);
        d.stroke(BALL_X, -1, BALL_Z, Face.UP, 150);
        d.hold(RESULT_MS);
        d.setting(ToolId.FLUID, "mode", "FLOOD");
    }

    // ---- 12 ----

    private static void scatter(Demo d) {
        DemoStage s = d.stage();
        d.view(s.x(TREE_X) + 0.5, s.y(8), s.z(TREE_Z + 13) + 0.5, s.x(TREE_X) + 0.5, s.y(2), s.z(TREE_Z) + 0.5);
        d.say(SCATTER_COPY);
        d.selectTool(ToolId.SELECT);
        d.pointAt(s.x(TREE_X) + 0.5, s.y(3) + 0.5, s.z(TREE_Z) + 2.5);
        d.onClient(() -> d.ctx().setSelection(s.box(TREE_X - 2, 0, TREE_Z - 2, TREE_X + 2, 5, TREE_Z + 2)));
        d.hold(SHORT_MS);
        copy(d);
        d.pause(800);
        d.selectTool(ToolId.SCATTER);
        ScatterTool tool = (ScatterTool) d.onClient(() -> d.ctx().tools().get(ToolId.SCATTER).orElseThrow());
        d.onClient(() -> {
            tool.replaceMix(List.of());
            d.ctx().session().ifPresent(session -> tool.addClipboard(session, "tree"));
            tool.addBlock(BlockDescriptor.parse("minecraft:poppy"));
        });
        d.setting(ToolId.SCATTER, "radius", 6);
        d.setting(ToolId.SCATTER, "density_mode", "PERCENT");
        d.setting(ToolId.SCATTER, "density", 8.0);
        d.setting(ToolId.SCATTER, "spacing", 3);
        d.onClient(() -> d.ctx().setSelection(null));
        d.say(SCATTER_PAINT);
        d.view(s.x(MOUND2_X) + 0.5, s.y(12), s.z(MOUND2_Z + 13) + 0.5, s.x(MOUND2_X) + 0.5, s.y(2),
                s.z(MOUND2_Z) + 0.5);
        int startX = MOUND2_X - 8;
        int endX = MOUND2_X + 8;
        d.aim(startX, moundTop(startX, MOUND2_Z + 2), MOUND2_Z + 2, Face.UP);
        d.press(0);
        d.dragTo(endX + 0.5, moundTop(endX, MOUND2_Z + 2) + 1, MOUND2_Z + 2.5, 1_600);
        d.release();
        d.driver().until(15_000, () -> tool.drawPlan().isPresent());
        d.hold(RESULT_MS);
        d.say(SCATTER_PLACE);
        d.edit(() -> d.key(KeyAction.COMMIT));
        d.hold(RESULT_MS);
    }

    // ---- 13 ----

    private static void tinker(Demo d) {
        DemoStage s = d.stage();
        d.view(s.x(STAIRS_X) + 0.5, s.y(6), s.z(STAIRS_Z + 8) + 0.5, s.x(STAIRS_X) + 0.5, s.y(0), s.z(STAIRS_Z) + 0.5);
        d.say(TINKER_AIM);
        d.selectTool(ToolId.TINKER);
        TinkerTool tool = (TinkerTool) d.onClient(() -> d.ctx().tools().get(ToolId.TINKER).orElseThrow());
        // The stair faces south: its raised half is on the south, so the top face is aimed at there (its centre
        // lies on the step's edge).
        d.aimWithin(STAIRS_X, 0, STAIRS_Z, Face.UP, 0.5, 1.0, 0.75);
        d.hold(SHORT_MS);
        d.edit(() -> d.scroll(1, 0));
        d.pause(900);
        d.edit(() -> d.scroll(1, 0));
        d.hold(SHORT_MS);
        d.say(TINKER_PANEL);
        d.click();
        if (!d.driver().until(5_000, () -> d.ui().windows().isOpen(EditorWindows.TINKER))) {
            throw new CheckDriver.Failed("the click did not open the Tinker panel");
        }
        d.hold(SHORT_MS);
        List<Dropdown> dropdowns = d.nodes(EditorWindows.TINKER, Dropdown.class);
        if (!dropdowns.isEmpty()) {
            Dropdown<?> dropdown = dropdowns.get(0);
            d.clickNode(dropdown);
            d.pause(600);
            List<Label> options = d.popupNodes(Label.class);
            int selected = d.onClient(() -> dropdown.options().indexOf(dropdown.selected()));
            if (!options.isEmpty()) {
                Label next = options.get((Math.max(0, selected) + 1) % options.size());
                d.edit(() -> d.clickNode(next));
            }
            d.hold(RESULT_MS);
        }
        d.closeWindow(EditorWindows.TINKER);
        d.say(TINKER_ENTITY);
        d.view(s.x(STAND_X) + 0.5, s.y(4), s.z(STAND_Z + 7) + 0.5, s.x(STAND_X) + 0.5, s.y(1), s.z(STAND_Z) + 0.5);
        d.pointAt(s.x(STAND_X) + 0.5, s.y(1) + 0.2, s.z(STAND_Z) + 0.5);
        d.frames(5);
        boolean onStand = d.onClient(() -> tool.controller().hovered()
                .filter(target -> target instanceof TinkerController.EntityTarget).isPresent());
        if (!onStand) {
            d.pointAt(s.x(STAND_X) + 0.5, s.y(1) + 0.6, s.z(STAND_Z) + 0.5);
            d.frames(5);
        }
        d.pause(800);
        for (int i = 0; i < 4; i++) {
            d.driver().scroll(1, 0);
            d.pause(500);
        }
        d.hold(RESULT_MS);
    }

    // ---- 14 ----

    private static void library(Demo d) {
        DemoStage s = d.stage();
        d.view(s.x(TREE_X) + 0.5, s.y(8), s.z(TREE_Z + 13) + 0.5, s.x(TREE_X) + 0.5, s.y(2), s.z(TREE_Z) + 0.5);
        d.selectTool(ToolId.SELECT);
        d.onClient(() -> d.ctx().setSelection(s.box(TREE_X - 2, 0, TREE_Z - 2, TREE_X + 2, 5, TREE_Z + 2)));
        d.pause(600);
        copy(d);
        d.say(LIBRARY_SAVE);
        d.clickMenuRow(EditorUi.MENU_FILE, "sculptory.command.file.save_selection");
        d.pause(500);
        List<TextInput> fields = d.popupNodes(TextInput.class);
        if (!fields.isEmpty()) {
            d.onClient(() -> fields.get(0).selectAll());
        }
        d.typeSlowly("demo_tree.schem", 90);
        d.pause(800);
        int mark = d.driver().toastMark();
        d.keyCode(GLFW.GLFW_KEY_ENTER, 0);
        d.driver().awaitToast(mark, "sculptory.notice.saved", 15_000);
        d.park();
        d.hold(SHORT_MS);
        d.say(LIBRARY_WINDOW);
        d.key(KeyAction.LIBRARY);
        d.pause(3_500);
        d.key(KeyAction.LIBRARY);
        d.pause(600);
        d.say(LIBRARY_EXPORT);
        d.clickMenuRow(EditorUi.MENU_FILE, "sculptory.command.file.export_clipboard");
        d.pause(500);
        List<TextInput> exportFields = d.popupNodes(TextInput.class);
        if (!exportFields.isEmpty()) {
            d.onClient(() -> exportFields.get(0).selectAll());
        }
        d.typeSlowly("demo_tree", 90);
        d.hold(SHORT_MS);
        int exportMark = d.driver().toastMark();
        d.keyCode(GLFW.GLFW_KEY_ENTER, 0);
        d.driver().awaitToast(exportMark, "sculptory.notice.exported", 15_000);
        d.park();
        d.hold(RESULT_MS);
        d.onClient(() -> d.ctx().setSelection(null));
    }

    // ---- 15 ----

    private static void history(Demo d) {
        DemoStage s = d.stage();
        d.view(s.x(FILLS_X + 8) + 0.5, s.y(9), s.z(FILLS_Z + 14) + 0.5, s.x(FILLS_X + 8) + 0.5, s.y(0),
                s.z(FILLS_Z) + 0.5);
        // Three quick edits, each a named step.
        d.selectTool(ToolId.SHAPE);
        d.setting(ToolId.SHAPE, "kind", "SPHERE");
        d.setting(ToolId.SHAPE, "radius", 2);
        d.setting(ToolId.SHAPE, "blocks", "ACTIVE_BLOCK");
        d.activeBlock(STONE_BRICKS);
        for (int i = 0; i < 3; i++) {
            d.aim(FILLS_X + i * 6, -1, FILLS_Z, Face.UP);
            d.edit(d::click);
            d.pause(400);
        }
        d.say(HISTORY_WINDOW);
        d.key(KeyAction.HISTORY);
        d.hold(RESULT_MS);
        d.say(HISTORY_UNDO);
        clickHistoryRow(d, HistoryWindow.Entry.Kind.UNDO, 2);
        d.driver().awaitIdle("the jump back");
        d.hold(RESULT_MS);
        d.say(HISTORY_REDO);
        clickHistoryRow(d, HistoryWindow.Entry.Kind.REDO, -1);
        d.driver().awaitIdle("the jump forward");
        d.hold(RESULT_MS);
        d.key(KeyAction.HISTORY);
        d.park();
    }

    /** Clicks the History window's row of the entry of {@code kind} and index ({@code -1}: the furthest redo). */
    private static void clickHistoryRow(Demo d, HistoryWindow.Entry.Kind kind, int index) {
        HistoryWindow window = d.onClient(() -> d.ui().historyWindow());
        List<ListView> lists = d.nodes(EditorWindows.HISTORY, ListView.class);
        if (lists.isEmpty()) {
            throw new CheckDriver.Failed("the History window shows no list");
        }
        ListView<?> list = lists.get(0);
        double[] at = d.onClient(() -> {
            d.driver().relayout();
            List<HistoryWindow.Entry> entries = window.shown();
            int row = -1;
            for (int i = 0; i < entries.size(); i++) {
                HistoryWindow.Entry entry = entries.get(i);
                if (entry.kind() == kind && (index < 0 || entry.index() == index)) {
                    row = i;
                    break;
                }
            }
            if (row < 0) {
                throw new CheckDriver.Failed("no " + kind + " entry " + index + " in the history: " + entries);
            }
            UiContext ctx = d.ui().windows().context();
            int height = list.rowHeight(ctx);
            Rect bounds = list.bounds();
            double y = bounds.y() + (row - list.firstVisibleIndex()) * height + height / 2.0;
            float factor = d.ui().uiScale().factor();
            return new double[] {(bounds.x() + bounds.width() / 2.0) * factor, y * factor};
        });
        d.moveTo(at[0], at[1], Demo.POINTER_MS);
        d.pause(300);
        d.click();
    }

    // ---- 16 ----

    private static void tutorial(Demo d) {
        DemoStage s = d.stage();
        d.view(s.x(FILLS_X + 14) + 0.5, s.y(9), s.z(FILLS_Z + 16) + 0.5, s.x(FILLS_X + 14) + 0.5, s.y(0),
                s.z(FILLS_Z + 4) + 0.5);
        d.say(TUTORIAL_OPEN);
        d.clickMenuRow(EditorUi.MENU_HELP, "sculptory.command.help.tutorial");
        d.pause(2_500);
        Node row = d.onClient(() -> d.ui().tutorial().window().lessonRow("terrain"));
        d.say(TUTORIAL_LESSON);
        d.clickNode(row);
        d.park();
        d.hold(RESULT_MS);
        TutorialRunner runner = d.onClient(() -> d.ui().tutorial().runner());
        d.key(KeyAction.toolSlot(2));
        d.driver().until(5_000, () -> runner.stepIndex() >= 1);
        d.say(TUTORIAL_DONE);
        d.stroke(FILLS_X + 14, -1, FILLS_Z + 4, Face.UP, 400);
        d.driver().until(5_000, () -> runner.stepIndex() >= 2);
        d.hold(RESULT_MS);
        Node exit = d.onClient(() -> d.ui().tutorial().card().exitButton());
        d.clickNode(exit);
        d.park();
        d.hold(SHORT_MS);
    }

    // ---- 17 ----

    private static void wiki(Demo d) {
        d.say(WIKI_OPEN);
        d.clickMenuRow(EditorUi.MENU_HELP, "sculptory.command.help.wiki");
        d.pause(2_500);
        WikiWindow wiki = d.onClient(() -> d.ui().wikiWindow());
        d.say(WIKI_PAGE);
        Optional<double[]> link = d.onClient(() -> {
            d.driver().relayout();
            UiContext ctx = d.ui().windows().context();
            Rect column = wiki.pageView().columnBounds(ctx);
            for (int y = column.y() + 2; y < column.bottom(); y += 3) {
                for (int x = column.x() + 2; x < column.right(); x += 5) {
                    Optional<dev.sculptory.fabric.client.editor.wiki.WikiLink> at = wiki.pageView()
                            .linkAt(ctx, x, y);
                    if (at.isPresent() && "terrain-brushes".equals(at.get().pageId())) {
                        float factor = d.ui().uiScale().factor();
                        return Optional.of(new double[] {x * factor, y * factor});
                    }
                }
            }
            return Optional.empty();
        });
        if (link.isPresent()) {
            d.moveTo(link.get()[0], link.get()[1], Demo.POINTER_MS);
            d.pause(300);
            d.click();
        } else {
            d.onClient(() -> d.ui().openWiki("terrain-brushes", null));
        }
        d.hold(RESULT_MS);
        Rect page = d.onClient(() -> {
            d.driver().relayout();
            return wiki.pagePane().bounds();
        });
        d.hoverUi(page.x() + page.width() / 2.0, page.y() + page.height() / 2.0, 300);
        d.scroll(-3, 0);
        d.hold(SHORT_MS);
        List<Rect> pictures = d.onClient(() -> wiki.pageView().pictureBounds(d.ui().windows().context()));
        if (!pictures.isEmpty()) {
            d.say(WIKI_PICTURE);
            Rect first = pictures.get(0);
            d.hoverUi(first.x() + first.width() / 2.0, first.y() + first.height() / 2.0, 300);
            d.click();
            d.pause(2_500);
            d.closePopup();
            d.pause(600);
        }
        d.clickNode(d.onClient(wiki::backButton));
        d.hold(SHORT_MS);
        d.clickNode(d.onClient(wiki::homeButton));
        d.hold(SHORT_MS);
        d.say(WIKI_SEARCH);
        if (!d.onClient(wiki::isListShown)) {
            d.clickNode(d.onClient(wiki::pagesButton));
            d.pause(500);
        }
        d.clickNode(d.onClient(wiki::searchBox));
        d.typeSlowly("sym", 160);
        d.hold(RESULT_MS);
        d.closeWindow(EditorWindows.WIKI);
        // A click that went past the window to the world may have selected a block.
        d.onClient(() -> d.ctx().setSelection(null));
        d.park();
    }

    // ---- 18 ----

    private static void builder(Demo d) {
        DemoStage s = d.stage();
        d.say(BUILDER_CLOSE);
        d.view(s.x(92) + 0.5, s.y(3) + 0.5, s.z(BULLDOZE_Z + 12) + 0.5, s.x(92) + 0.5, s.y(1) + 0.5,
                s.z(BULLDOZE_Z) + 0.5);
        d.closeEditor();
        d.hold(SHORT_MS);
        d.say(BUILDER_RING);
        d.openRing();
        d.hold(SHORT_MS);
        d.clickPower(BuilderPower.LONG_REACH);
        d.clickPower(BuilderPower.PLACE_IN_AIR);
        d.closeRing();
        d.note(BUILDER_POWERS_ON);
        d.hold(SHORT_MS);
        d.say(BUILDER_PLACE);
        d.selectHotbar(0);
        float[] yaws = {-20f, -8f, 4f, 16f};
        for (float yaw : yaws) {
            d.turnTo(yaw, -22f, 500);
            d.useItem();
            d.pause(400);
        }
        d.hold(SHORT_MS);
        d.say(BUILDER_BULLDOZER);
        d.openRing();
        d.clickPower(BuilderPower.BULLDOZER);
        d.closeRing();
        d.lookAt(s.x(BULLDOZE_X0) + 0.5, s.y(1) + 0.5, s.z(BULLDOZE_Z) + 0.5, 700);
        d.holdAttack(true);
        d.lookAt(s.x(BULLDOZE_X1) + 0.5, s.y(1) + 0.5, s.z(BULLDOZE_Z) + 0.5, 2_400);
        d.lookAt(s.x(BULLDOZE_X1) + 0.5, s.y(2) + 0.5, s.z(BULLDOZE_Z) + 0.5, 500);
        d.lookAt(s.x(BULLDOZE_X0) + 0.5, s.y(2) + 0.5, s.z(BULLDOZE_Z) + 0.5, 2_400);
        d.holdAttack(false);
        d.hold(SHORT_MS);
        d.say(BUILDER_UNDO);
        for (int i = 0; i < 2; i++) {
            d.holdKeys(Set.of(GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_Z));
            d.pause(250);
            d.holdKeys(Set.of());
            d.pause(1_200);
        }
        d.say(BUILDER_TINKER);
        d.openRing();
        d.clickPower(BuilderPower.TINKER);
        d.closeRing();
        d.view(s.x(STAIRS_X) + 0.5, s.y(3) + 0.5, s.z(STAIRS_Z + 6) + 0.5, s.x(STAIRS_X) + 0.5, s.y(0) + 0.6,
                s.z(STAIRS_Z) + 0.5);
        d.holdKeys(Set.of(GLFW.GLFW_KEY_LEFT_ALT));
        d.hold(SHORT_MS);
        for (int i = 0; i < 2; i++) {
            d.onClient(() -> d.builder().scrolled(1));
            d.pause(900);
        }
        d.holdKeys(Set.of());
        d.hold(SHORT_MS);
        d.say(BUILDER_OFF);
        d.openRing();
        for (BuilderPower power : List.of(BuilderPower.LONG_REACH, BuilderPower.PLACE_IN_AIR,
                BuilderPower.BULLDOZER, BuilderPower.TINKER)) {
            if (d.onClient(() -> d.builder().powers().on(power))) {
                d.clickPower(power);
            }
        }
        d.closeRing();
        d.hold(SHORT_MS);
        if (d.screenIs(RingScreen.class)) {
            d.onClient(() -> d.client().setScreen(null));
        }
    }

    // ---- 19 ----

    private static void closing(Demo d) {
        d.openEditor();
        d.pause(600);
        d.say(CLOSING_UNDO);
        for (int i = 0; i < 4; i++) {
            d.edit(() -> d.key(KeyAction.UNDO));
            d.pause(700);
        }
        d.pause(800);
        overview(d, 2_600);
        d.note(CLOSING_TITLE, d.translate(NAME));
        d.pause(1_500);
        d.closeEditor();
        d.pause(4_000);
    }
}
