package dev.sculptory.paper.nms;

import ca.spottedleaf.concurrentutil.executor.standard.PrioritisedExecutor;
import ca.spottedleaf.moonrise.patches.starlight.light.StarLightInterface;
import ca.spottedleaf.moonrise.patches.starlight.light.StarLightLightingProvider;
import dev.sculptory.mc.world.McInternals;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.BitStorage;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.StandingAndWallBlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.GrowingPlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * What modules/common reaches through its access widener and the builder mixin invokers on Fabric and NeoForge, and
 * what needs a mixin or vanilla's light engine there, done Paper's way: Paper's {@link McInternals} (installed by the
 * plugin when it loads: the widened reads and the steps of {@code BlockItem.place} builder mode calls), plus the
 * physics-off write and the light marker, which the modules/paper build ({@code commonForPaper}) patches the common
 * sources to call. Paper has no access transformers for plugins, so non-public members are read through method handles
 * ({@link MethodHandles#privateLookupIn}: the server's classes are in the unnamed module, which a plugin may open).
 * Server classes are Mojang-named at run time on Paper 1.20.5+, so the names below are the ones the dev bundle shows.
 */
public final class PaperAccess implements McInternals {
    public static final PaperAccess INSTANCE = new PaperAccess();

    /**
     * CraftBukkit's custom {@code Level.setBlock} flag: {@code LevelChunk.setBlockState(pos, state, moved, doPlace)}
     * with {@code doPlace = false}, so the new state's {@code onPlace} does not run.
     */
    public static final int NO_PLACE = 1024;

    private static final VarHandle GROWTH_DIRECTION;
    private static final VarHandle DATA;
    private static final MethodHandle DATA_STORAGE;
    private static final MethodHandle DATA_PALETTE;
    private static final MethodHandle PLACEMENT_STATE;
    private static final MethodHandle PLACE_BLOCK;
    private static final MethodHandle STATE_FROM_TAG;
    private static final MethodHandle CUSTOM_BLOCK_ENTITY_TAG;
    private static final MethodHandle PLACE_SOUND;
    private static final MethodHandle BLOCK_ENTITY_COMPONENTS;
    private static final VarHandle WALL_BLOCK;

    static {
        try {
            MethodHandles.Lookup plants = MethodHandles.privateLookupIn(GrowingPlantBlock.class, MethodHandles.lookup());
            GROWTH_DIRECTION = plants.findVarHandle(GrowingPlantBlock.class, "growthDirection", Direction.class);
            MethodHandles.Lookup containers = MethodHandles.privateLookupIn(PalettedContainer.class, MethodHandles.lookup());
            // PalettedContainer.Data is a package-private record; its accessors are found through the container's lookup.
            Class<?> data = containers.findClass(PalettedContainer.class.getName() + "$Data");
            DATA = containers.findVarHandle(PalettedContainer.class, "data", data);
            DATA_STORAGE = containers.findVirtual(data, "storage", MethodType.methodType(BitStorage.class))
                    .asType(MethodType.methodType(BitStorage.class, Object.class));
            DATA_PALETTE = containers.findVirtual(data, "palette", MethodType.methodType(Palette.class))
                    .asType(MethodType.methodType(Palette.class, Object.class));
            // Builder mode: the steps of BlockItem.place. Virtual where the game's are, so items that override them
            // (doors, beds, signs, torches) keep their behaviour.
            MethodHandles.Lookup items = MethodHandles.privateLookupIn(BlockItem.class, MethodHandles.lookup());
            PLACEMENT_STATE = items.findVirtual(BlockItem.class, "getPlacementState",
                    MethodType.methodType(BlockState.class, BlockPlaceContext.class));
            PLACE_BLOCK = items.findVirtual(BlockItem.class, "placeBlock",
                    MethodType.methodType(boolean.class, BlockPlaceContext.class, BlockState.class));
            STATE_FROM_TAG = items.findVirtual(BlockItem.class, "updateBlockStateFromTag", MethodType.methodType(
                    BlockState.class, BlockPos.class, Level.class, ItemStack.class, BlockState.class));
            CUSTOM_BLOCK_ENTITY_TAG = items.findVirtual(BlockItem.class, "updateCustomBlockEntityTag",
                    MethodType.methodType(boolean.class, BlockPos.class, Level.class, Player.class, ItemStack.class,
                            BlockState.class));
            PLACE_SOUND = items.findVirtual(BlockItem.class, "getPlaceSound",
                    MethodType.methodType(SoundEvent.class, BlockState.class));
            BLOCK_ENTITY_COMPONENTS = items.findStatic(BlockItem.class, "updateBlockEntityComponents",
                    MethodType.methodType(void.class, Level.class, BlockPos.class, ItemStack.class));
            WALL_BLOCK = MethodHandles.privateLookupIn(StandingAndWallBlockItem.class, MethodHandles.lookup())
                    .findVarHandle(StandingAndWallBlockItem.class, "wallBlock", Block.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private PaperAccess() {
    }

    /**
     * A future that completes once Paper's light engine has worked through what is queued for chunk (x, z) so far: the
     * stand-in for {@code ThreadedLevelLightEngine.waitForPendingTasks}, which Paper (Moonrise's Starlight) replaces
     * with an UnsupportedOperationException. An empty light task joins the chunk's pending tasks, and the future
     * completes when those have all run (on the light thread).
     */
    public static CompletableFuture<?> lightMarker(ThreadedLevelLightEngine light, int x, int z) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        StarLightInterface.ServerLightQueue queue =
                ((StarLightLightingProvider) light).starlight$getLightEngine().getServerLightQueue();
        queue.queueChunkLightTask(new ChunkPos(x, z), () -> false, PrioritisedExecutor.Priority.NORMAL)
                .queueOrRunTask(() -> done.complete(null));
        return done;
    }

    /** {@code GrowingPlantBlock.growthDirection} (protected). */
    @Override
    public Direction growthDirection(GrowingPlantBlock block) {
        return (Direction) GROWTH_DIRECTION.get(block);
    }

    /** {@code container.data}'s storage and palette, read from one snapshot of the field. */
    @Override
    @SuppressWarnings("unchecked")
    public <T> PaletteData<T> paletteData(PalettedContainer<T> container) {
        Object data = DATA.getVolatile(container);
        try {
            return new PaletteData<>((BitStorage) DATA_STORAGE.invokeExact(data), (Palette<T>) DATA_PALETTE.invokeExact(data));
        } catch (Throwable e) {
            throw new IllegalStateException("Reading a palette container failed", e);
        }
    }

    @Override
    public BlockState placementState(BlockItem item, BlockPlaceContext context) {
        try {
            return (BlockState) PLACEMENT_STATE.invokeExact(item, context);
        } catch (Throwable e) {
            throw rethrow(e);
        }
    }

    @Override
    public boolean placeBlock(BlockItem item, BlockPlaceContext context, BlockState state) {
        try {
            return (boolean) PLACE_BLOCK.invokeExact(item, context, state);
        } catch (Throwable e) {
            throw rethrow(e);
        }
    }

    @Override
    public BlockState updateBlockStateFromTag(BlockItem item, BlockPos pos, Level world, ItemStack stack,
                                              BlockState state) {
        try {
            return (BlockState) STATE_FROM_TAG.invokeExact(item, pos, world, stack, state);
        } catch (Throwable e) {
            throw rethrow(e);
        }
    }

    @Override
    public boolean updateCustomBlockEntityTag(BlockItem item, BlockPos pos, Level world, Player player, ItemStack stack,
                                              BlockState state) {
        try {
            return (boolean) CUSTOM_BLOCK_ENTITY_TAG.invokeExact(item, pos, world, player, stack, state);
        } catch (Throwable e) {
            throw rethrow(e);
        }
    }

    @Override
    public SoundEvent placeSound(BlockItem item, BlockState state) {
        try {
            return (SoundEvent) PLACE_SOUND.invokeExact(item, state);
        } catch (Throwable e) {
            throw rethrow(e);
        }
    }

    @Override
    public void updateBlockEntityComponents(Level world, BlockPos pos, ItemStack stack) {
        try {
            BLOCK_ENTITY_COMPONENTS.invokeExact(world, pos, stack);
        } catch (Throwable e) {
            throw rethrow(e);
        }
    }

    @Override
    public Block wallBlock(StandingAndWallBlockItem item) {
        return (Block) WALL_BLOCK.get(item);
    }

    /** What a method handle threw, unchecked as the game threw it (an Error or RuntimeException goes on as it is). */
    private static RuntimeException rethrow(Throwable e) {
        if (e instanceof RuntimeException runtime) return runtime;
        if (e instanceof Error error) throw error;
        return new IllegalStateException(e);
    }

    /**
     * A physics-off write on Paper, without mixins (the route WorldEdit's Paper adapter takes): {@code Level.setBlock}
     * with {@link Block#UPDATE_KNOWN_SHAPE} (no neighbour shape updates), no notify flags and depth 0 (no block updates,
     * no client packet), and {@link #NO_PLACE} so the new state's {@code onPlace} does not run. The old state's
     * {@code onRemove} is skipped by Paper's own {@code Level.isBlockPlaceCancelled} switch (set while it reverts a
     * cancelled placement): with it set, {@code LevelChunk.setBlockState} removes a block entity the new block doesn't
     * share instead of calling {@code onRemove}. The world's points of interest, heightmaps and light checks are updated
     * as in any write. Server thread only.
     */
    public static void setBlockWithoutCallbacks(ServerLevel world, BlockPos pos, BlockState state) {
        boolean cancelled = world.isBlockPlaceCancelled;
        world.isBlockPlaceCancelled = true;
        try {
            world.setBlock(pos, state, Block.UPDATE_KNOWN_SHAPE | NO_PLACE, 0);
        } finally {
            world.isBlockPlaceCancelled = cancelled;
        }
    }
}
