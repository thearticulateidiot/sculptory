package dev.sculptory.fabric.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.engine.impl.EngineRuntime;
import dev.sculptory.server.config.SculptoryConfig;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What {@code /sculptory reload} and {@code /sculptory version} say (the commands themselves: ConfigReloadGameTest). */
class SculptoryCommandsTest {
    private static final Path FILE = Path.of("config", "sculptory", "server.json");

    @Test
    void aFailedReloadSaysWhyAndThatNothingChanged() {
        String text = SculptoryCommands.reloadReport(new EngineRuntime.Reload(FILE, "the file is empty", List.of(),
                List.of(), List.of()));
        assertTrue(text.startsWith("Sculptory config not reloaded: "), text);
        assertTrue(text.contains(FILE.toAbsolutePath() + " can't be used (the file is empty)"), text);
        assertTrue(text.contains("The running settings stay as they were"), text);
    }

    @Test
    void aReloadListsWhatItAppliedWhatWaitsForARestartAndWhatItAdjusted() {
        String nothing = SculptoryCommands.reloadReport(new EngineRuntime.Reload(FILE, null, List.of(), List.of(),
                List.of()));
        assertEquals("Sculptory config reloaded from " + FILE.toAbsolutePath() + ". Nothing changed.", nothing);

        String text = SculptoryCommands.reloadReport(new EngineRuntime.Reload(FILE, null,
                List.of(new SculptoryConfig.Change("limits.maxOpVolume", "2097152", "1000"),
                        new SculptoryConfig.Change("editingEnabled", "true", "false")),
                List.of(new SculptoryConfig.Change("history.persist", "true", "false")),
                List.of("limits.maxBrushRadius = 64 out of range [1, 32]; using 32")));
        assertEquals("""
                Sculptory config reloaded from %s.
                Now in effect: limits.maxOpVolume 2097152 -> 1000, editingEnabled true -> false
                Edits already running keep the limits they started with.
                Changed in the file, but only a restart applies them: history.persist true -> false
                Out of range, so adjusted: limits.maxBrushRadius = 64 out of range [1, 32]; using 32"""
                .formatted(FILE.toAbsolutePath()), text);
        assertFalse(text.contains("Nothing changed"));
    }

    @Test
    void versionDescribesTheClientsBuildAgainstTheServers() {
        String server = "0.2.0-dev+6a043a85";
        assertEquals("no Sculptory editor connected", SculptoryCommands.describeClient("", false, server));
        assertEquals(server + ", the same build", SculptoryCommands.describeClient(server, false, server));
        assertTrue(SculptoryCommands.describeClient("0.2.0-dev+11111111", false, server)
                .startsWith("0.2.0-dev+11111111, a different build of the same protocol: editing works"));
        assertTrue(SculptoryCommands.describeClient("0.3.0+22222222", true, server)
                .startsWith("0.3.0+22222222, another protocol: editing is off"));
        assertEquals("unknown, the same protocol (which build isn't known)",
                SculptoryCommands.describeClient("unknown", false, server), "an unknown build is neither same nor different");
    }
}
