package dev.sculptory.mc.engine.impl;

import dev.sculptory.mc.world.BlockWriter;
import dev.sculptory.mc.world.McStateSpace;
import dev.sculptory.mc.world.McWorldReader;
import dev.sculptory.server.config.SculptoryConfig;
import dev.sculptory.server.platform.FluidTrailHook;
import dev.sculptory.server.platform.PlatformPermissions;
import dev.sculptory.server.platform.WriteOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * What Tinker ({@link TinkerEdits}) and Navigate ({@link NavigateService}) need from the engine host on a Minecraft
 * server: the loaders' {@link EngineRuntime} implements it, and so can another platform's host (Paper's) without being
 * one. The types are the common world layer's own, so the callers keep its methods.
 */
public interface McHost {
    SculptoryConfig config();

    McStateSpace states();

    PlatformPermissions<ServerPlayer, ServerLevel> permissions();

    FluidTrailHook<ServerLevel> fluidTrails();

    McWorldReader reader(ServerLevel world);

    BlockWriter writer(ServerLevel world, WriteOptions options);

    String worldId(ServerLevel world);

    boolean isOnThread();

    /**
     * Moves {@code player} to (x, y, z) in their own world, facing {@code yaw}/{@code pitch} (Navigate). Returns false
     * when the platform refused the move: on Paper a plugin cancelled the teleport event (WorldGuard's entry rules), on
     * Fabric and NeoForge never.
     */
    boolean teleport(ServerPlayer player, double x, double y, double z, float yaw, float pitch);
}
