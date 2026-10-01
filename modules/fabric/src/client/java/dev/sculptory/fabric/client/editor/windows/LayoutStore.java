package dev.sculptory.fabric.client.editor.windows;

import dev.sculptory.fabric.client.editor.ConfigFile;
import dev.sculptory.fabric.client.editor.ui.window.Corner;
import dev.sculptory.fabric.client.editor.ui.window.LayoutState;
import dev.sculptory.fabric.client.editor.ui.window.SizedLayouts;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Loads and saves {@code editor-layout.json}: every UI size's window arrangement, and which windows are open or
 * collapsed ({@link SizedLayouts}, version 2).
 *
 * <p>A version-1 file (one arrangement for every UI size, written before) is read as the arrangement of the UI size in
 * use, the other sizes starting from the default layout; the next save writes version 2. A missing file gives the
 * default layout at every size. A file that can't be used (not a layout file, or one of a newer version) also gives
 * the default layout, is reported, and is left unchanged: nothing is saved over it until it is read again at the next
 * game start ({@link ConfigFile#keepAsIs}, as for the key and preset files), so the player can look at it or remove
 * it. A file changed outside the game while it runs isn't overwritten either ({@link ConfigFile}).
 */
public final class LayoutStore {
    public static final String FILE_NAME = "editor-layout.json";

    /**
     * Where earlier versions opened each window by default (anchor, offsets from it, size), as they saved a window the
     * user never moved: main's window specs, and the earlier sizes of Selection, History and Keys. A layout file from
     * before "placed" existed says nothing else about whether the user moved a window.
     */
    static final List<LayoutState.WindowState> OLD_DEFAULTS = List.of(
            old(EditorWindows.TOOL_SETTINGS, Corner.TOP_RIGHT, 4, 26, 170, 200),
            old(EditorWindows.SELECTION, Corner.TOP_LEFT, 4, 26, 190, 250),
            old(EditorWindows.SELECTION, Corner.TOP_LEFT, 4, 26, 180, 196),
            old(EditorWindows.CLIPBOARD, Corner.BOTTOM_LEFT, 4, 100, 180, 150),
            old(EditorWindows.LIBRARY, Corner.TOP_RIGHT, 180, 26, 220, 260),
            old(EditorWindows.HISTORY, Corner.BOTTOM_LEFT, 4, 4, 190, 210),
            old(EditorWindows.HISTORY, Corner.BOTTOM_LEFT, 4, 4, 160, 92),
            old(EditorWindows.KEYS, Corner.TOP_LEFT, 190, 26, 250, 260),
            old(EditorWindows.KEYS, Corner.TOP_LEFT, 190, 26, 230, 220));

    private final ConfigFile file;

    public LayoutStore(ConfigFile file) {
        this.file = Objects.requireNonNull(file);
    }

    private static LayoutState.WindowState old(String id, Corner anchor, int x, int y, int width, int height) {
        return new LayoutState.WindowState(id, false, false, anchor, x, y, width, height);
    }

    /** Whether a saved window sits exactly where an earlier version opened it by default ({@link #OLD_DEFAULTS}). */
    public static boolean isOldDefault(LayoutState.WindowState state) {
        return OLD_DEFAULTS.stream().anyMatch(old -> old.id().equals(state.id()) && old.anchor() == state.anchor()
                && old.offsetX() == state.offsetX() && old.offsetY() == state.offsetY()
                && old.width() == state.width() && old.height() == state.height());
    }

    /**
     * A layout file's text. A version-1 file is the arrangement of UI size {@code uiPercent}: a window it saved before
     * "placed" existed is placed (the user may have moved it), except a closed one at an old default place
     * ({@link #isOldDefault}), which opens at today's default place.
     *
     * @throws IllegalArgumentException if the text isn't a layout file of version 1 or 2
     */
    public static SizedLayouts parse(String json, int uiPercent) {
        return SizedLayouts.fromJson(json, uiPercent, LayoutStore::isOldDefault);
    }

    /**
     * The saved layouts, a version-1 file as the arrangement of UI size {@code uiPercent}; empty when there is none or
     * it can't be used (the defaults apply; an unusable file is reported and left unchanged).
     */
    public Optional<SizedLayouts> load(int uiPercent) {
        Optional<String> text = file.read();
        if (text.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(parse(text.get(), uiPercent));
        } catch (IllegalArgumentException unusable) {
            file.keepAsIs(unusable.getMessage());
            return Optional.empty();
        }
    }

    /** Saves the layouts if they changed. Returns true if the file now holds them. */
    public boolean save(SizedLayouts layouts) {
        return file.write(layouts.toJson());
    }
}
