package dev.sculptory.fabric.client.editor.tools.select;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.region.Facing;
import dev.sculptory.fabric.client.editor.presets.PresetResolver;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.fabric.client.editor.settings.Section;
import dev.sculptory.fabric.client.editor.settings.SettingDef;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.protocol.v2.Limits;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class SelectSettingsTest {
    private static final Limits OP_50K = new Limits(50_000L, 2_097_152L, 32, 20, 32L << 20, 2);

    private static SettingsValues defaults() {
        return SettingsValues.defaults(SelectSettings.SCHEMA);
    }

    @Test
    void defaultsAreABoxSelectionAndMagicSelectsTheSameBlockUpToAHundredThousand() {
        SettingsValues values = defaults();
        assertEquals(SelectSettings.Mode.BOX, values.get(SelectSettings.MODE));
        assertEquals(SelectSettings.SelectShape.BOX, values.get(SelectSettings.SHAPE));
        assertEquals(SelectSettings.Axis.VERTICAL, values.get(SelectSettings.AXIS));
        assertEquals(Facing.UP, values.get(SelectSettings.FACING));
        assertEquals(MagicSelect.Match.SAME_BLOCK, values.get(SelectSettings.MATCH));
        assertEquals(MagicSelect.Connect.FACES, values.get(SelectSettings.CONNECT));
        assertEquals(100_000, values.get(SelectSettings.LIMIT));
        assertEquals(1, values.get(SelectSettings.THICKNESS));
        assertEquals(4, values.get(SelectSettings.BRUSH_RADIUS));
        assertTrue(values.get(SelectSettings.SOLID_ONLY));
        assertEquals(1, values.get(SelectSettings.LASSO_HEIGHT));
    }

    @Test
    void theBrushAndLassoSectionsShowOnlyInTheirModes() {
        for (SelectSettings.Mode mode : SelectSettings.Mode.values()) {
            SettingsValues values = defaults().with(SelectSettings.MODE, mode);
            boolean brush = mode == SelectSettings.Mode.BRUSH;
            boolean lasso = mode == SelectSettings.Mode.LASSO;
            assertEquals(brush, values.isVisible(SelectSettings.BRUSH_RADIUS), mode.name());
            assertEquals(brush, values.isVisible(SelectSettings.SOLID_ONLY), mode.name());
            assertEquals(lasso, values.isVisible(SelectSettings.LASSO_HEIGHT), mode.name());
            assertEquals(mode == SelectSettings.Mode.BOX, values.isVisible(SelectSettings.SHAPE), mode.name());
            assertEquals(mode == SelectSettings.Mode.MAGIC, values.isVisible(SelectSettings.MATCH), mode.name());
            assertTrue(values.isVisible(SelectSettings.THICKNESS), "the op settings show in every mode");
        }
        List<String> sections = SelectSettings.SCHEMA.sections().stream().map(Section::titleKey).toList();
        assertTrue(sections.indexOf("sculptory.setting.select.section.brush") == 1
                && sections.indexOf("sculptory.setting.select.section.lasso") == 2, sections.toString());
        assertEquals(List.of(SelectSettings.BRUSH_RADIUS, SelectSettings.SOLID_ONLY),
                SelectSettings.SCHEMA.sections().get(1).settings());
        assertEquals(List.of(SelectSettings.LASSO_HEIGHT), SelectSettings.SCHEMA.sections().get(2).settings());
        assertEquals(List.of("BOX", "MAGIC", "BRUSH", "LASSO"),
                Stream.of(SelectSettings.Mode.values()).map(Enum::name).toList(), "modes are append-only");
    }

    @Test
    void brushAndLassoSettingsRoundTripThroughThePresetText() {
        SettingsValues values = defaults()
                .with(SelectSettings.MODE, SelectSettings.Mode.LASSO)
                .with(SelectSettings.BRUSH_RADIUS, 17)
                .with(SelectSettings.SOLID_ONLY, false)
                .with(SelectSettings.LASSO_HEIGHT, 42);
        Map<String, String> text = values.encode();
        assertEquals("LASSO", text.get("mode"));
        assertEquals("17", text.get("brush_radius"));
        assertEquals("false", text.get("solid_only"));
        assertEquals("42", text.get("lasso_height"));
        assertEquals(values, SettingsValues.decode(SelectSettings.SCHEMA, text));
        PresetResolver.Result resolved = PresetResolver.resolve(defaults(), text, Limits.DEFAULTS, block -> true);
        assertTrue(resolved.clean(), resolved.toString());
        assertEquals(values, resolved.values());
        assertEquals(SelectSettings.Mode.BRUSH, SettingsValues.decode(SelectSettings.SCHEMA,
                defaults().with(SelectSettings.MODE, SelectSettings.Mode.BRUSH).encode()).get(SelectSettings.MODE));
    }

    @Test
    void eachSettingShowsOnlyWhereItMatters() {
        SettingsValues box = defaults();
        assertTrue(box.isVisible(SelectSettings.SHAPE));
        assertFalse(box.isVisible(SelectSettings.AXIS));
        assertFalse(box.isVisible(SelectSettings.FACING));
        assertFalse(box.isVisible(SelectSettings.MATCH));
        assertFalse(box.isVisible(SelectSettings.CONNECT));
        assertFalse(box.isVisible(SelectSettings.LIMIT));

        SettingsValues cylinder = box.with(SelectSettings.SHAPE, SelectSettings.SelectShape.CYLINDER);
        assertTrue(cylinder.isVisible(SelectSettings.AXIS));
        assertFalse(cylinder.isVisible(SelectSettings.FACING));
        for (SelectSettings.SelectShape pointed : List.of(SelectSettings.SelectShape.CONE, SelectSettings.SelectShape.PYRAMID)) {
            SettingsValues values = box.with(SelectSettings.SHAPE, pointed);
            assertFalse(values.isVisible(SelectSettings.AXIS));
            assertTrue(values.isVisible(SelectSettings.FACING), pointed.name());
        }
        assertFalse(box.with(SelectSettings.SHAPE, SelectSettings.SelectShape.SPHERE).isVisible(SelectSettings.FACING));

        SettingsValues magic = cylinder.with(SelectSettings.MODE, SelectSettings.Mode.MAGIC);
        assertFalse(magic.isVisible(SelectSettings.SHAPE));
        assertFalse(magic.isVisible(SelectSettings.AXIS), "a cylinder's axis hides in magic mode");
        assertTrue(magic.isVisible(SelectSettings.MATCH));
        assertTrue(magic.isVisible(SelectSettings.CONNECT));
        assertTrue(magic.isVisible(SelectSettings.LIMIT));
        assertTrue(magic.isVisible(SelectSettings.THICKNESS), "the op settings show in both modes");
    }

    @Test
    void settingsRoundTripThroughThePresetText() {
        SettingsValues values = defaults()
                .with(SelectSettings.MODE, SelectSettings.Mode.MAGIC)
                .with(SelectSettings.SHAPE, SelectSettings.SelectShape.PYRAMID)
                .with(SelectSettings.AXIS, SelectSettings.Axis.NORTH_SOUTH)
                .with(SelectSettings.FACING, Facing.WEST)
                .with(SelectSettings.MATCH, MagicSelect.Match.EXACT_STATE)
                .with(SelectSettings.CONNECT, MagicSelect.Connect.DIAGONALS)
                .with(SelectSettings.LIMIT, 4321);
        Map<String, String> text = values.encode();
        assertEquals("MAGIC", text.get("mode"));
        assertEquals("PYRAMID", text.get("shape"));
        assertEquals("WEST", text.get("facing"));
        assertEquals(values, SettingsValues.decode(SelectSettings.SCHEMA, text));

        PresetResolver.Result resolved = PresetResolver.resolve(defaults(), text, Limits.DEFAULTS, block -> true);
        assertTrue(resolved.clean(), resolved.toString());
        assertEquals(values, resolved.values());
    }

    @Test
    void aPresetsLimitIsCappedByTheServersOpLimit() {
        Map<String, String> text = defaults().with(SelectSettings.LIMIT, 100_000).encode();
        PresetResolver.Result resolved = PresetResolver.resolve(defaults(), text, OP_50K, block -> true);
        assertEquals(50_000, resolved.values().get(SelectSettings.LIMIT));
        assertFalse(resolved.adjusted().isEmpty());
        assertFalse(defaults().isValid(OP_50K), "the default limit is over this server's op limit");
    }

    @Test
    void aPresetsLimitIsCappedByTheServersSelectionCap() {
        Limits selection30k = new Limits(2_097_152L, 2_097_152L, 32, 20, 32L << 20, 2, 30_000, 1 << 16);
        Map<String, String> text = defaults().with(SelectSettings.LIMIT, 100_000).encode();
        PresetResolver.Result resolved = PresetResolver.resolve(defaults(), text, selection30k, block -> true);
        assertEquals(30_000, resolved.values().get(SelectSettings.LIMIT));
        assertFalse(defaults().isValid(selection30k), "the default limit is over this server's selection cap");
    }

    @Test
    void aPresetFromBeforeShapesKeepsItsSettingsAndGetsTheDefaults() {
        Map<String, String> old = Map.of("fill_with", "PALETTE", "thickness", "3",
                "palette", defaults().encode().get("palette"));
        PresetResolver.Result resolved = PresetResolver.resolve(defaults()
                .with(SelectSettings.SHAPE, SelectSettings.SelectShape.SPHERE), old, Limits.DEFAULTS, block -> true);
        assertEquals(SelectSettings.FillWith.PALETTE, resolved.values().get(SelectSettings.FILL_WITH));
        assertEquals(3, resolved.values().get(SelectSettings.THICKNESS));
        assertEquals(SelectSettings.Mode.BOX, resolved.values().get(SelectSettings.MODE));
        assertEquals(4, resolved.values().get(SelectSettings.BRUSH_RADIUS), "a preset from before the brush and lasso");
        assertTrue(resolved.values().get(SelectSettings.SOLID_ONLY));
        assertEquals(1, resolved.values().get(SelectSettings.LASSO_HEIGHT));

        Map<String, String> magic = Map.of("mode", "MAGIC", "match", "ANY_BLOCK", "limit", "5000");
        PresetResolver.Result magicResolved = PresetResolver.resolve(defaults(), magic, Limits.DEFAULTS, block -> true);
        assertTrue(magicResolved.clean(), magicResolved.toString());
        assertEquals(SelectSettings.Mode.MAGIC, magicResolved.values().get(SelectSettings.MODE));
        assertEquals(5000, magicResolved.values().get(SelectSettings.LIMIT));
    }

    // ---- Translations ----

    private static final Pattern KEY = Pattern.compile("\"(sculptory\\.[a-z_.]+)\"");
    private static final List<Path> SOURCES = List.of(
            Path.of("src/client/java/dev/sculptory/fabric/client/editor/tools/select"),
            Path.of("src/client/java/dev/sculptory/fabric/client/editor/hud/HelpSheet.java"));

    private static JsonObject lang() throws IOException {
        try (InputStream in = SelectSettingsTest.class.getResourceAsStream("/assets/sculptory/lang/en_us.json")) {
            assertNotNull(in, "en_us.json is on the test classpath");
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test
    void everySettingLabelAndOptionHasEnglishText() throws IOException {
        JsonObject lang = lang();
        Set<String> missing = new TreeSet<>();
        for (Section section : SelectSettings.SCHEMA.sections()) {
            if (!section.titleKey().isEmpty() && !lang.has(section.titleKey())) missing.add(section.titleKey());
            for (SettingDef<?> def : section.settings()) {
                if (!lang.has(def.labelKey())) missing.add(def.labelKey());
                if (def instanceof SettingDef.Enum<?> choice) {
                    for (Enum<?> option : choice.type().getEnumConstants()) {
                        String key = def.labelKey() + "." + option.name().toLowerCase(Locale.ROOT);
                        if (!lang.has(key)) missing.add(key);
                    }
                }
            }
        }
        assertEquals(Set.of(), missing);
    }

    @Test
    void everyKeyTheSelectCodeUsesHasEnglishText() throws IOException {
        Set<String> used = new TreeSet<>();
        for (Path root : SOURCES) {
            assertTrue(Files.exists(root), "tests run from the fabric module: " + root.toAbsolutePath());
            List<Path> files;
            try (Stream<Path> walk = Files.walk(root)) {
                files = walk.filter(path -> path.toString().endsWith(".java")).toList();
            }
            for (Path file : files) {
                Matcher matcher = KEY.matcher(Files.readString(file));
                while (matcher.find()) {
                    String key = matcher.group(1);
                    if (!key.endsWith(".")) used.add(key);
                }
            }
        }
        assertTrue(used.size() > 30, "the select code was scanned: " + used);
        JsonObject lang = lang();
        Set<String> missing = new TreeSet<>(used);
        missing.removeIf(lang::has);
        assertEquals(Set.of(), missing);
    }

    // ---- Into ----

    /** Fill's Into is Everything by default, sits in the "Fill, walls and hollow" section, and presets from before it load as Everything. */
    @Test
    void intoIsEverythingByDefaultInTheOpsSectionAndKeptByPresets() {
        SettingsValues values = defaults();
        assertEquals(PasteOptions.Into.EVERYTHING, values.get(SelectSettings.INTO));
        assertTrue(values.isVisible(SelectSettings.INTO));
        assertTrue(values.with(SelectSettings.MODE, SelectSettings.Mode.MAGIC).isVisible(SelectSettings.INTO));
        Section ops = SelectSettings.SCHEMA.sections().stream()
                .filter(section -> section.titleKey().equals("sculptory.setting.select.section.ops")).findFirst().orElseThrow();
        assertEquals(List.of(SelectSettings.FILL_WITH, SelectSettings.PALETTE, SelectSettings.PATTERN.pattern,
                SelectSettings.PATTERN.patchSize, SelectSettings.PATTERN.edge, SelectSettings.PATTERN.steepnessEdge,
                SelectSettings.PATTERN.seed, SelectSettings.INTO, SelectSettings.THICKNESS),
                ops.settings());
        Map<String, String> text = values.with(SelectSettings.INTO, PasteOptions.Into.AIR).encode();
        assertEquals("AIR", text.get("into"));
        assertEquals(PasteOptions.Into.AIR, SettingsValues.decode(SelectSettings.SCHEMA, text).get(SelectSettings.INTO));
        Map<String, String> old = Map.of("fill_with", "PALETTE", "thickness", "2");
        PresetResolver.Result resolved = PresetResolver.resolve(defaults().with(SelectSettings.INTO, PasteOptions.Into.EXISTING),
                old, Limits.DEFAULTS, block -> true);
        assertEquals(PasteOptions.Into.EVERYTHING, resolved.values().get(SelectSettings.INTO), "a preset from before Into");
        assertEquals(2, resolved.values().get(SelectSettings.THICKNESS));
    }

    // ---- Symmetry ----

    @Test
    void symmetryIsOffByDefaultAlwaysShownAndKeptByPresets() {
        SettingsValues values = defaults();
        assertEquals(Symmetry.Mode.OFF, values.get(SelectSettings.SYMMETRY));
        assertTrue(values.isVisible(SelectSettings.SYMMETRY));
        assertTrue(values.with(SelectSettings.MODE, SelectSettings.Mode.MAGIC).isVisible(SelectSettings.SYMMETRY));
        Section section = SelectSettings.SCHEMA.sections().get(SelectSettings.SCHEMA.sections().size() - 1);
        assertEquals("sculptory.setting.select.symmetry_section", section.titleKey());
        assertTrue(section.collapsedByDefault());
        assertEquals(List.of(SelectSettings.SYMMETRY), section.settings());
        Map<String, String> text = values.with(SelectSettings.SYMMETRY, Symmetry.Mode.ROTATE_4).encode();
        assertEquals("ROTATE_4", text.get("symmetry"), "the brushes' preset key");
        assertEquals(Symmetry.Mode.ROTATE_4, SettingsValues.decode(SelectSettings.SCHEMA, text).get(SelectSettings.SYMMETRY));
        // A preset from before symmetry loads as Off.
        Map<String, String> old = Map.of("fill_with", "PALETTE", "thickness", "2");
        PresetResolver.Result resolved = PresetResolver.resolve(defaults().with(SelectSettings.SYMMETRY, Symmetry.Mode.MIRROR_X),
                old, Limits.DEFAULTS, block -> true);
        assertEquals(Symmetry.Mode.OFF, resolved.values().get(SelectSettings.SYMMETRY));
        assertEquals(2, resolved.values().get(SelectSettings.THICKNESS));
    }
}
