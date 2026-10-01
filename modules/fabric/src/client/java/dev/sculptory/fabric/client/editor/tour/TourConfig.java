package dev.sculptory.fabric.client.editor.tour;

import dev.sculptory.fabric.client.editor.EditorClient;
import dev.sculptory.fabric.client.editor.ui.UiScale;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The dev-only switch of the screenshot tour: {@code -Dsculptory.tour=<absolute output dir>}
 * ({@code -PbsTour=<dir>} on {@code runClient}, or {@code scripts/playtest.ps1 -Tour}). Without it nothing of the
 * tour is loaded: the client entrypoint reads the property through the compile-time constant {@link #PROPERTY}.
 */
public final class TourConfig {
    public static final String PROPERTY = EditorClient.TOUR_PROPERTY;
    /** The UI size (percent) every step starts from ({@code -PbsTourUiSize}); 100 when absent. */
    public static final String UI_SIZE_PROPERTY = PROPERTY + ".uiSize";
    /**
     * An {@code editor-layout.json} every step starts from ({@code -PbsTourLayout}), each UI size at its arrangement
     * there; the default layout when absent.
     */
    public static final String LAYOUT_PROPERTY = PROPERTY + ".layout";
    /** "true": the tour maximises the game window before it starts ({@code -PbsTourMaximize}). */
    public static final String MAXIMIZE_PROPERTY = PROPERTY + ".maximize";
    /**
     * The wiki mode ({@code -PbsTourWiki=<dir>}, {@code scripts/playtest.ps1 -Tour -Wiki}): the absolute directory the
     * wiki pictures go to ({@code docs/wiki/images}); the tour then plays {@link WikiTour#steps()} instead, and only
     * its index goes to the tour directory.
     */
    public static final String WIKI_PROPERTY = PROPERTY + ".wiki";

    /** The editor UI size file, in the same directory as the layout file. */
    static final String UI_FILE = "editor-ui.json";

    /** A picture an earlier tour wrote ({@code 07-tool-raise.png}). */
    private static final Pattern PICTURE = Pattern.compile("\\d{2,}-[a-z0-9-]+\\.png");

    private TourConfig() {}

    /**
     * The output directory the property names, or empty when the tour is off (property absent or blank).
     *
     * @throws IllegalArgumentException when the value is not an absolute path
     */
    public static Optional<Path> outputDir(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        Path dir = Path.of(value.strip());
        if (!dir.isAbsolute()) {
            throw new IllegalArgumentException("-D" + PROPERTY + " needs an absolute directory, got '" + value + "'");
        }
        return Optional.of(dir.normalize());
    }

    /**
     * The UI size every step starts from: the property's percent, or {@link UiScale#DEFAULT_PERCENT} when absent.
     *
     * @throws IllegalArgumentException when the value is not one of {@link UiScale#steps()}
     */
    public static int baseUiSize(String value) {
        if (value == null || value.isBlank()) {
            return UiScale.DEFAULT_PERCENT;
        }
        try {
            int percent = Integer.parseInt(value.strip());
            if (UiScale.steps().contains(percent)) {
                return percent;
            }
        } catch (NumberFormatException notANumber) {
            // reported below
        }
        throw new IllegalArgumentException("-D" + UI_SIZE_PROPERTY + " must be one of " + UiScale.steps() + ", got '"
                + value + "'");
    }

    /**
     * The directory the wiki pictures go to, or empty for the ordinary tour.
     *
     * @throws IllegalArgumentException when the value is not an absolute path
     */
    public static Optional<Path> wikiDir(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        Path dir = Path.of(value.strip());
        if (!dir.isAbsolute()) {
            throw new IllegalArgumentException("-D" + WIKI_PROPERTY + " needs an absolute directory, got '" + value
                    + "'");
        }
        return Optional.of(dir.normalize());
    }

    /** The window layout file every step starts from, or empty for the default layout. */
    public static Optional<Path> layoutFile(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        Path file = Path.of(value.strip());
        if (!file.isAbsolute()) {
            throw new IllegalArgumentException("-D" + LAYOUT_PROPERTY + " needs an absolute file, got '" + value + "'");
        }
        return Optional.of(file.normalize());
    }

    /**
     * The UI size a layout file was last used at: the one in the {@code editor-ui.json} beside it, or 100% when there
     * is none or it can't be read (as the game has it). The game reads a version-1 layout file, one arrangement for
     * every size, as that size's arrangement; so does the tour.
     */
    public static int layoutUiSize(Path layoutFile) {
        Path uiFile = layoutFile.resolveSibling(UI_FILE);
        try {
            return Files.isRegularFile(uiFile) ? UiScale.fromJson(Files.readString(uiFile)) : UiScale.DEFAULT_PERCENT;
        } catch (IOException | IllegalArgumentException unusable) {
            return UiScale.DEFAULT_PERCENT;
        }
    }

    /**
     * Creates the output directory and removes the pictures and index an earlier tour left there, so a rerun into the
     * same directory never mixes old and new pictures. Other files are left alone.
     */
    public static void prepare(Path dir) throws IOException {
        Files.createDirectories(dir);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (Files.isRegularFile(file) && (PICTURE.matcher(name).matches() || name.equals(TourIndex.FILE))) {
                    Files.delete(file);
                }
            }
        }
    }
}
