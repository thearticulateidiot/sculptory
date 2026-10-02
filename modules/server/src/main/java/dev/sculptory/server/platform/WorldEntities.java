package dev.sculptory.server.platform;

import dev.sculptory.core.BlockPos;
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
