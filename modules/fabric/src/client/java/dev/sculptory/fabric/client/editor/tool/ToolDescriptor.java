package dev.sculptory.fabric.client.editor.tool;

import dev.sculptory.server.engine.Perm;
import java.util.Objects;

/**
 * How a tool appears in the palette. The palette greys the tool out when the player lacks
 * {@code permission}.
 *
 * @param nameKey translation key of the display name
 * @param icon icon sprite id
 */
public record ToolDescriptor(ToolId id, String nameKey, String icon, Perm permission) {
    public ToolDescriptor {
        Objects.requireNonNull(id);
        Objects.requireNonNull(nameKey);
        Objects.requireNonNull(icon);
        Objects.requireNonNull(permission);
    }
}
