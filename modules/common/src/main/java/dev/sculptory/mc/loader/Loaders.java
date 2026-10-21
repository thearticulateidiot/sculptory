package dev.sculptory.mc.loader;

import java.util.Objects;

/**
 * The installed loader seams. The loader's common entrypoint installs the {@link ServerLoader} before any other code
 * of the mod runs (on a dedicated server and on a client alike); its client entrypoint installs the client seam
 * ({@code ClientLoaders}, in the client source set). Reading one that is not installed is a programming error.
 */
public final class Loaders {
    private static volatile ServerLoader server;

    private Loaders() {
    }

    public static void installServer(ServerLoader loader) {
        Objects.requireNonNull(loader);
        if (server != null && server != loader) throw new IllegalStateException("a server loader is already installed");
        server = loader;
    }

    public static ServerLoader server() {
        ServerLoader loader = server;
        if (loader == null) throw new IllegalStateException("no server loader installed (the mod initializer runs first)");
        return loader;
    }

    /** The loader's folders, version and mod list; the server seam provides them on both sides. */
    public static LoaderInfo info() {
        return server();
    }
}
