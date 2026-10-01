package dev.sculptory.core.mask;

import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.edit.ComputeContext;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.world.WorldReader;
import java.util.Objects;

/**
 * The world as it was before a bulk edit, for mask rules that read neighbours: a section's pre-write snapshot
 * ({@link ComputeContext#source}) where the job took one, the live world elsewhere (or air where there is none).
 * Built per compute call; not thread-safe beyond what its sources are.
 */
public final class SnapshotReader implements WorldReader {
    private final ComputeContext ctx;
    /** The live world, or {@code null} when the runner offers none. */
    private final WorldReader live;

    public SnapshotReader(ComputeContext ctx, WorldReader live) {
        this.ctx = Objects.requireNonNull(ctx);
        this.live = live;
    }

    @Override
    public StateSpace states() {
        return ctx.states();
    }

    @Override
    public int bottomY() {
        return live == null ? Integer.MIN_VALUE : live.bottomY();
    }

    @Override
    public int topYExclusive() {
        return live == null ? Integer.MAX_VALUE : live.topYExclusive();
    }

    @Override
    public boolean isLoaded(int cx, int cz) {
        return live == null || live.isLoaded(cx, cz);
    }

    @Override
    public int get(int x, int y, int z) {
        SectionBuffer snapshot = snapshot(x, y, z);
        if (snapshot != null) {
            int state = snapshot.get(SectionBuffer.index(x & 15, y & 15, z & 15));
            return state < 0 ? ctx.states().air() : state;
        }
        return live == null ? ctx.states().air() : live.get(x, y, z);
    }

    @Override
    public BlockEntityData tile(int x, int y, int z) {
        SectionBuffer snapshot = snapshot(x, y, z);
        if (snapshot != null) return snapshot.tile(SectionBuffer.index(x & 15, y & 15, z & 15));
        return live == null ? null : live.tile(x, y, z);
    }

    @Override
    public void copySection(int sx, int sy, int sz, SectionBuffer into) {
        SectionBuffer snapshot = ctx.source(BlockBuffer.key(sx, sy, sz));
        if (snapshot == null) {
            if (live != null) {
                live.copySection(sx, sy, sz, into);
            } else {
                into.clearAll();
                for (int i = 0; i < SectionBuffer.SIZE; i++) into.set(i, ctx.states().air());
            }
            return;
        }
        into.clearAll();
        for (int i = 0; i < SectionBuffer.SIZE; i++) {
            int state = snapshot.get(i);
            into.set(i, state < 0 ? ctx.states().air() : state);
        }
        snapshot.forEachTile(into::setTile);
    }

    private SectionBuffer snapshot(int x, int y, int z) {
        return ctx.source(BlockBuffer.keyOfBlock(x, y, z));
    }
}
