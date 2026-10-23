package dev.sculptory.paper.engine;

import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import org.bukkit.plugin.Plugin;

/**
 * Builder steps for the plugin's self-test only (dev.sculptory.paper.devtest): what production code never needs, kept
 * out of {@link PaperBuilderSteps}' own constructors.
 */
public final class SelfTestSteps {
    private SelfTestSteps() {
    }

    /**
     * Paper's builder steps with {@code beforeUpdate} called before each changed cell's deferred updates run, so a
     * check can make the deferred physics fail halfway (by throwing from it).
     */
    public static PaperBuilderSteps failingBeforeUpdate(Plugin plugin, Consumer<BlockPos> beforeUpdate) {
        return new PaperBuilderSteps(plugin, PaperBuilderSteps.DEFAULT_MAX_WATCHED_CELLS, beforeUpdate);
    }
}
