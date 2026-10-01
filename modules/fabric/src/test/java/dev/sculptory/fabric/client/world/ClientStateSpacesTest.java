package dev.sculptory.fabric.client.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeStateSpace;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ClientStateSpacesTest {
    /** Hands out a new space per build, or fails while {@code failing} is set. */
    private static final class Factory implements Supplier<StateSpace> {
        final Deque<StateSpace> built = new ArrayDeque<>();
        boolean failing;
        boolean returnNull;

        @Override
        public StateSpace get() {
            if (failing) throw new IllegalStateException("registries not ready");
            if (returnNull) return null;
            StateSpace space = new FakeStateSpace();
            built.push(space);
            return space;
        }
    }

    private final Factory factory = new Factory();
    private final ClientStateSpaces spaces = new ClientStateSpaces(factory);

    @Test
    void nothingBeforeTheFirstJoin() {
        assertNull(spaces.get());
        assertFalse(spaces.joined());
        spaces.onTagsReloaded();
        assertNull(spaces.get(), "tags arrive during configuration, before the join builds the space");
        assertTrue(factory.built.isEmpty());
    }

    @Test
    void everyJoinBuildsANewSpaceBecauseRawIdsMayHaveBeenRemapped() {
        spaces.onJoin();
        StateSpace first = spaces.get();
        assertSame(factory.built.peek(), first);
        spaces.onDisconnect();
        assertNull(spaces.get());
        assertFalse(spaces.joined());
        spaces.onJoin();
        assertNotSame(first, spaces.get());
        spaces.onJoin();
        assertTrue(spaces.joined());
        assertSame(factory.built.peek(), spaces.get(), "a rejoin after reconfiguration rebuilds too");
    }

    @Test
    void aTagReloadRebuildsWhileInAWorld() {
        spaces.onJoin();
        StateSpace before = spaces.get();
        spaces.onTagsReloaded();
        assertNotSame(before, spaces.get());
        assertSame(factory.built.peek(), spaces.get());
    }

    @Test
    void aFailedJoinBuildLeavesNoSpaceRatherThanAStaleOne() {
        spaces.onJoin();
        factory.failing = true;
        spaces.onJoin();
        assertNull(spaces.get(), "the old space's handles may be wrong for the new registries");
        assertTrue(spaces.joined());
        factory.failing = false;
        factory.returnNull = true;
        spaces.onJoin();
        assertNull(spaces.get());
    }

    @Test
    void aFailedTagRebuildKeepsTheCurrentSpace() {
        spaces.onJoin();
        StateSpace current = spaces.get();
        factory.failing = true;
        spaces.onTagsReloaded();
        assertSame(current, spaces.get(), "a tag reload does not change raw ids, so the old space still works");
        factory.failing = false;
        spaces.onDisconnect();
        spaces.onTagsReloaded();
        assertNull(spaces.get());
        spaces.onJoin();
        assertNotNull(spaces.get());
    }
}
