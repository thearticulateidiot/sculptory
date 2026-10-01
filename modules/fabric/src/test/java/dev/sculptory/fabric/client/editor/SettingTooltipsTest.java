package dev.sculptory.fabric.client.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sculptory.fabric.client.editor.settings.SettingDef;
import dev.sculptory.fabric.client.editor.settings.form.SettingsForm;
import dev.sculptory.fabric.client.editor.tool.Tool;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** Every setting of every palette tool explains itself: its label key has a {@code .tooltip} in en_us.json. */
class SettingTooltipsTest {
    /** Enum options whose names don't say what they do: each has an option tooltip too. */
    private static final List<String> EXPLAINED_OPTIONS = List.of(
            "sculptory.setting.brush.falloff",
            "sculptory.setting.brush.shape",
            "sculptory.setting.brush.mode",
            "sculptory.setting.shape.kind",
            "sculptory.setting.scatter.density_mode",
            "sculptory.setting.generate.style",
            "sculptory.setting.generate.pitch");

    @Test
    void everySettingOfEveryToolHasATooltip() throws IOException {
        JsonObject lang = lang();
        List<Tool> tools = new EditorTestRig().ctx.tools().paletteOrder();
        assertEquals(15, tools.size(), "all fifteen palette tools");
        Set<String> missing = new TreeSet<>();
        int settings = 0;
        for (Tool tool : tools) {
            for (SettingDef<?> def : tool.schema().defs()) {
                settings++;
                String key = def.labelKey() + SettingsForm.TOOLTIP_SUFFIX;
                if (!lang.has(key) || lang.get(key).getAsString().isBlank()) {
                    missing.add(key);
                }
            }
        }
        assertTrue(settings > 100, "the schemas were walked: " + settings);
        assertEquals(Set.of(), missing, missing.size() + " settings have no tooltip");
    }

    @Test
    void optionsWhoseNamesDontExplainThemselvesHaveTooltips() throws IOException {
        JsonObject lang = lang();
        Set<String> missing = new TreeSet<>();
        for (Tool tool : new EditorTestRig().ctx.tools().paletteOrder()) {
            for (SettingDef<?> def : tool.schema().defs()) {
                if (def instanceof SettingDef.Enum<?> choice && EXPLAINED_OPTIONS.contains(def.labelKey())) {
                    for (Object option : choice.type().getEnumConstants()) {
                        String key = SettingsForm.optionTooltipKey(def.labelKey(), (Enum<?>) option);
                        if (!lang.has(key)) {
                            missing.add(key);
                        }
                    }
                }
            }
        }
        assertEquals(Set.of(), missing);
    }

    private JsonObject lang() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/assets/sculptory/lang/en_us.json")) {
            assertNotNull(in, "en_us.json is on the test classpath");
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
