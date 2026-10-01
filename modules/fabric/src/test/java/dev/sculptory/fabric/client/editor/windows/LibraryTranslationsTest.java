package dev.sculptory.fabric.client.editor.windows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Every {@code sculptory.library.*} key the editor code uses (the Library window and its M4 row) has English text. */
class LibraryTranslationsTest {
    private static final Pattern KEY = Pattern.compile("\"(sculptory\\.library\\.[a-z_.]+)\"");
    private static final Path EDITOR = Path.of("src/client/java/dev/sculptory/fabric/client/editor");

    @Test
    void everyLibraryKeyIsInEnUs() throws IOException {
        assertTrue(Files.isDirectory(EDITOR), "tests run from the fabric module: " + EDITOR.toAbsolutePath());
        Set<String> used = new TreeSet<>();
        List<Path> sources;
        try (Stream<Path> files = Files.walk(EDITOR)) {
            sources = files.filter(path -> path.toString().endsWith(".java")).toList();
        }
        for (Path source : sources) {
            Matcher matcher = KEY.matcher(Files.readString(source));
            while (matcher.find()) {
                used.add(matcher.group(1));
            }
        }
        assertTrue(used.contains("sculptory.library.delete.confirm_file"), "the management row was scanned: " + used);
        JsonObject lang;
        try (InputStream in = getClass().getResourceAsStream("/assets/sculptory/lang/en_us.json")) {
            assertNotNull(in, "en_us.json is on the test classpath");
            lang = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        Set<String> missing = new TreeSet<>(used);
        missing.removeIf(lang::has);
        assertEquals(Set.of(), missing);
    }
}
