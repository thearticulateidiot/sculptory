package dev.sculptory.fabric.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.history.HistoryLimits;
import dev.sculptory.fabric.library.Library;
import dev.sculptory.protocol.v2.Limits;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SculptoryConfigTest {
    @TempDir
    Path dir;

    @Test
    void firstStartWritesDefaults() throws Exception {
        Path file = dir.resolve("sculptory").resolve("server.json");
        SculptoryConfig config = SculptoryConfig.load(file);
        assertTrue(Files.isRegularFile(file));
        assertTrue(config.editingEnabled);
        assertEquals(2, config.permissionFallbackOpLevel);
        assertTrue(config.singleplayerHostAlwaysAllowed);
        assertEquals(UnloadedPolicy.LOAD, config.unloadedChunks);
        assertEquals(10_000_000L, config.tickBudgetNanos(true));
        assertEquals(20_000_000L, config.tickBudgetNanos(false));
        Limits d = Limits.DEFAULTS;
        assertEquals(new Limits(d.maxOpVolume(), d.maxClipboardVolume(), d.maxBrushRadius(), d.maxDabRate(),
                SculptoryConfig.DEFAULT_UPLOAD_BYTES, d.maxJobsPerPlayer()), config.toLimits(), "uploads default to 16 MiB");
        assertEquals(HistoryLimits.DEFAULTS, config.toHistoryLimits());
        assertEquals(Library.Settings.DEFAULTS, config.toLibrarySettings());
        assertTrue(config.transform.moddedFacingFallback, "the modded facing fallback is on by default");
        String json = Files.readString(file);
        for (String key : new String[] {"editingEnabled", "permissionFallbackOpLevel", "singleplayerHostAlwaysAllowed",
                "tickBudgetMsDedicated", "tickBudgetMsIntegrated", "maxBlocksPerTick", "maxEntriesPerPlayer",
                "maxBytesTotal", "maxOpVolume", "unloadedChunks", "maxFileBytes", "maxPlayerBytes", "transform",
                "moddedFacingFallback"}) {
            assertTrue(json.contains("\"" + key + "\""), key);
        }
        // Reading it back gives the same values.
        assertEquals(config.toJson(), SculptoryConfig.load(file).toJson());
    }

    @Test
    void missingKeysKeepDefaultsAndValuesAreClamped() throws Exception {
        Path file = dir.resolve("server.json");
        Files.writeString(file, """
                {
                  "editingEnabled": false,
                  "permissionFallbackOpLevel": 9,
                  "executor": { "tickBudgetMsDedicated": 0, "brushLaneShare": 3.5, "maxQueuedJobs": 0 },
                  "limits": { "maxBrushRadius": 500 },
                  "unloadedChunks": "REFUSE"
                }
                """, StandardCharsets.UTF_8);
        SculptoryConfig config = SculptoryConfig.load(file);
        assertFalse(config.editingEnabled);
        assertEquals(4, config.permissionFallbackOpLevel);
        assertEquals(1, config.executor.tickBudgetMsDedicated);
        assertEquals(20, config.executor.tickBudgetMsIntegrated);
        assertEquals(1.0, config.executor.brushLaneShare);
        assertEquals(1, config.executor.maxQueuedJobs);
        assertEquals(32, config.limits.maxBrushRadius);
        assertEquals(Limits.DEFAULTS.maxOpVolume(), config.limits.maxOpVolume);
        assertEquals(HistoryLimits.DEFAULTS, config.toHistoryLimits());
        assertEquals(UnloadedPolicy.REFUSE, config.unloadedChunks);
        assertTrue(Files.readString(file).contains("\"permissionFallbackOpLevel\": 9"), "existing file rewritten");
    }

    /** An old file keeps its values; the keys it lacks are listed (for the start-up INFO line) and use the defaults. */
    @Test
    void oldFilesKeepTheirValuesAndMissingKeysAreListed() throws Exception {
        Path file = dir.resolve("server.json");
        String old = """
                {
                  "editingEnabled": true,
                  "executor": { "tickBudgetMsDedicated": 10, "maxQueuedJobs": 32 },
                  "history": null,
                  "limits": { "maxUploadBytes": 33554432, "maxJobsPerPlayer": 2 },
                  "unloadedChunks": "LOAD"
                }
                """;
        Files.writeString(file, old, StandardCharsets.UTF_8);
        SculptoryConfig config = SculptoryConfig.load(file);
        assertEquals(32L << 20, config.limits.maxUploadBytes, "the file's value, not the newer 16 MiB default");
        assertEquals(8, config.executor.maxQueuedJobsPerPlayer);
        assertEquals(Library.Settings.DEFAULTS, config.toLibrarySettings());
        assertEquals(old, Files.readString(file), "the file is never rewritten");
        assertEquals(List.of("permissionFallbackOpLevel", "singleplayerHostAlwaysAllowed",
                        "executor.tickBudgetMsIntegrated", "executor.maxBlocksPerTick", "executor.brushLaneShare",
                        "executor.maxActiveJobsGlobal", "executor.maxQueuedJobsPerPlayer", "executor.maxChunkTicketsPerJob",
                        "executor.maxQueuedBrushWork", "executor.maxColumnsPerJob", "history", "limits.maxOpVolume",
                        "limits.maxClipboardVolume", "limits.maxBrushRadius", "limits.maxDabRate",
                        "limits.maxSelectionCells", "limits.maxSelectionSections", "limits.maxSelectionStoreBytes",
                        "limits.maxSelectionStoreBytesTotal", "library",
                        "scatter",
                        "transform", "entities", "builder", "navigate"),
                SculptoryConfig.missingKeys(old));
        assertEquals(List.of(), SculptoryConfig.missingKeys(SculptoryConfig.defaults().toJson()),
                "a file written by this build lacks nothing");
    }

    @Test
    void theModdedFacingFallbackCanBeSwitchedOffAndDefaultsToOn() throws Exception {
        Path file = dir.resolve("server.json");
        Files.writeString(file, "{\"transform\": {\"moddedFacingFallback\": false}}", StandardCharsets.UTF_8);
        assertFalse(SculptoryConfig.load(file).transform.moddedFacingFallback);
        // A file from before the key (or with the section null) keeps the default.
        for (String older : new String[] {"{}", "{\"transform\": null}", "{\"transform\": {}}"}) {
            Files.writeString(file, older, StandardCharsets.UTF_8);
            SculptoryConfig config = SculptoryConfig.load(file);
            assertTrue(config.editingEnabled, older);
            assertTrue(config.transform.moddedFacingFallback, older);
        }
    }

    @Test
    void unknownPolicyFallsBackToRefuseAndNullSectionsToDefaults() throws Exception {
        Path file = dir.resolve("server.json");
        Files.writeString(file, "{\"unloadedChunks\": \"SOMETIMES\", \"history\": null}", StandardCharsets.UTF_8);
        SculptoryConfig config = SculptoryConfig.load(file);
        assertEquals(UnloadedPolicy.REFUSE, config.unloadedChunks, "unknown policy must pick the safe option");
        assertTrue(config.editingEnabled);
        assertEquals(HistoryLimits.DEFAULTS, config.toHistoryLimits());
        for (String unusable : new String[] {"null", "3", "{}", "\"\""}) {
            Files.writeString(file, "{\"unloadedChunks\": " + unusable + "}", StandardCharsets.UTF_8);
            assertEquals(UnloadedPolicy.REFUSE, SculptoryConfig.load(file).unloadedChunks, unusable);
        }
    }

    @Test
    void policyNamesIgnoreCase() throws Exception {
        Path file = dir.resolve("server.json");
        Files.writeString(file, "{\"unloadedChunks\": \"load\"}", StandardCharsets.UTF_8);
        assertEquals(UnloadedPolicy.LOAD, SculptoryConfig.load(file).unloadedChunks);
        Files.writeString(file, "{\"unloadedChunks\": \" Refuse \"}", StandardCharsets.UTF_8);
        assertEquals(UnloadedPolicy.REFUSE, SculptoryConfig.load(file).unloadedChunks);
    }

    @Test
    void unreadableFilesDisableEditingAndAreLeftAlone() throws Exception {
        Path file = dir.resolve("server.json");
        for (String broken : new String[] {"{ this is not json", "", "   ", "[1, 2]", "{\"editingEnabled\": \"yes\"}",
                "{\"executor\": {\"maxQueuedJobs\": \"many\"}}"}) {
            Files.writeString(file, broken, StandardCharsets.UTF_8);
            SculptoryConfig config = SculptoryConfig.load(file);
            assertFalse(config.editingEnabled, "editing must be disabled for: " + broken);
            assertEquals(broken, Files.readString(file), "file rewritten");
            SculptoryConfig expected = SculptoryConfig.defaults();
            expected.editingEnabled = false;
            assertEquals(expected.toJson(), config.toJson(), "otherwise defaults for: " + broken);
        }
    }

    // ---------------------------------------------------------------- reload (/sculptory reload, EngineRuntime.reload)

    /** A reload's read never writes: a missing, unreadable or invalid file gives no config and says why. */
    @Test
    void readingForAReloadReportsEveryUnusableFileAndWritesNothing() throws Exception {
        Path missing = dir.resolve("missing.json");
        SculptoryConfig.Read read = SculptoryConfig.read(missing);
        assertNull(read.config());
        assertTrue(read.problem().contains("doesn't exist"), read.problem());
        assertFalse(Files.exists(missing), "a reload must not write the defaults");

        Path file = dir.resolve("server.json");
        // (A string where a boolean belongs is read as false, not refused: Gson parses "yes" as false.)
        for (String broken : new String[] {"{ this is not json", "", "   ", "[1, 2]", "{\"editingEnabled\": [1]}",
                "{\"limits\": {\"maxOpVolume\": \"lots\"}}"}) {
            Files.writeString(file, broken, StandardCharsets.UTF_8);
            read = SculptoryConfig.read(file);
            assertNull(read.config(), "no config for: " + broken);
            assertNotNull(read.problem(), "a reason for: " + broken);
            assertFalse(read.problem().isBlank(), "a reason for: " + broken);
            assertEquals(broken, Files.readString(file), "file rewritten");
        }

        // A folder where the file should be can't be read either.
        Path folder = Files.createDirectories(dir.resolve("folder.json"));
        read = SculptoryConfig.read(folder);
        assertNull(read.config());
        assertNotNull(read.problem());
    }

    @Test
    void anAdjustmentNamesItsKeyFirst() {
        assertEquals("limits.maxBrushRadius", SculptoryConfig.adjustmentKey("limits.maxBrushRadius = 64 out of range"));
        assertEquals("unloadedChunks", SculptoryConfig.adjustmentKey("unloadedChunks is not one of [LOAD, REFUSE]"));
        assertTrue(SculptoryConfig.needsRestart(SculptoryConfig.adjustmentKey("history.maxEntriesPerPlayer = 0 x")));
    }

    @Test
    void readingForAReloadClampsAndListsWhatItAdjusted() throws Exception {
        Path file = dir.resolve("server.json");
        Files.writeString(file, "{\"limits\": {\"maxBrushRadius\": 64}, \"executor\": {\"brushLaneShare\": 7}}",
                StandardCharsets.UTF_8);
        SculptoryConfig.Read read = SculptoryConfig.read(file);
        assertNull(read.problem());
        assertEquals(32, read.config().limits.maxBrushRadius);
        assertEquals(List.of("executor.brushLaneShare = 7.0 out of range [0.05, 1.0]; clamped",
                "limits.maxBrushRadius = 64 out of range [1, 32]; using 32"), read.config().adjustments());
        assertEquals(List.of(), SculptoryConfig.defaults().sanitize().adjustments());
    }

    /** Changes are dotted keys with both values, in the default file's order; nothing for equal configs. */
    @Test
    void changesListEverySettingThatDiffers() {
        SculptoryConfig before = SculptoryConfig.defaults();
        assertEquals(List.of(), SculptoryConfig.changes(before, SculptoryConfig.defaults()));
        SculptoryConfig after = SculptoryConfig.defaults();
        after.editingEnabled = false;
        after.limits.maxOpVolume = 1000;
        after.history.maxEntriesPerPlayer = 10;
        after.unloadedChunks = UnloadedPolicy.REFUSE;
        List<SculptoryConfig.Change> changes = SculptoryConfig.changes(before, after);
        assertEquals(List.of("editingEnabled true -> false",
                "history.maxEntriesPerPlayer " + HistoryLimits.DEFAULTS.maxEntries() + " -> 10",
                "limits.maxOpVolume 2097152 -> 1000", "unloadedChunks \"LOAD\" -> \"REFUSE\""),
                changes.stream().map(SculptoryConfig.Change::toString).toList());
        assertEquals(List.of(false, true, false, false),
                changes.stream().map(SculptoryConfig.Change::needsRestart).toList());
    }

    /** history, library and transform are wired in at start: a reload keeps the running ones. */
    @Test
    void restartSectionsAreKeptFromTheRunningConfig() {
        for (String key : List.of("history.persist", "history.maxEntriesPerPlayer", "library.maxFileBytes",
                "transform.moddedFacingFallback")) {
            assertTrue(SculptoryConfig.needsRestart(key), key);
        }
        for (String key : List.of("editingEnabled", "permissionFallbackOpLevel", "executor.tickBudgetMsDedicated",
                "limits.maxOpVolume", "scatter.maxWork", "entities.maxPerJob", "unloadedChunks")) {
            assertFalse(SculptoryConfig.needsRestart(key), key);
        }
        SculptoryConfig running = SculptoryConfig.defaults();
        SculptoryConfig file = SculptoryConfig.defaults();
        file.history.persist = false;
        file.library.trashDays = 3;
        file.transform.moddedFacingFallback = false;
        file.limits.maxBrushRadius = 8;
        SculptoryConfig next = file.keepRestartSectionsOf(running);
        assertSame(running.history, next.history);
        assertSame(running.library, next.library);
        assertSame(running.transform, next.transform);
        assertEquals(8, next.limits.maxBrushRadius, "the rest comes from the file");
        assertTrue(next.history.persist);
    }
}
