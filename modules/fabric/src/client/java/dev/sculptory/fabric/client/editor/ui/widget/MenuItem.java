package dev.sculptory.fabric.client.editor.ui.widget;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * One entry of a {@link Menu}: an action with a label, the key that also runs it (right-aligned), an optional check
 * mark and tooltip; a separator line; or a submenu. A disabled item is drawn dimmed, is skipped by the arrow keys and
 * shows why it can't run as its tooltip. Items are values built fresh each time a menu opens, so they always show the
 * current state.
 */
public final class MenuItem {
    private static final MenuItem SEPARATOR = new MenuItem(true, "", "", false, true, null, null, null);

    private final boolean separator;
    private final String label;
    private final String keyText;
    private final boolean checked;
    private final boolean enabled;
    private final String tooltip;
    private final Runnable action;
    private final Supplier<List<MenuItem>> submenu;

    private MenuItem(boolean separator, String label, String keyText, boolean checked, boolean enabled, String tooltip,
            Runnable action, Supplier<List<MenuItem>> submenu) {
        this.separator = separator;
        this.label = Objects.requireNonNull(label);
        this.keyText = Objects.requireNonNull(keyText);
        this.checked = checked;
        this.enabled = enabled;
        this.tooltip = tooltip;
        this.action = action;
        this.submenu = submenu;
    }

    /** An item that runs {@code action}; {@code keyText} is shown on the right ("" for none). */
    public static MenuItem action(String label, String keyText, Runnable action) {
        return new MenuItem(false, label, keyText, false, true, null, Objects.requireNonNull(action), null);
    }

    /** An item that opens {@code items} to its right (built when it opens). */
    public static MenuItem submenu(String label, Supplier<List<MenuItem>> items) {
        return new MenuItem(false, label, "", false, true, null, null, Objects.requireNonNull(items));
    }

    /** A line between groups of items. */
    public static MenuItem separator() {
        return SEPARATOR;
    }

    /** This item with a check mark (or without one). */
    public MenuItem checked(boolean checked) {
        return new MenuItem(separator, label, keyText, checked, enabled, tooltip, action, submenu);
    }

    /** This item dimmed and not runnable; {@code reason} (why) becomes its tooltip. */
    public MenuItem disabled(String reason) {
        return new MenuItem(separator, label, keyText, checked, false, reason, action, submenu);
    }

    /** This item with a tooltip. */
    public MenuItem tooltip(String tooltip) {
        return new MenuItem(separator, label, keyText, checked, enabled, tooltip, action, submenu);
    }

    public boolean isSeparator() {
        return separator;
    }

    public String label() {
        return label;
    }

    public String keyText() {
        return keyText;
    }

    public boolean isChecked() {
        return checked;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** The item's tooltip: why it is disabled, or a description; null for none. */
    public String tooltipText() {
        return tooltip;
    }

    public boolean hasSubmenu() {
        return submenu != null;
    }

    /** The submenu's items, built now. */
    public List<MenuItem> submenuItems() {
        return submenu == null ? List.of() : List.copyOf(submenu.get());
    }

    /** Whether the arrow keys and the pointer can land on it: not a separator, and enabled. */
    public boolean isSelectable() {
        return !separator && enabled;
    }

    /** Runs the item's action (nothing for a separator, a submenu or a disabled item). */
    public void run() {
        if (enabled && action != null) {
            action.run();
        }
    }

    @Override
    public String toString() {
        return separator ? "---" : label + (keyText.isEmpty() ? "" : " [" + keyText + "]") + (checked ? " ✓" : "")
                + (enabled ? "" : " (disabled)") + (submenu != null ? " >" : "");
    }
}
