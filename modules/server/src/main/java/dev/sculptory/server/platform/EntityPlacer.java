package dev.sculptory.server.platform;

import dev.sculptory.core.entity.EntityPlacement;
import dev.sculptory.core.entity.EntitySnapshot;
import dev.sculptory.core.history.EntityState;
import dev.sculptory.core.transform.Transform;
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
     * Places a clipboard entity at {@code where} (its position after {@code t}), turned by {@code t}, with new UUIDs,
     * if {@code allowed} accepts it as it would be spawned.
     */
    Spawn<E> place(EntitySnapshot entity, EntityPlacement.Placed where, Transform t, Predicate<E> allowed);

    /**
     * Puts a recorded entity back as it was, with its UUIDs (undo, redo), if {@code allowed} accepts it as it would be
     * spawned.
     */
    Spawn<E> restore(EntityState state, Predicate<E> allowed);

    /**
     * Takes back an entity this placer just spawned (with everything riding it) because it cannot be recorded, so no
     * edit leaves an entity undo does not know about; it counts as a failure.
     */
    void takeBack(E root);

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
