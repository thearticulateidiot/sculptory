package dev.sculptory.fabric.client.editor.tools.brush;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.brush.ShapeSpec;
import dev.sculptory.core.brush.ShapeStamp;
import dev.sculptory.core.region.Facing;
import dev.sculptory.fabric.client.editor.brush.StrokeController;
import dev.sculptory.fabric.client.editor.tool.WorldCursor;
import java.util.Objects;

/**
 * Where the Shape brush puts a shape for the cursor, as the point its dab carries: the centre of the shape's box
 * ({@link ShapeStamp}). Pure; client thread.
 *
 * <ul>
 *   <li><b>Centre:</b> the box is centred on the block under the cursor. Its sides across the facing are odd (the
 *       diameter), so centred exactly; an even height has one more cell toward the facing end (up for Up), so a shape
 *       and its mirror image are exact mirror images.</li>
 *   <li><b>On surface:</b> the box rests on the face the cursor is on, just outside the block: along that face's
 *       normal it starts at the next cell and runs away from the block; across it, it is centred as above. A sphere
 *       clicked on the ground sits on it, a cylinder facing up stands on it, and with "Clicked face" a click on a wall
 *       makes a horizontal cylinder growing out of the wall.</li>
 * </ul>
 * Along a drag every dab lands on such a centre ({@link #snap}): an odd side on a block centre, an even one on a block
 * edge, so the kernel's rounding never decides where a shape goes, and symmetric copies are exact.
 */
final class ShapeAnchor {
    private ShapeAnchor() {}

    /** The facing a press uses: the fixed one, or the face the cursor is on for "Clicked face". */
    static Facing facing(ShapeSettings.FacingChoice choice, WorldCursor.Face face) {
        if (choice.facing() != null) return choice.facing();
        return switch (Objects.requireNonNull(face)) {
            case UP -> Facing.UP;
            case DOWN -> Facing.DOWN;
            case NORTH -> Facing.NORTH;
            case SOUTH -> Facing.SOUTH;
            case EAST -> Facing.EAST;
            case WEST -> Facing.WEST;
        };
    }

    /**
     * The dab point {x, y, z} (block coordinates) for a shape of {@code shape} with {@code radius} facing
     * {@code facing} (its own facing is not used), anchored on face {@code face} of block {@code pos}.
     */
    static double[] point(ShapeSpec shape, int radius, Facing facing, ShapeSettings.Anchor anchor, BlockPos pos,
                          WorldCursor.Face face) {
        int[] size = ShapeStamp.sizes(shape, radius, facing);
        int[] block = {pos.x(), pos.y(), pos.z()};
        int[] normal = {face.dx(), face.dy(), face.dz()};
        int[] ahead = direction(facing);
        double[] point = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            if (anchor == ShapeSettings.Anchor.SURFACE && normal[axis] != 0) {
                point[axis] = normal[axis] > 0 ? block[axis] + 1 + size[axis] / 2.0 : block[axis] - size[axis] / 2.0;
            } else if (size[axis] % 2 == 1) {
                point[axis] = block[axis] + 0.5;
            } else {
                // Only a height can be even: its extra cell goes toward the facing end.
                point[axis] = ahead[axis] >= 0 ? block[axis] + 1 : block[axis];
            }
        }
        return point;
    }

    /**
     * Fits a point of the drag path to the nearest place a shape of these sizes can be centred on: per axis, a block
     * centre for an odd side, a block edge for an even one.
     */
    static StrokeController.Snap snap(ShapeSpec shape, int radius, Facing facing) {
        int[] size = ShapeStamp.sizes(shape, radius, facing);
        return (x, y, z) -> new double[] {fit(x, size[0]), fit(y, size[1]), fit(z, size[2])};
    }

    private static double fit(double value, int size) {
        return size % 2 == 1 ? Math.floor(value) + 0.5 : Math.floor(value + 0.5);
    }

    /**
     * The distance between the shapes of a drag: a quarter of the shape's shortest side, at least one block, so the
     * shapes overlap into a smooth tube (a sphere's surface stays within about 3% of its radius of a straight tube's) and
     * a thin shape (a disc, a slab) leaves no gaps.
     */
    static double spacing(ShapeSpec shape, int radius) {
        return Math.max(1.0, Math.min(2 * radius + 1, shape.axialSize(radius)) / 4.0);
    }

    /** The unit vector of {@code facing}, as {x, y, z}. */
    private static int[] direction(Facing facing) {
        return switch (facing) {
            case UP -> new int[] {0, 1, 0};
            case DOWN -> new int[] {0, -1, 0};
            case NORTH -> new int[] {0, 0, -1};
            case SOUTH -> new int[] {0, 0, 1};
            case EAST -> new int[] {1, 0, 0};
            case WEST -> new int[] {-1, 0, 0};
        };
    }
}
