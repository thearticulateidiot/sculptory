package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.EditTestSupport.loadAndForce;
import static dev.sculptory.fabric.gametest.EngineTestSupport.box;
import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.forceChunks;
import static dev.sculptory.fabric.gametest.EngineTestSupport.pos;
import static dev.sculptory.fabric.gametest.EngineTestSupport.regionCorner;

import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.fabric.engine.impl.EngineRuntime;
import dev.sculptory.fabric.world.BlockWriter;
import dev.sculptory.fabric.world.FabricWorldReader;
import dev.sculptory.server.platform.WriteOptions;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.world.chunk.PalettedContainer;

/**
 * {@link FabricWorldReader#copySection}, {@link FabricWorldReader#get} and {@link FabricWorldReader#getColumn} (which read
 * the section's palette and packed indices directly) return exactly what {@code world.getBlockState} reads, cell for
 * cell, for every kind of vanilla section storage.
 */
public final class WorldReaderGameTest implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_reader_sections",
            tickLimit = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT)
    public void copySectionMatchesEveryCell(TestContext context) {
        EngineRuntime runtime = EngineTestSupport.runtime(context);
        ServerWorld world = context.getWorld();
        int[] at = regionCorner(context, 324);
        int x0 = at[0], z0 = at[1];
        // Seven sections side by side at y 96..111: uniform, mixed (array palette), stale palette, global palette, air
        // (singular palette), block entity, and 100 states (hash-map palette).
        Box region = box(x0, 96, z0, x0 + 7 * 16 - 1, 111, z0 + 15);
        loadAndForce(world, region);
        BlockWriter writer = runtime.writer(world, WriteOptions.DEFAULT);
        int stone = handle(runtime, "minecraft:stone");
        List<Integer> many = new ArrayList<>();
        for (BlockState state : Block.STATE_IDS) {
            if (!state.isAir() && !state.hasBlockEntity() && state.getFluidState().isEmpty()) many.add(Block.getRawIdFromState(state));
            if (many.size() == 600) break;
        }
        for (int i = 0; i < SectionBuffer.SIZE; i++) {
            int x = SectionBuffer.localX(i), y = 96 + SectionBuffer.localY(i), z = z0 + SectionBuffer.localZ(i);
            writer.write(x0 + x, y, z, stone, null); // uniform
            writer.write(x0 + 16 + x, y, z, many.get((i * 7 + i / 16) % 6), null); // mixed
            writer.write(x0 + 32 + x, y, z, many.get(10 + i % 5), null); // then overwritten below: stale entries
            writer.write(x0 + 48 + x, y, z, many.get(i % many.size()), null); // > 256 states: the global palette
            writer.write(x0 + 96 + x, y, z, many.get(i % 100), null); // 17..256 states: the hash-map palette
        }
        for (int i = 0; i < SectionBuffer.SIZE; i++) {
            int x = SectionBuffer.localX(i), y = 96 + SectionBuffer.localY(i), z = z0 + SectionBuffer.localZ(i);
            writer.write(x0 + 32 + x, y, z, many.get(20 + i % 2), null);
        }
        writer.write(x0 + 80 + 3, 100, z0 + 4, handle(runtime, "minecraft:chest[facing=north]"), null);
        ((ChestBlockEntity) world.getBlockEntity(pos(x0 + 83, 100, z0 + 4))).setStack(0, new ItemStack(Items.DIAMOND, 3));

        PalettedContainer<BlockState> global = world.getChunk(x0 + 48 >> 4, z0 >> 4)
                .getSection(world.sectionCoordToIndex(6)).getBlockStateContainer();
        check(global.data.palette().getSize() > SectionBuffer.SIZE, "the many-state section does not use the global palette: "
                + global.data.palette().getSize() + " entries");

        FabricWorldReader reader = runtime.reader(world);
        PalettedContainer<BlockState> hashed = world.getChunk(x0 + 96 >> 4, z0 >> 4)
                .getSection(world.sectionCoordToIndex(6)).getBlockStateContainer();
        int hashedSize = hashed.data.palette().getSize();
        check(hashedSize > 16 && hashedSize <= 256, "the 100-state section has a palette of " + hashedSize);
        for (int s = 0; s < 7; s++) {
            int sx = (x0 >> 4) + s, sy = 6, sz = z0 >> 4;
            SectionBuffer section = new SectionBuffer();
            reader.copySection(sx, sy, sz, section);
            check(section.isDense(), "section " + s + " is not dense");
            for (int i = 0; i < SectionBuffer.SIZE; i++) {
                int x = (sx << 4) + SectionBuffer.localX(i), y = (sy << 4) + SectionBuffer.localY(i),
                        z = (sz << 4) + SectionBuffer.localZ(i);
                int expected = Block.getRawIdFromState(world.getBlockState(pos(x, y, z)));
                check(section.get(i) == expected, "section " + s + " at " + x + "," + y + "," + z + ": got "
                        + Block.getStateFromRawId(section.get(i)) + ", world has " + Block.getStateFromRawId(expected));
                check(reader.get(x, y, z) == expected, "get at " + x + "," + y + "," + z + ": got "
                        + Block.getStateFromRawId(reader.get(x, y, z)) + ", world has " + Block.getStateFromRawId(expected));
            }
            check(section.compact().paletteSize() == section.paletteSize(), "section " + s + " keeps unused palette entries");
            int tiles = s == 5 ? 1 : 0;
            check(section.tileCount() == tiles, "section " + s + ": " + section.tileCount() + " tiles");
        }
        forceChunks(world, region, false);
        context.complete();
    }

    /**
     * {@link FabricWorldReader#getColumn} (the palette-direct column read Surface Smooth uses) gives exactly what
     * {@link FabricWorldReader#get} gives cell by cell: columns across uniform, mixed, stale, global-palette, hash-map
     * palette and all-air sections, starting and ending mid-section, and the whole build height with cells beyond it.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_reader_columns",
            tickLimit = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT)
    public void getColumnMatchesGetCellByCell(TestContext context) {
        EngineRuntime runtime = EngineTestSupport.runtime(context);
        ServerWorld world = context.getWorld();
        int[] at = regionCorner(context, 960);
        int x0 = at[0], z0 = at[1];
        // Sections 5 to 7 (y 80..127) of five chunks side by side, each chunk's storage of another kind.
        Box region = box(x0, 80, z0, x0 + 5 * 16 - 1, 127, z0 + 15);
        loadAndForce(world, region);
        BlockWriter writer = runtime.writer(world, WriteOptions.DEFAULT);
        int stone = handle(runtime, "minecraft:stone");
        List<Integer> many = new ArrayList<>();
        for (BlockState state : Block.STATE_IDS) {
            if (!state.isAir() && !state.hasBlockEntity() && state.getFluidState().isEmpty()) many.add(Block.getRawIdFromState(state));
            if (many.size() == 600) break;
        }
        for (int y = 80; y <= 127; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int i = (y << 8) | (z << 4) | x;
                    writer.write(x0 + x, y, z0 + z, y < 100 ? stone : many.get(i % 3), null); // uniform, then mixed
                    writer.write(x0 + 16 + x, y, z0 + z, many.get(10 + i % 5), null); // overwritten below: stale
                    writer.write(x0 + 32 + x, y, z0 + z, many.get(i % many.size()), null); // the global palette
                    writer.write(x0 + 48 + x, y, z0 + z, many.get(i % 100), null); // the hash-map palette
                    if (y % 16 == 7) writer.write(x0 + 64 + x, y, z0 + z, many.get(i % 2), null); // one layer, air
                }
            }
        }
        for (int y = 80; y <= 127; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) writer.write(x0 + 16 + x, y, z0 + z, many.get(20 + (x + y) % 2), null);
            }
        }
        FabricWorldReader reader = runtime.reader(world);
        int bottom = world.getBottomY(), top = world.getTopY();
        int columns = 0;
        for (int x = x0; x < x0 + 80; x++) {
            for (int z = z0; z < z0 + 16; z++) {
                int y0 = 70 + Math.floorMod(x * 7 + z, 13), count = 20 + Math.floorMod(x + z * 5, 50);
                checkColumn(world, reader, x, z, y0, count);
                columns++;
            }
        }
        for (int x = x0; x < x0 + 80; x += 17) checkColumn(world, reader, x, z0 + 3, bottom - 3, top - bottom + 6);
        check(columns == 80 * 16, "columns read: " + columns);
        forceChunks(world, region, false);
        context.complete();
    }

    private static void checkColumn(ServerWorld world, FabricWorldReader reader, int x, int z, int y0, int count) {
        int[] into = new int[count + 4];
        java.util.Arrays.fill(into, -7);
        reader.getColumn(x, z, y0, count, into, 2);
        check(into[0] == -7 && into[1] == -7 && into[count + 2] == -7 && into[count + 3] == -7,
                "getColumn wrote outside its cells at " + x + "," + z);
        for (int i = 0; i < count; i++) {
            int y = y0 + i;
            // Beyond the build height the reader gives air (the world, void air).
            int expected = y < world.getBottomY() || y >= world.getTopY()
                    ? Block.getRawIdFromState(net.minecraft.block.Blocks.AIR.getDefaultState())
                    : Block.getRawIdFromState(world.getBlockState(pos(x, y, z)));
            check(into[2 + i] == expected, "column " + x + "," + z + " at y " + y + ": got "
                    + Block.getStateFromRawId(into[2 + i]) + ", the world has " + Block.getStateFromRawId(expected));
            check(reader.get(x, y, z) == expected, "get at " + x + "," + y + "," + z);
        }
    }

    private static int handle(EngineRuntime runtime, String spec) {
        return EngineTestSupport.handle(runtime.states(), spec);
    }
}
