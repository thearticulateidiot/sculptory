package dev.sculptory.core;

import java.util.Objects;

/** An integer block position. Arithmetic is overflow-checked. */
public record BlockPos(int x, int y, int z) implements Comparable<BlockPos> {
    public static final BlockPos ORIGIN = new BlockPos(0, 0, 0);

    public BlockPos offset(int dx, int dy, int dz) {
        return new BlockPos(Math.addExact(x, dx), Math.addExact(y, dy), Math.addExact(z, dz));
    }

    public BlockPos add(BlockPos other) {
        Objects.requireNonNull(other);
        return offset(other.x, other.y, other.z);
    }

    @Override
    public int compareTo(BlockPos other) {
        int result = Integer.compare(x, other.x);
        if (result == 0) result = Integer.compare(y, other.y);
        return result == 0 ? Integer.compare(z, other.z) : result;
    }
}
