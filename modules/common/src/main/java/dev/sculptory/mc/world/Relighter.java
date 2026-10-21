package dev.sculptory.mc.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * Update blocks' "fix lighting": asks the light engine to check
 * the cells of a section, as it does for a cell whose block changes. It recomputes the cell's block and sky light from
 * its own block and its neighbours', clearing light that nothing gives any more and spreading light that is missing.
 * The engine works the checks off on its own thread; light is never recorded in history. Server thread only.
 *
 * <p>A cell whose light is right whatever its neighbours hold is not checked: an opaque full cube that gives no light
 * and holds none (the stone of a hillside). Each check allocates a light-engine task, so skipping them keeps a large
 * job's garbage down; a light source among them is still checked, and spreads to its neighbours.
 */
public final class Relighter {
    private Relighter() {}

    /**
     * Queues a light check of the cells of section (sx, sy, sz) but those that need none; nothing when its chunk is not
     * loaded. Returns the cells checked.
     */
    public static int relightSection(ServerLevel world, int sx, int sy, int sz) {
        if (sy < world.getMinSection() || sy >= world.getMaxSection()) return 0;
        LevelChunk chunk = world.getChunkSource().getChunkNow(sx, sz);
        if (chunk == null) return 0;
        LevelLightEngine light = world.getChunkSource().getLightEngine();
        SectionPos sectionPos = SectionPos.of(sx, sy, sz);
        DataLayer blockLight = light.getLayerListener(LightLayer.BLOCK).getDataLayerData(sectionPos);
        // An absent sky section is not "no sky light" (above the terrain it is full): nothing is skipped then.
        DataLayer skyLight = world.dimensionType().hasSkyLight()
                ? light.getLayerListener(LightLayer.SKY).getDataLayerData(sectionPos) : null;
        boolean skyKnown = !world.dimensionType().hasSkyLight() || skyLight != null;
        LevelChunkSection section = chunk.getSection(world.getSectionIndexFromSectionY(sy));
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int ox = sx << 4, oy = sy << 4, oz = sz << 4;
        int checked = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    pos.set(ox + x, oy + y, oz + z);
                    if (skyKnown && dark(section.getBlockState(x, y, z), world, pos, blockLight, skyLight, x, y, z)) {
                        continue;
                    }
                    light.checkBlock(pos);
                    checked++;
                }
            }
        }
        return checked;
    }

    /** An opaque full cube without light of its own, holding none: already right. */
    private static boolean dark(BlockState state, ServerLevel world, BlockPos pos, DataLayer blockLight,
                                DataLayer skyLight, int x, int y, int z) {
        if (state.getLightEmission() != 0 || !state.isSolidRender(world, pos)) return false;
        if (blockLight != null && blockLight.get(x, y, z) != 0) return false;
        return skyLight == null || skyLight.get(x, y, z) == 0;
    }
}
