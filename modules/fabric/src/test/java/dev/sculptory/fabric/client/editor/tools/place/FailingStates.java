package dev.sculptory.fabric.client.editor.tools.place;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.transform.Mirror;
import java.util.Objects;

/** A state space whose rotate, mirror and flip throw while {@link #failing}: a ghost bake then fails (tests). */
public final class FailingStates implements StateSpace {
    private final StateSpace delegate;
    public boolean failing;

    public FailingStates(StateSpace delegate) {
        this.delegate = Objects.requireNonNull(delegate);
    }

    private void check() {
        if (failing) throw new IllegalStateException("state transform failure (test)");
    }

    @Override
    public int size() {
        return delegate.size();
    }

    @Override
    public int air() {
        return delegate.air();
    }

    @Override
    public int flags(int h) {
        return delegate.flags(h);
    }

    @Override
    public String format(int h) {
        return delegate.format(h);
    }

    @Override
    public int parse(String spec) {
        return delegate.parse(spec);
    }

    @Override
    public BlockDescriptor describe(int h) {
        return delegate.describe(h);
    }

    @Override
    public int resolve(BlockDescriptor d) {
        return delegate.resolve(d);
    }

    @Override
    public NamespacedId blockId(int h) {
        return delegate.blockId(h);
    }

    @Override
    public boolean inTag(int h, NamespacedId tag) {
        return delegate.inTag(h, tag);
    }

    @Override
    public int rotate(int h, int clockwiseQuarterTurns) {
        check();
        return delegate.rotate(h, clockwiseQuarterTurns);
    }

    @Override
    public int mirror(int h, Mirror m) {
        check();
        return delegate.mirror(h, m);
    }

    @Override
    public int flip(int h) {
        check();
        return delegate.flip(h);
    }

    @Override
    public int flipKind(int h) {
        return delegate.flipKind(h);
    }

    @Override
    public int withWaterlogged(int h, boolean on) {
        return delegate.withWaterlogged(h, on);
    }

    @Override
    public int fluidSource(int h) {
        return delegate.fluidSource(h);
    }
}
