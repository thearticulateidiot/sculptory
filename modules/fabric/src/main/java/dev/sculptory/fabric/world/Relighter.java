package dev.sculptory.fabric.world;

import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.ChunkNibbleArray;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.light.LightingProvider;

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
    public static int relightSection(ServerWorld world, int sx, int sy, int sz) {
        if (sy < world.getBottomSectionCoord() || sy >= world.getTopSectionCoord()) return 0;
        WorldChunk chunk = world.getChunkManager().getWorldChunk(sx, sz);
        if (chunk == null) return 0;
        LightingProvider light = world.getChunkManager().getLightingProvider();
        ChunkSectionPos sectionPos = ChunkSectionPos.from(sx, sy, sz);
        ChunkNibbleArray blockLight = light.get(LightType.BLOCK).getLightSection(sectionPos);
        // An absent sky section is not "no sky light" (above the terrain it is full): nothing is skipped then.
        ChunkNibbleArray skyLight = world.getDimension().hasSkyLight()
                ? light.get(LightType.SKY).getLightSection(sectionPos) : null;
        boolean skyKnown = !world.getDimension().hasSkyLight() || skyLight != null;
        ChunkSection section = chunk.getSection(world.sectionCoordToIndex(sy));
        BlockPos.Mutable pos = new BlockPos.Mutable();
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
    private static boolean dark(BlockState state, ServerWorld world, BlockPos pos, ChunkNibbleArray blockLight,
                                ChunkNibbleArray skyLight, int x, int y, int z) {
        if (state.getLuminance() != 0 || !state.isOpaqueFullCube(world, pos)) return false;
        if (blockLight != null && blockLight.get(x, y, z) != 0) return false;
        return skyLight == null || skyLight.get(x, y, z) == 0;
    }
}
