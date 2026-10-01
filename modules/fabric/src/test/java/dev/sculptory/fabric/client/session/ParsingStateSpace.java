package dev.sculptory.fabric.client.session;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.transform.Mirror;
import java.util.function.ToIntFunction;

/** A state space that parses block-state strings its own way (a client missing a mod, or failing) and else delegates. */
record ParsingStateSpace(StateSpace states, ToIntFunction<String> parser) implements StateSpace {
    @Override
    public int size() {
        return states.size();
    }

    @Override
    public int air() {
        return states.air();
    }

    @Override
    public int flags(int h) {
        return states.flags(h);
    }

    @Override
    public String format(int h) {
        return states.format(h);
    }

    @Override
    public int parse(String spec) {
        return parser.applyAsInt(spec);
    }

    @Override
    public BlockDescriptor describe(int h) {
        return states.describe(h);
    }

    @Override
    public int resolve(BlockDescriptor d) {
        return states.resolve(d);
    }

    @Override
    public NamespacedId blockId(int h) {
        return states.blockId(h);
    }

    @Override
    public boolean inTag(int h, NamespacedId tag) {
        return states.inTag(h, tag);
    }

    @Override
    public int rotate(int h, int clockwiseQuarterTurns) {
        return states.rotate(h, clockwiseQuarterTurns);
    }

    @Override
    public int mirror(int h, Mirror m) {
        return states.mirror(h, m);
    }

    @Override
    public int flip(int h) {
        return states.flip(h);
    }

    @Override
    public int flipKind(int h) {
        return states.flipKind(h);
    }

    @Override
    public int withWaterlogged(int h, boolean on) {
        return states.withWaterlogged(h, on);
    }

    @Override
    public int fluidSource(int h) {
        return states.fluidSource(h);
    }
}
