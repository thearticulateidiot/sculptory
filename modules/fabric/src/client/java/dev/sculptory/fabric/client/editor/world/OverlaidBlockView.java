package dev.sculptory.fabric.client.editor.world;

import dev.sculptory.fabric.client.editor.tool.RayOverlay;
import java.util.Objects;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;
import org.jetbrains.annotations.Nullable;

/**
 * A world with some cells shown as other states ({@link RayOverlay}, handles being raw block-state ids), for a cursor
 * ray that looks through a brush press's own shapes. A cell the overlay shows has its state's fluid and no block
 * entity; every other cell, and the height, is the world's. Render thread only.
 */
public final class OverlaidBlockView implements BlockView {
    private final BlockView world;
    private final RayOverlay overlay;

    public OverlaidBlockView(BlockView world, RayOverlay overlay) {
        this.world = Objects.requireNonNull(world);
        this.overlay = Objects.requireNonNull(overlay);
    }

    /** Whether this view shows {@code world} through {@code overlay}. */
    public boolean shows(BlockView world, RayOverlay overlay) {
        return this.world == world && this.overlay == overlay;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        int shown = overlay.stateAt(pos.getX(), pos.getY(), pos.getZ());
        return shown == RayOverlay.WORLD ? world.getBlockState(pos) : Block.getStateFromRawId(shown);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        int shown = overlay.stateAt(pos.getX(), pos.getY(), pos.getZ());
        return shown == RayOverlay.WORLD ? world.getFluidState(pos) : Block.getStateFromRawId(shown).getFluidState();
    }

    @Nullable
    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        boolean shown = overlay.stateAt(pos.getX(), pos.getY(), pos.getZ()) != RayOverlay.WORLD;
        return shown ? null : world.getBlockEntity(pos);
    }

    @Override
    public int getHeight() {
        return world.getHeight();
    }

    @Override
    public int getBottomY() {
        return world.getBottomY();
    }
}
