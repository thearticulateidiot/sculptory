package dev.sculptory.fabric.client.editor.commands;

import java.util.Locale;

/** The menu bar's menus, in bar order. Every command belongs to one; the command search shows it dimmed. */
public enum CommandMenu {
    FILE, EDIT, SELECTION, TOOLS, VIEW, HELP;

    /** The menu's title, e.g. "sculptory.menu.file". */
    public String titleKey() {
        return "sculptory.menu." + name().toLowerCase(Locale.ROOT);
    }
}
