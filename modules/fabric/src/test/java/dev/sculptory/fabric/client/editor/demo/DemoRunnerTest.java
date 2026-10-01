package dev.sculptory.fabric.client.editor.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The demo's sequencing: the Enter gate, the start step, a failed step, the closing state and the report. */
class DemoRunnerTest {
    /** Records what the runner asks of the game; the context is the list of events. */
    private static final class FakeHost implements DemoRunner.Host<List<String>> {
        final List<String> events = new ArrayList<>();
        final List<String> logs = new ArrayList<>();
        final List<List<DemoRunner.Result>> reports = new ArrayList<>();
        boolean enter = true;
        boolean finished;
        long now;

        @Override
        public List<String> context() {
            return events;
        }

        @Override
        public boolean awaitEnter() {
            events.add("await enter");
            return enter;
        }

        @Override
        public void started() {
            events.add("started: prompt gone");
        }

        @Override
        public void prepare(DemoStep<List<String>> step) {
            events.add("prepare " + step.id() + (step.startsInEditor() ? " in editor" : " outside"));
        }

        @Override
        public void finish() {
            finished = true;
            events.add("finish");
        }

        @Override
        public long nowMs() {
            return now += 1_000;
        }

        @Override
        public void log(String message, Throwable cause) {
            logs.add(message);
        }

        @Override
        public void report(List<DemoRunner.Result> results, boolean done) {
            reports.add(List.copyOf(results));
            events.add("report" + (done ? " done" : ""));
        }
    }

    private static List<DemoStep<List<String>>> steps() {
        return List.of(
                DemoStep.outsideEditor("title", events -> events.add("run title")),
                DemoStep.of("screen", events -> events.add("run screen")),
                DemoStep.of("broken", events -> {
                    events.add("run broken");
                    throw new IllegalStateException("the aim missed");
                }),
                DemoStep.of("closing", events -> events.add("run closing")));
    }

    @Test
    void nothingRunsBeforeEnterAndEveryStepRunsInOrderAfterIt() {
        FakeHost host = new FakeHost();
        List<DemoRunner.Result> results = new DemoRunner<>(steps(), host).run("");
        assertEquals("await enter", host.events.get(0));
        assertEquals(List.of("await enter", "started: prompt gone", "prepare title outside", "run title", "report",
                "prepare screen in editor",
                "run screen", "report", "prepare broken in editor", "run broken", "report",
                "prepare closing in editor", "run closing", "report", "finish", "report done"), host.events);
        assertEquals(List.of("OK", "OK", "FAILED", "OK"), results.stream().map(r -> r.status().name()).toList());
        assertEquals(List.of(1, 2, 3, 4), results.stream().map(DemoRunner.Result::number).toList());
        assertEquals("IllegalStateException: the aim missed", results.get(2).detail());
        assertTrue(results.get(2).line().startsWith("FAILED   3 broken"), results.get(2).line());
        assertTrue(host.logs.stream().anyMatch(line -> line.contains("broken") && line.contains("the aim missed")));
        assertTrue(host.finished);
        // The report grows a line per step and is written once more when done.
        assertEquals(5, host.reports.size());
        assertEquals(1, host.reports.get(0).size());
        assertEquals(4, host.reports.get(4).size());
    }

    @Test
    void withoutEnterEverythingIsSkippedAndTheClosingStateStillHappens() {
        FakeHost host = new FakeHost();
        host.enter = false;
        List<DemoRunner.Result> results = new DemoRunner<>(steps(), host).run("");
        assertEquals(List.of("await enter", "finish", "report done"), host.events);
        assertFalse(host.events.contains("started: prompt gone"));
        assertTrue(results.stream().allMatch(r -> r.status() == DemoRunner.Status.SKIPPED));
        assertEquals("the demo was not started", results.get(0).detail());
    }

    @Test
    void aRetakeStartsAtTheGivenStepAndSkipsTheOnesBefore() {
        FakeHost byNumber = new FakeHost();
        List<DemoRunner.Result> results = new DemoRunner<>(steps(), byNumber).run("3");
        assertEquals(List.of("SKIPPED", "SKIPPED", "FAILED", "OK"), results.stream().map(r -> r.status().name()).toList());
        assertEquals("before the start step", results.get(0).detail());
        assertFalse(byNumber.events.contains("run title"));
        assertTrue(byNumber.events.contains("run broken"));

        FakeHost byId = new FakeHost();
        new DemoRunner<>(steps(), byId).run("closing");
        assertEquals(List.of("await enter", "started: prompt gone", "prepare closing in editor", "run closing",
                "report", "finish", "report done"), byId.events);
    }

    @Test
    void anUnknownStartStepIsRefusedBeforeAnythingRuns() {
        assertEquals(0, DemoRunner.startIndex(steps(), null));
        assertEquals(0, DemoRunner.startIndex(steps(), "  "));
        assertEquals(1, DemoRunner.startIndex(steps(), " 2 "));
        assertEquals(3, DemoRunner.startIndex(steps(), "closing"));
        assertThrows(IllegalArgumentException.class, () -> DemoRunner.startIndex(steps(), "0"));
        assertThrows(IllegalArgumentException.class, () -> DemoRunner.startIndex(steps(), "5"));
        assertThrows(IllegalArgumentException.class, () -> DemoRunner.startIndex(steps(), "paste"));
        FakeHost host = new FakeHost();
        assertThrows(IllegalArgumentException.class, () -> new DemoRunner<>(steps(), host).run("nope"));
        assertTrue(host.events.isEmpty());
    }

    @Test
    void anInterruptedStepStopsTheRest() {
        List<DemoStep<List<String>>> steps = List.of(
                DemoStep.of("first", events -> events.add("run first")),
                DemoStep.of("interrupted", events -> {
                    throw new InterruptedException("stop");
                }),
                DemoStep.of("last", events -> events.add("run last")));
        FakeHost host = new FakeHost();
        List<DemoRunner.Result> results;
        try {
            results = new DemoRunner<>(steps, host).run("");
        } finally {
            // The runner keeps the interrupt flag for its caller; clear it for the next test.
            Thread.interrupted();
        }
        assertEquals(List.of("OK", "SKIPPED", "SKIPPED"), results.stream().map(r -> r.status().name()).toList());
        assertEquals("interrupted", results.get(1).detail());
        assertEquals("the demo was stopped", results.get(2).detail());
        assertFalse(host.events.contains("run last"));
        assertTrue(host.finished);
    }

    @Test
    void stepIdsAreShortNames() {
        assertThrows(IllegalArgumentException.class, () -> DemoStep.of("Bad Name", events -> {}));
        assertThrows(IllegalArgumentException.class, () -> DemoStep.of("", events -> {}));
        assertEquals("paste-flip", DemoStep.of("paste-flip", events -> {}).id());
    }
}
