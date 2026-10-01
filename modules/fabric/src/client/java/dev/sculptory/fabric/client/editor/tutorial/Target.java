package dev.sculptory.fabric.client.editor.tutorial;

import dev.sculptory.fabric.client.editor.commands.CommandMenu;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import java.util.Objects;

/**
 * The part of the screen a step points at, outlined with a gentle pulse while the step shows. Each resolves to a
 * rectangle every frame ({@link TargetResolver}); one that isn't on screen now (a closed window, a menu row while its
 * menu is closed and another is open) draws nothing.
 */
public sealed interface Target {
    /** A palette slot (1-based). */
    record PaletteSlot(int slot) implements Target {}

    /** A menu bar title. */
    record MenuTitle(CommandMenu menu) implements Target {}

    /** A command's row in its menu while that menu is open; the menu's title while no menu is open. */
    record MenuRow(CommandMenu menu, String commandId) implements Target {
        public MenuRow {
            Objects.requireNonNull(menu);
            Objects.requireNonNull(commandId);
        }
    }

    /** A whole window, while it is open and shown. */
    record WindowFrame(String windowId) implements Target {
        public WindowFrame {
            Objects.requireNonNull(windowId);
        }
    }

    /**
     * A setting's row in Tool Settings while {@code tool} is the active tool: the row where it is scrolled into view,
     * or the header of its section while that section is closed.
     */
    record SettingRow(ToolId tool, String key) implements Target {
        public SettingRow {
            Objects.requireNonNull(tool);
            Objects.requireNonNull(key);
        }
    }

    /** One of the top bar's items, while the bar shows it. */
    record TopBar(Item item) implements Target {
        public TopBar {
            Objects.requireNonNull(item);
        }
    }

    /** The Export… dialog (File > Export…, or the Export buttons), while it is open. */
    record ExportDialog() implements Target {}

    /** The top bar items a step can point at. */
    enum Item {
        ACTIVE_BLOCK,
        UNDO,
        REDO,
        FLUID_AIM,
        FLY_SPEED,
        UI_SIZE
    }
}
