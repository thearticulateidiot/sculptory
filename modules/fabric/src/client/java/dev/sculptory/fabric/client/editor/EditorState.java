package dev.sculptory.fabric.client.editor;

/** Whether editor mode is on. */
public enum EditorState {
    /** Normal gameplay. */
    INACTIVE,
    /** The editor screen is open and a tool is active. */
    ACTIVE,
    /** A vanilla screen (chat, commands, inventory) is open over the editor; it comes back when that closes. */
    SUSPENDED
}
