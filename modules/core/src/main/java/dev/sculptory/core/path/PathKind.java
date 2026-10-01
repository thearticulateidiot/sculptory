package dev.sculptory.core.path;

/** How a line runs through its points. */
public enum PathKind {
    /** Straight segments from point to point. */
    STRAIGHT,
    /** A 3D Catmull-Rom curve through every point. */
    CURVE,
    /** A catenary between each pair of points, sagging {@code PathSpec.sag} blocks at the middle of each span. */
    HANGING
}
