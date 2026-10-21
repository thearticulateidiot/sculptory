package dev.sculptory.neoforge.gametest;

import dev.sculptory.mc.gametest.GameTestLoader;
import java.util.Objects;

/**
 * The {@link GameTestLoader} on NeoForge. Permission answers are kept here and read by {@link TestPermissionHandler},
 * the permission handler the GameTest runs select in {@code config/neoforge-server.toml}, where a permissions mod's
 * handler would answer {@code PermissionAPI.getPermission}.
 */
final class NeoForgeGameTestLoader implements GameTestLoader {
    static final NeoForgeGameTestLoader INSTANCE = new NeoForgeGameTestLoader();

    private volatile PermissionAnswerer answerer;

    private NeoForgeGameTestLoader() {
    }

    @Override
    public void answerPermissions(PermissionAnswerer permissionAnswerer) {
        Objects.requireNonNull(permissionAnswerer);
        if (answerer != null && answerer != permissionAnswerer) {
            throw new IllegalStateException("permission answers are already installed");
        }
        answerer = permissionAnswerer;
    }

    /** The installed answers, or null before a test installed them. */
    PermissionAnswerer answerer() {
        return answerer;
    }
}
