package dev.sculptory.core.transform;

/**
 * How a free {@link Orientation} (a turn that is not a multiple of 90° about the vertical) maps blocks onto the grid:
 * the paste ghost's and Rotate…'s "Method" setting. An exact orientation is a permutation of cells and has no
 * resampling (it is always {@link #NEAREST} there). Written by ordinal on the wire (protocol 6, see
 * {@code CoreCodec.writeOrientation}): new methods go at the end.
 *
 * <p>Until they are built, the programs and the ghost treat {@link #EXACT} and {@link #SMOOTH} as
 * {@link #NEAREST} (same cells, same counts), so a client may already send them.
 */
public enum Resampling {
    /**
     * Each result cell takes the source cell its centre turns back into ({@link Orientation#inverseCell}): no holes,
     * but some source blocks are doubled and others lost on a non-90° turn. The default, and today's behaviour.
     */
    NEAREST,
    /**
     * Exact count: the rotation is decomposed into three shears per axis (composed for pitch and roll), each a
     * bijection on the grid, so every source block lands in exactly one cell: no holes, no doubles, the block count
     * kept.
     */
    EXACT,
    /**
     * Smooth: each result cell samples a small grid of points (3×3×3 or 2×2×2) turned back into the source and takes
     * the majority non-air state when more than half of the points land on blocks, else air (ties: the nearest
     * block). Fuller shapes and rounder edges.
     */
    SMOOTH
}
