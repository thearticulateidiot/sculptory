package dev.sculptory.core;

/**
 * SplitMix64 finalizer and the position hash used for deterministic weighted picks
 * (ported from the old {@code SurfacePalettes.select}). Pure integer arithmetic, so the client and
 * server always agree. Java long overflow supplies the required wrapping.
 */
public final class SplitMix64 {
    private SplitMix64() {}

    /** The SplitMix64 output mix. */
    public static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    /** Chained position hash: seed, then x, y and z, each mixed in turn. */
    public static long hash(long seed, int x, int y, int z) {
        long hash = mix(seed ^ 0xD1B54A32D192ED03L);
        hash = mix(hash ^ (long) x);
        hash = mix(hash ^ (long) y);
        return mix(hash ^ (long) z);
    }
}
