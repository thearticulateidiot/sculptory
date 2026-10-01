package dev.sculptory.core.region;

/**
 * The direction a {@link Region.Shape} faces: the axis of a cylinder, the apex direction of a cone or pyramid.
 * {@code NORTH} is -z, {@code SOUTH} +z, {@code EAST} +x, {@code WEST} -x. Wire order: append only.
 */
public enum Facing {
    UP,
    DOWN,
    NORTH,
    SOUTH,
    EAST,
    WEST;

    /** The facing's axis: 0 for x, 1 for y, 2 for z. */
    public int axis() {
        return switch (this) {
            case EAST, WEST -> 0;
            case UP, DOWN -> 1;
            case NORTH, SOUTH -> 2;
        };
    }

    /** +1 when the facing end is the box's maximum side on its axis (UP, SOUTH, EAST), -1 otherwise. */
    public int sign() {
        return switch (this) {
            case UP, SOUTH, EAST -> 1;
            case DOWN, NORTH, WEST -> -1;
        };
    }

    public Facing opposite() {
        return switch (this) {
            case UP -> DOWN;
            case DOWN -> UP;
            case NORTH -> SOUTH;
            case SOUTH -> NORTH;
            case EAST -> WEST;
            case WEST -> EAST;
        };
    }
}
