package dev.sculptory.mc.client.editor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sculptory.core.brush.Falloff;
import dev.sculptory.core.brush.ShapeSpec;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.mask.MaskRule;
import dev.sculptory.core.mask.MaskText;
import dev.sculptory.core.scatter.ScatterSource;
import dev.sculptory.mc.client.editor.input.EditorKeymap;
import dev.sculptory.mc.client.editor.input.KeyAction;
import dev.sculptory.mc.client.editor.input.KeyChord;
import dev.sculptory.mc.client.editor.input.KeymapStore;
import dev.sculptory.mc.client.editor.mask.EditMaskModel;
import dev.sculptory.mc.client.editor.mask.EditMaskStore;
import dev.sculptory.mc.client.editor.presets.PresetStore;
import dev.sculptory.mc.client.editor.presets.Presets;
import dev.sculptory.mc.client.editor.presets.ToolSettingsFile;
import dev.sculptory.mc.client.editor.presets.ToolSettingsStore;
import dev.sculptory.mc.client.editor.settings.SettingsValues;
import dev.sculptory.mc.client.editor.tool.Tool;
import dev.sculptory.mc.client.editor.tool.ToolId;
import dev.sculptory.mc.client.editor.tools.brush.BrushSettings;
import dev.sculptory.mc.client.editor.tools.brush.ShapeSettings;
import dev.sculptory.mc.client.editor.tools.brush.TerrainBrushTool;
import dev.sculptory.mc.client.editor.tools.scatter.ScatterMix;
import dev.sculptory.mc.client.editor.tools.scatter.ScatterMixPreset;
import dev.sculptory.mc.client.editor.tools.scatter.ScatterTool;
import dev.sculptory.mc.client.editor.tutorial.TutorialProgress;
import dev.sculptory.mc.client.editor.tutorial.TutorialStore;
import dev.sculptory.mc.client.editor.ui.UiOpacity;
import dev.sculptory.mc.client.editor.ui.window.Corner;
import dev.sculptory.mc.client.editor.ui.window.LayoutState;
import dev.sculptory.mc.client.editor.ui.window.SizedLayouts;
import dev.sculptory.mc.client.editor.ui.window.SizedLayouts.Shown;
import dev.sculptory.mc.client.editor.ui.window.WindowPlacement;
import dev.sculptory.mc.client.editor.windows.EditorWindows;
import dev.sculptory.mc.client.editor.windows.LayoutStore;
import dev.sculptory.mc.client.editor.windows.SectionStateStore;
import dev.sculptory.mc.client.editor.windows.UiSizeStore;
import dev.sculptory.mc.client.util.AtomicFileStore;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The editor settings files real older builds wrote ({@code src/test/resources/compat/<version>/}, frozen: see the
 * README there) read by this build with every value as it was saved, so updating Sculptory never resets them. Both
 * sample sets were written by that version's own stores with the same distinctive values (the 0.4.0 set adds a fly
 * speed of 2.5×). Loading a sample changes nothing on disk and reports nothing; a save afterwards keeps every value.
 */
class CompatSamplesTest {
    @TempDir
    Path dir;

    private final List<String> problems = new ArrayList<>();

    static byte[] sample(String version, String name) throws IOException {
        try (InputStream in = CompatSamplesTest.class.getResourceAsStream("/compat/" + version + "/" + name)) {
            assertNotNull(in, "the " + version + " sample " + name);
            return in.readAllBytes();
        }
    }

    private Path copy(String version, String name) throws IOException {
        Path file = dir.resolve(name);
        Files.write(file, sample(version, name));
        return file;
    }

    private ConfigFile config(Path file) {
        return new ConfigFile(file, new AtomicFileStore(PresetStore.MAX_BYTES), problems::add);
    }

    /** Loading left the file exactly as the older build wrote it, and nothing was reported. */
    private void assertUntouched(String version, Path file) throws IOException {
        assertArrayEquals(sample(version, file.getFileName().toString()), Files.readAllBytes(file),
                file.getFileName() + " unchanged by loading");
        assertEquals(List.of(), problems);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.3.0", "0.4.0"})
    void editorUi(String version) throws IOException {
        Path file = copy(version, "editor-ui.json");
        double flySpeed = version.equals("0.3.0") ? 1.0 : 2.5; // 0.3 had no saved fly speed: 1×
        UiSizeStore store = new UiSizeStore(config(file));
        assertEquals(80, store.load());
        assertTrue(store.quickStartSeen());
        assertEquals(new UiOpacity.Values(55, true, 35), store.opacity());
        assertEquals(flySpeed, store.flySpeed());
        assertUntouched(version, file);

        store.changed(90, 0);
        store.saveNow();
        UiSizeStore again = new UiSizeStore(config(file));
        assertEquals(90, again.load());
        assertTrue(again.quickStartSeen());
        assertEquals(new UiOpacity.Values(55, true, 35), again.opacity(), "a save keeps the opacity");
        assertEquals(flySpeed, again.flySpeed());
        assertEquals(List.of(), problems);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.3.0", "0.4.0"})
    void windowLayoutsKeepEachWindowsPlaceAtItsSize(String version) throws IOException {
        Path file = copy(version, LayoutStore.FILE_NAME);
        LayoutStore store = new LayoutStore(config(file));
        SizedLayouts layouts = store.load(80).orElseThrow();
        assertEquals(List.of(new Shown(EditorWindows.SELECTION, true, true), new Shown(EditorWindows.LIBRARY, false, false),
                new Shown(EditorWindows.HISTORY, true, false), new Shown(EditorWindows.KEYS, true, false),
                new Shown(EditorWindows.TOOL_SETTINGS, true, false)), layouts.windows());
        Map<String, WindowPlacement> at80 = new LinkedHashMap<>();
        at80.put(EditorWindows.TOOL_SETTINGS, new WindowPlacement(Corner.TOP_RIGHT, 12, 30, 210, 240, false));
        at80.put(EditorWindows.HISTORY, new WindowPlacement(Corner.BOTTOM_LEFT, 6, 8, 200, 180, true));
        at80.put(EditorWindows.KEYS, new WindowPlacement(Corner.TOP_LEFT, 220, 40, 260, 230, false));
        Map<String, WindowPlacement> at125 = new LinkedHashMap<>();
        at125.put(EditorWindows.TOOL_SETTINGS, new WindowPlacement(Corner.BOTTOM_RIGHT, 3, 5, 176, 190, false));
        at125.put(EditorWindows.SELECTION, new WindowPlacement(Corner.TOP_LEFT, 10, 60, 170, 210, false));
        assertEquals(Map.of("80", at80, "125", at125), layouts.uiSizes());
        assertEquals(SizedLayouts.Kept.NONE, layouts.kept(), "nothing this build can't read");
        assertEquals(layouts, store.load(125).orElseThrow(), "a version-2 file doesn't depend on the size in use");
        assertUntouched(version, file);

        // What the window manager restores: each window at its place at that size, at its default place elsewhere.
        LayoutState shown80 = layouts.at(80);
        for (Shown window : layouts.windows()) {
            assertEquals(Optional.ofNullable(at80.get(window.id())),
                    shown80.window(window.id()).orElseThrow().placement(), window.id() + " at 80%");
            assertEquals(Optional.ofNullable(at125.get(window.id())),
                    layouts.at(125).window(window.id()).orElseThrow().placement(), window.id() + " at 125%");
            assertFalse(layouts.at(100).window(window.id()).orElseThrow().placed(), window.id() + " at 100%");
        }
        assertTrue(shown80.window(EditorWindows.SELECTION).orElseThrow().collapsed());
        assertFalse(shown80.window(EditorWindows.LIBRARY).orElseThrow().open());

        // Saved again as the editor saves it (the live layout at 80%), every place at every size is kept.
        assertTrue(store.save(layouts.capture(80, layouts.at(80))));
        assertEquals(layouts, new LayoutStore(config(file)).load(80).orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.3.0", "0.4.0"})
    void keysKeepTheirBindingsAndNewActionsGetTheirDefaults(String version) throws IOException {
        Path file = copy(version, "editor-keys.json");
        EditorKeymap keymap = new KeymapStore(config(file)).load();
        assertEquals(List.of(KeyChord.parse("ctrl+u")), keymap.chords(KeyAction.UNDO));
        assertEquals(List.of(KeyChord.parse("ctrl+shift+u"), KeyChord.parse("f7")), keymap.chords(KeyAction.REDO));
        assertEquals(List.of(), keymap.chords(KeyAction.LIBRARY), "unbound stays unbound");
        assertEquals(List.of(KeyChord.parse("alt+j")), keymap.chords(KeyAction.JUMP));

        JsonObject bindings = JsonParser.parseString(new String(sample(version, "editor-keys.json"),
                StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("bindings");
        Set<KeyChord> inFile = new HashSet<>();
        bindings.entrySet().forEach(entry -> entry.getValue().getAsJsonArray()
                .forEach(chord -> inFile.add(KeyChord.parse(chord.getAsString()))));
        for (KeyAction action : KeyAction.values()) {
            JsonElement saved = bindings.get(action.id());
            List<KeyChord> expected = new ArrayList<>();
            if (saved != null) {
                saved.getAsJsonArray().forEach(chord -> expected.add(KeyChord.parse(chord.getAsString())));
            } else {
                // An action newer than the file: its defaults, but never a key the file gives to another action.
                action.defaultChords().stream().filter(chord -> !inFile.contains(chord)).forEach(expected::add);
            }
            assertEquals(expected, keymap.chords(action), action.id());
        }
        assertUntouched(version, file);

        JsonObject written = JsonParser.parseString(keymap.toJson()).getAsJsonObject().getAsJsonObject("bindings");
        bindings.entrySet().forEach(entry -> assertEquals(entry.getValue(), written.get(entry.getKey()),
                entry.getKey() + " written back as it was"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.3.0", "0.4.0"})
    void presetsAndTheLastToolSettingsComeBackOnTheEditor(String version) throws IOException {
        Path settingsFile = copy(version, ToolSettingsStore.FILE_NAME);
        Path presetsFile = copy(version, PresetStore.FILE_NAME);
        EditorTestRig rig = new EditorTestRig();
        ScatterTool scatter = (ScatterTool) rig.ctx.tools().get(ToolId.SCATTER).orElseThrow();
        ToolSettingsStore settings = new ToolSettingsStore(config(settingsFile), rig.ctx, Translator.KEYS,
                block -> true);
        settings.addExtra(ToolId.SCATTER, ScatterMixPreset.KEY, new ScatterMixPreset(scatter));
        settings.load();
        Presets presets = new Presets(new PresetStore(config(presetsFile)), rig.ctx, Translator.KEYS, block -> true);
        presets.addExtra(ToolId.SCATTER, ScatterMixPreset.KEY, new ScatterMixPreset(scatter));
        presets.load();
        Set<ToolId> restored = settings.restore();
        presets.restoreSelections(restored);

        BrushSettings raise = ((TerrainBrushTool) rig.ctx.tools().get(ToolId.RAISE).orElseThrow()).settings();
        SettingsValues raiseNow = rig.ctx.settings(ToolId.RAISE);
        assertEquals(9, raiseNow.get(raise.radius), "the last settings, not the preset's");
        assertEquals(0.8, raiseNow.get(raise.strength), 1e-9);
        assertEquals(Falloff.LINEAR, raiseNow.get(raise.falloff));
        assertEquals(30, rig.ctx.settings(ToolId.SHAPE).get(ShapeSettings.RADIUS));
        assertEquals(ShapeSpec.Kind.CONE, rig.ctx.settings(ToolId.SHAPE).get(ShapeSettings.KIND));
        assertEquals(List.of(
                new ScatterMix.Variant(new ScatterSource.Held(new SourceRef.Asset("ab".repeat(32))), "trees/oak.schem", 3),
                new ScatterMix.Variant(new ScatterSource.Block("minecraft:fern"), "minecraft:fern", 2)), scatter.mix().variants());
        for (Tool tool : rig.ctx.tools().paletteOrder()) {
            ToolId id = tool.descriptor().id();
            if (!Set.of(ToolId.RAISE, ToolId.SHAPE, ToolId.SCATTER).contains(id)) {
                assertEquals(SettingsValues.defaults(tool.schema()).encode(), rig.ctx.settings(id).encode(),
                        id.value() + " at its defaults, as it was left");
            }
        }

        assertEquals(List.of("Soft hills"), presets.names(ToolId.RAISE));
        assertEquals("Soft hills", presets.selected(ToolId.RAISE));
        assertTrue(presets.isModified(ToolId.RAISE), "changed after it was saved, as it was left");
        assertEquals(List.of("Big cone"), presets.names(ToolId.SHAPE));
        assertEquals("Big cone", presets.selected(ToolId.SHAPE));
        assertEquals(List.of("Forest"), presets.names(ToolId.SCATTER));
        assertEquals("Forest", presets.selected(ToolId.SCATTER));
        assertEquals(List.of(), rig.noticeKeys(), "nothing adjusted, skipped or missing");
        assertUntouched(version, settingsFile);
        assertUntouched(version, presetsFile);

        presets.select(ToolId.RAISE, "Soft hills");
        assertEquals(12, rig.ctx.settings(ToolId.RAISE).get(raise.radius), "the preset's own values");
        assertEquals(0.8, rig.ctx.settings(ToolId.RAISE).get(raise.strength), 1e-9);
        assertEquals(Falloff.LINEAR, rig.ctx.settings(ToolId.RAISE).get(raise.falloff));

        // Saving keeps every tool's entry and every preset of the older file.
        assertTrue(settings.saveNow());
        ToolSettingsFile before = ToolSettingsFile.fromJson(new String(sample(version, ToolSettingsStore.FILE_NAME),
                StandardCharsets.UTF_8));
        ToolSettingsFile after = ToolSettingsFile.fromJson(Files.readString(settingsFile));
        before.tools().forEach((id, entry) -> {
            if (!id.equals("raise")) assertEquals(entry, after.tools().get(id), id);
        });
        assertEquals("12", after.tools().get("raise").values().get("radius"), "the player's change since");
        assertTrue(presets.saveAs(ToolId.SMOOTH, "Gentle"));
        JsonObject presetsBefore = JsonParser.parseString(new String(sample(version, PresetStore.FILE_NAME),
                StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("tools");
        JsonObject presetsAfter = JsonParser.parseString(Files.readString(presetsFile)).getAsJsonObject()
                .getAsJsonObject("tools");
        presetsBefore.entrySet().forEach(entry -> assertEquals(entry.getValue(), presetsAfter.get(entry.getKey()),
                entry.getKey() + "'s presets kept"));
        assertEquals(List.of(), problems);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.3.0", "0.4.0"})
    void sectionsStayOpenOrClosed(String version) throws IOException {
        Path file = copy(version, SectionStateStore.FILE_NAME);
        SectionStateStore store = SectionStateStore.of(config(file));
        store.load();
        assertEquals(Optional.of(true), store.expanded(ToolId.RAISE, "sculptory.setting.brush.mask"));
        assertEquals(Optional.of(false), store.expanded(ToolId.RAISE, "sculptory.setting.brush.symmetry_section"));
        assertEquals(Optional.of(false), store.expanded(ToolId.SELECT, "sculptory.setting.select.copy_section"));
        assertEquals(Optional.empty(), store.expanded(ToolId.SMOOTH, "sculptory.setting.brush.mask"));
        assertUntouched(version, file);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.3.0", "0.4.0"})
    void theGlobalMaskKeepsItsRules(String version) throws IOException {
        Path file = copy(version, EditMaskStore.FILE_NAME);
        EditMaskModel model = new EditMaskModel();
        new EditMaskStore(config(file), model).load();
        assertEquals(MaskText.decode("v1 | is minecraft:grass_block;#minecraft:logs | !height 0 63 | slope 0 2"
                + " | chance 40 12345 | inside box 0 0 0 0 0 0").entries(), model.rules());
        assertEquals(new MaskRule.Height(0, 63), model.rules().get(1).rule());
        assertTrue(model.rules().get(1).not());
        assertEquals(EditMaskModel.insideSelection(), model.rules().get(4).rule(), "inside the selection");
        assertTrue(model.usesSelection());
        assertFalse(model.on(), "the mask starts off, as always");
        assertUntouched(version, file);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.3.0", "0.4.0"})
    void tutorialProgressIsKept(String version) throws IOException {
        Path file = copy(version, TutorialStore.FILE_NAME);
        assertEquals(new TutorialProgress(Set.of("getting_around", "menus", "select"),
                Optional.of(new TutorialProgress.Current("edit", 3))), TutorialStore.of(config(file)).load());
        assertUntouched(version, file);
    }
}
