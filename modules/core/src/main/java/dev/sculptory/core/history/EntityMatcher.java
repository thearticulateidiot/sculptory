package dev.sculptory.core.history;

import dev.sculptory.core.entity.EntityNbt;
import dev.sculptory.core.nbt.NbtCompound;
import java.io.IOException;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Decides whether the entity the world holds now is still the one a step recorded, unchanged: the entity counterpart of
 * {@link TileMatcher}. Undo and redo remove or replace an entity only while it matches what the step left there; a
 * changed one is kept and counted with the kept blocks ({@link EntityHistory}).
 */
@FunctionalInterface
public interface EntityMatcher {
    /**
     * Whether {@code live} (the entity with the recorded UUID as the world holds it now, saved the same way) matches
     * {@code recorded}.
     */
    boolean matches(EntityState recorded, EntityState live);

    /**
     * The matcher for a step whose two sides this one cannot tell apart, because all the step changed is where the entity
     * stands or looks (a Tinker move or turn): it also compares
     * {@link EntityNbt#PLACEMENT_VOLATILE_KEYS}, so the undo of such a step is not taken as already done. Matchers that
     * compare everything return themselves.
     */
    default EntityMatcher placementAware() {
        return this;
    }

    /** Same type and the same NBT bytes (tests). */
    EntityMatcher EXACT = (recorded, live) ->
            recorded.typeId().equals(live.typeId()) && Arrays.equals(recorded.nbt(), live.nbt());

    /**
     * The rule undo and redo use: the same type and the same NBT once {@link EntityNbt#VOLATILE_KEYS} are left out (at
     * the top and in every passenger), compared as maps (key order and the order the game wrote them in do not matter),
     * float values within {@link EntityNbt#FLOAT_TOLERANCE} (a display recomputes its rotation each time it loads).
     * Data that does not decode counts as changed. Each recorded side is decoded once per matcher; make one per undo or
     * redo. Its {@link #placementAware()} form leaves where the entity stands and looks in.
     */
    static EntityMatcher ignoringVolatile() {
        return new Comparing(EntityNbt::withoutVolatile, true);
    }

    /** Compares the comparable forms {@code form} makes of both sides, each recorded side's made once. */
    final class Comparing implements EntityMatcher {
        private final Function<NbtCompound, NbtCompound> form;
        private final boolean volatilePlacement;
        private final Map<EntityState, NbtCompound> recordedForms = new IdentityHashMap<>();
        private Comparing placementAware;

        private Comparing(Function<NbtCompound, NbtCompound> form, boolean volatilePlacement) {
            this.form = form;
            this.volatilePlacement = volatilePlacement;
        }

        @Override
        public boolean matches(EntityState recorded, EntityState live) {
            if (!recorded.typeId().equals(live.typeId())) return false;
            NbtCompound expected = recordedForms.get(recorded);
            if (expected == null && !recordedForms.containsKey(recorded)) {
                expected = comparable(recorded);
                recordedForms.put(recorded, expected);
            }
            NbtCompound actual = comparable(live);
            return expected != null && actual != null && EntityNbt.nearlyEqual(expected, actual);
        }

        @Override
        public EntityMatcher placementAware() {
            if (!volatilePlacement) return this;
            if (placementAware == null) placementAware = new Comparing(EntityNbt::withoutVolatileKeepingPlacement, false);
            return placementAware;
        }

        private NbtCompound comparable(EntityState state) {
            try {
                return form.apply(EntityNbt.decode(state.nbt()));
            } catch (IOException undecodable) {
                return null;
            }
        }
    }
}
