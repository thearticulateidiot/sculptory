package dev.sculptory.core.edit;

import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.SectionBuffer;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.List;
import java.util.Objects;

/**
 * A symmetric op as one program: the copies' programs,
 * the original first, applied to each section in order with union semantics. A section's cells are claimed by the
 * first copy that covers them ({@link ClaimingProgram}), so where copies overlap the earlier one wins exactly. The
 * bounds are the union of the copies' bounds, the section lists their sorted unions, the estimate their sum, so the
 * executor locks, loads, protects and records every copy as one job with one history entry.
 *
 * <p>The wrapped programs never override {@code mayReplace}, so the default (every write allowed) stands here too.
 */
final class SymmetricProgram implements EditProgram {
    private final ClaimingProgram[] copies;
    private final LongOpenHashSet[] sections;
    private final Box bounds;
    private final long estimatedCells;
    private final long[] sourceSections;
    private final long[] order;

    SymmetricProgram(List<? extends ClaimingProgram> programs) {
        if (programs.size() < 2) throw new IllegalArgumentException("A symmetric op has at least two copies");
        this.copies = programs.toArray(ClaimingProgram[]::new);
        this.sections = new LongOpenHashSet[copies.length];
        Box union = null;
        long cells = 0;
        LongOpenHashSet writes = new LongOpenHashSet();
        LongOpenHashSet reads = new LongOpenHashSet();
        for (int i = 0; i < copies.length; i++) {
            ClaimingProgram copy = Objects.requireNonNull(copies[i]);
            long[] own = copy.sectionOrder();
            sections[i] = new LongOpenHashSet(own);
            for (long key : own) writes.add(key);
            for (long key : copy.sourceSections()) reads.add(key);
            union = union == null ? copy.bounds() : CopySupport.union(union, copy.bounds());
            long sum = cells + copy.estimatedCells();
            cells = sum < 0 ? Long.MAX_VALUE : sum;
        }
        this.bounds = union;
        this.estimatedCells = cells;
        this.sourceSections = CopySupport.ordered(reads);
        this.order = CopySupport.ordered(writes);
    }

    @Override
    public String label() {
        return copies[0].label();
    }

    @Override
    public Box bounds() {
        return bounds;
    }

    /** The copies' estimates added up: a cell two copies cover is counted twice. */
    @Override
    public long estimatedCells() {
        return estimatedCells;
    }

    @Override
    public long[] sourceSections() {
        return sourceSections.clone();
    }

    @Override
    public long[] sectionOrder() {
        return order.clone();
    }

    @Override
    public long[] readColumns(long key) {
        long[] columns = NO_COLUMNS;
        for (int i = 0; i < copies.length; i++) {
            if (!sections[i].contains(key)) continue;
            long[] own = copies[i].readColumns(key);
            if (own.length == 0) continue;
            if (columns.length == 0) {
                columns = own;
                continue;
            }
            LongOpenHashSet merged = new LongOpenHashSet(columns);
            for (long column : own) merged.add(column);
            columns = merged.toLongArray();
        }
        return columns;
    }

    /** Every copy is the same op, so they relight alike: the original says. */
    @Override
    public boolean relightsAfter() {
        return copies[0].relightsAfter();
    }

    @Override
    public void compute(long key, SectionBuffer before, SectionBuffer out, ComputeContext ctx) {
        long[] claimed = new long[ClaimingProgram.CLAIM_WORDS];
        for (int i = 0; i < copies.length; i++) {
            if (sections[i].contains(key)) copies[i].compute(key, before, out, ctx, claimed);
        }
    }

    /** The copies' programs, the original first (tests). */
    List<ClaimingProgram> copies() {
        return List.of(copies);
    }
}
