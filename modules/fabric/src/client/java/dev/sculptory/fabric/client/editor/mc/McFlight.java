package dev.sculptory.fabric.client.editor.mc;

import dev.sculptory.fabric.client.editor.Flight;
import dev.sculptory.fabric.client.editor.FlySpeed;
import dev.sculptory.fabric.client.editor.FlySpeedApplier;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Abilities;

/** The fly speed's Minecraft side: {@link Flight} over the client player's abilities and game mode. */
public final class McFlight {
    private McFlight() {
    }

    /** The game's {@link Flight}; {@code editing} is the editor mode's {@code isEditing}. */
    public static Flight create(Minecraft client, FlySpeed speed, BooleanSupplier editing) {
        Objects.requireNonNull(client);
        return new Flight(speed, () -> {
            LocalPlayer player = client.player;
            return player == null ? null : player(player);
        }, editing);
    }

    private static Flight.Player player(LocalPlayer player) {
        Abilities abilities = player.getAbilities();
        return new Flight.Player() {
            @Override
            public Object identity() {
                return player;
            }

            @Override
            public FlySpeedApplier.Abilities abilities() {
                return new FlySpeedApplier.Abilities() {
                    @Override
                    public float flySpeed() {
                        return abilities.getFlyingSpeed();
                    }

                    @Override
                    public void setFlySpeed(float value) {
                        abilities.setFlyingSpeed(value);
                    }
                };
            }

            @Override
            public boolean scalesOutsideEditor() {
                return player.isCreative() || player.isSpectator();
            }
        };
    }
}
