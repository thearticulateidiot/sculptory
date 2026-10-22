package dev.sculptory.neoforge.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import net.neoforged.neoforge.client.settings.IKeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.junit.jupiter.api.Test;

/**
 * The movement keys' context under the editor screen: active in game and under the editor screen, inactive under any
 * other screen, and conflicting exactly as the context it wraps (vanilla's {@code IN_GAME}).
 */
class EditorMovementContextTest {
    /** Stands in for {@code IN_GAME} (whose {@code isActive} reads the running game): active when no screen is open. */
    private static final class InGame implements IKeyConflictContext {
        boolean noScreen;

        @Override
        public boolean isActive() {
            return noScreen;
        }

        @Override
        public boolean conflicts(IKeyConflictContext other) {
            return KeyConflictContext.IN_GAME.conflicts(other);
        }
    }

    @Test
    void activeInGameAndUnderTheEditorScreenOnly() {
        InGame inGame = new InGame();
        AtomicBoolean editorOpen = new AtomicBoolean();
        EditorMovementContext context = new EditorMovementContext(inGame, editorOpen::get);

        inGame.noScreen = true;
        assertTrue(context.isActive(), "no screen open");

        inGame.noScreen = false;
        editorOpen.set(true);
        assertTrue(context.isActive(), "the editor screen is open");

        editorOpen.set(false);
        assertFalse(context.isActive(), "another screen (chat, inventory, a menu) is open");
    }

    @Test
    void conflictsAsTheWrappedContext() {
        EditorMovementContext context = new EditorMovementContext(KeyConflictContext.IN_GAME, () -> false);
        EditorMovementContext other = new EditorMovementContext(KeyConflictContext.IN_GAME, () -> false);

        // Other in-game keys (chat, attack) and the other wrapped movement keys share the in-game context.
        assertTrue(context.conflicts(KeyConflictContext.IN_GAME));
        assertTrue(context.conflicts(other));
        assertTrue(context.conflicts(KeyConflictContext.UNIVERSAL) || KeyConflictContext.UNIVERSAL.conflicts(context));
        // GUI keys never share a key with in-game ones.
        assertFalse(context.conflicts(KeyConflictContext.GUI));
    }
}
