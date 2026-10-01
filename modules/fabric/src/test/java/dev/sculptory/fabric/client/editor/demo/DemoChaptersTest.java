package dev.sculptory.fabric.client.editor.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** The video's chapter list: the clock, which chapters are listed and when each starts. */
class DemoChaptersTest {
    private static final Function<String, String> UPPER = key -> key.toUpperCase(Locale.ROOT);

    private static DemoStep<List<String>> step(String id) {
        return DemoStep.of(id, events -> {});
    }

    private static DemoRunner.Result result(int number, String id, DemoRunner.Status status, long offsetMs) {
        return new DemoRunner.Result(number, id, status, offsetMs, 1_000, "");
    }

    private static DemoRunner.Result ok(int number, String id, long offsetMs) {
        return result(number, id, DemoRunner.Status.OK, offsetMs);
    }

    private static DemoRunner.Result notStarted(int number, String id) {
        return result(number, id, DemoRunner.Status.SKIPPED, DemoRunner.Result.NOT_STARTED);
    }

    @Test
    void theClockReadsMinutesAndSecondsAndHoursFromAnHour() {
        assertEquals("0:00", DemoChapters.clock(0));
        assertEquals("0:59", DemoChapters.clock(59_999));
        assertEquals("1:05", DemoChapters.clock(65_000));
        assertEquals("59:59", DemoChapters.clock(3_599_000));
        assertEquals("1:00:00", DemoChapters.clock(3_600_000));
        assertEquals("1:02:05", DemoChapters.clock(3_725_000));
        assertEquals("0:00", DemoChapters.clock(-5_000));
        assertEquals("0:00", DemoChapters.clock(DemoRunner.Result.NOT_STARTED));
    }

    @Test
    void aChapterIsListedAtItsFirstPlayedStepAndTheFirstLineReadsZero() {
        List<DemoChapter<List<String>>> chapters = List.of(
                DemoChapter.of("intro", "intro", step("title")),
                DemoChapter.of("learning", "learning", step("screen"), step("select")),
                DemoChapter.of("unplayed", "unplayed", step("brushes"), step("paint")),
                DemoChapter.of("close", "close", step("shape")),
                DemoChapter.of("late", "late", step("paste"), step("generate")),
                DemoChapter.of("broken", "broken", step("extrude")));
        List<DemoRunner.Result> results = List.of(
                ok(1, "title", 1_200),
                ok(2, "screen", 20_000),
                ok(3, "select", 30_000),
                notStarted(4, "brushes"),
                notStarted(5, "paint"),
                // 8 s after the chapter before: too short for its own line, so folded into it.
                ok(6, "shape", 28_000),
                notStarted(7, "paste"),
                ok(8, "generate", 45_000),
                // A failed step is still in the recording.
                result(9, "extrude", DemoRunner.Status.FAILED, 65_000));
        assertEquals(List.of("0:00 INTRO", "0:20 LEARNING", "0:45 LATE", "1:05 BROKEN"),
                DemoChapters.lines(chapters, results, UPPER));
    }

    @Test
    void aRetakeListsItsFirstPlayedChapterAtZeroAndCountsTheGapFromThere() {
        List<DemoChapter<List<String>>> chapters = List.of(
                DemoChapter.of("intro", "intro", step("title")),
                DemoChapter.of("learning", "learning", step("screen")),
                DemoChapter.of("shaping", "shaping", step("shape")),
                DemoChapter.of("closing", "closing", step("closing")));
        List<DemoRunner.Result> results = List.of(
                notStarted(1, "title"),
                ok(2, "screen", 1_000),
                // Under 10 s after the 0:00 line (not after the step's 1 s): folded.
                ok(3, "shape", 9_500),
                ok(4, "closing", 10_000));
        assertEquals(List.of("0:00 LEARNING", "0:10 CLOSING"), DemoChapters.lines(chapters, results, UPPER));
    }

    @Test
    void anInterruptedStepDidNotPlayAndNothingPlayedListsNothing() {
        List<DemoChapter<List<String>>> chapters = List.of(
                DemoChapter.of("intro", "intro", step("title")),
                DemoChapter.of("stopped", "stopped", step("screen")),
                DemoChapter.of("absent", "absent", step("select")));
        // The interrupted step started, but SKIPPED is never played; "select" has no result at all.
        List<DemoRunner.Result> results = List.of(
                ok(1, "title", 1_000),
                result(2, "screen", DemoRunner.Status.SKIPPED, 30_000));
        assertEquals(List.of("0:00 INTRO"), DemoChapters.lines(chapters, results, UPPER));
        assertEquals(List.of(), DemoChapters.lines(chapters,
                List.of(notStarted(1, "title"), notStarted(2, "screen"), notStarted(3, "select")), UPPER));
    }

    @Test
    void theTitlesComeFromTheTitleFunctionByKey() {
        List<DemoChapter<List<String>>> chapters = List.of(
                DemoChapter.of("intro", "sculptory.demo.chapter.intro", step("title")),
                DemoChapter.of("learning", "sculptory.demo.chapter.learning", step("screen")));
        List<DemoRunner.Result> results = List.of(ok(1, "title", 0), ok(2, "screen", 12_000));
        Map<String, String> english = Map.of("sculptory.demo.chapter.intro", "Builder mode",
                "sculptory.demo.chapter.learning", "Learning the editor");
        assertEquals(List.of("0:00 Builder mode", "0:12 Learning the editor"),
                DemoChapters.lines(chapters, results, english::get));
        assertEquals(10_000, DemoChapters.MIN_GAP_MS);
    }

    @Test
    void eachStepMapsToItsChapter() {
        DemoChapter<List<String>> intro = DemoChapter.of("intro", "intro", step("title"));
        DemoChapter<List<String>> learning = DemoChapter.of("learning", "learning", step("screen"), step("select"));
        Map<String, DemoChapter<List<String>>> byStep = DemoChapters.byStep(List.of(intro, learning));
        assertEquals(3, byStep.size());
        assertSame(intro, byStep.get("title"));
        assertSame(learning, byStep.get("screen"));
        assertSame(learning, byStep.get("select"));
    }

    @Test
    void aChapterNeedsAStep() {
        assertThrows(IllegalArgumentException.class, () -> new DemoChapter<List<String>>("empty", "empty", List.of()));
    }
}
