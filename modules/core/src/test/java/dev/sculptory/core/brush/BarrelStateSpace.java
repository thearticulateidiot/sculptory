package dev.sculptory.core.brush;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.transform.Mirror;

/**
 * {@link FakeStateSpace} with {@code minecraft:chest} flagged as a full terrain-solid cube with a block
 * entity, like a barrel or furnace. Handles are unchanged.
 */
final class BarrelStateSpace implements StateSpace {
    private final FakeStateSpace fake;
    private final NamespacedId chest = new NamespacedId("minecraft:chest");

    BarrelStateSpace(FakeStateSpace fake) {
        this.fake = fake;
    }

    @Override
    public int flags(int h) {
        int flags = fake.flags(h);
        return fake.blockId(h).equals(chest) ? flags | StateFlags.TERRAIN_SOLID : flags;
    }

    @Override
    public int size() {
        return fake.size();
    }

    @Override
    public int air() {
        return fake.air();
    }

    @Override
    public String format(int h) {
        return fake.format(h);
    }

    @Override
    public int parse(String spec) {
        return fake.parse(spec);
    }

    @Override
    public BlockDescriptor describe(int h) {
        return fake.describe(h);
    }

    @Override
    public int resolve(BlockDescriptor d) {
        return fake.resolve(d);
    }

    @Override
    public NamespacedId blockId(int h) {
        return fake.blockId(h);
    }

    @Override
    public boolean inTag(int h, NamespacedId tag) {
        return fake.inTag(h, tag);
    }

    @Override
    public int rotate(int h, int clockwiseQuarterTurns) {
        return fake.rotate(h, clockwiseQuarterTurns);
    }

    @Override
    public int mirror(int h, Mirror m) {
        return fake.mirror(h, m);
    }

    @Override
    public int withWaterlogged(int h, boolean on) {
        return fake.withWaterlogged(h, on);
    }

    @Override
    public int fluidSource(int h) {
        return fake.fluidSource(h);
    }
}
