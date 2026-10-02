package dev.sculptory.server.engine.impl;

import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.server.engine.BuilderOutcome;
import java.util.Set;
import java.util.UUID;

/**
 * Builder mode: the placements and breaks a player makes in normal play with a power on, carried out by the platform
 * (they are the game's own placement and break steps) through the engine's history ({@link EngineHost#builderMode}).
 * Owned by one {@link EngineEditService}; server thread only.
 *
 * @param <P> the platform's player type
 */
public interface BuilderMode<P> {
    /** The powers the player switched on ({@code BuilderPower} bits), kept while they are connected. */
    void powers(P player, int powers);

    /** The powers the player last reported (0 when none or unknown). */
    int powersOf(UUID player);

    /** Re-reads whether the player may use builder mode (op changes) and applies it at once. */
    void permissionsChanged(P player);

    /** One right-click placement, with its mirrored copies; one history step. */
    BuilderOutcome place(P player, C2S.BuilderPlace place);

    /** Breaks of one click or drag; the drag's breaks are one history step when it ends. */
    BuilderOutcome breakBlocks(P player, C2S.BuilderBreak breaks);

    /** The drag ended: its breaks become one history step. */
    void dragEnd(P player, int dragId);

    /** The player's open drag becomes its entry, before an editor edit, undo or redo (history order). */
    void commitDrag(UUID player);

    /** Adds the entries open drags are building, which fluid trails must not forget. */
    void liveEntries(Set<UUID> ids);

    /** Whether the player has a drag open (tests). */
    boolean dragOpen(UUID player);

    /** Whether block (x, y, z) lies within the player's builder reach (tests). */
    boolean withinReach(P player, int x, int y, int z);

    /** Per server tick (idle drags end, powers follow the game mode and the nodes). */
    void tick();

    /** The player left: their drag becomes an entry when {@code keep} (history is saved), and their powers go. */
    void playerLeft(UUID player, boolean keep);

    /** Server stop: open drags become entries when {@code keep} (history is saved). */
    void shutdown(boolean keep);
}
