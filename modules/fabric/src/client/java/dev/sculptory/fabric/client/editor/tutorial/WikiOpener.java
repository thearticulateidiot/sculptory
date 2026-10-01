package dev.sculptory.fabric.client.editor.tutorial;

/**
 * Opens a wiki page (one of the frozen page ids in {@code WikiPages}) at a section, for a step's
 * "Learn more". The editor UI passes in its wiki reader ({@code EditorUi.openWiki}).
 */
@FunctionalInterface
public interface WikiOpener {
    /** @param anchor a heading's anchor on the page, or null for its top */
    void open(String pageId, String anchor);

    /** Opens nothing (before the wiki reader is part of the editor). */
    WikiOpener NONE = (pageId, anchor) -> { };
}
