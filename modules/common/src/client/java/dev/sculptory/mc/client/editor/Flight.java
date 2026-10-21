package dev.sculptory.mc.client.editor;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * When the {@link FlySpeed} multiplier applies, through one {@link FlySpeedApplier}: while the editor is open it applies
 * whatever the game mode ({@link #editorApplies}, from the editor's platform); otherwise each client tick
 * ({@link #tick}) applies it in creative and spectator and puts the player's own speed back in the other game modes.
 * Whether the editor is open is read live ({@code editing}, the editor mode's {@code isEditing}), so however the editor
 * ends (also from a suspended state, where it hands nothing back) the next tick follows the game mode again. The
 * Minecraft side is {@code mc.McFlight}. Pure Java; client thread only.
 */
public final class Flight {
    /** The client player now. */
    public interface Player {
        /** The player object, compared by identity (a new one after respawning or changing dimension). */
        Object identity();

        FlySpeedApplier.Abilities abilities();

        /** Whether the player is in creative or spectator, where the speed applies outside the editor. */
        boolean scalesOutsideEditor();
    }

    private final FlySpeed speed;
    private final Supplier<Player> player;
    private final BooleanSupplier editing;
    private final FlySpeedApplier applier = new FlySpeedApplier();

    /**
     * @param player the client player, or null when there is none
     * @param editing whether the editor is on (also while a vanilla screen suspends it)
     */
    public Flight(FlySpeed speed, Supplier<Player> player, BooleanSupplier editing) {
        this.speed = Objects.requireNonNull(speed);
        this.player = Objects.requireNonNull(player);
        this.editing = Objects.requireNonNull(editing);
    }

    public FlySpeed speed() {
        return speed;
    }

    /** The editor is open: the speed is {@code multiplier} × the player's own in any game mode. */
    public void editorApplies(double multiplier) {
        update(multiplier, true);
    }

    /** The editor closed: from now on the game mode decides. */
    public void editorReleased() {
        tick();
    }

    /** Once per client tick, and after {@code /flyspeed}: outside the editor, creative and spectator flight scale. */
    public void tick() {
        if (editing.getAsBoolean()) return;
        Player now = player.get();
        update(speed.multiplier(), now != null && now.scalesOutsideEditor());
    }

    /** The server sent the player's abilities: the fly speed they hold is the player's own. */
    public void abilitiesFromServer() {
        applier.rebase();
    }

    /** Whether the player is in creative or spectator now. */
    public boolean scalesOutsideEditor() {
        Player now = player.get();
        return now != null && now.scalesOutsideEditor();
    }

    private void update(double multiplier, boolean scale) {
        Player now = player.get();
        if (now == null) {
            applier.update(null, null, multiplier, false);
        } else {
            applier.update(now.identity(), now.abilities(), multiplier, scale);
        }
    }
}
