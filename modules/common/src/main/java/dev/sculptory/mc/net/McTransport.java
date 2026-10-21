package dev.sculptory.mc.net;

import dev.sculptory.server.net.ServerTransport;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

/** A Minecraft connection's transport: the player's id and account name come from its {@link ServerPlayer}. */
public interface McTransport extends ServerTransport<ServerPlayer> {
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
