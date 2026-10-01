package dev.sculptory.fabric.client.editor.world;

import java.util.Optional;

/**
 * Picking for a selection box: ray/AABB slab intersection and the six face handles (small cubes
 * centred on each face, dragged to resize). Pure maths; the renderer uses the same handle geometry
 * so what is drawn is what is picked.
 *
 * <p>Handles take priority over the box faces. Handles keep a roughly constant screen size by
 * scaling with distance from the eye, capped to a quarter of the box's smallest side.
 */
public final class BoxHandles {
    /** Handle half-size per block of eye distance (about 25 px tall at a 70° FOV on a 1080p screen). */
    public static final double HANDLE_SCALE_PER_DISTANCE = 0.018;
    public static final double MIN_HANDLE_HALF_SIZE = 0.08;
    public static final double MAX_HANDLE_HALF_SIZE = 3.0;
    /** Handles never exceed this fraction of the box's smallest side (unless that is under the minimum). */
    public static final double MAX_HANDLE_FRACTION = 0.25;

    private static final double PARALLEL_EPSILON = 1e-12;

    private BoxHandles() {}

    /**
     * Result of a ray/box slab test.
     *
     * @param tEnter distance to where the ray enters the box; negative when the origin is inside
     * @param tExit distance to where the ray leaves the box
     * @param entryFace the face crossed at {@code tEnter} (behind the origin when it is inside)
     * @param exitFace the face crossed at {@code tExit}
     */
    public record SlabHit(double tEnter, double tExit, BoxFace entryFace, BoxFace exitFace) {
        public boolean originInside() {
            return tEnter < 0;
        }
    }

    /** What part of the selection a pick hit. */
    public enum Part { HANDLE, FACE }

    /**
     * A selection pick.
     *
     * @param part handle or face
     * @param face which face (or which face's handle)
     * @param t distance along the ray to the hit point
     * @param inside whether the ray started inside the hit box; a face pick then reports the exit face
     */
    public record Pick(Part part, BoxFace face, double t, boolean inside) {}

    /** Slab-method ray/AABB intersection. Empty when the ray misses or the box is entirely behind it. */
    public static Optional<SlabHit> intersect(Ray ray, Aabb box) {
        double[] origin = {ray.originX(), ray.originY(), ray.originZ()};
        double[] direction = {ray.dirX(), ray.dirY(), ray.dirZ()};
        double tEnter = Double.NEGATIVE_INFINITY;
        double tExit = Double.POSITIVE_INFINITY;
        BoxFace entryFace = null;
        BoxFace exitFace = null;
        for (int axis = 0; axis < 3; axis++) {
            double o = origin[axis];
            double d = direction[axis];
            double min = box.min(axis);
            double max = box.max(axis);
            if (Math.abs(d) < PARALLEL_EPSILON) {
                if (o < min || o > max) {
                    return Optional.empty();
                }
                continue;
            }
            double near = (min - o) / d;
            double far = (max - o) / d;
            BoxFace nearFace = BoxFace.of(axis, -1);
            BoxFace farFace = BoxFace.of(axis, 1);
            if (near > far) {
                double swap = near;
                near = far;
                far = swap;
                nearFace = BoxFace.of(axis, 1);
                farFace = BoxFace.of(axis, -1);
            }
            if (near > tEnter) {
                tEnter = near;
                entryFace = nearFace;
            }
            if (far < tExit) {
                tExit = far;
                exitFace = farFace;
            }
            if (tEnter > tExit) {
                return Optional.empty();
            }
        }
        if (tExit < 0 || entryFace == null || exitFace == null) {
            return Optional.empty();
        }
        return Optional.of(new SlabHit(tEnter, tExit, entryFace, exitFace));
    }

    /** Half-size of one face's handle as seen from the eye position. */
    public static double handleHalfSize(Aabb box, BoxFace face, double eyeX, double eyeY, double eyeZ) {
        double dx = box.faceCenter(face, 0) - eyeX;
        double dy = box.faceCenter(face, 1) - eyeY;
        double dz = box.faceCenter(face, 2) - eyeZ;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double size = Math.max(MIN_HANDLE_HALF_SIZE, Math.min(MAX_HANDLE_HALF_SIZE, distance * HANDLE_SCALE_PER_DISTANCE));
        double cap = Math.max(MIN_HANDLE_HALF_SIZE, box.minSize() * MAX_HANDLE_FRACTION);
        return Math.min(size, cap);
    }

    /** Half-sizes of all six handles, indexed by {@link BoxFace#ordinal()}. */
    public static double[] handleHalfSizes(Aabb box, double eyeX, double eyeY, double eyeZ) {
        double[] sizes = new double[BoxFace.values().length];
        for (BoxFace face : BoxFace.values()) {
            sizes[face.ordinal()] = handleHalfSize(box, face, eyeX, eyeY, eyeZ);
        }
        return sizes;
    }

    /** The handle cube for a face: centred on the face centre with the given half-size. */
    public static Aabb handleBox(Aabb box, BoxFace face, double halfSize) {
        return Aabb.cube(box.faceCenter(face, 0), box.faceCenter(face, 1), box.faceCenter(face, 2), halfSize);
    }

    /** Picks handles, then faces, sizing handles from the ray origin (the eye). */
    public static Optional<Pick> pick(Ray ray, Aabb box) {
        return pick(ray, box, handleHalfSizes(box, ray.originX(), ray.originY(), ray.originZ()));
    }

    /**
     * Picks the nearest handle the ray hits; failing that, the face where the ray enters the box (or
     * leaves it, when the ray starts inside).
     *
     * @param halfSizes handle half-sizes indexed by {@link BoxFace#ordinal()}; a size of 0 or less
     *     disables that handle
     */
    public static Optional<Pick> pick(Ray ray, Aabb box, double[] halfSizes) {
        Pick best = null;
        for (BoxFace face : BoxFace.values()) {
            double half = halfSizes[face.ordinal()];
            if (!(half > 0)) {
                continue;
            }
            Optional<SlabHit> hit = intersect(ray, handleBox(box, face, half));
            if (hit.isPresent()) {
                double t = Math.max(hit.get().tEnter(), 0.0);
                if (best == null || t < best.t()) {
                    best = new Pick(Part.HANDLE, face, t, hit.get().originInside());
                }
            }
        }
        if (best != null) {
            return Optional.of(best);
        }
        return intersect(ray, box).map(hit -> hit.originInside()
                ? new Pick(Part.FACE, hit.exitFace(), hit.tExit(), true)
                : new Pick(Part.FACE, hit.entryFace(), hit.tEnter(), false));
    }
}
