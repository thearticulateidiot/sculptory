package dev.sculptory.server.engine.impl;

import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.protocol.v2.S2C;
import dev.sculptory.server.config.SculptoryConfig;
import dev.sculptory.server.engine.TinkerService;
import dev.sculptory.server.platform.Platform;
import java.util.function.LongSupplier;

/**
 * The platform side of a running engine: the {@link Platform} plus what the platform keeps for the engine while the
 * server runs (the config in effect, the executor), the chunk tickets jobs hold, and the work the edit service hands to
 * the platform because it is the game's own interaction (builder mode, Tinker, Jump and Through). One per running
 * server (on Fabric, {@code EngineRuntime}).
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

    /**
     * Builder mode for {@code edits} (called once, while {@code edits} is being made), writing into {@code history} and
     * locking against {@code executor}.
     */
    BuilderMode<P> builderMode(EngineEditService<P, W> edits, HistoryService history, EditExecutor<W> executor,
                               LongSupplier clock);

    /** Tinker for {@code edits}: one block or entity changed in place, one history step (called once). */
    TinkerService<P> tinker(EngineEditService<P, W> edits);

    /** Jump or Through: moves the player onto (or through) the block they look at and says where their feet landed. */
    S2C.NavigateResult navigate(P player, C2S.Navigate request);
}
