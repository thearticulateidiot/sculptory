package dev.sculptory.core.generate;

import dev.sculptory.core.region.Facing;
import java.util.Locale;
import java.util.Objects;

/**
 * Vanilla's stair shape rule ({@code StairsBlock.getStairShape}) over a set of bottom-half stairs, so a roof pasted
 * without block updates holds exactly the shapes vanilla would give its stairs: an outer corner where the stair in
 * front (on its high side) runs across it, an inner corner where the stair behind does.
 */
public final class StairShapes {
    /** The {@code shape} property's values. */
    public enum Shape {
        STRAIGHT, INNER_LEFT, INNER_RIGHT, OUTER_LEFT, OUTER_RIGHT;

        /** The property value: {@code straight}, {@code inner_left}... */
        public String property() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The stairs to look at: the facing of the bottom stair at a cell, or {@code null} when there is none. */
    @FunctionalInterface
    public interface Stairs {
        Facing facingAt(int x, int y, int z);
    }

    private StairShapes() {}

    /** The shape of a bottom stair at (x, y, z) facing {@code facing}, among {@code stairs}. */
    public static Shape shape(Stairs stairs, int x, int y, int z, Facing facing) {
        Objects.requireNonNull(stairs);
        requireHorizontal(facing);
        Facing front = stairs.facingAt(x + dx(facing), y, z + dz(facing));
        if (front != null && front.axis() != facing.axis() && differs(stairs, x, y, z, front.opposite(), facing)) {
            return front == counterclockwise(facing) ? Shape.OUTER_LEFT : Shape.OUTER_RIGHT;
        }
        Facing back = stairs.facingAt(x - dx(facing), y, z - dz(facing));
        if (back != null && back.axis() != facing.axis() && differs(stairs, x, y, z, back, facing)) {
            return back == counterclockwise(facing) ? Shape.INNER_LEFT : Shape.INNER_RIGHT;
        }
        return Shape.STRAIGHT;
    }

    /** Whether the cell {@code toward} of (x, y, z) holds no stair facing {@code facing}. */
    private static boolean differs(Stairs stairs, int x, int y, int z, Facing toward, Facing facing) {
        Facing there = stairs.facingAt(x + dx(toward), y, z + dz(toward));
        return there != facing;
    }

    /** A quarter turn counterclockwise seen from above: north, west, south, east, north. */
    public static Facing counterclockwise(Facing facing) {
        return switch (requireHorizontal(facing)) {
            case NORTH -> Facing.WEST;
            case WEST -> Facing.SOUTH;
            case SOUTH -> Facing.EAST;
            case EAST -> Facing.NORTH;
            default -> throw new IllegalArgumentException(facing + " is not horizontal");
        };
    }

    public static int dx(Facing facing) {
        return facing.axis() == 0 ? facing.sign() : 0;
    }

    public static int dz(Facing facing) {
        return facing.axis() == 2 ? facing.sign() : 0;
    }

    private static Facing requireHorizontal(Facing facing) {
        Objects.requireNonNull(facing);
        if (facing.axis() == 1) throw new IllegalArgumentException(facing + " is not horizontal");
        return facing;
    }
}
