package dev.sculptory.mc.perm;

import dev.sculptory.protocol.v2.PermissionMask;
import dev.sculptory.server.config.SculptoryConfig;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.platform.PlatformPermissions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * The permissions of a Minecraft server's engine ({@code EngineRuntime#permissions}): nodes and chunk protection, plus
 * what the Minecraft side asks beyond the engine's {@link PlatformPermissions}. {@link McPermissionService} is the
 * Fabric and NeoForge default; a platform can hand {@code EngineRuntime} its own (Paper adds region plugins to the chunk
 * permits).
 */
public interface McPermissions extends PlatformPermissions<ServerPlayer, ServerLevel> {
    /** The node: {@code TRUE}, {@code FALSE}, or {@code UNKNOWN} when the permissions backend failed. */
    Tri check(ServerPlayer p, Perm node);

    /** Every node the player holds, for {@code Welcome}/{@code PermissionsChanged}. */
    PermissionMask mask(ServerPlayer p);

    /** Uses {@code config}'s permission settings from the next check on ({@code /sculptory reload}). */
    void configure(SculptoryConfig config);
}
