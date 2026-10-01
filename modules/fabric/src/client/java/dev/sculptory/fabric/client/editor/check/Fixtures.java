package dev.sculptory.fabric.client.editor.check;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.sculptory.core.Box;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.entity.Entity;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * Builds the scenarios' fixture areas on the integrated server at the start of a singleplayer check: a grid of
 * {@link Area}s in the sky next to the world spawn, each cleared to air over a stone floor, then built by its
 * {@link Fixture}. Their chunks are loaded and kept loaded (forced), so an area far from the player still undoes.
 * Server thread only.
 */
public final class Fixtures {
    /** The areas' first air layer: above any terrain near spawn, below the build limit with room to spare. */
    public static final int Y0 = 200;
    /** Distance between the corners of neighbouring areas (a gap of 8 blocks between them). */
    public static final int SPACING = 48;
    private static final int COLUMNS = 4;
    /** Writes without neighbour updates or shape changes, like a structure: the fixture stays exactly as built. */
    private static final int FLAGS = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;

    /** How one area is built, in area-relative coordinates. */
    public record Fixture(String area, Consumer<Build> build) {
    }

    private Fixtures() {}

    /**
     * The areas' places: a grid starting at the chunk corner 64 blocks east and south of {@code spawn}, in the order
     * given.
     */
    public static Map<String, Area> layout(List<String> names, int spawnX, int spawnZ) {
        int baseX = ((spawnX + 64) >> 4) << 4;
        int baseZ = ((spawnZ + 64) >> 4) << 4;
        Map<String, Area> areas = new LinkedHashMap<>();
        for (int i = 0; i < names.size(); i++) {
            areas.put(names.get(i), new Area(names.get(i), baseX + (i % COLUMNS) * SPACING, Y0,
                    baseZ + (i / COLUMNS) * SPACING));
        }
        return areas;
    }

    /** Loads and forces the area's chunks, clears it, lays its floor and builds it. */
    public static void build(ServerWorld world, Area area, Consumer<Build> fixture) {
        Box box = area.box();
        for (int cx = box.min().x() >> 4; cx <= box.max().x() >> 4; cx++) {
            for (int cz = box.min().z() >> 4; cz <= box.max().z() >> 4; cz++) {
                world.setChunkForced(cx, cz, true);
                world.getChunk(cx, cz);
            }
        }
        Build build = new Build(world, area);
        // Entities left from an earlier build of this world (a rerun on the same copy).
        for (Entity entity : world.getOtherEntities(null, build.worldBox(box))) {
            if (!entity.isPlayer()) {
                entity.discard();
            }
        }
        build.fill(0, -Area.FLOOR, 0, Area.SIZE - 1, Area.HEIGHT - 1, Area.SIZE - 1, Blocks.AIR.getDefaultState());
        build.fill(0, -Area.FLOOR, 0, Area.SIZE - 1, -1, Area.SIZE - 1, Blocks.STONE.getDefaultState());
        fixture.accept(build);
    }

    /** Area-relative writes into the server world. */
    public static final class Build {
        private final ServerWorld world;
        private final Area area;
        private final BlockPos.Mutable at = new BlockPos.Mutable();

        Build(ServerWorld world, Area area) {
            this.world = world;
            this.area = area;
        }

        public ServerWorld world() {
            return world;
        }

        public Area area() {
            return area;
        }

        /** A block state from its text: {@code minecraft:stone}, {@code minecraft:wall_torch[facing=east]}. */
        public static BlockState state(String text) {
            try {
                return BlockArgumentParser.block(Registries.BLOCK.getReadOnlyWrapper(), text, false).blockState();
            } catch (CommandSyntaxException e) {
                throw new IllegalArgumentException("Not a block state: " + text, e);
            }
        }

        public void set(int x, int y, int z, String state) {
            set(x, y, z, state(state));
        }

        public void set(int x, int y, int z, BlockState state) {
            world.setBlockState(at.set(area.x(x), area.y(y), area.z(z)), state, FLAGS);
        }

        public void fill(int ax, int ay, int az, int bx, int by, int bz, String state) {
            fill(ax, ay, az, bx, by, bz, state(state));
        }

        public void fill(int ax, int ay, int az, int bx, int by, int bz, BlockState state) {
            for (int y = Math.min(ay, by); y <= Math.max(ay, by); y++) {
                for (int z = Math.min(az, bz); z <= Math.max(az, bz); z++) {
                    for (int x = Math.min(ax, bx); x <= Math.max(ax, bx); x++) {
                        set(x, y, z, state);
                    }
                }
            }
        }

        /** The world position of a relative cell. */
        public BlockPos pos(int x, int y, int z) {
            return new BlockPos(area.x(x), area.y(y), area.z(z));
        }

        net.minecraft.util.math.Box worldBox(Box box) {
            return new net.minecraft.util.math.Box(box.min().x(), box.min().y(), box.min().z(), box.max().x() + 1,
                    box.max().y() + 1, box.max().z() + 1);
        }
    }
}
