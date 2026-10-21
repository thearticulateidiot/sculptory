package dev.sculptory.mc.client.editor.render.ghost;

import java.util.Objects;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
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
 * <p>Checked with javap against Minecraft 1.21.1 (Mojang names): the abstract methods of {@code BlockAndTintGetter},
 * {@code BlockGetter} and {@code LevelHeightAccessor} are {@code getShade}, {@code getLightEngine},
 * {@code getBlockTint}, {@code getBlockEntity}, {@code getBlockState}, {@code getFluidState}, {@code getHeight} and
 * {@code getMinBuildHeight}. The light defaults ({@code getBrightness}, {@code getRawBrightness}, {@code canSeeSky}) go
 * through the light engine with <em>local</em> positions, so they are overridden; {@code getLightEngine}
 * itself returns the world's provider for code that asks for it directly (such a caller sees world light at local
 * positions). Fabric's {@code FabricBlockView} methods keep their defaults (no block entities, no biomes).
 *
 * <p>One instance per mesh job: it holds a mutable position and is not thread-safe. The sections and the world it
 * reads are safe to read from mesher threads (vanilla's own section builders read world light and colours there).
 */
final class GhostBlockView implements BlockAndTintGetter {
    /** Ghost volumes can sit at any local height; keep every position "inside the world". */
    private static final int BOTTOM_Y = -(1 << 24);
    private static final int HEIGHT = 1 << 25;
    private static final int FULL_LIGHT = 15;
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private final GhostSection[] neighbourhood;
    private final int centreX;
    private final int centreY;
    private final int centreZ;
    private final ClientLevel world;
    private final GhostPlacement.LightMode lightMode;
    private final GhostMapping mapping;
    private final BlockPos.MutableBlockPos placed = new BlockPos.MutableBlockPos();

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
            ClientLevel world,
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
    public int getMinBuildHeight() {
        return BOTTOM_Y;
    }

    // ---- Light and colour ----

    @Override
    public float getShade(Direction direction, boolean shaded) {
        return world.getShade(direction, shaded);
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return world.getLightEngine();
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
        return world.getBlockTint(place(pos), colorResolver);
    }

    @Override
    public int getBrightness(LightLayer type, BlockPos pos) {
        if (lightMode == GhostPlacement.LightMode.FLAT) {
            return FULL_LIGHT;
        }
        return world.getBrightness(type, place(pos));
    }

    @Override
    public int getRawBrightness(BlockPos pos, int ambientDarkness) {
        if (lightMode == GhostPlacement.LightMode.FLAT) {
            return FULL_LIGHT;
        }
        return world.getRawBrightness(place(pos), ambientDarkness);
    }

    @Override
    public boolean canSeeSky(BlockPos pos) {
        if (lightMode == GhostPlacement.LightMode.FLAT) {
            return true;
        }
        return world.canSeeSky(place(pos));
    }

    /** The placed world position of a local position (reuses one mutable position). */
    private BlockPos place(BlockPos local) {
        int x = local.getX();
        int z = local.getZ();
        return placed.set(mapping.worldX(x, z), mapping.worldY(local.getY()), mapping.worldZ(x, z));
    }
}
