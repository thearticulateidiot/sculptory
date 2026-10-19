package dev.sculptory.fabric.client.editor.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sculptory.fabric.client.editor.input.EditorKeymap;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.input.KeyChord;
import dev.sculptory.fabric.client.editor.settings.SettingsSchema;
import dev.sculptory.fabric.client.editor.tool.KeyHint;
import dev.sculptory.fabric.client.editor.tool.Tool;
import dev.sculptory.fabric.client.editor.tool.ToolDescriptor;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tool.ToolRegistry;
import dev.sculptory.fabric.client.editor.tools.brush.TerrainBrushTool;
import dev.sculptory.fabric.client.editor.tools.generate.GenerateSettings;
import dev.sculptory.fabric.client.editor.ui.McFontText;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.widget.Dropdown;
import dev.sculptory.server.engine.Perm;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

/**
 * The client contracts of the batch after 0.3.0-alpha (protocol 6) before its streams land: palette slots 16 and 17
 * (the Heightmap brush on ; and the Biome brush on ', both built),
 * and Generate's Sweep and Stamp kinds, listed but not yet offered (Heightmap is built).
 */
class BatchSixPaletteTest {
    private static Tool slot(int slot) {
        ToolRegistry registry = new ToolRegistry();
        EditorToolSet.register(registry, new PlaceholderTool(new ToolDescriptor(ToolId.SELECT, "sculptory.tool.select",
                "minecraft:stone", Perm.REGION), "sculptory.hint.select.drag"));
        assertEquals(ToolRegistry.PALETTE_SLOTS, registry.paletteOrder().size());
        return registry.slot(slot).orElseThrow();
    }

    private static JsonObject lang() throws IOException {
        try (InputStream in = BatchSixPaletteTest.class.getResourceAsStream("/assets/sculptory/lang/en_us.json")) {
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test
    void slotSixteenIsTheHeightmapBrushAndSeventeenTheBiomeBrushOnSemicolonAndApostrophe() throws IOException {
        assertEquals(17, ToolRegistry.PALETTE_SLOTS);
        JsonObject lang = lang();
        Tool heightmap = slot(16);
        Tool biome = slot(17);
        assertEquals(new ToolDescriptor(ToolId.HEIGHTMAP, "sculptory.tool.heightmap", TerrainBrushTool.HEIGHTMAP_ICON,
                Perm.BRUSH), heightmap.descriptor());
        assertEquals(new ToolDescriptor(ToolId.BIOME, "sculptory.tool.biome", TerrainBrushTool.BIOME_ICON, Perm.BIOME),
                biome.descriptor());
        // Both streams landed: the Heightmap brush in slot 16 and the Biome brush (needing the biome node) in 17.
        assertEquals(dev.sculptory.core.brush.BrushTool.HEIGHTMAP,
                assertInstanceOf(TerrainBrushTool.class, heightmap, "ws/heightmap built it").kind());
        assertEquals(dev.sculptory.core.brush.BrushTool.BIOME,
                assertInstanceOf(TerrainBrushTool.class, biome, "ws/biomes built it").kind());
        for (Tool tool : List.of(heightmap, biome)) {
            assertFalse(EditorToolSet.comingSoon(tool.descriptor().id()).isPresent(), "selectable, not greyed out");
            for (String key : List.of(tool.descriptor().nameKey(), tool.descriptor().nameKey() + ".tooltip")) {
                assertTrue(lang.has(key), key);
            }
        }
        assertEquals(KeyAction.TOOL_16, KeyAction.toolSlot(16));
        assertEquals(KeyAction.TOOL_17, KeyAction.toolSlot(17));
        assertEquals(16, KeyAction.TOOL_16.toolSlot());
        assertEquals(17, KeyAction.TOOL_17.toolSlot());
        assertEquals(0, KeyAction.TOOL_SIZE.toolSlot(), "the slot keys end with 17");
        assertEquals(List.of(KeyChord.parse("semicolon")), KeyAction.TOOL_16.defaultChords());
        assertEquals(List.of(KeyChord.parse("apostrophe")), KeyAction.TOOL_17.defaultChords());
        assertEquals(";", EditorKeymap.defaults().display(KeyAction.TOOL_16));
        assertEquals("'", EditorKeymap.defaults().display(KeyAction.TOOL_17));
        for (KeyAction action : List.of(KeyAction.TOOL_16, KeyAction.TOOL_17)) {
            assertTrue(lang.has("sculptory.key." + action.id()), action.id());
        }
    }

    @Test
    void generatesNewKindsAreListedButNotOfferedYet() throws IOException {
        JsonObject lang = lang();
        List<GenerateSettings.Kind> offered = new ArrayList<>();
        for (GenerateSettings.Kind kind : GenerateSettings.Kind.values()) {
            if (GenerateSettings.KIND.available(kind)) offered.add(kind);
            assertTrue(lang.has("sculptory.setting.generate.kind." + kind.name().toLowerCase(java.util.Locale.ROOT)),
                    kind.name());
        }
        assertEquals(List.of(GenerateSettings.Kind.PATH, GenerateSettings.Kind.ROOF, GenerateSettings.Kind.LINE,
                GenerateSettings.Kind.SWEEP, GenerateSettings.Kind.STAMP, GenerateSettings.Kind.HEIGHTMAP,
                GenerateSettings.Kind.SMART_SURFACE), offered, "every kind is built");
        assertEquals(List.of(GenerateSettings.Kind.SWEEP, GenerateSettings.Kind.STAMP, GenerateSettings.Kind.HEIGHTMAP),
                List.of(GenerateSettings.Kind.values()).subList(3, 6), "appended, in this order");
        // Smart Surface is appended and, built, offered.
        assertEquals(GenerateSettings.Kind.SMART_SURFACE, GenerateSettings.Kind.values()[6], "appended");
        assertTrue(GenerateSettings.KIND.unavailable().isEmpty(), "every kind is built");
        assertTrue(GenerateSettings.KIND.validate(GenerateSettings.Kind.SMART_SURFACE, null).isValid());
        assertTrue(GenerateSettings.KIND.options().contains(GenerateSettings.Kind.SMART_SURFACE), "listed");
        assertEquals(7, GenerateSettings.KIND.options().size());
        assertTrue(GenerateSettings.KIND.validate(GenerateSettings.Kind.HEIGHTMAP, null).isValid());
    }

    @Test
    void aDropdownSkipsTheOptionsThatCantBeChosen() {
        List<String> picked = new ArrayList<>();
        Dropdown<String> dropdown = new Dropdown<>(List.of("path", "roof", "sweep", "line"), "roof", s -> s, picked::add);
        dropdown.setOptionEnabled(option -> !option.equals("sweep"));
        assertFalse(dropdown.isOptionEnabled("sweep"));
        dropdown.pick("sweep");
        assertEquals("roof", dropdown.selected(), "picking an option that can't be chosen does nothing");
        UiContext ctx = new UiContext(McFontText.INSTANCE, Theme.DARK);
        dropdown.keyPressed(ctx, GLFW.GLFW_KEY_DOWN, 0, 0);
        assertEquals("line", dropdown.selected(), "Down steps past it");
        dropdown.keyPressed(ctx, GLFW.GLFW_KEY_UP, 0, 0);
        assertEquals("roof", dropdown.selected(), "and so does Up");
        assertEquals(List.of("line", "roof"), picked);
    }
}
