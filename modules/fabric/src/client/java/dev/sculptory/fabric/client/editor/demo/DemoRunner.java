package dev.sculptory.fabric.client.editor.demo;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Plays the demo's steps in order on the calling thread (the demo's own): nothing starts until the host says Enter was
 * pressed; each step runs after the host prepared it (the editor open or closed as the step wants); a step that throws
 * is recorded FAILED and the next one runs; the steps before {@code from} (a retake) and after an interruption are
 * SKIPPED. Pure: the game is behind {@link Host}, so the sequencing is unit-tested.
 */
public final class DemoRunner<C> {
    public enum Status { OK, FAILED, SKIPPED }

    /** How one step went: its number (from 1), id, status, how long it took and why it failed or was skipped. */
    public record Result(int number, String id, Status status, long millis, String detail) {
        public String line() {
            return String.format(java.util.Locale.ROOT, "%-7s %2d %-14s %5.1f s%s", status, number, id, millis / 1000.0,
                    detail.isEmpty() ? "" : " -- " + detail);
        }
    }

    /** What the runner asks of the game. */
    public interface Host<C> {
        C context();

        /** Blocks until Enter is pressed; false when the wait ended otherwise (the demo was stopped). */
        boolean awaitEnter();

        /** Right after Enter, before the first step: the "Press Enter" prompt goes. */
        void started();

        /** Runs right before a step's body: the editor open or closed as the step starts. Throwing fails the step. */
        void prepare(DemoStep<C> step) throws Exception;

        /** After the last step, or after a stop: the closing state (editor closed, caption gone). */
        void finish();

        long nowMs();

        void log(String message, Throwable cause);

        /** The results so far, after every step and once more when done (the report file). */
        void report(List<Result> results, boolean done);
    }

    private final List<DemoStep<C>> steps;
    private final Host<C> host;

    public DemoRunner(List<DemoStep<C>> steps, Host<C> host) {
        this.steps = List.copyOf(steps);
        this.host = Objects.requireNonNull(host);
        if (this.steps.isEmpty()) {
            throw new IllegalArgumentException("no steps");
        }
    }

    /**
     * The index (from 0) of the step {@code from} names: blank is the first, a number is that step's number, else a
     * step's id.
     *
     * @throws IllegalArgumentException when no step is named so
     */
    public static <C> int startIndex(List<DemoStep<C>> steps, String from) {
        String wanted = from == null ? "" : from.strip();
        if (wanted.isEmpty()) {
            return 0;
        }
        if (wanted.matches("\\d+")) {
            int number = Integer.parseInt(wanted);
            if (number < 1 || number > steps.size()) {
                throw new IllegalArgumentException("The demo has steps 1 to " + steps.size() + ", not " + number);
            }
            return number - 1;
        }
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).id().equals(wanted)) {
                return i;
            }
        }
        throw new IllegalArgumentException("No demo step is called '" + wanted + "'; the steps are "
                + steps.stream().map(DemoStep::id).toList());
    }

    /** Plays the steps from {@code from} (see {@link #startIndex}) and returns how each went. */
    public List<Result> run(String from) {
        int start = startIndex(steps, from);
        List<Result> results = new ArrayList<>();
        for (int i = 0; i < start; i++) {
            results.add(new Result(i + 1, steps.get(i).id(), Status.SKIPPED, 0, "before the start step"));
        }
        if (!host.awaitEnter()) {
            for (int i = start; i < steps.size(); i++) {
                results.add(new Result(i + 1, steps.get(i).id(), Status.SKIPPED, 0, "the demo was not started"));
            }
            host.finish();
            host.report(results, true);
            return List.copyOf(results);
        }
        host.started();
        boolean stopped = false;
        for (int i = start; i < steps.size(); i++) {
            DemoStep<C> step = steps.get(i);
            if (stopped || Thread.currentThread().isInterrupted()) {
                results.add(new Result(i + 1, step.id(), Status.SKIPPED, 0, "the demo was stopped"));
                continue;
            }
            long started = host.nowMs();
            host.log("Demo step " + (i + 1) + " " + step.id(), null);
            try {
                host.prepare(step);
                step.body().run(host.context());
                results.add(new Result(i + 1, step.id(), Status.OK, host.nowMs() - started, ""));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stopped = true;
                results.add(new Result(i + 1, step.id(), Status.SKIPPED, host.nowMs() - started, "interrupted"));
            } catch (Exception | Error e) {
                String why = describe(e);
                host.log("Demo step " + (i + 1) + " " + step.id() + " failed: " + why, e);
                results.add(new Result(i + 1, step.id(), Status.FAILED, host.nowMs() - started, why));
                if (Thread.currentThread().isInterrupted()) {
                    stopped = true;
                }
            }
            host.report(results, false);
        }
        host.finish();
        host.report(results, true);
        return List.copyOf(results);
    }

    static String describe(Throwable e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
