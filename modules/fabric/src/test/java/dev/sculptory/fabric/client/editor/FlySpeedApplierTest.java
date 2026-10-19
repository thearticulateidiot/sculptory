package dev.sculptory.fabric.client.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** How the fly speed multiplier is applied to the client player's abilities, and put back. */
class FlySpeedApplierTest {
    private static final float VANILLA = 0.05f;

    /** A player's abilities, counting writes. */
    private static final class Abilities implements FlySpeedApplier.Abilities {
        float speed = VANILLA;
        int writes;

        @Override
        public float flySpeed() {
            return speed;
        }

        @Override
        public void setFlySpeed(float value) {
            speed = value;
            writes++;
        }
    }

    private final FlySpeedApplier applier = new FlySpeedApplier();
    private final Object player = new Object();
    private final Abilities abilities = new Abilities();

    @Test
    void creativeFlightIsScaledAndSurvivalGetsTheOwnSpeedBack() {
        applier.update(player, abilities, 4, true);
        assertEquals(VANILLA * 4, abilities.speed);
        applier.update(player, abilities, 4, true);
        applier.update(player, abilities, 4, true);
        assertEquals(1, abilities.writes, "written once, not every tick");
        applier.update(player, abilities, 2, true);
        assertEquals(VANILLA * 2, abilities.speed, "a new multiplier applies to the own speed, not the scaled one");
        applier.update(player, abilities, 2, false);
        assertEquals(VANILLA, abilities.speed, "survival: vanilla again");
        int writes = abilities.writes;
        applier.update(player, abilities, 2, false);
        assertEquals(writes, abilities.writes, "nothing more to put back");
        applier.update(player, abilities, 2, true);
        assertEquals(VANILLA * 2, abilities.speed, "back in creative");
    }

    @Test
    void theServersSpeedIsTheNewOwnSpeed() {
        applier.update(player, abilities, 4, true);
        // A game mode change or a plugin's fly speed: the server sends the abilities.
        abilities.speed = 0.1f;
        applier.rebase();
        applier.update(player, abilities, 4, true);
        assertEquals(0.4f, abilities.speed, 1e-6);
        applier.update(player, abilities, 4, false);
        assertEquals(0.1f, abilities.speed);
    }

    @Test
    void aChangeMadeOnTheClientWhileScaledIsKeptAndNeverCompounded() {
        applier.update(player, abilities, 4, true);
        // Vanilla's spectator scroll: one notch down from the scaled 0.2.
        abilities.speed = 0.195f;
        applier.update(player, abilities, 4, true);
        assertEquals(0.195f, abilities.speed, "kept as made");
        applier.update(player, abilities, 4, true);
        assertEquals(0.195f, abilities.speed, "and not multiplied again");
        applier.update(player, abilities, 1, true);
        assertEquals(0.195f / 4, abilities.speed, 1e-6);
    }

    @Test
    void aChangeWhileNotScaledIsTheOwnSpeed() {
        applier.update(player, abilities, 2, false);
        abilities.speed = 0.08f;
        applier.update(player, abilities, 2, false);
        assertEquals(0.08f, abilities.speed, "not scaled in survival");
        applier.update(player, abilities, 2, true);
        assertEquals(0.16f, abilities.speed, 1e-6);
    }

    @Test
    void aSpeedChangedByAnotherModIsNotOverwrittenWhenScalingStops() {
        applier.update(player, abilities, 4, true);
        abilities.speed = 0.3f;
        applier.update(player, abilities, 4, false);
        assertEquals(0.3f, abilities.speed);
    }

    @Test
    void aNewPlayerObjectStartsFromItsOwnSpeed() {
        applier.update(player, abilities, 4, true);
        Abilities respawned = new Abilities();
        applier.update(new Object(), respawned, 4, true);
        assertEquals(VANILLA * 4, respawned.speed, "not 4 × the old scaled speed");
        applier.update(null, null, 4, true);
        Abilities rejoined = new Abilities();
        applier.update(player, rejoined, 4, true);
        assertEquals(VANILLA * 4, rejoined.speed);
    }
}
