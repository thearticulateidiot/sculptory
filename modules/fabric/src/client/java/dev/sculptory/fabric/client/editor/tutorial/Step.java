package dev.sculptory.fabric.client.editor.tutorial;

import java.util.List;
import java.util.Objects;

/**
 * One step of a lesson: an instruction (a translation key, filled with {@code args} as the step shows), optionally a
 * part of the screen to point at, the condition that ticks it off (none for a step that only explains: it has a Next
 * button) and optionally a wiki page for "Learn more".
 *
 * @param target    what to outline, or null
 * @param condition when the step is done, or null for a step the player reads and clicks Next on
 * @param wiki      the "Learn more" page, or null
 */
public record Step(String name, String textKey, List<Arg> args, Target target, Condition condition, WikiLink wiki) {
    public Step {
        Objects.requireNonNull(name);
        Objects.requireNonNull(textKey);
        args = List.copyOf(args);
    }

    /** A step with a Next button instead of a condition. */
    public boolean isRead() {
        return condition == null;
    }
}
