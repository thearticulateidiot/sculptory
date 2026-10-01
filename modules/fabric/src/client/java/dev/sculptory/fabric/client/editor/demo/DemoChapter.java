package dev.sculptory.fabric.client.editor.demo;

import java.util.List;
import java.util.Objects;

/**
 * A chapter of the demo: steps that show one job (builder mode, learning the editor, shaping land, ...). The video's
 * chapter list ({@code chapters.txt}) has a line per chapter; with captions on, its title shows as it starts.
 *
 * @param id a short name ({@code learning})
 * @param titleKey the chapter's title, a caption key ({@code sculptory.demo.chapter.<id>})
 * @param steps its steps, in order (at least one)
 */
public record DemoChapter<C>(String id, String titleKey, List<DemoStep<C>> steps) {
    public DemoChapter {
        Objects.requireNonNull(id);
        Objects.requireNonNull(titleKey);
        steps = List.copyOf(steps);
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("The chapter '" + id + "' has no steps");
        }
    }

    @SafeVarargs
    public static <C> DemoChapter<C> of(String id, String titleKey, DemoStep<C>... steps) {
        return new DemoChapter<>(id, titleKey, List.of(steps));
    }
}
