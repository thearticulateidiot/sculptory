package dev.sculptory.fabric.client.editor.wiki;

import java.util.Locale;

/**
 * The wiki's heading anchors (frozen): a heading's anchor is its text lower-cased, with
 * every run of characters other than a–z and 0–9 replaced by one {@code -}, trimmed of {@code -}. "Magic select
 * (Shift+click)" becomes {@code magic-select-shift-click}.
 */
public final class WikiAnchors {
    private WikiAnchors() {}

    /** The anchor of a heading whose plain text (formatting removed) is {@code text}; "" when it has no letters or digits. */
    public static String anchor(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(lower.length());
        boolean dash = false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9') {
                if (dash && !out.isEmpty()) {
                    out.append('-');
                }
                dash = false;
                out.append(c);
            } else {
                dash = true;
            }
        }
        return out.toString();
    }
}
