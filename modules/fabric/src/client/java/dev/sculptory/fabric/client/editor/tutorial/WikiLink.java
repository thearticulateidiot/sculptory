package dev.sculptory.fabric.client.editor.tutorial;

import java.util.Objects;

/** A step's "Learn more": a wiki page id and, optionally, a heading's anchor on it (null: the page's top). */
public record WikiLink(String page, String anchor) {
    public WikiLink {
        Objects.requireNonNull(page);
    }

    public static WikiLink page(String page) {
        return new WikiLink(page, null);
    }
}
