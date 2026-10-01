package dev.sculptory.fabric.client.editor.render;

import dev.sculptory.fabric.client.editor.ui.UiOpacity;

/**
 * The Tool outlines opacity (View > Opacity…): one factor on the alpha of everything tools draw in the world, applied
 * where overlay colours reach the GPU, so no tool has to know about it: {@link LineBatch} and {@link QuadBatch}
 * (selection box and handles, brush, shape and fluid cursors, gizmo, symmetry markers, lasso and brush-select paths,
 * the ghosts' boxes), {@link CellMeshBuffers} (exact selection and shape outlines) and the ghost previews' colour pass.
 * A colour that shows keeps at least alpha 1, so nothing disappears at the lowest setting (10%).
 *
 * <p>One factor for the client. Set on the client thread ({@link #follow}), read on the render thread (the same
 * thread in 1.21.1).
 */
public final class OverlayOpacity {
    private static volatile float factor = 1.0F;

    private OverlayOpacity() {}

    /** The factor now, 0.1 to 1. */
    public static float factor() {
        return factor;
    }

    /** Sets the factor (clamped to 0.1–1; NaN gives 1). */
    public static void set(float fraction) {
        factor = Float.isNaN(fraction) ? 1.0F : Math.max(UiOpacity.OUTLINES_MIN / 100.0F, Math.min(1.0F, fraction));
    }

    /** Keeps the factor at {@code opacity}'s Tool outlines setting, now and after every change. */
    public static void follow(UiOpacity opacity) {
        set(opacity.values().outlineAlpha());
        opacity.addListener(values -> set(values.outlineAlpha()));
    }

    /** {@code argb} with its alpha multiplied by the factor; a colour that shows keeps at least alpha 1. */
    public static int apply(int argb) {
        float f = factor;
        int alpha = argb >>> 24;
        if (f >= 1.0F || alpha == 0) {
            return argb;
        }
        return OverlayColors.withAlpha(argb, Math.max(1, Math.round(alpha * f)));
    }

    /** An opacity (0 to 1, the ghost previews' colour pass) multiplied by the factor. */
    public static float alpha(float alpha) {
        return alpha * factor;
    }
}
