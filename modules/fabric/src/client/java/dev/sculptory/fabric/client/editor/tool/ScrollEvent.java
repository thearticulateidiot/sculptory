package dev.sculptory.fabric.client.editor.tool;

/**
 * A scroll routed to the active tool (Ctrl+Scroll radius, Alt+Scroll strength).
 *
 * @param amount vertical scroll, positive away from the user
 * @param modifiers {@link Modifiers} bits
 */
public record ScrollEvent(double amount, int modifiers) {}
