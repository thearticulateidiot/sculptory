package dev.sculptory.paper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Supplier;

/**
 * The plugin runs only on the Minecraft version it was built for. It is compiled against that version's server
 * internals (Mojang-named NMS), which change between versions, while {@code plugin.yml}'s {@code api-version} only
 * names the Bukkit API level ({@code 1.21}, so Paper would load it on any 1.21.x): on another version it would fail as
 * it starts or, worse, in the middle of an edit. The build writes the version into {@value #RESOURCE}.
 */
final class MinecraftVersionGuard {
    static final String RESOURCE = "sculptory-paper.properties";

    private MinecraftVersionGuard() {
    }

    /** The Minecraft version this jar was built for, from {@value #RESOURCE}. */
    static String builtFor() {
        try (InputStream in = MinecraftVersionGuard.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException(RESOURCE + " is missing from the plugin jar");
            Properties properties = new Properties();
            properties.load(in);
            String version = properties.getProperty("minecraft", "").trim();
            if (version.isEmpty()) throw new IllegalStateException(RESOURCE + " names no Minecraft version");
            return version;
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + RESOURCE, e);
        }
    }

    /**
     * Why the plugin must not run here, reading both versions through the suppliers; empty when it may. A failure to
     * read either (no {@value #RESOURCE} in the jar, a server without Paper's {@code getMinecraftVersion}) refuses too,
     * with one line saying why, rather than leaving it to fail later with a stack trace.
     */
    static Optional<String> refusalFor(Supplier<String> builtFor, Supplier<String> found) {
        try {
            return refusal(builtFor.get(), found.get());
        } catch (RuntimeException | LinkageError e) {
            return Optional.of("Sculptory for Paper could not check the Minecraft version, so it stays disabled: " + e);
        }
    }

    /** Why the plugin must not run on Minecraft {@code found}; empty when it is the version it was built for. */
    static Optional<String> refusal(String builtFor, String found) {
        if (builtFor.equals(found)) return Optional.empty();
        return Optional.of("Sculptory for Paper is built for Minecraft " + builtFor + " only, and this server runs "
                + "Minecraft " + found + ", so it stays disabled (it uses server internals that differ between "
                + "versions). Install the Sculptory build for Minecraft " + found + " if there is one, or run Paper "
                + builtFor + ".");
    }
}
