package dev.sculptory.fabric.gametest;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Protected columns for chosen players in GameTests, as spawn protection or a claim mod would make them (applied by
 * {@code SpawnProtectionHookMixin} through {@code canPlayerModifyAt}). Only the players a test names are affected, so
 * tests running at the same time are not.
 */
public final class ProtectionHook {
    /** A protected rectangle of columns, inclusive, in one world. */
    private record Area(RegistryKey<World> world, int minX, int minZ, int maxX, int maxZ) {}

    private static final Map<UUID, Area> PROTECTED = new ConcurrentHashMap<>();

    private ProtectionHook() {}

    /** Protects columns {@code minX..maxX × minZ..maxZ} of {@code world} from {@code player} until {@link #clear}. */
    public static void protect(PlayerEntity player, ServerWorld world, int minX, int minZ, int maxX, int maxZ) {
        PROTECTED.put(player.getUuid(), new Area(world.getRegistryKey(), minX, minZ, maxX, maxZ));
    }

    public static void clear(PlayerEntity player) {
        PROTECTED.remove(player.getUuid());
    }

    public static boolean protects(ServerWorld world, BlockPos pos, PlayerEntity player) {
        if (player == null) return false;
        Area area = PROTECTED.get(player.getUuid());
        return area != null && area.world().equals(world.getRegistryKey()) && pos.getX() >= area.minX()
                && pos.getX() <= area.maxX() && pos.getZ() >= area.minZ() && pos.getZ() <= area.maxZ();
    }
}
