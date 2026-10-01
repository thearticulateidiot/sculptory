package dev.sculptory.core.brush;

import dev.sculptory.core.region.Facing;
import java.util.Objects;

/**
 * What the Shape brush ({@link BrushTool#SHAPE}) places at each dab, with {@link BrushSpec#radius()} and
 * {@link BrushSpec#material()}: the dab's point is the centre of the shape's box, and the kernel writes exactly the cells
 * of the contract's voxelization of that box ({@link ShapeStamp}).
 *
 * @param kind the solid
 * @param height the box's size along the facing axis, 1-{@value #MAX_HEIGHT}; the other two sizes are the diameter
 *     {@code 2 × radius + 1}. A sphere ignores it (its box is the diameter every way).
 * @param facing the axis of a cylinder, the apex direction of a cone or pyramid, the long side of a cube's box; a
 *     sphere ignores it
 * @param mode which cells of the shape change
 * @param hollow 0 for a solid shape, else the shell thickness 1-{@value #MAX_HOLLOW}: a cell of the shape is written
 *     only when a cell outside it lies within that many cells along one of the six axis directions (the regions'
 *     shell rule)
 */
public record ShapeSpec(Kind kind, int height, Facing facing, Mode mode, int hollow) {
    /** Shape brush solids. Wire order: append only. */
    public enum Kind {
        SPHERE,
        CYLINDER,
        CONE,
        CUBE,
        /** A square pyramid, its tip at the facing end, as the Select tool's pyramid. */
        PYRAMID
    }

    /** Which cells of the shape a dab changes. Wire order: append only. */
    public enum Mode {
        /** Every cell takes the material. */
        PLACE,
        /** Only air and replaceable cells (short plants, fluids) take the material. */
        PLACE_IN_AIR,
        /** Only the other cells take the material: {@link #PLACE_IN_AIR} and this together are {@link #PLACE}. */
        PAINT,
        /** Every cell becomes air; the material is not used. */
        CARVE
    }

    public static final int MIN_HEIGHT = 1;
    /** The tallest box: the largest diameter. */
    public static final int MAX_HEIGHT = 2 * BrushSpec.MAX_RADIUS + 1;
    public static final int MAX_HOLLOW = 16;

    public ShapeSpec {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(facing);
        Objects.requireNonNull(mode);
        if (height < MIN_HEIGHT || height > MAX_HEIGHT) {
            throw new IllegalArgumentException("Shape height must be " + MIN_HEIGHT + "-" + MAX_HEIGHT);
        }
        if (hollow < 0 || hollow > MAX_HOLLOW) throw new IllegalArgumentException("Shape hollow must be 0-" + MAX_HOLLOW);
    }

    /** A solid shape placed in every cell. */
    public static ShapeSpec solid(Kind kind, int height, Facing facing) {
        return new ShapeSpec(kind, height, facing, Mode.PLACE, 0);
    }

    /** The box's size along the facing axis for a brush of {@code radius}: the height, or a sphere's diameter. */
    public int axialSize(int radius) {
        return kind == Kind.SPHERE ? 2 * radius + 1 : height;
    }

    /** Whether the dab writes blocks of the material (every mode but {@link Mode#CARVE}). */
    public boolean usesMaterial() {
        return mode != Mode.CARVE;
    }
}
