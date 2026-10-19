package dev.sculptory.fabric.client.editor;

/**
 * Scales the client player's fly speed by the {@link FlySpeed} multiplier, and puts it back when it no longer applies.
 * The <b>base</b> is the player's own speed (vanilla 0.05 in creative, unless the server or another mod set another):
 * <ul>
 *   <li>a new player object (joining, respawning, changing dimension) or a server abilities update ({@link #rebase})
 *       takes the speed the player has then as the base;</li>
 *   <li>a change made on the client while the speed is scaled (vanilla's spectator scroll, another mod) counts as a
 *       change of the scaled speed: the base becomes that speed divided by the multiplier, so it is kept as made and
 *       never multiplied again;</li>
 *   <li>a change while the speed is not scaled becomes the base.</li>
 * </ul>
 * While {@code scaled} (the editor is open, or the player is in creative or spectator), the speed is base ×
 * multiplier; once not (survival, adventure), the base is put back, unless something else has changed the speed since.
 * Only the client's copy is changed: vanilla never sends the fly speed to the server. Pure Java; client thread only.
 */
public final class FlySpeedApplier {
    /** The client player's abilities. */
    public interface Abilities {
        float flySpeed();

        void setFlySpeed(float speed);
    }

    /** The player object last seen (compared by identity), or null. */
    private Object player;
    private boolean rebase = true;
    private float base;
    /** Whether the speed is scaled now: it was set to {@link #applied}, for {@link #appliedMultiplier}. */
    private boolean scaled;
    private float applied;
    private double appliedMultiplier = 1;

    /** The server sent the player's abilities: the speed they hold is the new base. */
    public void rebase() {
        rebase = true;
    }

    /** The base speed (for tests and the play check); meaningful once a player was seen. */
    public float base() {
        return base;
    }

    /**
     * Once per client tick and whenever the multiplier changes. {@code player} identifies the player object (null:
     * no player, nothing to do); {@code abilities} are its abilities.
     */
    public void update(Object player, Abilities abilities, double multiplier, boolean scale) {
        if (player == null || abilities == null) {
            this.player = null;
            rebase = true;
            scaled = false;
            return;
        }
        if (player != this.player) {
            this.player = player;
            rebase = true;
        }
        float current = abilities.flySpeed();
        if (rebase) {
            base = current;
            scaled = false;
            rebase = false;
        } else if (scaled && current != applied) {
            base = appliedMultiplier > 0 ? (float) (current / appliedMultiplier) : current;
        } else if (!scaled) {
            base = current;
        }
        if (scale) {
            float target = (float) (base * multiplier);
            if (current != target) {
                abilities.setFlySpeed(target);
            }
            scaled = true;
            applied = target;
            appliedMultiplier = multiplier;
        } else if (scaled) {
            if (current == applied) {
                abilities.setFlySpeed(base);
            }
            scaled = false;
        }
    }
}
