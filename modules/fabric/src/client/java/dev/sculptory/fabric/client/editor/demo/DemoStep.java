package dev.sculptory.fabric.client.editor.demo;

import java.util.Objects;

/**
 * One numbered part of the demo: a caption-led sequence of actions on {@code C} (the driving facade in the game, a
 * recorder in tests). Its number is its place in the script, from 1.
 *
 * @param id a short name, for {@code -DemoFrom} and the report ("brushes")
 * @param startsInEditor whether the editor must be open when the step starts (the runner opens it silently for a
 *        retake that starts here); a step that opens or closes the editor itself says false
 * @param body the actions, run on the demo's thread
 */
public record DemoStep<C>(String id, boolean startsInEditor, Body<C> body) {
    /** The step's actions; anything thrown marks the step failed and the next step runs. */
    @FunctionalInterface
    public interface Body<C> {
        void run(C demo) throws Exception;
    }

    public DemoStep {
        Objects.requireNonNull(id);
        Objects.requireNonNull(body);
        if (id.isBlank() || !id.matches("[a-z][a-z0-9-]*")) {
            throw new IllegalArgumentException("A step id is lower-case letters, digits and dashes: '" + id + "'");
        }
    }

    public static <C> DemoStep<C> of(String id, Body<C> body) {
        return new DemoStep<>(id, true, body);
    }

    /** A step that opens or closes the editor itself. */
    public static <C> DemoStep<C> outsideEditor(String id, Body<C> body) {
        return new DemoStep<>(id, false, body);
    }
}
