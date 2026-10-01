package dev.sculptory.fabric.client.editor.demo;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The dev-only switch of the scripted demo: {@code -Dsculptory.demo=<absolute output dir>} ({@code -PbsDemo=<dir>} on
 * {@code runClient}, or {@code scripts/playtest.ps1 -Demo}). Without it nothing of the demo is loaded: the client
 * entrypoint reads the property through the compile-time constant {@link #PROPERTY}.
 *
 * @param dir where {@code report.txt} goes
 * @param from the step to start at (its number or id; blank: the first), for retakes ({@code -DemoFrom})
 * @param maximize whether to maximise the game window once the world is joined ({@code -DemoMaximize})
 * @param autoStartSeconds start this many seconds after "Press Enter" shows, without Enter (0: wait for Enter); for
 *        unattended runs ({@code -DemoAutoStart})
 * @param captions whether the caption bar shows during the run ({@code -NoCaptions} turns it off, for a
 *        voice-over; see {@link DemoPacing})
 */
public record DemoConfig(Path dir, String from, boolean maximize, int autoStartSeconds, boolean captions) {
    public static final String PROPERTY = "sculptory.demo";
    public static final String FROM_PROPERTY = PROPERTY + ".from";
    public static final String MAXIMIZE_PROPERTY = PROPERTY + ".maximize";
    public static final String AUTO_START_PROPERTY = PROPERTY + ".autoStart";
    public static final String CAPTIONS_PROPERTY = PROPERTY + ".captions";

    public DemoConfig {
        if (!dir.isAbsolute()) {
            throw new IllegalArgumentException("-D" + PROPERTY + " needs an absolute directory, got '" + dir + "'");
        }
        dir = dir.normalize();
        from = from == null ? "" : from.strip();
        if (autoStartSeconds < 0) {
            throw new IllegalArgumentException("-D" + AUTO_START_PROPERTY + " is a number of seconds, got "
                    + autoStartSeconds);
        }
    }

    /**
     * The configuration the properties give, or empty when the demo is off ({@link #PROPERTY} absent or blank).
     * Captions are on unless {@code captions} is {@code "false"}.
     */
    public static Optional<DemoConfig> of(String dir, String from, String maximize, String autoStart,
            String captions) {
        if (dir == null || dir.isBlank()) {
            return Optional.empty();
        }
        int seconds = 0;
        if (autoStart != null && !autoStart.isBlank()) {
            try {
                seconds = Integer.parseInt(autoStart.strip());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("-D" + AUTO_START_PROPERTY + " is a number of seconds, got '"
                        + autoStart + "'");
            }
        }
        boolean withCaptions = captions == null || captions.isBlank() || !captions.strip().equalsIgnoreCase("false");
        return Optional.of(new DemoConfig(Path.of(dir.strip()), from, Boolean.parseBoolean(maximize), seconds,
                withCaptions));
    }

    /** From the system properties. */
    public static Optional<DemoConfig> fromSystem() {
        return of(System.getProperty(PROPERTY), System.getProperty(FROM_PROPERTY),
                System.getProperty(MAXIMIZE_PROPERTY), System.getProperty(AUTO_START_PROPERTY),
                System.getProperty(CAPTIONS_PROPERTY));
    }

    public DemoPacing pacing() {
        return captions ? DemoPacing.WITH_CAPTIONS : DemoPacing.WITHOUT_CAPTIONS;
    }
}
