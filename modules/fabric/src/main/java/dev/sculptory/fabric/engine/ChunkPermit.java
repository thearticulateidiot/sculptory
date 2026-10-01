package dev.sculptory.fabric.engine;

import java.util.Arrays;
import java.util.Objects;

/** Whether a player may modify each column of one chunk. Denied cells are skipped and counted. */
public sealed interface ChunkPermit {
    ChunkPermit ALLOW = new Allow();
    ChunkPermit DENY = new Deny();

    /** Whether column (x, z) may be modified; world coordinates, only the low 4 bits are used. */
    boolean allows(int x, int z);

    record Allow() implements ChunkPermit {
        @Override
        public boolean allows(int x, int z) {
            return true;
        }
    }

    record Deny() implements ChunkPermit {
        @Override
        public boolean allows(int x, int z) {
            return false;
        }
    }

    /**
     * Per-column answers: bit {@code ((z & 15) << 4) | (x & 15)} of the 256-bit set {@code allowed} (4 longs).
     * The array is copied in and out.
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
        public boolean allows(int x, int z) {
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
}
