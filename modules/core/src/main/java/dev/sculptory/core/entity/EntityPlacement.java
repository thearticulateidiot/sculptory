package dev.sculptory.core.entity;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.transform.Mirror;
import dev.sculptory.core.transform.Transform;
import java.util.Objects;

/**
 * Where a clipboard entity lands when its box is placed with a {@link Transform} (mirror first, then clockwise quarter
 * turns, as for blocks). Positions are continuous: {@link Transform#mapX} maps cells, this
 * maps points, so a point in a cell lands in the cell {@code Transform} maps that cell to. For a source box of size
 * (sx, sz) (before the transform):
 * <ul>
 *   <li>mirror X: x → sx − x; mirror Z: z → sz − z;</li>
 *   <li>then 1 turn: (x, z) → (sz − z, x); 2 turns: (sx − x, sz − z); 3 turns: (z, sx − x).</li>
 * </ul>
 * The heading turns with it ({@link #yaw}); the server lets vanilla's {@code Entity.applyMirror} and
 * {@code applyRotation} do that (they also turn a hanging entity's facing), mirror first as here.
 *
 * <p>Flipped upside down, a point's height maps to {@code sy − y} and an
 * attachment cell's to {@code sy − 1 − y}: a hanging entity's position is its centre, which lands on the centre of
 * where it hangs flipped, while for any other entity it is its feet, which land where the top of its box goes, so the
 * server lowers it by its height ({@link #flippedFeet}). The pitch turns over ({@link #pitch}); the heading stays.
 */
public final class EntityPlacement {
    private EntityPlacement() {}

    /** An entity's place in the world: its position, its attachment (or {@code null}) and the cell it belongs to. */
    public record Placed(double x, double y, double z, BlockPos attached, BlockPos cell) {
        public Placed {
            Objects.requireNonNull(cell);
        }
    }

    /** The transformed local x of point (x, z) in a source box of size (sx, sz). */
    public static double mapX(double x, double z, Transform t, int sx, int sz) {
        double mx = t.mirror() == Mirror.X ? sx - x : x;
        double mz = t.mirror() == Mirror.Z ? sz - z : z;
        return switch (t.quarterTurnsCw()) {
            case 0 -> mx;
            case 1 -> sz - mz;
            case 2 -> sx - mx;
            default -> mz;
        };
    }

    /** The transformed local z of point (x, z) in a source box of size (sx, sz). */
    public static double mapZ(double x, double z, Transform t, int sx, int sz) {
        double mx = t.mirror() == Mirror.X ? sx - x : x;
        double mz = t.mirror() == Mirror.Z ? sz - z : z;
        return switch (t.quarterTurnsCw()) {
            case 0 -> mz;
            case 1 -> mx;
            case 2 -> sz - mz;
            default -> sx - mx;
        };
    }

    /** The transformed local height of point height {@code y} in a source box {@code sy} tall: {@code sy − y} flipped. */
    public static double mapY(double y, Transform t, int sy) {
        return t.upsideDown() ? sy - y : y;
    }

    /**
     * Where an entity {@code height} tall stands once flipped upside down, given the flipped image {@code y} of its feet:
     * its box, turned over, keeps the space it had, so its feet go {@code height} below that image.
     */
    public static double flippedFeet(double y, double height) {
        return y - height;
    }

    /** A pitch (vanilla: positive looks down) after the transform: turned over by the flip, else unchanged. */
    public static float pitch(float pitch, Transform t) {
        return t.upsideDown() ? -pitch : pitch;
    }

    /**
     * A heading (vanilla yaw: 0 south, 90 west, 180 north, 270 east) after the transform: mirror X negates it, mirror
     * Z makes it 180 − yaw, each clockwise quarter turn adds 90 (vanilla's {@code applyMirror} then
     * {@code applyRotation}). Wrapped into [-180, 180).
     */
    public static float yaw(float yaw, Transform t) {
        float y = switch (t.mirror()) {
            case NONE -> yaw;
            case X -> -yaw;
            case Z -> 180f - yaw;
        };
        return wrap(y + 90f * t.quarterTurnsCw());
    }

    /**
     * Where {@code entity} (local to a source box of {@code size}) lands when the box is placed with {@code t} so that
     * its transformed box starts at {@code targetMin} (world coordinates).
     */
    public static Placed place(EntitySnapshot entity, BlockPos size, Transform t, BlockPos targetMin) {
        Objects.requireNonNull(entity);
        int sx = size.x(), sz = size.z();
        double x = targetMin.x() + mapX(entity.x(), entity.z(), t, sx, sz);
        double y = targetMin.y() + mapY(entity.y(), t, size.y());
        double z = targetMin.z() + mapZ(entity.x(), entity.z(), t, sx, sz);
        BlockPos attached = null;
        if (entity.attached() != null) {
            BlockPos a = entity.attached();
            attached = new BlockPos(targetMin.x() + t.mapX(a.x(), a.z(), sx, sz), targetMin.y() + t.mapY(a.y(), size.y()),
                    targetMin.z() + t.mapZ(a.x(), a.z(), sx, sz));
        }
        BlockPos cell = attached != null ? attached
                : new BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        return new Placed(x, y, z, attached, cell);
    }

    /** {@code degrees} wrapped into [-180, 180). */
    public static float wrap(float degrees) {
        float d = degrees % 360f;
        if (d >= 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }
}
