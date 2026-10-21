package dev.sculptory.mc.gametest.mixin;

import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Test mod only (sculptory-gametest): the online player list itself, so a test can take a player off it the way a
 * leave the edit service has not seen yet looks ({@code MultiplayerGameTest}). {@code PlayerList.remove} cannot do
 * that on every loader: NeoForge fires its logout event, the loader's leave hook, from there, and its
 * {@code getPlayers()} is a read-only view. Field checked against Minecraft 1.21.1 (Mojang names).
 */
@Mixin(PlayerList.class)
public interface PlayerListAccessor {
    @Accessor("players")
    List<ServerPlayer> sculptory$players();
}
