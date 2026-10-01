package dev.sculptory.fabric.client.editor.check;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The play check's switch, its report and the fixture grid. */
class CheckFilesTest {
    @TempDir
    Path dir;

    @Test
    void theCheckIsOffWithoutTheProperty() {
        assertTrue(CheckConfig.of(null, "a", "visual", "x", "Iris").isEmpty());
        assertTrue(CheckConfig.of("  ", null, null, null, null).isEmpty());
    }

    @Test
    void propertiesGiveRoleSuiteScenariosAndLabel() {
        CheckConfig config = CheckConfig.of(dir.toString(), "B", "visual", "water, select ,", " Iris ").orElseThrow();
        assertEquals(CheckConfig.Role.B, config.role());
        assertEquals(CheckConfig.Suite.VISUAL, config.suite());
        assertEquals(List.of("water", "select"), config.only());
        assertEquals("Iris", config.label());
        CheckConfig defaults = CheckConfig.of(dir.toString(), null, "", null, null).orElseThrow();
        assertEquals(CheckConfig.Role.SOLO, defaults.role());
        assertEquals(CheckConfig.Suite.FULL, defaults.suite());
        assertEquals("vanilla", defaults.label());
        assertThrows(IllegalArgumentException.class, () -> CheckConfig.of("relative/dir", null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> CheckConfig.of(dir.toString(), "c", null, null, null));
        assertThrows(IllegalArgumentException.class, () -> CheckConfig.of(dir.toString(), null, "most", null, null));
    }

    @Test
    void theVisualSuiteAndPrefixesChooseScenarios() {
        CheckConfig visual = CheckConfig.of(dir.toString(), null, "visual", "", null).orElseThrow();
        assertTrue(visual.runs("opacity-panels", true));
        assertFalse(visual.runs("water-undo", false));
        CheckConfig some = CheckConfig.of(dir.toString(), null, null, "water,brush-raise", null).orElseThrow();
        assertTrue(some.runs("water-undo", false));
        assertTrue(some.runs("brush-raise-cliff", true));
        assertFalse(some.runs("brush-lower-ceiling", false));
    }

    @Test
    void theReportListsFailuresFirstThenEverythingThenPictures() throws IOException {
        CheckReport report = new CheckReport("Sculptory play check, test");
        report.note("Window: 1600x900 px");
        report.pass("select-fill", "the drag selects the box", "");
        report.fail("select-fill", "undo restores the area exactly", "2 cells differ:\n 1 2 3: a -> b");
        report.skip("iris", "shader pack", "no pack");
        report.pass("two-players", "B is refused", "Editing is off here — ask an operator");
        report.picture(new CheckReport.Picture("01-select-fill-filled.png", "select-fill", "planks in the floor"));
        Path file = report.write(dir);
        // UTF-8 without a byte order mark, whatever the platform's default charset.
        byte[] bytes = Files.readAllBytes(file);
        assertEquals('#', bytes[0]);
        String text = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(text.contains("B is refused -- Editing is off here — ask an operator"));
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals("# Sculptory play check, test", lines.get(0));
        assertEquals("# 4 checks: 2 PASS, 1 FAIL, 1 SKIPPED; 1 pictures", lines.get(1));
        assertEquals("# Window: 1600x900 px", lines.get(2));
        int failed = lines.indexOf("== Failed ==");
        assertEquals("FAIL    select-fill / undo restores the area exactly -- 2 cells differ: 1 2 3: a -> b",
                lines.get(failed + 1));
        int results = lines.indexOf("== Results ==");
        assertEquals("PASS    select-fill / the drag selects the box", lines.get(results + 1));
        assertEquals("SKIPPED iris / shader pack -- no pack", lines.get(results + 3));
        assertTrue(lines.contains("01-select-fill-filled.png  [select-fill] planks in the floor"));
        assertFalse(Files.exists(dir.resolve(CheckReport.FILE + ".partial")));
    }

    @Test
    void anEarlierRunsPicturesAndReportAreRemovedOtherFilesKept() throws IOException {
        Files.writeString(dir.resolve("01-old.png"), "x");
        Files.writeString(dir.resolve(CheckReport.FILE), "x");
        Files.writeString(dir.resolve("notes.txt"), "keep");
        PlayCheck.prepare(dir);
        assertFalse(Files.exists(dir.resolve("01-old.png")));
        assertFalse(Files.exists(dir.resolve(CheckReport.FILE)));
        assertTrue(Files.exists(dir.resolve("notes.txt")));
    }

    @Test
    void signalsArriveWholeAndOnlyThoseOfThisRunCount() throws IOException {
        Path syncDir = dir.resolve("sync");
        Files.createDirectories(syncDir);
        Files.writeString(syncDir.resolve("a-ready"), "old run");
        Files.setLastModifiedTime(syncDir.resolve("a-ready"),
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 60_000));
        Sync sync = new Sync(syncDir, System.currentTimeMillis() - 1_000);
        assertTrue(sync.peek("a-ready").isEmpty(), "a signal from before this run is ignored");
        assertTrue(sync.await("b-ready", 150).isEmpty());
        sync.signal("a-ready", "64 32");
        assertEquals("64 32", sync.peek("a-ready").orElseThrow());
        assertEquals("64 32", sync.await("a-ready", 10).orElseThrow());
        assertFalse(Files.exists(syncDir.resolve("a-ready.partial")));
        assertThrows(IllegalArgumentException.class, () -> sync.signal("../x", ""));
    }

    @Test
    void fixtureAreasSitOnAGridOfChunkCornersBesideSpawnWithGapsBetweenThem() {
        Map<String, Area> areas = Fixtures.layout(List.of("a", "b", "c", "d", "e"), 3, -20);
        Area a = areas.get("a");
        assertEquals(64, a.x0());
        assertEquals(32, a.z0());
        assertEquals(Fixtures.Y0, a.y0());
        assertEquals(a.x0() + Fixtures.SPACING, areas.get("b").x0());
        assertEquals(a.z0() + Fixtures.SPACING, areas.get("e").z0());
        assertEquals(a.x0(), areas.get("e").x0());
        assertTrue(Fixtures.SPACING > Area.SIZE);
        assertEquals(List.of("a", "b", "c", "d", "e"), List.copyOf(areas.keySet()));
        assertEquals(a.y0() - Area.FLOOR, a.box().min().y());
    }
}
