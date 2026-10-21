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
     * This build's id: the product's version from the mod's metadata, its version plus the Minecraft version it was
     * built for ({@code 0.5.0+1.21.1} for a release; a dev build adds the commit, {@code 0.5.0-dev+1.21.1.6a043a85},
     * {@code .dirty} for uncommitted changes; root build.gradle). Client and server exchange it at the handshake.
     * {@code "unknown"} if the loader has no such mod.
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
