package dev.sculptory.core.schem;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.buffer.NbtBytes;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.entity.EntityNbt;
import dev.sculptory.core.entity.EntitySnapshot;
import dev.sculptory.core.nbt.BlockEntityNbt;
import dev.sculptory.core.nbt.NbtCompound;
import dev.sculptory.core.nbt.NbtIo;
import dev.sculptory.core.nbt.NbtLimitException;
import dev.sculptory.core.nbt.NbtLimits;
import dev.sculptory.core.nbt.NbtList;
import dev.sculptory.core.nbt.NbtTag;
import dev.sculptory.core.schem.SchematicException.Kind;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.testing.FakeStateSpace;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SchematicCodecTest {
    private static final int DATA_VERSION = 3955;

    private final FakeStateSpace states = new FakeStateSpace();
    private final DataFixHook identity = DataFixHook.identity(DATA_VERSION);
    private final int air = states.air();
    private final int stone = states.state("minecraft:stone");
    private final int dirt = states.state("minecraft:dirt");

    private static NbtBytes chestTile(String item, int count) {
        return BlockEntityNbt.toNbtBytes("minecraft:chest", NbtCompound.builder()
                .put("Items", NbtList.of(NbtTag.COMPOUND, List.of(NbtCompound.builder()
                        .putByte("Slot", (byte) 0).putString("id", item).putInt("count", count).build())))
                .putString("CustomName", "\"Loot\"")
                .build());
    }

    /** Every cell set, cycling through the whole state space; chests carry distinct tiles. */
    private Clipboard richClipboard() {
        Clipboard.Builder builder = Clipboard.builder(states, new BlockPos(6, 4, 5)).anchor(new BlockPos(3, 1, 2));
        int chests = 0;
        for (int y = 0; y < 4; y++) {
            for (int z = 0; z < 5; z++) {
                for (int x = 0; x < 6; x++) {
                    int state = (x + 6 * z + 30 * y + 7) % states.size();
                    builder.set(x, y, z, state);
                    if (StateFlags.has(states.flags(state), StateFlags.HAS_BLOCK_ENTITY)) {
                        builder.setTile(x, y, z, chestTile("minecraft:apple", ++chests));
                    }
                }
            }
        }
        return builder.build();
    }

    private static final SchematicMetadata METADATA = new SchematicMetadata("golden", "tester", 1234L, List.of("extra"),
            new AssetInfo(List.of("tree", "oak"), new BlockPos(3, 1, 2), List.of(2, 0), 5));

    private static byte[] write(Clipboard clipboard, SchematicMetadata metadata) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SchematicCodec.write(out, clipboard, metadata, DATA_VERSION);
        return out.toByteArray();
    }

    private Schematic read(byte[] file) throws IOException {
        return SchematicCodec.read(new ByteArrayInputStream(file), states, SchematicCodec.Limits.DEFAULT, identity);
    }

    private Schematic decode(NbtCompound root) throws SchematicException {
        return decode(root, identity);
    }

    private Schematic decode(NbtCompound root, DataFixHook hook) throws SchematicException {
        return SchematicCodec.decode(root, states, SchematicCodec.Limits.DEFAULT, hook);
    }

    private Kind failure(NbtCompound root) {
        return assertThrows(SchematicException.class, () -> decode(root)).kind();
    }

    private static NbtCompound palette(String... specs) {
        NbtCompound.Builder palette = NbtCompound.builder();
        for (int i = 0; i < specs.length; i++) palette.putInt(specs[i], i);
        return palette.build();
    }

    /** A version 3 schematic body (unwrapped). */
    private static NbtCompound.Builder v3(int w, int h, int l, NbtCompound palette, byte[] data, NbtList blockEntities) {
        NbtCompound.Builder blocks = NbtCompound.builder().put("Palette", palette).putByteArray("Data", data);
        if (blockEntities != null) blocks.put("BlockEntities", blockEntities);
        return NbtCompound.builder().putInt("Version", 3).putInt("DataVersion", DATA_VERSION)
                .putShort("Width", (short) w).putShort("Height", (short) h).putShort("Length", (short) l)
                .put("Blocks", blocks.build());
    }

    private static NbtCompound wrap(NbtCompound.Builder schematic) {
        return NbtCompound.builder().put("Schematic", schematic.build()).build();
    }

    private static NbtCompound blockEntity(int x, int y, int z, String id, NbtCompound data) {
        NbtCompound.Builder entry = NbtCompound.builder().putIntArray("Pos", new int[] {x, y, z});
        if (id != null) entry.putString("Id", id);
        return entry.put("Data", data).build();
    }

    // ================================================================== writing and round trips

    /** The exact v3 layout for a tiny clipboard, field by field. */
    @Test
    void writesTheSpongeVersion3Layout() throws SchematicException {
        int chest = states.state("minecraft:chest[facing=north]");
        int widget = states.state("testmod:widget[facing=up]");
        NbtBytes tile = chestTile("minecraft:apple", 3);
        Clipboard clipboard = Clipboard.builder(states, new BlockPos(2, 2, 2)).anchor(new BlockPos(1, 0, 1))
                .set(0, 0, 0, stone).set(1, 0, 0, dirt).set(0, 0, 1, stone).set(1, 0, 1, chest).setTile(1, 0, 1, tile)
                .set(0, 1, 0, air).set(1, 1, 0, widget).set(0, 1, 1, stone).set(1, 1, 1, air)
                .build();
        NbtCompound root = SchematicCodec.encode(clipboard, METADATA, DATA_VERSION);
        assertEquals(List.of("Schematic"), List.copyOf(root.keys()), "the v3 wrapper");
        NbtCompound schem = root.getCompound("Schematic");
        assertEquals(List.of("Version", "DataVersion", "Metadata", "Width", "Height", "Length", "Offset", "Blocks"),
                List.copyOf(schem.keys()));
        assertEquals(new NbtTag.NbtInt(3), schem.get("Version"));
        assertEquals(new NbtTag.NbtInt(DATA_VERSION), schem.get("DataVersion"));
        assertEquals(new NbtTag.NbtShort((short) 2), schem.get("Width"));
        assertEquals(new NbtTag.NbtShort((short) 2), schem.get("Height"));
        assertEquals(new NbtTag.NbtShort((short) 2), schem.get("Length"));
        assertArrayEquals(new int[] {-1, 0, -1}, schem.getIntArray("Offset"), "Offset = -anchor");

        NbtCompound blocks = schem.getCompound("Blocks");
        assertEquals(palette("minecraft:stone", "minecraft:dirt", "minecraft:chest[facing=north,waterlogged=false]",
                "minecraft:air", "testmod:widget[facing=up]"), blocks.getCompound("Palette"));
        assertEquals(List.of("minecraft:stone", "minecraft:dirt", "minecraft:chest[facing=north,waterlogged=false]",
                "minecraft:air", "testmod:widget[facing=up]"), List.copyOf(blocks.getCompound("Palette").keys()),
                "palette in first-use order");
        // Index x + z*W + y*W*L.
        assertArrayEquals(new byte[] {0, 1, 0, 2, 3, 4, 0, 3}, blocks.get("Data", NbtTag.NbtByteArray.class).value());
        NbtList entities = blocks.getList("BlockEntities");
        assertEquals(1, entities.size());
        NbtCompound entity = entities.compounds().get(0);
        assertEquals(List.of("Id", "Pos", "Data"), List.copyOf(entity.keys()));
        assertEquals("minecraft:chest", entity.getString("Id"));
        assertArrayEquals(new int[] {1, 0, 1}, entity.getIntArray("Pos"));
        assertFalse(entity.getCompound("Data").contains("id"), "Data holds no id, x, y or z");
        assertEquals(List.of("Items", "CustomName"), List.copyOf(entity.getCompound("Data").keys()));

        NbtCompound meta = schem.getCompound("Metadata");
        assertEquals("golden", meta.getString("Name"));
        assertEquals("tester", meta.getString("Author"));
        assertEquals(new NbtTag.NbtLong(1234L), meta.get("Date"));
        assertEquals(List.of("extra", "testmod"), meta.getList("RequiredMods").strings(), "merged with namespaces in use");
        NbtCompound bs = meta.getCompound("Sculptory");
        assertEquals(List.of("tree", "oak"), bs.getList("Tags").strings());
        assertArrayEquals(new int[] {3, 1, 2}, bs.getIntArray("Anchor"));
        assertArrayEquals(new int[] {0, 2}, bs.getIntArray("Rotations"));
        assertEquals(5, bs.getInt("Weight"));
        assertFalse(meta.contains(SchematicCodec.LEGACY_META_KEY), "the old Builder Suite key is never written");
    }

    @Test
    void version3RoundTripIsExact() throws IOException {
        Clipboard original = richClipboard();
        assertTrue(original.tileCount() > 0);
        byte[] file = write(original, METADATA);
        assertEquals((byte) 0x1f, file[0], "gzip");
        Schematic schematic = read(file);

        assertEquals(3, schematic.formatVersion());
        assertEquals(DATA_VERSION, schematic.dataVersion());
        assertEquals(new BlockPos(-3, -1, -2), schematic.offset());
        assertEquals(new BlockPos(6, 4, 5), schematic.dims());
        assertTrue(schematic.report().isLossless(), schematic.report().toString());
        assertEquals(original.tileCount(), schematic.report().blockEntities());

        Clipboard read = schematic.clipboard();
        assertEquals(original.size(), read.size());
        assertEquals(original.anchor(), read.anchor());
        original.forEachCell((x, y, z, state, tile) -> {
            assertEquals(state, read.get(x, y, z), "state at " + x + "," + y + "," + z);
            assertEquals(tile, read.tile(x, y, z), "tile at " + x + "," + y + "," + z);
        });
        assertEquals(original.contentHash(), read.contentHash());
        assertEquals("schematic:golden", read.source());
        assertEquals(original.cellCount(), schematic.blocks().cellCount());

        SchematicMetadata meta = schematic.metadata();
        assertEquals("golden", meta.name());
        assertEquals("tester", meta.author());
        assertEquals(1234L, meta.dateMillis());
        assertEquals(List.of("extra", "testmod"), meta.requiredMods());
        assertEquals(METADATA.sculptory(), meta.sculptory());

        // Writing what was read gives the same bytes: the encoding is deterministic and nothing drifted.
        assertArrayEquals(NbtIo.toBytes(SchematicCodec.encode(original, meta, DATA_VERSION)),
                NbtIo.toBytes(SchematicCodec.encode(read, meta, DATA_VERSION)));
        assertArrayEquals(file, write(original, METADATA));
    }

    @Test
    void absentCellsAreWrittenAsAirAndEmptyMetadataIsMinimal() throws IOException {
        Clipboard sparse = Clipboard.builder(states, new BlockPos(3, 1, 1)).set(1, 0, 0, stone).build();
        Schematic schematic = read(write(sparse, SchematicMetadata.EMPTY));
        assertEquals(air, schematic.clipboard().get(0, 0, 0));
        assertEquals(stone, schematic.clipboard().get(1, 0, 0));
        assertEquals(3, schematic.clipboard().cellCount());
        assertNull(schematic.metadata().name());
        assertNull(schematic.metadata().sculptory());
        assertEquals("schematic", schematic.clipboard().source());
        NbtCompound meta = SchematicCodec.encode(sparse, SchematicMetadata.EMPTY, DATA_VERSION)
                .getCompound("Schematic").getCompound("Metadata");
        assertEquals(List.of("RequiredMods"), List.copyOf(meta.keys()));
    }

    @Test
    void writerRefusesSidesTheFormatCannotStore() {
        Clipboard wide = Clipboard.builder(states, new BlockPos(70_000, 1, 1)).build();
        assertEquals(Kind.TOO_LARGE, assertThrows(SchematicException.class,
                () -> SchematicCodec.encode(wide, SchematicMetadata.EMPTY, DATA_VERSION)).kind());
        Clipboard badTile = Clipboard.builder(states, new BlockPos(1, 1, 1))
                .set(0, 0, 0, states.state("minecraft:chest")).setTile(0, 0, 0, new NbtBytes("minecraft:chest", new byte[] {1}))
                .build();
        assertEquals(Kind.MALFORMED, assertThrows(SchematicException.class,
                () -> SchematicCodec.encode(badTile, SchematicMetadata.EMPTY, DATA_VERSION)).kind());
        Clipboard farAnchor = Clipboard.builder(states, new BlockPos(1, 1, 1)).set(0, 0, 0, stone)
                .anchor(new BlockPos(Integer.MIN_VALUE, 0, 0)).build();
        assertEquals(Kind.TOO_LARGE, assertThrows(SchematicException.class,
                () -> SchematicCodec.encode(farAnchor, SchematicMetadata.EMPTY, DATA_VERSION)).kind());
    }

    // ================================================================== reading older versions

    /** A version 2 file as WorldEdit writes it: fields in the root, WEOffset metadata, inline block entities. */
    @Test
    void readsVersion2() throws IOException {
        NbtCompound root = NbtCompound.builder()
                .putInt("Version", 2).putInt("DataVersion", 2586)
                .putShort("Width", (short) 3).putShort("Height", (short) 1).putShort("Length", (short) 2)
                .putIntArray("Offset", new int[] {100, 64, -200})
                .put("Metadata", NbtCompound.builder().putInt("WEOffsetX", -1).putInt("WEOffsetY", 0).putInt("WEOffsetZ", -1).build())
                .putInt("PaletteMax", 3)
                .put("Palette", palette("minecraft:stone", "minecraft:chest[facing=south]", "minecraft:air"))
                .putByteArray("BlockData", new byte[] {0, 1, 2, 2, 2, 0})
                .put("BlockEntities", NbtList.of(NbtTag.COMPOUND, List.of(NbtCompound.builder()
                        .putIntArray("Pos", new int[] {1, 0, 0}).putString("Id", "minecraft:chest")
                        .put("Items", NbtList.EMPTY).putString("CustomName", "\"v2\"").build())))
                .put("Entities", NbtList.of(NbtTag.COMPOUND, List.of(NbtCompound.builder().putString("Id", "minecraft:pig").build())))
                .build();
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        NbtIo.writeGzip(file, "Schematic", root);
        Schematic schematic = read(file.toByteArray());
        assertEquals(2, schematic.formatVersion());
        assertEquals(2586, schematic.dataVersion());
        assertEquals(new BlockPos(100, 64, -200), schematic.offset());
        Clipboard clipboard = schematic.clipboard();
        assertEquals(new BlockPos(1, 0, 1), clipboard.anchor(), "anchor = -WEOffset");
        int chest = states.state("minecraft:chest[facing=south]");
        assertEquals(stone, clipboard.get(0, 0, 0));
        assertEquals(chest, clipboard.get(1, 0, 0));
        assertEquals(air, clipboard.get(2, 0, 0));
        assertEquals(stone, clipboard.get(2, 0, 1));
        NbtCompound tile = BlockEntityNbt.decode(clipboard.tile(1, 0, 0));
        assertEquals(List.of("id", "Items", "CustomName"), List.copyOf(tile.keys()));
        assertEquals("\"v2\"", tile.getString("CustomName"));
        assertEquals(1, schematic.report().entitiesSkipped());
        assertEquals(1, schematic.report().blockEntities());
    }

    @Test
    void readsVersion1() throws IOException {
        NbtCompound root = NbtCompound.builder()
                .putInt("Version", 1)
                .putShort("Width", (short) 1).putShort("Height", (short) 2).putShort("Length", (short) 1)
                .put("Palette", palette("minecraft:chest", "minecraft:dirt"))
                .putByteArray("BlockData", new byte[] {0, 1})
                .put("TileEntities", NbtList.of(NbtTag.COMPOUND, List.of(NbtCompound.builder()
                        .putIntArray("Pos", new int[] {0, 0, 0}).putString("Id", "minecraft:chest").build())))
                .build();
        Schematic schematic = decode(root);
        assertEquals(1, schematic.formatVersion());
        assertEquals(SchematicCodec.VERSION_1_DATA_VERSION, schematic.dataVersion());
        assertEquals(BlockPos.ORIGIN, schematic.clipboard().anchor());
        assertEquals(dirt, schematic.clipboard().get(0, 1, 0));
        assertEquals("minecraft:chest", schematic.clipboard().tile(0, 0, 0).typeId());
    }

    // ================================================================== unknown content and data fixing

    @Test
    void multiByteVarintsAndUnknownStates() throws SchematicException {
        NbtCompound.Builder palette = NbtCompound.builder();
        for (int i = 0; i < 300; i++) {
            String spec = switch (i) {
                case 200 -> "minecraft:stone";
                case 150 -> "minecraft:oak_stairs[facing=sideways]";
                default -> "testmod:gone_" + i;
            };
            palette.putInt(spec, i);
        }
        // 200 = C8 01, 150 = 96 01, 7 = 07; the other 297 cells are stone (the palette may not outnumber the cells).
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.writeBytes(new byte[] {(byte) 0xC8, 0x01, (byte) 0x96, 0x01, 0x07});
        for (int i = 3; i < 300; i++) data.writeBytes(new byte[] {(byte) 0xC8, 0x01});
        Schematic schematic = decode(wrap(v3(100, 1, 3, palette.build(), data.toByteArray(), null)));
        assertEquals(stone, schematic.clipboard().get(0, 0, 0));
        assertEquals(air, schematic.clipboard().get(1, 0, 0));
        assertEquals(air, schematic.clipboard().get(2, 0, 0));
        assertEquals(stone, schematic.clipboard().get(99, 0, 2));
        SchematicReport report = schematic.report();
        assertEquals(Map.of("minecraft:oak_stairs[facing=sideways]", 1L, "testmod:gone_7", 1L), report.unknownStates());
        assertEquals(2, report.unknownCells());
        assertFalse(report.isLossless());
        assertTrue(report.warnings().stream().anyMatch(w -> w.contains("testmod:gone_7")), report.warnings().toString());
    }

    @Test
    void dataFixHookUpgradesOlderFiles() throws IOException {
        List<String> seen = new ArrayList<>();
        DataFixHook hook = new DataFixHook() {
            @Override
            public int targetDataVersion() {
                return DATA_VERSION;
            }

            @Override
            public String fixBlockState(String state, int from) {
                seen.add(state + "@" + from);
                return state.equals("minecraft:grass") ? "minecraft:short_grass" : state;
            }

            @Override
            public NbtCompound fixBlockEntity(NbtCompound blockEntity, int from) {
                assertEquals("minecraft:chest", blockEntity.getString("id"), "the hook sees the id");
                assertFalse(blockEntity.contains("x"));
                return blockEntity.toBuilder().putInt("fixedFrom", from).build();
            }
        };
        NbtCompound root = wrap(v3(2, 1, 1, palette("minecraft:grass", "minecraft:chest"), new byte[] {0, 1},
                NbtList.of(NbtTag.COMPOUND, List.of(blockEntity(1, 0, 0, "minecraft:chest", NbtCompound.EMPTY))))
                .putInt("DataVersion", 1000));
        Schematic schematic = decode(root, hook);
        assertEquals(states.state("minecraft:short_grass"), schematic.clipboard().get(0, 0, 0));
        assertEquals(List.of("minecraft:grass@1000", "minecraft:chest@1000"), seen);
        assertEquals(1000, BlockEntityNbt.decode(schematic.clipboard().tile(1, 0, 0)).getInt("fixedFrom"));
        assertTrue(schematic.report().isLossless());
    }

    @Test
    void dataFixHookIsNotCalledForCurrentFiles() throws SchematicException {
        DataFixHook throwing = new DataFixHook() {
            @Override
            public int targetDataVersion() {
                return DATA_VERSION;
            }

            @Override
            public String fixBlockState(String state, int from) {
                throw new AssertionError("not needed");
            }

            @Override
            public NbtCompound fixBlockEntity(NbtCompound blockEntity, int from) {
                throw new AssertionError("not needed");
            }
        };
        NbtCompound root = wrap(v3(1, 1, 1, palette("minecraft:stone"), new byte[] {0}, null));
        assertEquals(stone, decode(root, throwing).clipboard().get(0, 0, 0));
        NbtCompound newer = wrap(v3(1, 1, 1, palette("minecraft:stone"), new byte[] {0}, null).putInt("DataVersion", 5000));
        Schematic schematic = decode(newer, throwing);
        assertTrue(schematic.report().newerDataVersion());
        assertTrue(schematic.report().warnings().stream().anyMatch(w -> w.contains("newer")));
    }

    @Test
    void failingDataFixesAreReported() throws SchematicException {
        DataFixHook failing = new DataFixHook() {
            @Override
            public int targetDataVersion() {
                return DATA_VERSION;
            }

            @Override
            public String fixBlockState(String state, int from) {
                if (state.equals("minecraft:stone")) throw new IllegalStateException("broken fixer");
                return state;
            }

            @Override
            public NbtCompound fixBlockEntity(NbtCompound blockEntity, int from) {
                throw new IllegalStateException("broken fixer");
            }
        };
        NbtCompound root = wrap(v3(2, 1, 1, palette("minecraft:stone", "minecraft:chest"), new byte[] {0, 1},
                NbtList.of(NbtTag.COMPOUND, List.of(blockEntity(1, 0, 0, "minecraft:chest", NbtCompound.EMPTY))))
                .putInt("DataVersion", 100));
        Schematic schematic = decode(root, failing);
        assertEquals(air, schematic.clipboard().get(0, 0, 0));
        assertEquals(Map.of("minecraft:stone", 1L), schematic.report().unknownStates());
        assertEquals(1, schematic.report().blockEntitiesSkipped());
        assertNull(schematic.clipboard().tile(1, 0, 0));
        assertTrue(schematic.report().warnings().stream().anyMatch(w -> w.contains("broken fixer")));
    }

    @Test
    void reportsSkippedEntitiesBiomesAndBlockEntities() throws SchematicException {
        NbtCompound items = NbtCompound.builder().put("Items", NbtList.EMPTY).build();
        NbtList entities = NbtList.of(NbtTag.COMPOUND, List.of(
                blockEntity(1, 0, 0, "minecraft:chest", items),        // kept
                blockEntity(1, 0, 0, "minecraft:chest", items),        // duplicate position
                blockEntity(5, 0, 0, "minecraft:chest", items),        // outside the box
                blockEntity(0, 0, 0, "minecraft:chest", items),        // on stone
                blockEntity(1, 0, 0, null, items),                     // no Id
                NbtCompound.builder().putString("Id", "minecraft:chest").build())); // no Pos
        NbtCompound root = wrap(v3(2, 1, 1, palette("minecraft:stone", "minecraft:chest"), new byte[] {0, 1}, entities)
                .put("Entities", NbtList.of(NbtTag.COMPOUND, List.of(NbtCompound.EMPTY, NbtCompound.EMPTY)))
                .put("Biomes", NbtCompound.builder().put("Palette", palette("minecraft:plains"))
                        .putByteArray("Data", new byte[] {0, 0}).build()));
        Schematic schematic = decode(root);
        SchematicReport report = schematic.report();
        assertEquals(1, report.blockEntities());
        assertEquals(5, report.blockEntitiesSkipped());
        assertEquals(2, report.entitiesSkipped());
        assertTrue(report.biomesSkipped());
        assertFalse(report.isLossless());
        assertEquals(7, report.warnings().size(), report.warnings().toString());
        assertInstanceOf(BlockEntityData.class, schematic.clipboard().tile(1, 0, 0));
    }

    @Test
    void biomeOnlyVersion3FilesAreAir() throws SchematicException {
        NbtCompound root = wrap(NbtCompound.builder().putInt("Version", 3).putInt("DataVersion", DATA_VERSION)
                .putShort("Width", (short) 2).putShort("Height", (short) 1).putShort("Length", (short) 1)
                .putIntArray("Offset", new int[] {0, -1, 0}));
        Schematic schematic = decode(root);
        assertEquals(2, schematic.clipboard().cellCount());
        assertEquals(air, schematic.clipboard().get(1, 0, 0));
        assertEquals(new BlockPos(0, 1, 0), schematic.clipboard().anchor());
    }

    // ================================================================== refusals

    @Test
    void refusesMalformedFiles() {
        NbtCompound stonePalette = palette("minecraft:stone");
        assertEquals(Kind.UNSUPPORTED, failure(wrap(v3(1, 1, 1, stonePalette, new byte[] {0}, null).putInt("Version", 4))));
        assertEquals(Kind.MALFORMED, failure(wrap(v3(1, 1, 1, stonePalette, new byte[] {0}, null).remove("Version"))));
        assertEquals(Kind.MALFORMED, failure(wrap(v3(1, 1, 1, stonePalette, new byte[] {0}, null).remove("DataVersion"))));
        assertEquals(Kind.MALFORMED, failure(wrap(v3(1, 1, 1, stonePalette, new byte[] {0}, null).remove("Width"))));
        assertEquals(Kind.MALFORMED, failure(wrap(v3(0, 1, 1, stonePalette, new byte[0], null))));
        assertEquals(Kind.MALFORMED, failure(wrap(v3(1, 1, 1, stonePalette, new byte[] {0}, null).putString("Width", "1"))));
        assertEquals(Kind.MALFORMED, failure(wrap(v3(1, 1, 1, stonePalette, new byte[] {0}, null)
                .putIntArray("Offset", new int[] {1, 2}))));
        assertEquals(Kind.MALFORMED, failure(wrap(v3(1, 1, 1, stonePalette, new byte[] {0}, null)
                .put("Blocks", NbtCompound.builder().putByteArray("Data", new byte[] {0}).build()))));
        assertEquals(Kind.MALFORMED, failure(wrap(v3(1, 1, 1, stonePalette, new byte[] {0}, null)
                .put("Blocks", NbtCompound.builder().put("Palette", stonePalette).build()))));
        assertEquals(Kind.MALFORMED, failure(wrap(v3(1, 1, 1, NbtCompound.EMPTY, new byte[] {0}, null))));
        assertEquals(Kind.MALFORMED, failure(wrap(v3(1, 1, 1,
                NbtCompound.builder().putString("minecraft:stone", "0").build(), new byte[] {0}, null))));
        assertEquals(Kind.MALFORMED, failure(wrap(v3(1, 1, 1,
                NbtCompound.builder().putInt("minecraft:stone", -1).build(), new byte[] {0}, null))));
        assertEquals(Kind.MALFORMED, failure(wrap(v3(2, 1, 1,
                NbtCompound.builder().putInt("minecraft:stone", 0).putInt("minecraft:dirt", 0).build(), new byte[] {0, 0}, null))));
        assertEquals(Kind.MALFORMED, failure(wrap(v3(2, 1, 1, stonePalette, new byte[] {0}, null))), "data too short");
        assertEquals(Kind.MALFORMED, failure(wrap(v3(1, 1, 1, stonePalette, new byte[] {0, 0}, null))), "trailing data");
        assertEquals(Kind.MALFORMED, failure(wrap(v3(1, 1, 1, stonePalette, new byte[] {1}, null))), "index not in palette");
        byte[] longVarint = {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x00};
        assertEquals(Kind.MALFORMED, failure(wrap(v3(1, 1, 1, stonePalette, longVarint, null))));
        assertEquals(Kind.MALFORMED, failure(wrap(v3(1, 1, 1, stonePalette, new byte[] {0},
                NbtList.of(NbtTag.INT, List.of(new NbtTag.NbtInt(1)))))));
        NbtCompound entitiesNotAList = wrap(v3(1, 1, 1, stonePalette, new byte[] {0}, null)
                .put("Blocks", NbtCompound.builder().put("Palette", stonePalette).putByteArray("Data", new byte[] {0})
                        .put("BlockEntities", NbtCompound.EMPTY).build()));
        assertEquals(Kind.MALFORMED, failure(entitiesNotAList));
        assertEquals(Kind.MALFORMED, failure(wrap(v3(1, 1, 1, stonePalette, new byte[] {0}, null)
                .putIntArray("Offset", new int[] {Integer.MIN_VALUE, 0, 0}))), "an Offset that cannot be negated");
        NbtCompound hostileWeOffset = NbtCompound.builder().putInt("Version", 2).putInt("DataVersion", DATA_VERSION)
                .putShort("Width", (short) 1).putShort("Height", (short) 1).putShort("Length", (short) 1)
                .put("Metadata", NbtCompound.builder().putInt("WEOffsetX", 0).putInt("WEOffsetY", Integer.MIN_VALUE)
                        .putInt("WEOffsetZ", 0).build())
                .put("Palette", stonePalette).putByteArray("BlockData", new byte[] {0}).build();
        assertEquals(Kind.MALFORMED, failure(hostileWeOffset));
        NbtCompound badRotations = wrap(v3(1, 1, 1, stonePalette, new byte[] {0}, null).put("Metadata",
                NbtCompound.builder().put("Sculptory", NbtCompound.builder().putIntArray("Rotations", new int[] {7}).build()).build()));
        assertEquals(Kind.MALFORMED, failure(badRotations));
    }

    @Test
    void refusesOversizedFiles() {
        NbtCompound stonePalette = palette("minecraft:stone");
        SchematicCodec.Limits small = new SchematicCodec.Limits(NbtLimits.DEFAULT, 1000, 4, 1);
        NbtCompound big = wrap(v3(11, 10, 10, stonePalette, new byte[1100], null));
        assertEquals(Kind.TOO_LARGE, assertThrows(SchematicException.class,
                () -> SchematicCodec.decode(big, states, small, identity)).kind());
        NbtCompound wide = wrap(v3(1, 1, 1, stonePalette, new byte[] {0}, null).putInt("Width", 70_000));
        assertEquals(Kind.TOO_LARGE, failure(wide));
        NbtCompound manyStates = wrap(v3(1, 1, 1, palette("a:a", "a:b", "a:c", "a:d", "minecraft:stone"), new byte[] {4}, null));
        assertEquals(Kind.TOO_LARGE, assertThrows(SchematicException.class,
                () -> SchematicCodec.decode(manyStates, states, small, identity)).kind());
        NbtCompound chest = NbtCompound.builder().put("Items", NbtList.EMPTY).build();
        NbtCompound manyEntities = wrap(v3(2, 1, 1, palette("minecraft:chest"), new byte[] {0, 0},
                NbtList.of(NbtTag.COMPOUND, List.of(blockEntity(0, 0, 0, "minecraft:chest", chest),
                        blockEntity(1, 0, 0, "minecraft:chest", chest)))));
        assertEquals(Kind.TOO_LARGE, assertThrows(SchematicException.class,
                () -> SchematicCodec.decode(manyEntities, states, small, identity)).kind());
    }

    /** Corrupted files fail with a typed IOException (or read with losses reported), never a runtime exception. */
    @Test
    void corruptFilesOnlyFailWithTypedErrors() throws IOException {
        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        NbtIo.write(plain, "", SchematicCodec.encode(richClipboard(), METADATA, DATA_VERSION));
        byte[] valid = plain.toByteArray();
        java.util.Random random = new java.util.Random(7);
        int typed = 0;
        for (int round = 0; round < 3000; round++) {
            byte[] corrupt = valid.clone();
            int flips = 1 + random.nextInt(4);
            for (int i = 0; i < flips; i++) corrupt[random.nextInt(corrupt.length)] = (byte) random.nextInt(256);
            if (random.nextInt(10) == 0) corrupt = java.util.Arrays.copyOf(corrupt, random.nextInt(corrupt.length));
            try {
                read(corrupt);
            } catch (IOException expected) {
                typed++;
            }
        }
        assertTrue(typed > 0, "some corruptions must be refused");
    }

    // ================================================================== untrusted-input hardening

    /** Counts data-fix calls: the fixer must run only for palette entries and block entities that matter. */
    private static final class CountingHook implements DataFixHook {
        int states;
        int blockEntities;

        @Override
        public int targetDataVersion() {
            return DATA_VERSION;
        }

        @Override
        public String fixBlockState(String state, int from) {
            states++;
            return state;
        }

        @Override
        public NbtCompound fixBlockEntity(NbtCompound blockEntity, int from) {
            blockEntities++;
            return blockEntity;
        }
    }

    @Test
    void dataFixWorkIsBoundedByTheCellsThatUseIt() throws SchematicException {
        // 100 cells; 100 palette entries, of which only the chest (index 0) and stone (index 1) are used.
        NbtCompound.Builder palette = NbtCompound.builder().putInt("minecraft:chest", 0).putInt("minecraft:stone", 1);
        for (int i = 2; i < 100; i++) palette.putInt("testmod:unused_" + i, i);
        byte[] data = new byte[100];
        java.util.Arrays.fill(data, (byte) 1);
        data[0] = 0;
        List<NbtCompound> entities = new ArrayList<>();
        NbtCompound items = NbtCompound.builder().put("Items", NbtList.EMPTY).build();
        for (int i = 0; i < 30; i++) entities.add(blockEntity(0, 0, 0, "minecraft:chest", items));
        for (int i = 0; i < 30; i++) entities.add(blockEntity(1 + i % 9, 0, 0, "minecraft:chest", items));
        NbtCompound root = wrap(v3(10, 1, 10, palette.build(), data, NbtList.of(NbtTag.COMPOUND, entities))
                .putInt("DataVersion", 1000));
        CountingHook hook = new CountingHook();
        Schematic schematic = decode(root, hook);
        assertEquals(2, hook.states, "only referenced palette entries are fixed");
        assertEquals(1, hook.blockEntities, "duplicates and entities on stone are refused before fixing");
        assertEquals(1, schematic.report().blockEntities());
        assertEquals(59, schematic.report().blockEntitiesSkipped());
        assertTrue(schematic.report().unknownStates().isEmpty(), "unused unknown entries are not reported");

        NbtCompound tooMany = wrap(v3(2, 1, 1, palette("a:a", "a:b", "minecraft:stone"), new byte[] {2, 2}, null));
        assertEquals(Kind.TOO_LARGE, failure(tooMany), "a palette may not outnumber the cells");
    }

    @Test
    void metadataArraysAreBoundedBeforeTheyAreCopied() {
        NbtCompound stonePalette = palette("minecraft:stone");
        NbtCompound hugeRotations = wrap(v3(1, 1, 1, stonePalette, new byte[] {0}, null).put("Metadata",
                NbtCompound.builder().put("Sculptory", NbtCompound.builder()
                        .putIntArray("Rotations", new int[4_000_000]).build()).build()));
        assertEquals(Kind.MALFORMED, failure(hugeRotations));
        List<String> tags = new ArrayList<>();
        for (int i = 0; i <= SchematicCodec.MAX_ASSET_TAGS; i++) tags.add("t" + i);
        assertEquals(Kind.MALFORMED, failure(wrap(v3(1, 1, 1, stonePalette, new byte[] {0}, null).put("Metadata",
                NbtCompound.builder().put("Sculptory", NbtCompound.builder()
                        .put("Tags", NbtList.ofStrings(tags)).build()).build()))));
        assertEquals(Kind.MALFORMED, failure(wrap(v3(1, 1, 1, stonePalette, new byte[] {0}, null).put("Metadata",
                NbtCompound.builder().put("Sculptory", NbtCompound.builder()
                        .put("Tags", NbtList.ofStrings(List.of("x".repeat(200)))).build()).build()))));
        List<String> mods = new ArrayList<>();
        for (int i = 0; i <= SchematicCodec.MAX_REQUIRED_MODS; i++) mods.add("mod" + i);
        assertEquals(Kind.MALFORMED, failure(wrap(v3(1, 1, 1, stonePalette, new byte[] {0}, null).put("Metadata",
                NbtCompound.builder().put("RequiredMods", NbtList.ofStrings(mods)).build()))));
    }

    @Test
    void metadataSavedBeforeTheRenameIsStillRead() throws SchematicException {
        NbtCompound stonePalette = palette("minecraft:stone");
        NbtCompound old = NbtCompound.builder().put("Tags", NbtList.ofStrings(List.of("tree"))).putInt("Weight", 3).build();
        NbtCompound now = NbtCompound.builder().put("Tags", NbtList.ofStrings(List.of("oak"))).build();
        Schematic legacy = decode(wrap(v3(1, 1, 1, stonePalette, new byte[] {0}, null).put("Metadata",
                NbtCompound.builder().put(SchematicCodec.LEGACY_META_KEY, old).build())));
        assertEquals(List.of("tree"), legacy.metadata().sculptory().tags(), "a file written by Builder Suite");
        assertEquals(3, legacy.metadata().sculptory().weight());
        Schematic both = decode(wrap(v3(1, 1, 1, stonePalette, new byte[] {0}, null).put("Metadata",
                NbtCompound.builder().put(SchematicCodec.LEGACY_META_KEY, old).put(SchematicCodec.META_KEY, now).build())));
        assertEquals(List.of("oak"), both.metadata().sculptory().tags(), "with both keys the new one is read");
    }

    @Test
    void oversizedPositionsAndIdsAreSkipped() throws SchematicException {
        NbtCompound items = NbtCompound.builder().put("Items", NbtList.EMPTY).build();
        NbtList entities = NbtList.of(NbtTag.COMPOUND, List.of(
                NbtCompound.builder().putIntArray("Pos", new int[1_000_000]).putString("Id", "minecraft:chest").build(),
                blockEntity(0, 0, 0, "Bad Id!", items),
                blockEntity(1, 0, 0, "chest", items)));
        NbtCompound root = wrap(v3(2, 1, 1, palette("minecraft:chest"), new byte[] {0, 0}, entities));
        Schematic schematic = decode(root);
        assertEquals(1, schematic.report().blockEntities());
        assertEquals(2, schematic.report().blockEntitiesSkipped());
        assertEquals("minecraft:chest", schematic.clipboard().tile(1, 0, 0).typeId(), "a bare id gets minecraft:");
        assertTrue(schematic.report().warnings().stream().anyMatch(w -> w.contains("invalid block entity id")));
    }

    @Test
    void tileBytesAreCapped() {
        NbtCompound named = NbtCompound.builder().putString("CustomName", "x".repeat(200)).build();
        NbtCompound small = NbtCompound.builder().putString("CustomName", "x".repeat(40)).build();
        SchematicCodec.Limits capped = new SchematicCodec.Limits(NbtLimits.DEFAULT, 1000, 16, 16, 150, 120);
        NbtCompound big = wrap(v3(1, 1, 1, palette("minecraft:chest"), new byte[] {0},
                NbtList.of(NbtTag.COMPOUND, List.of(blockEntity(0, 0, 0, "minecraft:chest", named)))));
        assertEquals(Kind.TOO_LARGE, assertThrows(SchematicException.class,
                () -> SchematicCodec.decode(big, states, capped, identity)).kind(), "one tile over the cap");
        NbtCompound two = wrap(v3(2, 1, 1, palette("minecraft:chest"), new byte[] {0, 0},
                NbtList.of(NbtTag.COMPOUND, List.of(blockEntity(0, 0, 0, "minecraft:chest", small),
                        blockEntity(1, 0, 0, "minecraft:chest", small)))));
        assertEquals(Kind.TOO_LARGE, assertThrows(SchematicException.class,
                () -> SchematicCodec.decode(two, states, capped, identity)).kind(), "tiles over the total cap");
    }

    @Test
    void coordinatesAreBounded() throws SchematicException {
        NbtCompound stonePalette = palette("minecraft:stone");
        int limit = SchematicCodec.MAX_COORDINATE;
        assertEquals(new BlockPos(limit, 0, -limit), decode(wrap(v3(1, 1, 1, stonePalette, new byte[] {0}, null)
                .putIntArray("Offset", new int[] {-limit, 0, limit}))).clipboard().anchor());
        assertEquals(Kind.MALFORMED, failure(wrap(v3(1, 1, 1, stonePalette, new byte[] {0}, null)
                .putIntArray("Offset", new int[] {0, limit + 1, 0}))));
        assertEquals(Kind.MALFORMED, failure(wrap(v3(1, 1, 1, stonePalette, new byte[] {0}, null).put("Metadata",
                NbtCompound.builder().put("Sculptory", NbtCompound.builder()
                        .putIntArray("Anchor", new int[] {0, 0, -limit - 1}).build()).build()))));
        Clipboard far = Clipboard.builder(states, new BlockPos(1, 1, 1)).set(0, 0, 0, stone)
                .anchor(new BlockPos(limit + 1, 0, 0)).build();
        assertEquals(Kind.TOO_LARGE, assertThrows(SchematicException.class,
                () -> SchematicCodec.encode(far, SchematicMetadata.EMPTY, DATA_VERSION)).kind());
    }

    @Test
    void uploadLimitsReadOrdinaryFiles() throws IOException {
        Clipboard original = richClipboard();
        Schematic schematic = SchematicCodec.read(new ByteArrayInputStream(write(original, METADATA)), states,
                SchematicCodec.Limits.untrustedUpload(), identity);
        assertEquals(original.contentHash(), schematic.clipboard().contentHash());
    }

    @Test
    void zipBombsAreRefusedWhileInflating() throws IOException {
        // 8 MiB of block data compresses to a few KiB; a 1 MiB cap refuses it before allocating the array.
        NbtCompound root = wrap(v3(256, 128, 256, palette("minecraft:air"), new byte[256 * 128 * 256], null));
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        NbtIo.writeGzip(file, "", root);
        assertTrue(file.size() < 100_000, "compressed size " + file.size());
        SchematicCodec.Limits limits = new SchematicCodec.Limits(
                new NbtLimits(512, 1 << 20, 1 << 25, 1 << 20, 1 << 20), 1L << 30, 1 << 16, 1 << 10);
        NbtLimitException refused = assertThrows(NbtLimitException.class, () -> SchematicCodec.read(
                new ByteArrayInputStream(file.toByteArray()), states, limits, identity));
        assertEquals(NbtLimitException.Limit.BYTES, refused.limit());
    }

    // ================================================================== entities

    private static EntitySnapshot snapshot(String type, double x, double y, double z, float yaw, BlockPos attached,
                                           NbtCompound data) {
        return new EntitySnapshot(type, x, y, z, yaw, 0f, attached, NbtIo.toBytes(data), true);
    }

    private static NbtCompound entityEntry(String id, double x, double y, double z, NbtCompound data) {
        NbtCompound.Builder entry = NbtCompound.builder().put("Pos", EntityNbt.doubles(x, y, z));
        if (id != null) entry.putString("Id", id);
        return entry.put("Data", data).build();
    }

    private static NbtList list(NbtCompound... entries) {
        return NbtList.of(NbtTag.COMPOUND, List.of(entries));
    }

    private static NbtCompound data(EntitySnapshot entity) {
        try {
            return EntityNbt.decode(entity.nbt());
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void entitiesRoundTripThroughVersion3() throws IOException {
        NbtCompound frameData = NbtCompound.builder().putByte("Facing", (byte) 3)
                .put("Item", NbtCompound.builder().putString("id", "minecraft:diamond").putInt("count", 1).build())
                .build();
        NbtCompound standData = NbtCompound.builder().putString("CustomName", "\"Bob\"").build();
        Clipboard.Builder builder = Clipboard.builder(states, new BlockPos(4, 3, 4)).anchor(new BlockPos(1, 0, 1));
        for (int y = 0; y < 3; y++) {
            for (int z = 0; z < 4; z++) {
                for (int x = 0; x < 4; x++) builder.set(x, y, z, y == 0 || z == 1 ? stone : air);
            }
        }
        Clipboard clipboard = builder
                .addEntity(snapshot("minecraft:item_frame", 1.5, 1.5, 1.03125, 0f, new BlockPos(1, 1, 1), frameData))
                .addEntity(snapshot("minecraft:armor_stand", 2.5, 1, 3.5, 30f, null, standData))
                .build();
        byte[] file = write(clipboard, SchematicMetadata.EMPTY);

        NbtCompound schem = NbtIo.readAuto(new ByteArrayInputStream(file), NbtLimits.DEFAULT).value()
                .getCompound("Schematic");
        NbtList entities = schem.getList("Entities");
        assertEquals(2, entities.size());
        NbtCompound stand = entities.compounds().get(1); // canonical order: the frame's cell (z 1) comes first
        assertEquals("minecraft:armor_stand", stand.getString("Id"));
        assertArrayEquals(new double[] {2.5, 1, 3.5}, EntityNbt.position(stand), "Pos relative to the minimum corner");
        NbtCompound data = stand.getCompound("Data");
        assertEquals("\"Bob\"", data.getString("CustomName"));
        assertArrayEquals(new float[] {30f, 0f}, EntityNbt.rotation(data));
        assertNull(data.get("UUID"));
        assertEquals(1, entities.compounds().get(0).getCompound("Data").getInt("TileX"), "attachment, local");

        Schematic read = read(file);
        assertEquals(2, read.report().entities());
        assertEquals(0, read.report().entitiesSkipped());
        assertTrue(read.report().isLossless());
        assertEquals(clipboard.entities(), read.clipboard().entities());
        assertTrue(read.clipboard().entities().stream().noneMatch(EntitySnapshot::trusted), "entities from files");
        assertEquals(clipboard.contentHash(), read.clipboard().contentHash());
    }

    @Test
    void readsWorldEditStyleEntities() throws SchematicException {
        // Version 3 as WorldEdit writes it: Pos relative, Data still holding the world position and attachment.
        NbtCompound frame = NbtCompound.builder().putString("id", "minecraft:item_frame")
                .put("Pos", EntityNbt.doubles(101.5, 65.5, -198.96875)).putIntArray("UUID", new int[] {1, 2, 3, 4})
                .putInt("TileX", 101).putInt("TileY", 65).putInt("TileZ", -199).putByte("Facing", (byte) 3)
                .put("Rotation", NbtList.of(NbtTag.FLOAT, List.of(new NbtTag.NbtFloat(0f), new NbtTag.NbtFloat(0f))))
                .build();
        NbtCompound root = wrap(v3(3, 2, 2, palette("minecraft:stone"), new byte[12], null)
                .put("Entities", list(entityEntry("minecraft:item_frame", 1.5, 1.5, 1.03125, frame))));
        Schematic schematic = decode(root);
        EntitySnapshot read = schematic.clipboard().entities().get(0);
        assertEquals(new BlockPos(1, 1, 1), read.attached(), "TileX/Y/Z moved with the entity");
        assertEquals(1.03125, read.z());
        NbtCompound data = data(read);
        for (String key : EntityNbt.PLACEMENT_KEYS) assertNull(data.get(key), key + " is placement, not data");
        assertEquals(new NbtTag.NbtByte((byte) 3), data.get("Facing"));
    }

    @Test
    void readsVersion2EntitiesAtAbsolutePositions() throws SchematicException {
        NbtCompound pig = NbtCompound.builder().putString("Id", "pig")
                .put("Pos", EntityNbt.doubles(101.5, 64, -199.5))
                .put("Rotation", NbtList.of(NbtTag.FLOAT, List.of(new NbtTag.NbtFloat(90f), new NbtTag.NbtFloat(10f))))
                .putByte("Saddle", (byte) 1).build();
        NbtCompound root = NbtCompound.builder()
                .putInt("Version", 2).putInt("DataVersion", DATA_VERSION)
                .putShort("Width", (short) 3).putShort("Height", (short) 1).putShort("Length", (short) 2)
                .putIntArray("Offset", new int[] {100, 64, -200})
                .putInt("PaletteMax", 1).put("Palette", palette("minecraft:stone"))
                .putByteArray("BlockData", new byte[6])
                .put("Entities", list(pig))
                .build();
        Schematic schematic = SchematicCodec.decode(NbtCompound.builder().put("Schematic", root).build(), states,
                SchematicCodec.Limits.DEFAULT, identity);
        EntitySnapshot read = schematic.clipboard().entities().get(0);
        assertEquals("minecraft:pig", read.typeId(), "namespace added");
        assertEquals(1.5, read.x());
        assertEquals(0, read.y());
        assertEquals(0.5, read.z());
        assertEquals(90f, read.yaw());
        assertEquals(10f, read.pitch());
        assertEquals(new NbtTag.NbtByte((byte) 1), data(read).get("Saddle"), "version 2 data is inline");
    }

    @Test
    void entitiesThatCannotBePlacedAreSkippedAndReported() throws SchematicException {
        NbtCompound notFinite = NbtCompound.builder()
                .put("Pos", NbtList.of(NbtTag.DOUBLE, List.of(new NbtTag.NbtDouble(Double.NaN),
                        new NbtTag.NbtDouble(0), new NbtTag.NbtDouble(0))))
                .putString("Id", "minecraft:pig").build();
        NbtCompound root = wrap(v3(2, 1, 2, palette("minecraft:stone"), new byte[4], null)
                .put("Entities", list(
                        entityEntry("minecraft:pig", 0.5, 0, 0.5, NbtCompound.EMPTY),          // kept
                        entityEntry(null, 0.5, 0, 0.5, NbtCompound.EMPTY),                     // no Id
                        NbtCompound.builder().putString("Id", "minecraft:pig").build(),        // no Pos
                        entityEntry("minecraft:pig", 2.5, 0, 0.5, NbtCompound.EMPTY),          // outside the box
                        entityEntry("minecraft:pig", -0.5, 0, 0.5, NbtCompound.EMPTY),         // outside the box
                        entityEntry("Not An Id", 0.5, 0, 0.5, NbtCompound.EMPTY),              // invalid id
                        notFinite)));
        Schematic schematic = decode(root);
        assertEquals(1, schematic.clipboard().entityCount());
        assertEquals(1, schematic.report().entities());
        assertEquals(6, schematic.report().entitiesSkipped());
        assertFalse(schematic.report().isLossless());
        assertTrue(schematic.report().warnings().stream().anyMatch(w -> w.startsWith("6 entities skipped")),
                schematic.report().warnings().toString());

        NbtCompound notCompounds = wrap(v3(1, 1, 1, palette("minecraft:stone"), new byte[1], null)
                .put("Entities", NbtList.of(NbtTag.INT, List.of(new NbtTag.NbtInt(1)))));
        assertEquals(Kind.MALFORMED, failure(notCompounds));
    }

    @Test
    void entityCountsAndBytesAreCapped() {
        NbtCompound two = wrap(v3(1, 1, 1, palette("minecraft:stone"), new byte[1], null)
                .put("Entities", list(entityEntry("minecraft:pig", 0.5, 0, 0.5, NbtCompound.EMPTY),
                        entityEntry("minecraft:cow", 0.5, 0, 0.5, NbtCompound.EMPTY))));
        SchematicException tooMany = assertThrows(SchematicException.class, () -> SchematicCodec.decode(two, states,
                SchematicCodec.Limits.DEFAULT.withMaxEntities(1), identity));
        assertEquals(Kind.TOO_LARGE, tooMany.kind());

        NbtCompound big = NbtCompound.builder().putString("CustomName", "x".repeat(40_000)).build();
        NbtCompound large = wrap(v3(1, 1, 1, palette("minecraft:stone"), new byte[1], null)
                .put("Entities", list(entityEntry("minecraft:pig", 0.5, 0, 0.5, big))));
        SchematicCodec.Limits small = new SchematicCodec.Limits(NbtLimits.DEFAULT, 1000, 100, 10, 16_384, 1 << 20);
        assertEquals(Kind.TOO_LARGE, assertThrows(SchematicException.class,
                () -> SchematicCodec.decode(large, states, small, identity)).kind());
        SchematicCodec.Limits smallTotal = new SchematicCodec.Limits(NbtLimits.DEFAULT, 1000, 100, 10, 1 << 20, 16_384);
        assertEquals(Kind.TOO_LARGE, assertThrows(SchematicException.class,
                () -> SchematicCodec.decode(large, states, smallTotal, identity)).kind(), "entities share the tile total");
    }

    @Test
    void theDataFixHookUpgradesEntitiesOfOlderFiles() throws SchematicException {
        int[] calls = {0};
        DataFixHook renaming = new DataFixHook() {
            @Override
            public int targetDataVersion() {
                return DATA_VERSION;
            }

            @Override
            public String fixBlockState(String state, int fromDataVersion) {
                return state;
            }

            @Override
            public NbtCompound fixBlockEntity(NbtCompound tile, int fromDataVersion) {
                return tile;
            }

            @Override
            public NbtCompound fixEntity(NbtCompound entity, int fromDataVersion) {
                calls[0]++;
                assertEquals(1343, fromDataVersion);
                assertEquals("minecraft:zombie_pigman", entity.getString("id"));
                return entity.toBuilder().putString("id", "minecraft:zombified_piglin").putInt("Fixed", 1).build();
            }
        };
        NbtCompound root = wrap(v3(1, 1, 1, palette("minecraft:stone"), new byte[1], null).putInt("DataVersion", 1343)
                .put("Entities", list(entityEntry("minecraft:zombie_pigman", 0.5, 0, 0.5, NbtCompound.EMPTY))));
        EntitySnapshot read = decode(root, renaming).clipboard().entities().get(0);
        assertEquals(1, calls[0]);
        assertEquals("minecraft:zombified_piglin", read.typeId(), "the fixed id wins");
        assertEquals(1, data(read).getInt("Fixed"));
    }

    @Test
    void version1EntitiesAreSkipped() throws SchematicException {
        NbtCompound root = NbtCompound.builder()
                .putInt("Version", 1)
                .putShort("Width", (short) 1).putShort("Height", (short) 1).putShort("Length", (short) 1)
                .put("Palette", palette("minecraft:stone"))
                .putByteArray("BlockData", new byte[1])
                .put("Entities", list(entityEntry("minecraft:pig", 0.5, 0, 0.5, NbtCompound.EMPTY)))
                .build();
        Schematic schematic = decode(root);
        assertEquals(0, schematic.clipboard().entityCount());
        assertEquals(1, schematic.report().entitiesSkipped());
    }

    /** A file's entity may carry many passengers (the rest of the file is small): each root keeps at most 16. */
    @Test
    void passengersAreCappedPerEntityAndCountTowardTheEntityLimit() throws SchematicException {
        List<NbtCompound> riders = new ArrayList<>();
        for (int i = 0; i < 90_000; i++) riders.add(NbtCompound.builder().putString("id", "minecraft:armor_stand").build());
        NbtCompound stand = NbtCompound.builder().put("Passengers", NbtList.of(NbtTag.COMPOUND, riders)).build();
        NbtCompound root = wrap(v3(1, 1, 1, palette("minecraft:stone"), new byte[1], null)
                .put("Entities", list(entityEntry("minecraft:armor_stand", 0.5, 0, 0.5, stand))));
        Schematic schematic = decode(root);
        EntitySnapshot read = schematic.clipboard().entities().get(0);
        assertEquals(EntityNbt.MAX_RIDERS, EntityNbt.riderCount(data(read)));
        assertEquals(1, schematic.report().entities());
        assertEquals(90_000 - EntityNbt.MAX_RIDERS, schematic.report().entitiesSkipped());
        assertTrue(schematic.report().warnings().stream().anyMatch(w -> w.contains("passengers left out")),
                schematic.report().warnings().toString());
        assertEquals(1 + EntityNbt.MAX_RIDERS, schematic.clipboard().entityTotal());

        // The limit counts passengers: one entity with its 16 riders is 17.
        SchematicException over = assertThrows(SchematicException.class, () -> SchematicCodec.decode(root, states,
                SchematicCodec.Limits.DEFAULT.withMaxEntities(EntityNbt.MAX_RIDERS), identity));
        assertEquals(Kind.TOO_LARGE, over.kind());
        SchematicCodec.decode(root, states, SchematicCodec.Limits.DEFAULT.withMaxEntities(EntityNbt.MAX_RIDERS + 1),
                identity);
    }
}
