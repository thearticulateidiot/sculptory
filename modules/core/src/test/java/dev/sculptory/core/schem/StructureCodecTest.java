package dev.sculptory.core.schem;

import static dev.sculptory.core.schem.SchematicSamples.DATA_VERSION;
import static dev.sculptory.core.schem.SchematicSamples.METADATA;
import static dev.sculptory.core.schem.SchematicSamples.compounds;
import static dev.sculptory.core.schem.SchematicSamples.ints;
import static dev.sculptory.core.schem.SchematicSamples.state;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.entity.EntityNbt;
import dev.sculptory.core.entity.EntitySnapshot;
import dev.sculptory.core.nbt.NbtCompound;
import dev.sculptory.core.nbt.NbtIo;
import dev.sculptory.core.nbt.NbtLimitException;
import dev.sculptory.core.nbt.NbtLimits;
import dev.sculptory.core.nbt.NbtList;
import dev.sculptory.core.nbt.NbtTag;
import dev.sculptory.core.schem.SchematicException.Kind;
import dev.sculptory.core.testing.FakeStateSpace;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class StructureCodecTest {
    private final FakeStateSpace states = new FakeStateSpace();
    private final DataFixHook identity = DataFixHook.identity(DATA_VERSION);
    private final int air = states.air();
    private final int stone = states.state("minecraft:stone");
    private final int dirt = states.state("minecraft:dirt");
    private final int chest = states.state("minecraft:chest[facing=east]");

    private byte[] write(Clipboard clipboard) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StructureCodec.write(out, clipboard, METADATA, DATA_VERSION);
        return out.toByteArray();
    }

    private Schematic read(byte[] file) throws IOException {
        return StructureCodec.read(new ByteArrayInputStream(file), states, SchematicCodec.Limits.DEFAULT, identity);
    }

    private Schematic decode(NbtCompound root) throws SchematicException {
        return StructureCodec.decode(root, states, SchematicCodec.Limits.DEFAULT, identity);
    }

    private Kind failure(NbtCompound root) {
        return assertThrows(SchematicException.class, () -> decode(root)).kind();
    }

    private static NbtCompound block(int x, int y, int z, int state) {
        return NbtCompound.builder().put("pos", ints(x, y, z)).putInt("state", state).build();
    }

    /** A structure as the game writes one: no Sculptory compound. */
    private static NbtCompound.Builder structure(int[] size, NbtList palette, NbtCompound... blocks) {
        return NbtCompound.builder()
                .putInt("DataVersion", DATA_VERSION)
                .put("size", ints(size))
                .put("palette", palette)
                .put("blocks", compounds(blocks))
                .put("entities", NbtList.EMPTY);
    }

    // ================================================================== writing and round trips

    @Test
    void writesTheStructureLayout() throws SchematicException {
        Clipboard clipboard = SchematicSamples.richClipboard(states);
        NbtCompound root = StructureCodec.encode(clipboard, METADATA, DATA_VERSION);
        assertEquals(DATA_VERSION, root.getInt("DataVersion"));
        assertEquals(ints(6, 4, 5), root.getList("size"));
        List<NbtCompound> palette = root.getList("palette").compounds();
        List<NbtCompound> blocks = root.getList("blocks").compounds();
        assertEquals(120, blocks.size(), "every present cell, air included");
        NbtCompound first = blocks.get(0);
        assertEquals(ints(0, 0, 0), first.getList("pos"));
        assertEquals(states.format(clipboard.get(0, 0, 0)),
                FileImport.entrySpec(palette.get(first.getInt("state")), "entry"));
        long tiles = blocks.stream().filter(b -> b.contains("nbt")).count();
        assertEquals(clipboard.tileCount(), tiles);
        NbtCompound nbt = blocks.stream().filter(b -> b.contains("nbt")).findFirst().orElseThrow().getCompound("nbt");
        assertEquals("minecraft:chest", nbt.getString("id"));
        assertTrue(!nbt.contains("x") && !nbt.contains("y") && !nbt.contains("z"), "no position in the block entity");
        List<NbtCompound> entities = root.getList("entities").compounds();
        assertEquals(2, entities.size());
        NbtCompound frame = entities.stream()
                .filter(e -> "minecraft:item_frame".equals(e.getCompound("nbt").getString("id"))).findFirst().orElseThrow();
        assertEquals(ints(1, 2, 0), frame.getList("blockPos"), "a hanging entity's block");
        assertArrayEquals(new double[] {1.5, 2.5, 0.03125}, EntityNbt.position(frame.toBuilder()
                .put("Pos", frame.get("pos")).build()));
        NbtCompound ours = root.getCompound("Sculptory");
        assertArrayEquals(new int[] {3, 1, 2}, ours.getIntArray("Anchor"));
        assertEquals("golden", ours.getString("Name"));
    }

    @Test
    void roundTripIsExact() throws IOException {
        Clipboard original = SchematicSamples.richClipboard(states);
        Schematic read = read(write(original));
        assertEquals(SchematicFormat.STRUCTURE, read.format());
        assertEquals(DATA_VERSION, read.dataVersion());
        assertTrue(read.report().isLossless(), read.report().toString());
        assertEquals(original.contentHash(), read.clipboard().contentHash(), "blocks, tiles, entities and anchor");
        assertEquals("structure:golden", read.clipboard().source());
        assertEquals("tester", read.metadata().author());
        assertEquals(METADATA.sculptory().tags(), read.metadata().sculptory().tags());
    }

    @Test
    void everyStateRoundTrips() throws IOException {
        Clipboard every = SchematicSamples.everyState(states);
        Schematic read = read(write(every));
        assertEquals(every.contentHash(), read.clipboard().contentHash());
    }

    @Test
    void absentCellsAreLeftOutAndAirIsKept() throws IOException {
        Clipboard sparse = SchematicSamples.sparseClipboard(states);
        NbtCompound root = StructureCodec.encode(sparse, SchematicMetadata.EMPTY, DATA_VERSION);
        assertEquals(sparse.cellCount(), root.getList("blocks").size(), "absent cells are not listed");
        Schematic read = read(write(sparse));
        assertEquals(sparse.contentHash(), read.clipboard().contentHash());
        assertEquals(-1, read.clipboard().get(0, 0, 0), "absent stays absent");
        assertEquals(air, read.clipboard().get(0, 0, 1), "air stays air");
    }

    // ================================================================== vanilla files

    @Test
    void readsVanillaStructures() throws SchematicException {
        NbtList palette = compounds(state("minecraft:stone"), state("minecraft:air"),
                state("minecraft:chest", "facing", "east", "waterlogged", "false"));
        NbtCompound chestBlock = block(1, 0, 0, 2).toBuilder().put("nbt", NbtCompound.builder()
                .putString("id", "minecraft:chest").put("Items", NbtList.EMPTY).build()).build();
        NbtCompound pig = NbtCompound.builder()
                .put("pos", EntityNbt.doubles(0.5, 1.0, 1.5))
                .put("blockPos", ints(0, 1, 1))
                .put("nbt", NbtCompound.builder().putString("id", "minecraft:pig")
                        .put("Pos", EntityNbt.doubles(1000.5, 64, 1000.5)).build())
                .build();
        NbtCompound painting = NbtCompound.builder()
                .put("pos", EntityNbt.doubles(1.0, 1.5, 0.03125))
                .put("blockPos", ints(1, 1, 0))
                .put("nbt", NbtCompound.builder().putString("id", "minecraft:painting").putInt("TileX", 1001)
                        .putInt("TileY", 65).putInt("TileZ", 1000).putByte("facing", (byte) 3).build())
                .build();
        NbtCompound root = structure(new int[] {2, 2, 2}, palette, block(0, 0, 0, 0), chestBlock, block(0, 1, 0, 1))
                .put("entities", compounds(pig, painting)).build();
        Schematic read = decode(root);
        Clipboard clipboard = read.clipboard();
        assertEquals(new BlockPos(2, 2, 2), clipboard.size());
        assertEquals(BlockPos.ORIGIN, clipboard.anchor(), "a structure lands with its minimum corner on the origin");
        assertEquals(stone, clipboard.get(0, 0, 0));
        assertEquals(chest, clipboard.get(1, 0, 0));
        assertNotNull(clipboard.tile(1, 0, 0));
        assertEquals(air, clipboard.get(0, 1, 0));
        assertEquals(-1, clipboard.get(1, 1, 1), "cells the file does not list are absent");
        assertEquals(3, clipboard.cellCount());
        EntitySnapshot pigRead = clipboard.entities().stream().filter(e -> e.typeId().equals("minecraft:pig"))
                .findFirst().orElseThrow();
        assertArrayEquals(new double[] {0.5, 1.0, 1.5}, new double[] {pigRead.x(), pigRead.y(), pigRead.z()},
                "pos, not the original world's Pos");
        assertNull(pigRead.attached());
        EntitySnapshot paintingRead = clipboard.entities().stream()
                .filter(e -> e.typeId().equals("minecraft:painting")).findFirst().orElseThrow();
        assertEquals(new BlockPos(1, 1, 0), paintingRead.attached(), "a hanging entity's block is its blockPos");
        assertTrue(read.report().isLossless(), read.report().toString());
        assertEquals(0, read.formatVersion());
    }

    @Test
    void cellsTheFileDoesNotListAreAbsent() throws SchematicException {
        // As the structure block saves structure voids: by leaving them out. (A listed structure_void is kept as that
        // block, as the game places it; the fake state space has none, the GameTests check it.)
        NbtList palette = compounds(state("minecraft:air"), state("minecraft:stone"));
        Schematic read = decode(structure(new int[] {3, 1, 1}, palette, block(1, 0, 0, 1), block(2, 0, 0, 0)).build());
        assertEquals(-1, read.clipboard().get(0, 0, 0));
        assertEquals(stone, read.clipboard().get(1, 0, 0));
        assertEquals(air, read.clipboard().get(2, 0, 0));
        assertEquals(2, read.clipboard().cellCount());
    }

    @Test
    void severalPalettesUseTheFirst() throws SchematicException {
        NbtList first = compounds(state("minecraft:stone"));
        NbtList second = compounds(state("minecraft:dirt"));
        NbtCompound root = structure(new int[] {1, 1, 1}, first, block(0, 0, 0, 0)).remove("palette")
                .put("palettes", NbtList.of(NbtTag.LIST, List.of(first, second))).build();
        Schematic read = decode(root);
        assertEquals(stone, read.clipboard().get(0, 0, 0));
        assertTrue(read.report().warnings().stream().anyMatch(w -> w.contains("2 palettes; the first is used")));
    }

    @Test
    void repeatedAndOutsideBlocksAreReported() throws SchematicException {
        NbtList palette = compounds(state("minecraft:stone"), state("minecraft:dirt"),
                state("minecraft:chest", "facing", "east", "waterlogged", "false"));
        NbtCompound chestBlock = block(0, 0, 0, 2).toBuilder().put("nbt", NbtCompound.builder()
                .putString("id", "minecraft:chest").put("Items", NbtList.EMPTY).build()).build();
        Schematic read = decode(structure(new int[] {1, 1, 1}, palette, chestBlock, block(0, 0, 0, 1),
                block(5, 0, 0, 0)).build());
        assertEquals(dirt, read.clipboard().get(0, 0, 0), "the last entry wins");
        assertNull(read.clipboard().tile(0, 0, 0));
        assertEquals(1, read.report().blockEntitiesSkipped(), "the replaced chest's block entity");
        assertTrue(read.report().warnings().stream().anyMatch(w -> w.contains("more than once")));
        assertTrue(read.report().warnings().stream().anyMatch(w -> w.contains("outside the structure")));
    }

    @Test
    void olderStructuresAreDataFixed() throws SchematicException {
        List<String> fixed = new ArrayList<>();
        DataFixHook hook = new DataFixHook() {
            @Override
            public int targetDataVersion() {
                return DATA_VERSION;
            }

            @Override
            public String fixBlockState(String state, int from) {
                fixed.add(state + "@" + from);
                return state.equals("minecraft:grass_path") ? "minecraft:dirt" : state;
            }

            @Override
            public NbtCompound fixBlockEntity(NbtCompound blockEntity, int from) {
                return blockEntity;
            }
        };
        NbtList palette = compounds(state("minecraft:grass_path"), state("minecraft:stone"));
        NbtCompound old = structure(new int[] {1, 1, 1}, palette, block(0, 0, 0, 0)).putInt("DataVersion", 2586).build();
        Schematic read = StructureCodec.decode(old, states, SchematicCodec.Limits.DEFAULT, hook);
        assertEquals(dirt, read.clipboard().get(0, 0, 0));
        assertEquals(List.of("minecraft:grass_path@2586"), fixed, "only used entries are fixed");
        NbtCompound undated = structure(new int[] {1, 1, 1}, palette, block(0, 0, 0, 1)).remove("DataVersion").build();
        Schematic noVersion = StructureCodec.decode(undated, states, SchematicCodec.Limits.DEFAULT, hook);
        assertEquals(SchematicCodec.VERSION_1_DATA_VERSION, noVersion.dataVersion());
        assertTrue(noVersion.report().warnings().stream().anyMatch(w -> w.contains("no DataVersion")));
    }

    @Test
    void unknownStatesBecomeAirAndAreReported() throws SchematicException {
        NbtList palette = compounds(state("testmod:gone"), state("minecraft:stone"));
        Schematic read = decode(structure(new int[] {2, 1, 1}, palette, block(0, 0, 0, 0), block(1, 0, 0, 1)).build());
        assertEquals(air, read.clipboard().get(0, 0, 0));
        assertEquals(1L, read.report().unknownStates().get("testmod:gone"));
    }

    // ================================================================== malformed input

    @Test
    void refusesMalformedFiles() {
        NbtList palette = compounds(state("minecraft:stone"));
        NbtCompound good = structure(new int[] {1, 1, 1}, palette, block(0, 0, 0, 0)).build();
        assertEquals(Kind.MALFORMED, failure(good.toBuilder().put("size", ints(1, 1)).build()), "size of two");
        assertEquals(Kind.MALFORMED, failure(good.toBuilder().put("size", EntityNbt.doubles(1, 1, 1)).build()),
                "size of doubles");
        assertEquals(Kind.MALFORMED, failure(good.toBuilder().put("size", ints(0, 1, 1)).build()), "empty");
        assertEquals(Kind.MALFORMED, failure(good.toBuilder().put("size", ints(-3, 1, 1)).build()), "negative");
        assertEquals(Kind.MALFORMED, failure(good.toBuilder().remove("palette").build()));
        assertEquals(Kind.MALFORMED, failure(good.toBuilder().remove("blocks").build()));
        assertEquals(Kind.MALFORMED, failure(good.toBuilder().putString("blocks", "x").build()));
        assertEquals(Kind.MALFORMED, failure(good.toBuilder().putInt("DataVersion", 0).putString("DataVersion", "x")
                .build()));
        assertEquals(Kind.MALFORMED, failure(good.toBuilder().put("palettes", NbtList.EMPTY).build()));
        assertEquals(Kind.MALFORMED, failure(good.toBuilder().put("blocks", compounds(
                NbtCompound.builder().putInt("state", 0).build())).build()), "a block without pos");
        assertEquals(Kind.MALFORMED, failure(good.toBuilder().put("blocks", compounds(
                NbtCompound.builder().put("pos", ints(0, 0, 0)).putString("state", "0").build())).build()));
        assertEquals(Kind.MALFORMED, failure(good.toBuilder().put("entities", NbtList.ofStrings(List.of("pig")))
                .build()));
        assertEquals(Kind.MALFORMED, failure(good.toBuilder().put("Sculptory", NbtCompound.builder()
                .putIntArray("Anchor", new int[] {40_000_000, 0, 0}).build()).build()), "an anchor beyond the world");
    }

    @Test
    void refusesPaletteIndicesOutsideThePalette() {
        NbtList palette = compounds(state("minecraft:stone"));
        NbtCompound root = structure(new int[] {2, 1, 1}, palette, block(0, 0, 0, 0), block(1, 0, 0, 1)).build();
        String message = assertThrows(SchematicException.class, () -> decode(root)).getMessage();
        assertTrue(message.contains("palette index 1") && message.contains("1 entries"), message);
        NbtCompound negative = structure(new int[] {1, 1, 1}, palette, block(0, 0, 0, -1)).build();
        assertEquals(Kind.MALFORMED, failure(negative));
    }

    @Test
    void refusesOversizedFiles() {
        NbtList palette = compounds(state("minecraft:stone"));
        assertEquals(Kind.TOO_LARGE, failure(structure(new int[] {70_000, 1, 1}, palette).build()));
        assertEquals(Kind.TOO_LARGE, failure(structure(new int[] {2048, 2048, 1}, palette).build()),
                "4M cells, over the 2M default");
        SchematicCodec.Limits unlimited = new SchematicCodec.Limits(NbtLimits.DEFAULT, Long.MAX_VALUE, 1 << 16, 1 << 16);
        NbtCompound sparse = structure(new int[] {65_535, 65_535, 1}, palette, block(0, 0, 0, 0)).build();
        assertEquals(Kind.TOO_LARGE, assertThrows(SchematicException.class,
                () -> StructureCodec.decode(sparse, states, unlimited, identity)).kind(),
                "a mostly empty box is capped even without a volume limit");
        List<NbtCompound> many = new ArrayList<>();
        for (int i = 0; i < 3; i++) many.add(block(0, 0, 0, 0));
        NbtCompound listed = structure(new int[] {2, 1, 1}, palette).put("blocks", NbtList.of(NbtTag.COMPOUND, many))
                .build();
        SchematicCodec.Limits twoCells = new SchematicCodec.Limits(NbtLimits.DEFAULT, 2, 10, 10);
        assertEquals(Kind.TOO_LARGE, assertThrows(SchematicException.class,
                () -> StructureCodec.decode(listed, states, twoCells, identity)).kind(), "more entries than cells allowed");
        List<NbtCompound> big = new ArrayList<>();
        for (int i = 0; i < 3; i++) big.add(state("testmod:x" + i));
        NbtCompound fat = structure(new int[] {2, 1, 1}, NbtList.of(NbtTag.COMPOUND, big)).build();
        SchematicCodec.Limits twoEntries = new SchematicCodec.Limits(NbtLimits.DEFAULT, 1000, 2, 10);
        assertEquals(Kind.TOO_LARGE, assertThrows(SchematicException.class,
                () -> StructureCodec.decode(fat, states, twoEntries, identity)).kind(), "a palette over the cap");
        List<NbtCompound> pigs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            pigs.add(NbtCompound.builder().put("pos", EntityNbt.doubles(0.5, 0, 0.5))
                    .put("nbt", NbtCompound.builder().putString("id", "minecraft:pig").build()).build());
        }
        NbtCompound crowded = structure(new int[] {1, 1, 1}, palette, block(0, 0, 0, 0))
                .put("entities", NbtList.of(NbtTag.COMPOUND, pigs)).build();
        assertEquals(Kind.TOO_LARGE, assertThrows(SchematicException.class, () -> StructureCodec.decode(crowded,
                states, SchematicCodec.Limits.DEFAULT.withMaxEntities(4), identity)).kind());
    }

    @Test
    void corruptFilesOnlyFailWithTypedErrors() throws IOException {
        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        NbtIo.write(plain, "", StructureCodec.encode(SchematicSamples.richClipboard(states), METADATA, DATA_VERSION));
        byte[] valid = plain.toByteArray();
        Random random = new Random(13);
        int typed = 0;
        for (int round = 0; round < 3000; round++) {
            byte[] corrupt = valid.clone();
            int flips = 1 + random.nextInt(4);
            for (int i = 0; i < flips; i++) corrupt[random.nextInt(corrupt.length)] = (byte) random.nextInt(256);
            if (random.nextInt(10) == 0) corrupt = Arrays.copyOf(corrupt, random.nextInt(corrupt.length));
            try {
                SchematicFiles.read(new ByteArrayInputStream(corrupt), states, SchematicCodec.Limits.untrustedUpload(),
                        identity);
            } catch (IOException expected) {
                typed++;
            }
        }
        assertTrue(typed > 0, "some corruptions must be refused");
    }

    @Test
    void truncatedGzipIsRefused() throws IOException {
        byte[] file = write(SchematicSamples.richClipboard(states));
        for (int cut : new int[] {1, 10, file.length / 4, file.length / 2, file.length * 3 / 4}) {
            byte[] truncated = Arrays.copyOf(file, cut);
            assertThrows(IOException.class, () -> read(truncated), "cut at " + cut);
        }
    }

    @Test
    void zipBombsAreRefusedWhileInflating() throws IOException {
        // A million identical empty-ish blocks compress to a few KiB.
        List<NbtCompound> blocks = new ArrayList<>();
        NbtCompound one = block(0, 0, 0, 0);
        for (int i = 0; i < 200_000; i++) blocks.add(one);
        NbtCompound root = structure(new int[] {1000, 1000, 1}, compounds(state("minecraft:stone")))
                .put("blocks", NbtList.of(NbtTag.COMPOUND, blocks)).build();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        NbtIo.writeGzip(out, "", root);
        assertTrue(out.size() < 200_000, "compressed size " + out.size());
        SchematicCodec.Limits limits = new SchematicCodec.Limits(
                new NbtLimits(512, 1 << 20, 1 << 25, 1 << 20, 1 << 20), 1L << 30, 1 << 16, 1 << 10);
        NbtLimitException refused = assertThrows(NbtLimitException.class, () -> StructureCodec.read(
                new ByteArrayInputStream(out.toByteArray()), states, limits, identity));
        assertEquals(NbtLimitException.Limit.BYTES, refused.limit());
    }
}
