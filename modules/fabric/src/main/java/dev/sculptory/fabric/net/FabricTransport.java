package dev.sculptory.fabric.net;

import dev.sculptory.server.net.ServerTransport;
import java.util.UUID;
import net.minecraft.server.network.ServerPlayerEntity;

/** A Fabric connection's transport: the player's id and account name come from its {@link ServerPlayerEntity}. */
public interface FabricTransport extends ServerTransport<ServerPlayerEntity> {
    @Override
    default UUID playerId() {
        ServerPlayerEntity player = player();
        return player == null ? null : player.getUuid();
    }

    @Override
    default String playerName() {
        ServerPlayerEntity player = player();
        return player == null ? null : player.getGameProfile().getName();
    }
}
