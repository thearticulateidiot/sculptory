package dev.sculptory.fabric.gametest;

import dev.sculptory.mc.gametest.GameTestLoader;
import me.lucko.fabric.api.permissions.v0.PermissionCheckEvent;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.commands.CommandSourceStack;

/**
 * The {@link GameTestLoader} on Fabric. Permission answers go through fabric-permissions-api's
 * {@code PermissionCheckEvent}, where a permissions mod answers, for checks whose source is an entity (a player's
 * command source, which {@code FabricServerLoader.hasPermission} builds); the world is the source's.
 */
final class FabricGameTestLoader implements GameTestLoader {
    static final FabricGameTestLoader INSTANCE = new FabricGameTestLoader();

    private FabricGameTestLoader() {
    }

    @Override
    public void answerPermissions(PermissionAnswerer answerer) {
        PermissionCheckEvent.EVENT.register((source, node) -> {
            if (!(source instanceof CommandSourceStack command) || command.getEntity() == null) return TriState.DEFAULT;
            Answer answer = answerer.answer(command.getEntity().getUUID(),
                    command.getLevel() == null ? null : command.getLevel().dimension(), node);
            return switch (answer) {
                case ALLOW -> TriState.TRUE;
                case DENY -> TriState.FALSE;
                case DEFAULT -> TriState.DEFAULT;
            };
        });
    }
}
