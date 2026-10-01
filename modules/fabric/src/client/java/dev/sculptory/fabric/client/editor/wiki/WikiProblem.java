package dev.sculptory.fabric.client.editor.wiki;

import java.util.Objects;

/**
 * Something in a page outside the wiki's Markdown subset, or written so that GitHub
 * would show it differently from the reader. The reader still shows the page as well as it can; the repository's wiki
 * test fails on any problem.
 *
 * @param line the 1-based line in the page's file
 */
public record WikiProblem(int line, String message) {
    public WikiProblem {
        Objects.requireNonNull(message);
    }

    @Override
    public String toString() {
        return "line " + line + ": " + message;
    }
}
