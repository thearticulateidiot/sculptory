package dev.sculptory.server.engine;

import java.util.Arrays;
import java.util.Objects;

/**
 * Whether a player may modify each cell of the part of a job's box inside one chunk. Denied cells are skipped and
 * counted.
 *
 * <p>{@link Columns} answers per column, whatever the height (protection that is the same all the way up: spawn
 * protection, claims); {@link Cells} answers per cell over the box's heights (protection limited in height, such as
 * a region that spans only some layers of a chunk).
 */
public sealed interface ChunkPermit {
    ChunkPermit ALLOW = new Allow();
    ChunkPermit DENY = new Deny();

    /** Whether cell (x, y, z) may be modified; world coordinates, only the low 4 bits of x and z are used. */
    boolean allows(int x, int y, int z);

    record Allow() implements ChunkPermit {
        @Override
        public boolean allows(int x, int y, int z) {
            return true;
        }
    }

    record Deny() implements ChunkPermit {
        @Override
        public boolean allows(int x, int y, int z) {
            return false;
        }
    }

    /**
     * Per-column answers, the same at every height: bit {@code ((z & 15) << 4) | (x & 15)} of the 256-bit set
     * {@code allowed} (4 longs). The array is copied in and out.
     */
    record Columns(long[] allowed) implements ChunkPermit {
        public Columns {
            Objects.requireNonNull(allowed);
            if (allowed.length != 4) throw new IllegalArgumentException("Expected 256 bits");
            allowed = allowed.clone();
        }

        @Override
        public long[] allowed() {
            return allowed.clone();
        }

        @Override
        public boolean allows(int x, int y, int z) {
            int bit = ((z & 15) << 4) | (x & 15);
            return (allowed[bit >>> 6] & (1L << bit)) != 0;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Columns other && Arrays.equals(allowed, other.allowed);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(allowed);
        }
    }

    /**
     * Per-cell answers for the layers {@code minY..maxY} (the box the permit was asked for, not the world's height, so
     * it stays small): layer {@code y} is the 256 bits at {@link #word} {@code (y, 0)..(y, 3)} of {@code allowed}, bit
     * {@code ((z & 15) << 4) | (x & 15)} as in {@link Columns}. A cell above or below the layers is denied: the permit
     * says nothing about it. The array is copied in and out.
     */
    record Cells(int minY, int maxY, long[] allowed) implements ChunkPermit {
        public Cells {
            Objects.requireNonNull(allowed);
            if (maxY < minY) throw new IllegalArgumentException("Empty layer range " + minY + ".." + maxY);
            if (allowed.length != ((long) maxY - minY + 1) * 4) {
                throw new IllegalArgumentException("Expected 256 bits for each of the layers " + minY + ".." + maxY);
            }
            allowed = allowed.clone();
        }

        /** The index in {@code allowed} of word {@code w} (0-3) of layer {@code y}, which must lie in the layers. */
        public static int word(int minY, int y, int w) {
            return ((y - minY) << 2) | w;
        }

        @Override
        public long[] allowed() {
            return allowed.clone();
        }

        @Override
        public boolean allows(int x, int y, int z) {
            if (y < minY || y > maxY) return false;
            int bit = ((z & 15) << 4) | (x & 15);
            return (allowed[word(minY, y, bit >>> 6)] & (1L << bit)) != 0;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Cells other && minY == other.minY && maxY == other.maxY
                    && Arrays.equals(allowed, other.allowed);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * minY + maxY) + Arrays.hashCode(allowed);
        }

        @Override
        public String toString() {
            return "Cells[" + minY + ".." + maxY + "]";
        }
    }
}
