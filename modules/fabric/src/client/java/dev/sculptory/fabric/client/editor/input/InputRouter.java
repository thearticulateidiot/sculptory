package dev.sculptory.fabric.client.editor.input;

import dev.sculptory.fabric.client.editor.tool.EditorAction;
import dev.sculptory.fabric.client.editor.tool.PointerEvent;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Objects;
import java.util.Optional;
import org.lwjgl.glfw.GLFW;

/**
 * Decides who gets each input event while the editor screen is open. Priority, highest first:
 * <ol>
 *   <li>an open popup (dropdown, block picker, confirmation, help sheet), or a control listening for a key to
 *       bind (the Keys window): every press, scroll and key goes there and to no tool, and a held key it took is
 *       not a chord press while it repeats;</li>
 *   <li>an active capture: the layer that received a button press gets its drags and release;
 *       a tool holding pointer capture also gets new presses;</li>
 *   <li>a focused text field or control (typing never triggers shortcuts);</li>
 *   <li>keymap chords;</li>
 *   <li>a window, the top bar or the palette under the pointer;</li>
 *   <li>right-button look;</li>
 *   <li>the active tool.</li>
 * </ol>
 * Esc runs the ladder: close a popup, unfocus text (or cancel a window drag), end a mouse look,
 * cancel the tool's drag, stroke or preview, and finally leave the editor. The player's own
 * movement keys are passed through to vanilla; key releases always return false so vanilla
 * releases its bindings.
 *
 * <p>Key repeat: vanilla 1.21.1 hands both a GLFW press and a GLFW repeat (action 2) to
 * {@code Screen.keyPressed}, with nothing to tell them apart (checked with javap on
 * {@code Keyboard.onKey}). The router therefore remembers which keys are down: a press of a key
 * that is already down is a repeat, and chords whose action {@link KeyAction#ignoresRepeat}
 * (undo, redo) swallow it.
 *
 * <p>Coordinates are screen coordinates (GUI-scaled pixels) everywhere here. The UI maps them to
 * its own units for the editor UI size; tools, world picking and the eyedropper get them unchanged.
 *
 * <p>Pure Java: every collaborator is an interface, so the routing is unit-tested with fakes.
 */
public final class InputRouter {
    /** The editor UI: windows, popups, top bar, palette and help sheet. Takes screen coordinates. */
    public interface Ui {
        /** A popup (dropdown list, block picker, confirmation) or the help sheet is open. */
        boolean hasPopup();

        /** A control has keyboard focus. */
        boolean hasKeyboardFocus();

        /** A control is listening for a key or button to bind: every press, scroll and key goes to it and nowhere else. */
        boolean capturesInput();

        void clearFocus();

        boolean isOverUi(double x, double y);

        boolean mouseDown(double x, double y, int button, int modifiers);

        boolean mouseDragged(double x, double y, int button);

        boolean mouseUp(double x, double y, int button);

        boolean mouseScrolled(double x, double y, double amount, int modifiers);

        void mouseMoved(double x, double y);

        /** Esc here closes a popup, cancels a window drag or clears focus, in that order. */
        boolean keyPressed(int key, int scanCode, int modifiers);

        boolean charTyped(char chr, int modifiers);
    }

    /** The active tool, reached through the editor (which adds the world cursor). */
    public interface Tools {
        boolean pointer(PointerEvent.Kind kind, int button, double x, double y, int modifiers);

        boolean scroll(double amount, int modifiers);

        /** Whether the active tool takes a plain Scroll now (the Tinker tool over a block): it goes there, not to fly speed. */
        default boolean takesScroll(int modifiers) {
            return false;
        }

        boolean action(EditorAction action, int modifiers);

        boolean hasPointerCapture();
    }

    /** Editor-level commands. */
    public interface Commands {
        /** A keymap action that the tool did not take (or that is always editor-level). */
        void run(KeyAction action, int modifiers);

        void flySpeed(double amount, int modifiers);

        /** Middle-click in the world when the tool doesn't take {@link EditorAction#EYEDROPPER}. */
        void eyedropper(double x, double y);

        /** The last rung of the Esc ladder. */
        void exitEditor();

        /**
         * The editor-toggle key, or the player's chat, command or inventory key (which suspend the
         * editor). Returns true if the key was one of those.
         */
        boolean systemKey(int key, int scanCode);
    }

    /** Right-button look. */
    public interface Look {
        boolean isLooking();

        void begin();

        void end();
    }

    /** The player's own movement keys. */
    public interface Movement {
        boolean isMovementKey(int key, int scanCode);

        void press(int key, int scanCode);
    }

    private enum Owner { NONE, UI, LOOK, TOOL, SWALLOWED }

    private final Ui ui;
    private final Tools tools;
    private final Commands commands;
    private final Look look;
    private final Movement movement;
    private final EditorKeymap keymap;
    private final Owner[] owners = new Owner[8];
    /** GLFW key codes pressed and not yet released while the editor had input. */
    private final BitSet heldKeys = new BitSet();
    /** The key a listening control took last, until its release: its repeats are not chord presses. */
    private int capturedKey = -1;

    public InputRouter(Ui ui, Tools tools, Commands commands, Look look, Movement movement, EditorKeymap keymap) {
        this.ui = Objects.requireNonNull(ui);
        this.tools = Objects.requireNonNull(tools);
        this.commands = Objects.requireNonNull(commands);
        this.look = Objects.requireNonNull(look);
        this.movement = Objects.requireNonNull(movement);
        this.keymap = Objects.requireNonNull(keymap);
        Arrays.fill(owners, Owner.NONE);
    }

    public EditorKeymap keymap() {
        return keymap;
    }

    /** Forgets pressed buttons and keys, e.g. when the editor closes or is suspended mid-drag. */
    public void reset() {
        if (look.isLooking()) {
            look.end();
        }
        Arrays.fill(owners, Owner.NONE);
        heldKeys.clear();
        capturedKey = -1;
    }

    /** True while a button pressed in the world is held (the tool is dragging). */
    public boolean isToolDragging() {
        for (Owner owner : owners) {
            if (owner == Owner.TOOL) {
                return true;
            }
        }
        return false;
    }

    // ---- Mouse ----

    public boolean mouseClicked(double x, double y, int button, int modifiers) {
        if (button < 0 || button >= owners.length) {
            return true;
        }
        if (ui.hasPopup() || ui.capturesInput()) {
            ui.mouseDown(x, y, button, modifiers);
            owners[button] = Owner.UI;
            return true;
        }
        if (tools.hasPointerCapture()) {
            tools.pointer(PointerEvent.Kind.PRESS, button, x, y, modifiers);
            owners[button] = Owner.TOOL;
            return true;
        }
        if (ui.isOverUi(x, y)) {
            ui.mouseDown(x, y, button, modifiers);
            owners[button] = Owner.UI;
            return true;
        }
        ui.clearFocus();
        Optional<KeyAction> chord = keymap.match(KeyChord.mouse(button, modifiers));
        if (chord.isPresent() && chord.get() == KeyAction.EYEDROPPER) {
            if (!tools.action(EditorAction.EYEDROPPER, modifiers)) {
                commands.eyedropper(x, y);
            }
            owners[button] = Owner.SWALLOWED;
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            look.begin();
            owners[button] = Owner.LOOK;
            return true;
        }
        tools.pointer(PointerEvent.Kind.PRESS, button, x, y, modifiers);
        owners[button] = Owner.TOOL;
        return true;
    }

    public boolean mouseDragged(double x, double y, int button) {
        if (button < 0 || button >= owners.length) {
            return true;
        }
        switch (owners[button]) {
            case UI -> ui.mouseDragged(x, y, button);
            case TOOL -> tools.pointer(PointerEvent.Kind.DRAG, button, x, y, 0);
            default -> {
            }
        }
        return true;
    }

    public boolean mouseReleased(double x, double y, int button) {
        if (button < 0 || button >= owners.length) {
            return true;
        }
        Owner owner = owners[button];
        owners[button] = Owner.NONE;
        switch (owner) {
            case UI -> ui.mouseUp(x, y, button);
            case LOOK -> look.end();
            case TOOL -> tools.pointer(PointerEvent.Kind.RELEASE, button, x, y, 0);
            default -> {
            }
        }
        return true;
    }

    public void mouseMoved(double x, double y) {
        if (look.isLooking()) {
            return;
        }
        ui.mouseMoved(x, y);
        if (!isToolDragging() && !ui.hasPopup() && !ui.isOverUi(x, y)) {
            tools.pointer(PointerEvent.Kind.MOVE, -1, x, y, 0);
        }
    }

    /** Positive {@code amount} scrolls up (away from the player). */
    public boolean mouseScrolled(double x, double y, double amount, int modifiers) {
        if (amount == 0) {
            return true;
        }
        if (ui.hasPopup() || ui.capturesInput() || ui.isOverUi(x, y)) {
            ui.mouseScrolled(x, y, amount, modifiers);
            return true;
        }
        Optional<KeyAction> chord = keymap.match(KeyChord.scroll(modifiers));
        if (chord.isPresent() && chord.get() == KeyAction.FLY_SPEED && !tools.takesScroll(modifiers)) {
            commands.flySpeed(amount, modifiers);
        } else {
            tools.scroll(amount, modifiers);
        }
        return true;
    }

    // ---- Keyboard ----

    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == GLFW.GLFW_KEY_UNKNOWN) {
            // A key GLFW has no code for (a media or macro key): nothing here is bound to it, and a chord can't be
            // made of it (it once crashed the game mid-session).
            return false;
        }
        boolean repeat = key >= 0 && heldKeys.get(key);
        if (key >= 0) {
            heldKeys.set(key);
        }
        if (repeat && key == capturedKey) {
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (ui.capturesInput()) {
                capturedKey = key;
            }
            escape(scanCode, modifiers);
            return true;
        }
        if (ui.capturesInput()) {
            ui.keyPressed(key, scanCode, modifiers);
            capturedKey = key;
            return true;
        }
        if (ui.hasPopup()) {
            if (!ui.keyPressed(key, scanCode, modifiers) && !ui.hasKeyboardFocus()
                    && movement.isMovementKey(key, scanCode)) {
                movement.press(key, scanCode);
            }
            return true;
        }
        if (ui.hasKeyboardFocus() && ui.keyPressed(key, scanCode, modifiers)) {
            return true;
        }
        if (KeyNames.isModifierKey(key)) {
            if (movement.isMovementKey(key, scanCode)) {
                movement.press(key, scanCode);
            }
            return true;
        }
        Optional<KeyAction> chord = keymap.match(KeyChord.key(key, modifiers));
        if (chord.isPresent()) {
            if (!(repeat && chord.get().ignoresRepeat())) {
                dispatch(chord.get(), modifiers);
            }
            return true;
        }
        if (commands.systemKey(key, scanCode)) {
            return true;
        }
        if (movement.isMovementKey(key, scanCode)) {
            movement.press(key, scanCode);
            return true;
        }
        return false;
    }

    /** Always false, so vanilla releases the key's bindings (the forwarded movement keys included). */
    public boolean keyReleased(int key, int scanCode, int modifiers) {
        if (key >= 0) {
            heldKeys.clear(key);
        }
        if (key == capturedKey) {
            capturedKey = -1;
        }
        return false;
    }

    public boolean charTyped(char chr, int modifiers) {
        if (ui.hasKeyboardFocus()) {
            return ui.charTyped(chr, modifiers);
        }
        return true;
    }

    private void dispatch(KeyAction action, int modifiers) {
        Optional<EditorAction> toolAction = action.editorAction();
        if (toolAction.isPresent() && tools.action(toolAction.get(), modifiers)) {
            return;
        }
        commands.run(action, modifiers);
    }

    private void escape(int scanCode, int modifiers) {
        if (ui.keyPressed(GLFW.GLFW_KEY_ESCAPE, scanCode, modifiers)) {
            return;
        }
        if (look.isLooking()) {
            look.end();
            Arrays.fill(owners, Owner.NONE);
            return;
        }
        if (tools.action(EditorAction.CANCEL, modifiers)) {
            return;
        }
        commands.exitEditor();
    }
}
