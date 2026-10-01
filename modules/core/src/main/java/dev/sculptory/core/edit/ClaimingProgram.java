package dev.sculptory.core.edit;

import dev.sculptory.core.buffer.SectionBuffer;

/**
 * A program that can share a section with the other copies of a symmetric op ({@link SymmetricProgram}): it skips
 * the cells an earlier copy covered and claims the cells it covers itself and its Into filter allows, whether or not the state changes (a region op covers a region cell its
 * mask accepts, a paste a present source cell, a move the cells it lands on and vacates, a stack the cells its
 * copies land on; a landing cell the filter leaves out is not claimed).
 */
interface ClaimingProgram extends EditProgram {
    /** Bits per claimed word: a section's 4096 cells in 64 longs, bit {@code SectionBuffer.index}. */
    int CLAIM_WORDS = SectionBuffer.SIZE / 64;

    /**
     * {@link #compute(long, SectionBuffer, SectionBuffer, ComputeContext)} skipping the cells set in {@code claimed}
     * and setting the cells it covers; {@code null} means no sharing (the plain compute).
     */
    void compute(long key, SectionBuffer before, SectionBuffer out, ComputeContext ctx, long[] claimed);

    /** Whether cell {@code i} of a section is claimed ({@code claimed} may be {@code null}: never). */
    static boolean claimed(long[] claimed, int i) {
        return claimed != null && (claimed[i >>> 6] & (1L << i)) != 0;
    }

    /** Claims cell {@code i} (nothing without a mask). */
    static void claim(long[] claimed, int i) {
        if (claimed != null) claimed[i >>> 6] |= 1L << i;
    }

    /** The 16 claimed bits of row {@code r} ({@code (ly << 4) | lz}, bit lx), 0 without a mask. */
    static int claimedRow(long[] claimed, int r) {
        return claimed == null ? 0 : (int) (claimed[r >> 2] >>> ((r & 3) << 4)) & 0xFFFF;
    }

    /** Claims the cells of row {@code r} whose bits are set in {@code bits}. */
    static void claimRow(long[] claimed, int r, int bits) {
        if (claimed != null) claimed[r >> 2] |= (long) (bits & 0xFFFF) << ((r & 3) << 4);
    }
}
