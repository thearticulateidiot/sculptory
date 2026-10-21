package dev.sculptory.mc.world;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.BitStorage;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.StandingAndWallBlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.GrowingPlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * The non-public game members the world layer reads (and builder mode calls: the steps of {@code BlockItem.place}),
 * behind one seam. Fabric and NeoForge reach them directly ({@link WidenedMcInternals}: the access widener, the access
 * transformer generated from it, and the builder mixin invokers); a platform without either (Paper, whose plugins get
 * no access transformers or mixins) reaches them its own way. The platform installs its
 * implementation before any of the mod's other code runs: the Fabric and NeoForge loaders do it in their
 * {@code install()}.
 */
public interface McInternals {
    /** {@code GrowingPlantBlock.growthDirection} (protected in the game). */
    Direction growthDirection(GrowingPlantBlock block);

    /**
     * A section container's packed indices and palette, from one read of its {@code data} field (the game replaces the
     * whole record when the container resizes, so both come from the same snapshot).
     */
    <T> PaletteData<T> paletteData(PalettedContainer<T> container);

    /** {@code BlockItem.getPlacementState} (protected): the state the item places in the context, or null. */
    BlockState placementState(BlockItem item, BlockPlaceContext context);

    /** {@code BlockItem.placeBlock} (protected; items override it: doors, beds, tall plants). */
    boolean placeBlock(BlockItem item, BlockPlaceContext context, BlockState state);

    /** {@code BlockItem.updateBlockStateFromTag} (private): the item's block-state component applied to the placed block. */
    BlockState updateBlockStateFromTag(BlockItem item, BlockPos pos, Level world, ItemStack stack, BlockState state);

    /** {@code BlockItem.updateCustomBlockEntityTag} (protected; a sign opens its editor here). */
    boolean updateCustomBlockEntityTag(BlockItem item, BlockPos pos, Level world, Player player, ItemStack stack,
                                       BlockState state);

    /** {@code BlockItem.getPlaceSound} (protected). */
    SoundEvent placeSound(BlockItem item, BlockState state);

    /** {@code BlockItem.updateBlockEntityComponents} (private static): the item's components onto the block entity. */
    void updateBlockEntityComponents(Level world, BlockPos pos, ItemStack stack);

    /** {@code StandingAndWallBlockItem.wallBlock} (protected): the block a wall-and-floor item puts on a wall. */
    Block wallBlock(StandingAndWallBlockItem item);

    /** {@code PalettedContainer.Data}'s storage and palette ({@code Data} itself is package-private in the game). */
    record PaletteData<T>(BitStorage storage, Palette<T> palette) {
    }

    /** Installs the platform's implementation; once (installing the same instance again is allowed). */
    static void install(McInternals internals) {
        Holder.install(internals);
    }

    /** The installed implementation. */
    static McInternals get() {
        McInternals internals = Holder.installed;
        if (internals == null) {
            throw new IllegalStateException("no McInternals installed (the platform's loader installs it first)");
        }
        return internals;
    }

    /** The installed instance (an interface cannot hold a mutable static field itself). */
    final class Holder {
        private static volatile McInternals installed;

        private Holder() {
        }

        private static synchronized void install(McInternals internals) {
            Objects.requireNonNull(internals);
            if (installed != null && installed != internals) {
                throw new IllegalStateException("McInternals are already installed");
            }
            installed = internals;
        }
    }
}
