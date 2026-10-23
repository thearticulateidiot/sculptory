package dev.sculptory.server.platform;

import dev.sculptory.core.entity.EntityPlacement;
import dev.sculptory.core.entity.EntitySnapshot;
import dev.sculptory.core.history.EntityState;
import dev.sculptory.core.transform.Orientation;
import java.util.function.Predicate;

/**
 * Places, puts back and removes entities in one world for one job ({@link WorldEntities#placer}), with the job's
 * write options (operator-only data kept or left out) and counters. Server thread only.
 *
 * @param <E> the platform's entity type
 */
public interface EntityPlacer<E> {
    /** What a placement or restore did: the spawned root entity, or none ({@code refused}: by the caller's guard). */
    record Spawn<E>(E entity, boolean refused) {}

    /**
     * Places a clipboard entity at {@code where} (its position after {@code orientation}), turned by it (the exact part
     * as blocks turn, then a free rotation: {@code dev.sculptory.core.entity.EntityTurn}), with new UUIDs, if
     * {@code allowed} accepts it as it would be spawned.
     */
    Spawn<E> place(EntitySnapshot entity, EntityPlacement.Placed where, Orientation orientation, Predicate<E> allowed);

    /**
     * Puts a recorded entity back as it was, with its UUIDs (undo, redo), if {@code allowed} accepts it as it would be
     * spawned.
     */
    Spawn<E> restore(EntityState state, Predicate<E> allowed);

    /**
     * Puts back, with its UUIDs, an entity a history step of this job took away, because what was to stand in its place
     * could not be put there, if {@code allowed} accepts it: a no-op for the entity, so it does not count as placed.
     */
    default Spawn<E> putBack(EntityState state, Predicate<E> allowed) {
        return restore(state, allowed);
    }

    /**
     * Takes back an entity this placer just spawned (with everything riding it) because it cannot be recorded, so no
     * edit leaves an entity undo does not know about; it counts as a failure.
     */
    void takeBack(E root);

    /**
     * {@link #takeBack(Object)} for another reason, which the failure's log line names: an undo or redo put back a
     * hanging entity where it cannot hang any more.
     */
    default void takeBack(E root, String why) {
        takeBack(root);
    }

    /**
     * Removes {@code root} and the passengers whose UUIDs {@code recorded} names (what a step took along or placed with
     * it); other riders, players always, are dismounted and stay.
     */
    void remove(E root, EntityState recorded);

    /** Entities placed or put back. */
    long placed();

    long removed();

    /** Entities whose operator-only data was left out. */
    long stripped();

    /** Entities that could not be placed. */
    long failures();
}
