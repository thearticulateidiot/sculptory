package dev.sculptory.fabric.net;

import dev.sculptory.server.net.ServerTransport;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

/** A Fabric connection's transport: the player's id and account name come from its {@link ServerPlayer}. */
public interface FabricTransport extends ServerTransport<ServerPlayer> {
    @Override
    default UUID playerId() {
        ServerPlayer player = player();
        return player == null ? null : player.getUUID();
    }

    @Override
    default String playerName() {
        ServerPlayer player = player();
        return player == null ? null : player.getGameProfile().getName();
    }
}
