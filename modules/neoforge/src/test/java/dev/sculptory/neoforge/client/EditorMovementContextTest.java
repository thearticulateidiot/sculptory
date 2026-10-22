package dev.sculptory.neoforge.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.settings.IKeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyModifier;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

/**
 * The movement keys' context under the editor screen: active in game and under the editor screen, inactive under any
 * other screen, conflicting exactly as the context it wraps (vanilla's {@code IN_GAME}), passing NeoForge's modifier
 * check that {@code KeyMapping.isDown} and the key lookup make, and put on a key only once.
 */
class EditorMovementContextTest {
    /**
     * Stands in for {@code IN_GAME}, whose {@code isActive} reads the running game: active when no screen is open,
     * conflicting as {@code IN_GAME} does.
     */
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
        // Against the other contexts it answers as IN_GAME itself does.
        assertEquals(KeyConflictContext.IN_GAME.conflicts(KeyConflictContext.UNIVERSAL),
                context.conflicts(KeyConflictContext.UNIVERSAL));
        assertFalse(context.conflicts(KeyConflictContext.GUI));
    }

    /**
     * {@code KeyMapping.isDown} and {@code KeyMappingLookup} also ask the key's modifier ({@code NONE} for the movement
     * keys) whether it is active in the key's context. {@code NONE} is active for any context that conflicts with
     * {@code IN_GAME} without looking at the keyboard; anything else would make it read the held modifier keys, and
     * W would stop while Shift (sneak) is held.
     */
    @Test
    void noModifierIsActiveInTheWrappedContext() {
        InGame inGame = new InGame();
        EditorMovementContext context = new EditorMovementContext(inGame, () -> true);
        assertTrue(KeyModifier.NONE.isActive(context));
    }

    @Test
    void installWrapsEachKeyOnceAndAgainAfterAnotherModReplacedIt() {
        KeyMapping key = new KeyMapping("key.sculptory.test.forward", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_W,
                "key.categories.sculptory.test");
        key.setKeyConflictContext(KeyConflictContext.IN_GAME);

        List<String> first = EditorMovementContext.install(List.of(key), () -> false);
        IKeyConflictContext wrapper = key.getKeyConflictContext();
        assertInstanceOf(EditorMovementContext.class, wrapper);
        assertEquals(List.of("key.sculptory.test.forward (" + KeyConflictContext.class.getName() + ".IN_GAME)"),
                first);

        assertEquals(List.of(), EditorMovementContext.install(List.of(key), () -> false), "already wrapped");
        assertSame(wrapper, key.getKeyConflictContext(), "never a wrapper in a wrapper");

        // Another mod sets a context of its own: the next install wraps that one and names it.
        InGame theirs = new InGame();
        key.setKeyConflictContext(theirs);
        List<String> again = EditorMovementContext.install(List.of(key), () -> true);
        assertEquals(List.of("key.sculptory.test.forward (" + InGame.class.getName() + ")"), again);
        assertInstanceOf(EditorMovementContext.class, key.getKeyConflictContext());
        assertTrue(key.getKeyConflictContext().isActive(), "their context, plus the editor screen");
    }
}
