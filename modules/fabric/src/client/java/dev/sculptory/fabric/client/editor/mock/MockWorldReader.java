package dev.sculptory.fabric.client.editor.mock;

import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.world.WorldReader;
import java.util.Objects;

/** An empty, fully loaded world of air, for the mock session (which never applies edits). */
public final class MockWorldReader implements WorldReader {
    private final StateSpace states;

    public MockWorldReader(StateSpace states) {
        this.states = Objects.requireNonNull(states);
    }

    @Override
    public StateSpace states() {
        return states;
    }

    @Override
    public int bottomY() {
        return -64;
    }

    @Override
    public int topYExclusive() {
        return 320;
    }

    @Override
    public boolean isLoaded(int cx, int cz) {
        return true;
    }

    @Override
    public int get(int x, int y, int z) {
        return states.air();
    }

    @Override
    public BlockEntityData tile(int x, int y, int z) {
        return null;
    }

    @Override
    public void copySection(int sx, int sy, int sz, SectionBuffer into) {
        into.clearAll();
        for (int i = 0; i < SectionBuffer.SIZE; i++) {
            into.set(i, states.air());
        }
    }
}
