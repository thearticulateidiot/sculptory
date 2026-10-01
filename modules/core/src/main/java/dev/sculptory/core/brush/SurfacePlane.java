package dev.sculptory.core.brush;

import dev.sculptory.core.region.Facing;
import java.util.Objects;

/**
 * Surface-mode Flatten's plane ({@link SculptMode#SURFACE}): the face of a layer of cells, facing the way the surface
 * faced where the press began. {@code target} is that layer's coordinate on the facing's axis; it and every cell behind
 * it (toward the opposite facing) belong to the solid side, every cell beyond it is open. So {@code (UP, 64)} levels
 * ground to the top of y 64, as the Terrain mode's {@code flattenY = 64} does, {@code (DOWN, 80)} levels a ceiling to
 * the bottom of y 80, and {@code (EAST, 9)} makes a wall face east with its surface on x 9.
 *
 * @param target within ±{@value BrushSpec#CLIP_MAX_HORIZONTAL} for x and z, ±{@value BrushSpec#CLIP_MAX_Y} for y
 */
public record SurfacePlane(Facing facing, int target) {
    public SurfacePlane {
        Objects.requireNonNull(facing);
        long limit = facing.axis() == 1 ? BrushSpec.CLIP_MAX_Y : BrushSpec.CLIP_MAX_HORIZONTAL;
        if (Math.abs((long) target) > limit) throw new IllegalArgumentException("Flatten plane out of range: " + target);
    }

    /**
     * The plane facing {@code facing} through a point in 1/16 block: its layer is the last whose cell centres lie
     * strictly on the solid side of the point. A point on a block face gives that face (the top face of y 64 at
     * y16 = 1040, or a hit a sixteenth short of it, gives (UP, 64)).
     */
    public static SurfacePlane through(Facing facing, int x16, int y16, int z16) {
        int p16 = switch (facing.axis()) {
            case 0 -> x16;
            case 1 -> y16;
            default -> z16;
        };
        long target = facing.sign() > 0 ? Math.floorDiv((long) p16 - 9, 16) : Math.floorDiv((long) p16 - 8, 16) + 1;
        return new SurfacePlane(facing, (int) target);
    }

    /** The plane's position on its axis in 1/16 block: the face between the target layer and the next one out. */
    public long face16() {
        return facing.sign() > 0 ? 16L * target + 16 : 16L * target;
    }

    /**
     * The image of this plane under a symmetry image about {@code symmetry}'s centre (y planes are kept), or
     * {@code null} when it lies beyond the plane's range (a copy that far away is beyond the kernel's dab limit anyway).
     */
    public SurfacePlane image(Symmetry symmetry, Symmetry.Image image) {
        if (facing.axis() == 1 || image == Symmetry.Image.IDENTITY) return this;
        Facing mapped = Symmetry.imageFacing(image, facing);
        // Map one cell of the target layer; the image layer's coordinate is that cell's on the new axis.
        int x = facing.axis() == 0 ? target : 0;
        int z = facing.axis() == 2 ? target : 0;
        long coordinate = mapped.axis() == 0 ? symmetry.cellX(image, x, z) : symmetry.cellZ(image, x, z);
        if (Math.abs(coordinate) > BrushSpec.CLIP_MAX_HORIZONTAL) return null;
        return new SurfacePlane(mapped, (int) coordinate);
    }
}
