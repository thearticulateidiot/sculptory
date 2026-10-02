package dev.sculptory.server.engine.impl;

import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.edit.ComputeContext;
import dev.sculptory.core.edit.EditProgram;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.world.WorldReader;
import java.util.Objects;

/**
 * Wraps an undo, redo or scatter-commit program to sum the conflicts it reports through
 * {@link ComputeContext#conflicts} (from {@code compute} and from {@code mayReplace}), which the executor does not count
 * ({@code JobResult.skippedConflicts} is always 0 there). The edit service puts the sum into the {@code JobFinished} it
 * reports: conflicting cells for undo and redo, skipped or cut-short placements for a scatter commit.
 */
final class ConflictCountingProgram implements EditProgram {
    private final EditProgram delegate;
    private long conflicts;
    /** The runner's context and its counting wrapper, made once per runner context. */
    private ComputeContext outer;
    private ComputeContext counting;

    ConflictCountingProgram(EditProgram delegate) {
        this(delegate, 0);
    }

    /** Counting from {@code alreadySkipped} (a scatter commit's placements left out before it ran: masked). */
    ConflictCountingProgram(EditProgram delegate, long alreadySkipped) {
        this.delegate = Objects.requireNonNull(delegate);
        if (alreadySkipped < 0) throw new IllegalArgumentException("Negative count");
        this.conflicts = alreadySkipped;
    }

    /** Conflicts reported so far. */
    long conflicts() {
        return conflicts;
    }

    @Override
    public String label() {
        return delegate.label();
    }

    @Override
    public Box bounds() {
        return delegate.bounds();
    }

    @Override
    public long estimatedCells() {
        return delegate.estimatedCells();
    }

    @Override
    public long[] sourceSections() {
        return delegate.sourceSections();
    }

    @Override
    public long[] sectionOrder() {
        return delegate.sectionOrder();
    }

    @Override
    public void compute(long key, SectionBuffer before, SectionBuffer out, ComputeContext ctx) {
        delegate.compute(key, before, out, counting(ctx));
    }

    @Override
    public long[] readColumns(long key) {
        return delegate.readColumns(key);
    }

    @Override
    public boolean relightsAfter() {
        return delegate.relightsAfter();
    }

    @Override
    public boolean mayReplace(long key, int index, int liveState, ComputeContext ctx) {
        return delegate.mayReplace(key, index, liveState, counting(ctx));
    }

    @Override
    public boolean mayReplace(long key, int index, int liveState, BlockEntityData liveTile, ComputeContext ctx) {
        return delegate.mayReplace(key, index, liveState, liveTile, counting(ctx));
    }

    private ComputeContext counting(ComputeContext ctx) {
        if (ctx != outer) {
            outer = ctx;
            counting = new ComputeContext() {
                @Override
                public StateSpace states() {
                    return ctx.states();
                }

                @Override
                public long seed() {
                    return ctx.seed();
                }

                @Override
                public SectionBuffer source(long sourceKey) {
                    return ctx.source(sourceKey);
                }

                @Override
                public WorldReader world() {
                    return ctx.world();
                }

                @Override
                public boolean mayWrite(int x, int z) {
                    return ctx.mayWrite(x, z);
                }

                @Override
                public void conflicts(int n) {
                    if (n > 0) conflicts += n;
                    ctx.conflicts(n);
                }
            };
        }
        return counting;
    }
}
