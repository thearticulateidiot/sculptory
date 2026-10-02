package dev.sculptory.fabric.perm;

import com.mojang.authlib.GameProfile;
import dev.sculptory.core.Box;
import dev.sculptory.protocol.v2.PermissionMask;
import dev.sculptory.server.config.SculptoryConfig;
import dev.sculptory.server.engine.ChunkPermit;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.perm.ChunkPermits;
import dev.sculptory.server.platform.PlatformPermissions;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Permission nodes via fabric-permissions-api with an op-level fallback, and per-chunk protection via
 * {@code ServerWorld.canPlayerModifyAt} (spawn protection, world border, and whatever claim mods hook there).
 * The singleplayer host is always allowed when the config says so.
 *
 * <p>A permissions mod whose check throws is treated as not granting the node ({@link #has} fails closed, so new
 * requests are refused {@code NO_PERMISSION}); {@link #check} reports it as unknown instead, so re-checks of work
 * already admitted can leave that work alone. Failures are logged at most once a minute per player.
 */
public final class FabricPermissionService implements PlatformPermissions<ServerPlayerEntity, ServerWorld> {
    private static final Logger LOG = LoggerFactory.getLogger("sculptory");
    /** Failed permission checks are logged at most this often per player. */
    static final long FAILURE_LOG_INTERVAL_NANOS = 60_000_000_000L;
    private static final int MAX_FAILURE_ENTRIES = 1024;

    /** The permission settings in effect ({@link #configure}: replaced by a config reload). */
    private volatile SculptoryConfig config;
    /** When a failed check was last logged, per player (nanoTime). */
    private final Map<UUID, Long> failureLogged = new ConcurrentHashMap<>();

    public FabricPermissionService(SculptoryConfig config) {
        this.config = Objects.requireNonNull(config);
    }

    /**
     * Uses {@code config}'s {@code permissionFallbackOpLevel} and {@code singleplayerHostAlwaysAllowed} from the next
     * check on ({@code /sculptory reload}).
     */
    public void configure(SculptoryConfig config) {
        this.config = Objects.requireNonNull(config);
    }

    /** Whether the player holds the node; {@code false} when the permissions backend fails (see {@link #check}). */
    @Override
    public boolean has(ServerPlayerEntity p, Perm node) {
        return check(p, node) == TriState.TRUE;
    }

    /**
     * The node for the player: {@code TRUE} or {@code FALSE}, or {@code DEFAULT} when the permissions backend threw
     * (logged at most once a minute per player).
     */
    public TriState check(ServerPlayerEntity p, Perm node) {
        Objects.requireNonNull(node);
        if (isSingleplayerHost(p)) return TriState.TRUE;
        try {
            return Permissions.check(p, node.node(), config.permissionFallbackOpLevel) ? TriState.TRUE : TriState.FALSE;
        } catch (RuntimeException | LinkageError e) {
            // A LinkageError: a permissions mod built against another API version. Treated like any failure.
            logFailure(p, node, e);
            return TriState.DEFAULT;
        }
    }

    /** Whether {@link #check} says {@code FALSE}: a definite no (a failing permissions backend is none). */
    @Override
    public boolean denied(ServerPlayerEntity p, Perm node) {
        return check(p, node) == TriState.FALSE;
    }

    private void logFailure(ServerPlayerEntity p, Perm node, Throwable e) {
        long now = System.nanoTime();
        UUID id = p.getUuid();
        Long last = failureLogged.get(id);
        if (last != null && now - last < FAILURE_LOG_INTERVAL_NANOS) return;
        if (failureLogged.size() >= MAX_FAILURE_ENTRIES) {
            failureLogged.values().removeIf(at -> now - at >= FAILURE_LOG_INTERVAL_NANOS);
        }
        failureLogged.put(id, now);
        LOG.warn("Sculptory: the permissions mod failed to check {} for {} ({}); treating it as not granted. "
                + "Logged at most once a minute per player.", node.node(), p.getGameProfile().getName(), e.toString());
    }

    /** Every node the player holds, for {@code Welcome}/{@code PermissionsChanged}. */
    public PermissionMask mask(ServerPlayerEntity p) {
        EnumSet<Perm> granted = EnumSet.noneOf(Perm.class);
        for (Perm perm : Perm.values()) {
            if (has(p, perm)) granted.add(perm);
        }
        return Perm.mask(granted);
    }

    /** Whether operator-only block-entity NBT is kept for this player. */
    @Override
    public boolean mayWriteOperatorNbt(ServerPlayerEntity p) {
        return operatorNbt(p) == TriState.TRUE;
    }

    /** {@link #mayWriteOperatorNbt} as a {@link #check}: {@code DEFAULT} when the permissions backend failed. */
    public TriState operatorNbt(ServerPlayerEntity p) {
        return p.isCreativeLevelTwoOp() ? TriState.TRUE : check(p, Perm.NBT_OPERATOR);
    }

    /** Whether {@link #operatorNbt} says {@code FALSE}. */
    @Override
    public boolean operatorNbtDenied(ServerPlayerEntity p) {
        return operatorNbt(p) == TriState.FALSE;
    }

    /** Corner rule plus a sample at the column nearest world spawn, so small spawn protection is not missed. */
    @Override
    public ChunkPermit chunk(ServerPlayerEntity p, ServerWorld w, int cx, int cz, Box bounds) {
        int y = Math.max(w.getBottomY(), Math.min(w.getTopY() - 1, bounds.min().y()));
        BlockPos spawn = w.getSpawnPos();
        return ChunkPermits.forChunk(cx, cz, bounds, (x, z) -> w.canPlayerModifyAt(p, new BlockPos(x, y, z)),
                spawn.getX(), spawn.getZ());
    }

    private boolean isSingleplayerHost(ServerPlayerEntity p) {
        if (!config.singleplayerHostAlwaysAllowed) return false;
        MinecraftServer server = p.getServer();
        return server != null && isSingleplayerHost(server.isDedicated(), server.getHostProfile(), p.getGameProfile());
    }

    /**
     * The singleplayer host rule by UUID. (Vanilla {@code IntegratedServer.isHost} compares names without regard to
     * case, which an offline LAN guest could match.)
     */
    public static boolean isSingleplayerHost(boolean dedicatedServer, GameProfile host, GameProfile player) {
        if (dedicatedServer || host == null || player == null) return false;
        UUID hostId = host.getId();
        return hostId != null && hostId.equals(player.getId());
    }
}
