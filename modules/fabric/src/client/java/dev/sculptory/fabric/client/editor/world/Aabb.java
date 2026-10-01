package dev.sculptory.fabric.client.editor.world;

/**
 * An axis-aligned box in world coordinates. Pure maths, independent of Minecraft's {@code Box} so
 * picking can be tested without a game. Corners are normalized so {@code min <= max} on every axis.
 */
public record Aabb(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    public Aabb {
        if (!Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(minZ)
                || !Double.isFinite(maxX) || !Double.isFinite(maxY) || !Double.isFinite(maxZ)) {
            throw new IllegalArgumentException("box corners must be finite");
        }
        if (minX > maxX) {
            double swap = minX;
            minX = maxX;
            maxX = swap;
        }
        if (minY > maxY) {
            double swap = minY;
            minY = maxY;
            maxY = swap;
        }
        if (minZ > maxZ) {
            double swap = minZ;
            minZ = maxZ;
            maxZ = swap;
        }
    }

    /**
     * The box covering every block between two inclusive block corners, given in any order. For
     * corners (0,0,0) and (2,0,0) the box spans x 0..3, y 0..1, z 0..1.
     */
    public static Aabb ofBlocks(int x1, int y1, int z1, int x2, int y2, int z2) {
        return new Aabb(
                Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2) + 1.0, Math.max(y1, y2) + 1.0, Math.max(z1, z2) + 1.0);
    }

    public double min(int axis) {
        return switch (axis) {
            case 0 -> minX;
            case 1 -> minY;
            case 2 -> minZ;
            default -> throw new IllegalArgumentException("axis must be 0, 1 or 2: " + axis);
        };
    }

    public double max(int axis) {
        return switch (axis) {
            case 0 -> maxX;
            case 1 -> maxY;
            case 2 -> maxZ;
            default -> throw new IllegalArgumentException("axis must be 0, 1 or 2: " + axis);
        };
    }

    public double center(int axis) {
        return (min(axis) + max(axis)) * 0.5;
    }

    public double size(int axis) {
        return max(axis) - min(axis);
    }

    public double minSize() {
        return Math.min(maxX - minX, Math.min(maxY - minY, maxZ - minZ));
    }

    /** Coordinate of the face centre on {@code axis}; the face's own axis gives its plane. */
    public double faceCenter(BoxFace face, int axis) {
        if (axis == face.axis()) {
            return face.sign() < 0 ? min(axis) : max(axis);
        }
        return center(axis);
    }

    /** A cube of half-size {@code half} centred on the given point. */
    public static Aabb cube(double x, double y, double z, double half) {
        return new Aabb(x - half, y - half, z - half, x + half, y + half, z + half);
    }

    public Aabb inflate(double amount) {
        return new Aabb(minX - amount, minY - amount, minZ - amount, maxX + amount, maxY + amount, maxZ + amount);
    }
}
