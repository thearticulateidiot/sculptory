package dev.sculptory.core.path;

/** A point on a line, in world coordinates, {@code along} blocks from its start measured along the line. */
public record PathSample(double x, double y, double z, double along) {}
