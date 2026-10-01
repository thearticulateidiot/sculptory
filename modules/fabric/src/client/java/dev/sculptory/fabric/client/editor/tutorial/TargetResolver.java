package dev.sculptory.fabric.client.editor.tutorial;

import dev.sculptory.fabric.client.editor.ui.Rect;
import java.util.Optional;

/** Where a {@link Target} is on screen now, in UI units; empty when it isn't shown. */
@FunctionalInterface
public interface TargetResolver {
    Optional<Rect> resolve(Target target);

    /** Nothing is ever on screen (tests, or before the editor UI exists). */
    TargetResolver NONE = target -> Optional.empty();
}
