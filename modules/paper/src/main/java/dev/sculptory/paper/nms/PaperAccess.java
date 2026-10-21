package dev.sculptory.paper.nms;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.GrowingPlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * What modules/common reaches through its access widener on Fabric and NeoForge, and the one write that needs a mixin
 * there, done Paper's way. The modules/paper build ({@code commonForPaper}) rewrites the common sources' uses to these
 * methods. Paper has no access transformers for plugins, so non-public members are read through method handles
 * ({@link MethodHandles#privateLookupIn}: the server's classes are in the unnamed module, which a plugin may open).
 * Server classes are Mojang-named at run time on Paper 1.20.5+, so the names below are the ones the dev bundle shows.
 */
public final class PaperAccess {
    /**
     * CraftBukkit's custom {@code Level.setBlock} flag: {@code LevelChunk.setBlockState(pos, state, moved, doPlace)}
     * with {@code doPlace = false}, so the new state's {@code onPlace} does not run.
     */
    public static final int NO_PLACE = 1024;

    private static final VarHandle GROWTH_DIRECTION;
    private static final VarHandle DATA;
    private static final MethodHandle DATA_STORAGE;
    private static final MethodHandle DATA_PALETTE;

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
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private PaperAccess() {
    }

    /** {@code GrowingPlantBlock.growthDirection} (protected). */
    public static Direction growthDirection(GrowingPlantBlock block) {
        return (Direction) GROWTH_DIRECTION.get(block);
    }

    /** A section container's packed indices and palette as one read of its {@code data} (replaced as a whole on resize). */
    public record PaletteData<T>(BitStorage storage, Palette<T> palette) {
    }

    /** {@code container.data}'s storage and palette, read from one snapshot of the field. */
    @SuppressWarnings("unchecked")
    public static <T> PaletteData<T> data(PalettedContainer<T> container) {
        Object data = DATA.getVolatile(container);
        try {
            return new PaletteData<>((BitStorage) DATA_STORAGE.invokeExact(data), (Palette<T>) DATA_PALETTE.invokeExact(data));
        } catch (Throwable e) {
            throw new IllegalStateException("Reading a palette container failed", e);
        }
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
