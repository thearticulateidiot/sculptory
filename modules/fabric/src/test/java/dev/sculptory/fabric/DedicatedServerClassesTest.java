package dev.sculptory.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * v1 runs on dedicated servers from the same jar, so nothing a dedicated server loads may touch client-only code. The
 * split source sets already keep {@code src/main} from compiling against client classes; this guards the output and
 * the metadata: no common/server class names a client-only class (a class-level reference would fail to load on a
 * server), and the client entrypoint and client mixins are declared for the client only. Paths come from the build
 * ({@code sculptory.mainClassDirs}, {@code sculptory.mainResourceDir}).
 */
class DedicatedServerClassesTest {
    /** Internal-name prefixes that exist only on the client (the integrated server ships in the client jar only). */
    private static final List<String> CLIENT_ONLY = List.of("net/minecraft/client/", "com/mojang/blaze3d/",
            "net/minecraft/server/integrated/", "net/fabricmc/fabric/api/client/", "dev/sculptory/fabric/client/");

    @Test
    void commonAndServerClassesReferenceNoClientOnlyClass() throws IOException {
        String dirs = System.getProperty("sculptory.mainClassDirs");
        assertNotNull(dirs, "the build did not pass the main class directories");
        int scanned = 0;
        List<String> offenders = new ArrayList<>();
        for (String dir : dirs.split(File.pathSeparator)) {
            Path root = Path.of(dir);
            if (!Files.isDirectory(root)) continue;
            List<Path> classes;
            try (Stream<Path> walk = Files.walk(root)) {
                classes = walk.filter(p -> p.toString().endsWith(".class")).toList();
            }
            for (Path file : classes) {
                scanned++;
                // Class and descriptor names sit in the constant pool as (modified) UTF-8; these are ASCII.
                String bytes = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                for (String prefix : CLIENT_ONLY) {
                    if (bytes.contains(prefix)) offenders.add(root.relativize(file) + " references " + prefix);
                }
            }
        }
        assertTrue(scanned > 100, "only " + scanned + " classes scanned from " + dirs);
        assertEquals(List.of(), offenders);
    }

    @Test
    void theClientEntrypointAndMixinsAreDeclaredForTheClientOnly() throws IOException {
        String dir = System.getProperty("sculptory.mainResourceDir");
        assertNotNull(dir, "the build did not pass the main resource directory");
        Path resources = Path.of(dir);
        JsonObject mod = JsonParser.parseString(Files.readString(resources.resolve("fabric.mod.json"))).getAsJsonObject();
        JsonObject entrypoints = mod.getAsJsonObject("entrypoints");
        for (JsonElement main : entrypoints.getAsJsonArray("main")) {
            assertFalse(main.getAsString().startsWith("dev.sculptory.fabric.client."), "client class as main: " + main);
        }
        for (JsonElement client : entrypoints.getAsJsonArray("client")) {
            assertTrue(client.getAsString().startsWith("dev.sculptory.fabric.client."), "client entrypoint " + client);
        }
        int clientConfigs = 0;
        for (JsonElement entry : mod.getAsJsonArray("mixins")) {
            if (entry.isJsonPrimitive()) {
                JsonObject config = JsonParser.parseString(Files.readString(resources.resolve(entry.getAsString())))
                        .getAsJsonObject();
                assertFalse(config.has("client"), entry + " is loaded everywhere but lists client mixins");
                assertFalse(config.get("package").getAsString().startsWith("dev.sculptory.fabric.client"),
                        entry + " is loaded everywhere but holds client mixins");
            } else {
                JsonObject declared = entry.getAsJsonObject();
                assertEquals("client", declared.get("environment").getAsString(), "mixin config " + declared);
                clientConfigs++;
            }
        }
        assertEquals(1, clientConfigs, "the client mixin config");
    }
}
