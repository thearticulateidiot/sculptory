package dev.sculptory.fabric.client.editor.demo;

import static dev.sculptory.fabric.client.editor.demo.DemoCaptions.*;
import static dev.sculptory.fabric.client.editor.demo.DemoStage.*;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.Box;
import dev.sculptory.core.mask.MaskEntry;
import dev.sculptory.core.scatter.FeatureCatalog;
import dev.sculptory.fabric.SculptoryMod;
import dev.sculptory.fabric.client.builder.RingScreen;
import dev.sculptory.fabric.client.editor.blocks.BlockChip;
import dev.sculptory.fabric.client.editor.check.CheckDriver;
import dev.sculptory.fabric.client.editor.hud.EditorUi;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.mask.EditMaskModel;
import dev.sculptory.fabric.client.editor.mask.MaskChip;
import dev.sculptory.fabric.client.editor.mask.RuleKind;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tool.WorldCursor.Face;
import dev.sculptory.fabric.client.editor.tools.place.PlaceTool;
import dev.sculptory.fabric.client.editor.tools.scatter.ScatterTool;
import dev.sculptory.fabric.client.editor.tools.tinker.TinkerTool;
import dev.sculptory.fabric.client.editor.tutorial.TutorialRunner;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.widget.AbstractButton;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.editor.ui.widget.Dropdown;
import dev.sculptory.fabric.client.editor.ui.widget.Label;
import dev.sculptory.fabric.client.editor.ui.widget.ListView;
import dev.sculptory.fabric.client.editor.ui.widget.Slider;
import dev.sculptory.fabric.client.editor.ui.widget.TextInput;
import dev.sculptory.fabric.client.editor.ui.widget.Toggle;
import dev.sculptory.fabric.client.editor.ui.window.Window;
import dev.sculptory.fabric.client.editor.windows.EditorWindows;
import dev.sculptory.fabric.client.editor.windows.HistoryWindow;
import dev.sculptory.fabric.client.editor.windows.WikiWindow;
import dev.sculptory.fabric.client.tinker.TinkerController;
import dev.sculptory.protocol.v2.BuilderPower;
import dev.sculptory.protocol.v2.S2C;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.lwjgl.glfw.GLFW;

/**
 * The demonstration itself: a walkthrough in seven chapters on the {@link DemoStage}, at a pace a viewer can follow.
 * It opens in builder mode, then shows how to learn the editor, then history, copy and paste and the shared library,
 * then the tools by job: shaping land, building, detailing. Each step stands on the stage as built (a retake with
 * {@code -DemoFrom} starts at any step); the few that need earlier work (a clipboard, history entries) make it
 * themselves. The words are in {@link DemoCaptions}.
 */
public final class DemoScript {
    /** A beat on a result. */
    private static final long RESULT_MS = 2_400;
    private static final long SHORT_MS = 1_600;
    private static final String BRICKS = "minecraft:bricks";
    private static final String STONE_BRICKS = "minecraft:stone_bricks";
    private static final String OAK_PLANKS = "minecraft:oak_planks";
    private static final List<String> MIX = List.of("minecraft:stone", "minecraft:cobblestone",
            "minecraft:mossy_cobblestone");
    private static final List<Integer> MIX_WEIGHTS = List.of(3, 2, 1);

    private DemoScript() {}

    /** The chapters, in order. */
    public static List<DemoChapter<Demo>> chapters() {
        return List.of(
                DemoChapter.of("builder", CHAPTER_BUILDER,
                        DemoStep.outsideEditor("title", DemoScript::title),
                        DemoStep.outsideEditor("builder", DemoScript::builder)),
                DemoChapter.of("learning", CHAPTER_LEARNING,
                        DemoStep.of("screen", DemoScript::screen),
                        DemoStep.of("tutorial", DemoScript::tutorial),
                        DemoStep.of("wiki", DemoScript::wiki)),
                DemoChapter.of("library", CHAPTER_LIBRARY,
                        DemoStep.of("history", DemoScript::history),
                        DemoStep.of("paste", DemoScript::paste),
                        DemoStep.of("library", DemoScript::library)),
                DemoChapter.of("land", CHAPTER_LAND,
                        DemoStep.of("brushes", DemoScript::brushes),
                        DemoStep.of("fluid", DemoScript::fluid)),
                DemoChapter.of("building", CHAPTER_BUILDING,
                        DemoStep.of("select", DemoScript::select),
                        DemoStep.of("overlay", DemoScript::overlay),
                        DemoStep.of("shape", DemoScript::shape),
                        DemoStep.of("symmetry", DemoScript::symmetry),
                        DemoStep.of("generate", DemoScript::generate),
                        DemoStep.of("extrude", DemoScript::extrude)),
                DemoChapter.of("detailing", CHAPTER_DETAILING,
                        DemoStep.of("paint", DemoScript::paint),
                        DemoStep.of("scatter", DemoScript::scatter),
                        DemoStep.of("tinker", DemoScript::tinker)),
                DemoChapter.of("closing", CHAPTER_CLOSING,
                        DemoStep.outsideEditor("closing", DemoScript::closing)));
    }

    /** The steps of every chapter, in order. */
    public static List<DemoStep<Demo>> steps() {
        return chapters().stream().flatMap(chapter -> chapter.steps().stream()).toList();
    }

    /** The view over the whole stage the demo opens and closes with. */
    static void overview(Demo d, long ms) {
        d.glide(d.stage().x(56) + 0.5, d.stage().y(52), d.stage().z(136) + 0.5, d.stage().x(56) + 0.5,
                d.stage().y(0), d.stage().z(52) + 0.5, ms);
    }

    // ---- title ----

    /** The stage from above, the title over it (with captions); the editor stays closed for builder mode. */
    private static void title(Demo d) {
        d.note(TITLE, d.translate(NAME));
        d.pause(3_000);
    }

    // ---- screen ----

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
        // The Mask chip, its tooltip naming Ctrl+M (the detailing chapter uses it).
        d.say(SCREEN_MASK);
        d.hover(maskChip(d), RESULT_MS);

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
        d.typeSlowly("replace", 140);
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

    // ---- select ----

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
        d.say(SELECT_UNDO);
        d.edit(() -> d.key(KeyAction.UNDO));
        d.pause(1_000);
        d.edit(() -> d.key(KeyAction.UNDO));
        d.hold(RESULT_MS);
        d.onClient(() -> d.ctx().setSelection(null));

        // Replace on the oak porch: planks, stairs and slabs at once, each keeping its shape; undone, then the whole
        // oak family at once.
        d.view(s.x(PORCH_X0 + 3) + 0.5, s.y(7), s.z(PORCH_Z1 + 11) + 0.5, s.x(PORCH_X0 + 3) + 0.5, s.y(1),
                s.z(PORCH_Z0 + 2) + 0.5);
        d.say(SELECT_REPLACE);
        d.aim(PORCH_X0, 0, PORCH_Z1, Face.SOUTH);
        d.press(0);
        d.dragTo(PORCH_X1 + 0.5, PORCH_ROOF + 1, PORCH_Z0 + 0.5, 1_000);
        d.release();
        // The stairs stand a row in front of the floor: the box takes them in.
        d.onClient(() -> d.ctx().setSelection(s.box(PORCH_X0, 0, PORCH_Z0, PORCH_X1, PORCH_ROOF, PORCH_Z1 + 1)));
        d.pause(600);
        replace(d, "minecraft:spruce_planks", List.of(OAK_PLANKS, "minecraft:oak_stairs", "minecraft:oak_slab"),
                false);
        d.edit(() -> d.key(KeyAction.UNDO));
        d.pause(1_000);
        d.say(SELECT_FAMILY);
        replace(d, "minecraft:dark_oak_planks", List.of(OAK_PLANKS), true);
        d.onClient(() -> d.ctx().setSelection(null));
    }

    /**
     * The Replace dialog on the selection: To is {@code to}, From is {@code from} (picked in the block-set picker),
     * Keep shape on, and Whole family as {@code family}; then Replace.
     */
    private static void replace(Demo d, String to, List<String> from, boolean family) {
        d.clickButton(EditorWindows.SELECTION, "sculptory.op.replace_dots");
        d.pause(600);
        BlockChip toChip = d.popupNodes(BlockChip.class).stream().findFirst()
                .orElseThrow(() -> new CheckDriver.Failed("the Replace dialog has no To block"));
        d.onClient(() -> toChip.setBlock(BlockDescriptor.parse(to)));
        String fromTip = d.translate("sculptory.selection.replace.from.tooltip");
        d.clickNode(d.popupNodes(Button.class).stream().filter(b -> fromTip.equals(b.tooltip())).findFirst()
                .orElseThrow(() -> new CheckDriver.Failed("the Replace dialog has no From button")));
        d.pause(500);
        d.pickBlockSet(from);
        d.pause(500);
        // Palette, Keep shape, Whole family.
        List<Toggle> toggles = d.popupNodes(Toggle.class);
        if (toggles.size() < 3) {
            throw new CheckDriver.Failed("the Replace dialog shows " + toggles.size() + " switches, not 3");
        }
        if (toggles.get(2).value() != family) {
            d.clickNode(toggles.get(2));
        }
        if (!family && !toggles.get(1).value()) {
            d.clickNode(toggles.get(1));
        }
        d.hold(SHORT_MS);
        String replace = d.translate("sculptory.op.replace");
        Button confirm = d.popupNodes(Button.class).stream().filter(b -> b.text().equals(replace)).findFirst()
                .orElseThrow(() -> new CheckDriver.Failed("no Replace button in the dialog"));
        d.edit(() -> d.clickNode(confirm));
        d.park();
        d.hold(RESULT_MS);
    }

    /** Clicks the button labelled {@code labelKey} in the dialog on top, as an edit. */
    private static void confirm(Demo d, String labelKey) {
        String text = d.translate(labelKey);
        Button button = d.popupNodes(Button.class).stream().filter(b -> b.text().equals(text)).findFirst()
                .orElseThrow(() -> new CheckDriver.Failed("no \"" + text + "\" button in the dialog"));
        d.edit(() -> d.clickNode(button));
        d.park();
    }

    // ---- overlay ----

    /** Naturalize, then Overlay, on a bare stone hill. */
    private static void overlay(Demo d) {
        DemoStage s = d.stage();
        d.view(s.x(HILL_X) + 0.5, s.y(11), s.z(HILL_Z + 15) + 0.5, s.x(HILL_X) + 0.5, s.y(1), s.z(HILL_Z) + 0.5);
        d.say(OVERLAY_SELECT);
        d.selectTool(ToolId.SELECT);
        d.aim(HILL_X - 2, hillTop(HILL_X - 2, HILL_Z + HILL_RADIUS - 2), HILL_Z + HILL_RADIUS - 2, Face.UP);
        d.press(0);
        d.dragTo(HILL_X + HILL_RADIUS + 0.5, HILL_HEIGHT + 1, HILL_Z - HILL_RADIUS + 0.5, 1_000);
        d.release();
        // From the hill's first layer up: the grass around it stays out (no snow in the box's corners).
        d.onClient(() -> d.ctx().setSelection(s.box(HILL_X - HILL_RADIUS, 0, HILL_Z - HILL_RADIUS,
                HILL_X + HILL_RADIUS, HILL_HEIGHT + 1, HILL_Z + HILL_RADIUS)));
        d.hold(SHORT_MS);
        d.say(OVERLAY_NATURALIZE);
        d.clickButton(EditorWindows.SELECTION, "sculptory.op.naturalize_dots");
        d.hold(SHORT_MS);
        confirm(d, "sculptory.op.naturalize");
        d.hold(RESULT_MS);
        d.say(OVERLAY_OVERLAY);
        d.activeBlock("minecraft:snow");
        d.clickButton(EditorWindows.SELECTION, "sculptory.op.overlay_dots");
        d.pause(1_000);
        confirm(d, "sculptory.op.overlay");
        d.hold(RESULT_MS);
        d.onClient(() -> d.ctx().setSelection(null));
    }

    // ---- brushes ----

    private static void brushes(Demo d) {
        DemoStage s = d.stage();
        // J: from above the field to the cliff top in one jump.
        d.view(s.x(CLIFF_X - 26) + 0.5, s.y(CLIFF_TOP + 9), s.z(58) + 0.5, s.x(CLIFF_X + 8) + 0.5, s.y(CLIFF_TOP),
                s.z(58) + 0.5);
        d.say(BRUSH_JUMP);
        d.aim(CLIFF_X + 8, CLIFF_TOP, 58, Face.UP);
        d.pause(600);
        d.key(KeyAction.JUMP);
        d.frames(10);
        d.hold(SHORT_MS);
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

    // ---- paint ----

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
        mask(d);
    }

    /**
     * The global mask: a rule "is grass block" made in the Mask window, then a stroke across the gravel path that
     * leaves the path alone. The player's own rules are saved to their config, so they are put back afterwards.
     */
    private static void mask(Demo d) {
        DemoStage s = d.stage();
        List<MaskEntry> before = d.onClient(() -> EditMaskModel.global().rules());
        try {
            d.onClient(() -> {
                EditMaskModel.global().setOn(false);
                EditMaskModel.global().setRules(List.of());
            });
            d.view(s.x(18) + 0.5, s.y(12), s.z(PATH_Z + 15) + 0.5, s.x(18) + 0.5, s.y(0), s.z(PATH_Z) + 0.5);
            d.say(PAINT_MASK);
            MaskChip chip = maskChip(d);
            d.clickNode(chip);
            if (!d.driver().until(1_500, () -> d.ui().windows().isOpen(EditorWindows.MASK))) {
                String where = d.onClient(() -> {
                    Rect at = chip.bounds();
                    double x = at.x() + at.width() / 2.0;
                    double y = at.y() + at.height() / 2.0;
                    Node hit = d.ui().hud().hitTest(x, y);
                    return "chip " + at + ", editing " + d.ui().isEditing() + ", over windows "
                            + d.ui().windows().isMouseOverUi(x, y) + ", popup " + d.ui().windows().context().popups()
                            .isOpen() + ", HUD hit " + (hit == null ? "none" : hit.getClass().getSimpleName());
                });
                SculptoryMod.LOG.warn("Demo: Mask chip click: {}", where);
                SculptoryMod.LOG.warn("Demo: a click on the Mask chip did not open its window; opening it directly");
                d.onClient(() -> d.ui().toggleWindow(EditorWindows.MASK));
            }
            if (!d.driver().until(5_000, () -> d.ui().windows().isOpen(EditorWindows.MASK))) {
                throw new CheckDriver.Failed("the Mask chip did not open the Mask window");
            }
            d.pause(800);
            List<Dropdown> dropdowns = d.nodes(EditorWindows.MASK, Dropdown.class);
            if (dropdowns.isEmpty()) {
                throw new CheckDriver.Failed("the Mask window has no Add rule list");
            }
            @SuppressWarnings("unchecked")
            Dropdown<Optional<RuleKind>> add = (Dropdown<Optional<RuleKind>>) dropdowns.get(dropdowns.size() - 1);
            d.clickNode(add);
            d.pause(600);
            String is = d.translate(RuleKind.IS.nameKey());
            Optional<Label> choice = d.popupNodes(Label.class).stream().filter(l -> l.text().equals(is)).findFirst();
            if (choice.isPresent()) {
                d.clickNode(choice.get());
            } else {
                d.closePopup();
                d.onClient(() -> add.pick(Optional.of(RuleKind.IS)));
            }
            d.pause(800);
            String blocksTip = d.translate("sculptory.mask.blocks.tooltip");
            Button blocks = d.nodes(EditorWindows.MASK, Button.class).stream()
                    .filter(b -> blocksTip.equals(b.tooltip())).findFirst()
                    .orElseThrow(() -> new CheckDriver.Failed("the Is rule has no blocks button"));
            d.clickNode(blocks);
            d.pause(500);
            d.pickBlockSet(List.of("minecraft:grass_block"));
            d.pause(600);
            Toggle on = d.nodes(EditorWindows.MASK, Toggle.class).stream().findFirst()
                    .orElseThrow(() -> new CheckDriver.Failed("the Mask window has no On switch"));
            d.clickNode(on);
            if (!d.driver().until(3_000, () -> EditMaskModel.global().active())) {
                throw new CheckDriver.Failed("the mask did not switch on");
            }
            d.hold(SHORT_MS);
            d.closeWindow(EditorWindows.MASK);
            d.say(PAINT_MASK_STROKE);
            d.selectTool(ToolId.PALETTE);
            d.mix(ToolId.PALETTE, "palette", List.of("minecraft:moss_block", "minecraft:podzol",
                    "minecraft:coarse_dirt"), List.of(3, 2, 1));
            d.setting(ToolId.PALETTE, "pattern", "RANDOM");
            d.setting(ToolId.PALETTE, "radius", 4);
            d.dragStroke(PATH_X0 + 4, -1, PATH_Z, Face.UP, PATH_X1 - 3.5, 0, PATH_Z + 0.5, 1_800, 0);
            d.hold(RESULT_MS);
            d.say(PAINT_MASK_OFF);
            d.key(KeyAction.TOGGLE_MASK);
            d.hold(SHORT_MS);
        } finally {
            // Also after a failure: no picker or Mask window left over the next step's view.
            d.onClient(() -> {
                d.ui().windows().context().popups().closeAll();
                EditMaskModel.global().setOn(false);
                EditMaskModel.global().setRules(before);
            });
            d.closeWindow(EditorWindows.MASK);
        }
    }

    /** The Mask chip in the top bar. */
    private static MaskChip maskChip(Demo d) {
        return d.topBar(MaskChip.class, chip -> true, "Mask chip");
    }

    // ---- shape ----

    private static void shape(Demo d) {
        DemoStage s = d.stage();
        d.view(s.x(SHAPE_X + 6) + 0.5, s.y(10), s.z(SHAPE_Z) + 16.5, s.x(SHAPE_X + 6) + 0.5, s.y(1),
                s.z(SHAPE_Z) + 0.5);
        d.say(SHAPE_SPHERE);
        d.selectTool(ToolId.SHAPE);
        // Tool settings are kept between games: a run that stopped halfway through the line part left Line mode on.
        d.setting(ToolId.SHAPE, "draw", "SHAPES");
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

        // Line mode: a small sphere swept along a curve through four clicked points, a winding wall. From the north,
        // high above builder mode's floating planks (z 29), with the pasted houses out of the picture.
        d.view(s.x(68) + 0.5, s.y(20), s.z(26) + 0.5, s.x(68) + 0.5, s.y(0), s.z(36) + 0.5);
        d.say(SHAPE_LINE);
        d.setting(ToolId.SHAPE, "radius", 2);
        d.setting(ToolId.SHAPE, "draw", "LINE");
        d.setting(ToolId.SHAPE, "line.path", "CURVE");
        for (int[] point : CURVE) {
            d.aim(point[0], -1, point[1], Face.UP);
            d.pause(300);
            d.click();
            d.pause(500);
        }
        d.hold(SHORT_MS);
        d.say(SHAPE_LINE_BUILT);
        d.edit(() -> d.key(KeyAction.COMMIT));
        d.hold(RESULT_MS);
        clearPoints(d);
        d.setting(ToolId.SHAPE, "draw", "SHAPES");
        d.setting(ToolId.SHAPE, "radius", 4);
    }

    /** Esc: the line's points go (no point is being dragged). */
    private static void clearPoints(Demo d) {
        d.keyCode(GLFW.GLFW_KEY_ESCAPE, 0);
        d.pause(300);
    }

    // ---- symmetry ----

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

    // ---- paste ----

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

    // ---- generate ----

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

        // Line, hanging: a chain sagging from one post's top to the other's.
        int middle = (LINE_POST_X0 + LINE_POST_X1) / 2;
        // From high enough that the pillars' tops face the camera.
        d.view(s.x(middle) + 0.5, s.y(14), s.z(LINE_POST_Z + 15) + 0.5, s.x(middle) + 0.5, s.y(2),
                s.z(LINE_POST_Z) + 0.5);
        d.say(GENERATE_LINE);
        d.setting(ToolId.GENERATE, "kind", "LINE");
        d.setting(ToolId.GENERATE, "line.path", "HANGING");
        d.setting(ToolId.GENERATE, "line.sag", 4);
        d.setting(ToolId.GENERATE, "line.material", "BLOCK");
        d.setting(ToolId.GENERATE, "line.block", "minecraft:chain");
        for (int x : new int[] {LINE_POST_X0, LINE_POST_X1}) {
            d.aim(x, LINE_POST_TOP, LINE_POST_Z, Face.UP);
            d.pause(300);
            d.click();
            d.pause(600);
        }
        d.hold(SHORT_MS);
        d.say(GENERATE_ROAD_BUILT);
        d.edit(() -> d.key(KeyAction.COMMIT));
        d.hold(RESULT_MS);
        clearPoints(d);

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

    // ---- extrude ----

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

    // ---- fluid ----

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

    // ---- scatter ----

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
        // + Tree: the game's oak from the list, and its birch.
        d.say(SCATTER_TREES);
        FeatureCatalog.FeatureDef oak = feature("minecraft:oak");
        d.clickButton(EditorWindows.TOOL_SETTINGS, "sculptory.scatter.panel.add_tree");
        d.pause(800);
        if (!d.clickMenuItem(d.onClient(() -> tool.featureName(oak.id())))) {
            d.closePopup();
            d.onClient(() -> tool.addFeature(oak));
        }
        d.pause(500);
        d.onClient(() -> tool.addFeature(feature("minecraft:birch")));
        d.hold(SHORT_MS);
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
        // Back far enough to see the whole slope.
        d.view(s.x(MOUND2_X) + 0.5, s.y(22), s.z(MOUND2_Z + 34) + 0.5, s.x(MOUND2_X) + 0.5, s.y(1),
                s.z(MOUND2_Z) + 0.5);
        d.hold(SHORT_MS);
    }

    private static FeatureCatalog.FeatureDef feature(String id) {
        return FeatureCatalog.find(id)
                .orElseThrow(() -> new CheckDriver.Failed("no " + id + " in the feature catalog"));
    }

    // ---- tinker ----

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

    // ---- library ----

    /**
     * The shared library: the house saved into the starter kit, the kit's cottage, boulder and lamp post placed from
     * the Library window, a shared palette loaded into Palette Paint, and the clipboard exported.
     */
    private static void library(Demo d) {
        DemoStage s = d.stage();
        houseView(d);
        selectHouse(d);
        d.pause(600);
        copy(d);
        d.say(LIBRARY_SAVE);
        d.clickMenuRow(EditorUi.MENU_FILE, "sculptory.command.file.save_selection");
        d.pause(500);
        List<TextInput> fields = d.popupNodes(TextInput.class);
        if (!fields.isEmpty()) {
            d.onClient(() -> fields.get(0).selectAll());
        }
        d.typeSlowly(DemoLibrary.SAVED_HOUSE, 70);
        d.pause(800);
        int mark = d.driver().toastMark();
        d.keyCode(GLFW.GLFW_KEY_ENTER, 0);
        if (d.driver().awaitToast(mark, "sculptory.notice.saved", 15_000).isEmpty()) {
            throw new CheckDriver.Failed("the house was not saved" + d.driver().toastNote());
        }
        d.onClient(() -> d.ctx().setSelection(null));
        d.park();
        d.hold(SHORT_MS);

        // The Library window: the root, then the starter kit (a double click opens a folder).
        d.view(s.x(COTTAGE_X) + 0.5, s.y(12), s.z(COTTAGE_Z + 19) + 0.5, s.x(COTTAGE_X) + 0.5, s.y(1),
                s.z(COTTAGE_Z) + 0.5);
        d.say(LIBRARY_WINDOW);
        ListView<S2C.LibraryListing.Entry> list = openLibrary(d);
        int folder = d.awaitRow(list, entry -> entry.path().equals(DemoLibrary.FOLDER), DemoLibrary.FOLDER);
        d.hold(SHORT_MS);
        d.say(LIBRARY_FOLDER);
        d.clickListRow(list, folder, true);
        d.awaitRow(list, entry -> entry.path().equals(DemoLibrary.COTTAGE), DemoLibrary.COTTAGE);
        d.hold(RESULT_MS);
        d.say(LIBRARY_PLACE);
        placeFromLibrary(d, list, DemoLibrary.COTTAGE, COTTAGE_X, COTTAGE_Z);
        d.say(LIBRARY_MORE);
        placeFromLibrary(d, openLibrary(d), DemoLibrary.BOULDER, BOULDER_X, BOULDER_Z);
        placeFromLibrary(d, openLibrary(d), DemoLibrary.LAMP_POST, LAMP_X, LAMP_Z);

        // A shared palette into Palette Paint, then a path in front of the cottage.
        d.say(LIBRARY_PALETTE);
        d.selectTool(ToolId.PALETTE);
        d.setting(ToolId.PALETTE, "pattern", "RANDOM");
        d.setting(ToolId.PALETTE, "radius", 1);
        d.clickButton(EditorWindows.TOOL_SETTINGS, "sculptory.palette.load");
        d.pause(800);
        pickPalette(d, DemoLibrary.MOSSY_STONE);
        d.hold(SHORT_MS);
        d.dragStroke(COTTAGE_X - 4, -1, COTTAGE_Z + 7, Face.UP, COTTAGE_X + 6.5, 0, COTTAGE_Z + 7.5, 1_400, 0);
        d.hold(RESULT_MS);

        d.say(LIBRARY_EXPORT);
        d.clickMenuRow(EditorUi.MENU_FILE, "sculptory.command.file.export_clipboard");
        d.pause(500);
        List<TextInput> exportFields = d.popupNodes(TextInput.class);
        if (!exportFields.isEmpty()) {
            d.onClient(() -> exportFields.get(0).selectAll());
        }
        d.typeSlowly("house", 90);
        d.hold(SHORT_MS);
        int exportMark = d.driver().toastMark();
        d.keyCode(GLFW.GLFW_KEY_ENTER, 0);
        d.driver().awaitToast(exportMark, "sculptory.notice.exported", 15_000);
        d.park();
        d.hold(RESULT_MS);
    }

    /** Opens the Library window (L) if it is closed, and returns its list. */
    private static ListView<S2C.LibraryListing.Entry> openLibrary(Demo d) {
        if (!d.onClient(() -> d.ui().windows().isOpen(EditorWindows.LIBRARY))) {
            d.key(KeyAction.LIBRARY);
            d.pause(800);
        }
        @SuppressWarnings("unchecked")
        ListView<S2C.LibraryListing.Entry> list = (ListView<S2C.LibraryListing.Entry>) d
                .nodes(EditorWindows.LIBRARY, ListView.class).stream().findFirst()
                .orElseThrow(() -> new CheckDriver.Failed("the Library window shows no list"));
        return list;
    }

    /** Selects a schematic in the Library window, clicks Place, closes the window and places it at a floor spot. */
    private static void placeFromLibrary(Demo d, ListView<S2C.LibraryListing.Entry> list, String path, int x, int z) {
        DemoStage s = d.stage();
        int row = d.awaitRow(list, entry -> entry.path().equals(path), path);
        d.clickListRow(list, row, false);
        d.pause(500);
        PlaceTool place = d.onClient(() -> d.driver().controller().placeTool().orElseThrow());
        d.clickButton(EditorWindows.LIBRARY, "sculptory.library.place");
        if (!d.driver().until(20_000, () -> d.driver().controller().activePlaceTool().isPresent()
                && place.placement().isPresent())) {
            throw new CheckDriver.Failed("Place did not start placing " + path + d.driver().toastNote());
        }
        d.closeWindow(EditorWindows.LIBRARY);
        d.pointAt(s.x(x) + 0.5, s.y(0), s.z(z) + 0.5);
        d.pause(700);
        d.click();
        d.pause(600);
        d.edit(() -> d.key(KeyAction.COMMIT));
        d.hold(SHORT_MS);
    }

    /**
     * In the palette picker on top: up to the root if it starts elsewhere, into the folders on the way to
     * {@code path}, then the palette (a click opens or picks).
     */
    private static void pickPalette(Demo d, String path) {
        String up = d.translate("sculptory.palette.picker.up");
        for (int tries = 0; tries < 8; tries++) {
            @SuppressWarnings("unchecked")
            ListView<S2C.LibraryListing.Entry> list = (ListView<S2C.LibraryListing.Entry>) d
                    .popupNodes(ListView.class).stream().findFirst()
                    .orElseThrow(() -> new CheckDriver.Failed("the palette picker shows no list"));
            d.pause(600);
            List<S2C.LibraryListing.Entry> shown = d.onClient(() -> List.copyOf(list.items()));
            int row = -1;
            for (int i = 0; i < shown.size() && row < 0; i++) {
                String at = shown.get(i).path();
                if (at.equals(path) || (shown.get(i).folder() && path.startsWith(at + "/"))) {
                    row = i;
                }
            }
            if (row >= 0) {
                boolean picked = shown.get(row).path().equals(path);
                d.clickListRow(list, row, false);
                if (picked) {
                    d.pause(800);
                    return;
                }
                continue;
            }
            Button upButton = d.popupNodes(Button.class).stream().filter(b -> b.text().equals(up)).findFirst()
                    .orElseThrow(() -> new CheckDriver.Failed("the palette picker has no Up button"));
            d.clickNode(upButton);
        }
        throw new CheckDriver.Failed("the palette picker never showed " + path);
    }

    // ---- history ----

    /**
     * The History window over builder mode's edits: a jump back, a jump forward, then the top bar's arrows. A retake
     * that starts here makes three quick edits first.
     */
    private static void history(Demo d) {
        DemoStage s = d.stage();
        // Close on builder mode's work: the planks in the air and the stairs it turned, so each undo shows.
        // Close, from the south at their height: the row of planks across the middle of the picture (builder mode set
        // them 5 blocks north of where it stood, 3 up), the stairs it turned behind and below them.
        d.view(s.x(STAIRS_X) + 0.5, s.y(4), s.z(BUILDER_Z + 1) + 0.5, s.x(STAIRS_X) + 0.5, s.y(2),
                s.z(STAIRS_Z) + 0.5);
        d.say(HISTORY_WINDOW);
        d.key(KeyAction.HISTORY);
        d.hold(RESULT_MS);
        if (undoSteps(d) < 3) {
            d.key(KeyAction.HISTORY);
            quickEdits(d);
            d.key(KeyAction.HISTORY);
            d.hold(SHORT_MS);
        }
        d.say(HISTORY_UNDO);
        clickHistoryRow(d, HistoryWindow.Entry.Kind.UNDO, 2);
        d.driver().awaitIdle("the jump back");
        d.hold(RESULT_MS);
        d.say(HISTORY_REDO);
        // Forward to where it was: the three steps just undone (the tutorial's stroke, undone in its lesson, stays).
        clickHistoryRow(d, HistoryWindow.Entry.Kind.REDO, 2);
        d.driver().awaitIdle("the jump forward");
        d.hold(RESULT_MS);
        d.key(KeyAction.HISTORY);
        d.park();
        d.say(HISTORY_TOP_BAR);
        clickTopBarOrKey(d, "sculptory.topbar.undo", KeyAction.UNDO);
        d.hold(SHORT_MS);
        clickTopBarOrKey(d, "sculptory.topbar.redo", KeyAction.REDO);
        d.hold(SHORT_MS);
        d.park();
    }

    /** How many steps the History window offers to undo. */
    private static int undoSteps(Demo d) {
        return d.onClient(() -> (int) d.ui().historyWindow().shown().stream()
                .filter(entry -> entry.kind() == HistoryWindow.Entry.Kind.UNDO).count());
    }

    /** Three small spheres, each a named step, where they can be seen. */
    private static void quickEdits(Demo d) {
        DemoStage s = d.stage();
        d.view(s.x(FILLS_X + 8) + 0.5, s.y(9), s.z(FILLS_Z + 14) + 0.5, s.x(FILLS_X + 8) + 0.5, s.y(0),
                s.z(FILLS_Z) + 0.5);
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
        d.setting(ToolId.SHAPE, "radius", 4);
        d.setting(ToolId.SHAPE, "blocks", "PALETTE");
    }

    /** Clicks a top bar button as an edit, or presses its key when the bar had no room for it. */
    private static void clickTopBarOrKey(Demo d, String labelKey, KeyAction key) {
        String text = d.translate(labelKey);
        Button button = d.topBar(Button.class, b -> b.text().equals(text), "\"" + text + "\" button");
        boolean shown = d.onClient(() -> button.isShown() && !button.bounds().isEmpty());
        d.edit(() -> {
            if (shown) {
                d.clickNode(button);
            } else {
                d.key(key);
            }
        });
    }

    /** Clicks the History window's row of the entry of {@code kind} and index ({@code -1}: the furthest redo). */
    private static void clickHistoryRow(Demo d, HistoryWindow.Entry.Kind kind, int index) {
        HistoryWindow window = d.onClient(() -> d.ui().historyWindow());
        List<ListView> lists = d.nodes(EditorWindows.HISTORY, ListView.class);
        if (lists.isEmpty()) {
            throw new CheckDriver.Failed("the History window shows no list");
        }
        ListView<?> list = lists.get(0);
        int row = d.onClient(() -> {
            List<HistoryWindow.Entry> entries = window.shown();
            for (int i = 0; i < entries.size(); i++) {
                HistoryWindow.Entry entry = entries.get(i);
                if (entry.kind() == kind && (index < 0 || entry.index() == index)) {
                    return i;
                }
            }
            throw new CheckDriver.Failed("no " + kind + " entry " + index + " in the history: " + entries);
        });
        // A rest on the row first: its tooltip says how many steps the jump takes.
        double[] at = d.listRow(list, row);
        d.moveTo(at[0], at[1], Demo.POINTER_MS);
        d.pause(1_200);
        d.click();
    }

    // ---- tutorial ----

    /**
     * Help > Tutorial: the lesson groups, the terrain lesson started, its Learn more page, three steps ticked as they
     * are done, then Exit.
     */
    private static void tutorial(Demo d) {
        DemoStage s = d.stage();
        d.view(s.x(FILLS_X + 14) + 0.5, s.y(9), s.z(FILLS_Z + 16) + 0.5, s.x(FILLS_X + 14) + 0.5, s.y(0),
                s.z(FILLS_Z + 4) + 0.5);
        d.say(TUTORIAL_OPEN);
        d.clickMenuRow(EditorUi.MENU_HELP, "sculptory.command.help.tutorial");
        d.pause(2_000);
        d.say(TUTORIAL_GROUPS);
        for (String lesson : List.of("getting_around", "terrain", "library")) {
            Node row = d.onClient(() -> d.ui().tutorial().window().lessonRow(lesson));
            if (shown(d, row)) {
                d.hover(row, 1_100);
            }
        }
        Node row = d.onClient(() -> d.ui().tutorial().window().lessonRow("terrain"));
        d.say(TUTORIAL_LESSON);
        d.clickNode(row);
        d.park();
        d.hold(RESULT_MS);
        TutorialRunner runner = d.onClient(() -> d.ui().tutorial().runner());
        Button learnMore = d.onClient(() -> d.ui().tutorial().card().learnMoreButton());
        if (shown(d, learnMore)) {
            d.say(TUTORIAL_LEARN_MORE);
            d.clickNode(learnMore);
            d.pause(1_000);
            d.hold(RESULT_MS);
            d.closeWindow(EditorWindows.WIKI);
            d.park();
        }
        d.key(KeyAction.toolSlot(2));
        d.driver().until(5_000, () -> runner.stepIndex() >= 1);
        d.hold(SHORT_MS);
        d.say(TUTORIAL_DONE);
        d.stroke(FILLS_X + 14, -1, FILLS_Z + 4, Face.UP, 400);
        d.driver().until(5_000, () -> runner.stepIndex() >= 2);
        d.hold(SHORT_MS);
        d.setting(ToolId.RAISE, "radius", 6);
        d.driver().until(5_000, () -> runner.stepIndex() >= 3);
        d.hold(RESULT_MS);
        // The lesson's stroke goes again, so History shows builder mode's work next.
        d.edit(() -> d.key(KeyAction.UNDO));
        d.pause(600);
        d.say(TUTORIAL_EXIT);
        Node exit = d.onClient(() -> d.ui().tutorial().card().exitButton());
        d.clickNode(exit);
        d.park();
        d.hold(SHORT_MS);
        d.setting(ToolId.RAISE, "radius", 4);
    }

    /** Whether a node is laid out and shown now. */
    private static boolean shown(Demo d, Node node) {
        return d.onClient(() -> {
            d.driver().relayout();
            return node.isShown() && !node.bounds().isEmpty();
        });
    }

    // ---- wiki ----

    /**
     * Help > Wiki: the home page, a link to a tool's page, a picture full size, Back and Forward, then a search and its
     * result.
     */
    private static void wiki(Demo d) {
        d.say(WIKI_OPEN);
        d.clickMenuRow(EditorUi.MENU_HELP, "sculptory.command.help.wiki");
        d.pause(2_000);
        WikiWindow wiki = d.onClient(() -> d.ui().wikiWindow());
        // The wiki opens where it was left (the tutorial's Learn more): home first.
        if (!d.onClient(() -> wiki.currentPage().map("home"::equals).orElse(true))) {
            d.clickNode(d.onClient(wiki::homeButton));
            d.pause(800);
        }
        d.say(WIKI_HOME);
        Rect page = d.onClient(() -> {
            d.driver().relayout();
            return wiki.pagePane().bounds();
        });
        d.hoverUi(page.x() + page.width() / 2.0, page.y() + page.height() / 2.0, 300);
        d.scroll(-4, 0);
        d.hold(SHORT_MS);
        d.scroll(4, 0);
        d.pause(600);
        d.say(WIKI_PAGE);
        Optional<double[]> link = d.onClient(() -> {
            d.driver().relayout();
            UiContext ctx = d.ui().windows().context();
            Rect column = wiki.pageView().columnBounds(ctx);
            Rect visible = wiki.pagePane().bounds();
            for (int y = Math.max(column.y(), visible.y()) + 2; y < Math.min(column.bottom(), visible.bottom());
                    y += 3) {
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
        d.say(WIKI_BACK);
        d.clickNode(d.onClient(wiki::backButton));
        d.hold(SHORT_MS);
        d.clickNode(d.onClient(wiki::forwardButton));
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
        d.hold(SHORT_MS);
        Optional<AbstractButton> result = d.onClient(() -> {
            d.driver().relayout();
            return wiki.row("symmetry");
        });
        if (result.isPresent() && shown(d, result.get())) {
            d.say(WIKI_RESULT);
            d.clickNode(result.get());
            d.hold(RESULT_MS);
        }
        d.closeWindow(EditorWindows.WIKI);
        // A click that went past the window to the world may have selected a block.
        d.onClient(() -> d.ctx().setSelection(null));
        d.park();
    }

    // ---- builder ----

    /**
     * Builder mode, before the editor was ever opened: the ring of powers, placing in mid-air, undo, the Tinker power,
     * powers off; then B opens the editor.
     */
    private static void builder(Demo d) {
        DemoStage s = d.stage();
        d.say(BUILDER_INTRO);
        // In the open south of the stairs, nothing within reach ahead: the planks go into the air, 5 blocks out.
        d.glide(s.x(STAIRS_X) + 0.5, s.y(1) + 0.6, s.z(BUILDER_Z) + 0.5, s.x(STAIRS_X) + 0.5, s.y(3),
                s.z(STAIRS_Z) + 0.5, 2_600);
        d.hold(SHORT_MS);
        d.say(BUILDER_RING);
        d.openRing();
        d.hold(SHORT_MS);
        d.clickPower(BuilderPower.PLACE_IN_AIR);
        d.closeRing();
        d.note(BUILDER_POWERS_ON);
        d.hold(SHORT_MS);
        d.say(BUILDER_PLACE);
        d.selectHotbar(0);
        // Facing north (yaw 180), a little up: a row of planks hanging in the air.
        for (float yaw : new float[] {156f, 168f, 180f, 192f, 204f}) {
            d.turnTo(yaw, -20f, 500);
            d.useItem();
            d.pause(450);
        }
        d.hold(SHORT_MS);
        d.say(BUILDER_UNDO);
        d.holdKeys(Set.of(GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_Z));
        d.pause(250);
        d.holdKeys(Set.of());
        d.hold(SHORT_MS);
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
        for (BuilderPower power : List.of(BuilderPower.PLACE_IN_AIR, BuilderPower.TINKER)) {
            if (d.onClient(() -> d.builder().powers().on(power))) {
                d.clickPower(power);
            }
        }
        d.closeRing();
        d.hold(SHORT_MS);
        if (d.screenIs(RingScreen.class)) {
            d.onClient(() -> d.client().setScreen(null));
        }
        d.say(OPEN_EDITOR);
        d.openEditor();
        d.defaultLayout();
        d.hold(RESULT_MS);
    }

    // ---- closing ----

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
