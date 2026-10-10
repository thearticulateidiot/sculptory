package dev.sculptory.core.brush;

import java.util.Arrays;
import java.util.Objects;

/**
 * What the Heightmap brush stamps ({@link BrushTool#HEIGHTMAP}, protocol 6): a square grayscale height image, already
 * resampled and turned by the client to the brush's footprint, so the server never sees the picture itself.
 *
 * <p>The image covers the dab's footprint exactly: {@code side} is {@code 2 * radius + 1} of its {@link BrushSpec}, and
 * pixel {@code (u, v)} lies over column {@code (dabX - radius + u, dabZ - radius + v)}, where the dab's column is
 * {@code dabX = floor(x16 / 16)} and {@code dabZ = floor(z16 / 16)} ({@link Dab#blockX()}, {@link Dab#blockZ()}); u runs
 * east (+x), v south (+z), so the picture lies as drawn when seen from above with north up. {@link #height(int, int)} is
 * unsigned, 0-255; 255 is {@code heightBlocks} blocks. Each column's target is its pre-stroke surface plus (or, with
 * {@code lower}, minus) {@code height / 255 × heightBlocks}, rounded to the nearest block; a stroke never goes beyond the
 * furthest target any of its dabs asked for (the highest when raising, the lowest when lowering), so a drag lays a ridge
 * instead of piling up.
 *
 * <p>With brush symmetry each copy stamps the mirrored or turned picture: the copy's columns are the images of the dab's
 * footprint under the copy's symmetry image, each taking the value of the column it is the image of, so a mirrored
 * stroke is the exact mirror image of the stroke (the footprint may then lie one column off the copy's own block, as a
 * mirror between blocks puts it). A dab on a mirror plane or the rotation centre stamps every image that maps onto it, each
 * column taking the highest. See {@code HeightmapKernel}.
 *
 * @param side 1-{@value #MAX_SIDE}: pixels per row and per column
 * @param heights {@code side × side} unsigned bytes, row by row ({@code heights[v * side + u]}); copied in and out
 * @param heightBlocks 1-{@value #MAX_HEIGHT_BLOCKS}: blocks a full-white pixel raises or lowers
 * @param lower whether the stamp lowers the terrain instead of raising it
 */
public record HeightmapSpec(int side, byte[] heights, int heightBlocks, boolean lower) {
    /** The widest image: the footprint of the Heightmap brush's largest radius, {@code 2 × 128 + 1}. */
    public static final int MAX_SIDE = 2 * BrushSpec.MAX_HEIGHTMAP_RADIUS + 1;
    /** The tallest stamp, in blocks. */
    public static final int MAX_HEIGHT_BLOCKS = 128;
    /**
     * The most cells one step of the Heightmap brush may write, for everyone: one stamp of the widest image, white all
     * over at the tallest height, moving every column {@value #MAX_HEIGHT_BLOCKS} blocks with its old top and two plants
     * ({@value #MAX_SIDE}² × 131, about 8.7 million). A step past it is refused while it is worked out
     * ({@link StepTooLargeException}); symmetric copies share it.
     */
    public static final long MAX_STEP_CELLS = (long) MAX_SIDE * MAX_SIDE * (MAX_HEIGHT_BLOCKS + 1 + TerrainKernel.MAX_PLANT_RUN);

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

    /**
     * The cells one stamp of this image is expected to write on open ground: for each pixel that moves its column, the
     * blocks it lifts or cuts ({@code value / 255 × heightBlocks}, rounded), plus the column's old top (or the floor a cut
     * stops on) and two plants. A column a stroke could not move as far as asked before may move further in a later dab,
     * so a step can write more than this; {@link #MAX_STEP_CELLS} bounds that. What the server counts against its op
     * volume limit when a stroke begins, times the copies.
     */
    public long stampCells() {
        long cells = 0;
        for (byte b : heights) {
            int lift = HeightmapKernel.lift(b & 0xFF, heightBlocks);
            if (lift > 0) cells += lift + 1 + TerrainKernel.MAX_PLANT_RUN;
        }
        return cells;
    }

    /**
     * What one dab of this stamp weighs in a player's queue of dabs, in radius-32 stamps: its columns over a radius-32
     * stamp's ({@code 65²}), rounded up; 1 up to radius 32, 16 at radius 128. The server counts a dab's copies times this
     * against its queued-dab budget until the dab is written, and the client counts its dabs in flight the same way until
     * the server reports them written, so it sends a wide stamp only when the server has room for it.
     */
    public int dabWeight() {
        return weight(side);
    }

    /** {@link #dabWeight()} of a stamp {@code side} pixels wide. */
    public static int weight(int side) {
        long columns = (long) side * side;
        long unit = (long) (2 * BrushSpec.MAX_RADIUS + 1) * (2 * BrushSpec.MAX_RADIUS + 1);
        return (int) Math.max(1, (columns + unit - 1) / unit);
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
