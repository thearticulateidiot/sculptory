package dev.sculptory.fabric.client.editor.render;

import dev.sculptory.fabric.client.editor.world.SurfaceSampler;
import java.util.Objects;

/**
 * What the brush cursor should show.
 *
 * @param samples surface heights around the hit block, from {@link SurfaceSampler}; the ring is
 *     centred on the sampled centre column and hugs the disc ({@code radius + 0.5})
 * @param falloffStart fraction of the radius (0..1) where the falloff begins; an inner ring is drawn
 *     there when it lies strictly between 0 and 1
 * @param color the tool's colour (ARGB)
 * @param serverOnly draws a dashed ring: the tool runs on the server with no client prediction
 * @param columnTint also tints each column's top face, weighted by falloff
 */
public record BrushCursor(SurfaceSampler.Samples samples, double falloffStart, int color, boolean serverOnly, boolean columnTint) {
    public BrushCursor {
        Objects.requireNonNull(samples, "samples");
        falloffStart = Double.isNaN(falloffStart) ? 1.0 : Math.max(0.0, Math.min(1.0, falloffStart));
    }
}
