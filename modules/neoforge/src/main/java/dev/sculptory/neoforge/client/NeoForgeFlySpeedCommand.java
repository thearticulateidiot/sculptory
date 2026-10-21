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
 * (or whose requirement fails) on to the server, so on a server with its own {@code /flyspeed} that one runs.
 *
 * <p>NeoForge's {@code handleCommands} stores the command tree twice: the server's tree, then the tree with the client
 * commands merged in. {@code ClientCommandTreeMixin} (modules/common) injects after every store, so the listener here
 * sees both, in that order, within the one call; it reads only the first of each pair. (With {@code ordinal = 0} on
 * that injection it would see only the server's tree on both loaders.) Client thread only.
 */
public final class NeoForgeFlySpeedCommand implements FlySpeedCommand.Handler<CommandSourceStack> {
    /** Whether the server's last command tree has a root {@code flyspeed}; false until one arrives. */
    private static boolean serverHasFlySpeed;
    /** The next tree the mixin hands over is NeoForge's merged tree, not the server's. */
    private static boolean mergedTreeNext;

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
     * A command tree was stored: the server's (notes whether it has a {@code /flyspeed} and readies it for the merge,
     * {@link FlySpeedCommand#prepareServerTree}), or right after it NeoForge's merged one (ignored).
     */
    private static void serverTree(CommandDispatcher<SharedSuggestionProvider> tree) {
        if (mergedTreeNext) {
            mergedTreeNext = false;
            return;
        }
        mergedTreeNext = true;
        serverHasFlySpeed = tree.getRoot().getChild(FlySpeedCommand.NAME) != null;
        FlySpeedCommand.prepareServerTree(tree.getRoot());
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
