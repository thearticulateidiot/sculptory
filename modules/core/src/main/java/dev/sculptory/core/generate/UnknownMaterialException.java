package dev.sculptory.core.generate;

import dev.sculptory.core.BlockDescriptor;
import java.util.Objects;

/** A roof material's state is not known to the state space (a modded stairs block without that shape, a typo). */
public final class UnknownMaterialException extends Exception {
    private final BlockDescriptor state;

    public UnknownMaterialException(BlockDescriptor state) {
        super("Unknown block state " + state.format());
        this.state = Objects.requireNonNull(state);
    }

    public BlockDescriptor state() {
        return state;
    }
}
