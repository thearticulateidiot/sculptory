package dev.sculptory.core.brush;

import java.util.Arrays;
import java.util.Objects;

/**
 * A grayscale height image: {@code width × height} unsigned bytes, row by row from the top ({@code pixels[y * width +
 * x]}), 0 low and 255 high. Seen from above with north up, x runs east and y south. The Heightmap brush resamples it to
 * its square stamp at any angle ({@link #stamp}); the Generate tool's Heightmap import stretches it over a selection
 * ({@link #stretch}). Both sample with {@code StrictMath} only, so a result never depends on the machine. Immutable.
 *
 * @param width 1-{@value #MAX_SIDE} pixels
 * @param height 1-{@value #MAX_SIDE} pixels
 * @param pixels {@code width × height} bytes; copied in and out
 */
public record HeightImage(int width, int height, byte[] pixels) {
    /** The widest and tallest image read. */
    public static final int MAX_SIDE = 4096;
    /** Most samples per output pixel along each axis when shrinking an image (an average of up to 4 × 4). */
    static final int MAX_SUPERSAMPLE = 4;

    public HeightImage {
        Objects.requireNonNull(pixels);
        if (width < 1 || width > MAX_SIDE || height < 1 || height > MAX_SIDE) {
            throw new IllegalArgumentException("A height image is 1-" + MAX_SIDE + " pixels a side, not " + width + "x" + height);
        }
        if (pixels.length != width * height) {
            throw new IllegalArgumentException("A " + width + "x" + height + " image needs " + width * height + " pixels");
        }
        pixels = pixels.clone();
    }

    @Override
    public byte[] pixels() {
        return pixels.clone();
    }

    /** Pixel (x, y), 0-255. */
    public int value(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) throw new IndexOutOfBoundsException(x + "," + y);
        return pixels[y * width + x] & 0xFF;
    }

    /**
     * The Heightmap brush's stamp of this image: {@code side × side} values ({@code [v * side + u]}, u east, v south)
     * for a brush of {@code side = 2 × radius + 1}, turned {@code degrees} clockwise seen from above (east to south) about
     * the stamp's centre. The image is fitted inside the square, its longer side across the whole stamp and its shorter
     * one centred; outside the picture the stamp is 0. Each value averages up to {@value #MAX_SUPERSAMPLE} ×
     * {@value #MAX_SUPERSAMPLE} bilinear samples within its pixel (more where the image is shrunk more), rounded.
     * Quarter turns are exact.
     */
    public byte[] stamp(int side, int degrees) {
        if (side < 1 || side > HeightmapSpec.MAX_SIDE) throw new IllegalArgumentException("Stamp side must be 1-" + HeightmapSpec.MAX_SIDE);
        double scale = (double) Math.max(width, height) / side;
        int k = supersample(scale);
        double[] trig = turn(degrees);
        double cos = trig[0], sin = trig[1];
        byte[] out = new byte[side * side];
        double half = side / 2.0;
        for (int v = 0; v < side; v++) {
            for (int u = 0; u < side; u++) {
                long sum = 0;
                for (int sv = 0; sv < k; sv++) {
                    for (int su = 0; su < k; su++) {
                        double px = u + (su + 0.5) / k - half;
                        double py = v + (sv + 0.5) / k - half;
                        // Back from the turned stamp to the picture: the inverse of the clockwise turn.
                        double qx = px * cos + py * sin;
                        double qy = -px * sin + py * cos;
                        sum += sample(qx * scale + width / 2.0, qy * scale + height / 2.0, false);
                    }
                }
                out[v * side + u] = (byte) average(sum, k);
            }
        }
        return out;
    }

    /**
     * This image stretched over a {@code columns × rows} footprint ({@code [row * columns + column]}, columns east, rows
     * south), first turned {@code quarterTurns} quarter turns clockwise seen from above, then, with {@code invert}, each
     * value {@code 255 - value}. The edges are held (no fade at the border). Each value averages up to
     * {@value #MAX_SUPERSAMPLE} × {@value #MAX_SUPERSAMPLE} bilinear samples, rounded.
     */
    public byte[] stretch(int columns, int rows, int quarterTurns, boolean invert) {
        if (columns < 1 || rows < 1) throw new IllegalArgumentException("Empty footprint");
        if ((long) columns * rows > Integer.MAX_VALUE - 8) throw new IllegalArgumentException("Footprint too large");
        int q = Math.floorMod(quarterTurns, 4);
        // The picture's width lies along x unturned or half turned, along z after one or three quarter turns.
        int along = q % 2 == 0 ? columns : rows;
        int across = q % 2 == 0 ? rows : columns;
        int k = supersample(Math.max((double) width / along, (double) height / across));
        byte[] out = new byte[columns * rows];
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                long sum = 0;
                for (int sr = 0; sr < k; sr++) {
                    for (int sc = 0; sc < k; sc++) {
                        double a = (column + (sc + 0.5) / k) / columns;
                        double b = (row + (sr + 0.5) / k) / rows;
                        double u, v;
                        switch (q) {
                            case 1 -> {
                                u = b;
                                v = 1 - a;
                            }
                            case 2 -> {
                                u = 1 - a;
                                v = 1 - b;
                            }
                            case 3 -> {
                                u = 1 - b;
                                v = a;
                            }
                            default -> {
                                u = a;
                                v = b;
                            }
                        }
                        sum += sample(u * width, v * height, true);
                    }
                }
                int value = average(sum, k);
                out[row * columns + column] = (byte) (invert ? 255 - value : value);
            }
        }
        return out;
    }

    /** The cosine and sine of a clockwise turn of {@code degrees}: exact for quarter turns, {@code StrictMath} otherwise. */
    static double[] turn(int degrees) {
        int d = Math.floorMod(degrees, 360);
        return switch (d) {
            case 0 -> new double[] {1, 0};
            case 90 -> new double[] {0, 1};
            case 180 -> new double[] {-1, 0};
            case 270 -> new double[] {0, -1};
            default -> {
                double radians = StrictMath.toRadians(d);
                yield new double[] {StrictMath.cos(radians), StrictMath.sin(radians)};
            }
        };
    }

    /** Samples per axis for a shrink of {@code scale} image pixels per output pixel: 1 to {@value #MAX_SUPERSAMPLE}. */
    private static int supersample(double scale) {
        return (int) Math.max(1, Math.min(MAX_SUPERSAMPLE, StrictMath.ceil(scale - 1e-9)));
    }

    /** The rounded mean of {@code k × k} samples, each 256 times its value ({@link #sample}), clamped to 0-255. */
    private static int average(long sum, int k) {
        long n = (long) k * k * 256;
        return (int) Math.max(0, Math.min(255, (sum + n / 2) / n));
    }

    /**
     * The bilinear value at image point (x, y) (pixel centres at half steps), times 256 and rounded down: outside the
     * picture 0, or with {@code clamp} the nearest edge pixel.
     */
    private long sample(double x, double y, boolean clamp) {
        double sx = x - 0.5, sy = y - 0.5;
        double fx0 = StrictMath.floor(sx), fy0 = StrictMath.floor(sy);
        int x0 = (int) Math.max(-2, Math.min(width + 1, fx0));
        int y0 = (int) Math.max(-2, Math.min(height + 1, fy0));
        double tx = Math.max(0, Math.min(1, sx - fx0)), ty = Math.max(0, Math.min(1, sy - fy0));
        double top = pixel(x0, y0, clamp) * (1 - tx) + pixel(x0 + 1, y0, clamp) * tx;
        double bottom = pixel(x0, y0 + 1, clamp) * (1 - tx) + pixel(x0 + 1, y0 + 1, clamp) * tx;
        return (long) ((top * (1 - ty) + bottom * ty) * 256);
    }

    private int pixel(int x, int y, boolean clamp) {
        if (clamp) {
            x = Math.max(0, Math.min(width - 1, x));
            y = Math.max(0, Math.min(height - 1, y));
        } else if (x < 0 || x >= width || y < 0 || y >= height) {
            return 0;
        }
        return pixels[y * width + x] & 0xFF;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof HeightImage other && width == other.width && height == other.height
                && Arrays.equals(pixels, other.pixels);
    }

    @Override
    public int hashCode() {
        return (width * 31 + height) * 31 + Arrays.hashCode(pixels);
    }

    @Override
    public String toString() {
        return "HeightImage[" + width + "x" + height + "]";
    }
}
