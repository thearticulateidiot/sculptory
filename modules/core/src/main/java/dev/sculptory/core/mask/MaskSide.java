package dev.sculptory.core.mask;

/**
 * Where an op's cells are judged by the global mask: where they
 * land ({@link #DESTINATION}: fills, pastes, stacks, brushes, generators, scatter, builder mode), or where they are
 * lifted from ({@link #SOURCE}: Move, Cut and Copy take only the matching blocks; Move's landing writes are not masked).
 */
public enum MaskSide {
    DESTINATION,
    SOURCE
}
