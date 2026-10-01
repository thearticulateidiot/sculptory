package dev.sculptory.core.brush;

import dev.sculptory.core.world.WorldReader;
import java.util.List;

/**
 * Applies dabs. Must be deterministic: the client (prediction) and server run the same kernel and must
 * write identical cells. Use only {@code + - * / Math.sqrt} and {@code StrictMath}; never depend on hash
 * iteration order.
 */
@FunctionalInterface
public interface BrushKernel {
    /**
     * Applies {@code dabs} as one step: every dab reads the world as it was before the step, and each column any of
     * them covers changes once, by the strongest weight among those whose scan window found its surface (a dab
     * standing much higher or lower than a column's surface leaves it alone). The order they are listed in makes no
     * difference, and a dab listed twice counts once. A step holds a dab and its symmetric copies, each at the height
     * given ({@link SymmetricStep#dabs}): 1 to {@value Symmetry#MAX_COPIES} dabs.
     *
     * <p>Surface-mode Flatten ({@link SculptMode#SURFACE}) takes the first dab listed as the stroke's own and each
     * other as one of its copies: a copy flattens against the plane's image under the symmetry image that maps the first
     * dab onto it, so its step lists the dab first, as {@link SymmetricStep#of} does. Palette Paint with a mix laid out
     * in space ({@code Pattern.Arranged}) takes the first dab the same way: a copy's cells read the pattern at their
     * pre-image under the image that maps the first dab onto the copy.
     *
     * <p>The Shape brush ({@link BrushTool#SHAPE}) is the other exception: its step must be exactly a dab and its
     * {@link Symmetry#copies}, in that order (as {@link SymmetricStep#of} lists them), since it places the first dab's
     * shape and its mirrored or turned images itself ({@link ShapeStep}).
     */
    void applyStep(BrushSpec s, List<Dab> dabs, StrokeState st, WorldReader w, CellSink out);

    /**
     * Applies one dab and its copies under the spec's {@link BrushSpec#symmetry()}, as one step: each copy stands on
     * the ground where it lands, found in {@code w} before the step, and a copy that finds none is left out
     * ({@link SymmetricStep#of}). The server does the same in two calls, so it can check the copies' areas first.
     */
    default void apply(BrushSpec s, Dab d, StrokeState st, WorldReader w, CellSink out) {
        applyStep(s, SymmetricStep.of(s, d, w).dabs(), st, w, out);
    }
}
