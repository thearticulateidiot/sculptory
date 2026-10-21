package dev.sculptory.mc.client.editor.tools.place;

import dev.sculptory.core.transform.Orientation;
import dev.sculptory.mc.client.editor.Translator;
import dev.sculptory.mc.client.editor.clipboard.ClipboardActions;
import dev.sculptory.mc.client.editor.ui.Node;
import dev.sculptory.mc.client.editor.ui.layout.Column;
import dev.sculptory.mc.client.editor.ui.layout.FlowRow;
import dev.sculptory.mc.client.editor.ui.layout.Row;
import dev.sculptory.mc.client.editor.ui.widget.Button;
import dev.sculptory.mc.client.editor.ui.widget.Label;
import dev.sculptory.mc.client.editor.ui.widget.TextInput;
import dev.sculptory.mc.client.editor.windows.ToolSettingsWindow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The Place tool's rows above its settings in Tool Settings: Rotate, Flip and Flip upside down, as in the Clipboard
 * window, and the placement's three angles (yaw about up, pitch about east-west, roll about north-south, in degrees),
 * which follow the gizmo's rings and can be typed (Enter sets them). They turn the placement in progress (a stack takes
 * the flip upside down only; the tool says so otherwise), or else the next paste.
 */
public final class PlaceTransformPanel implements ToolSettingsWindow.Panel {
    private final ClipboardActions actions;
    private final Translator tr;
    private final TextInput[] fields = new TextInput[3];
    /** The angles last shown (1/100 degree), or null to show them again. */
    private int[] shown;

    public PlaceTransformPanel(ClipboardActions actions, Translator translator) {
        this.actions = Objects.requireNonNull(actions);
        this.tr = Objects.requireNonNull(translator);
    }

    @Override
    public Node build() {
        shown = null;
        String[] names = {"sculptory.place.angle.yaw", "sculptory.place.angle.pitch", "sculptory.place.angle.roll"};
        Node[] angles = new Node[6];
        for (int i = 0; i < 3; i++) {
            Label label = new Label(tr.translate(names[i]));
            label.setTooltip(tr.translate(names[i] + ".tooltip"));
            TextInput field = new TextInput("0", text -> {});
            field.setMaxLength(9);
            field.setOnSubmit(text -> submit());
            field.setTooltip(tr.translate(names[i] + ".tooltip"));
            field.setGrow(1);
            fields[i] = field;
            angles[2 * i] = label;
            angles[2 * i + 1] = field;
        }
        return Column.of(
                FlowRow.of(
                        button("sculptory.clipboard.rotate", "sculptory.clipboard.rotate.tooltip", actions::rotate),
                        button("sculptory.clipboard.flip", "sculptory.clipboard.flip.tooltip", actions::flip),
                        button("sculptory.clipboard.upside_down", "sculptory.clipboard.upside_down.tooltip",
                                actions::flipUpsideDown)),
                Row.of(angles));
    }

    /** Follows the placement's angles (0 without a placement), unless they have not changed since last shown. */
    @Override
    public void refresh() {
        int[] angles = actions.placeTool().flatMap(PlaceTool::angles).orElse(new int[3]);
        if (shown != null && Arrays.equals(shown, angles)) return;
        shown = angles.clone();
        for (int i = 0; i < 3; i++) {
            if (fields[i] != null) fields[i].setText(degrees(angles[i]));
        }
    }

    /** Enter in an angle field: sets all three, as typed (a field that does not read as a number keeps its angle). */
    private void submit() {
        Optional<PlaceTool> tool = actions.placeTool();
        if (tool.isEmpty()) {
            shown = null;
            return;
        }
        int[] current = tool.get().angles().orElse(null);
        if (current == null) {
            shown = null;
            return;
        }
        int[] typed = current.clone();
        for (int i = 0; i < 3; i++) {
            OptionalInt parsed = parseDegrees(fields[i].text());
            if (parsed.isPresent()) typed[i] = parsed.getAsInt();
        }
        tool.get().setAngles(typed[0], typed[1], typed[2]);
        shown = null;
    }

    /**
     * An angle in 1/100 degree as degrees for a field: between -180 and 180, up to two decimals, no trailing zeros
     * ({@code 9000} is "90", {@code 34500} is "-15", {@code 1250} is "12.5").
     */
    public static String degrees(int centidegrees) {
        int a = Math.floorMod(centidegrees, Orientation.FULL_TURN);
        if (a > Orientation.FULL_TURN / 2) a -= Orientation.FULL_TURN;
        return BigDecimal.valueOf(a, 2).stripTrailingZeros().toPlainString();
    }

    /** Typed degrees ("15", "-22.5", "90°") in 1/100 degree, or empty when the text is no number. */
    public static OptionalInt parseDegrees(String text) {
        String trimmed = text.strip().replace("°", "").replace(',', '.');
        if (trimmed.isEmpty()) return OptionalInt.empty();
        try {
            BigDecimal value = new BigDecimal(trimmed).setScale(2, RoundingMode.HALF_UP);
            if (value.abs().compareTo(BigDecimal.valueOf(100_000)) > 0) return OptionalInt.empty();
            return OptionalInt.of(value.movePointRight(2).intValueExact());
        } catch (NumberFormatException | ArithmeticException notANumber) {
            return OptionalInt.empty();
        }
    }

    private Button button(String textKey, String tooltipKey, Runnable action) {
        Button button = new Button(tr.translate(textKey), action);
        button.setTooltip(tr.translate(tooltipKey));
        button.setGrow(1);
        return button;
    }
}
