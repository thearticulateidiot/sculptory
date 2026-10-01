package dev.sculptory.fabric.client.editor.render.ghost;

import dev.sculptory.core.generate.GeneratedSource;
import dev.sculptory.core.mask.BoundMask;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.world.WorldReader;
import dev.sculptory.fabric.client.editor.mask.EditMaskModel;
import java.util.Objects;

/**
 * The global mask on ghost previews: a ghost shows only the cells the server would
 * write under the player's mask, judged against the world as it is now, as the server judges them before the edit.
 * Generate's lines and roads, the Shape brush's Line and Scatter call it on what they generate; the Place tool's ghosts
 * go through {@link #current} too. A cell in a chunk the client has not loaded is shown (the server judges it). Client
 * thread only.
 */
public final class GhostMasking {
    private GhostMasking() {}

    /**
     * {@code source} cut down to the cells the player's mask accepts over {@code world}; {@code source} itself while the
     * mask is off.
     */
    public static GeneratedSource filter(GeneratedSource source, WorldReader world) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(world);
        return filter(source, world, current(world.states()));
    }

    /** {@link #filter(GeneratedSource, WorldReader)} under {@code mask}. */
    public static GeneratedSource filter(GeneratedSource source, WorldReader world, BoundMask mask) {
        if (mask.acceptsAll() || source.isEmpty()) return source;
        GeneratedSource.Builder kept = GeneratedSource.builder(source.cells());
        boolean[] dropped = {false};
        source.forEach((x, y, z, state) -> {
            if (accepts(mask, world, x, y, z)) {
                kept.set(x, y, z, state);
            } else {
                dropped[0] = true;
            }
        });
        return dropped[0] ? kept.build() : source;
    }

    /** Whether the mask lets cell (x, y, z) be written, as the world is now (always in a chunk not loaded here). */
    public static boolean accepts(BoundMask mask, WorldReader world, int x, int y, int z) {
        if (mask.acceptsAll() || !world.isLoaded(x >> 4, z >> 4)) return true;
        return mask.test(x, y, z, world.get(x, y, z), world);
    }

    /** The player's mask bound to {@code states} ({@link BoundMask#ALL} while it is off). */
    public static BoundMask current(StateSpace states) {
        return EditMaskModel.global().bound(states);
    }

    /** Changes whenever the player's mask does: a ghost filtered under one revision is judged again under the next. */
    public static long revision() {
        EditMaskModel model = EditMaskModel.global();
        return model.active() ? model.revision() : 0;
    }
}
