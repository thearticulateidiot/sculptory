package dev.sculptory.fabric.client.editor.tutorial;

import dev.sculptory.fabric.client.editor.input.KeyAction;
import java.util.Objects;

/**
 * A value filled into a step's text when the step shows: a key as the player has it bound now, or a translated label.
 * Lesson texts never name a key themselves, so a rebound key reads right.
 */
public sealed interface Arg {
    /** The first key of an editor action as bound now ("Ctrl+K"); unbound reads as "(no key)". */
    record Key(KeyAction action) implements Arg {
        public Key {
            Objects.requireNonNull(action);
        }
    }

    /** One of the player's own Minecraft keys the editor uses (the editor toggle, movement). */
    record Vanilla(VanillaKey key) implements Arg {
        public Vanilla {
            Objects.requireNonNull(key);
        }
    }

    /** A translated text, such as the name of a mouse gesture ("Right-drag") or of Esc. */
    record Text(String key) implements Arg {
        public Text {
            Objects.requireNonNull(key);
        }
    }

    /** The player's Minecraft keys a lesson names. */
    enum VanillaKey {
        /** The key that enters and leaves the editor (B by default). */
        TOGGLE,
        /** The movement keys ("W A S D"). */
        MOVE,
        /** Jump: flies up in Creative. */
        UP,
        /** Sneak: flies down in Creative. */
        DOWN,
        /** The builder-mode ring key (G by default; a vanilla key binding, so it is named as bound). */
        RING
    }

    static Arg key(KeyAction action) {
        return new Key(action);
    }

    static Arg vanilla(VanillaKey key) {
        return new Vanilla(key);
    }

    static Arg text(String key) {
        return new Text(key);
    }
}
