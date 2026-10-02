package dev.sculptory.server.platform;

/**
 * A world border's bounds when they were asked for ({@link Platform#border}): its west, east, north and south edges,
 * in block coordinates. {@link Platform#insideBorder} says whether one column is inside.
 */
public record BorderBounds(double west, double east, double north, double south) {}
