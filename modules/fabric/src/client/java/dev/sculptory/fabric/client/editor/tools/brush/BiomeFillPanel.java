package dev.sculptory.fabric.client.editor.tools.brush;

import dev.sculptory.core.brush.BiomeSpec;
import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.editor.tools.select.SelectionActions;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.layout.FlowRow;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.editor.windows.ToolSettingsWindow;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The Biome tool's row above its settings in Tool Settings (protocol 6): Fill selection, which sets every quart the
 * selection touches to the tool's biome, whole columns unless 3D is on ({@link SelectionActions#biomeFill}). Biome view
 * is in the View menu.
 */
public final class BiomeFillPanel implements ToolSettingsWindow.Panel {
    private final SelectionActions actions;
    private final Supplier<SettingsValues> settings;
    private final Translator tr;

    /** @param settings the Biome tool's values as they are now */
    public BiomeFillPanel(SelectionActions actions, Supplier<SettingsValues> settings, Translator translator) {
        this.actions = Objects.requireNonNull(actions);
        this.settings = Objects.requireNonNull(settings);
        this.tr = Objects.requireNonNull(translator);
    }

    @Override
    public Node build() {
        Button fill = new Button(tr.translate("sculptory.biome.fill_selection"), this::fill);
        fill.setTooltip(tr.translate("sculptory.biome.fill_selection.tooltip"));
        fill.setGrow(1);
        return FlowRow.of(fill);
    }

    /** Fills the selection with the tool's biome; the actions explain a missing selection or right. */
    public boolean fill() {
        BiomeSpec choice = BrushSettings.biomeChoice(settings.get());
        return actions.biomeFill(choice.biomeId(), choice.columns());
    }
}
