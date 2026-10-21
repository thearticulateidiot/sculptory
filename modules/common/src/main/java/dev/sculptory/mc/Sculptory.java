package dev.sculptory.mc;

import dev.sculptory.mc.loader.Loaders;
import dev.sculptory.server.EngineVersion;
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
     * The build id client and server exchange at the handshake: the <em>engine's</em> ({@link EngineVersion}:
     * {@code 0.5.0} for a release, {@code 0.5.0-dev+6a043a85} for a dev build, {@code .dirty} with uncommitted
     * changes). Every product built from the same engine commit shares it, so a Fabric client on a NeoForge or Paper
     * server with the same engine is "the same build": what each product calls itself is {@link #productBuildId}.
     */
    public static String buildId() {
        return EngineVersion.get();
    }

    /**
     * This product's own build id, from the mod's metadata: its version plus the Minecraft version it was built for
     * ({@code 0.5.0+1.21.1} for a release; a dev build adds the commit, {@code 0.5.0-dev+1.21.1.6a043a85}; root
     * build.gradle). Shown in logs and by {@code /sculptory version}; {@code "unknown"} if the loader has no such mod.
     */
    public static String productBuildId() {
        String id = buildId;
        if (id == null) {
            id = Loaders.info().modVersion();
            buildId = id;
        }
        return id;
    }
}
