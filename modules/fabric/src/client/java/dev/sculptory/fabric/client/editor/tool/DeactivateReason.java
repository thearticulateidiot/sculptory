package dev.sculptory.fabric.client.editor.tool;

/** Why the active tool is being deactivated. */
public enum DeactivateReason {
    /** Another tool was selected. */
    SWITCHED_TOOL,
    /** The editor was closed. */
    EDITOR_CLOSED,
    /** A vanilla screen (chat, inventory) suspended the editor. */
    SUSPENDED,
    /** The player lost the tool's permission. */
    PERMISSION_LOST,
    /** Disconnect, death or dimension change. */
    WORLD_CHANGED
}
