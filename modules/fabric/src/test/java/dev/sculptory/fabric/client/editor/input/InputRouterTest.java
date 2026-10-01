package dev.sculptory.fabric.client.editor.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.tool.EditorAction;
import dev.sculptory.fabric.client.editor.tool.Modifiers;
import dev.sculptory.fabric.client.editor.tool.PointerEvent;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

class InputRouterTest {
    private static final int LEFT = GLFW.GLFW_MOUSE_BUTTON_LEFT;
    private static final int RIGHT = GLFW.GLFW_MOUSE_BUTTON_RIGHT;
    private static final int MIDDLE = GLFW.GLFW_MOUSE_BUTTON_MIDDLE;
    private static final int W = GLFW.GLFW_KEY_W;
    private static final int T = GLFW.GLFW_KEY_T;
    private static final int ESC = GLFW.GLFW_KEY_ESCAPE;
    private static final double UI_X = 50;
    private static final double WORLD_X = 500;

    private final List<String> log = new ArrayList<>();

    /** The UI covers x < 100. */
    private final class FakeUi implements InputRouter.Ui {
        boolean popup;
        boolean focus;
        boolean consumesFocusedKeys = true;
        /** A Keys window control is listening for a key to bind. */
        boolean capturing;

        @Override
        public boolean hasPopup() {
            return popup;
        }

        @Override
        public boolean hasKeyboardFocus() {
            return focus;
        }

        @Override
        public boolean capturesInput() {
            return capturing;
        }

        @Override
        public void clearFocus() {
            focus = false;
        }

        @Override
        public boolean isOverUi(double x, double y) {
            return x < 100;
        }

        @Override
        public boolean mouseDown(double x, double y, int button, int modifiers) {
            log.add("ui down " + button);
            return true;
        }

        @Override
        public boolean mouseDragged(double x, double y, int button) {
            log.add("ui drag " + button);
            return true;
        }

        @Override
        public boolean mouseUp(double x, double y, int button) {
            log.add("ui up " + button);
            return true;
        }

        @Override
        public boolean mouseScrolled(double x, double y, double amount, int modifiers) {
            log.add("ui scroll");
            return true;
        }

        @Override
        public void mouseMoved(double x, double y) {
        }

        /** Like the window manager: Esc closes a popup, then clears focus; focused controls take keys. */
        @Override
        public boolean keyPressed(int key, int scanCode, int modifiers) {
            if (key == ESC) {
                if (popup) {
                    popup = false;
                    log.add("ui close popup");
                    return true;
                }
                if (focus) {
                    focus = false;
                    log.add("ui unfocus");
                    return true;
                }
                return false;
            }
            if (focus && consumesFocusedKeys) {
                log.add("ui key " + key);
                return true;
            }
            return false;
        }

        @Override
        public boolean charTyped(char chr, int modifiers) {
            log.add("ui char " + chr);
            return true;
        }
    }

    private final class FakeTools implements InputRouter.Tools {
        boolean capture;
        final Set<EditorAction> takes = EnumSet.noneOf(EditorAction.class);

        @Override
        public boolean pointer(PointerEvent.Kind kind, int button, double x, double y, int modifiers) {
            log.add("tool " + kind + " " + button);
            return true;
        }

        @Override
        public boolean scroll(double amount, int modifiers) {
            log.add("tool scroll " + modifiers);
            return true;
        }

        @Override
        public boolean action(EditorAction action, int modifiers) {
            log.add("tool action " + action);
            return takes.contains(action);
        }

        @Override
        public boolean hasPointerCapture() {
            return capture;
        }
    }

    private final class FakeCommands implements InputRouter.Commands {
        @Override
        public void run(KeyAction action, int modifiers) {
            log.add("run " + action);
        }

        @Override
        public void flySpeed(double amount, int modifiers) {
            log.add("fly " + amount);
        }

        @Override
        public void eyedropper(double x, double y) {
            log.add("eyedropper");
        }

        @Override
        public void exitEditor() {
            log.add("exit");
        }

        @Override
        public boolean systemKey(int key, int scanCode) {
            if (key == T) {
                log.add("suspend chat");
                return true;
            }
            return false;
        }
    }

    private final class FakeLook implements InputRouter.Look {
        boolean looking;

        @Override
        public boolean isLooking() {
            return looking;
        }

        @Override
        public void begin() {
            looking = true;
            log.add("look begin");
        }

        @Override
        public void end() {
            looking = false;
            log.add("look end");
        }
    }

    private final class FakeMovement implements InputRouter.Movement {
        final Set<Integer> keys = Set.of(W, GLFW.GLFW_KEY_SPACE, GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_LEFT_CONTROL);

        @Override
        public boolean isMovementKey(int key, int scanCode) {
            return keys.contains(key);
        }

        @Override
        public void press(int key, int scanCode) {
            log.add("move " + key);
        }
    }

    private final FakeUi ui = new FakeUi();
    private final FakeTools tools = new FakeTools();
    private final FakeLook look = new FakeLook();
    private final InputRouter router = new InputRouter(ui, tools, new FakeCommands(), look, new FakeMovement(),
            EditorKeymap.defaults());

    // ---- Priority ----

    @Test
    void anOpenPopupGetsClicksEvenOverTheWorld() {
        ui.popup = true;
        router.mouseClicked(WORLD_X, 10, LEFT, 0);
        router.mouseDragged(WORLD_X, 20, LEFT);
        router.mouseReleased(WORLD_X, 20, LEFT);
        assertEquals(List.of("ui down 0", "ui drag 0", "ui up 0"), log);
    }

    @Test
    void aKeyWithoutACodeIsIgnoredRatherThanCrashing() {
        // GLFW reports a media or macro key as GLFW_KEY_UNKNOWN (-1); a chord can't be made of it.
        assertFalse(router.keyPressed(GLFW.GLFW_KEY_UNKNOWN, 0, 0));
        assertFalse(router.keyReleased(GLFW.GLFW_KEY_UNKNOWN, 0, 0));
        assertEquals(List.of(), log);
    }

    @Test
    void anOpenPopupGetsKeysAndMovementStillWorks() {
        ui.popup = true;
        router.keyPressed(GLFW.GLFW_KEY_Z, 0, Modifiers.CONTROL);
        assertEquals(List.of(), log, "chords don't fire under a popup");
        router.keyPressed(W, 0, 0);
        assertEquals(List.of("move " + W), log);
    }

    @Test
    void ctrlKFindsACommandButNotWhileTypingOrUnderAPopup() {
        router.keyPressed(GLFW.GLFW_KEY_K, 0, Modifiers.CONTROL);
        router.keyReleased(GLFW.GLFW_KEY_K, 0, Modifiers.CONTROL);
        assertEquals(List.of("run COMMAND_SEARCH"), log, "no tool action comes first");
        log.clear();
        router.keyPressed(GLFW.GLFW_KEY_K, 0, 0);
        router.keyReleased(GLFW.GLFW_KEY_K, 0, 0);
        assertEquals(List.of(), log, "plain K is not bound");

        ui.focus = true;
        router.keyPressed(GLFW.GLFW_KEY_K, 0, Modifiers.CONTROL);
        router.keyReleased(GLFW.GLFW_KEY_K, 0, Modifiers.CONTROL);
        assertEquals(List.of("ui key " + GLFW.GLFW_KEY_K), log, "a focused text field keeps Ctrl+K");
        log.clear();
        ui.focus = false;
        ui.popup = true;
        router.keyPressed(GLFW.GLFW_KEY_K, 0, Modifiers.CONTROL);
        assertEquals(List.of(), log, "an open menu or search takes the key");
    }

    @Test
    void ctrlKIsRebindable() {
        EditorKeymap keymap = EditorKeymap.defaults();
        keymap.bind(KeyAction.COMMAND_SEARCH, List.of(KeyChord.parse("ctrl+p")));
        InputRouter rebound = new InputRouter(ui, tools, new FakeCommands(), look, new FakeMovement(), keymap);
        rebound.keyPressed(GLFW.GLFW_KEY_K, 0, Modifiers.CONTROL);
        rebound.keyPressed(GLFW.GLFW_KEY_P, 0, Modifiers.CONTROL);
        assertEquals(List.of("run COMMAND_SEARCH"), log);
    }

    @Test
    void aToolWithPointerCaptureGetsPressesOverTheUi() {
        tools.capture = true;
        router.mouseClicked(UI_X, 10, LEFT, 0);
        router.mouseReleased(UI_X, 10, LEFT);
        assertEquals(List.of("tool PRESS 0", "tool RELEASE 0"), log);
    }

    @Test
    void theLayerThatGotThePressKeepsTheDragAndRelease() {
        router.mouseClicked(UI_X, 10, LEFT, 0);
        router.mouseDragged(WORLD_X, 10, LEFT);
        router.mouseReleased(WORLD_X, 10, LEFT);
        router.mouseClicked(WORLD_X, 10, LEFT, 0);
        router.mouseDragged(UI_X, 10, LEFT);
        router.mouseReleased(UI_X, 10, LEFT);
        assertEquals(List.of("ui down 0", "ui drag 0", "ui up 0", "tool PRESS 0", "tool DRAG 0", "tool RELEASE 0"), log);
    }

    @Test
    void focusedTextTakesKeysBeforeChordsAndSystemKeys() {
        ui.focus = true;
        router.keyPressed(GLFW.GLFW_KEY_Z, 0, Modifiers.CONTROL);
        router.keyPressed(T, 0, 0);
        router.keyPressed(W, 0, 0);
        router.charTyped('t', 0);
        assertEquals(List.of("ui key " + GLFW.GLFW_KEY_Z, "ui key " + T, "ui key " + W, "ui char t"), log);
    }

    @Test
    void keysAFocusedControlDoesNotTakeFallThroughToChords() {
        ui.focus = true;
        ui.consumesFocusedKeys = false;
        router.keyPressed(GLFW.GLFW_KEY_F1, 0, 0);
        assertEquals(List.of("run HELP"), log);
    }

    @Test
    void chordsRunWhenNothingIsFocused() {
        router.keyPressed(GLFW.GLFW_KEY_Z, 0, Modifiers.CONTROL);
        router.keyReleased(GLFW.GLFW_KEY_Z, 0, Modifiers.CONTROL);
        router.keyPressed(GLFW.GLFW_KEY_Y, 0, Modifiers.CONTROL);
        router.keyPressed(GLFW.GLFW_KEY_Z, 0, Modifiers.CONTROL | Modifiers.SHIFT);
        router.keyPressed(GLFW.GLFW_KEY_3, 0, 0);
        router.keyPressed(GLFW.GLFW_KEY_TAB, 0, 0);
        assertEquals(List.of("tool action UNDO", "run UNDO", "run REDO", "run REDO", "run TOOL_3",
                "run HIDE_WINDOWS"), log,
                "undo is offered to the tool first (the Scatter tool undoes its painted strokes)");
    }

    // ---- A Keys window control listening for a key to bind ----

    @Test
    void aListeningControlTakesEveryPressAndNoToolSeesIt() {
        ui.focus = true;
        ui.capturing = true;
        assertTrue(router.keyPressed(GLFW.GLFW_KEY_Z, 0, Modifiers.CONTROL), "consumed, whatever the UI answers");
        assertTrue(router.keyPressed(T, 0, 0));
        assertTrue(router.keyPressed(W, 0, 0));
        assertTrue(router.keyPressed(GLFW.GLFW_KEY_K, 0, 0));
        assertTrue(router.mouseClicked(WORLD_X, 10, MIDDLE, 0));
        router.mouseReleased(WORLD_X, 10, MIDDLE);
        assertTrue(router.mouseClicked(WORLD_X, 10, RIGHT, 0));
        router.mouseReleased(WORLD_X, 10, RIGHT);
        router.mouseScrolled(WORLD_X, 10, 1, Modifiers.CONTROL);
        assertEquals(List.of("ui key " + GLFW.GLFW_KEY_Z, "ui key " + T, "ui key " + W, "ui key " + GLFW.GLFW_KEY_K,
                "ui down 2", "ui up 2", "ui down 1", "ui up 1", "ui scroll"), log,
                "no undo, no chat, no movement, no eyedropper, no look, no fly speed");
        assertFalse(look.isLooking());
    }

    @Test
    void aHeldKeyTheControlTookIsNotAChordWhileItRepeats() {
        ui.focus = true;
        ui.capturing = true;
        router.keyPressed(GLFW.GLFW_KEY_Z, 0, Modifiers.CONTROL);
        // The control bound Ctrl+Z and stopped listening; the operating system repeats the held Z.
        ui.focus = false;
        ui.capturing = false;
        assertTrue(router.keyPressed(GLFW.GLFW_KEY_Z, 0, Modifiers.CONTROL));
        assertEquals(List.of("ui key " + GLFW.GLFW_KEY_Z), log, "the repeat is swallowed");
        router.keyReleased(GLFW.GLFW_KEY_Z, 0, Modifiers.CONTROL);
        router.keyPressed(GLFW.GLFW_KEY_Z, 0, Modifiers.CONTROL);
        assertEquals(List.of("ui key " + GLFW.GLFW_KEY_Z, "tool action UNDO", "run UNDO"), log,
                "a new press after the release is a chord again");
    }

    @Test
    void escWhileListeningCancelsAndItsRepeatsStopThere() {
        ui.focus = true;
        ui.capturing = true;
        router.keyPressed(ESC, 0, 0);
        assertEquals(List.of("ui unfocus"), log, "the window manager clears focus, which cancels the listening");
        ui.capturing = false;
        router.keyPressed(ESC, 0, 0);
        assertEquals(List.of("ui unfocus"), log, "a held Esc doesn't go on down the ladder");
        router.keyReleased(ESC, 0, 0);
        router.keyPressed(ESC, 0, 0);
        assertEquals(List.of("ui unfocus", "tool action CANCEL", "exit"), log);
    }

    @Test
    void rebindingTheKeymapTakesEffectForTheNextPress() {
        router.keymap().bind(KeyAction.UNDO, List.of(KeyChord.parse("ctrl+u")));
        assertFalse(router.keyPressed(GLFW.GLFW_KEY_Z, 0, Modifiers.CONTROL), "Ctrl+Z is nothing now");
        router.keyPressed(GLFW.GLFW_KEY_U, 0, Modifiers.CONTROL);
        assertEquals(List.of("tool action UNDO", "run UNDO"), log);
        log.clear();
        router.keymap().bind(KeyAction.EYEDROPPER, List.of(KeyChord.parse("alt+mouse.middle")));
        router.mouseClicked(WORLD_X, 10, MIDDLE, 0);
        router.mouseReleased(WORLD_X, 10, MIDDLE);
        router.mouseClicked(WORLD_X, 10, MIDDLE, Modifiers.ALT);
        assertEquals(List.of("tool PRESS 2", "tool RELEASE 2", "tool action EYEDROPPER", "eyedropper"), log,
                "a plain middle-click is the tool's; Alt+middle picks");
    }

    @Test
    void resetForgetsTheCapturedKey() {
        ui.focus = true;
        ui.capturing = true;
        router.keyPressed(GLFW.GLFW_KEY_Z, 0, Modifiers.CONTROL);
        ui.focus = false;
        ui.capturing = false;
        router.reset();
        router.keyPressed(GLFW.GLFW_KEY_Z, 0, Modifiers.CONTROL);
        assertEquals(List.of("ui key " + GLFW.GLFW_KEY_Z, "tool action UNDO", "run UNDO"), log);
    }

    // ---- Key repeat (vanilla hands GLFW repeats to Screen.keyPressed like presses) ----

    @Test
    void holdingUndoOrRedoFiresOncePerPress() {
        int z = GLFW.GLFW_KEY_Z;
        router.keyPressed(z, 0, Modifiers.CONTROL);
        router.keyPressed(z, 0, Modifiers.CONTROL);
        router.keyPressed(z, 0, Modifiers.CONTROL);
        assertEquals(List.of("tool action UNDO", "run UNDO"), log, "repeats of a held Ctrl+Z are ignored");
        assertTrue(router.keyPressed(z, 0, Modifiers.CONTROL), "a swallowed repeat is still consumed");
        router.keyPressed(z, 0, Modifiers.CONTROL | Modifiers.SHIFT);
        assertEquals(List.of("tool action UNDO", "run UNDO"), log,
                "adding Shift to a held Z is not a new press of redo either");
        router.keyReleased(z, 0, Modifiers.CONTROL);
        router.keyPressed(z, 0, Modifiers.CONTROL);
        assertEquals(List.of("tool action UNDO", "run UNDO", "tool action UNDO", "run UNDO"), log,
                "pressing again after the release undoes again");

        log.clear();
        router.keyPressed(GLFW.GLFW_KEY_Y, 0, Modifiers.CONTROL);
        router.keyPressed(GLFW.GLFW_KEY_Y, 0, Modifiers.CONTROL);
        router.keyReleased(GLFW.GLFW_KEY_Y, 0, Modifiers.CONTROL);
        router.keyPressed(GLFW.GLFW_KEY_Y, 0, Modifiers.CONTROL);
        assertEquals(List.of("run REDO", "run REDO"), log);
    }

    @Test
    void otherChordsStillRepeatWhileHeld() {
        router.keyPressed(GLFW.GLFW_KEY_UP, 0, 0);
        router.keyPressed(GLFW.GLFW_KEY_UP, 0, 0);
        assertEquals(List.of("tool action NUDGE_FORWARD", "run NUDGE_FORWARD", "tool action NUDGE_FORWARD",
                "run NUDGE_FORWARD"), log, "a held arrow keeps nudging");
        for (KeyAction action : KeyAction.values()) {
            assertEquals(action == KeyAction.UNDO || action == KeyAction.REDO || action == KeyAction.AIM_AT_FLUIDS
                            || action == KeyAction.SET_SYMMETRY_CENTRE || action == KeyAction.JUMP
                            || action == KeyAction.JUMP_THROUGH || action == KeyAction.TOGGLE_MASK,
                    action.ignoresRepeat(), action.name());
        }
    }

    /** The symmetry centre key toggles the centre, so a held M (or Shift+M) sets it once, not on and off by turns. */
    @Test
    void holdingTheSymmetryCentreKeyFiresOncePerPress() {
        int m = GLFW.GLFW_KEY_M;
        router.keyPressed(m, 0, 0);
        router.keyPressed(m, 0, 0);
        router.keyPressed(m, 0, Modifiers.SHIFT);
        List<String> once = List.of("tool action SET_SYMMETRY_CENTRE", "run SET_SYMMETRY_CENTRE");
        assertEquals(once, log, "repeats of a held M are ignored");
        router.keyReleased(m, 0, 0);
        router.keyPressed(m, 0, Modifiers.SHIFT);
        assertEquals(List.of("tool action SET_SYMMETRY_CENTRE", "run SET_SYMMETRY_CENTRE", "tool action SET_SYMMETRY_CENTRE",
                "run SET_SYMMETRY_CENTRE"), log, "Shift+M after the release is a new press");
    }

    @Test
    void resetForgetsHeldKeys() {
        router.keyPressed(GLFW.GLFW_KEY_Z, 0, Modifiers.CONTROL);
        // The editor was suspended with Z down, so its release went to another screen.
        router.reset();
        router.keyPressed(GLFW.GLFW_KEY_Z, 0, Modifiers.CONTROL);
        assertEquals(List.of("tool action UNDO", "run UNDO", "tool action UNDO", "run UNDO"), log);
    }

    @Test
    void toolActionsAreOfferedToTheToolFirst() {
        tools.takes.add(EditorAction.ERASE_SELECTION);
        router.keyPressed(GLFW.GLFW_KEY_DELETE, 0, 0);
        assertEquals(List.of("tool action ERASE_SELECTION"), log);
        log.clear();
        router.keyPressed(GLFW.GLFW_KEY_D, 0, Modifiers.CONTROL);
        assertEquals(List.of("tool action DESELECT", "run DESELECT"), log, "the editor handles what the tool leaves");
        log.clear();
        router.keyPressed(GLFW.GLFW_KEY_UP, 0, Modifiers.SHIFT);
        assertEquals(List.of("tool action NUDGE_FORWARD", "run NUDGE_FORWARD"), log, "Shift+arrow is still a nudge");
    }

    @Test
    void rightButtonLooksInTheWorldButNotOverTheUi() {
        router.mouseClicked(WORLD_X, 10, RIGHT, 0);
        router.mouseDragged(WORLD_X, 30, RIGHT);
        router.mouseReleased(WORLD_X, 30, RIGHT);
        router.mouseClicked(UI_X, 10, RIGHT, 0);
        assertEquals(List.of("look begin", "look end", "ui down 1"), log);
    }

    @Test
    void middleClickPicksABlockWhenTheToolDoesNot() {
        router.mouseClicked(WORLD_X, 10, MIDDLE, 0);
        router.mouseReleased(WORLD_X, 10, MIDDLE);
        assertEquals(List.of("tool action EYEDROPPER", "eyedropper"), log);
        log.clear();
        tools.takes.add(EditorAction.EYEDROPPER);
        router.mouseClicked(WORLD_X, 10, MIDDLE, 0);
        assertEquals(List.of("tool action EYEDROPPER"), log);
    }

    @Test
    void clickingTheWorldClearsTextFocus() {
        ui.focus = true;
        router.mouseClicked(WORLD_X, 10, LEFT, 0);
        assertFalse(ui.focus);
    }

    @Test
    void scrollGoesToTheUiOrFlySpeedOrTheTool() {
        router.mouseScrolled(UI_X, 10, 1, 0);
        router.mouseScrolled(WORLD_X, 10, 1, 0);
        router.mouseScrolled(WORLD_X, 10, -1, Modifiers.CONTROL);
        router.mouseScrolled(WORLD_X, 10, 1, Modifiers.CONTROL | Modifiers.SHIFT);
        router.mouseScrolled(WORLD_X, 10, 1, Modifiers.ALT);
        assertEquals(List.of("ui scroll", "fly 1.0", "tool scroll 2", "tool scroll 3", "tool scroll 4"), log);
    }

    @Test
    void moveEventsReachTheToolOnlyWhenIdleOverTheWorld() {
        router.mouseMoved(WORLD_X, 10);
        router.mouseMoved(UI_X, 10);
        router.mouseClicked(WORLD_X, 10, LEFT, 0);
        router.mouseMoved(WORLD_X, 20);
        router.mouseReleased(WORLD_X, 20, LEFT);
        router.mouseClicked(WORLD_X, 10, RIGHT, 0);
        router.mouseMoved(WORLD_X, 40);
        assertEquals(List.of("tool MOVE -1", "tool PRESS 0", "tool RELEASE 0", "look begin"), log);
    }

    // ---- Movement and system keys ----

    @Test
    void movementKeysPassThroughAndReleaseInVanilla() {
        assertTrue(router.keyPressed(W, 17, 0));
        assertTrue(router.keyPressed(GLFW.GLFW_KEY_LEFT_SHIFT, 42, Modifiers.SHIFT));
        assertEquals(List.of("move " + W, "move " + GLFW.GLFW_KEY_LEFT_SHIFT), log);
        assertFalse(router.keyReleased(W, 17, 0), "vanilla must see the release");
        assertFalse(router.keyReleased(GLFW.GLFW_KEY_LEFT_SHIFT, 42, 0));
    }

    @Test
    void chatCommandAndInventoryKeysSuspend() {
        assertTrue(router.keyPressed(T, 0, 0));
        assertEquals(List.of("suspend chat"), log);
        assertFalse(router.keyPressed(GLFW.GLFW_KEY_K, 0, 0), "unbound keys are not consumed");
    }

    // ---- Esc ladder ----

    @Test
    void escClosesAPopupFirst() {
        ui.popup = true;
        ui.focus = true;
        router.keyPressed(ESC, 0, 0);
        assertEquals(List.of("ui close popup"), log);
    }

    @Test
    void escThenUnfocusesText() {
        ui.focus = true;
        router.keyPressed(ESC, 0, 0);
        assertEquals(List.of("ui unfocus"), log);
    }

    @Test
    void escThenEndsAMouseLook() {
        router.mouseClicked(WORLD_X, 10, RIGHT, 0);
        log.clear();
        router.keyPressed(ESC, 0, 0);
        assertEquals(List.of("look end"), log);
        router.mouseReleased(WORLD_X, 10, RIGHT);
        assertEquals(List.of("look end"), log, "the later release is ignored");
    }

    @Test
    void escThenCancelsTheToolsDragOrPreview() {
        tools.takes.add(EditorAction.CANCEL);
        router.keyPressed(ESC, 0, 0);
        assertEquals(List.of("tool action CANCEL"), log);
    }

    @Test
    void escFinallyLeavesTheEditor() {
        router.keyPressed(ESC, 0, 0);
        assertEquals(List.of("tool action CANCEL", "exit"), log);
    }

    @Test
    void theFullLadderTakesOneStepPerPress() {
        ui.popup = true;
        ui.focus = true;
        tools.takes.add(EditorAction.CANCEL);
        router.keyPressed(ESC, 0, 0);
        router.keyPressed(ESC, 0, 0);
        router.keyPressed(ESC, 0, 0);
        tools.takes.clear();
        router.keyPressed(ESC, 0, 0);
        assertEquals(List.of("ui close popup", "ui unfocus", "tool action CANCEL", "tool action CANCEL", "exit"), log);
    }

    @Test
    void resetEndsALookAndForgetsPressedButtons() {
        router.mouseClicked(WORLD_X, 10, RIGHT, 0);
        router.mouseClicked(WORLD_X, 10, LEFT, 0);
        assertTrue(router.isToolDragging());
        router.reset();
        assertFalse(look.isLooking());
        assertFalse(router.isToolDragging());
    }
}
