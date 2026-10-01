package dev.sculptory.core.edit;

import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockBuffer;
import java.util.Arrays;
import java.util.Objects;

/**
 * The deterministic, chunk-local write order shared by every {@link EditProgram}: section x ascending,
 * then section z, then section y (all signed), so the sections of one chunk column are consecutive.
 * This is the same order as {@link Box#forEachSectionKey}.
 */
public final class SectionOrder {
    /**
     * XOR-ing a {@link BlockBuffer#key} with this turns the two's-complement z and y fields into offset
     * binary, so a signed numeric sort of the result orders keys by (sx, sz, sy). The x field needs no
     * flip: it occupies the top bits, where a signed comparison already orders it.
     */
    private static final long ORDER_FLIP = (1L << 41) | (1L << 19);

    private SectionOrder() {}

    /** Sorts section keys in place into write order. */
    public static void sort(long[] keys) {
        Objects.requireNonNull(keys);
        for (int i = 0; i < keys.length; i++) keys[i] ^= ORDER_FLIP;
        Arrays.sort(keys);
        for (int i = 0; i < keys.length; i++) keys[i] ^= ORDER_FLIP;
    }

    /** Number of sections {@code box} touches, saturating at {@link Long#MAX_VALUE}. */
    public static long sectionCount(Box box) {
        long nx = (box.max().x() >> 4) - (box.min().x() >> 4) + 1L;
        long ny = (box.max().y() >> 4) - (box.min().y() >> 4) + 1L;
        long nz = (box.max().z() >> 4) - (box.min().z() >> 4) + 1L;
        try {
            return Math.multiplyExact(Math.multiplyExact(nx, ny), nz);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
