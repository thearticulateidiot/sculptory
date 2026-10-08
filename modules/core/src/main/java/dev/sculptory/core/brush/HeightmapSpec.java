package dev.sculptory.core.brush;

import java.util.Arrays;
import java.util.Objects;

/**
 * What the Heightmap brush stamps ({@link BrushTool#HEIGHTMAP}, protocol 6): a square grayscale height image, already
 * resampled and turned by the client to the brush's footprint, so the server never sees the picture itself.
 *
 * <p>The image covers the dab's footprint exactly: {@code side} is {@code 2 * radius + 1} of its {@link BrushSpec}, and
 * pixel {@code (u, v)} lies over column {@code (dabX - radius + u, dabZ - radius + v)}. {@link #height(int, int)} is
 * unsigned, 0-255; 255 is {@code heightBlocks} blocks. Each column's target is its pre-stroke surface plus (or, with
 * {@code lower}, minus) {@code height / 255 × heightBlocks}; a stroke never goes beyond the highest target any of its
 * dabs asked for, so a drag lays a ridge instead of piling up.
 *
 * @param side 1-{@value #MAX_SIDE}: pixels per row and per column
 * @param heights {@code side × side} unsigned bytes, row by row ({@code heights[v * side + u]}); copied in and out
 * @param heightBlocks 1-{@value #MAX_HEIGHT_BLOCKS}: blocks a full-white pixel raises or lowers
 * @param lower whether the stamp lowers the terrain instead of raising it
 */
public record HeightmapSpec(int side, byte[] heights, int heightBlocks, boolean lower) {
    /** The widest image: the footprint of the largest brush radius, {@code 2 × 32 + 1}. */
    public static final int MAX_SIDE = 2 * BrushSpec.MAX_RADIUS + 1;
    /** The tallest stamp, in blocks. */
    public static final int MAX_HEIGHT_BLOCKS = 128;

    public HeightmapSpec {
        Objects.requireNonNull(heights);
        if (side < 1 || side > MAX_SIDE) throw new IllegalArgumentException("Heightmap side must be 1-" + MAX_SIDE);
        if (heights.length != side * side) {
            throw new IllegalArgumentException("Heightmap needs " + side * side + " heights, not " + heights.length);
        }
        if (heightBlocks < 1 || heightBlocks > MAX_HEIGHT_BLOCKS) {
            throw new IllegalArgumentException("Heightmap height must be 1-" + MAX_HEIGHT_BLOCKS + " blocks");
        }
        heights = heights.clone();
    }

    /** A copy of the heights, row by row. */
    @Override
    public byte[] heights() {
        return heights.clone();
    }

    /** Pixel {@code (u, v)}'s height, 0-255. */
    public int height(int u, int v) {
        if (u < 0 || u >= side || v < 0 || v >= side) throw new IndexOutOfBoundsException(u + "," + v);
        return heights[v * side + u] & 0xFF;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof HeightmapSpec other && side == other.side && heightBlocks == other.heightBlocks
                && lower == other.lower && Arrays.equals(heights, other.heights);
    }

    @Override
    public int hashCode() {
        return Objects.hash(side, heightBlocks, lower) * 31 + Arrays.hashCode(heights);
    }

    @Override
    public String toString() {
        return "HeightmapSpec[side=" + side + ", heightBlocks=" + heightBlocks + ", lower=" + lower + "]";
    }
}
