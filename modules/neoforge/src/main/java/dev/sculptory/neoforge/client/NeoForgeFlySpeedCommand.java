package dev.sculptory.neoforge.client;

import com.mojang.brigadier.CommandDispatcher;
import dev.sculptory.mc.client.editor.Flight;
import dev.sculptory.mc.client.editor.FlySpeedCommand;
import dev.sculptory.mc.client.editor.mc.ServerCommandTree;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * {@code /flyspeed} and {@code /sculptory flyspeed} as NeoForge client commands ({@link FlySpeedCommand}'s tree; no
 * operator rights, works on any server), the counterpart of the Fabric layer's {@code FabricFlySpeedCommand}. NeoForge
 * builds its client commands anew each time the server sends a command tree ({@code RegisterClientCommandsEvent}, from
 * {@code ClientCommandHandler.mergeServerCommands}), and like Fabric it sends a command the client tree does not know
 * (or whose requirement fails) on to the server, so on a server with its own {@code /flyspeed} that one runs. Client
 * thread only.
 */
public final class NeoForgeFlySpeedCommand implements FlySpeedCommand.Handler<CommandSourceStack> {
    /** Whether the server's last command tree has a root {@code flyspeed}; false until one arrives. */
    private static boolean serverHasFlySpeed;

    private final Flight flight;
    private final BooleanSupplier editing;

    private NeoForgeFlySpeedCommand(Flight flight, BooleanSupplier editing) {
        this.flight = Objects.requireNonNull(flight);
        this.editing = Objects.requireNonNull(editing);
    }

    /** Registers the commands. Call once. */
    public static void register(Flight flight, BooleanSupplier editing) {
        NeoForgeFlySpeedCommand command = new NeoForgeFlySpeedCommand(flight, editing);
        NeoForge.EVENT_BUS.addListener((RegisterClientCommandsEvent event) ->
                FlySpeedCommand.register(event.getDispatcher(), () -> serverHasFlySpeed, command));
        ServerCommandTree.listen(NeoForgeFlySpeedCommand::serverTree);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> serverHasFlySpeed = false);
    }

    /**
     * The server's command tree arrived, as the server sent it ({@code ClientCommandTreeMixin} reads only the first store
     * in {@code handleCommands}, before NeoForge merges its client commands in): notes whether it has a {@code /flyspeed}
     * and readies it for the merge ({@link FlySpeedCommand#prepareServerTree}).
     */
    private static void serverTree(CommandDispatcher<SharedSuggestionProvider> server) {
        serverHasFlySpeed = server.getRoot().getChild(FlySpeedCommand.NAME) != null;
        FlySpeedCommand.prepareServerTree(server.getRoot());
    }

    @Override
    public int flySpeed(CommandSourceStack source, Optional<String> argument) {
        boolean appliesNow = editing.getAsBoolean() || flight.scalesOutsideEditor();
        FlySpeedCommand.Reply reply = FlySpeedCommand.run(flight.speed(), argument, appliesNow);
        flight.tick();
        Component text = Component.translatable(reply.key(), reply.args().toArray());
        if (reply.error()) {
            source.sendFailure(text);
            return 0;
        }
        source.sendSuccess(() -> text, false);
        if (reply.notFlyingHint()) {
            source.sendSuccess(() -> Component.translatable(FlySpeedCommand.NOT_FLYING_KEY), false);
        }
        return 1;
    }

    @Override
    public int forward(CommandSourceStack source, String command) {
        ClientPacketListener network = Minecraft.getInstance().getConnection();
        if (network == null) return 0;
        // As vanilla sends a command without signed arguments; not sendCommand, which would come back here.
        network.send(new ServerboundChatCommandPacket(command));
        return 1;
    }
}
