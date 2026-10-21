package dev.sculptory.neoforge.gametest;

import dev.sculptory.mc.gametest.GameTestLoader.Answer;
import dev.sculptory.mc.gametest.GameTestLoader.PermissionAnswerer;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.server.permission.handler.DefaultPermissionHandler;
import net.neoforged.neoforge.server.permission.handler.IPermissionHandler;
import net.neoforged.neoforge.server.permission.nodes.PermissionDynamicContext;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;

/**
 * The GameTest runs' permission handler: what a permissions mod (LuckPerms) is on a real server. It answers the
 * boolean nodes the tests decide ({@link NeoForgeGameTestLoader#answerer}) and leaves everything else to NeoForge's
 * default handler, which asks the node's default resolver (Sculptory's: the operator level). The runs select it with
 * {@code permissionHandler} in {@code config/neoforge-server.toml} (modules/neoforge/build.gradle), so no test hook
 * sits in the mod's own permission code.
 */
final class TestPermissionHandler implements IPermissionHandler {
    /** Selected by the GameTest runs' neoforge-server.toml; the same in both test mods, which never load together. */
    static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("sculptory-gametest", "permissions");

    private final DefaultPermissionHandler fallback;

    TestPermissionHandler(Collection<PermissionNode<?>> nodes) {
        fallback = new DefaultPermissionHandler(nodes);
    }

    @Override
    public ResourceLocation getIdentifier() {
        return ID;
    }

    @Override
    public Set<PermissionNode<?>> getRegisteredNodes() {
        return fallback.getRegisteredNodes();
    }

    @Override
    public <T> T getPermission(ServerPlayer player, PermissionNode<T> node, PermissionDynamicContext<?>... context) {
        T answer = answer(player.getUUID(), player.serverLevel().dimension(), node);
        return answer != null ? answer : fallback.getPermission(player, node, context);
    }

    @Override
    public <T> T getOfflinePermission(UUID player, PermissionNode<T> node, PermissionDynamicContext<?>... context) {
        T answer = answer(player, null, node);
        return answer != null ? answer : fallback.getOfflinePermission(player, node, context);
    }

    /** The tests' answer for a boolean node, or null to let the default decide. May throw, as a test asks. */
    @SuppressWarnings("unchecked")
    private static <T> T answer(UUID player, ResourceKey<Level> world, PermissionNode<T> node) {
        PermissionAnswerer answerer = NeoForgeGameTestLoader.INSTANCE.answerer();
        if (answerer == null || node.getType() != PermissionTypes.BOOLEAN) return null;
        Answer answer = answerer.answer(player, world, node.getNodeName());
        return switch (answer) {
            case ALLOW -> (T) Boolean.TRUE;
            case DENY -> (T) Boolean.FALSE;
            case DEFAULT -> null;
        };
    }
}
