package dev.sculptory.fabric.client.editor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.brush.Falloff;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.scatter.ScatterSource;
import dev.sculptory.fabric.client.editor.presets.PresetExtra;
import dev.sculptory.fabric.client.editor.presets.PresetStore;
import dev.sculptory.fabric.client.editor.presets.Presets;
import dev.sculptory.fabric.client.editor.presets.ToolSettingsFile;
import dev.sculptory.fabric.client.editor.presets.ToolSettingsStore;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tools.brush.BrushSettings;
import dev.sculptory.fabric.client.editor.tools.brush.ShapeSettings;
import dev.sculptory.fabric.client.editor.tools.brush.TerrainBrushTool;
import dev.sculptory.fabric.client.editor.tools.scatter.ScatterMix;
import dev.sculptory.fabric.client.editor.tools.scatter.ScatterMixPreset;
import dev.sculptory.fabric.client.editor.tools.scatter.ScatterTool;
import dev.sculptory.fabric.client.session.Notice;
import dev.sculptory.fabric.client.session.Permissions;
import dev.sculptory.fabric.client.util.AtomicFileStore;
import dev.sculptory.protocol.v2.Limits;
import dev.sculptory.server.engine.Perm;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Each tool's settings kept across a game restart ({@link ToolSettingsStore}, {@code editor-tool-settings.json})
 * against the editor on the mock session: what comes back, how it goes with presets, and every way the file can be
 * wrong (missing, empty, corrupt, cut short, partly unreadable, from a newer version, holding tools and settings this
 * build doesn't have, holding values out of range), and how it is written (settled, atomically, never over a change
 * made outside the game, never before the settings were restored).
 */
class ToolSettingsStoreTest {
    private static final Limits RADIUS_16 = new Limits(2_097_152L, 2_097_152L, 16, 20, 32L << 20, 2);
    /** A library asset in the Scatter mix, weight 3. */
    private static final ScatterMix.Variant OAK_VARIANT = new ScatterMix.Variant(
            new ScatterSource.Held(new SourceRef.Asset("ab".repeat(32))), "trees/oak.schem", 3);

    @TempDir
    Path dir;

    private final List<String> fileProblems = new ArrayList<>();

    /** An extra whose restore fails (an error in a tool's code), for the tools around it. */
    private static final PresetExtra BROKEN = new PresetExtra() {
        @Override
        public Capture capture() {
            return new Capture("", null);
        }

        @Override
        public Applied apply(String text) {
            throw new IllegalStateException("broken extra: " + text);
        }

        @Override
        public boolean matches(String text) {
            return false;
        }
    };

    /** One game session: an editor on the mock session with its own settings, the files in {@link #dir}. */
    private final class Game {
        final EditorTestRig rig = new EditorTestRig();
        final ToolSettingsStore store;
        final Presets presets;

        Game() {
            this(new AtomicFileStore());
        }

        /** With the settings file kept in {@code files} (a small limit makes saves fail). */
        Game(AtomicFileStore files) {
            store = new ToolSettingsStore(new ConfigFile(file(), files, fileProblems::add), rig.ctx, Translator.KEYS,
                    block -> true);
            store.addExtra(ToolId.SCATTER, ScatterMixPreset.KEY, new ScatterMixPreset(scatter()));
            store.addExtra(ToolId.RAISE, "broken", BROKEN);
            store.load();
            rig.ctx.onSettingsChanged(id -> store.changed(0));
            presets = new Presets(new PresetStore(new ConfigFile(dir.resolve(PresetStore.FILE_NAME),
                    new AtomicFileStore(PresetStore.MAX_BYTES), fileProblems::add)), rig.ctx, Translator.KEYS,
                    block -> true);
            presets.addExtra(ToolId.SCATTER, ScatterMixPreset.KEY, new ScatterMixPreset(scatter()));
            presets.load();
        }

        /** The editor opens the first time: the last settings, then the presets of the tools without them. */
        Set<ToolId> open() {
            Set<ToolId> restored = store.restore();
            presets.restoreSelections(restored);
            return restored;
        }

        BrushSettings brush(ToolId id) {
            return ((TerrainBrushTool) rig.ctx.tools().get(id).orElseThrow()).settings();
        }

        ScatterTool scatter() {
            return (ScatterTool) rig.ctx.tools().get(ToolId.SCATTER).orElseThrow();
        }

        SettingsValues settings(ToolId id) {
            return rig.ctx.settings(id);
        }

        void set(ToolId id, SettingsValues values) {
            rig.ctx.updateSettings(id, values);
        }

        List<String> noticeKeys() {
            return rig.notices.stream().map(Notice::key).toList();
        }
    }

    private Path file() {
        return dir.resolve(ToolSettingsStore.FILE_NAME);
    }

    private String onDisk() throws IOException {
        return Files.readString(file());
    }

    private void write(String text) throws IOException {
        Files.writeString(file(), text);
    }

    /** Raise at radius 12, strength 0.8, linear falloff; Shape a 30-radius cone; Scatter mixing dirt. */
    private SettingsValues setUp(Game game) {
        BrushSettings raise = game.brush(ToolId.RAISE);
        SettingsValues soft = game.settings(ToolId.RAISE).with(raise.radius, 12).with(raise.strength, 0.8)
                .with(raise.falloff, Falloff.LINEAR);
        game.set(ToolId.RAISE, soft);
        game.set(ToolId.SHAPE, game.settings(ToolId.SHAPE).with(ShapeSettings.RADIUS, 30)
                .with(ShapeSettings.KIND, dev.sculptory.core.brush.ShapeSpec.Kind.CONE));
        game.scatter().replaceMix(List.of(OAK_VARIANT));
        return soft;
    }

    // ---- What comes back ----

    @Test
    void everyToolComesBackAsItWasLeftAfterARestart() throws IOException {
        Game first = new Game();
        assertEquals(Set.of(), first.open(), "no file yet: nothing restored");
        SettingsValues soft = setUp(first);
        SettingsValues shape = first.settings(ToolId.SHAPE);
        assertTrue(first.store.saveNow());
        ToolSettingsFile saved = ToolSettingsFile.fromJson(onDisk());
        assertEquals(first.rig.ctx.tools().paletteOrder().size(), saved.tools().size(), "every tool listed: " + saved.tools().keySet());
        assertEquals("12", saved.tools().get("raise").values().get("radius"));
        assertFalse(saved.tools().get("raise").values().containsKey("shape"), "a setting at its default isn't written");
        assertTrue(saved.tools().get("select").values().isEmpty(), "a tool at its defaults: listed, no values");

        Game second = new Game();
        Set<ToolId> restored = second.open();
        assertEquals(second.rig.ctx.tools().paletteOrder().size(), restored.size());
        // Each game has its own tools, so the values are compared as saved text.
        assertEquals(soft.encode(), second.settings(ToolId.RAISE).encode());
        assertEquals(shape.encode(), second.settings(ToolId.SHAPE).encode());
        assertEquals(List.of(OAK_VARIANT), second.scatter().mix().variants(), "the Scatter mix too");
        assertEquals(List.of(), second.noticeKeys(), "nothing to report");
        assertEquals(List.of(), fileProblems);
    }

    @Test
    void savedSettingsWinOverTheSelectedPresetWhichStaysSelectedAndModified() throws IOException {
        Game first = new Game();
        first.open();
        BrushSettings raise = first.brush(ToolId.RAISE);
        first.set(ToolId.RAISE, first.settings(ToolId.RAISE).with(raise.radius, 20));
        assertTrue(first.presets.saveAs(ToolId.RAISE, "Big"));
        first.set(ToolId.RAISE, first.settings(ToolId.RAISE).with(raise.radius, 7));
        assertTrue(first.presets.isModified(ToolId.RAISE));
        first.store.saveNow();

        Game second = new Game();
        second.open();
        assertEquals(7, second.settings(ToolId.RAISE).get(second.brush(ToolId.RAISE).radius), "the last value");
        assertEquals("Big", second.presets.selected(ToolId.RAISE));
        assertTrue(second.presets.isModified(ToolId.RAISE), "(modified), as it was");
    }

    @Test
    void aMissingFileRestoresNothingAndTheSelectedPresetsComeBackAsBefore() throws IOException {
        Game first = new Game();
        first.open();
        BrushSettings raise = first.brush(ToolId.RAISE);
        first.set(ToolId.RAISE, first.settings(ToolId.RAISE).with(raise.radius, 20));
        assertTrue(first.presets.saveAs(ToolId.RAISE, "Big"));
        first.set(ToolId.RAISE, first.settings(ToolId.RAISE).with(raise.radius, 7));
        // No save of the last settings (a build from before them, say).
        assertFalse(Files.exists(file()));

        Game second = new Game();
        assertEquals(Set.of(), second.open());
        assertEquals(20, second.settings(ToolId.RAISE).get(second.brush(ToolId.RAISE).radius), "the preset's value");
        assertFalse(second.presets.isModified(ToolId.RAISE));
        assertEquals(List.of(), fileProblems, "a missing file is no problem");
        assertTrue(second.store.saveNow(), "and it is written from then on");
        assertTrue(Files.exists(file()));
    }

    // ---- Every way the file can be wrong ----

    @Test
    void anEmptyFileIsReportedTheToolsStartFromTheirDefaultsAndItIsReplaced() throws IOException {
        write("  \n");
        Game game = new Game();
        assertEquals(1, fileProblems.size());
        assertTrue(fileProblems.get(0).contains("empty"), fileProblems.get(0));
        assertEquals(Set.of(), game.open());
        assertEquals(SettingsValues.defaults(game.brush(ToolId.RAISE).schema()), game.settings(ToolId.RAISE));
        setUp(game);
        assertTrue(game.store.saveNow());
        assertEquals("12", ToolSettingsFile.fromJson(onDisk()).tools().get("raise").values().get("radius"));
    }

    @Test
    void aCorruptFileIsKeptAsideReportedAndReplacedByTheNextSave() throws IOException {
        byte[] garbage = "{\"version\": 1, \"tools\": {\"raise\": \u0000 nonsense".getBytes(StandardCharsets.UTF_8);
        Files.write(file(), garbage);
        Game game = new Game();
        Path aside = dir.resolve(ToolSettingsStore.FILE_NAME + ".bad");
        assertArrayEquals(garbage, Files.readAllBytes(aside), "kept aside byte for byte");
        assertEquals(1, fileProblems.size());
        assertTrue(fileProblems.get(0).contains(".bad") && fileProblems.get(0).contains("not JSON"),
                fileProblems.get(0));
        assertEquals(Set.of(), game.open(), "the defaults");
        setUp(game);
        assertTrue(game.store.saveNow(), "the bad file is replaced");
        assertEquals("12", ToolSettingsFile.fromJson(onDisk()).tools().get("raise").values().get("radius"));
        assertArrayEquals(garbage, Files.readAllBytes(aside), "the copy stays as it was");

        // Corrupt again: a new name, the first copy never overwritten.
        Files.writeString(file(), "[1, 2");
        new Game();
        assertArrayEquals(garbage, Files.readAllBytes(aside));
        assertEquals("[1, 2", Files.readString(dir.resolve(ToolSettingsStore.FILE_NAME + ".bad-2")));
    }

    @Test
    void aFileCutShortIsCorruptAndSoIsOneThatIsntASettingsFile() throws IOException {
        Game first = new Game();
        first.open();
        setUp(first);
        first.store.saveNow();
        String whole = onDisk();
        write(whole.substring(0, whole.length() / 2));
        Game cut = new Game();
        assertEquals(Set.of(), cut.open());
        assertTrue(Files.exists(dir.resolve(ToolSettingsStore.FILE_NAME + ".bad")));
        for (String wrong : List.of("[]", "\"text\"", "{}", "{\"version\": \"1\"}", "{\"version\": 1.5}",
                "{\"version\": 1, \"tools\": []}")) {
            assertThrows(IllegalArgumentException.class, () -> ToolSettingsFile.fromJson(wrong), wrong);
        }
    }

    @Test
    void aPartlyUnreadableFileRestoresWhatItCanAndSkipsTheRestWithOneNotice() throws IOException {
        write("""
                {"version": 1, "tools": {
                  "raise": {"values": {"radius": "12", "strength": 0.8, "falloff": "LINEAR"}},
                  "lower": ["not", "an", "object"],
                  "Not A Tool!": {"values": {}},
                  "smooth": {"values": {"radius": "not a number"}},
                  "shape": {"values": {"radius": "30"}, "extra": "not an object"}
                }}""");
        String written = onDisk();
        Game game = new Game();
        assertEquals(List.of(), fileProblems, "readable as a whole");
        assertEquals(List.of("sculptory.tool_settings.notice.unreadable_kept"), game.noticeKeys());
        assertEquals(List.of(ToolSettingsStore.FILE_NAME, "4", ToolSettingsStore.FILE_NAME + ".bad"),
                game.rig.notices.get(0).args(), "strength (not text), lower, the bad id and shape's extra");
        assertEquals(written, Files.readString(dir.resolve(ToolSettingsStore.FILE_NAME + ".bad")),
                "the next save drops what couldn't be read, so the file as it was is kept beside it");
        game.rig.notices.clear();
        Set<ToolId> restored = game.open();
        assertEquals(Set.of(ToolId.RAISE, ToolId.SMOOTH, ToolId.SHAPE), restored, "the tools missing: as by default");
        BrushSettings raise = game.brush(ToolId.RAISE);
        assertEquals(12, game.settings(ToolId.RAISE).get(raise.radius));
        assertEquals(Falloff.LINEAR, game.settings(ToolId.RAISE).get(raise.falloff));
        assertEquals(raise.strength.defaultValue(), game.settings(ToolId.RAISE).get(raise.strength),
                "the value that wasn't text: its default");
        assertEquals(SettingsValues.defaults(game.brush(ToolId.SMOOTH).schema()), game.settings(ToolId.SMOOTH),
                "a value that doesn't decode: the default");
        assertTrue(game.noticeKeys().contains("sculptory.tool_settings.notice.skipped"), game.noticeKeys().toString());
        assertEquals(30, game.settings(ToolId.SHAPE).get(ShapeSettings.RADIUS));
    }

    @Test
    void toolsAndSettingsThisBuildDoesntHaveAreLeftUnusedAndWrittenBack() throws IOException {
        write("""
                {"version": 1, "tools": {
                  "raise": {"values": {"radius": "12", "later_setting": "on"}, "extra": {"later": "x"}},
                  "later_tool": {"values": {"size": "3"}, "extra": {"mix": "whatever"}}
                }}""");
        Game game = new Game();
        assertEquals(Set.of(ToolId.RAISE), game.open());
        assertEquals(12, game.settings(ToolId.RAISE).get(game.brush(ToolId.RAISE).radius));
        assertEquals(List.of(), game.noticeKeys(), "not reported: a newer build's");
        game.store.saveNow();
        ToolSettingsFile saved = ToolSettingsFile.fromJson(onDisk());
        assertEquals("on", saved.tools().get("raise").values().get("later_setting"));
        assertEquals("x", saved.tools().get("raise").extra().get("later"));
        assertEquals("3", saved.tools().get("later_tool").values().get("size"));
        assertEquals("whatever", saved.tools().get("later_tool").extra().get("mix"));
    }

    @Test
    void valuesOutOfRangeAreClampedThroughTheSchemaAndTheServersLimits() throws IOException {
        write("""
                {"version": 1, "tools": {
                  "raise": {"values": {"radius": "99", "strength": "7"}},
                  "shape": {"values": {"radius": "30"}},
                  "lower": {"values": {"mask.slope": "10..500", "mask.invert": "true"}}
                }}""");
        Game game = new Game();
        game.rig.session.setPermissions(new Permissions(Perm.mask(EnumSet.allOf(Perm.class)), RADIUS_16));
        game.open();
        BrushSettings raise = game.brush(ToolId.RAISE);
        assertEquals(16, game.settings(ToolId.RAISE).get(raise.radius), "the server's brush radius");
        assertEquals(1.0, game.settings(ToolId.RAISE).get(raise.strength), 1e-9, "the setting's own maximum");
        assertEquals(16, game.settings(ToolId.SHAPE).get(ShapeSettings.RADIUS));
        BrushSettings lower = game.brush(ToolId.LOWER);
        assertEquals(SettingsValues.defaults(lower.schema()).get(lower.maskSlope), game.settings(ToolId.LOWER)
                .get(lower.maskSlope), "a mask that doesn't fit whole stays at its defaults");
        assertFalse(game.settings(ToolId.LOWER).get(lower.maskInvert), "the whole section");
        List<String> keys = game.noticeKeys();
        assertEquals(2, keys.stream().filter("sculptory.tool_settings.notice.adjusted"::equals).count(), keys.toString());
        assertTrue(keys.contains("sculptory.tool_settings.notice.withheld"), keys.toString());
    }

    @Test
    void aFileOfANewerVersionIsLeftUnchangedAndTheSettingsLastTheSession() throws IOException {
        String newer = "{\"version\": 2, \"tools\": {\"raise\": {\"values\": {\"radius\": \"12\"}}}}\n";
        write(newer);
        Game game = new Game();
        assertEquals(1, fileProblems.size());
        assertTrue(fileProblems.get(0).contains("version 2") && fileProblems.get(0).contains("left unchanged"),
                fileProblems.get(0));
        assertEquals(Set.of(), game.open());
        setUp(game);
        assertFalse(game.store.saveNow());
        assertEquals(newer, onDisk(), "never overwritten");
        assertFalse(Files.exists(dir.resolve(ToolSettingsStore.FILE_NAME + ".bad")), "nor kept aside");
    }

    // ---- How it is written ----

    @Test
    void nothingIsSavedBeforeTheSettingsWereRestored() throws IOException {
        Game first = new Game();
        first.open();
        setUp(first);
        first.store.saveNow();
        String saved = onDisk();

        // A game started and closed without opening the editor: the defaults in memory don't replace the file.
        Game second = new Game();
        second.store.changed(0);
        second.store.saveIfSettled(10_000);
        assertFalse(second.store.saveNow());
        assertEquals(saved, onDisk());
    }

    @Test
    void aChangeIsSavedOnceItSettles() throws IOException {
        Game game = new Game();
        game.open();
        game.store.saveNow();
        String before = onDisk();
        BrushSettings raise = game.brush(ToolId.RAISE);
        game.set(ToolId.RAISE, game.settings(ToolId.RAISE).with(raise.radius, 9));
        game.store.changed(1_000);
        game.store.saveIfSettled(1_000 + ToolSettingsStore.SETTLE_MS - 1);
        assertEquals(before, onDisk(), "not while it may still change");
        game.store.saveIfSettled(1_000 + ToolSettingsStore.SETTLE_MS);
        assertEquals("9", ToolSettingsFile.fromJson(onDisk()).tools().get("raise").values().get("radius"));
    }

    @Test
    void savesAreAtomicAndNeverOverAChangeMadeOutsideTheGame() throws IOException {
        Game game = new Game();
        game.open();
        assertTrue(game.store.saveNow());
        assertTrue(Files.exists(dir.resolve(ToolSettingsStore.FILE_NAME + ".lock")), "through the atomic file store");
        try (var listing = Files.list(dir)) {
            assertTrue(listing.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")), "no temporary left");
        }
        String outside = "{\"version\": 1, \"tools\": {}}\n";
        write(outside);
        setUp(game);
        assertFalse(game.store.saveNow());
        assertEquals(outside, onDisk(), "the change made outside is kept");
        assertTrue(fileProblems.stream().anyMatch(problem -> problem.contains("changed outside")), fileProblems.toString());
    }

    @Test
    void theTourKeepsItsChangesForTheSessionOnly() throws IOException {
        Game first = new Game();
        first.open();
        setUp(first);
        first.store.saveNow();
        String saved = onDisk();
        Game tour = new Game();
        tour.store.keepForSessionOnly();
        tour.open();
        tour.set(ToolId.RAISE, SettingsValues.defaults(tour.brush(ToolId.RAISE).schema()));
        assertFalse(tour.store.saveNow());
        assertEquals(saved, onDisk());
        assertEquals("12", ToolSettingsFile.fromJson(onDisk()).tools().get("raise").values().get("radius"));
    }

    /**
     * A value restore had to fit (clamped to the server's limit, to the setting's own range, or skipped as unreadable)
     * is not what the player set: while they haven't changed it, the file keeps its own text, so a radius the server
     * here clamps comes back whole on a server that allows it. Once they change it, the current value is written,
     * even if they set it back to the fitted value.
     */
    @Test
    void aValueRestoreFittedIsWrittenBackAsTheFileHadItUntilThePlayerChangesIt() throws IOException {
        String mix = "asset:3:" + "ab".repeat(32) + ":trees/oak.schem";
        write("""
                {"version": 1, "tools": {
                  "raise": {"values": {"radius": "24", "strength": "7", "falloff": "LINEAR"}},
                  "smooth": {"values": {"radius": "not a number"}},
                  "scatter": {"values": {}, "extra": {"mix": "%s;nonsense"}}
                }}""".formatted(mix));
        Game game = new Game();
        game.rig.session.setPermissions(new Permissions(Perm.mask(EnumSet.allOf(Perm.class)), RADIUS_16));
        game.open();
        BrushSettings raise = game.brush(ToolId.RAISE);
        assertEquals(16, game.settings(ToolId.RAISE).get(raise.radius), "fitted to the server here");
        assertEquals(1.0, game.settings(ToolId.RAISE).get(raise.strength), 1e-9);
        assertEquals(List.of(OAK_VARIANT), game.scatter().mix().variants(), "the usable part of the mix");
        assertTrue(game.store.saveNow());
        ToolSettingsFile saved = ToolSettingsFile.fromJson(onDisk());
        assertEquals("24", saved.tools().get("raise").values().get("radius"), "the file's text, not the fitted 16");
        assertEquals("7", saved.tools().get("raise").values().get("strength"));
        assertEquals("LINEAR", saved.tools().get("raise").values().get("falloff"));
        assertEquals("not a number", saved.tools().get("smooth").values().get("radius"), "unreadable, unchanged");
        assertEquals(mix + ";nonsense", saved.tools().get("scatter").extra().get("mix"), "the extras too");

        game.scatter().replaceMix(List.of());
        assertTrue(game.store.saveNow());
        assertFalse(ToolSettingsFile.fromJson(onDisk()).tools().get("scatter").extra().containsKey("mix"),
                "the mix changed (to the default, empty): written as it is now");

        game.set(ToolId.RAISE, game.settings(ToolId.RAISE).with(raise.radius, 20));
        assertTrue(game.store.saveNow());
        saved = ToolSettingsFile.fromJson(onDisk());
        assertEquals("20", saved.tools().get("raise").values().get("radius"), "changed: the player's value");
        assertEquals("7", saved.tools().get("raise").values().get("strength"), "untouched: still the file's");

        game.set(ToolId.RAISE, game.settings(ToolId.RAISE).with(raise.radius, 16));
        assertTrue(game.store.saveNow());
        assertEquals("16", ToolSettingsFile.fromJson(onDisk()).tools().get("raise").values().get("radius"),
                "set back to the fitted value by hand: that value, not 24");

        // The next game on the same server: strength 7 still fits to 1.0 and is still kept as 7.
        Game next = new Game();
        next.rig.session.setPermissions(new Permissions(Perm.mask(EnumSet.allOf(Perm.class)), RADIUS_16));
        next.open();
        assertEquals(1.0, next.settings(ToolId.RAISE).get(next.brush(ToolId.RAISE).strength), 1e-9);
        assertTrue(next.store.saveNow());
        assertEquals("7", ToolSettingsFile.fromJson(onDisk()).tools().get("raise").values().get("strength"));
    }

    @Test
    void aToolWhoseRestoreFailsIsReportedAndSkippedAndTheOthersAreStillRestored() throws IOException {
        write("""
                {"version": 1, "tools": {
                  "raise": {"values": {"radius": "12"}, "extra": {"broken": "x"}},
                  "lower": {"values": {"radius": "9"}}
                }}""");
        Game game = new Game();
        assertEquals(Set.of(ToolId.LOWER), game.open(), "Raise is left to its preset");
        assertEquals(9, game.settings(ToolId.LOWER).get(game.brush(ToolId.LOWER).radius));
        Notice failed = game.rig.notices.stream()
                .filter(notice -> notice.key().equals("sculptory.tool_settings.notice.failed")).findFirst()
                .orElseThrow(() -> new AssertionError(game.noticeKeys().toString()));
        assertEquals("broken extra: x", failed.args().get(1));
        assertTrue(game.store.saveNow(), "saving goes on");
    }

    @Test
    void aChangeWhoseSaveFailedStaysPendingAndIsTriedAgainAtTheNextChangeNotEveryTick() throws IOException {
        Game game = new Game(new AtomicFileStore(64));
        game.open();
        BrushSettings raise = game.brush(ToolId.RAISE);
        game.set(ToolId.RAISE, game.settings(ToolId.RAISE).with(raise.radius, 9));
        game.store.changed(1_000);
        assertTrue(game.store.pending());
        game.store.saveIfSettled(1_000 + ToolSettingsStore.SETTLE_MS);
        assertTrue(game.store.pending(), "not saved: still pending");
        assertEquals(1, fileProblems.size(), fileProblems.toString());
        assertTrue(fileProblems.get(0).contains("64-byte limit"), fileProblems.get(0));
        game.store.saveIfSettled(1_000 + 10 * ToolSettingsStore.SETTLE_MS);
        assertEquals(1, fileProblems.size(), "not tried again every tick");
        game.store.changed(20_000);
        game.store.saveIfSettled(20_000 + ToolSettingsStore.SETTLE_MS);
        assertEquals(2, fileProblems.size(), "tried again once the settings change again");
        assertFalse(game.store.saveNow(), "and when the editor closes");
        assertTrue(game.store.pending());
        assertFalse(Files.exists(file()));
    }

    @Test
    void theFileRoundTrips() {
        ToolSettingsFile file = ToolSettingsFile.fromJson("""
                {"version": 1, "tools": {"scatter": {"values": {"seed": "7"},
                  "extra": {"mix": "block:1:minecraft:pink_petals[facing=east]"}}}}""");
        assertEquals(file, ToolSettingsFile.fromJson(file.toJson()));
        assertTrue(file.toJson().contains("pink_petals[facing=east]"), "readable: " + file.toJson());
    }
}
