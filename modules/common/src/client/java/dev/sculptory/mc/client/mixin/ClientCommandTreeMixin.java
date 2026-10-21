package dev.sculptory.mc.client.mixin;

import dev.sculptory.mc.client.editor.mc.ServerCommandTree;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code /flyspeed} is Sculptory's only where the server has none of its own: this hands the server's command tree to
 * {@link ServerCommandTree} right after the handler stores it, before the loader's client command API merges the client
 * commands into it (Fabric's does at the method's RETURN). Target verified with javap against Minecraft 1.21.1 (Mojang names): {@code handleCommands(ClientboundCommandsPacket)} ends
 * with {@code this.commands = new CommandDispatcher<>(packet.getRoot(...))}; the client thread only
 * reaches it (the handler first hands the packet over).
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientCommandTreeMixin {
    @Inject(method = "handleCommands", at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
            target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;commands:Lcom/mojang/brigadier/CommandDispatcher;",
            shift = At.Shift.AFTER), require = 1)
    private void sculptory$serverCommandTree(ClientboundCommandsPacket packet, CallbackInfo ci) {
        ServerCommandTree.received(((ClientPacketListener) (Object) this).getCommands());
    }
}
