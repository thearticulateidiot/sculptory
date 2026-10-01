package dev.sculptory.core.generate;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.state.StateSpace;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * A roof's materials resolved to state handles: the full block, the bottom slab and every bottom stair by facing and
 * shape. Resolved up front, so the kernel never meets an unknown state.
 */
public final class RoofStates {
    static final List<Facing> HORIZONTAL = List.of(Facing.NORTH, Facing.SOUTH, Facing.EAST, Facing.WEST);

    private final int full;
    private final int slab;
    private final int[][] stairs = new int[Facing.values().length][StairShapes.Shape.values().length];

    private RoofStates(int full, int slab) {
        this.full = full;
        this.slab = slab;
    }

    /**
     * Resolves {@code materials} in {@code states}: the stairs with {@code half=bottom} and every horizontal facing
     * and shape, the slab with {@code type=bottom}, and the full block as given.
     *
     * @throws UnknownMaterialException naming the first state the space does not know
     */
    public static RoofStates resolve(StateSpace states, RoofKernel.Materials materials) throws UnknownMaterialException {
        Objects.requireNonNull(states);
        Objects.requireNonNull(materials);
        RoofStates resolved = new RoofStates(require(states, materials.full()),
                require(states, materials.slab().with("type", "bottom")));
        for (Facing facing : HORIZONTAL) {
            for (StairShapes.Shape shape : StairShapes.Shape.values()) {
                BlockDescriptor state = materials.stairs()
                        .with("facing", facing.name().toLowerCase(Locale.ROOT))
                        .with("half", "bottom")
                        .with("shape", shape.property());
                resolved.stairs[facing.ordinal()][shape.ordinal()] = require(states, state);
            }
        }
        return resolved;
    }

    private static int require(StateSpace states, BlockDescriptor state) throws UnknownMaterialException {
        int handle = states.resolve(state);
        if (handle < 0) throw new UnknownMaterialException(state);
        return handle;
    }

    public int full() {
        return full;
    }

    /** The bottom slab. */
    public int slab() {
        return slab;
    }

    /** The bottom stair facing {@code facing} (horizontal) with {@code shape}. */
    public int stair(Facing facing, StairShapes.Shape shape) {
        if (facing.axis() == 1) throw new IllegalArgumentException(facing + " is not horizontal");
        return stairs[facing.ordinal()][shape.ordinal()];
    }
}
