package dev.sculptory.fabric.client.editor.input;

import java.util.Objects;

/**
 * Keeps the player's own movement keys (forward, back, left, right, jump, sneak, sprint) working
 * while the editor screen is open. Vanilla only presses key bindings when no screen is open, so the
 * editor presses them itself; releases are left to vanilla, which releases bindings for any key a
 * screen doesn't consume.
 */
public final class MovementForwarder implements InputRouter.Movement {
    /** The player's movement bindings. */
    public interface Keys {
        /** True if the key is bound to one of the movement bindings. */
        boolean matches(int key, int scanCode);

        /** {@code KeyBinding.setKeyPressed(InputUtil.fromKeyCode(key, scanCode), true)}. */
        void press(int key, int scanCode);
    }

    private final Keys keys;

    public MovementForwarder(Keys keys) {
        this.keys = Objects.requireNonNull(keys);
    }

    @Override
    public boolean isMovementKey(int key, int scanCode) {
        return keys.matches(key, scanCode);
    }

    @Override
    public void press(int key, int scanCode) {
        if (keys.matches(key, scanCode)) {
            keys.press(key, scanCode);
        }
    }
}
