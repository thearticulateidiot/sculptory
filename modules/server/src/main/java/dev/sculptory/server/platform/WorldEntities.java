package dev.sculptory.server.platform;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.entity.EntitySnapshot;
import dev.sculptory.core.history.EntityState;
import dev.sculptory.core.region.Region;
import java.util.List;
import java.util.UUID;

/**
 * The entities of one world as the engine reads and changes them ({@link Platform#entities}). An entity belongs to a
 * block ({@link #cell}): a hanging entity to its attachment block, any other to the block holding its position. Only
 * root entities (riding nothing) are taken; their passengers ride along when the filter takes them. Chunk columns are
 * packed like {@code ColumnPlan.pack}. Server thread only.
 *
 * @param <E> the platform's entity type
 */
public interface WorldEntities<E> {
    /** Whether chunk (cx, cz) is loaded with its entities (the game may load entities after the chunk). */
    boolean loaded(int cx, int cz);

    /** The first of the packed chunk {@code columns} ("cx,cz") whose entities are not loaded, or {@code null}. */
    String firstUnloaded(long[] columns);

    /**
     * The root entities {@code filter} takes whose cell is in {@code region}, among the loaded ones, looking only in
     * the chunk {@code columns} (as {@code EntityColumns.of} gives them), in a stable order (by UUID).
     */
    List<E> inRegion(Region region, EntityFilter filter, long[] columns);

    /**
     * The root entities {@code filter} takes whose cell is in {@code region}, among those stored in chunk (cx, cz), in a
     * stable order (by UUID).
     */
    List<E> inRegion(Region region, EntityFilter filter, int cx, int cz);

    /** The entity with this UUID in this world, or {@code null}. */
    E find(UUID id);

    UUID id(E entity);

    /** Whether the entity was removed from the world (killed, unloaded, discarded). */
    boolean removed(E entity);

    /** Whether the entity rides another. */
    boolean riding(E entity);

    /** The block the entity belongs to. */
    BlockPos cell(E entity);

    /** The block holding the entity's position. */
    BlockPos blockPos(E entity);

    /**
     * Whether {@code entity} hangs on a block (an item frame, a painting) and would not stay where it is now: its wall is
     * gone or its own space is filled. The game drops such an entity off, with its item, at its next check; an edit that
     * took the support takes the entity along instead. Leash knots are left to the game (removing one drops the lead of
     * the mob tied to it anyway).
     */
    boolean hangsUnsupported(E entity);

    /**
     * Whether every chunk holding a block {@code entity} stands in or hangs on is fully loaded, so
     * {@link #hangsUnsupported} can be asked without loading one (the game's own test reads those blocks). An entity
     * whose support is not all loaded is left to the game.
     */
    boolean supportLoaded(E entity);

    /**
     * The blocks {@code entity}'s box lies in and, for an item frame or painting, the wall blocks right behind them
     * (the box moved one block away from its face): the blocks whose change takes its support away. Once an edit writes
     * none of them again, it cannot give the entity its support back. Not every block the game's collision test reads
     * ({@link #supportLoaded} looks one block further).
     */
    Box supportBox(E entity);

    /** How many entities taking {@code entity} with {@code filter} takes: itself and the passengers the filter takes. */
    int takenCount(E entity, EntityFilter filter);

    /**
     * The entity and the passengers {@code filter} takes as clipboard data, positioned relative to {@code min};
     * {@code trusted} marks data captured where the player may write. {@code null} for an entity the game does not
     * save.
     *
     * @throws IllegalArgumentException when its data is too large
     */
    EntitySnapshot snapshot(E entity, EntityFilter filter, BlockPos min, boolean trusted);

    /** The entity as history records it, with the passengers {@code filter} takes, or {@code null} when it cannot. */
    EntityState state(E entity, EntityFilter filter);

    /** The entity as it is now, with all its passengers (players are never saved), or {@code null} when it cannot. */
    EntityState live(E entity);

    /** The entity's type id ("minecraft:painting"). */
    String typeId(E entity);

    /** A placer for one job in this world, writing with {@code options}. */
    EntityPlacer<E> placer(WriteOptions options);
}
