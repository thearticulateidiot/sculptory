package dev.sculptory.server.platform;

import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.edit.NeighbourShapes;
import dev.sculptory.core.history.TileMatcher;
import dev.sculptory.core.scatter.FeatureCatalog;
import dev.sculptory.core.scatter.ScatterPlanner;
import dev.sculptory.core.state.StateSpace;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The game as the shared engine sees it, for one running server: its thread, its players and worlds, and the tools
 * the engine reads and writes a world with. Each platform implements it once (on Fabric, {@code EngineRuntime}).
 *
 * <p>The platform's own player and world objects enter as type parameters, as in {@code EditService<P>}: the engine
 * keeps and compares them exactly as it did the platform's objects, and asks the platform about them. The tools
 * ({@link #reader}, {@link #writer}, {@link #entities}...) are made per job, stroke or request and hold the platform's
 * objects themselves, so per-cell work calls them directly and allocates nothing more than before.
 *
 * <p>Unless a method says otherwise, call it on the server thread.
 *
 * @param <P> the platform's player type
 * @param <W> the platform's world type
 */
public interface Platform<P, W> {
    // ------------------------------------------------------------------ the server

    /** Whether the caller runs on the server thread. Any thread. */
    boolean isOnThread();

    /** Hands {@code task} to the server thread, as the game's own server executor does. Any thread. */
    void execute(Runnable task);

    /** The server's tick counter. */
    int ticks();

    // ------------------------------------------------------------------ players

    UUID id(P player);

    /** The player's account name. */
    String name(P player);

    /** The world the player is in. */
    W world(P player);

    /** The online player with this id, or {@code null}. */
    P online(UUID id);

    /** The online player with this name (as the game matches names), or {@code null}. */
    P online(String name);

    /** The account the server's own cache holds for {@code id}; never asks elsewhere. Any thread. */
    Optional<Profile> knownProfile(UUID id);

    /**
     * The account named {@code name}, from the server's cache or, for a name it never saw, the account service (which
     * may take a moment). Off the server thread.
     */
    Optional<Profile> lookUpProfile(String name);

    /** Acknowledges the player's block-prediction sequence {@code sequence} the game's own way; ignores negatives. */
    void acknowledge(P player, int sequence);

    // ------------------------------------------------------------------ worlds

    /** The server's worlds. */
    Iterable<W> worlds();

    /** The world's id, as history entries and scatter plans name it ("minecraft:overworld"). */
    String worldId(W world);

    /** An object equal for the same world and only for it, whatever its instance (section locks key on it). */
    Object worldKey(W world);

    /** Whether {@code world} is still one of the server's worlds (a dimension mod may unload one). */
    boolean exists(W world);

    /** The lowest block y. */
    int bottomY(W world);

    /** One above the highest block y. */
    int topY(W world);

    /** The lowest section y. */
    int bottomSection(W world);

    /** One above the highest section y. */
    int topSection(W world);

    /** Whether the cell is inside the build height and the horizontal world limit. */
    boolean inBuildLimit(W world, int x, int y, int z);

    /** Whether some cell of {@code box} is inside the build height and the horizontal world limit. */
    boolean intersectsBuildLimit(W world, Box box);

    /** Whether the chunk is loaded (fully, as edits need it); never loads it. */
    boolean chunkLoaded(W world, int cx, int cz);

    /** Whether column (x, z) is inside the world border now. */
    boolean insideBorder(W world, int x, int z);

    /** The world border's bounds now. */
    BorderBounds border(W world);

    // ------------------------------------------------------------------ what the engine edits with

    /** The block states of this server (state handles everywhere in the engine are this space's). Any thread. */
    StateSpace states();

    PlatformPermissions<P, W> permissions();

    FluidTrailHook<W> fluidTrails();

    /** A reader over {@code world}'s live chunks, for one job, stroke or request. */
    LiveReader reader(W world);

    /** A writer into {@code world} for one job, stroke or request. */
    WorldWriter writer(W world, WriteOptions options);

    /**
     * Sends the bulk jobs' writes in {@code world} to the players watching them; {@code predicting} names players who
     * may hold unacknowledged predicted block changes (they get per-block updates).
     */
    ClientUpdates clientUpdates(W world, Predicate<UUID> predicting);

    /** Queues the light checks section (sx, sy, sz) needs once written; returns how many it queued. */
    int relightSection(W world, int sx, int sy, int sz);

    /** Compares block-entity contents as the game holds them (undo keeps a chest filled since). */
    TileMatcher tileMatcher(W world);

    /** The game's neighbour updates over the live world, without physics (Update blocks). */
    NeighbourShapes neighbourShapes(W world);

    /** The world's entities. */
    WorldEntities<?> entities(W world);

    /** What the game says about each entity type, learned with {@code world} if not yet learned. */
    EntityRules entityRules(W world);

    /** The entity type rules learned so far (before, every type is operator-only and never placed). Any thread. */
    EntityRules entityRules();

    /** Whether the game knows an entity type of this id. Any thread. */
    boolean knownEntityType(String typeId);

    /** An entity type's width and height, for previews. Any thread. */
    float[] entitySize(String typeId);

    /** Whether the tile was captured from a world by this server (then trusted where its source was). Any thread. */
    boolean serverCaptured(BlockEntityData tile);

    /** A server-captured tile as untrusted content (a copy of what the player could not write); others as they are. */
    BlockEntityData untrusted(BlockEntityData tile);

    /**
     * Grows the scatter's trees and features in {@code world} without changing it.
     *
     * @param features per scatter source index, its catalog entry, or {@code null} for a held or block source
     * @param survive whether trees grow only where their sapling could stand
     */
    ScatterPlanner.Grower featureGrower(W world, List<FeatureCatalog.FeatureDef> features, boolean survive);

    /** How far from its spot a growth of {@code feature} may write horizontally. */
    int featureReach(FeatureCatalog.FeatureDef feature);

    /** How far above its spot a growth of {@code feature} may write. */
    int featureAbove(FeatureCatalog.FeatureDef feature);

    /** How far below its spot any growth may write. */
    int featureBelow();

    /**
     * The scatter's survival check for block variants in {@code world}: a block, turned as it will be placed, must be
     * one the game would keep there. Never loads chunks.
     *
     * @param blockStates per source index, the block variant's lower state, or -1 for a clipboard or asset
     */
    ScatterPlanner.SurvivalCheck survivalCheck(W world, int[] blockStates);
}
