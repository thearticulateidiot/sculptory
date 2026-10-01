package dev.sculptory.fabric.client.editor.tools.brush;

import dev.sculptory.core.brush.Shape;
import dev.sculptory.core.brush.SurfacePlane;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.region.Facing;
import dev.sculptory.fabric.client.editor.render.BrushCursorRenderer;
import dev.sculptory.fabric.client.editor.render.OverlayColors;
import dev.sculptory.fabric.client.editor.tool.WorldDraw;
import dev.sculptory.fabric.client.editor.world.SurfaceSampler;

/**
 * Brush outlines the overlay's round cursor can't show, drawn through {@link WorldDraw}: the square
 * footprint (stepping over the terrain), Flatten's target plane, the symmetry (its centre line, mirror planes
 * and the copies' footprints), and the Surface mode's ring across the way the surface faces, with its direction tick
 * and Flatten's plane.
 */
public final class BrushOutlines {
    /** Height above the surface the outline floats at. */
    public static final double LIFT = 0.05;

    private BrushOutlines() {}

    /**
     * The outline of the square footprint {@code radius} columns around the sampled centre, at each edge
     * column's surface; dashed (every other column) for a server-only brush.
     */
    static void square(WorldDraw d, SurfaceSampler.Samples samples, int radius, int color, boolean dashed) {
        int cx = samples.centerX();
        int cz = samples.centerZ();
        double fallback = top(samples, cx, cz, samples.centerY() + 1.0);
        d.seeThrough(false);
        for (int side = 0; side < 4; side++) {
            double previous = Double.NaN;
            for (int i = -radius; i <= radius; i++) {
                // The column along this edge, and the edge line's fixed coordinate.
                int x = switch (side) {
                    case 0, 1 -> cx + i;
                    case 2 -> cx - radius;
                    default -> cx + radius;
                };
                int z = switch (side) {
                    case 0 -> cz - radius;
                    case 1 -> cz + radius;
                    default -> cz + i;
                };
                double y = top(samples, x, z, fallback) + LIFT;
                boolean drawn = !dashed || Math.floorMod(i, 2) == 0;
                double edge = switch (side) {
                    case 0 -> cz - radius;
                    case 1 -> cz + radius + 1.0;
                    case 2 -> cx - radius;
                    default -> cx + radius + 1.0;
                };
                double from = side < 2 ? x : z;
                if (drawn) {
                    if (side < 2) {
                        d.line(from, y, edge, from + 1, y, edge, color);
                    } else {
                        d.line(edge, y, from, edge, y, from + 1, color);
                    }
                }
                if (!dashed && !Double.isNaN(previous) && previous != y) {
                    if (side < 2) {
                        d.line(from, previous, edge, from, y, edge, color);
                    } else {
                        d.line(edge, previous, from, edge, y, from, color);
                    }
                }
                previous = y;
            }
        }
    }

    /**
     * Flatten's target: the plane the surface is levelled to (the top face of blocks at {@code targetY}),
     * around the footprint, visible through the terrain.
     */
    static void plane(WorldDraw d, int cx, int targetY, int cz, int radius, Shape shape, int color) {
        double x = cx + 0.5;
        double z = cz + 0.5;
        double y = targetY + 1.0 + LIFT;
        double reach = radius + 0.5;
        int faint = OverlayColors.scaleAlpha(color, 0.7);
        d.seeThrough(true);
        if (shape == Shape.SQUARE) {
            d.line(x - reach, y, z - reach, x + reach, y, z - reach, faint);
            d.line(x + reach, y, z - reach, x + reach, y, z + reach, faint);
            d.line(x + reach, y, z + reach, x - reach, y, z + reach, faint);
            d.line(x - reach, y, z + reach, x - reach, y, z - reach, faint);
        } else {
            d.ring(x, y, z, reach, faint);
            d.ring(x, y, z, reach * 0.5, OverlayColors.scaleAlpha(color, 0.4));
        }
        d.line(x - reach, y, z, x + reach, y, z, faint);
        d.line(x, y, z - reach, x, y, z + reach, faint);
        d.seeThrough(false);
    }

    /**
     * The symmetry, seen through the terrain: a vertical line at the centre, and each mirror plane as an upright frame
     * through the centre, {@code max(16, 2 × radius + 8)} blocks to either side and from 8 below to 16 above
     * {@code y}, with a line at {@code y}. A rotation marks the centre with a small ring at {@code y}.
     */
    public static void symmetry(WorldDraw d, Symmetry symmetry, double y, int radius, int color) {
        double cx = symmetry.centreX();
        double cz = symmetry.centreZ();
        double reach = Math.max(16, 2 * radius + 8);
        double low = y - 8;
        double high = y + 16;
        int faint = OverlayColors.scaleAlpha(color, 0.55);
        d.seeThrough(true);
        d.line(cx, low - 8, cz, cx, high + 8, cz, color);
        Symmetry.Mode mode = symmetry.mode();
        if (mode == Symmetry.Mode.MIRROR_X || mode == Symmetry.Mode.MIRROR_XZ) {
            // The plane x = cx runs north-south.
            d.line(cx, low, cz - reach, cx, low, cz + reach, faint);
            d.line(cx, high, cz - reach, cx, high, cz + reach, faint);
            d.line(cx, low, cz - reach, cx, high, cz - reach, faint);
            d.line(cx, low, cz + reach, cx, high, cz + reach, faint);
            d.line(cx, y, cz - reach, cx, y, cz + reach, color);
        }
        if (mode == Symmetry.Mode.MIRROR_Z || mode == Symmetry.Mode.MIRROR_XZ) {
            // The plane z = cz runs east-west.
            d.line(cx - reach, low, cz, cx + reach, low, cz, faint);
            d.line(cx - reach, high, cz, cx + reach, high, cz, faint);
            d.line(cx - reach, low, cz, cx - reach, high, cz, faint);
            d.line(cx + reach, low, cz, cx + reach, high, cz, faint);
            d.line(cx - reach, y, cz, cx + reach, y, cz, color);
        }
        if (mode == Symmetry.Mode.ROTATE_2 || mode == Symmetry.Mode.ROTATE_4) {
            d.ring(cx, y, cz, 1.5, color);
        }
        d.seeThrough(false);
    }

    /**
     * A footprint's outline, flat at height {@code y} and seen through the terrain: a ring of {@code radius + 0.5}, or
     * that square.
     */
    static void footprint(WorldDraw d, double x, double y, double z, int radius, Shape shape, int color) {
        double reach = radius + 0.5;
        d.seeThrough(true);
        if (shape == Shape.SQUARE) {
            d.line(x - reach, y, z - reach, x + reach, y, z - reach, color);
            d.line(x + reach, y, z - reach, x + reach, y, z + reach, color);
            d.line(x + reach, y, z + reach, x - reach, y, z + reach, color);
            d.line(x - reach, y, z + reach, x - reach, y, z - reach, color);
        } else {
            d.ring(x, y, z, reach, color);
        }
        d.seeThrough(false);
    }

    /** Segments of a Surface-mode ring at small radii; {@link #ringSegments} adds more for large ones. */
    private static final int RING_SEGMENTS = 48;
    /** The see-through pass's share of a ring's alpha (the depth-tested pass has the full colour). */
    static final double GHOST_ALPHA = BrushCursorRenderer.GHOST_ALPHA;
    /** The gaps of a dashed (server-only) ring: faint rather than empty, so the ring still reads as a whole circle. */
    static final double DASH_GAP_ALPHA = 0.3;
    /** From this radius on the outer ring is drawn twice, a little apart: it reads from as far as a big brush is used. */
    static final int THICK_RING_RADIUS = 12;
    static final double THICK_RING_GAP = 0.4;

    /**
     * Segments of a Surface-mode ring of {@code radius}: {@value #RING_SEGMENTS} per 12 blocks of radius (at least once),
     * so a large ring stays round from afar; a multiple of 48, so a dashed ring has as many dashes as gaps.
     */
    static int ringSegments(int radius) {
        return RING_SEGMENTS * Math.max(1, (int) Math.round(radius / 12.0));
    }

    /**
     * The Surface mode's cursor: a ring of {@code radius + 0.5} around (x, y, z) lying across {@code facing}'s axis (flat on
     * a floor or ceiling, upright on a wall), or that square, lifted a little toward the facing. It is drawn twice, depth
     * tested in the full colour and through the terrain at {@value #GHOST_ALPHA} of it, so it stays visible in a dent,
     * behind a rise or under an overhang and still shows where it sits; from radius {@value #THICK_RING_RADIUS} a second
     * outer line {@value #THICK_RING_GAP} in makes it read from afar. The inner ring at {@code falloffStart} of it (none
     * for NaN or 1) is see-through only. A server-only brush's rings are dashed: bright and faint segments alternate.
     */
    static void surfaceRing(WorldDraw d, double x, double y, double z, Facing facing, int radius, Shape shape,
                            double falloffStart, int color, boolean dashed) {
        double reach = radius + 0.5;
        double lift = LIFT * facing.sign();
        double cx = x + (facing.axis() == 0 ? lift : 0);
        double cy = y + (facing.axis() == 1 ? lift : 0);
        double cz = z + (facing.axis() == 2 ? lift : 0);
        int segments = ringSegments(radius);
        outline(d, facing, cx, cy, cz, reach, shape, color, dashed, segments);
        if (radius >= THICK_RING_RADIUS) {
            outline(d, facing, cx, cy, cz, reach - THICK_RING_GAP, shape, color, dashed, segments);
        }
        if (!Double.isNaN(falloffStart) && falloffStart > 0 && falloffStart < 1) {
            d.seeThrough(true);
            circle(d, facing, cx, cy, cz, reach * falloffStart, OverlayColors.scaleAlpha(color, 0.45), dashed,
                    segments);
            d.seeThrough(false);
        }
    }

    /** A ring (or square) of {@code reach} in both passes: depth tested in {@code color}, see-through at the ghost alpha. */
    private static void outline(WorldDraw d, Facing facing, double cx, double cy, double cz, double reach, Shape shape,
                                int color, boolean dashed, int segments) {
        d.seeThrough(false);
        shapeOutline(d, facing, cx, cy, cz, reach, shape, color, dashed, segments);
        d.seeThrough(true);
        shapeOutline(d, facing, cx, cy, cz, reach, shape, OverlayColors.scaleAlpha(color, GHOST_ALPHA), dashed,
                segments);
        d.seeThrough(false);
    }

    private static void shapeOutline(WorldDraw d, Facing facing, double cx, double cy, double cz, double reach,
                                     Shape shape, int color, boolean dashed, int segments) {
        if (shape == Shape.SQUARE) {
            double[][] corners = {{-reach, -reach}, {reach, -reach}, {reach, reach}, {-reach, reach}};
            for (int i = 0; i < 4; i++) {
                double[] a = corners[i], b = corners[(i + 1) % 4];
                segment(d, facing, cx, cy, cz, a[0], a[1], b[0], b[1], color);
            }
        } else {
            circle(d, facing, cx, cy, cz, reach, color, dashed, segments);
        }
    }

    /** A short tick from (x, y, z) toward {@code direction}, the way Raise pushes or Lower pulls; longer for a big brush. */
    static void directionTick(WorldDraw d, double x, double y, double z, Facing direction, int radius, int color) {
        double length = Math.max(1.5, radius / 4.0);
        double dx = direction.axis() == 0 ? direction.sign() * length : 0;
        double dy = direction.axis() == 1 ? direction.sign() * length : 0;
        double dz = direction.axis() == 2 ? direction.sign() * length : 0;
        d.seeThrough(true);
        d.line(x, y, z, x + dx, y + dy, z + dz, color);
        d.seeThrough(false);
    }

    /**
     * Surface-mode Flatten's plane: a faint ring (or square) with a cross, in the plane, around the point of it nearest
     * (x, y, z), seen through the terrain.
     */
    static void surfacePlane(WorldDraw d, SurfacePlane plane, double x, double y, double z, int radius, Shape shape,
                             int color) {
        Facing facing = plane.facing();
        double at = plane.face16() / 16.0 + LIFT * facing.sign();
        double cx = facing.axis() == 0 ? at : x;
        double cy = facing.axis() == 1 ? at : y;
        double cz = facing.axis() == 2 ? at : z;
        double reach = radius + 0.5;
        int faint = OverlayColors.scaleAlpha(color, 0.7);
        d.seeThrough(true);
        if (shape == Shape.SQUARE) {
            double[][] corners = {{-reach, -reach}, {reach, -reach}, {reach, reach}, {-reach, reach}};
            for (int i = 0; i < 4; i++) {
                double[] a = corners[i], b = corners[(i + 1) % 4];
                segment(d, facing, cx, cy, cz, a[0], a[1], b[0], b[1], faint);
            }
        } else {
            int segments = ringSegments(radius);
            circle(d, facing, cx, cy, cz, reach, faint, false, segments);
            circle(d, facing, cx, cy, cz, reach * 0.5, OverlayColors.scaleAlpha(color, 0.4), false, segments);
        }
        segment(d, facing, cx, cy, cz, -reach, 0, reach, 0, faint);
        segment(d, facing, cx, cy, cz, 0, -reach, 0, reach, faint);
        d.seeThrough(false);
    }

    /**
     * A circle across {@code facing}'s axis of {@code segments} segments; dashed, they alternate in runs of
     * {@code segments / 24} between {@code color} and {@value #DASH_GAP_ALPHA} of it.
     */
    private static void circle(WorldDraw d, Facing facing, double cx, double cy, double cz, double r, int color,
                               boolean dashed, int segments) {
        int gap = OverlayColors.scaleAlpha(color, DASH_GAP_ALPHA);
        int dash = Math.max(1, segments / 24);
        double previousU = r, previousV = 0;
        for (int i = 1; i <= segments; i++) {
            double angle = 2 * Math.PI * i / segments;
            double u = r * Math.cos(angle), v = r * Math.sin(angle);
            boolean bright = !dashed || ((i - 1) / dash) % 2 == 0;
            segment(d, facing, cx, cy, cz, previousU, previousV, u, v, bright ? color : gap);
            previousU = u;
            previousV = v;
        }
    }

    /** A line between two points (u, v) of the plane across {@code facing}'s axis through (cx, cy, cz). */
    private static void segment(WorldDraw d, Facing facing, double cx, double cy, double cz, double u1, double v1,
                                double u2, double v2, int color) {
        switch (facing.axis()) {
            case 0 -> d.line(cx, cy + u1, cz + v1, cx, cy + u2, cz + v2, color);
            case 1 -> d.line(cx + u1, cy, cz + v1, cx + u2, cy, cz + v2, color);
            default -> d.line(cx + u1, cy + v1, cz, cx + u2, cy + v2, cz, color);
        }
    }

    /** The top face of a column's surface, or {@code fallback} when it has none. */
    private static double top(SurfaceSampler.Samples samples, int x, int z, double fallback) {
        int height = samples.heightAtWorld(x, z);
        return height == SurfaceSampler.NONE ? fallback : height + 1.0;
    }
}
