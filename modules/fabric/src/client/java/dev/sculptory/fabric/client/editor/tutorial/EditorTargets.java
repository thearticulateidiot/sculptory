package dev.sculptory.fabric.client.editor.tutorial;

import dev.sculptory.fabric.client.editor.EditorContext;
import dev.sculptory.fabric.client.editor.commands.Command;
import dev.sculptory.fabric.client.editor.hud.EditorUi;
import dev.sculptory.fabric.client.editor.settings.Section;
import dev.sculptory.fabric.client.editor.settings.SettingsSchema;
import dev.sculptory.fabric.client.editor.settings.form.SettingsForm;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.widget.CollapsibleSection;
import dev.sculptory.fabric.client.editor.ui.widget.Menu;
import dev.sculptory.fabric.client.editor.ui.widget.MenuBar;
import dev.sculptory.fabric.client.editor.ui.window.Window;
import dev.sculptory.fabric.client.editor.ui.window.WindowManager;
import dev.sculptory.fabric.client.editor.windows.EditorWindows;
import dev.sculptory.fabric.client.editor.windows.ExportDialog;
import java.util.Objects;
import java.util.Optional;

/** Where each {@link Target} is in the editor UI now, from its last layout (UI units). */
public final class EditorTargets implements TargetResolver {
    private final EditorUi ui;
    private final EditorContext ctx;
    private final Theme theme;

    public EditorTargets(EditorUi ui, EditorContext ctx, Theme theme) {
        this.ui = Objects.requireNonNull(ui);
        this.ctx = Objects.requireNonNull(ctx);
        this.theme = Objects.requireNonNull(theme);
    }

    @Override
    public Optional<Rect> resolve(Target target) {
        if (!ui.isEditing()) {
            return Optional.empty();
        }
        return switch (target) {
            case Target.PaletteSlot slot -> ui.paletteButton(slot.slot()).filter(Node::isShown).map(Node::bounds);
            case Target.MenuTitle title -> menuTitle(title.menu().ordinal());
            case Target.MenuRow row -> menuRow(row);
            case Target.WindowFrame frame -> shownWindow(frame.windowId()).map(Window::rect);
            case Target.SettingRow row -> settingRow(row);
            case Target.TopBar item -> ui.topBarBounds(item.item());
            case Target.ExportDialog dialog -> ExportDialog.bounds(ui.windows().context().popups());
        };
    }

    private Optional<Rect> menuTitle(int index) {
        MenuBar bar = ui.menuBar();
        if (!bar.isShown()) {
            return Optional.empty();
        }
        return Optional.of(bar.titleBounds(index)).filter(rect -> !rect.isEmpty());
    }

    /** The row while its menu is open; the menu's title otherwise. */
    private Optional<Rect> menuRow(Target.MenuRow row) {
        MenuBar bar = ui.menuBar();
        int index = row.menu().ordinal();
        if (!bar.isMenuOpen() || bar.openIndex() != index) {
            return menuTitle(index);
        }
        Optional<Menu> open = bar.openMenu();
        Optional<String> label = ui.commands().get(row.commandId()).map(this::label);
        if (open.isEmpty() || label.isEmpty()) {
            return Optional.empty();
        }
        Menu menu = open.get();
        for (int i = 0; i < menu.items().size(); i++) {
            if (!menu.items().get(i).isSeparator() && menu.items().get(i).label().equals(label.get())) {
                return Optional.of(menu.rowBounds(i)).filter(rect -> !rect.isEmpty());
            }
        }
        return Optional.empty();
    }

    private String label(Command command) {
        return ui.commands().label(command);
    }

    private Optional<Window> shownWindow(String id) {
        WindowManager windows = ui.windows();
        if (windows.isAllHidden() || !windows.isOpen(id)) {
            return Optional.empty();
        }
        return windows.window(id);
    }

    /**
     * The setting's row, clipped to what Tool Settings shows; while its section is closed, the section's header (the
     * player opens it first).
     */
    private Optional<Rect> settingRow(Target.SettingRow row) {
        if (!ctx.tools().isActive(row.tool())) {
            return Optional.empty();
        }
        Optional<Window> window = shownWindow(EditorWindows.TOOL_SETTINGS).filter(open -> !open.isCollapsed());
        Optional<SettingsForm> form = ui.toolSettings().form();
        if (window.isEmpty() || form.isEmpty()) {
            return Optional.empty();
        }
        Rect visible = window.get().contentRect(theme);
        // A closed section keeps its rows but doesn't lay them out: point at its header instead.
        SettingsSchema schema = ctx.settings(row.tool()).schema();
        for (Section section : schema.sections()) {
            boolean holds = section.settings().stream().anyMatch(def -> def.key().equals(row.key()));
            if (!holds || section.titleKey().isEmpty()) {
                continue;
            }
            Optional<CollapsibleSection> node = form.get().section(section.titleKey());
            if (node.isEmpty() || !node.get().isShown()) {
                return Optional.empty();
            }
            if (!node.get().isExpanded()) {
                return clip(node.get().headerRect(ui.windows().context()), visible);
            }
        }
        Optional<Node> field = form.get().field(row.key());
        if (field.isPresent() && field.get().isShown()) {
            return clip(field.get().bounds(), visible);
        }
        return Optional.empty();
    }

    private static Optional<Rect> clip(Rect rect, Rect visible) {
        return Optional.of(rect.intersect(visible)).filter(clipped -> !clipped.isEmpty());
    }
}
