package dev.sculptory.fabric.client.editor.render.ghost;

import java.util.Objects;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockRenderView;
import net.minecraft.world.LightType;
import net.minecraft.world.biome.ColorResolver;
import net.minecraft.world.chunk.light.LightingProvider;
import org.jetbrains.annotations.Nullable;

/**
 * The world a ghost section is meshed in: the volume's own cells, in local coordinates, so faces between ghost blocks
 * are culled; everything outside the volume (absent cells included) reads as air, so the preview's outer faces are
 * drawn.
 *
 * <p>Light and colour:
 * <ul>
 *   <li>{@link GhostPlacement.LightMode#FLAT}: sky and block light 15 everywhere (full-bright; vanilla's directional
 *       face shading and ambient occlusion still apply).</li>
 *   <li>{@link GhostPlacement.LightMode#WORLD}: the client world's light at the cell's placed position.</li>
 * </ul>
 * Biome colours (grass, foliage, water) always come from the client world at the placed position. Positions are
 * mapped with the placement captured when the mesh job was made.
 *
 * <p>Checked with javap against yarn 1.21.1+build.3: the abstract methods of {@code BlockRenderView},
 * {@code BlockView} and {@code HeightLimitView} are {@code getBrightness}, {@code getLightingProvider},
 * {@code getColor}, {@code getBlockEntity}, {@code getBlockState}, {@code getFluidState}, {@code getHeight} and
 * {@code getBottomY}. The light defaults ({@code getLightLevel}, {@code getBaseLightLevel}, {@code isSkyVisible}) go
 * through the lighting provider with <em>local</em> positions, so they are overridden; {@code getLightingProvider}
 * itself returns the world's provider for code that asks for it directly (such a caller sees world light at local
 * positions). Fabric's {@code FabricBlockView} methods keep their defaults (no block entities, no biomes).
 *
 * <p>One instance per mesh job: it holds a mutable position and is not thread-safe. The sections and the world it
 * reads are safe to read from mesher threads (vanilla's own section builders read world light and colours there).
 */
final class GhostBlockView implements BlockRenderView {
    /** Ghost volumes can sit at any local height; keep every position "inside the world". */
    private static final int BOTTOM_Y = -(1 << 24);
    private static final int HEIGHT = 1 << 25;
    private static final int FULL_LIGHT = 15;
    private static final BlockState AIR = Blocks.AIR.getDefaultState();

    private final GhostSection[] neighbourhood;
    private final int centreX;
    private final int centreY;
    private final int centreZ;
    private final ClientWorld world;
    private final GhostPlacement.LightMode lightMode;
    private final GhostMapping mapping;
    private final BlockPos.Mutable placed = new BlockPos.Mutable();

    /**
     * @param neighbourhood the 3×3×3 sections around the meshed one, index {@link #neighbourIndex}; nulls for absent
     * @param world the client world light and colours are read from
     * @param mapping local-to-world cell mapping of the placement when the job was made
     */
    GhostBlockView(
            GhostSection[] neighbourhood,
            int centreX,
            int centreY,
            int centreZ,
            ClientWorld world,
            GhostPlacement.LightMode lightMode,
            GhostMapping mapping) {
        if (neighbourhood.length != 27) {
            throw new IllegalArgumentException("neighbourhood must hold 27 sections");
        }
        this.neighbourhood = neighbourhood;
        this.centreX = centreX;
        this.centreY = centreY;
        this.centreZ = centreZ;
        this.world = Objects.requireNonNull(world, "world");
        this.lightMode = Objects.requireNonNull(lightMode, "lightMode");
        this.mapping = Objects.requireNonNull(mapping, "mapping");
    }

    /** Index into the neighbourhood of the section offset (dx, dy, dz), each -1..1. */
    static int neighbourIndex(int dx, int dy, int dz) {
        return (dx + 1) * 9 + (dy + 1) * 3 + (dz + 1);
    }

    // ---- Blocks ----

    @Override
    public BlockState getBlockState(BlockPos pos) {
        int dx = (pos.getX() >> 4) - centreX;
        int dy = (pos.getY() >> 4) - centreY;
        int dz = (pos.getZ() >> 4) - centreZ;
        if (dx < -1 || dx > 1 || dy < -1 || dy > 1 || dz < -1 || dz > 1) {
            return AIR;
        }
        GhostSection section = neighbourhood[neighbourIndex(dx, dy, dz)];
        if (section == null) {
            return AIR;
        }
        int handle = section.handle(((pos.getY() & 15) << 8) | ((pos.getZ() & 15) << 4) | (pos.getX() & 15));
        return handle < 0 ? AIR : GhostStates.state(handle);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    /** Ghosts have no block entities. */
    @Override
    public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public int getHeight() {
        return HEIGHT;
    }

    @Override
    public int getBottomY() {
        return BOTTOM_Y;
    }

    // ---- Light and colour ----

    @Override
    public float getBrightness(Direction direction, boolean shaded) {
        return world.getBrightness(direction, shaded);
    }

    @Override
    public LightingProvider getLightingProvider() {
        return world.getLightingProvider();
    }

    @Override
    public int getColor(BlockPos pos, ColorResolver colorResolver) {
        return world.getColor(place(pos), colorResolver);
    }

    @Override
    public int getLightLevel(LightType type, BlockPos pos) {
        if (lightMode == GhostPlacement.LightMode.FLAT) {
            return FULL_LIGHT;
        }
        return world.getLightLevel(type, place(pos));
    }

    @Override
    public int getBaseLightLevel(BlockPos pos, int ambientDarkness) {
        if (lightMode == GhostPlacement.LightMode.FLAT) {
            return FULL_LIGHT;
        }
        return world.getBaseLightLevel(place(pos), ambientDarkness);
    }

    @Override
    public boolean isSkyVisible(BlockPos pos) {
        if (lightMode == GhostPlacement.LightMode.FLAT) {
            return true;
        }
        return world.isSkyVisible(place(pos));
    }

    /** The placed world position of a local position (reuses one mutable position). */
    private BlockPos place(BlockPos local) {
        int x = local.getX();
        int z = local.getZ();
        return placed.set(mapping.worldX(x, z), mapping.worldY(local.getY()), mapping.worldZ(x, z));
    }
}
