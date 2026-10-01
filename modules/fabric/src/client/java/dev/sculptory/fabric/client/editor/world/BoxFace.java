package dev.sculptory.fabric.client.editor.world;

/**
 * One face of an axis-aligned box. Constant names and order match Minecraft's {@code Direction}
 * ({@code Direction.valueOf(face.name())} maps across) without depending on it.
 */
public enum BoxFace {
    DOWN(1, -1),
    UP(1, 1),
    NORTH(2, -1),
    SOUTH(2, 1),
    WEST(0, -1),
    EAST(0, 1);

    /** 0 = X, 1 = Y, 2 = Z. */
    private final int axis;
    /** -1 for the min face, +1 for the max face. */
    private final int sign;

    BoxFace(int axis, int sign) {
        this.axis = axis;
        this.sign = sign;
    }

    public int axis() {
        return axis;
    }

    public int sign() {
        return sign;
    }

    public int normalX() {
        return axis == 0 ? sign : 0;
    }

    public int normalY() {
        return axis == 1 ? sign : 0;
    }

    public int normalZ() {
        return axis == 2 ? sign : 0;
    }

    public BoxFace opposite() {
        return of(axis, -sign);
    }

    /** The face on {@code axis} (0 = X, 1 = Y, 2 = Z) whose outward normal has sign {@code sign}. */
    public static BoxFace of(int axis, int sign) {
        return switch (axis) {
            case 0 -> sign < 0 ? WEST : EAST;
            case 1 -> sign < 0 ? DOWN : UP;
            case 2 -> sign < 0 ? NORTH : SOUTH;
            default -> throw new IllegalArgumentException("axis must be 0, 1 or 2: " + axis);
        };
    }
}
