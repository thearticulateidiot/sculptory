package dev.sculptory.mc.loader;

import java.util.Objects;

/** The installed {@link ClientLoader}: the loader's client entrypoint installs it before the editor starts. */
public final class ClientLoaders {
    private static volatile ClientLoader client;

    private ClientLoaders() {
    }

    public static void install(ClientLoader loader) {
        Objects.requireNonNull(loader);
        if (client != null && client != loader) throw new IllegalStateException("a client loader is already installed");
        client = loader;
    }

    public static ClientLoader get() {
        ClientLoader loader = client;
        if (loader == null) throw new IllegalStateException("no client loader installed (the client initializer runs first)");
        return loader;
    }
}
