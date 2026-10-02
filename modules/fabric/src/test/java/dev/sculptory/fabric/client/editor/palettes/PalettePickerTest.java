package dev.sculptory.fabric.client.editor.palettes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sculptory.core.palette.BlockPalette;
import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.mock.MockEditorSession;
import dev.sculptory.fabric.client.editor.ui.widget.TextPrompt;
import dev.sculptory.fabric.client.session.Permissions;
import dev.sculptory.protocol.v2.Limits;
import dev.sculptory.protocol.v2.S2C;
import dev.sculptory.server.engine.Perm;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** The palette picker and the save prompt's check, on the mock session; and the palette texts all have English. */
class PalettePickerTest {
    private final MockEditorSession session = new MockEditorSession();
    private final List<String> picked = new ArrayList<>();

    private PalettePicker picker(String folder) {
        return new PalettePicker(() -> Optional.of(session), Translator.KEYS, folder, picked::add);
    }

    private static List<String> paths(List<S2C.LibraryListing.Entry> entries) {
        return entries.stream().map(S2C.LibraryListing.Entry::path).toList();
    }

    @Test
    void thePickerListsFoldersAndPalettesOnlyAndPicksAPalette() {
        session.palettes().put("palettes/moss.palette.json", BlockPalette.of("minecraft:stone", 1));
        session.palettes().put("palettes/Autumn.palette.json", BlockPalette.of("minecraft:dirt", 1));
        session.palettes().put("palettes/reef/coral.palette.json", BlockPalette.of("minecraft:sand", 1));
        session.library().put("palettes/tree.schem", "ab".repeat(32));
        PalettePicker picker = picker("palettes");
        assertEquals("palettes", picker.folder());
        assertEquals(List.of("palettes/reef", "palettes/Autumn.palette.json", "palettes/moss.palette.json"),
                paths(picker.shown()), "folders first, then palettes by name; no schematics");

        picker.activate(picker.shown().get(0));
        assertEquals("palettes/reef", picker.folder());
        assertEquals(List.of("palettes/reef/coral.palette.json"), paths(picker.shown()));
        assertEquals(List.of(), picked, "opening a folder picks nothing");
        picker.list("palettes");
        picker.activate(picker.shown().get(2));
        assertEquals(List.of("palettes/moss.palette.json"), picked);

        picker.list("");
        assertEquals(List.of("palettes", "_shared"), paths(picker.shown()), "Shared with me is offered last");
    }

    @Test
    void aFolderThatCantBeListedKeepsWhatWasShown() {
        session.palettes().put("a/x.palette.json", BlockPalette.of("minecraft:stone", 1));
        PalettePicker picker = picker("a");
        session.setState(dev.sculptory.fabric.client.session.SessionState.DISCONNECTED);
        picker.list("elsewhere");
        assertEquals("a", picker.folder());
        assertEquals(List.of("a/x.palette.json"), paths(picker.shown()));
    }

    @Test
    void theSavePromptChecksPathsAsTheServerWould() {
        assertEquals(new TextPrompt.Check(true, "sculptory.palette.save.target[palettes/moss.palette.json]"),
                PaletteButtons.check("palettes/moss", Translator.KEYS), "the extension is added");
        assertFalse(PaletteButtons.check("", Translator.KEYS).ok());
        assertFalse(PaletteButtons.check("../moss", Translator.KEYS).ok());
        assertFalse(PaletteButtons.check("a b", Translator.KEYS).ok());
        assertTrue(PaletteButtons.check("_players/" + new java.util.UUID(1, 2) + "/moss", Translator.KEYS).ok());
    }

    @Test
    void theButtonsNeedTheLibraryAndClipboard() {
        assertTrue(PaletteButtons.available(Optional.of(session)));
        session.setPermissions(new Permissions(Perm.mask(EnumSet.of(Perm.USE, Perm.BRUSH)), Limits.DEFAULTS));
        assertFalse(PaletteButtons.available(Optional.of(session)));
        assertFalse(PaletteButtons.available(Optional.empty()));
    }

    @Test
    void everyPaletteKeyIsInEnUs() throws IOException {
        Pattern key = Pattern.compile("\"(sculptory\\.palette\\.[a-z_.]+)\"");
        Path editor = Path.of("src/client/java/dev/sculptory/fabric/client/editor");
        assertTrue(Files.isDirectory(editor), "tests run from the fabric module: " + editor.toAbsolutePath());
        Set<String> used = new TreeSet<>();
        try (Stream<Path> files = Files.walk(editor)) {
            for (Path source : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source);
                Matcher matcher = key.matcher(text);
                while (matcher.find()) used.add(matcher.group(1));
                // PaletteActions builds its keys from a prefix: "sculptory.palette." + "saved".
                if (source.getFileName().toString().equals("PaletteActions.java")) {
                    Matcher suffix = Pattern.compile("KEY \\+ \"([a-z_.]+)\"").matcher(text);
                    while (suffix.find()) used.add("sculptory.palette." + suffix.group(1));
                }
            }
        }
        assertTrue(used.contains("sculptory.palette.saved") && used.contains("sculptory.palette.save.title"),
                "the palette code was scanned: " + used);
        JsonObject lang;
        try (InputStream in = getClass().getResourceAsStream("/assets/sculptory/lang/en_us.json")) {
            lang = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        Set<String> missing = new TreeSet<>(used);
        missing.removeIf(lang::has);
        assertEquals(Set.of(), missing);
        assertTrue(lang.has("sculptory.library.palette"));
    }
}
