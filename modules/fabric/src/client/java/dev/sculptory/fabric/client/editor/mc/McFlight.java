package dev.sculptory.fabric.client.editor.mc;

import dev.sculptory.fabric.client.editor.Flight;
import dev.sculptory.fabric.client.editor.FlySpeed;
import dev.sculptory.fabric.client.editor.FlySpeedApplier;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerAbilities;

/** The fly speed's Minecraft side: {@link Flight} over the client player's abilities and game mode. */
public final class McFlight {
    private McFlight() {
    }

    /** The game's {@link Flight}; {@code editing} is the editor mode's {@code isEditing}. */
    public static Flight create(MinecraftClient client, FlySpeed speed, BooleanSupplier editing) {
        Objects.requireNonNull(client);
        return new Flight(speed, () -> {
            ClientPlayerEntity player = client.player;
            return player == null ? null : player(player);
        }, editing);
    }

    private static Flight.Player player(ClientPlayerEntity player) {
        PlayerAbilities abilities = player.getAbilities();
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
                        return abilities.getFlySpeed();
                    }

                    @Override
                    public void setFlySpeed(float value) {
                        abilities.setFlySpeed(value);
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
