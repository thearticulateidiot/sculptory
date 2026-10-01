package dev.sculptory.core.transform;

/**
 * A horizontal mirror. {@link #X} negates x (swaps east and west; vanilla {@code BlockMirror.FRONT_BACK}).
 * {@link #Z} negates z (swaps north and south; vanilla {@code BlockMirror.LEFT_RIGHT}).
 * Wire order: append only.
 */
public enum Mirror {
    NONE,
    X,
    Z
}
