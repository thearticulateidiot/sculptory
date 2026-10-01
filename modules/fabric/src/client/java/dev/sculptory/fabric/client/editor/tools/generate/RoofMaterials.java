package dev.sculptory.fabric.client.editor.tools.generate;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.state.StateSpace;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Derives a roof's slab and full block from its stairs block by name: {@code x_stairs} gives {@code x_slab}, and the
 * full block is the first of {@code x_planks}, {@code xs}, {@code x_block}, {@code x}, {@code x_bricks} the state space
 * knows. A stairs block named otherwise (a modded one) derives nothing: the builder picks the others.
 */
public final class RoofMaterials {
    /** What derives from a stairs block: each part empty when it could not be derived. */
    public record Derived(Optional<BlockDescriptor> slab, Optional<BlockDescriptor> full) {
        public static final Derived NONE = new Derived(Optional.empty(), Optional.empty());
    }

    private RoofMaterials() {}

    public static Derived derive(StateSpace states, BlockDescriptor stairs) {
        Objects.requireNonNull(states);
        Objects.requireNonNull(stairs);
        NamespacedId id = stairs.block();
        String name = id.value();
        int colon = name.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : name.substring(0, colon);
        String path = colon < 0 ? name : name.substring(colon + 1);
        if (!path.endsWith("_stairs")) return Derived.NONE;
        String base = path.substring(0, path.length() - "_stairs".length());
        Optional<BlockDescriptor> slab = known(states, namespace, base + "_slab");
        Optional<BlockDescriptor> full = Optional.empty();
        for (String candidate : List.of(base + "_planks", base + "s", base + "_block", base, base + "_bricks")) {
            full = known(states, namespace, candidate);
            if (full.isPresent()) break;
        }
        return new Derived(slab, full);
    }

    private static Optional<BlockDescriptor> known(StateSpace states, String namespace, String path) {
        BlockDescriptor block;
        try {
            block = BlockDescriptor.of(new NamespacedId(namespace + ":" + path));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
        return states.resolve(block) >= 0 ? Optional.of(block) : Optional.empty();
    }
}
