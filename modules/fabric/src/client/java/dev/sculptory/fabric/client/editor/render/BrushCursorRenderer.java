package dev.sculptory.fabric.client.editor.render;

import dev.sculptory.fabric.client.editor.world.SurfaceSampler;

/**
 * Draws the brush cursor: a {@value #RING_SEGMENTS}-segment ring that follows the terrain using
 * {@link SurfaceSampler} heights, an inner ring where the falloff starts, and optionally a per-column
 * top-face tint weighted by falloff. A server-only tool (no client prediction) gets a dashed ring.
 *
 * <p>The ring also gets a see-through pass at {@value #GHOST_ALPHA} of its alpha so it stays readable behind hills, in
 * dents and under overhangs.
 */
public final class BrushCursorRenderer {
    public static final int RING_SEGMENTS = 96;
    /** Segments drawn, then skipped, per dash of the server-only ring. */
    public static final int DASH_SEGMENTS = 2;
    /** Height above the surface the ring floats at, to stay clear of the top faces. */
    public static final double RING_LIFT = 0.05;
    /** Height above the surface the column tint sits at (polygon offset does the rest). */
    public static final double TINT_LIFT = 0.005;
    /** The see-through pass's share of the ring's alpha. */
    public static final double GHOST_ALPHA = 0.55;
    /** Tint alpha (0..255) at full falloff weight. */
    public static final int TINT_ALPHA = 0x48;

    private BrushCursorRenderer() {}

    public static void render(LineBatch lines, QuadBatch quads, BrushCursor cursor) {
        SurfaceSampler.Samples samples = cursor.samples();
        double centerX = samples.centerX() + 0.5;
        double centerZ = samples.centerZ() + 0.5;
        double outer = outerRadius(samples.radius());
        double fallbackTop = fallbackTop(samples);

        if (cursor.columnTint()) {
            tint(quads, samples, cursor.falloffStart(), cursor.color());
        }
        ring(lines, samples, centerX, centerZ, outer, fallbackTop, cursor.color(), cursor.serverOnly());
        double falloffStart = cursor.falloffStart();
        if (falloffStart > 0 && falloffStart < 1) {
            int inner = OverlayColors.scaleAlpha(cursor.color(), 0.6);
            ring(lines, samples, centerX, centerZ, outer * falloffStart, fallbackTop, inner, cursor.serverOnly());
        }
    }

    /** Ring radius for a sampled disc radius: the disc's rounded edge, {@code radius + 0.5}. */
    public static double outerRadius(int radius) {
        return radius + 0.5;
    }

    /**
     * Brush weight at {@code distance} from the centre: 1 inside {@code falloffStart * outerRadius},
     * falling linearly to 0 at {@code outerRadius}.
     */
    public static double falloffWeight(double distance, double outerRadius, double falloffStart) {
        double inner = outerRadius * falloffStart;
        if (distance <= inner) {
            return 1.0;
        }
        if (distance >= outerRadius) {
            return 0.0;
        }
        return 1.0 - (distance - inner) / (outerRadius - inner);
    }

    /** Whether segment {@code index} of a ring is drawn. */
    public static boolean segmentDrawn(int index, boolean dashed) {
        return !dashed || (index / DASH_SEGMENTS) % 2 == 0;
    }

    /**
     * Top-face height under a ring point. The lookup is pulled half a block towards the centre, so a
     * ring hugging the disc edge reads the edge columns rather than the ones just outside.
     */
    public static double surfaceTop(
            SurfaceSampler.Samples samples, double centerX, double centerZ, double pointX, double pointZ, double fallbackTop) {
        double dx = pointX - centerX;
        double dz = pointZ - centerZ;
        double distance = Math.sqrt(dx * dx + dz * dz);
        double pull = distance > 0.5 ? (distance - 0.5) / distance : 0.0;
        int columnX = (int) Math.floor(centerX + dx * pull);
        int columnZ = (int) Math.floor(centerZ + dz * pull);
        int height = samples.heightAtWorld(columnX, columnZ);
        return height == SurfaceSampler.NONE ? fallbackTop : height + 1.0;
    }

    /** Top face of the centre column, or of the hit block when the centre has no surface. */
    static double fallbackTop(SurfaceSampler.Samples samples) {
        int center = samples.heightAt(0, 0);
        return (center == SurfaceSampler.NONE ? samples.centerY() : center) + 1.0;
    }

    private static void ring(
            LineBatch lines,
            SurfaceSampler.Samples samples,
            double centerX,
            double centerZ,
            double radius,
            double fallbackTop,
            int color,
            boolean dashed) {
        double[] xs = new double[RING_SEGMENTS + 1];
        double[] ys = new double[RING_SEGMENTS + 1];
        double[] zs = new double[RING_SEGMENTS + 1];
        for (int i = 0; i <= RING_SEGMENTS; i++) {
            double angle = 2 * Math.PI * (i % RING_SEGMENTS) / RING_SEGMENTS;
            xs[i] = centerX + radius * Math.cos(angle);
            zs[i] = centerZ + radius * Math.sin(angle);
            ys[i] = surfaceTop(samples, centerX, centerZ, xs[i], zs[i], fallbackTop) + RING_LIFT;
        }
        int ghost = OverlayColors.scaleAlpha(color, GHOST_ALPHA);
        for (int i = 0; i < RING_SEGMENTS; i++) {
            if (!segmentDrawn(i, dashed)) {
                continue;
            }
            lines.line(xs[i], ys[i], zs[i], xs[i + 1], ys[i + 1], zs[i + 1], ghost, true);
            lines.line(xs[i], ys[i], zs[i], xs[i + 1], ys[i + 1], zs[i + 1], color, false);
        }
    }

    private static void tint(QuadBatch quads, SurfaceSampler.Samples samples, double falloffStart, int color) {
        int radius = samples.radius();
        double outer = outerRadius(radius);
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int height = samples.heightAt(dx, dz);
                if (height == SurfaceSampler.NONE) {
                    continue;
                }
                double weight = falloffWeight(Math.sqrt(dx * dx + dz * dz), outer, falloffStart);
                int alpha = (int) Math.round(TINT_ALPHA * weight);
                if (alpha <= 0) {
                    continue;
                }
                int x = samples.centerX() + dx;
                int z = samples.centerZ() + dz;
                quads.horizontal(x, z, x + 1.0, z + 1.0, height + 1.0 + TINT_LIFT, OverlayColors.withAlpha(color, alpha), false);
            }
        }
    }
}
