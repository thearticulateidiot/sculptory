package dev.sculptory.core.schem;

import static dev.sculptory.core.schem.SchematicSamples.DATA_VERSION;
import static dev.sculptory.core.schem.SchematicSamples.METADATA;
import static dev.sculptory.core.schem.SchematicSamples.bits;
import static dev.sculptory.core.schem.SchematicSamples.compounds;
import static dev.sculptory.core.schem.SchematicSamples.state;
import static dev.sculptory.core.schem.SchematicSamples.xyz;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
import dev.sculptory.core.schem.SchematicSamples.ReferenceBitArray;
import dev.sculptory.core.testing.FakeStateSpace;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class LitematicCodecTest {
    private final FakeStateSpace states = new FakeStateSpace();
    private final DataFixHook identity = DataFixHook.identity(DATA_VERSION);
    private final int air = states.air();
    private final int stone = states.state("minecraft:stone");
    private final int dirt = states.state("minecraft:dirt");
    private final int chest = states.state("minecraft:chest[facing=east]");

    private byte[] write(Clipboard clipboard) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LitematicCodec.write(out, clipboard, METADATA, DATA_VERSION);
        return out.toByteArray();
    }

    private Schematic read(byte[] file) throws IOException {
        return LitematicCodec.read(new ByteArrayInputStream(file), states, SchematicCodec.Limits.DEFAULT, identity);
    }

    private Schematic decode(NbtCompound root) throws SchematicException {
        return LitematicCodec.decode(root, states, SchematicCodec.Limits.DEFAULT, identity);
    }

    private Kind failure(NbtCompound root) {
        return assertThrows(SchematicException.class, () -> decode(root)).kind();
    }

    private String failureMessage(NbtCompound root) {
        return assertThrows(SchematicException.class, () -> decode(root)).getMessage();
    }

    /** A region packed with Litematica's own bit array: {@code cells} are palette indices in cell order. */
    private static NbtCompound.Builder region(BlockPos position, BlockPos size, List<NbtCompound> palette, int[] cells) {
        int bits = bits(palette.size());
        ReferenceBitArray packed = new ReferenceBitArray(bits, cells.length);
        for (int i = 0; i < cells.length; i++) packed.set(i, cells[i]);
        return NbtCompound.builder()
                .put("Position", xyz(position.x(), position.y(), position.z()))
                .put("Size", xyz(size.x(), size.y(), size.z()))
                .put("BlockStatePalette", NbtList.of(NbtTag.COMPOUND, palette))
                .putLongArray("BlockStates", packed.longs)
                .put("TileEntities", NbtList.EMPTY)
                .put("Entities", NbtList.EMPTY);
    }

    private static NbtCompound file(int version, NbtCompound... regions) {
        NbtCompound.Builder all = NbtCompound.builder();
        for (int i = 0; i < regions.length; i++) all.put("r" + i, regions[i]);
        return NbtCompound.builder()
                .putInt("Version", version)
                .putInt("MinecraftDataVersion", DATA_VERSION)
                .put("Metadata", NbtCompound.builder().putString("Name", "hand made").build())
                .put("Regions", all.build())
                .build();
    }

    // ================================================================== writing and round trips

    @Test
    void writesTheLitematicaLayout() throws SchematicException {
        Clipboard clipboard = SchematicSamples.richClipboard(states);
        NbtCompound root = LitematicCodec.encode(clipboard, METADATA, DATA_VERSION);
        assertEquals(6, root.getInt("Version"));
        assertEquals(1, root.getInt("SubVersion"));
        assertEquals(DATA_VERSION, root.getInt("MinecraftDataVersion"));
        NbtCompound meta = root.getCompound("Metadata");
        assertEquals("golden", meta.getString("Name"));
        assertEquals("tester", meta.getString("Author"));
        assertEquals("a test build", meta.getString("Description"));
        assertEquals(1, meta.getInt("RegionCount"));
        assertEquals(120, meta.getInt("TotalVolume"));
        assertEquals(1234L, meta.get("TimeCreated", NbtTag.NbtLong.class).value());
        assertEquals(1234L, meta.get("TimeModified", NbtTag.NbtLong.class).value());
        assertEquals(xyz(6, 4, 5), meta.getCompound("EnclosingSize"));
        long blocks = 0;
        for (int y = 0; y < 4; y++) {
            for (int z = 0; z < 5; z++) {
                for (int x = 0; x < 6; x++) if (clipboard.get(x, y, z) != air) blocks++;
            }
        }
        assertEquals(blocks, (long) meta.getInt("TotalBlocks"));
        assertNotNull(meta.getCompound("Sculptory"));

        NbtCompound regions = root.getCompound("Regions");
        assertEquals(List.of("golden"), List.copyOf(regions.keys()), "one region named after the schematic");
        NbtCompound region = regions.getCompound("golden");
        assertEquals(xyz(-3, -1, -2), region.getCompound("Position"), "Position is -anchor: the origin is the anchor");
        assertEquals(xyz(6, 4, 5), region.getCompound("Size"));
        List<NbtCompound> palette = region.getList("BlockStatePalette").compounds();
        assertEquals(state("minecraft:air"), palette.get(0), "air is entry 0, as Litematica keeps it");
        java.util.Set<Integer> used = new java.util.HashSet<>(List.of(air));
        clipboard.forEachCell((x, y, z, s, t) -> used.add(s));
        assertEquals(used.size(), palette.size(), "each state once, air first");
        int bits = bits(palette.size());
        long[] longs = region.get("BlockStates", NbtTag.NbtLongArray.class).value();
        assertEquals((120L * bits + 63) / 64, longs.length);
        // The packing is Litematica's: decode it with a port of LitematicaBitArray.
        ReferenceBitArray reference = new ReferenceBitArray(bits, longs);
        int i = 0;
        for (int y = 0; y < 4; y++) {
            for (int z = 0; z < 5; z++) {
                for (int x = 0; x < 6; x++, i++) {
                    NbtCompound entry = palette.get(reference.get(i));
                    assertEquals(states.format(clipboard.get(x, y, z)), FileImport.entrySpec(entry, "entry"));
                }
            }
        }
        List<NbtCompound> tiles = region.getList("TileEntities").compounds();
        assertEquals(clipboard.tileCount(), tiles.size());
        NbtCompound tile = tiles.get(0);
        assertEquals("minecraft:chest", tile.getString("id"));
        assertTrue(tile.contains("x") && tile.contains("y") && tile.contains("z") && tile.contains("Items"));
        List<NbtCompound> entities = region.getList("Entities").compounds();
        assertEquals(2, entities.size());
        NbtCompound stand = entities.stream().filter(e -> "minecraft:armor_stand".equals(e.getString("id")))
                .findFirst().orElseThrow();
        assertArrayEquals(new double[] {2.5, 1.0, 3.25}, EntityNbt.position(stand), "Pos relative to Position");
        assertTrue(!stand.contains("UUID"));
        assertEquals(NbtList.EMPTY.size(), region.getList("PendingBlockTicks").size());
    }

    @Test
    void roundTripIsExact() throws IOException {
        Clipboard original = SchematicSamples.richClipboard(states);
        Schematic read = read(write(original));
        assertEquals(SchematicFormat.LITEMATIC, read.format());
        assertEquals(6, read.formatVersion());
        assertEquals(DATA_VERSION, read.dataVersion());
        assertTrue(read.report().isLossless(), read.report().toString());
        assertEquals(original.contentHash(), read.clipboard().contentHash(), "blocks, tiles, entities and anchor");
        assertEquals(new BlockPos(3, 1, 2), read.clipboard().anchor());
        assertEquals("litematic:golden", read.clipboard().source());
        SchematicMetadata meta = read.metadata();
        assertEquals("golden", meta.name());
        assertEquals("tester", meta.author());
        assertEquals("a test build", meta.description());
        assertEquals(Long.valueOf(1234L), meta.dateMillis());
        assertEquals(METADATA.sculptory(), meta.sculptory());
        EntitySnapshot frame = read.clipboard().entities().stream()
                .filter(e -> e.typeId().equals("minecraft:item_frame")).findFirst().orElseThrow();
        assertEquals(new BlockPos(1, 2, 0), frame.attached(), "our own files keep the hanging entity's block");
    }

    @Test
    void everyStateRoundTrips() throws IOException {
        Clipboard every = SchematicSamples.everyState(states);
        Schematic read = read(write(every));
        assertEquals(every.contentHash(), read.clipboard().contentHash());
        assertTrue(read.report().isLossless(), read.report().toString());
    }

    @Test
    void litematicaFilesLeaveHangingEntitiesToThePosition() throws SchematicException {
        List<NbtCompound> palette = List.of(state("minecraft:air"), state("minecraft:stone"));
        // Litematica keeps TileX/Y/Z in the original world: 1000 blocks away from where the frame now is.
        NbtCompound frame = NbtCompound.builder().putString("id", "minecraft:item_frame")
                .put("Pos", EntityNbt.doubles(0.5, 0.5, 0.03125)).putInt("TileX", 1000).putInt("TileY", 64)
                .putInt("TileZ", 1000).putByte("Facing", (byte) 3).build();
        NbtCompound region = region(BlockPos.ORIGIN, new BlockPos(1, 1, 1), palette, new int[] {1})
                .put("Entities", compounds(frame)).build();
        Schematic read = decode(file(6, region));
        assertEquals(1, read.report().entities());
        assertNull(read.clipboard().entities().get(0).attached(), "the server derives the block from the position");
    }

    @Test
    void absentCellsAreWrittenAsAir() throws IOException {
        Clipboard sparse = SchematicSamples.sparseClipboard(states);
        Schematic read = read(write(sparse));
        assertEquals(sparse.volume(), read.clipboard().cellCount(), "Litematica has no absent cells");
        assertEquals(air, read.clipboard().get(0, 0, 0));
        assertEquals(sparse.get(1, 1, 0), read.clipboard().get(1, 1, 0));
        assertEquals(sparse.anchor(), read.clipboard().anchor());
    }

    @Test
    void emptyMetadataWritesDefaults() throws SchematicException {
        Clipboard one = Clipboard.builder(states, new BlockPos(1, 1, 1)).build();
        NbtCompound root = LitematicCodec.encode(one, SchematicMetadata.EMPTY, DATA_VERSION);
        NbtCompound meta = root.getCompound("Metadata");
        assertEquals(LitematicCodec.DEFAULT_REGION, meta.getString("Name"));
        assertEquals("", meta.getString("Author"));
        assertEquals(0, meta.getInt("TotalBlocks"));
        assertTrue(root.getCompound("Regions").contains(LitematicCodec.DEFAULT_REGION));
    }

    /**
     * Palettes of every size from 1 entry to the whole state space: 2 to 9 bits, values that run over a long boundary
     * included.
     */
    @Test
    void bitPackingMatchesLitematicaForEveryWidth() throws IOException {
        List<NbtCompound> all = new ArrayList<>();
        for (int h = 0; h < states.size(); h++) all.add(LitematicCodec.paletteEntry(states, h));
        Random random = new Random(3);
        for (int n = 1; n <= all.size(); n++) {
            List<NbtCompound> palette = all.subList(0, n);
            int[] cells = new int[7 * 4 * 16];
            for (int i = 0; i < cells.length; i++) cells[i] = random.nextInt(n);
            Schematic read = decode(file(6, region(BlockPos.ORIGIN, new BlockPos(7, 4, 16), palette, cells).build()));
            int i = 0;
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 7; x++, i++) {
                        assertEquals(cells[i], read.clipboard().get(x, y, z),
                                "palette of " + n + " at " + x + "," + y + "," + z);
                    }
                }
            }
            // And our writer packs what Litematica unpacks.
            NbtCompound written = LitematicCodec.encode(read.clipboard(), SchematicMetadata.EMPTY, DATA_VERSION);
            NbtCompound region = written.getCompound("Regions").getCompound(LitematicCodec.DEFAULT_REGION);
            List<NbtCompound> writtenPalette = region.getList("BlockStatePalette").compounds();
            ReferenceBitArray reference = new ReferenceBitArray(bits(writtenPalette.size()),
                    region.get("BlockStates", NbtTag.NbtLongArray.class).value());
            for (int j = 0; j < cells.length; j++) {
                assertEquals(FileImport.entrySpec(palette.get(cells[j]), "a"),
                        FileImport.entrySpec(writtenPalette.get(reference.get(j)), "b"));
            }
        }
    }

    /**
     * Wide palettes, past what the state space needs: 8, 13, 16 and 17 bits per cell (entries cycle through the state
     * space, so index {@code i} is state {@code i mod size}), packed by Litematica's own bit array, for the reader's
     * unpacking of values that straddle a long boundary at every one of those widths.
     */
    @Test
    void wideBitPackingReadsAsLitematicaWrites() throws IOException {
        List<NbtCompound> all = new ArrayList<>();
        for (int h = 0; h < states.size(); h++) all.add(LitematicCodec.paletteEntry(states, h));
        BlockPos size = new BlockPos(48, 32, 48);
        Random random = new Random(4);
        for (int n : new int[] {256, 8192, 65536, 70_000}) {
            List<NbtCompound> palette = new ArrayList<>(n);
            for (int i = 0; i < n; i++) palette.add(all.get(i % all.size()));
            int[] cells = new int[size.x() * size.y() * size.z()];
            for (int i = 0; i < cells.length; i++) cells[i] = random.nextInt(n);
            Schematic read = decode(file(6, region(BlockPos.ORIGIN, size, palette, cells).build()));
            int i = 0;
            for (int y = 0; y < size.y(); y++) {
                for (int z = 0; z < size.z(); z++) {
                    for (int x = 0; x < size.x(); x++, i++) {
                        int expected = cells[i] % all.size();
                        if (expected != read.clipboard().get(x, y, z)) {
                            fail("palette of " + n + " (" + bits(n) + " bits) at " + x + "," + y + "," + z + ": index "
                                    + cells[i] + " should read as state " + expected + ", not "
                                    + read.clipboard().get(x, y, z));
                        }
                    }
                }
            }
        }
    }

    // ================================================================== regions

    @Test
    void multiRegionFilesLandEachRegionAtItsPlace() throws SchematicException {
        List<NbtCompound> palette = List.of(state("minecraft:air"), state("minecraft:stone"), state("minecraft:dirt"));
        // Region a: x 0..1, y 0, z 0 (stone). Region b: Position (5, 2, 1) with a negative size in x and z covers
        // x 3..5, y 2..3, z 0..1 (dirt), as Litematica reads Position + Size - sign(Size).
        NbtCompound a = region(new BlockPos(0, 0, 0), new BlockPos(2, 1, 1), palette, new int[] {1, 1}).build();
        int[] dirtCells = new int[12];
        Arrays.fill(dirtCells, 2);
        dirtCells[0] = 0; // the minimum corner (3, 2, 0) is air
        NbtCompound entity = NbtCompound.builder().putString("id", "minecraft:pig")
                .put("Pos", EntityNbt.doubles(-0.5, 0.0, 0.5)).build();
        NbtCompound tile = NbtCompound.builder().putString("id", "minecraft:chest").putInt("x", 2).putInt("y", 1)
                .putInt("z", 1).put("Items", NbtList.EMPTY).build();
        List<NbtCompound> chestPalette = List.of(state("minecraft:air"), state("minecraft:stone"),
                state("minecraft:dirt"), state("minecraft:chest", "facing", "east", "waterlogged", "false"));
        dirtCells[11] = 3; // (5, 3, 1): the region's last cell holds a chest
        NbtCompound b = region(new BlockPos(5, 2, 1), new BlockPos(-3, 2, -2), chestPalette, dirtCells)
                .put("Entities", compounds(entity))
                .put("TileEntities", compounds(tile))
                .build();
        Schematic read = decode(file(5, a, b));
        Clipboard clipboard = read.clipboard();
        assertEquals(new BlockPos(6, 4, 2), clipboard.size(), "the regions' enclosing box");
        assertEquals(new BlockPos(0, 0, 0), clipboard.anchor(), "the schematic origin");
        assertEquals(stone, clipboard.get(0, 0, 0));
        assertEquals(stone, clipboard.get(1, 0, 0));
        assertEquals(-1, clipboard.get(2, 0, 0), "between regions: absent");
        assertEquals(-1, clipboard.get(0, 2, 0));
        assertEquals(air, clipboard.get(3, 2, 0));
        assertEquals(dirt, clipboard.get(4, 2, 0));
        assertEquals(chest, clipboard.get(5, 3, 1));
        assertNotNull(clipboard.tile(5, 3, 1), "block entity x/y/z are relative to the region's minimum corner");
        EntitySnapshot pig = clipboard.entities().get(0);
        assertArrayEquals(new double[] {4.5, 2.0, 1.5}, new double[] {pig.x(), pig.y(), pig.z()},
                "entity Pos is relative to the region's Position (its first corner)");
        assertEquals(new BlockPos(0, 0, 0), read.offset());
        assertEquals(5, read.formatVersion());
    }

    @Test
    void theSchematicOriginIsTheAnchor() throws SchematicException {
        List<NbtCompound> palette = List.of(state("minecraft:air"), state("minecraft:stone"));
        NbtCompound far = region(new BlockPos(10, -2, 4), new BlockPos(1, 1, 1), palette, new int[] {1}).build();
        Schematic read = decode(file(6, far));
        assertEquals(new BlockPos(-10, 2, -4), read.clipboard().anchor());
        assertEquals(new BlockPos(10, -2, 4), read.offset());
    }

    @Test
    void overlappingRegionsLetTheLaterOneWin() throws SchematicException {
        List<NbtCompound> palette = List.of(state("minecraft:air"),
                state("minecraft:chest", "facing", "east", "waterlogged", "false"), state("minecraft:stone"));
        NbtCompound tile = NbtCompound.builder().putString("id", "minecraft:chest").putInt("x", 0).putInt("y", 0)
                .putInt("z", 0).put("Items", NbtList.EMPTY).build();
        NbtCompound first = region(BlockPos.ORIGIN, new BlockPos(2, 1, 1), palette, new int[] {1, 1})
                .put("TileEntities", compounds(tile)).build();
        NbtCompound second = region(BlockPos.ORIGIN, new BlockPos(1, 1, 1),
                List.of(state("minecraft:air"), state("minecraft:stone")), new int[] {1}).build();
        Schematic read = decode(file(6, first, second));
        assertEquals(stone, read.clipboard().get(0, 0, 0), "the later region's block");
        assertEquals(chest, read.clipboard().get(1, 0, 0));
        assertNull(read.clipboard().tile(0, 0, 0));
        assertEquals(1, read.report().blockEntitiesSkipped(), "the covered chest's block entity is reported");
        assertTrue(read.report().warnings().stream().anyMatch(w -> w.contains("covered by region")));
    }

    // ================================================================== versions, data fixes, losses

    @Test
    void olderVersionsAreDataFixedAndTicksCounted() throws SchematicException {
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
        List<NbtCompound> palette = List.of(state("minecraft:air"), state("minecraft:grass_path"));
        NbtCompound region = region(BlockPos.ORIGIN, new BlockPos(2, 1, 1), palette, new int[] {1, 1})
                .put("PendingBlockTicks", compounds(NbtCompound.builder().putInt("Time", 3).build()))
                .put("PendingFluidTicks", compounds(NbtCompound.EMPTY, NbtCompound.EMPTY))
                .build();
        NbtCompound root = file(2, region).toBuilder().remove("MinecraftDataVersion").build();
        Schematic read = LitematicCodec.decode(root, states, SchematicCodec.Limits.DEFAULT, hook);
        assertEquals(SchematicCodec.VERSION_1_DATA_VERSION, read.dataVersion(), "no MinecraftDataVersion: read as 1.13.2");
        assertEquals(List.of("minecraft:grass_path@1631"), fixed, "only the used entry is fixed");
        assertEquals(dirt, read.clipboard().get(0, 0, 0));
        assertEquals(3, read.report().ticksSkipped());
        assertTrue(read.report().warnings().stream().anyMatch(w -> w.contains("scheduled")));
        assertTrue(!read.report().isLossless());
    }

    @Test
    void unsupportedVersionsAreRefused() {
        List<NbtCompound> palette = List.of(state("minecraft:air"));
        NbtCompound region = region(BlockPos.ORIGIN, new BlockPos(1, 1, 1), palette, new int[] {0}).build();
        assertEquals(Kind.UNSUPPORTED, failure(file(1, region)), "version 1 predates flattened states");
        assertEquals(Kind.UNSUPPORTED, failure(file(7, region)), "newer versions are not guessed at");
        assertTrue(failureMessage(file(7, region)).contains("version 7"));
    }

    @Test
    void unknownStatesBecomeAirAndAreReported() throws SchematicException {
        List<NbtCompound> palette = List.of(state("minecraft:air"), state("testmod:gone"),
                state("minecraft:stone", "no_such", "property"), state("minecraft:stone"));
        NbtCompound region = region(BlockPos.ORIGIN, new BlockPos(4, 1, 1), palette, new int[] {1, 2, 3, 1}).build();
        Schematic read = decode(file(6, region));
        assertEquals(air, read.clipboard().get(0, 0, 0));
        assertEquals(stone, read.clipboard().get(2, 0, 0));
        assertEquals(2L, read.report().unknownStates().get("testmod:gone"));
        assertEquals(1L, read.report().unknownStates().get("minecraft:stone[no_such=property]"));
        assertEquals(3, read.report().unknownCells());
    }

    // ================================================================== malformed input

    @Test
    void refusesMalformedFiles() throws SchematicException {
        List<NbtCompound> palette = List.of(state("minecraft:air"), state("minecraft:stone"));
        NbtCompound good = region(BlockPos.ORIGIN, new BlockPos(2, 1, 1), palette, new int[] {1, 0}).build();
        assertEquals(Kind.MALFORMED, failure(NbtCompound.builder().put("Regions", NbtCompound.EMPTY).build()),
                "no Version");
        assertEquals(Kind.MALFORMED, failure(file(6)), "no regions");
        assertEquals(Kind.MALFORMED, failure(file(6).toBuilder().remove("Regions").putString("Regions", "x").build()),
                "Regions of the wrong type");
        assertEquals(Kind.MALFORMED, failure(file(6, good.toBuilder().remove("BlockStatePalette").build())));
        assertEquals(Kind.MALFORMED, failure(file(6, good.toBuilder().put("BlockStatePalette", NbtList.EMPTY).build())));
        assertEquals(Kind.MALFORMED, failure(file(6, good.toBuilder().remove("BlockStates").build())));
        assertEquals(Kind.MALFORMED, failure(file(6, good.toBuilder().putLongArray("BlockStates", new long[0]).build())),
                "BlockStates too short");
        // A padded array reads (Litematica never checks the length, and some writers pad it): the extra longs are ignored.
        long[] padded = Arrays.copyOf(good.get("BlockStates", NbtTag.NbtLongArray.class).value(), 4);
        Schematic paddedRead = decode(file(6, good.toBuilder().putLongArray("BlockStates", padded).build()));
        assertEquals(states.state("minecraft:stone"), paddedRead.clipboard().get(0, 0, 0), "a padded BlockStates reads");
        assertEquals(states.air(), paddedRead.clipboard().get(1, 0, 0));
        assertEquals(Kind.MALFORMED, failure(file(6, good.toBuilder().putIntArray("BlockStates", new int[1]).build())),
                "BlockStates of the wrong type");
        assertEquals(Kind.MALFORMED, failure(file(6, good.toBuilder().put("Size", xyz(0, 1, 1)).build())),
                "a size of 0");
        assertEquals(Kind.MALFORMED, failure(file(6, good.toBuilder().put("Size", NbtCompound.EMPTY).build())));
        assertEquals(Kind.MALFORMED, failure(file(6, good.toBuilder().putInt("Position", 3).build())));
        assertEquals(Kind.MALFORMED, failure(file(6, good.toBuilder()
                .put("BlockStatePalette", compounds(state("minecraft:air"), NbtCompound.EMPTY)).build())),
                "a palette entry without Name");
        assertEquals(Kind.MALFORMED, failure(file(6, good.toBuilder().put("BlockStatePalette", compounds(
                state("minecraft:air"), NbtCompound.builder().putString("Name", "minecraft:stone")
                        .put("Properties", NbtCompound.builder().putInt("a", 1).build()).build())).build())),
                "a property that is not a string");
        assertEquals(Kind.MALFORMED, failure(file(6, good.toBuilder().put("TileEntities", NbtList.ofStrings(List.of("x")))
                .build())), "block entities that are not compounds");
        assertEquals(Kind.MALFORMED, failure(file(6, good.toBuilder().putInt("Entities", 1).build())));
        assertEquals(Kind.MALFORMED, failure(file(6, good.toBuilder()
                .put("Position", xyz(40_000_000, 0, 0)).build())), "a corner beyond the world");
    }

    @Test
    void refusesPaletteIndicesOutsideThePalette() {
        // Three entries take 2 bits, so the data can say 3: one past the palette.
        List<NbtCompound> palette = List.of(state("minecraft:air"), state("minecraft:stone"), state("minecraft:dirt"));
        NbtCompound region = region(BlockPos.ORIGIN, new BlockPos(2, 1, 1), palette, new int[] {1, 3}).build();
        String message = failureMessage(file(6, region));
        assertTrue(message.contains("palette index 3") && message.contains("3 entries"), message);
    }

    @Test
    void refusesOversizedFiles() {
        List<NbtCompound> palette = List.of(state("minecraft:air"));
        NbtCompound.Builder huge = region(BlockPos.ORIGIN, new BlockPos(1, 1, 1), palette, new int[] {0});
        assertEquals(Kind.TOO_LARGE, failure(file(6, huge.put("Size", xyz(70_000, 1, 1)).build())), "a side over 65535");
        assertEquals(Kind.TOO_LARGE, failure(file(6, huge.put("Size", xyz(-2048, 2048, 1)).build())),
                "4M cells, over the 2M default");
        // Two regions far apart: small, but their enclosing box is too long.
        NbtCompound a = region(BlockPos.ORIGIN, new BlockPos(1, 1, 1), palette, new int[] {0}).build();
        NbtCompound b = region(new BlockPos(100_000, 0, 0), new BlockPos(1, 1, 1), palette, new int[] {0}).build();
        assertEquals(Kind.TOO_LARGE, failure(file(6, a, b)));
        NbtCompound c = region(new BlockPos(0, 60_000, 60_000), new BlockPos(1, 1, 1), palette, new int[] {0}).build();
        NbtCompound d = region(new BlockPos(60_000, 0, 0), new BlockPos(1, 1, 1), palette, new int[] {0}).build();
        SchematicCodec.Limits unlimited = new SchematicCodec.Limits(NbtLimits.DEFAULT, Long.MAX_VALUE, 1 << 16, 1 << 16);
        assertEquals(Kind.TOO_LARGE, assertThrows(SchematicException.class,
                () -> LitematicCodec.decode(file(6, a, c, d), states, unlimited, identity)).kind(),
                "a mostly empty box is capped even without a volume limit");
        // Overlapping regions whose combined volume is over the limit, though their box is small.
        NbtCompound[] many = new NbtCompound[3];
        int[] cells = new int[1_000_000];
        for (int i = 0; i < many.length; i++) {
            many[i] = region(BlockPos.ORIGIN, new BlockPos(100, 100, 100), palette, cells).build();
        }
        assertEquals(Kind.TOO_LARGE, failure(file(6, many)), "the regions' cells count together");
        // A palette with more entries than cells (plus air).
        List<NbtCompound> big = new ArrayList<>();
        for (int i = 0; i < 5; i++) big.add(state("testmod:x" + i));
        NbtCompound fat = region(BlockPos.ORIGIN, new BlockPos(2, 1, 1), big, new int[] {0, 1}).build();
        assertEquals(Kind.TOO_LARGE, failure(file(6, fat)));
        NbtCompound.Builder regions = NbtCompound.builder();
        for (int i = 0; i <= LitematicCodec.MAX_REGIONS; i++) regions.put("r" + i, a);
        assertEquals(Kind.TOO_LARGE, failure(file(6).toBuilder().put("Regions", regions.build()).build()));
    }

    @Test
    void negativeSizesAreHandledAsLitematicaDoes() throws SchematicException {
        List<NbtCompound> palette = List.of(state("minecraft:air"), state("minecraft:stone"), state("minecraft:dirt"));
        // Position (0, 0, 0), Size (-2, -1, -1): the box is x -1..0, y 0, z 0; cell order runs from its minimum corner.
        NbtCompound region = region(BlockPos.ORIGIN, new BlockPos(-2, -1, -1), palette, new int[] {1, 2}).build();
        Schematic read = decode(file(6, region));
        assertEquals(new BlockPos(2, 1, 1), read.clipboard().size());
        assertEquals(new BlockPos(1, 0, 0), read.clipboard().anchor(), "the origin is the box's east cell");
        assertEquals(stone, read.clipboard().get(0, 0, 0));
        assertEquals(dirt, read.clipboard().get(1, 0, 0));
    }

    /** Corrupted files fail with a typed IOException (or read with losses reported), never a runtime exception. */
    @Test
    void corruptFilesOnlyFailWithTypedErrors() throws IOException {
        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        NbtIo.write(plain, "", LitematicCodec.encode(SchematicSamples.richClipboard(states), METADATA, DATA_VERSION));
        byte[] valid = plain.toByteArray();
        Random random = new Random(11);
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
        // The gzip trailer is not needed to read the document, so cuts go into the compressed data.
        for (int cut : new int[] {1, 10, file.length / 4, file.length / 2, file.length * 3 / 4}) {
            byte[] truncated = Arrays.copyOf(file, cut);
            assertThrows(IOException.class, () -> read(truncated), "cut at " + cut);
        }
    }

    @Test
    void zipBombsAreRefusedWhileInflating() throws IOException {
        // 16 MiB of zero longs compress to a few KiB; a 1 MiB cap refuses them before the array is allocated.
        List<NbtCompound> palette = List.of(state("minecraft:air"), state("minecraft:stone"), state("minecraft:dirt"));
        NbtCompound region = NbtCompound.builder()
                .put("Position", xyz(0, 0, 0)).put("Size", xyz(256, 256, 1024))
                .put("BlockStatePalette", NbtList.of(NbtTag.COMPOUND, palette))
                .putLongArray("BlockStates", new long[2 << 20]).build();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        NbtIo.writeGzip(out, "", file(6, region));
        assertTrue(out.size() < 200_000, "compressed size " + out.size());
        SchematicCodec.Limits limits = new SchematicCodec.Limits(
                new NbtLimits(512, 1 << 20, 1 << 25, 1 << 20, 1 << 20), 1L << 30, 1 << 16, 1 << 10);
        NbtLimitException refused = assertThrows(NbtLimitException.class, () -> LitematicCodec.read(
                new ByteArrayInputStream(out.toByteArray()), states, limits, identity));
        assertEquals(NbtLimitException.Limit.BYTES, refused.limit());
    }

    @Test
    void entitiesAndTilesAreCappedAsInSpongeFiles() {
        List<NbtCompound> palette = List.of(state("minecraft:air"), state("minecraft:stone"));
        List<NbtCompound> pigs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            pigs.add(NbtCompound.builder().putString("id", "minecraft:pig").put("Pos", EntityNbt.doubles(0.5, 0, 0.5))
                    .build());
        }
        NbtCompound region = region(BlockPos.ORIGIN, new BlockPos(1, 1, 1), palette, new int[] {1})
                .put("Entities", NbtList.of(NbtTag.COMPOUND, pigs)).build();
        SchematicCodec.Limits fewEntities = SchematicCodec.Limits.DEFAULT.withMaxEntities(4);
        assertEquals(Kind.TOO_LARGE, assertThrows(SchematicException.class,
                () -> LitematicCodec.decode(file(6, region), states, fewEntities, identity)).kind());
    }

    @Test
    void entitiesWithoutIdsOrOutsideAreSkippedAndReported() throws SchematicException {
        List<NbtCompound> palette = List.of(state("minecraft:air"), state("minecraft:stone"));
        NbtCompound noId = NbtCompound.builder().put("Pos", EntityNbt.doubles(0.5, 0, 0.5)).build();
        NbtCompound outside = NbtCompound.builder().putString("id", "minecraft:pig")
                .put("Pos", EntityNbt.doubles(9.5, 0, 0.5)).build();
        NbtCompound noPos = NbtCompound.builder().putString("id", "minecraft:pig").build();
        NbtCompound region = region(BlockPos.ORIGIN, new BlockPos(1, 1, 1), palette, new int[] {1})
                .put("Entities", compounds(noId, outside, noPos)).build();
        Schematic read = decode(file(6, region));
        assertEquals(0, read.report().entities());
        assertEquals(3, read.report().entitiesSkipped());
    }
}
