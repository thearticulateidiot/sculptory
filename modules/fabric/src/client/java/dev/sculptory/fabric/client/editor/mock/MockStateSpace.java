package dev.sculptory.fabric.client.editor.mock;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.transform.Mirror;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The state space of the in-game mock session: every block description gets a handle the first
 * time it is resolved, so any block the picker offers can be used. Handle 0 is {@code minecraft:air}.
 * Rotation and mirroring are identities and there are no tags; it only has to carry block choices
 * into {@code OpSpec}s, which the mock never applies to a world. Client thread only.
 */
public final class MockStateSpace implements StateSpace {
    private static final BlockDescriptor AIR = BlockDescriptor.of(new NamespacedId("minecraft:air"));

    private final List<BlockDescriptor> states = new ArrayList<>();
    private final Map<BlockDescriptor, Integer> handles = new HashMap<>();

    public MockStateSpace() {
        intern(AIR);
    }

    private int intern(BlockDescriptor descriptor) {
        Integer existing = handles.get(descriptor);
        if (existing != null) {
            return existing;
        }
        int handle = states.size();
        states.add(descriptor);
        handles.put(descriptor, handle);
        return handle;
    }

    @Override
    public int size() {
        return states.size();
    }

    @Override
    public int air() {
        return 0;
    }

    @Override
    public int flags(int h) {
        return describe(h).block().equals(AIR.block()) ? StateFlags.AIR | StateFlags.REPLACEABLE : 0;
    }

    @Override
    public String format(int h) {
        return describe(h).format();
    }

    @Override
    public int parse(String spec) {
        try {
            return intern(BlockDescriptor.parse(spec));
        } catch (IllegalArgumentException malformed) {
            return -1;
        }
    }

    @Override
    public BlockDescriptor describe(int h) {
        return states.get(h);
    }

    @Override
    public int resolve(BlockDescriptor d) {
        return intern(Objects.requireNonNull(d));
    }

    @Override
    public NamespacedId blockId(int h) {
        return describe(h).block();
    }

    @Override
    public boolean inTag(int h, NamespacedId tag) {
        describe(h);
        return false;
    }

    @Override
    public int rotate(int h, int clockwiseQuarterTurns) {
        describe(h);
        return h;
    }

    @Override
    public int mirror(int h, Mirror m) {
        describe(h);
        return h;
    }

    @Override
    public int withWaterlogged(int h, boolean on) {
        describe(h);
        return h;
    }

    @Override
    public int fluidSource(int h) {
        describe(h);
        return -1;
    }
}
