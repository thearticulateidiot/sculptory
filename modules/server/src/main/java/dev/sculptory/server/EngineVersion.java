package dev.sculptory.server;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * The engine's build id, written into this module at build time ({@code engine.properties}, from the root build's
 * engine build id: {@code sculptory_engine_version}, plus the commit for a dev build). Every product built from the
 * same engine commit shares it, so client and server compare it at the handshake: a Fabric client on a NeoForge or
 * Paper server with the same engine is the same build as far as editing is concerned, whatever each product's own
 * version is.
 */
public final class EngineVersion {
    public static final String UNKNOWN = "unknown";
    private static final String RESOURCE = "/dev/sculptory/server/engine.properties";
    private static volatile String cached;

    private EngineVersion() {
    }

    /** The engine build id, or {@value #UNKNOWN} when the resource is missing (a build without processResources). */
    public static String get() {
        String id = cached;
        if (id == null) {
            id = read();
            cached = id;
        }
        return id;
    }

    private static String read() {
        try (InputStream in = EngineVersion.class.getResourceAsStream(RESOURCE)) {
            if (in == null) return UNKNOWN;
            Properties properties = new Properties();
            properties.load(in);
            String version = properties.getProperty("version", "").trim();
            // An unexpanded placeholder (the resource copied without processing) is as good as none.
            return version.isEmpty() || version.contains("${") ? UNKNOWN : version;
        } catch (IOException | RuntimeException e) {
            return UNKNOWN;
        }
    }
}
