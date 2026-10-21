package dev.sculptory.mc.gametest;

import java.util.Objects;

/** The installed {@link GameTestLoader}: the loader's test mod installs it when it starts, before any test runs. */
public final class GameTestLoaders {
    private static volatile GameTestLoader loader;

    private GameTestLoaders() {
    }

    public static void install(GameTestLoader gameTestLoader) {
        Objects.requireNonNull(gameTestLoader);
        if (loader != null && loader != gameTestLoader) {
            throw new IllegalStateException("a GameTest loader is already installed");
        }
        loader = gameTestLoader;
    }

    public static GameTestLoader get() {
        GameTestLoader installed = loader;
        if (installed == null) throw new IllegalStateException("no GameTest loader installed (the test mod does it)");
        return installed;
    }
}
