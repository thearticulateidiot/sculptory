package dev.sculptory.fabric.gametest;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * Protected columns for chosen players in GameTests, as spawn protection or a claim mod would make them (applied by
 * {@code SpawnProtectionHookMixin} through {@code canPlayerModifyAt}). Only the players a test names are affected, so
 * tests running at the same time are not.
 */
public final class ProtectionHook {
    /** A protected rectangle of columns, inclusive, in one world. */
    private record Area(ResourceKey<Level> world, int minX, int minZ, int maxX, int maxZ) {}

    private static final Map<UUID, Area> PROTECTED = new ConcurrentHashMap<>();

    private ProtectionHook() {}

    /** Protects columns {@code minX..maxX × minZ..maxZ} of {@code world} from {@code player} until {@link #clear}. */
    public static void protect(Player player, ServerLevel world, int minX, int minZ, int maxX, int maxZ) {
        PROTECTED.put(player.getUUID(), new Area(world.dimension(), minX, minZ, maxX, maxZ));
    }

    public static void clear(Player player) {
        PROTECTED.remove(player.getUUID());
    }

    public static boolean protects(ServerLevel world, BlockPos pos, Player player) {
        if (player == null) return false;
        Area area = PROTECTED.get(player.getUUID());
        return area != null && area.world().equals(world.dimension()) && pos.getX() >= area.minX()
                && pos.getX() <= area.maxX() && pos.getZ() >= area.minZ() && pos.getZ() <= area.maxZ();
    }
}
