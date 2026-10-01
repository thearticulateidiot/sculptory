package dev.sculptory.fabric.client.editor.check;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The dev-only switch of the play check: {@code -Dsculptory.check=<absolute output dir>} ({@code -PbsCheck=<dir>} on
 * {@code runClient}, or {@code scripts/playtest.ps1 -Check}). Without it nothing of the check is loaded: the client
 * entrypoint reads the property through the compile-time constant {@link #PROPERTY}.
 *
 * @param dir where the pictures and {@code report.txt} go
 * @param role which script this client plays: alone in singleplayer, or player A (op) or B (not op) of the two-client
 *        check on the test server
 * @param suite everything, or only the visual scenarios (the render-mod runs)
 * @param only scenario name prefixes to run (empty: all of the suite)
 * @param label what the report calls this run's renderer ("vanilla", "Sodium", "Iris + <pack>")
 */
public record CheckConfig(Path dir, Role role, Suite suite, List<String> only, String label) {
    public static final String PROPERTY = "sculptory.check";
    public static final String ROLE_PROPERTY = PROPERTY + ".role";
    public static final String SUITE_PROPERTY = PROPERTY + ".suite";
    public static final String ONLY_PROPERTY = PROPERTY + ".only";
    public static final String LABEL_PROPERTY = PROPERTY + ".label";

    public enum Role {
        /** Singleplayer: fixtures on the integrated server, the world read on both sides. */
        SOLO,
        /** The op player on the test server: builds the fixture with commands and edits. */
        A,
        /** The second, non-op player: watches A's edits and tries its own. */
        B
    }

    public enum Suite {
        /** Every scenario of the role. */
        FULL,
        /** The scenarios whose pictures show rendering: ghosts, outlines, opacity, windows (Sodium, Iris). */
        VISUAL
    }

    public CheckConfig {
        if (!dir.isAbsolute()) {
            throw new IllegalArgumentException("-D" + PROPERTY + " needs an absolute directory, got '" + dir + "'");
        }
        dir = dir.normalize();
        only = List.copyOf(only);
    }

    /**
     * The configuration the properties give, or empty when the check is off ({@link #PROPERTY} absent or blank).
     *
     * @throws IllegalArgumentException when a value is malformed
     */
    public static Optional<CheckConfig> of(String dir, String role, String suite, String only, String label) {
        if (dir == null || dir.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new CheckConfig(Path.of(dir.strip()), parse(Role.class, role, Role.SOLO, ROLE_PROPERTY),
                parse(Suite.class, suite, Suite.FULL, SUITE_PROPERTY), names(only),
                label == null || label.isBlank() ? "vanilla" : label.strip()));
    }

    /** From the system properties. */
    public static Optional<CheckConfig> fromSystem() {
        return of(System.getProperty(PROPERTY), System.getProperty(ROLE_PROPERTY), System.getProperty(SUITE_PROPERTY),
                System.getProperty(ONLY_PROPERTY), System.getProperty(LABEL_PROPERTY));
    }

    /** Whether a scenario of this name runs: in the suite, and matching one of {@link #only} when that is set. */
    public boolean runs(String scenario, boolean visual) {
        if (suite == Suite.VISUAL && !visual) {
            return false;
        }
        return only.isEmpty() || only.stream().anyMatch(scenario::startsWith);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, E fallback, String property) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("-D" + property + " must be one of "
                    + Arrays.toString(type.getEnumConstants()).toLowerCase(Locale.ROOT) + ", got '" + value + "'");
        }
    }

    private static List<String> names(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::strip).filter(name -> !name.isEmpty()).toList();
    }
}
