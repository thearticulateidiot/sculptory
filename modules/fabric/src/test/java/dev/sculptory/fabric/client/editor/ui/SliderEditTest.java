package dev.sculptory.fabric.client.editor.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.ui.layout.Column;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.editor.ui.widget.Slider;
import dev.sculptory.fabric.client.editor.ui.widget.TextInput;
import dev.sculptory.fabric.client.editor.ui.window.Corner;
import dev.sculptory.fabric.client.editor.ui.window.WindowManager;
import dev.sculptory.fabric.client.editor.ui.window.WindowSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

/** Typing an exact value into a slider, driven through the window manager as the editor drives it. */
class SliderEditTest {
    private static final int LEFT = GLFW.GLFW_MOUSE_BUTTON_LEFT;

    private final WindowManager windows = new WindowManager(FakeTextMeasure.INSTANCE, Theme.DARK,
            UnaryOperator.identity());
    private final UiContext ctx = windows.context();
    private final List<Double> changes = new ArrayList<>();
    private final List<Double> releases = new ArrayList<>();
    private Slider radius;
    private Slider strength;
    private Button other;

    @BeforeEach
    void open() {
        radius = Slider.ofInt("Radius", 1, 32, 5, value -> changes.add((double) value));
        radius.setOnRelease(releases::add);
        strength = Slider.ofDecimal("Strength", 0, 1, 0.05, 0.5, changes::add);
        other = new Button("Other", null);
        windows.register(WindowSpec.builder("test", "test", () -> Column.of(radius, strength, other))
                .anchor(Corner.TOP_LEFT, 0, 0).size(200, 120).build());
        windows.layout(800, 600);
    }

    private double centreX(Node node) {
        return node.bounds().x() + node.bounds().width() / 2.0;
    }

    private double centreY(Node node) {
        return node.bounds().y() + node.bounds().height() / 2.0;
    }

    private void click(Node node, int modifiers) {
        windows.mouseDown(centreX(node), centreY(node), LEFT, modifiers);
        windows.mouseUp(centreX(node), centreY(node), LEFT);
        windows.layout(800, 600);
    }

    private TextInput editor(Slider slider) {
        assertTrue(slider.isEditing(), "the value field is open");
        TextInput field = slider.editor().orElseThrow();
        assertSame(field, ctx.focused(), "the keyboard is in the field");
        return field;
    }

    private void type(Slider slider, String text) {
        editor(slider).setText(text);
    }

    private boolean key(int keyCode) {
        boolean consumed = windows.keyPressed(keyCode, 0, 0);
        windows.layout(800, 600);
        return consumed;
    }

    @Test
    void ctrlClickOpensTheFieldWithTheValueAndChangesNothing() {
        click(radius, GLFW.GLFW_MOD_CONTROL);
        assertEquals("5", editor(radius).text());
        assertEquals(5, radius.intValue());
        assertEquals(List.of(), changes, "a Ctrl+click doesn't move the value");
        assertSame(editor(radius), radius.hitTest(centreX(radius), centreY(radius)), "the field covers the slider");
    }

    @Test
    void doubleClickPutsBackWhatTheFirstClickMovedThenOpensTheField() {
        ctx.setNow(1_000);
        click(radius, 0);
        assertEquals(17, radius.intValue(), "the first click sets the value where it lands");
        ctx.setNow(1_000 + Theme.DARK.doubleClickMs / 2);
        click(radius, 0);
        assertEquals(5, radius.intValue(), "the double-click keeps the value from before it");
        assertEquals("5", editor(radius).text());
    }

    @Test
    void twoSlowClicksAreTwoClicks() {
        ctx.setNow(1_000);
        click(radius, 0);
        ctx.setNow(1_000 + Theme.DARK.doubleClickMs + 1);
        click(radius, 0);
        assertFalse(radius.isEditing());
    }

    @Test
    void enterKeepsTheTypedValueAsOneCompleteEdit() {
        click(radius, GLFW.GLFW_MOD_CONTROL);
        type(radius, "12");
        assertTrue(key(GLFW.GLFW_KEY_ENTER));
        assertFalse(radius.isEditing());
        assertEquals(12, radius.intValue());
        assertEquals(List.of(12.0), changes);
        assertEquals(List.of(12.0), releases, "the release listener hears it once");
        assertNull(ctx.focused(), "a mouse-started edit gives the keyboard back to the editor");
    }

    @Test
    void typedValuesAreClampedAndSnapped() {
        click(radius, GLFW.GLFW_MOD_CONTROL);
        type(radius, "500");
        key(GLFW.GLFW_KEY_ENTER);
        assertEquals(32, radius.intValue(), "above the range: the maximum");

        click(radius, GLFW.GLFW_MOD_CONTROL);
        type(radius, "-3");
        key(GLFW.GLFW_KEY_ENTER);
        assertEquals(1, radius.intValue(), "below the range: the minimum");

        click(strength, GLFW.GLFW_MOD_CONTROL);
        assertEquals("0.50", editor(strength).text());
        type(strength, " 0,33 ");
        key(GLFW.GLFW_KEY_ENTER);
        assertEquals(0.35, strength.value(), 1e-9, "snapped to the step (a comma counts as the point)");
    }

    @Test
    void textThatIsNotANumberChangesNothing() {
        click(radius, GLFW.GLFW_MOD_CONTROL);
        type(radius, "big");
        key(GLFW.GLFW_KEY_ENTER);
        assertFalse(radius.isEditing());
        assertEquals(5, radius.intValue());
        assertEquals(List.of(), changes);
    }

    @Test
    void escCancelsWithoutClosingAnythingElse() {
        click(radius, GLFW.GLFW_MOD_CONTROL);
        type(radius, "20");
        assertTrue(key(GLFW.GLFW_KEY_ESCAPE), "Esc is used by the edit");
        assertFalse(radius.isEditing());
        assertEquals(5, radius.intValue());
        assertEquals(List.of(), changes);
    }

    @Test
    void clickingAwayKeepsTheTypedValue() {
        click(radius, GLFW.GLFW_MOD_CONTROL);
        type(radius, "9");
        click(other, 0);
        assertFalse(radius.isEditing());
        assertEquals(9, radius.intValue());
        assertEquals(List.of(9.0), changes);

        click(strength, GLFW.GLFW_MOD_CONTROL);
        type(strength, "0.8");
        windows.mouseDown(700, 500, LEFT, 0);
        windows.mouseUp(700, 500, LEFT);
        assertFalse(strength.isEditing(), "a click on the world ends it too");
        assertEquals(0.8, strength.value(), 1e-9);
    }

    @Test
    void enterOnTheFocusedSliderTypesAndTheKeyboardComesBack() {
        ctx.setFocus(radius);
        assertTrue(key(GLFW.GLFW_KEY_ENTER));
        assertEquals("5", editor(radius).text());
        type(radius, "7");
        key(GLFW.GLFW_KEY_ENTER);
        assertEquals(7, radius.intValue());
        assertSame(radius, ctx.focused(), "a keyboard-started edit gives the keyboard back to the slider");
        assertTrue(key(GLFW.GLFW_KEY_RIGHT), "the arrows step it again");
        assertEquals(8, radius.intValue());
    }

    @Test
    void whileTypingTheScrollWheelDoesNotStepTheValue() {
        click(radius, GLFW.GLFW_MOD_CONTROL);
        assertFalse(radius.mouseScroll(ctx, centreX(radius), centreY(radius), 1));
        assertEquals(5, radius.intValue());
    }
}
