package dev.sculptory.core.region;

/**
 * The shapes a {@link Region.Shape} can inscribe in its box.
 * Wire order: append only.
 */
public enum ShapeKind {
    /** {@code u² + v² + w² <= 1}; the facing is ignored. */
    ELLIPSOID,
    /** {@code p² + q² <= 1}: its axis runs along the facing. */
    CYLINDER,
    /** {@code p² + q² <= t²}: base at the back of the box, apex at the facing end. */
    CONE,
    /** {@code max(|p|, |q|) <= t}: square base at the back of the box, apex at the facing end. */
    PYRAMID
}
