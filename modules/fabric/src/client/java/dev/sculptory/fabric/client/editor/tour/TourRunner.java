package dev.sculptory.fabric.client.editor.tour;

import dev.sculptory.fabric.client.editor.ui.Rect;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Plays the tour's steps, one call of {@link #frame} per rendered frame, and writes {@code index.txt} at the end.
 * For each step: {@link Host#prepare}, the step's action, then the wait, then the step's crop (if it has one) and
 * {@link Host#capture}. A step whose action, crop or screenshot throws is logged and recorded as failed, and the tour
 * goes on with the next one. Pure: the game side is the {@link Host}.
 */
public final class TourRunner<C> {
    /** What the runner needs from the game. */
    public interface Host<C> {
        /** What the steps' actions work on. */
        C context();

        /**
         * Puts the editor into the tour's known starting state before {@code step}'s action, at its UI size
         * ({@link TourStep#uiPercent}: the run's base size unless the step is about another).
         */
        void prepare(TourStep<C> step) throws Exception;

        /**
         * Saves the last rendered frame as a PNG file: the whole frame, or only {@code area} (UI units), scaled down to
         * at most {@code maxWidth} pixels.
         */
        void capture(Path file, Optional<Rect> area, int maxWidth) throws Exception;

        long nowMs();

        /** Logs progress, or a failure with its {@code cause} (null when there is none). */
        void log(String message, Throwable cause);
    }

    /**
     * Where the pictures and the index go. The ordinary tour numbers its pictures ({@code 07-tool-raise.png}) and
     * keeps them with the index; the wiki tour names them after their step ({@code select-box.png}) straight into
     * {@code docs/wiki/images}, with the index elsewhere.
     */
    public record Output(Path pictures, Path index, boolean numbered) {
        public Output {
            Objects.requireNonNull(pictures);
            Objects.requireNonNull(index);
        }

        /** Numbered pictures and the index in {@code dir}. */
        public static Output of(Path dir) {
            return new Output(dir, dir, true);
        }

        /** Pictures named after their steps in {@code pictures}, the index in {@code index}. */
        public static Output named(Path pictures, Path index) {
            return new Output(pictures, index, false);
        }
    }

    private final List<TourStep<C>> steps;
    private final Output output;
    private final Host<C> host;
    private final TourIndex index = new TourIndex();
    private int next;
    private boolean waiting;
    private int framesWaited;
    private long startedMs;
    private boolean done;

    public TourRunner(List<TourStep<C>> steps, Path dir, Host<C> host) {
        this(steps, Output.of(dir), host);
    }

    public TourRunner(List<TourStep<C>> steps, Output output, Host<C> host) {
        this.steps = List.copyOf(steps);
        this.output = Objects.requireNonNull(output);
        this.host = Objects.requireNonNull(host);
    }

    /**
     * Called once per rendered frame, between frames (the last frame is complete, the next not started). Returns true
     * once the tour is over and the index is written.
     */
    public boolean frame() {
        if (done) {
            return true;
        }
        if (!waiting) {
            if (next >= steps.size()) {
                finish();
                return true;
            }
            start(steps.get(next));
            return false;
        }
        TourStep<C> step = steps.get(next);
        framesWaited++;
        if (framesWaited < step.frames() || host.nowMs() - startedMs < step.minMillis()) {
            return false;
        }
        waiting = false;
        capture(step);
        next++;
        return false;
    }

    /** Ends the tour early: the step under way and those after it are recorded as skipped, and the index is written. */
    public void abort(String reason) {
        if (done) {
            return;
        }
        host.log("Screenshot tour stopped: " + reason, null);
        waiting = false;
        for (; next < steps.size(); next++) {
            record(steps.get(next), "", TourIndex.Status.SKIPPED, reason, null);
        }
        finish();
    }

    public boolean isDone() {
        return done;
    }

    /** The results so far. */
    public TourIndex index() {
        return index;
    }

    private void start(TourStep<C> step) {
        try {
            host.prepare(step);
            step.action().run(host.context());
        } catch (Exception | LinkageError | AssertionError e) {
            record(step, "", TourIndex.Status.FAILED, describe(e), e);
            next++;
            return;
        }
        waiting = true;
        framesWaited = 0;
        startedMs = host.nowMs();
    }

    private void capture(TourStep<C> step) {
        Optional<Rect> area = Optional.empty();
        if (step.crop() != null) {
            try {
                Rect rect = step.crop().area(host.context());
                if (rect == null || rect.isEmpty()) {
                    throw new IllegalStateException("the crop is empty");
                }
                area = Optional.of(rect);
            } catch (Exception | LinkageError | AssertionError e) {
                record(step, "", TourIndex.Status.FAILED, "crop: " + describe(e), e);
                return;
            }
        }
        String file = fileName(next);
        try {
            host.capture(output.pictures().resolve(file), area, step.maxWidth());
            record(step, file, TourIndex.Status.OK, "", null);
        } catch (Exception | LinkageError | AssertionError e) {
            record(step, "", TourIndex.Status.FAILED, "screenshot: " + describe(e), e);
        }
    }

    private void record(TourStep<C> step, String file, TourIndex.Status status, String detail, Throwable cause) {
        index.add(new TourIndex.Entry(next + 1, step.name(), file, status, step.description(), detail));
        if (status == TourIndex.Status.FAILED) {
            host.log("Screenshot tour step " + (next + 1) + " (" + step.name() + ") failed: " + detail, cause);
        }
    }

    private void finish() {
        done = true;
        try {
            Path file = index.write(output.index());
            host.log("Screenshot tour finished: " + index.count(TourIndex.Status.OK) + " of " + steps.size()
                    + " pictures in " + output.pictures() + " (" + file + ")", null);
        } catch (IOException e) {
            host.log("Screenshot tour could not write its index in " + output.index(), e);
        }
    }

    private String fileName(int stepIndex) {
        String name = steps.get(stepIndex).name();
        return output.numbered() ? TourIndex.fileName(stepIndex + 1, steps.size(), name) : TourIndex.fileName(name);
    }

    private static String describe(Throwable e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
