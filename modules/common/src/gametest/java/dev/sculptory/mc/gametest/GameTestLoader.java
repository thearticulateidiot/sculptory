package dev.sculptory.mc.gametest;

import java.util.UUID;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * What the shared GameTests need from a mod loader that the mod's own seam ({@code Loaders.server()}) does not give:
 * each loader's test mod implements it and installs it in {@link GameTestLoaders} before any test runs. Lifecycle and
 * tick hooks, the mod list and folders come from {@code Loaders} like in the mod itself.
 *
 * <p>The template the tests are placed on is not here: a {@code @GameTest} annotation needs a compile-time constant,
 * so it is {@link GameTestSuite#EMPTY_STRUCTURE}, a structure the shared test resources ship and every loader reads
 * the same way (vanilla's structure manager). How each loader turns the annotated classes
 * ({@link GameTestSuite#CLASSES}) into registered tests is the test mod's business.
 */
public interface GameTestLoader {
    /** A permissions mod's answer for a node: grant it, refuse it, or leave it to the default (the operator level). */
    enum Answer { ALLOW, DENY, DEFAULT }

    /**
     * Decides permission checks for the test players, the way a permissions mod (LuckPerms) would. May throw, as a
     * broken permissions mod does; the throw reaches the code that asked.
     */
    @FunctionalInterface
    interface PermissionAnswerer {
        /**
         * @param player the player the check is for
         * @param world  the world the player is in when the check runs (a world-scoped grant), or null if unknown
         * @param node   the permission node, e.g. {@code sculptory.edit}
         */
        Answer answer(UUID player, ResourceKey<Level> world, String node);
    }

    /**
     * Puts {@code answerer} in front of every Sculptory permission check from now on, where the server's permissions
     * mod would answer (Fabric: fabric-permissions-api's {@code PermissionCheckEvent}; NeoForge: the test permission
     * handler the GameTest run selects). {@link Answer#DEFAULT} keeps the check's own fallback. Called once.
     */
    void answerPermissions(PermissionAnswerer answerer);
}
