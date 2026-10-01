package dev.sculptory.core.brush;

/**
 * One brush application. Coordinates are fixed point in 1/16 block so client and server agree exactly.
 *
 * @param index position of this dab within its stroke, from 0
 * @param pressure 0-255, where {@link #FULL_PRESSURE} is full strength
 */
public record Dab(int index, int x16, int y16, int z16, int pressure) {
    public static final int FULL_PRESSURE = 255;

    public Dab {
        if (index < 0) throw new IllegalArgumentException("Negative dab index");
        if (pressure < 0 || pressure > FULL_PRESSURE) throw new IllegalArgumentException("Dab pressure must be 0-255");
    }

    /** A dab at a block-space point, rounded down to 1/16 block. */
    public static Dab of(int index, double x, double y, double z, int pressure) {
        return new Dab(index, fixed(x), fixed(y), fixed(z), pressure);
    }

    /** The block containing the dab centre. */
    public int blockX() {
        return x16 >> 4;
    }

    public int blockY() {
        return y16 >> 4;
    }

    public int blockZ() {
        return z16 >> 4;
    }

    private static int fixed(double value) {
        double scaled = Math.floor(value * 16.0);
        if (!(scaled >= Integer.MIN_VALUE && scaled <= Integer.MAX_VALUE)) {
            throw new IllegalArgumentException("Dab coordinate out of range");
        }
        return (int) scaled;
    }
}
