package dev.sculptory.fabric.client.editor.tutorial;

/**
 * When a step is done: the game sees the player do it. {@link #start} is called as the step begins and remembers
 * what it needs from then (a value to see change, a counter to see grow); the {@link Check} it returns is asked once
 * per frame. Built by {@link Conditions}.
 */
@FunctionalInterface
public interface Condition {
    Check start(TutorialProbe probe);

    /** A started condition. */
    @FunctionalInterface
    interface Check {
        /** Whether the step is done, read at a frame. */
        boolean met(TutorialProbe probe);
    }
}
