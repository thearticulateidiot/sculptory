package dev.sculptory.mc.client.editor.render.ghost;

import dev.sculptory.core.Box;
import dev.sculptory.core.transform.Orientation;
import dev.sculptory.core.transform.Transform;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * One ghost preview to draw: a {@link GhostVolume} placed in the world. {@code origin} is the world cell where the
 * transformed frame's minimum corner lands (see {@link GhostMapping}); a free {@code orientation} draws the volume
 * turned as a solid (a placement being turned, before its ghost is resampled).
 *
 * <p>Changing the origin or transform of a {@link LightMode#FLAT FLAT} placement only changes the model matrix; no
 * section is meshed again. {@link LightMode#WORLD WORLD} meshes bake the world's light at the placed position, so
 * they are rebuilt once the placement has stopped moving; use FLAT while dragging.
 *
 * @param alpha colour-pass opacity, 0..1 (clamped)
 */
public record GhostPlacement(
        GhostVolume volume,
        int originX,
        int originY,
        int originZ,
        Orientation orientation,
        float alpha,
        LightMode lightMode) {
    public static final float DEFAULT_ALPHA = 0.55f;

    /** How meshes are lit. */
    public enum LightMode {
        /** Full-bright: independent of the placement, for moving previews. */
        FLAT,
        /** Samples the client world's light (and biome colours) at each cell's placed position. */
        WORLD
    }

    public GhostPlacement {
        Objects.requireNonNull(volume, "volume");
        Objects.requireNonNull(orientation, "orientation");
        Objects.requireNonNull(lightMode, "lightMode");
        alpha = Float.isNaN(alpha) ? DEFAULT_ALPHA : Math.max(0f, Math.min(1f, alpha));
    }

    /** A placement with an exact transform. */
    public GhostPlacement(GhostVolume volume, int originX, int originY, int originZ, Transform transform, float alpha,
                          LightMode lightMode) {
        this(volume, originX, originY, originZ, Orientation.of(Objects.requireNonNull(transform, "transform")), alpha,
                lightMode);
    }

    /** The exact part of the orientation (all of it unless it is free). */
    public Transform transform() {
        return orientation.transform();
    }

    /** An unrotated, full-bright placement with the default alpha. */
    public static GhostPlacement of(GhostVolume volume, int originX, int originY, int originZ) {
        return new GhostPlacement(volume, originX, originY, originZ, Orientation.IDENTITY, DEFAULT_ALPHA, LightMode.FLAT);
    }

    public GhostPlacement withOrigin(int x, int y, int z) {
        return new GhostPlacement(volume, x, y, z, orientation, alpha, lightMode);
    }

    public GhostPlacement withTransform(Transform newTransform) {
        return withOrientation(Orientation.of(newTransform));
    }

    public GhostPlacement withOrientation(Orientation newOrientation) {
        return new GhostPlacement(volume, originX, originY, originZ, newOrientation, alpha, lightMode);
    }

    public GhostPlacement withAlpha(float newAlpha) {
        return new GhostPlacement(volume, originX, originY, originZ, orientation, newAlpha, lightMode);
    }

    public GhostPlacement withLightMode(LightMode newMode) {
        return new GhostPlacement(volume, originX, originY, originZ, orientation, alpha, newMode);
    }

    /** The cell mapping for the volume's current frame, or null while the volume is empty. */
    public @Nullable GhostMapping mapping() {
        Box frame = volume.frame();
        return frame == null ? null : new GhostMapping(frame, orientation, originX, originY, originZ);
    }
}
