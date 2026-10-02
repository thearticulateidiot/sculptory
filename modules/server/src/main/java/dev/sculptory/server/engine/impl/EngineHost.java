package dev.sculptory.server.engine.impl;

import dev.sculptory.server.config.SculptoryConfig;
import dev.sculptory.server.platform.Platform;

/**
 * The platform side of a running engine: the {@link Platform} plus what the platform keeps for the engine while the
 * server runs (the config in effect, the executor) and the chunk tickets jobs hold. One per running server (on Fabric,
 * {@code EngineRuntime}).
 *
 * @param <P> the platform's player type
 * @param <W> the platform's world type
 */
public interface EngineHost<P, W> extends Platform<P, W> {
    /** The settings in effect (a config reload replaces them; checks read them when they run). */
    SculptoryConfig config();

    /** The server's executor. */
    EditExecutor<W> executor();

    /**
     * The chunk tickets jobs hold in {@code world} (radius 0, expiring unless refreshed): adding an equal ticket again
     * restarts its expiry timer.
     */
    TicketWindow.Tickets chunkTickets(W world);
}
