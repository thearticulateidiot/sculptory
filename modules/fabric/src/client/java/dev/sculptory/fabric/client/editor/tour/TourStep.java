package dev.sculptory.fabric.client.editor.tour;

import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.UiScale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One picture of the screenshot tour: an action that puts the editor into some state, a wait for it to settle (at
 * least {@code frames} rendered frames and {@code minMillis} milliseconds), then a screenshot named after the step.
 *
 * @param name short lower-case name, used in the file name ({@code 07-tool-raise.png}; a wiki picture is
 *        {@code <name>.png})
 * @param description what the picture shows, one line (goes into {@code index.txt})
 * @param uiPercent the editor UI size the step starts at: {@link #BASE_UI_SIZE} for the run's base size (almost every
 *        step), or one of {@link UiScale#steps()} for a step about that size (an overflow check at 100%)
 * @param crop the part of the frame the picture keeps, worked out when it is taken; null for the whole frame
 * @param maxWidth the widest the picture is written; a wider one is scaled down to it
 * @param <C> what the action works on (the tour's context in the game, anything in tests)
 */
public record TourStep<C>(String name, String description, Action<C> action, int frames, long minMillis,
        int uiPercent, Crop<C> crop, int maxWidth) {
    /** Frames to wait by default: enough for layout, window content and outline meshes. */
    public static final int DEFAULT_FRAMES = 10;
    /** A step at the run's base UI size (the main checkout's, or {@code playtest.ps1 -UiSize}). */
    public static final int BASE_UI_SIZE = 0;
    /** The widest picture a step writes unless it says otherwise: a whole frame is kept as it is. */
    public static final int FULL_WIDTH = Integer.MAX_VALUE;

    private static final Pattern NAME = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    /** Puts the editor into the state the picture shows; runs on the client thread. May throw. */
    @FunctionalInterface
    public interface Action<C> {
        void run(C tour) throws Exception;
    }

    /**
     * The part of the frame a picture keeps, in UI units (the editor's own coordinates at its UI size), worked out
     * after the wait, when the layout has settled. May throw (the step then fails without a picture).
     */
    @FunctionalInterface
    public interface Crop<C> {
        Rect area(C tour) throws Exception;
    }

    public TourStep {
        Objects.requireNonNull(name);
        Objects.requireNonNull(description);
        Objects.requireNonNull(action);
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Step names are lower-case words joined by '-': " + name);
        }
        if (description.isBlank() || description.contains("\n")) {
            throw new IllegalArgumentException("A step description is one non-empty line: " + name);
        }
        if (frames < 1 || minMillis < 0) {
            throw new IllegalArgumentException("A step waits at least one frame: " + name);
        }
        if (uiPercent != BASE_UI_SIZE && !UiScale.steps().contains(uiPercent)) {
            throw new IllegalArgumentException("Not a UI size: " + uiPercent + "% (" + name + ")");
        }
        if (maxWidth < 1) {
            throw new IllegalArgumentException("A picture is at least one pixel wide: " + name);
        }
    }

    /** A step at the run's base UI size, keeping the whole frame. */
    public static <C> TourStep<C> of(String name, String description, Action<C> action) {
        return new TourStep<>(name, description, action, DEFAULT_FRAMES, 0, BASE_UI_SIZE, null, FULL_WIDTH);
    }

    /** This step waiting {@code frames} frames before its screenshot. */
    public TourStep<C> withFrames(int frames) {
        return new TourStep<>(name, description, action, frames, minMillis, uiPercent, crop, maxWidth);
    }

    /** This step also waiting at least {@code millis} ms (a tooltip needs its hover delay). */
    public TourStep<C> withMinMillis(long millis) {
        return new TourStep<>(name, description, action, frames, millis, uiPercent, crop, maxWidth);
    }

    /**
     * This step at UI size {@code percent} whatever the run's base size: only for a step about that size (the UI size
     * steps, an overflow check at 100%). The step starts from the base layout at the base size, then changes size.
     */
    public TourStep<C> atUiSize(int percent) {
        return new TourStep<>(name, description, action, frames, minMillis, percent, crop, maxWidth);
    }

    /**
     * This step keeping only {@code area} of the frame, written at most {@link TourCrop#MAX_WIDTH} pixels wide (the
     * wiki pictures).
     */
    public TourStep<C> cropped(Crop<C> area) {
        return new TourStep<>(name, description, action, frames, minMillis, uiPercent, Objects.requireNonNull(area),
                Math.min(maxWidth, TourCrop.MAX_WIDTH));
    }

    /** This step's picture scaled down to at most {@code width} pixels (a small picture of the world stays small). */
    public TourStep<C> withMaxWidth(int width) {
        return new TourStep<>(name, description, action, frames, minMillis, uiPercent, crop, width);
    }
}
