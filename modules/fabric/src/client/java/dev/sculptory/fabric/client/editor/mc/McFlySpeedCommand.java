package dev.sculptory.fabric.client.editor.mc;

import com.mojang.brigadier.CommandDispatcher;
import dev.sculptory.fabric.client.editor.Flight;
import dev.sculptory.fabric.client.editor.FlySpeedCommand;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;

/**
 * Registers {@link FlySpeedCommand}'s tree as Fabric client commands (no operator rights, works on any server) and
 * shows its replies in chat. Whether the server has a {@code /flyspeed} of its own is read from each command tree it
 * sends, before Fabric merges the client commands into it ({@code ClientCommandTreeMixin}). After a change the speed
 * applies at once outside the editor ({@link Flight#tick}); in the editor at its next tick. Client thread only.
 */
public final class McFlySpeedCommand implements FlySpeedCommand.Handler<FabricClientCommandSource> {
    /** Whether the server's last command tree has a root {@code flyspeed}; false until one arrives. */
    private static boolean serverHasFlySpeed;

    private final Flight flight;
    private final BooleanSupplier editing;

    private McFlySpeedCommand(Flight flight, BooleanSupplier editing) {
        this.flight = Objects.requireNonNull(flight);
        this.editing = Objects.requireNonNull(editing);
    }

    /** Registers the commands. Call once. */
    public static void register(Flight flight, BooleanSupplier editing) {
        McFlySpeedCommand command = new McFlySpeedCommand(flight, editing);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                FlySpeedCommand.register(dispatcher, () -> serverHasFlySpeed, command));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> serverHasFlySpeed = false));
    }

    /**
     * The server's command tree arrived, as the server sent it (before the client commands are merged in): notes whether
     * it has a {@code /flyspeed} and readies it for the merge ({@link FlySpeedCommand#prepareServerTree}).
     */
    public static void serverTree(CommandDispatcher<SharedSuggestionProvider> server) {
        serverHasFlySpeed = server.getRoot().getChild(FlySpeedCommand.NAME) != null;
        FlySpeedCommand.prepareServerTree(server.getRoot());
    }

    @Override
    public int flySpeed(FabricClientCommandSource source, Optional<String> argument) {
        boolean appliesNow = editing.getAsBoolean() || flight.scalesOutsideEditor();
        FlySpeedCommand.Reply reply = FlySpeedCommand.run(flight.speed(), argument, appliesNow);
        flight.tick();
        Component text = Component.translatable(reply.key(), reply.args().toArray());
        if (reply.error()) {
            source.sendError(text);
            return 0;
        }
        source.sendFeedback(text);
        if (reply.notFlyingHint()) {
            source.sendFeedback(Component.translatable(FlySpeedCommand.NOT_FLYING_KEY));
        }
        return 1;
    }

    @Override
    public int forward(FabricClientCommandSource source, String command) {
        ClientPacketListener network = source.getClient().getConnection();
        if (network == null) return 0;
        // As vanilla sends a command without signed arguments; not sendChatCommand, which would come back here.
        network.send(new ServerboundChatCommandPacket(command));
        return 1;
    }
}
