package dev.sculptory.fabric.client.world;

import dev.sculptory.core.state.StateSpace;
import dev.sculptory.fabric.SculptoryMod;
import java.util.Objects;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.CommonLifecycleEvents;

/**
 * The client's {@link StateSpace} for the current connection, as a {@link Supplier} for {@code ClientNet} and the
 * editor backend. {@link #get()} is {@code null} outside a world, or when building failed.
 *
 * <ul>
 *   <li><b>Join:</b> built afresh on every join. Handles are raw block-state ids, and Fabric registry sync
 *       remaps those during the configuration phase that precedes each join, so a space is never reused.</li>
 *   <li><b>Tag reload</b> (the server's {@code /reload}): rebuilt, since some flags come from block tags. Raw ids
 *       do not change, so if that rebuild fails the previous space stays.</li>
 *   <li><b>Disconnect:</b> cleared.</li>
 * </ul>
 *
 * <p>Checked against yarn 1.21.1 and Fabric API 0.116.4: {@code ClientPlayConnectionEvents.JOIN} fires at the end
 * of {@code onGameJoin}, after registry sync (a configuration-phase receiver) and after the static registries'
 * tags were bound ({@code ClientTagLoader.load}, which also fires {@code TAGS_LOADED} with {@code client = true}).
 * The lifecycle methods are plain Java so they are tested without Minecraft. Render thread only.
 */
public final class ClientStateSpaces implements Supplier<StateSpace> {
    private final Supplier<? extends StateSpace> factory;
    private boolean joined;
    private StateSpace current;

    /** @param factory builds a space from the current registries and tags; may throw */
    public ClientStateSpaces(Supplier<? extends StateSpace> factory) {
        this.factory = Objects.requireNonNull(factory);
    }

    /**
     * Creates the lifecycle and registers it with the connection and tag events. Call before
     * {@code ClientNet.install}, so a new space is in place before the handshake runs on the same join event.
     */
    public static ClientStateSpaces install(Supplier<? extends StateSpace> factory) {
        ClientStateSpaces spaces = new ClientStateSpaces(factory);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> spaces.onJoin());
        // DISCONNECT can fire on the network thread.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(spaces::onDisconnect));
        CommonLifecycleEvents.TAGS_LOADED.register((registries, client) -> {
            if (client) spaces.onTagsReloaded();
        });
        return spaces;
    }

    /** The space of the current connection, or {@code null}. */
    @Override
    public StateSpace get() {
        return current;
    }

    /** Whether a world is joined (even if its space failed to build). */
    public boolean joined() {
        return joined;
    }

    /** Joined a world (or rejoined after reconfiguration): drops the old space and builds a new one. */
    public void onJoin() {
        joined = true;
        current = null;
        current = build("joining a world");
    }

    /** The client received new tags. Rebuilds while in a world; outside one, the next join builds anyway. */
    public void onTagsReloaded() {
        if (!joined) return;
        StateSpace rebuilt = build("a tag reload");
        if (rebuilt != null) current = rebuilt;
    }

    /** Left the world. */
    public void onDisconnect() {
        joined = false;
        current = null;
    }

    private StateSpace build(String why) {
        long started = System.nanoTime();
        try {
            StateSpace space = Objects.requireNonNull(factory.get(), "the state-space factory returned null");
            SculptoryMod.LOG.info("Sculptory: built the client block-state space ({} states) after {} in {} ms",
                    space.size(), why, (System.nanoTime() - started) / 1_000_000);
            return space;
        } catch (RuntimeException | LinkageError e) {
            SculptoryMod.LOG.error("Sculptory: could not build the client block-state space after {}; "
                    + "editing is unavailable until the next join", why, e);
            return null;
        }
    }
}
