package dev.sculptory.server.library;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sculptory.core.palette.BlockPalette;
import dev.sculptory.core.palette.PalettePattern;
import dev.sculptory.protocol.v2.AssetAccess;
import dev.sculptory.server.config.SculptoryConfig;
import dev.sculptory.server.config.UnloadedPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The server-side files real older builds wrote ({@code src/test/resources/compat/<version>/}, frozen: see the README
 * there) read by this build with every value as saved: a library palette with a mix pattern, a folder's access grants,
 * and the server config. Updating Sculptory must never lose or reset them.
 */
class CompatSamplesTest {
    private static final AssetAccess.Grantee ALICE =
            new AssetAccess.Grantee(UUID.fromString("11111111-2222-4333-8444-555555555555"), "Alice");
    private static final AssetAccess.Grantee BOB =
            new AssetAccess.Grantee(UUID.fromString("66666666-7777-4888-9999-aaaaaaaaaaaa"), "Bob");

    @TempDir
    Path dir;

    static byte[] sample(String version, String name) throws IOException {
        try (InputStream in = CompatSamplesTest.class.getResourceAsStream("/compat/" + version + "/" + name)) {
            assertNotNull(in, "the " + version + " sample " + name);
            return in.readAllBytes();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.3.0", "0.4.0"})
    void aLibraryPaletteKeepsItsBlocksWeightsAndPattern(String version) throws Exception {
        PaletteFile.Content content = PaletteFile.decode(sample(version, "mossy.palette.json"));
        assertEquals(List.of(new BlockPalette.Entry("minecraft:moss_block", 4),
                new BlockPalette.Entry("minecraft:sea_pickle[pickles=2,waterlogged=true]", 1),
                new BlockPalette.Entry("minecraft:mossy_cobblestone", 2)), content.entries());
        assertEquals(OptionalInt.of(3955), content.dataVersion());
        assertEquals(new PalettePattern(PalettePattern.Kind.PATCHES, 6, 4, 10, -8214629017740148339L), content.pattern());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.3.0", "0.4.0"})
    void accessGrantsAreKeptPerAsset(String version) throws IOException {
        Files.write(dir.resolve(AccessFile.NAME), sample(version, AccessFile.NAME));
        AccessFile.Loaded loaded = AccessFile.read(dir);
        assertFalse(loaded.corrupt(), "a sample that fails closed would lock every player out of the folder");
        assertEquals(Map.of("oak.schem", AssetAccess.listed(List.of(ALICE, BOB)),
                "mossy.palette.json", AssetAccess.listed(List.of(BOB))), loaded.entries());

        // Rewritten by this build, the grants are the same.
        AccessFile.write(dir, loaded.entries());
        assertEquals(loaded.entries(), AccessFile.read(dir).entries());
    }

    /**
     * Every key the older file holds reads back with its value, the keys this build added since take their defaults,
     * nothing is clamped, and loading never rewrites the file.
     */
    @ParameterizedTest
    @ValueSource(strings = {"0.3.0", "0.4.0"})
    void theServerConfigKeepsEveryValue(String version) throws IOException {
        Path file = dir.resolve("server.json");
        byte[] bytes = sample(version, "server.json");
        Files.write(file, bytes);
        SculptoryConfig.Read read = SculptoryConfig.read(file);
        assertNull(read.problem());
        SculptoryConfig config = read.config();
        assertEquals(List.of(), config.adjustments(), "nothing clamped");

        Map<String, JsonElement> inFile = flatten(JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)));
        Map<String, JsonElement> asRead = flatten(JsonParser.parseString(config.toJson()));
        Map<String, JsonElement> defaults = flatten(JsonParser.parseString(SculptoryConfig.defaults().toJson()));
        assertEquals(defaults.keySet(), asRead.keySet());
        inFile.forEach((key, value) -> assertEquals(value, asRead.get(key), key));
        defaults.forEach((key, value) -> {
            if (!inFile.containsKey(key)) assertEquals(value, asRead.get(key), key + ", added since: its default");
        });
        if (version.equals("0.3.0")) {
            assertTrue(!inFile.containsKey("limits.maxHeightmapRadius"), "0.3.0 had no Heightmap brush");
        } else {
            assertEquals(100, config.limits.maxHeightmapRadius);
            assertEquals(90, config.limits.maxHeightmapHeight);
        }

        // The values the sample set apart from the defaults, one by one.
        assertTrue(config.editingEnabled);
        assertEquals(3, config.permissionFallbackOpLevel);
        assertFalse(config.singleplayerHostAlwaysAllowed);
        assertEquals(UnloadedPolicy.REFUSE, config.unloadedChunks);
        assertEquals(7, config.executor.tickBudgetMsDedicated);
        assertEquals(5, config.executor.maxQueuedJobsPerPlayer);
        assertEquals(0.3, config.executor.brushLaneShare, 1e-12);
        assertEquals(77, config.history.maxEntriesPerPlayer);
        assertEquals(1L << 30, config.history.maxDiskBytes);
        assertEquals(12, config.history.maxAgeDays);
        assertEquals(24, config.limits.maxBrushRadius);
        assertEquals(32L << 20, config.limits.maxUploadBytes);
        assertEquals(9, config.library.trashDays);
        assertEquals(0.2, config.scatter.tickShare, 1e-12);
        assertFalse(config.transform.moddedFacingFallback);
        assertEquals(1000, config.entities.maxPerClipboard);
        assertEquals(40, config.builder.maxReach);
        assertEquals(200, config.navigate.maxDistance);

        SculptoryConfig loaded = SculptoryConfig.load(file);
        assertEquals(config.toJson(), loaded.toJson());
        assertArrayEquals(bytes, Files.readAllBytes(file), "an existing config is never rewritten");
    }

    private static Map<String, JsonElement> flatten(JsonElement tree) {
        Map<String, JsonElement> out = new TreeMap<>();
        flatten(tree, "", out);
        return out;
    }

    private static void flatten(JsonElement tree, String prefix, Map<String, JsonElement> out) {
        if (tree instanceof JsonObject object) {
            object.entrySet().forEach(entry -> flatten(entry.getValue(),
                    prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey(), out));
        } else {
            out.put(prefix, tree);
        }
    }
}
