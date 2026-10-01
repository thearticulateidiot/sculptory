package dev.sculptory.fabric.client.editor.tools;

import dev.sculptory.fabric.client.editor.settings.SettingsSchema;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.editor.tool.KeyHint;
import dev.sculptory.fabric.client.editor.tool.RaycastMode;
import dev.sculptory.fabric.client.editor.tool.Tool;
import dev.sculptory.fabric.client.editor.tool.ToolContext;
import dev.sculptory.fabric.client.editor.tool.ToolDescriptor;
import java.util.List;
import java.util.Objects;

/**
 * A palette slot whose tool isn't built yet (Place and Scatter): shown disabled in the palette with its
 * milestone, and its hint line says so.
 */
public final class PlaceholderTool implements Tool {
    private final ToolDescriptor descriptor;
    private final String hintKey;

    /** @param hintKey what the hint line says while this tool is selected */
    public PlaceholderTool(ToolDescriptor descriptor, String hintKey) {
        this.descriptor = Objects.requireNonNull(descriptor);
        this.hintKey = Objects.requireNonNull(hintKey);
    }

    @Override
    public ToolDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public SettingsSchema schema() {
        return SettingsSchema.EMPTY;
    }

    @Override
    public RaycastMode raycastMode(SettingsValues s) {
        return RaycastMode.TERRAIN;
    }

    @Override
    public List<KeyHint> hints(ToolContext c) {
        return List.of(new KeyHint("", hintKey));
    }
}
