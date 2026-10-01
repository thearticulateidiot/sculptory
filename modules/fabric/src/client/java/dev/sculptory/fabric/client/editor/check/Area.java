package dev.sculptory.fabric.client.editor.check;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;

/**
 * A scenario's own patch of the check world: {@link #SIZE} blocks square, a stone floor {@link #FLOOR} blocks thick
 * under {@code y0}, and air above up to {@link #HEIGHT} blocks, built in the sky so nothing of the world's terrain is
 * in it. Coordinates given to its helpers are relative to {@code (x0, y0, z0)}: {@code (0, 0, 0)} is the first air cell
 * of the corner, {@code (0, -1, 0)} the floor's top there.
 */
public record Area(String name, int x0, int y0, int z0) {
    public static final int SIZE = 40;
    public static final int HEIGHT = 36;
    public static final int FLOOR = 4;

    /** The whole area, floor included. */
    public Box box() {
        return Box.of(new BlockPos(x0, y0 - FLOOR, z0), new BlockPos(x0 + SIZE - 1, y0 + HEIGHT - 1, z0 + SIZE - 1));
    }

    /** A cell, relative. */
    public BlockPos at(int dx, int dy, int dz) {
        return new BlockPos(x0 + dx, y0 + dy, z0 + dz);
    }

    /** A box between two relative cells. */
    public Box box(int ax, int ay, int az, int bx, int by, int bz) {
        return Box.of(at(ax, ay, az), at(bx, by, bz));
    }

    public int x(int dx) {
        return x0 + dx;
    }

    public int y(int dy) {
        return y0 + dy;
    }

    public int z(int dz) {
        return z0 + dz;
    }
}
