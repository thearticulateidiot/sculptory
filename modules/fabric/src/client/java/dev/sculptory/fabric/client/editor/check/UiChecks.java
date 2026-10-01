package dev.sculptory.fabric.client.editor.check;

import dev.sculptory.fabric.client.editor.input.EditorKeymap;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.input.KeyChord;
import dev.sculptory.fabric.client.editor.settings.SettingDef;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.editor.settings.form.SettingsForm;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tool.WorldCursor.Face;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.editor.ui.widget.KeyCaptureButton;
import dev.sculptory.fabric.client.editor.ui.widget.Slider;
import dev.sculptory.fabric.client.editor.ui.widget.TextInput;
import dev.sculptory.fabric.client.editor.windows.EditorWindows;
import dev.sculptory.fabric.client.editor.windows.KeysWindow;
import dev.sculptory.fabric.client.editor.windows.OpacityPopup;
import dev.sculptory.fabric.config.FolderMigration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.lwjgl.glfw.GLFW;

/**
 * The editor's windows through the real client: View > Opacity… (Panels 20%, fade only when not hovered, Tool outlines
 * 10%) over bright terrain, the wiki reader and the Tutorial list (pictures for a reviewer), and key rebinding in the
 * Keys window, kept in {@code editor-keys.json}.
 */
final class UiChecks {
    static final String AREA = "bright";

    static final ScenarioGroup GROUP = ScenarioGroup.solo(
            List.of(new Fixtures.Fixture(AREA, b -> {
                b.fill(0, -1, 0, 39, -1, 39, "minecraft:snow_block");
                b.fill(14, 0, 14, 24, 2, 24, "minecraft:white_concrete");
                b.fill(26, 0, 10, 30, 6, 14, "minecraft:quartz_block");
            })),
            List.of(Scenario.visual("ui-opacity", AREA,
                            "View > Opacity…: Panels 20%, fade only when not hovered, Tool outlines 10%, over snow",
                            UiChecks::opacity),
                    Scenario.visual("ui-wiki", AREA, "The wiki reader: home, a page with pictures, a picture full size",
                            UiChecks::wiki),
                    Scenario.visual("ui-tutorial", AREA, "The Tutorial window's list of lessons", UiChecks::tutorial),
                    Scenario.of("keys-rebind", AREA,
                            "Keys window: rebind History to U, a clash shown red, reset; editor-keys.json follows",
                            UiChecks::keys)));

    private UiChecks() {}

    private static void opacity(CheckRun run) {
        CheckDriver d = run.driver();
        Area a = run.area();
        d.view(a.x(20) + 0.5, a.y(8), a.z(36) + 0.5, a.x(20) + 0.5, a.y(1), a.z(18) + 0.5);
        d.selectTool(ToolId.SELECT);
        PlaceChecks.selectTopFaces(run, a.box(15, 2, 15, 23, 2, 23));
        d.view(a.x(20) + 0.5, a.y(8), a.z(36) + 0.5, a.x(20) + 0.5, a.y(1), a.z(18) + 0.5);
        d.pointAt(a.x(20) + 0.5, a.y(0), a.z(28) + 0.5);
        run.picture("opacity-default", "Panels and outlines at 100% over the snow (the reference)");

        OpacityPopup popup = d.onClient(() -> d.ui().openOpacity());
        d.frames(3);
        String typed = typeInto(run, popup.form(), "panels", "20");
        run.check("Panels 20% applies", value(d, popup.form(), "panels").equals(20), "panels "
                + value(d, popup.form(), "panels") + "; " + typed);
        run.picture("opacity-popup", "The Opacity popup: Panels 20, the windows behind it faded");
        d.closePopup();
        d.pointAt(a.x(20) + 0.5, a.y(0), a.z(28) + 0.5);
        run.picture("panels-20", "Every window, the top bar and the palette at 20% over the bright snow: can the"
                + " text still be read?");

        popup = d.onClient(() -> d.ui().openOpacity());
        d.frames(3);
        Node fade = popup.form().control("fade_unless_hovered").orElseThrow();
        d.clickNode(fade);
        run.check("\"Fade only when not hovered\" switches on", value(d, popup.form(), "fade_unless_hovered")
                .equals(true), String.valueOf(value(d, popup.form(), "fade_unless_hovered")));
        String outlines = typeInto(run, popup.form(), "tool_outlines", "10");
        run.check("Tool outlines 10% applies", value(d, popup.form(), "tool_outlines").equals(10),
                "tool outlines " + value(d, popup.form(), "tool_outlines") + "; " + outlines);
        d.closePopup();
        // The pointer on the Tool Settings window: that panel shows solid while the rest stays faded.
        Rect tools = d.onClient(() -> d.ui().windows().window(EditorWindows.TOOL_SETTINGS).orElseThrow().rect());
        d.pointUi(tools.x() + tools.width() / 2.0, tools.y() + 40);
        d.millis(600);
        run.pictureAtPointer("fade-hovered", "Fade only when not hovered: the pointer is on Tool Settings, which is solid; the"
                + " other windows stay at 20%");
        d.pointAt(a.x(20) + 0.5, a.y(0), a.z(28) + 0.5);
        d.millis(600);
        run.picture("fade-not-hovered", "The pointer over the world: every panel back at 20%");
        d.selectTool(ToolId.RAISE);
        d.pointAt(a.x(19) + 0.5, a.y(3), a.z(19) + 0.5);
        d.frames(5);
        run.picture("outlines-10", "Tool outlines 10%: the selection box and the Raise ring barely visible on the"
                + " white blocks");

        // Back to the defaults with each setting's reset button.
        OpacityPopup last = d.onClient(() -> d.ui().openOpacity());
        d.frames(3);
        for (String key : List.of("panels", "fade_unless_hovered", "tool_outlines")) {
            Optional<Button> reset = d.onClient(() -> last.form().resetButton(key).filter(Node::isShown));
            if (reset.isPresent()) {
                d.clickNode(reset.get());
            }
        }
        run.check("each reset button puts its setting back", value(d, last.form(), "panels").equals(100)
                && value(d, last.form(), "fade_unless_hovered").equals(false)
                && value(d, last.form(), "tool_outlines").equals(100), "panels " + value(d, last.form(), "panels")
                + ", fade " + value(d, last.form(), "fade_unless_hovered") + ", outlines "
                + value(d, last.form(), "tool_outlines"));
        d.closePopup();
    }

    private static void wiki(CheckRun run) {
        CheckDriver d = run.driver();
        d.onClient(() -> d.ui().openWiki("home", null));
        d.frames(10);
        run.check("the wiki opens on its home page", "home".equals(d.onClient(() ->
                d.ui().wikiWindow().currentPage().orElse(""))), d.onClient(() -> d.ui().wikiWindow().title()));
        run.picture("wiki-home", "The wiki reader on its home page: larger text, the page list behind Pages");
        d.onClient(() -> d.ui().openWiki("terrain-brushes", null));
        d.frames(20);
        run.picture("wiki-page", "The Terrain brushes page, with its thumbnails");
        List<Rect> pictures = d.onClient(() -> d.ui().wikiWindow().pageView()
                .pictureBounds(d.ui().windows().context()));
        if (pictures.isEmpty()) {
            run.skip("a thumbnail opens full size", "the page shows no thumbnail in view");
        } else {
            Rect first = pictures.get(0);
            d.clickUi(first.x() + first.width() / 2.0, first.y() + first.height() / 2.0);
            d.frames(10);
            run.picture("wiki-picture", "The first thumbnail of the page opened full size");
            d.closePopup();
        }
        d.onClient(() -> {
            if (d.ui().windows().isOpen(EditorWindows.WIKI)) {
                d.ui().toggleWindow(EditorWindows.WIKI);
            }
        });
    }

    private static void tutorial(CheckRun run) {
        CheckDriver d = run.driver();
        d.openWindow(EditorWindows.TUTORIAL);
        d.frames(10);
        boolean open = d.onClient(() -> d.ui().windows().isOpen(EditorWindows.TUTORIAL));
        run.check("the Tutorial window opens", open);
        run.picture("tutorial-list", "The Tutorial window: one slim row per lesson");
        d.onClient(() -> d.ui().toggleWindow(EditorWindows.TUTORIAL));
    }

    private static void keys(CheckRun run) throws IOException {
        CheckDriver d = run.driver();
        Path file = FolderMigration.configDir().resolve("editor-keys.json");
        byte[] original = Files.exists(file) ? Files.readAllBytes(file) : null;
        try {
            d.openWindow(EditorWindows.KEYS);
            d.onClient(() -> d.ui().keysWindow().search("history"));
            d.frames(5);
            KeysWindow keys = d.onClient(() -> d.ui().keysWindow());
            KeyCaptureButton chord = d.onClient(() -> keys.controls(KeyAction.HISTORY).chords().get(0));
            d.clickNode(chord);
            run.check("a click on History's key listens for a new one", d.onClient(chord::isListening));
            d.key(GLFW.GLFW_KEY_U, 0);
            List<KeyChord> bound = d.onClient(() -> d.controller().keymap().chords(KeyAction.HISTORY));
            run.check("pressing U binds History to U", bound.equals(List.of(KeyChord.key(GLFW.GLFW_KEY_U, 0))),
                    String.valueOf(bound));
            d.frames(5);
            run.check("editor-keys.json keeps History on U", saved(file, KeyAction.HISTORY)
                    .equals(List.of(KeyChord.key(GLFW.GLFW_KEY_U, 0))), String.valueOf(saved(file, KeyAction.HISTORY)));

            // U now opens and closes the History window, in the world (the Keys window closed first: Esc would
            // leave the editor).
            d.onClient(() -> d.ui().toggleWindow(EditorWindows.KEYS));
            d.overlook(run.area().x(20), run.area().y(-1), run.area().z(20));
            d.pointAt(run.area().x(20) + 0.5, run.area().y(0), run.area().z(20) + 0.5);
            boolean before = d.onClient(() -> d.ui().windows().isOpen(EditorWindows.HISTORY));
            d.key(GLFW.GLFW_KEY_U, 0);
            boolean after = d.onClient(() -> d.ui().windows().isOpen(EditorWindows.HISTORY));
            run.check("U toggles the History window", before != after, "open before " + before + ", after " + after);
            if (after != before) {
                d.key(GLFW.GLFW_KEY_U, 0);
            }

            // Library's L for History too: both show the clash.
            d.openWindow(EditorWindows.KEYS);
            d.onClient(() -> keys.search("history"));
            d.frames(5);
            KeyCaptureButton again = d.onClient(() -> keys.controls(KeyAction.HISTORY).chords().get(0));
            d.clickNode(again);
            boolean listening = d.onClient(again::isListening);
            d.key(GLFW.GLFW_KEY_L, 0);
            EditorKeymap keymap = d.onClient(() -> d.controller().keymap());
            boolean clash = d.onClient(() -> keymap.hasProblem(KeyAction.HISTORY) && keymap.hasProblem(KeyAction.LIBRARY));
            run.check("History and Library both on L are both marked", clash, "listening " + listening
                    + ", History " + d.onClient(() -> keymap.chords(KeyAction.HISTORY)) + ", Library "
                    + d.onClient(() -> keymap.chords(KeyAction.LIBRARY)) + ", conflicts "
                    + d.onClient(() -> String.valueOf(keymap.conflicts())));
            d.onClient(() -> keys.search("l"));
            d.frames(5);
            run.picture("keys-clash", "The Keys window: History and Library both on L, both in red with the reason");

            // Reset History: back to H, and so in the file.
            d.onClient(() -> keys.search("history"));
            d.frames(3);
            Button reset = d.onClient(() -> keys.controls(KeyAction.HISTORY).reset());
            d.clickNode(reset);
            List<KeyChord> restored = d.onClient(() -> keymap.chords(KeyAction.HISTORY));
            run.check("Reset puts History back on H", restored.equals(List.of(KeyChord.key(GLFW.GLFW_KEY_H, 0))),
                    String.valueOf(restored));
            d.frames(5);
            run.check("editor-keys.json follows the reset", saved(file, KeyAction.HISTORY)
                    .equals(List.of(KeyChord.key(GLFW.GLFW_KEY_H, 0))), String.valueOf(saved(file, KeyAction.HISTORY)));
            d.onClient(() -> keys.search(""));
            d.onClient(() -> d.ui().toggleWindow(EditorWindows.KEYS));
        } finally {
            if (original == null) {
                Files.deleteIfExists(file);
            } else {
                Files.write(file, original);
            }
        }
    }

    // ---- Helpers ----

    /** What a key's chords are in the saved keys file (a new game start reads the same). */
    private static List<KeyChord> saved(Path file, KeyAction action) {
        try {
            return EditorKeymap.fromJson(Files.readString(file)).chords(action);
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    /**
     * Types a value into a form's slider as a player does: open its value field, type, Enter. Returns what the field
     * and the focus were after the typing, for the report when the value doesn't take.
     */
    private static String typeInto(CheckRun run, SettingsForm form, String key, String text) {
        CheckDriver d = run.driver();
        Slider slider = (Slider) d.onClient(() -> form.control(key).orElseThrow());
        d.onClient(() -> slider.startEditing(d.ui().windows().context()));
        d.frames(2);
        // The field opens "with the current value selected" (Slider.startEditing), so typing should replace it.
        String opened = d.onClient(() -> slider.editor().map(TextInput::text).orElse(""));
        d.type("7");
        String afterOne = d.onClient(() -> slider.editor().map(TextInput::text).orElse(""));
        run.check("a slider's value field opens with its value selected (typing replaces it)", afterOne.equals("7"),
                "opened with \"" + opened + "\", one key typed gives \"" + afterOne + "\"");
        for (int i = 0; i < afterOne.length(); i++) {
            d.key(GLFW.GLFW_KEY_BACKSPACE, 0);
        }
        d.type(text);
        String typed = d.onClient(() -> "field \"" + slider.editor().map(TextInput::text).orElse("(closed)")
                + "\", focus " + java.util.Optional.ofNullable(d.ui().windows().context().focused())
                        .map(node -> node.getClass().getSimpleName()).orElse("none"));
        d.key(GLFW.GLFW_KEY_ENTER, 0);
        return typed + ", slider " + d.onClient(slider::value);
    }

    private static Object value(CheckDriver d, SettingsForm form, String key) {
        return d.onClient(() -> {
            SettingsValues values = form.values();
            SettingDef<?> def = values.schema().def(key).orElseThrow();
            return values.get(def);
        });
    }
}
