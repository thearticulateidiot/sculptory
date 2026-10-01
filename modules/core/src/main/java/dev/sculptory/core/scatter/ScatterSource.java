package dev.sculptory.core.scatter;

import dev.sculptory.core.edit.SourceRef;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Where a scatter variant's blocks come from: a server-held paste source, one block state given by name, or a vanilla
 * tree or feature grown in place.
 */
public sealed interface ScatterSource {
    /**
     * The most UTF-8 bytes of a {@link Block}'s state text (the wire cap too), so a preview of 64 block variants
     * still fits one client frame. Vanilla's longest state texts are under 100 bytes.
     */
    int MAX_STATE_BYTES = 256;

    /** A clipboard or library asset the server holds (the paste rule). */
    record Held(SourceRef ref) implements ScatterSource {
        public Held {
            Objects.requireNonNull(ref);
        }
    }

    /**
     * One block, by its state text ({@code StateSpace.format}, for example {@code minecraft:pink_petals[facing=east,
     * flower_amount=3]}), so no upload is needed. Whoever plans it resolves the text against its own state space
     * ({@link BlockVariants#resolve}) and builds the footprint ({@link BlockVariants#clipboard}).
     */
    record Block(String state) implements ScatterSource {
        public Block {
            Objects.requireNonNull(state);
            if (state.isBlank()) throw new IllegalArgumentException("An empty block state");
            if (state.getBytes(StandardCharsets.UTF_8).length > MAX_STATE_BYTES) {
                throw new IllegalArgumentException("A block state over " + MAX_STATE_BYTES + " bytes");
            }
        }
    }

    /**
     * A vanilla tree or feature grown at each spot, by its configured
     * feature id ({@code minecraft:fancy_oak}); seeded per spot, so the preview shows what commits. The server refuses an
     * id that is not in {@link FeatureCatalog}. At most {@value #MAX_STATE_BYTES} UTF-8 bytes (the wire cap too).
     */
    record Feature(String id) implements ScatterSource {
        public Feature {
            Objects.requireNonNull(id);
            if (id.isBlank()) throw new IllegalArgumentException("An empty feature id");
            if (id.getBytes(StandardCharsets.UTF_8).length > MAX_STATE_BYTES) {
                throw new IllegalArgumentException("A feature id over " + MAX_STATE_BYTES + " bytes");
            }
        }
    }
}
