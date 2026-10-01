package dev.sculptory.fabric.client.editor.tutorial;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A lesson: an id (kept in {@code editor-tutorial.json}, so it never changes) and its steps. Its title is
 * {@code sculptory.tutorial.lesson.<id>}, its one-line summary {@code ...<id>.summary}, and each step's text
 * {@code sculptory.tutorial.<id>.<step name>}.
 */
public record Lesson(String id, List<Step> steps) {
    public static final String PREFIX = "sculptory.tutorial.";

    public Lesson {
        Objects.requireNonNull(id);
        steps = List.copyOf(steps);
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("Lesson " + id + " has no steps");
        }
    }

    public String titleKey() {
        return PREFIX + "lesson." + id;
    }

    public String summaryKey() {
        return titleKey() + ".summary";
    }

    public int size() {
        return steps.size();
    }

    public Step step(int index) {
        return steps.get(index);
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    /**
     * Builds a lesson step by step: {@link #step} or {@link #read} starts a step, and {@link #target}, {@link #args}
     * and {@link #wiki} set the last one started.
     */
    public static final class Builder {
        private final String id;
        private final List<Step> steps = new ArrayList<>();

        private Builder(String id) {
            this.id = Objects.requireNonNull(id);
        }

        /** A step the game ticks off when {@code condition} is met. */
        public Builder step(String name, Condition condition) {
            steps.add(new Step(name, PREFIX + id + "." + name, List.of(), null, Objects.requireNonNull(condition), null));
            return this;
        }

        /** A step that only explains: the player reads it and clicks Next. */
        public Builder read(String name) {
            steps.add(new Step(name, PREFIX + id + "." + name, List.of(), null, null, null));
            return this;
        }

        public Builder target(Target target) {
            Step last = last();
            replaceLast(new Step(last.name(), last.textKey(), last.args(), Objects.requireNonNull(target),
                    last.condition(), last.wiki()));
            return this;
        }

        public Builder args(Arg... args) {
            Step last = last();
            replaceLast(new Step(last.name(), last.textKey(), List.of(args), last.target(), last.condition(), last.wiki()));
            return this;
        }

        public Builder wiki(String page) {
            return wiki(page, null);
        }

        public Builder wiki(String page, String anchor) {
            Step last = last();
            replaceLast(new Step(last.name(), last.textKey(), last.args(), last.target(), last.condition(),
                    new WikiLink(page, anchor)));
            return this;
        }

        private Step last() {
            if (steps.isEmpty()) {
                throw new IllegalStateException("No step yet in lesson " + id);
            }
            return steps.get(steps.size() - 1);
        }

        private void replaceLast(Step step) {
            steps.set(steps.size() - 1, step);
        }

        public Lesson build() {
            return new Lesson(id, steps);
        }
    }
}
