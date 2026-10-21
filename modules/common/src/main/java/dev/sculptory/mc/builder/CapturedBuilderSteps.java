package dev.sculptory.mc.builder;

import dev.sculptory.mc.world.BlockWriter;
import dev.sculptory.mc.world.EditScope;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Clearable;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * {@link BuilderSteps} on Fabric and NeoForge: the steps run inside a {@link BuilderCapture}, whose mixin
 * ({@code BuilderCaptureMixin}) notes every cell any {@code Level.setBlock} of the action changes, the cascade of
 * neighbour updates included, and for Keep shape turns those writes physics-free; an {@link EditScope} cancels the
 * blocks' own add and replace callbacks for Keep shape. No plugin events exist there, so a step is never
 * {@link Step#REFUSED}.
 */
public final class CapturedBuilderSteps implements BuilderSteps {
    public static final CapturedBuilderSteps INSTANCE = new CapturedBuilderSteps();

    private CapturedBuilderSteps() {
    }

    @Override
    public Action open(ServerLevel world, ServerPlayer player, boolean keepShape) {
        BuilderCapture capture = BuilderCapture.open(world, keepShape, true);
        EditScope scope = keepShape ? EditScope.suppressPhysics() : null;
        return new Action() {
            private boolean scopeClosed;

            @Override
            public void emptyContainer(BlockPos pos) {
                BlockEntity entity = world.getBlockEntity(pos);
                if (!(entity instanceof Clearable)) return;
                capture.touch(pos);
                try (EditScope suppressed = EditScope.suppressPhysics()) {
                    Clearable.tryClear(entity);
                }
            }

            @Override
            public Step place(BlockItem item, BlockPlaceContext context, BlockState state, boolean copy) {
                return BuilderPlacement.place(item, context, state, copy) ? Step.DONE : Step.NOT_DONE;
            }

            @Override
            public Step breakBlock(BlockPos pos) {
                emptyContainer(pos);
                return BuilderPlacement.breakBlock(world, player, pos) ? Step.DONE : Step.NOT_DONE;
            }

            @Override
            public List<BuilderChange> finish() {
                if (scope != null && !scopeClosed) {
                    scopeClosed = true;
                    scope.close();
                }
                List<BuilderChange> changes = new ArrayList<>();
                capture.finish((x, y, z, before, beforeTile, after, afterTile) ->
                        changes.add(new BuilderChange(new BlockPos(x, y, z), before, beforeTile, after, afterTile)));
                return changes;
            }
        };
    }

    /**
     * Puts every changed cell back as it was, newest first, physics-free ({@code FORCE_STATE}, no neighbour updates, the
     * blocks' own callbacks cancelled so nothing drops or reacts); block entities are restored with their contents.
     * Clients hear the writes, and the action's prediction is reverted by its acknowledgement anyway. Block entities are
     * removed through the chunk ({@link BlockWriter#removeBlockEntityQuietly}): NeoForge's {@code Level.removeBlockEntity}
     * also updates the comparators next to the cell, which a physics-free write must not do.
     */
    @Override
    public void revert(ServerLevel world, List<BuilderChange> changes) {
        try (EditScope scope = EditScope.suppressPhysics()) {
            for (int i = changes.size() - 1; i >= 0; i--) {
                BuilderChange change = changes.get(i);
                BlockPos pos = change.pos();
                BlockState before = Block.stateById(change.before());
                if (world.getBlockState(pos).hasBlockEntity()) BlockWriter.removeBlockEntityQuietly(world, pos);
                world.setBlock(pos, before, Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_CLIENTS, 0);
                change.putBackTile(world, before);
            }
        }
    }

}
