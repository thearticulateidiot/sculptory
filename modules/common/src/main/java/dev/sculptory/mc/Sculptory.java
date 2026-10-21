package dev.sculptory.mc;

import dev.sculptory.mc.loader.Loaders;
import dev.sculptory.server.ServerLog;
import org.slf4j.Logger;

/** The mod's identity on the game side: its id, log and build id. */
public final class Sculptory {
    public static final String MOD_ID = "sculptory";
    public static final Logger LOG = ServerLog.LOG;
    private static volatile String buildId;

    private Sculptory() {
    }

    /**
     * This build's id: the mod version from the mod's metadata. A release build sets it to {@code mod_version}
     * (e.g. {@code 0.3.0-alpha}); any other build to {@code mod_version} plus the commit it was made from
     * (e.g. {@code 0.3.0-alpha+6a043a85}, {@code .dirty} for uncommitted changes; root build.gradle). Client and server
     * exchange it at the handshake. {@code "unknown"} if the loader has no such mod.
     */
    public static String buildId() {
        String id = buildId;
        if (id == null) {
            id = Loaders.info().modVersion();
            buildId = id;
        }
        return id;
    }
}
