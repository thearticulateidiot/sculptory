package dev.sculptory.fabric.client.editor.tools.place;

import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.clipboard.ClipboardActions;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.layout.FlowRow;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.editor.windows.ToolSettingsWindow;
import java.util.Objects;

/**
 * The Place tool's row above its settings in Tool Settings: Rotate, Flip and Flip upside down, as in the Clipboard window. They turn the placement in progress (a stack takes the flip upside
 * down only; the tool says so otherwise), or else the next paste.
 */
public final class PlaceTransformPanel implements ToolSettingsWindow.Panel {
    private final ClipboardActions actions;
    private final Translator tr;

    public PlaceTransformPanel(ClipboardActions actions, Translator translator) {
        this.actions = Objects.requireNonNull(actions);
        this.tr = Objects.requireNonNull(translator);
    }

    @Override
    public Node build() {
        return FlowRow.of(
                button("sculptory.clipboard.rotate", "sculptory.clipboard.rotate.tooltip", actions::rotate),
                button("sculptory.clipboard.flip", "sculptory.clipboard.flip.tooltip", actions::flip),
                button("sculptory.clipboard.upside_down", "sculptory.clipboard.upside_down.tooltip",
                        actions::flipUpsideDown));
    }

    private Button button(String textKey, String tooltipKey, Runnable action) {
        Button button = new Button(tr.translate(textKey), action);
        button.setTooltip(tr.translate(tooltipKey));
        button.setGrow(1);
        return button;
    }
}
