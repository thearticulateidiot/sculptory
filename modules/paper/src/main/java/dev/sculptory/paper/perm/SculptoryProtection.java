package dev.sculptory.paper.perm;

import org.bukkit.Bukkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Picks the {@link Protection} for this server from the region plugins it runs. */
public final class SculptoryProtection {
    private static final Logger LOG = LoggerFactory.getLogger("sculptory");

    private SculptoryProtection() {
    }

    /** WorldGuard when it is enabled (it loads before this plugin: plugin.yml softdepend), else {@link Protection#NONE}. */
    public static Protection detect() {
        if (Bukkit.getPluginManager().isPluginEnabled("WorldGuard")) {
            try {
                Protection worldGuard = new WorldGuardProtection();
                LOG.info("Sculptory: edits ask WorldGuard's build rule for each region they touch");
                return worldGuard;
            } catch (LinkageError e) {
                // Another WorldGuard API than the one this was built against (7.0.x). Failing open would let edits
                // into protected regions, so every edit is refused instead.
                LOG.error("Sculptory: WorldGuard is installed but its API could not be used ({}); edits are refused "
                        + "everywhere until this is fixed", e.toString());
                return new Protection() {
                    @Override
                    public CellTest cells(net.minecraft.server.level.ServerPlayer player,
                                          net.minecraft.server.level.ServerLevel world, dev.sculptory.core.Box part) {
                        return CellTest.DENY;
                    }

                    @Override
                    public String name() {
                        return "WorldGuard (unusable: refusing all)";
                    }
                };
            }
        }
        return Protection.NONE;
    }
}
