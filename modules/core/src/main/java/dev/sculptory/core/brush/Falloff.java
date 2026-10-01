package dev.sculptory.core.brush;

/** How brush strength scales from the centre to the rim. Wire order: append only. */
public enum Falloff {
    CONSTANT,
    LINEAR,
    SMOOTH,
    SPHERE
}
