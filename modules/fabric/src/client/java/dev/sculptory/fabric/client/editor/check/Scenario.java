package dev.sculptory.fabric.client.editor.check;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One scripted scenario of the play check: what it does in one line, whether it belongs to the visual subset (the
 * render-mod runs) and its script, which drives the client through {@link CheckRun}.
 *
 * @param area the fixture area it works in (singleplayer), or null for one that needs none
 */
public record Scenario(String name, String description, boolean visual, String area, Body body) {
    private static final Pattern NAME = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    /** The script. Throwing stops the scenario; it is then recorded as failed with the reason. */
    @FunctionalInterface
    public interface Body {
        void run(CheckRun run) throws Exception;
    }

    public Scenario {
        Objects.requireNonNull(description);
        Objects.requireNonNull(body);
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Scenario names are lower-case words joined by '-': " + name);
        }
    }

    public static Scenario of(String name, String area, String description, Body body) {
        return new Scenario(name, description, false, area, body);
    }

    public static Scenario visual(String name, String area, String description, Body body) {
        return new Scenario(name, description, true, area, body);
    }
}
