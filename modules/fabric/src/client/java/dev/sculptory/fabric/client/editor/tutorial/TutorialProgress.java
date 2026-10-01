package dev.sculptory.fabric.client.editor.tutorial;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The player's tutorial progress: the lessons finished, and the lesson in progress with its step (for Resume).
 * Lesson ids this version doesn't know are kept (a newer version's lessons), so saving never drops them.
 */
public record TutorialProgress(Set<String> completed, Optional<Current> current) {
    public static final TutorialProgress NONE = new TutorialProgress(Set.of(), Optional.empty());

    /** A lesson in progress, at step {@code step} (0-based). */
    public record Current(String lesson, int step) {
        public Current {
            Objects.requireNonNull(lesson);
            if (step < 0) {
                throw new IllegalArgumentException("Negative step: " + step);
            }
        }
    }

    public TutorialProgress {
        completed = Set.copyOf(new TreeSet<>(completed));
        Objects.requireNonNull(current);
    }

    public boolean isCompleted(String lesson) {
        return completed.contains(lesson);
    }

    public TutorialProgress withCompleted(String lesson) {
        Set<String> more = new TreeSet<>(completed);
        more.add(Objects.requireNonNull(lesson));
        return new TutorialProgress(more, current);
    }

    public TutorialProgress withCurrent(String lesson, int step) {
        return new TutorialProgress(completed, Optional.of(new Current(lesson, step)));
    }

    public TutorialProgress withoutCurrent() {
        return new TutorialProgress(completed, Optional.empty());
    }
}
