package dev.sculptory.fabric.client.editor.presets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.settings.Validation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PresetNamesTest {
    private static String key(Validation validation) {
        return validation.messageKey();
    }

    @Test
    void namesAreTrimmedAndNeedOneTo48Characters() {
        assertEquals("Soft hills", PresetNames.normalize("  Soft hills \t"));
        assertTrue(PresetNames.problem("  Soft hills ", List.of(), null).isValid());
        assertEquals("sculptory.preset.name.empty", key(PresetNames.problem("   ", List.of(), null)));
        assertEquals("sculptory.preset.name.empty", key(PresetNames.problem(null, List.of(), null)));
        assertTrue(PresetNames.problem("x".repeat(48), List.of(), null).isValid());
        Validation tooLong = PresetNames.problem("x".repeat(49), List.of(), null);
        assertEquals("sculptory.preset.name.too_long", key(tooLong));
        assertEquals(List.of("48"), tooLong.args());
        assertTrue(PresetNames.problem(" " + "x".repeat(48) + " ", List.of(), null).isValid(), "spaces around don't count");
    }

    @Test
    void charactersAreCountedNotUtf16Units() {
        String emoji = "🌲"; // one code point, two chars
        assertTrue(PresetNames.problem(emoji.repeat(48), List.of(), null).isValid());
        assertFalse(PresetNames.problem(emoji.repeat(49), List.of(), null).isValid());
    }

    @Test
    void controlCharactersAreRefused() {
        for (String name : List.of("a\tb", "a\nb", "a\u0000b", "a\u007Fb", "a b", "a\u0085b")) {
            assertEquals("sculptory.preset.name.control", key(PresetNames.problem(name, List.of(), null)), name);
        }
        assertTrue(PresetNames.problem("Überhang – 5×5 · ok", List.of(), null).isValid(), "other characters are fine");
    }

    @Test
    void namesAreUniquePerToolIgnoringCase() {
        List<String> existing = List.of("Forest", "Big soft");
        assertEquals("sculptory.preset.name.taken", key(PresetNames.problem("forest", existing, null)));
        assertEquals("sculptory.preset.name.taken", key(PresetNames.problem(" BIG SOFT ", existing, null)));
        assertTrue(PresetNames.problem("Forest 2", existing, null).isValid());
        assertTrue(PresetNames.problem("FOREST", existing, "Forest").isValid(), "a rename may change the case");
        assertEquals("sculptory.preset.name.taken", key(PresetNames.problem("big soft", existing, "Forest")));
    }

    @Test
    void savedNamesMustBeTrimmedAndWellFormed() {
        assertTrue(PresetNames.isValid("Forest"));
        assertFalse(PresetNames.isValid(" Forest"));
        assertFalse(PresetNames.isValid(""));
        assertFalse(PresetNames.isValid("a\nb"));
        assertFalse(PresetNames.isValid(null));
    }

    @Test
    void aToolsListIsSortedIgnoringCaseAndRefusesDuplicatesAndOverflow() {
        ToolPresets presets = new ToolPresets(List.of(preset("beta"), preset("Alpha"), preset("gamma")), "beta");
        assertEquals(List.of("Alpha", "beta", "gamma"), presets.names());
        assertEquals("beta", presets.selected());
        assertEquals("", new ToolPresets(List.of(preset("beta")), "missing").selected(), "an unknown selection is Default");
        assertThrows(IllegalArgumentException.class, () -> new ToolPresets(List.of(preset("A"), preset("a")), ""));
        assertThrows(IllegalArgumentException.class, () -> new ToolPresets(List.of(preset(" padded")), ""));
        List<Preset> many = new java.util.ArrayList<>();
        for (int i = 0; i < PresetNames.MAX_PER_TOOL; i++) many.add(preset("p" + i));
        assertTrue(new ToolPresets(many, "").isFull());
        many.add(preset("one more"));
        assertThrows(IllegalArgumentException.class, () -> new ToolPresets(many, ""));
    }

    @Test
    void renamingAndDeletingKeepTheSelectionRight() {
        ToolPresets presets = new ToolPresets(List.of(preset("Forest"), preset("Desert")), "Forest");
        assertEquals("Woods", presets.renamed("Forest", "Woods").selected());
        assertEquals(List.of("Desert", "Woods"), presets.renamed("Forest", "Woods").names());
        assertEquals("", presets.without("Forest").selected(), "deleting the selected preset selects Default");
        assertEquals("Forest", presets.without("Desert").selected());
        ToolPresets replaced = presets.with(new Preset("FOREST", Map.of("radius", "9")));
        assertEquals(List.of("Desert", "FOREST"), replaced.names(), "same name ignoring case replaces");
        assertEquals("FOREST", replaced.selected());
    }

    private static Preset preset(String name) {
        return new Preset(name, Map.of("radius", "5"));
    }
}
