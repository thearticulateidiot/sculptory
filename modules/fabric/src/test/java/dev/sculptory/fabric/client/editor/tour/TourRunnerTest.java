package dev.sculptory.fabric.client.editor.tour;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.ui.Rect;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TourRunnerTest {
    @TempDir
    Path dir;

    /** Records what the runner asks of the game; the "screenshot" is a text file naming the last action. */
    private final class FakeHost implements TourRunner.Host<List<String>> {
        final List<String> events = new ArrayList<>();
        final List<String> logs = new ArrayList<>();
        final List<String> captures = new ArrayList<>();
        long now;
        boolean failCapture;

        @Override
        public List<String> context() {
            return events;
        }

        @Override
        public void prepare(TourStep<List<String>> step) {
            events.add(step.uiPercent() == TourStep.BASE_UI_SIZE ? "prepare" : "prepare at " + step.uiPercent() + "%");
        }

        @Override
        public void capture(Path file, Optional<Rect> area, int maxWidth) throws IOException {
            if (failCapture) {
                throw new IOException("disk full");
            }
            events.add("capture " + file.getFileName());
            captures.add(area.map(Rect::toString).orElse("whole frame") + " at most " + maxWidth);
            Files.writeString(file, "picture");
        }

        @Override
        public long nowMs() {
            return now;
        }

        @Override
        public void log(String message, Throwable cause) {
            logs.add(message);
        }
    }

    private final FakeHost host = new FakeHost();

    private static TourStep<List<String>> step(String name) {
        return TourStep.of(name, "Shows " + name, events -> events.add("action " + name));
    }

    /** Frames until the runner says it is done (at most 1000). */
    private int runToEnd(TourRunner<?> runner) {
        for (int frame = 1; frame <= 1000; frame++) {
            if (runner.frame()) {
                return frame;
            }
        }
        throw new AssertionError("The tour never finished");
    }

    @Test
    void eachStepPreparesActsWaitsItsFramesThenCaptures() {
        TourRunner<List<String>> runner = new TourRunner<>(List.of(step("one").withFrames(3), step("two")), dir, host);
        runner.frame();
        assertEquals(List.of("prepare", "action one"), host.events);
        runner.frame();
        runner.frame();
        assertEquals(2, host.events.size(), "two frames rendered: still waiting");
        runner.frame();
        assertEquals("capture 01-one.png", host.events.get(2));
        runToEnd(runner);
        assertEquals(List.of("prepare", "action one", "capture 01-one.png", "prepare", "action two",
                "capture 02-two.png"), host.events);
        assertTrue(Files.exists(dir.resolve("01-one.png")));
        assertTrue(Files.exists(dir.resolve("02-two.png")));
    }

    @Test
    void eachStepIsPreparedAtItsOwnUiSizeOrTheBaseSize() {
        TourStep<List<String>> at100 = step("wide").atUiSize(100);
        TourRunner<List<String>> runner = new TourRunner<>(List.of(step("one"), at100, step("two")), dir, host);
        runToEnd(runner);
        assertEquals(List.of("prepare", "prepare at 100%", "prepare"),
                host.events.stream().filter(event -> event.startsWith("prepare")).toList(),
                "the step after one at 100% is back at the base size");
        assertThrows(IllegalArgumentException.class, () -> step("odd").atUiSize(42), "not a UI size");
    }

    @Test
    void theDefaultWaitIsTenFrames() {
        TourRunner<List<String>> runner = new TourRunner<>(List.of(step("one")), dir, host);
        runner.frame();
        for (int i = 1; i < TourStep.DEFAULT_FRAMES; i++) {
            runner.frame();
        }
        assertFalse(host.events.contains("capture 01-one.png"));
        runner.frame();
        assertTrue(host.events.contains("capture 01-one.png"));
    }

    @Test
    void aStepAlsoWaitsItsMinimumTime() {
        TourRunner<List<String>> runner = new TourRunner<>(List.of(step("tip").withFrames(1).withMinMillis(500)), dir,
                host);
        runner.frame();
        for (int i = 0; i < 20; i++) {
            host.now += 10;
            runner.frame();
        }
        assertFalse(host.events.contains("capture 01-tip.png"), "200 ms is not enough");
        host.now += 300;
        runner.frame();
        assertTrue(host.events.contains("capture 01-tip.png"));
    }

    @Test
    void aThrowingStepIsRecordedAsFailedAndTheTourGoesOn() throws IOException {
        TourStep<List<String>> broken = TourStep.of("broken", "Never works", events -> {
            throw new IllegalStateException("no such window");
        });
        TourRunner<List<String>> runner = new TourRunner<>(List.of(step("one"), broken, step("three")), dir, host);
        runToEnd(runner);
        assertEquals(List.of("capture 01-one.png", "capture 03-three.png"),
                host.events.stream().filter(event -> event.startsWith("capture")).toList(), "no picture of the failure");
        TourIndex.Entry failed = runner.index().entries().get(1);
        assertEquals(TourIndex.Status.FAILED, failed.status());
        assertEquals("IllegalStateException: no such window", failed.detail());
        assertEquals("", failed.file());
        assertTrue(host.logs.stream().anyMatch(line -> line.contains("broken") && line.contains("no such window")));
        List<String> index = Files.readAllLines(dir.resolve(TourIndex.FILE), StandardCharsets.UTF_8);
        assertEquals("# Sculptory screenshot tour: 3 steps, 2 ok, 1 failed, 0 skipped", index.get(0));
        assertEquals("(no picture) | broken | FAILED: IllegalStateException: no such window | Never works", index.get(2));
    }

    @Test
    void aFailedScreenshotFailsItsStep() {
        host.failCapture = true;
        TourRunner<List<String>> runner = new TourRunner<>(List.of(step("one")), dir, host);
        runToEnd(runner);
        TourIndex.Entry entry = runner.index().entries().get(0);
        assertEquals(TourIndex.Status.FAILED, entry.status());
        assertEquals("screenshot: IOException: disk full", entry.detail());
    }

    @Test
    void theIndexListsEveryStepInOrderWhenTheTourEnds() throws IOException {
        TourRunner<List<String>> runner = new TourRunner<>(List.of(step("one"), step("two")), dir, host);
        assertFalse(Files.exists(dir.resolve(TourIndex.FILE)));
        runToEnd(runner);
        assertTrue(runner.isDone());
        assertTrue(runner.frame(), "stays done");
        assertEquals(List.of("# Sculptory screenshot tour: 2 steps, 2 ok, 0 failed, 0 skipped",
                "01-one.png | one | OK | Shows one", "02-two.png | two | OK | Shows two"),
                Files.readAllLines(dir.resolve(TourIndex.FILE), StandardCharsets.UTF_8));
    }

    @Test
    void abortSkipsTheStepUnderWayAndTheRest() throws IOException {
        TourRunner<List<String>> runner = new TourRunner<>(List.of(step("one").withFrames(1), step("two"),
                step("three")), dir, host);
        runner.frame();
        runner.frame(); // one captured
        runner.frame(); // two acted, waiting
        runner.abort("window closed");
        assertTrue(runner.isDone());
        assertEquals(List.of(TourIndex.Status.OK, TourIndex.Status.SKIPPED, TourIndex.Status.SKIPPED),
                runner.index().entries().stream().map(TourIndex.Entry::status).toList());
        assertEquals("(no picture) | three | SKIPPED: window closed | Shows three",
                Files.readAllLines(dir.resolve(TourIndex.FILE), StandardCharsets.UTF_8).get(3));
    }

    @Test
    void aStepWithoutACropKeepsTheWholeFrame() {
        runToEnd(new TourRunner<>(List.of(step("one")), dir, host));
        assertEquals(List.of("whole frame at most " + TourStep.FULL_WIDTH), host.captures);
    }

    @Test
    void aCroppedStepWorksOutItsAreaAfterTheWaitAndCapturesOnlyThat() {
        TourStep<List<String>> cropped = step("part").withFrames(2).cropped(events -> {
            events.add("crop");
            return new Rect(10, 20, 30, 40);
        });
        TourRunner<List<String>> runner = new TourRunner<>(List.of(cropped), dir, host);
        runner.frame();
        runner.frame();
        assertFalse(host.events.contains("crop"), "not before the wait is over");
        runToEnd(runner);
        assertEquals(List.of("prepare", "action part", "crop", "capture 01-part.png"), host.events);
        assertEquals(List.of("Rect[x=10, y=20, width=30, height=40] at most " + TourCrop.MAX_WIDTH), host.captures,
                "a cropped picture is at most 1200 px wide");
        assertEquals(List.of("Rect[x=10, y=20, width=30, height=40] at most 640"),
                runAndCapture(step("small").cropped(events -> new Rect(10, 20, 30, 40)).withMaxWidth(640)));
    }

    @Test
    void aFailingOrEmptyCropFailsItsStepWithoutAPictureAndTheTourGoesOn() {
        TourStep<List<String>> throwing = step("gone").cropped(events -> {
            throw new IllegalStateException("The keys window is not open");
        });
        TourStep<List<String>> empty = step("empty").cropped(events -> Rect.EMPTY);
        TourRunner<List<String>> runner = new TourRunner<>(List.of(throwing, empty, step("three")), dir, host);
        runToEnd(runner);
        assertEquals(List.of("capture 03-three.png"),
                host.events.stream().filter(event -> event.startsWith("capture")).toList());
        List<TourIndex.Entry> entries = runner.index().entries();
        assertEquals("crop: IllegalStateException: The keys window is not open", entries.get(0).detail());
        assertEquals("crop: IllegalStateException: the crop is empty", entries.get(1).detail());
        assertEquals(List.of(TourIndex.Status.FAILED, TourIndex.Status.FAILED, TourIndex.Status.OK),
                entries.stream().map(TourIndex.Entry::status).toList());
    }

    @Test
    void namedOutputWritesEachPictureUnderItsStepNameAndTheIndexApart() throws IOException {
        Path pictures = dir.resolve("images");
        Path index = dir.resolve("index");
        Files.createDirectories(pictures);
        Files.createDirectories(index);
        TourRunner<List<String>> runner = new TourRunner<>(List.of(step("select-box"), step("keys-window")),
                TourRunner.Output.named(pictures, index), host);
        runToEnd(runner);
        assertTrue(Files.exists(pictures.resolve("select-box.png")));
        assertTrue(Files.exists(pictures.resolve("keys-window.png")));
        assertFalse(Files.exists(pictures.resolve(TourIndex.FILE)), "no index among the wiki pictures");
        assertEquals(List.of("# Sculptory screenshot tour: 2 steps, 2 ok, 0 failed, 0 skipped",
                "select-box.png | select-box | OK | Shows select-box",
                "keys-window.png | keys-window | OK | Shows keys-window"),
                Files.readAllLines(index.resolve(TourIndex.FILE), StandardCharsets.UTF_8));
    }

    private List<String> runAndCapture(TourStep<List<String>> step) {
        FakeHost other = new FakeHost();
        runToEnd(new TourRunner<>(List.of(step), dir, other));
        return other.captures;
    }
}
