package dev.sculptory.core.schem;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.biome.BiomeGrid;
import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.entity.EntityNbt;
import dev.sculptory.core.entity.EntitySnapshot;
import dev.sculptory.core.nbt.BlockEntityNbt;
import dev.sculptory.core.nbt.NbtCompound;
import dev.sculptory.core.nbt.NbtIo;
import dev.sculptory.core.nbt.NbtLimits;
import dev.sculptory.core.nbt.NbtList;
import dev.sculptory.core.nbt.NbtTag;
import dev.sculptory.core.schem.SchematicException.Kind;
import dev.sculptory.core.state.StateSpace;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Reads Sponge schematics (versions 1, 2 and 3) and writes version 3, following the public Sponge
 * specification and WorldEdit 7.3's reader and writer.
 *
 * <p><b>Layout.</b> A version 3 file is a root compound (named {@code ""}) holding one compound
 * {@code Schematic}; versions 1 and 2 put the fields in the root itself (both are accepted for every version).
 * Blocks are a palette ({@code "id[props]" -> int}) plus a varint byte array in cell order
 * {@code x + z*Width + y*Width*Length}. {@code Width}, {@code Height} and {@code Length} are unsigned shorts.
 * Block entities are {@code {Pos:int[3] (local), Id, Data:{...}}} in version 3 and
 * {@code {Pos, Id, ...fields inline}} in versions 1 and 2.
 *
 * <p><b>Anchor.</b> Version 3 stores {@code Offset = -anchor}. In versions 1 and 2 WorldEdit stores the absolute
 * minimum corner in {@code Offset} and {@code min - origin} in {@code Metadata.WEOffsetX/Y/Z}, so the anchor is
 * {@code -WEOffset} ((0, 0, 0) without it). Every stored coordinate must be within
 * &plusmn;{@value #MAX_COORDINATE}.
 *
 * <p>Unknown states become air and are reported; block entities that cannot be placed are counted and skipped. Nothing
 * is dropped silently.
 *
 * <p><b>Biomes</b> (protocol 6) are read from version 3's {@code Biomes: {Palette, Data}} (a biome per cell) and version
 * 2's {@code BiomePalette}/{@code BiomeData} (a biome per column) into the clipboard's quart grid, and written as
 * version 3 {@code Biomes} (a cell of an absent quart takes the grid's first biome) with the grid's phase in
 * {@code Metadata.SculptoryBiomePhase}, which other readers ignore. Malformed biome data is skipped and reported.
 *
 * <p><b>Entities</b> (versions 2 and 3) are {@code {Pos: double[3], Id, Data}}
 * entries in the schematic's {@code Entities} list ({@code Pos} relative to the minimum corner in version 3, absolute in
 * version 2, whose fields are inline). They are data-fixed ({@link DataFixHook#fixEntity}) and read as untrusted
 * {@link EntitySnapshot}s; one without an id or a position, or whose cell lies outside the box, is counted and skipped.
 * A version 1 file's entities are skipped. The writer writes version 3 entries (see {@link #encode}).
 *
 * <p><b>Untrusted input.</b> Use {@link Limits#untrustedUpload()}. Work is bounded by the file's cells, not its
 * declared sizes: palette entries are resolved (and data-fixed) only when a cell uses them, and a block entity is
 * data-fixed only when its cell holds a block-entity state and no earlier entry claimed that cell.
 *
 * <p>What every format shares (palettes, block entities, entities, the report) is {@link FileImport}'s; the other
 * formats are {@link LitematicCodec} and {@link StructureCodec}, and {@link SchematicFiles} reads any of them.
 */
public final class SchematicCodec {
    /** Version 1 files predate {@code DataVersion}; WorldEdit assumes 1.13.2. */
    public static final int VERSION_1_DATA_VERSION = 1631;
    /** Largest side the format can store (unsigned short). */
    public static final int MAX_SIDE = 0xFFFF;
    /** Largest volume the writer accepts: the varint block data must fit one byte array. */
    public static final long MAX_WRITE_VOLUME = Integer.MAX_VALUE - 64;
    /** Largest magnitude of a stored offset or anchor coordinate (the world border). */
    public static final int MAX_COORDINATE = 30_000_000;
    /** Metadata bounds: {@code Sculptory.Tags} entries and their length, {@code RequiredMods} entries. */
    public static final int MAX_ASSET_TAGS = 64;
    public static final int MAX_ASSET_TAG_LENGTH = 128;
    public static final int MAX_REQUIRED_MODS = 256;
    /** Most biome palette entries a file may hold (a modded server lists a few hundred), checked before any is fixed. */
    public static final int MAX_BIOME_PALETTE = 4096;
    /** The compound that holds this mod's library metadata ({@link AssetInfo}) in a schematic. */
    public static final String META_KEY = "Sculptory";
    /** Its name in files written before the rename: read when {@link #META_KEY} is absent, never written. */
    public static final String LEGACY_META_KEY = "BuilderSuite";

    private SchematicCodec() {}

    /**
     * Read limits.
     *
     * @param nbt limits for the NBT document (the zip-bomb and allocation guards)
     * @param maxVolume maximum cells (Width * Height * Length)
     * @param maxPaletteSize palette entries and largest palette index + 1 (the entries are also capped at the volume)
     * @param maxBlockEntities maximum block-entity entries
     * @param maxTileBytes maximum encoded NBT of one block entity (and of one entity)
     * @param maxTotalTileBytes maximum encoded NBT of all block entities and entities together
     * @param maxEntities maximum entities, their passengers included
     */
    public record Limits(NbtLimits nbt, long maxVolume, int maxPaletteSize, int maxBlockEntities, int maxTileBytes,
                         long maxTotalTileBytes, int maxEntities) {
        /** For trusted files: 2,097,152 cells (the default {@code maxClipboardVolume}), 2 MiB per tile, 64 MiB of tiles. */
        public static final Limits DEFAULT = new Limits(NbtLimits.DEFAULT, 2_097_152L, 1 << 20, 1 << 18);
        /** Entity entries a file may hold unless a limit says otherwise. */
        public static final int DEFAULT_MAX_ENTITIES = 1 << 16;

        public Limits {
            Objects.requireNonNull(nbt);
            if (maxVolume < 1 || maxPaletteSize < 1 || maxBlockEntities < 0 || maxTileBytes < 1 || maxTotalTileBytes < 1
                    || maxEntities < 0) {
                throw new IllegalArgumentException("Schematic limits must be positive");
            }
        }

        /** Limits with {@link #DEFAULT_MAX_ENTITIES} entities. */
        public Limits(NbtLimits nbt, long maxVolume, int maxPaletteSize, int maxBlockEntities, int maxTileBytes,
                      long maxTotalTileBytes) {
            this(nbt, maxVolume, maxPaletteSize, maxBlockEntities, maxTileBytes, maxTotalTileBytes, DEFAULT_MAX_ENTITIES);
        }

        /** Limits with the default tile caps (2 MiB per tile, 64 MiB in total). */
        public Limits(NbtLimits nbt, long maxVolume, int maxPaletteSize, int maxBlockEntities) {
            this(nbt, maxVolume, maxPaletteSize, maxBlockEntities, 2 << 20, 64L << 20);
        }

        /** These limits with another entity cap. */
        public Limits withMaxEntities(int entities) {
            return new Limits(nbt, maxVolume, maxPaletteSize, maxBlockEntities, maxTileBytes, maxTotalTileBytes, entities);
        }

        /**
         * For files uploaded by players: {@link NbtLimits#untrustedUpload()}, 2,097,152 cells, 65,536 palette
         * entries, 65,536 block entities, 1 MiB per tile and 32 MiB of tiles.
         */
        public static Limits untrustedUpload() {
            return new Limits(NbtLimits.untrustedUpload(), 2_097_152L, 1 << 16, 1 << 16, 1 << 20, 32L << 20);
        }
    }

    // ================================================================== reading

    /** Reads a gzip-compressed (or uncompressed) schematic. Does not close {@code in}. */
    public static Schematic read(InputStream in, StateSpace states, Limits limits, DataFixHook hook) throws IOException {
        Objects.requireNonNull(in);
        Objects.requireNonNull(limits);
        return decode(NbtIo.readAuto(in, limits.nbt()).value(), states, limits, hook);
    }

    /** Decodes a schematic document (the root compound). */
    public static Schematic decode(NbtCompound root, StateSpace states, Limits limits, DataFixHook hook)
            throws SchematicException {
        Objects.requireNonNull(root);
        Objects.requireNonNull(states);
        Objects.requireNonNull(limits);
        Objects.requireNonNull(hook);
        NbtCompound schem = root.getCompound("Schematic");
        if (schem == null) schem = root;
        return new Reader(schem, states, limits, hook).read();
    }

    private static final class Reader {
        private final NbtCompound schem;
        private final StateSpace states;
        private final Limits limits;
        private final DataFixHook hook;
        private int version;
        private int width, height, length;
        private FileImport in;

        Reader(NbtCompound schem, StateSpace states, Limits limits, DataFixHook hook) {
            this.schem = schem;
            this.states = states;
            this.limits = limits;
            this.hook = hook;
        }

        Schematic read() throws SchematicException {
            Integer v = schem.getInt("Version");
            if (v == null) throw malformed("missing Version");
            version = v;
            if (version < 1 || version > 3) {
                throw new SchematicException(Kind.UNSUPPORTED, "Sponge schematic version " + version + " is not supported");
            }
            Integer dv = schem.getInt("DataVersion");
            if (dv == null && version > 1) throw malformed("missing DataVersion");
            int dataVersion = dv == null ? VERSION_1_DATA_VERSION : dv;
            width = side("Width");
            height = side("Height");
            length = side("Length");
            long volume = (long) width * height * length;
            if (volume > limits.maxVolume()) {
                throw new SchematicException(Kind.TOO_LARGE, volume + " cells > " + limits.maxVolume());
            }
            BlockPos offset = vector(schem, "Offset", BlockPos.ORIGIN);
            NbtCompound meta = schem.getCompound("Metadata");
            BlockPos anchor = version == 3 ? negate(offset) : negate(weOffset(meta));
            SchematicMetadata metadata = metadata(meta);

            in = new FileImport(states, limits, hook, dataVersion, new BlockPos(width, height, length), anchor,
                    metadata.name() == null ? "schematic" : "schematic:" + metadata.name());
            NbtList blockEntities;
            if (version == 3) {
                NbtCompound blocks = schem.getCompound("Blocks");
                if (blocks == null) {
                    // Blocks are optional in version 3 (a biome-only file): every cell is air.
                    fillAir();
                    blockEntities = null;
                } else {
                    decodeBlocks(require(blocks, "Palette", NbtCompound.class, "Blocks.Palette"),
                            require(blocks, "Data", NbtTag.NbtByteArray.class, "Blocks.Data"));
                    blockEntities = list(blocks, "BlockEntities");
                }
            } else {
                decodeBlocks(require(schem, "Palette", NbtCompound.class, "Palette"),
                        require(schem, "BlockData", NbtTag.NbtByteArray.class, "BlockData"));
                blockEntities = schem.contains("BlockEntities") ? list(schem, "BlockEntities") : list(schem, "TileEntities");
            }
            decodeBlockEntities(blockEntities);
            decodeEntities(schem.getList("Entities"), offset);

            boolean biomesSkipped = false;
            try {
                BiomeGrid biomes = decodeBiomes(offset, meta, dataVersion);
                if (biomes != null) in.builder.biomes(biomes);
            } catch (SchematicException e) {
                in.warn("biome data skipped (" + e.getMessage() + ")");
                biomesSkipped = true;
            }
            SchematicReport report = in.report(biomesSkipped);
            return new Schematic(SchematicFormat.SPONGE, version, dataVersion, offset, metadata, in.build(), report);
        }

        /**
         * Reads the biomes (protocol 6) into a grid: version 3's {@code Biomes: {Palette, Data}} (a biome per cell, in cell
         * order) or version 2's {@code BiomePalette} and {@code BiomeData} (a biome per column, {@code x + z*Width}); a
         * version 1 file has none. Each quart takes the biome of its first cell in cell order. The grid's phase is the one
         * our writer stored ({@code Metadata.SculptoryBiomePhase}), else the minimum corner's within its quart for a
         * version 2 file (whose {@code Offset} is that corner in the world), else 0. Ids are data-fixed
         * ({@link DataFixHook#fixBiome}); an entry that is not a namespaced id leaves its cells without a biome.
         *
         * @return the grid, or {@code null} when the file holds no biomes
         * @throws SchematicException when the biome data is malformed (the caller skips it and reports that)
         */
        private BiomeGrid decodeBiomes(BlockPos offset, NbtCompound meta, int dataVersion) throws SchematicException {
            NbtCompound palette;
            NbtTag.NbtByteArray data;
            boolean perColumn;
            if (version == 3) {
                NbtCompound biomes = schem.getCompound("Biomes");
                if (biomes == null) return null;
                palette = require(biomes, "Palette", NbtCompound.class, "Biomes.Palette");
                data = require(biomes, "Data", NbtTag.NbtByteArray.class, "Biomes.Data");
                perColumn = false;
            } else if (version == 2 && (schem.contains("BiomeData") || schem.contains("BiomePalette"))) {
                palette = require(schem, "BiomePalette", NbtCompound.class, "BiomePalette");
                data = require(schem, "BiomeData", NbtTag.NbtByteArray.class, "BiomeData");
                perColumn = true;
            } else {
                return null;
            }
            if (palette.isEmpty() || palette.size() > Math.min(MAX_BIOME_PALETTE, limits.maxPaletteSize())) {
                throw malformed("biome palette of " + palette.size() + " entries");
            }
            String[] names = new String[palette.size()];
            for (Map.Entry<String, NbtTag> entry : palette.entries().entrySet()) {
                if (!(entry.getValue() instanceof NbtTag.NbtInt index) || index.value() < 0
                        || index.value() >= names.length || names[index.value()] != null) {
                    throw malformed("biome palette entry " + entry.getKey());
                }
                String id = FileImport.namespaced(entry.getKey());
                if (id != null && dataVersion < hook.targetDataVersion()) {
                    try {
                        id = FileImport.namespaced(hook.fixBiome(id, dataVersion));
                    } catch (RuntimeException e) {
                        id = null;
                    }
                }
                names[index.value()] = id == null ? "" : id;
            }
            int[] stored = meta == null ? null : meta.getIntArray("SculptoryBiomePhase");
            BlockPos phase;
            if (stored != null && stored.length == 3 && inPhase(stored[0]) && inPhase(stored[1]) && inPhase(stored[2])) {
                phase = new BlockPos(stored[0], stored[1], stored[2]);
            } else if (version == 2) {
                phase = new BlockPos(offset.x() & 3, offset.y() & 3, offset.z() & 3);
            } else {
                phase = BlockPos.ORIGIN;
            }
            BiomeGrid.Builder grid;
            try {
                grid = BiomeGrid.builder(phase, new BlockPos(width, height, length));
            } catch (IllegalArgumentException e) {
                throw malformed("biome grid: " + e.getMessage());
            }
            long cells = perColumn ? (long) width * length : (long) width * height * length;
            int pos = 0, size = data.length();
            boolean unnamed = false;
            for (long cell = 0; cell < cells; cell++) {
                int value = 0, shift = 0;
                while (true) {
                    if (pos >= size) throw malformed("biome data ends after " + cell + " of " + cells + " cells");
                    int b = data.get(pos++);
                    value |= (b & 0x7F) << shift;
                    if ((b & 0x80) == 0) break;
                    shift += 7;
                    if (shift > 28) throw malformed("varint longer than 5 bytes in the biome data");
                }
                if (value < 0 || value >= names.length) throw malformed("biome data index " + value + " not in the palette");
                String id = names[value];
                if (id.isEmpty()) {
                    unnamed = true;
                    continue;
                }
                int x = (int) (cell % width);
                if (perColumn) {
                    int z = (int) (cell / width);
                    for (int y = 0; y < height; y += 4 - ((y + phase.y()) & 3)) {
                        if (!grid.hasAt(x, y, z)) setBiome(grid, x, y, z, id);
                    }
                } else {
                    int z = (int) (cell / width % length), y = (int) (cell / ((long) width * length));
                    if (!grid.hasAt(x, y, z)) setBiome(grid, x, y, z, id);
                }
            }
            if (pos != size) throw malformed((size - pos) + " bytes of biome data after the last cell");
            if (unnamed) in.warn("biomes that are not namespaced ids skipped");
            return grid.build();
        }

        /** Sets a cell's biome; the grid refusing it (too many biomes) makes the biome data malformed. */
        private static void setBiome(BiomeGrid.Builder grid, int x, int y, int z, String id) throws SchematicException {
            try {
                grid.setAt(x, y, z, id);
            } catch (IllegalArgumentException e) {
                throw malformed("biome grid: " + e.getMessage());
            }
        }

        private static boolean inPhase(int v) {
            return v >= 0 && v <= 3;
        }

        /**
         * Reads entities (versions 2 and 3; a version 1 file's are counted and skipped). Skipped entities are reported
         * in one warning with the first reason.
         *
         * @throws SchematicException ({@code TOO_LARGE}) over the entity count or the tile byte caps, which entities
         *     share with block entities
         */
        private void decodeEntities(NbtList list, BlockPos offset) throws SchematicException {
            if (list == null || list.isEmpty()) return;
            if (version == 1) {
                in.entitiesSkipped(list.size(), "version 1 files hold none that can be read");
                return;
            }
            in.checkEntityCount(list.size());
            List<NbtCompound> entries = list.compounds();
            if (entries == null) throw malformed("entities are not compounds");
            for (NbtCompound entry : entries) readEntity(entry, offset);
            in.finishEntities();
        }

        /**
         * Reads one entity. {@code Pos} is relative to the box's minimum corner in version 3 and absolute (in the frame
         * of {@code Offset}, the minimum corner) in version 2; the entity data is {@code Data} in version 3 and the entry
         * itself in version 2. {@code TileX/Y/Z} give a hanging entity's block ({@link #attachment}).
         */
        private void readEntity(NbtCompound entry, BlockPos offset) throws SchematicException {
            double[] pos = EntityNbt.position(entry);
            if (pos != null && version == 2) {
                pos[0] -= offset.x();
                pos[1] -= offset.y();
                pos[2] -= offset.z();
            }
            String id = entry.getString("Id");
            if (id == null) id = entry.getString("id");
            NbtCompound data;
            if (version == 3) {
                data = entry.getCompound("Data");
                if (data == null) data = NbtCompound.EMPTY;
            } else {
                data = entry.toBuilder().remove("Id").remove("Pos").build();
            }
            BlockPos frame = version == 2 ? offset : null;
            in.entity(id, data, pos, (compound, at) -> attachment(compound, at, frame));
        }

        private void decodeBlocks(NbtCompound palette, NbtTag.NbtByteArray data) throws SchematicException {
            long volume = (long) width * height * length;
            // A palette needs no more entries than cells: this bounds the work a crafted palette can cause.
            int maxEntries = (int) Math.min(limits.maxPaletteSize(), volume);
            if (palette.size() > maxEntries) {
                throw new SchematicException(Kind.TOO_LARGE, "palette of " + palette.size() + " > " + maxEntries + " entries");
            }
            if (palette.isEmpty()) throw malformed("empty palette");
            int maxIndex = -1;
            for (Map.Entry<String, NbtTag> entry : palette.entries().entrySet()) {
                if (!(entry.getValue() instanceof NbtTag.NbtInt index)) {
                    throw malformed("palette entry " + entry.getKey() + " is not an int");
                }
                if (index.value() < 0 || index.value() >= limits.maxPaletteSize()) {
                    throw malformed("palette index " + index.value() + " out of range");
                }
                maxIndex = Math.max(maxIndex, index.value());
            }
            String[] names = new String[maxIndex + 1];
            for (Map.Entry<String, NbtTag> entry : palette.entries().entrySet()) {
                int index = ((NbtTag.NbtInt) entry.getValue()).value();
                if (names[index] != null) throw malformed("palette index " + index + " is used twice");
                names[index] = entry.getKey();
            }
            FileImport.Palette resolved = in.new Palette(names, names);

            int pos = 0, x = 0, y = 0, z = 0, size = data.length();
            for (long cell = 0; cell < volume; cell++) {
                // Varint: 7 bits per byte, low group first, high bit set on all but the last byte.
                int value = 0, shift = 0;
                while (true) {
                    if (pos >= size) throw malformed("block data ends after " + cell + " of " + volume + " cells");
                    int b = data.get(pos++);
                    value |= (b & 0x7F) << shift;
                    if ((b & 0x80) == 0) break;
                    shift += 7;
                    if (shift > 28) throw malformed("varint longer than 5 bytes at byte " + pos);
                }
                if (value < 0 || value > maxIndex || names[value] == null) {
                    throw malformed("block data references palette index " + value + " not in the palette");
                }
                in.builder.set(x, y, z, resolved.handle(value));
                if (++x == width) {
                    x = 0;
                    if (++z == length) {
                        z = 0;
                        y++;
                    }
                }
            }
            if (pos != size) throw malformed((size - pos) + " bytes of block data after the last cell");
            resolved.finish();
        }

        /** Attaches block entities. */
        private void decodeBlockEntities(NbtList list) throws SchematicException {
            if (list == null || list.isEmpty()) return;
            in.checkBlockEntityCount(list.size());
            List<NbtCompound> entries = list.compounds();
            if (entries == null) throw malformed("block entities are not compounds");
            for (NbtCompound entry : entries) {
                NbtTag.NbtIntArray pos = entry.get("Pos", NbtTag.NbtIntArray.class);
                if (pos == null || pos.length() != 3) {
                    in.tileSkipped("no Pos");
                    continue;
                }
                String id = entry.getString("Id");
                if (id == null) id = entry.getString("id");
                NbtCompound data;
                if (version == 3) {
                    data = entry.getCompound("Data");
                    if (data == null) data = NbtCompound.EMPTY;
                } else {
                    data = entry.toBuilder().remove("Id").remove("Pos").build();
                }
                in.tile(pos.get(0), pos.get(1), pos.get(2), id, data);
            }
        }

        private void fillAir() {
            int air = states.air();
            for (int y = 0; y < height; y++) {
                for (int z = 0; z < length; z++) {
                    for (int x = 0; x < width; x++) in.builder.set(x, y, z, air);
                }
            }
        }

        private int side(String key) throws SchematicException {
            NbtTag tag = schem.get(key);
            int value = switch (tag) {
                case NbtTag.NbtShort s -> s.value() & 0xFFFF;
                case NbtTag.NbtByte b -> b.value() & 0xFF;
                case NbtTag.NbtInt i -> i.value();
                case null -> throw malformed("missing " + key);
                default -> throw malformed(key + " is not a number");
            };
            if (value < 1) throw malformed(key + " is " + value + "; an empty schematic has nothing to place");
            if (value > MAX_SIDE) throw new SchematicException(Kind.TOO_LARGE, key + " " + value + " > " + MAX_SIDE);
            return value;
        }

        private NbtList list(NbtCompound compound, String key) throws SchematicException {
            NbtTag tag = compound.get(key);
            if (tag == null) return null;
            if (!(tag instanceof NbtList list)) throw malformed(key + " is not a list");
            return list;
        }
    }

    /**
     * A hanging entity's block relative to the box's minimum corner, from its {@code TileX}/{@code TileY}/{@code TileZ},
     * or {@code null} when it has none or it is not next to the entity ({@code pos}, relative). Files keep the block in
     * the frame of the entity's own data: version 2 in the absolute frame of {@code Offset} ({@code absoluteFrame}); a
     * version 3 file from WorldEdit keeps the original world's block next to the original {@code Pos} in {@code Data}
     * (the block moves as the position did); this codec writes it relative, with no {@code Pos} in {@code Data}. The
     * server derives the block from the position when there is none.
     */
    private static BlockPos attachment(NbtCompound data, double[] pos, BlockPos absoluteFrame) {
        Integer tx = data.getInt("TileX"), ty = data.getInt("TileY"), tz = data.getInt("TileZ");
        if (tx == null || ty == null || tz == null) return null;
        long x = tx, y = ty, z = tz;
        double[] original = EntityNbt.position(data);
        if (absoluteFrame != null) {
            x -= absoluteFrame.x();
            y -= absoluteFrame.y();
            z -= absoluteFrame.z();
        } else if (original != null) {
            x += Math.round(pos[0] - original[0]);
            y += Math.round(pos[1] - original[1]);
            z += Math.round(pos[2] - original[2]);
        }
        return FileImport.near(x, y, z, pos);
    }

    private static BlockPos weOffset(NbtCompound meta) throws SchematicException {
        if (meta == null) return BlockPos.ORIGIN;
        Integer x = meta.getInt("WEOffsetX"), y = meta.getInt("WEOffsetY"), z = meta.getInt("WEOffsetZ");
        if (x == null || y == null || z == null) return BlockPos.ORIGIN;
        return bounded(new BlockPos(x, y, z), "WEOffset");
    }

    private static SchematicMetadata metadata(NbtCompound meta) throws SchematicException {
        if (meta == null) return SchematicMetadata.EMPTY;
        NbtTag.NbtLong date = meta.get("Date", NbtTag.NbtLong.class);
        return new SchematicMetadata(meta.getString("Name"), meta.getString("Author"),
                date == null ? null : date.value(), strings(meta, "RequiredMods", MAX_REQUIRED_MODS, 256),
                assetInfo(ours(meta)));
    }

    /** A list of strings, bounded before it is copied; an absent or ill-typed list is empty. */
    static List<String> strings(NbtCompound compound, String key, int maxEntries, int maxLength)
            throws SchematicException {
        NbtList list = compound.getList(key);
        if (list == null) return List.of();
        if (list.size() > maxEntries) throw malformed(key + " has " + list.size() + " > " + maxEntries + " entries");
        List<String> values = list.strings();
        if (values == null) return List.of();
        for (String value : values) {
            if (value.length() > maxLength) throw malformed(key + " entry longer than " + maxLength);
        }
        return values;
    }

    static AssetInfo assetInfo(NbtCompound bs) throws SchematicException {
        if (bs == null) return null;
        List<String> tags = strings(bs, "Tags", MAX_ASSET_TAGS, MAX_ASSET_TAG_LENGTH);
        BlockPos anchor = vector(bs, "Anchor", BlockPos.ORIGIN);
        NbtTag.NbtIntArray rotations = bs.get("Rotations", NbtTag.NbtIntArray.class);
        Integer weight = bs.getInt("Weight");
        List<Integer> turns = new ArrayList<>();
        if (rotations == null) {
            turns.addAll(AssetInfo.ALL_ROTATIONS);
        } else {
            if (rotations.length() > 4) throw malformed("Sculptory.Rotations has " + rotations.length() + " > 4 entries");
            for (int i = 0; i < rotations.length(); i++) {
                int turn = rotations.get(i);
                if (turn < 0 || turn > 3) throw malformed("Sculptory.Rotations holds " + turn);
                turns.add(turn);
            }
        }
        if (weight != null && weight < 1) throw malformed("Sculptory.Weight is " + weight);
        return new AssetInfo(tags, anchor, turns, weight == null ? 1 : weight);
    }

    /** An int[3] within &plusmn;{@value #MAX_COORDINATE}. */
    static BlockPos vector(NbtCompound compound, String key, BlockPos absent) throws SchematicException {
        NbtTag tag = compound.get(key);
        if (tag == null) return absent;
        if (!(tag instanceof NbtTag.NbtIntArray array) || array.length() != 3) throw malformed(key + " is not an int[3]");
        return bounded(new BlockPos(array.get(0), array.get(1), array.get(2)), key);
    }

    static BlockPos bounded(BlockPos p, String what) throws SchematicException {
        if (!withinCoordinates(p)) throw malformed(what + " " + p + " is beyond " + MAX_COORDINATE);
        return p;
    }

    static boolean withinCoordinates(BlockPos p) {
        return Math.abs((long) p.x()) <= MAX_COORDINATE && Math.abs((long) p.y()) <= MAX_COORDINATE
                && Math.abs((long) p.z()) <= MAX_COORDINATE;
    }

    /** Negation of a bounded vector (never overflows). */
    static BlockPos negate(BlockPos p) {
        return new BlockPos(-p.x(), -p.y(), -p.z());
    }

    static <T extends NbtTag> T require(NbtCompound compound, String key, Class<T> type, String what)
            throws SchematicException {
        NbtTag tag = compound.get(key);
        if (tag == null) throw malformed("missing " + what);
        if (!type.isInstance(tag)) throw malformed(what + " has the wrong type");
        return type.cast(tag);
    }

    static SchematicException malformed(String message) {
        return new SchematicException(Kind.MALFORMED, message);
    }

    // ================================================================== writing

    /** Writes a gzip-compressed Sponge version 3 schematic. Does not close {@code out}. */
    public static void write(OutputStream out, Clipboard clipboard, SchematicMetadata metadata, int dataVersion)
            throws IOException {
        NbtIo.writeGzip(out, "", encode(clipboard, metadata, dataVersion));
    }

    /**
     * Encodes a Sponge version 3 document: root {@code {Schematic: {Version: 3, DataVersion, Metadata, Width,
     * Height, Length, Offset: -anchor, Blocks: {Palette, Data, BlockEntities}}}}. The palette is in first-use
     * order over the cell order, so equal clipboards encode to equal bytes. Absent cells are written as air.
     *
     * @throws SchematicException ({@code TOO_LARGE}) if a side exceeds {@value #MAX_SIDE}, the volume exceeds
     *     {@link #MAX_WRITE_VOLUME}, or an anchor coordinate is beyond &plusmn;{@value #MAX_COORDINATE};
     *     ({@code MALFORMED}) if a tile's NBT cannot be decoded
     */
    public static NbtCompound encode(Clipboard clipboard, SchematicMetadata metadata, int dataVersion)
            throws SchematicException {
        Objects.requireNonNull(clipboard);
        Objects.requireNonNull(metadata);
        BlockPos size = clipboard.size();
        if (size.x() > MAX_SIDE || size.y() > MAX_SIDE || size.z() > MAX_SIDE) {
            throw new SchematicException(Kind.TOO_LARGE, "clipboard " + size + " exceeds " + MAX_SIDE + " per side");
        }
        // The block data takes at least a byte per cell and must fit one array.
        if (clipboard.volume() > MAX_WRITE_VOLUME) {
            throw new SchematicException(Kind.TOO_LARGE, clipboard.volume() + " cells > " + MAX_WRITE_VOLUME);
        }
        if (!withinCoordinates(clipboard.anchor())
                || (metadata.sculptory() != null && !withinCoordinates(metadata.sculptory().anchor()))) {
            throw new SchematicException(Kind.TOO_LARGE, "anchor beyond " + MAX_COORDINATE + " cannot be stored");
        }
        BlockPos offset = negate(clipboard.anchor());
        StateSpace states = clipboard.states();
        int air = states.air();
        Int2IntOpenHashMap paletteIndex = new Int2IntOpenHashMap();
        paletteIndex.defaultReturnValue(-1);
        LinkedHashMap<String, Integer> palette = new LinkedHashMap<>();
        TreeSet<String> namespaces = new TreeSet<>(metadata.requiredMods());
        ByteArrayOutputStream data = new ByteArrayOutputStream((int) Math.min(1 << 20, clipboard.volume()));
        List<NbtTag> blockEntities = new ArrayList<>();
        SchematicException[] failure = {null};
        clipboard.forEachCell((x, y, z, state, tile) -> {
            int h = state < 0 ? air : state;
            int index = paletteIndex.get(h);
            if (index < 0) {
                index = palette.size();
                paletteIndex.put(h, index);
                palette.put(states.format(h), index);
                addNamespace(namespaces, states.blockId(h).value());
            }
            writeVarint(data, index);
            if (tile != null && failure[0] == null) {
                try {
                    blockEntities.add(blockEntity(x, y, z, tile));
                    addNamespace(namespaces, tile.typeId());
                } catch (SchematicException e) {
                    failure[0] = e;
                }
            }
        });
        if (failure[0] != null) throw failure[0];

        List<NbtTag> entities = new ArrayList<>(clipboard.entityCount());
        for (EntitySnapshot entity : clipboard.entities()) {
            entities.add(entity(entity));
            addNamespace(namespaces, entity.typeId());
        }

        // A modded biome's namespace is a mod the file needs too (protocol 6).
        BiomeGrid grid = clipboard.biomes();
        if (grid != null) {
            for (String id : grid.ids()) addNamespace(namespaces, id);
        }
        NbtCompound metaTag = metadataTag(metadata, List.copyOf(namespaces));
        if (grid != null) {
            BlockPos phase = grid.phase();
            metaTag = metaTag.toBuilder().putIntArray("SculptoryBiomePhase", new int[] {phase.x(), phase.y(), phase.z()})
                    .build();
        }
        NbtCompound.Builder paletteTag = NbtCompound.builder();
        palette.forEach(paletteTag::putInt);
        NbtCompound blocks = NbtCompound.builder()
                .put("Palette", paletteTag.build())
                .putByteArray("Data", data.toByteArray())
                .put("BlockEntities", NbtList.of(NbtTag.COMPOUND, blockEntities))
                .build();
        NbtCompound.Builder schem = NbtCompound.builder()
                .putInt("Version", 3)
                .putInt("DataVersion", dataVersion)
                .put("Metadata", metaTag)
                .putShort("Width", (short) size.x())
                .putShort("Height", (short) size.y())
                .putShort("Length", (short) size.z())
                .putIntArray("Offset", new int[] {offset.x(), offset.y(), offset.z()})
                .put("Blocks", blocks);
        if (!entities.isEmpty()) schem.put("Entities", NbtList.of(NbtTag.COMPOUND, entities));
        if (grid != null) schem.put("Biomes", biomes(clipboard, grid));
        return NbtCompound.builder().put("Schematic", schem.build()).build();
    }

    /**
     * Version 3's {@code Biomes: {Palette, Data}}: a biome per cell, in cell order, the palette in first-use order; a
     * cell of an absent quart takes the grid's first biome.
     */
    private static NbtCompound biomes(Clipboard clipboard, BiomeGrid grid) {
        BlockPos size = clipboard.size();
        String fallback = grid.ids().get(0);
        LinkedHashMap<String, Integer> palette = new LinkedHashMap<>();
        ByteArrayOutputStream data = new ByteArrayOutputStream((int) Math.min(1 << 20, clipboard.volume()));
        for (int y = 0; y < size.y(); y++) {
            for (int z = 0; z < size.z(); z++) {
                for (int x = 0; x < size.x(); x++) {
                    String id = grid.biomeAt(x, y, z);
                    if (id == null) id = fallback;
                    Integer index = palette.get(id);
                    if (index == null) {
                        index = palette.size();
                        palette.put(id, index);
                    }
                    writeVarint(data, index);
                }
            }
        }
        NbtCompound.Builder paletteTag = NbtCompound.builder();
        palette.forEach(paletteTag::putInt);
        return NbtCompound.builder().put("Palette", paletteTag.build()).putByteArray("Data", data.toByteArray()).build();
    }

    /**
     * A version 3 entity entry: {@code Pos} relative to the box's minimum corner, {@code Id}, and {@code Data} holding
     * the entity's data ({@link #entityData}); no {@code UUID}.
     *
     * @throws SchematicException ({@code MALFORMED}) if the entity's NBT cannot be decoded
     */
    private static NbtCompound entity(EntitySnapshot entity) throws SchematicException {
        return NbtCompound.builder()
                .put("Pos", EntityNbt.doubles(entity.x(), entity.y(), entity.z()))
                .putString("Id", entity.typeId())
                .put("Data", entityData(entity).build())
                .build();
    }

    /**
     * An entity's data as files keep it: without {@link EntityNbt#PLACEMENT_KEYS}, with its {@code Rotation} and, for a
     * hanging entity, its block as {@code TileX}/{@code TileY}/{@code TileZ} relative to the box's minimum corner (as
     * WorldEdit reads it).
     *
     * @throws SchematicException ({@code MALFORMED}) if the entity's NBT cannot be decoded
     */
    static NbtCompound.Builder entityData(EntitySnapshot entity) throws SchematicException {
        NbtCompound nbt;
        try {
            nbt = EntityNbt.decode(entity.nbt());
        } catch (IOException e) {
            throw new SchematicException(Kind.MALFORMED, "entity " + entity.typeId() + " has bad NBT", e);
        }
        NbtCompound.Builder data = nbt.toBuilder();
        for (String key : EntityNbt.PLACEMENT_KEYS) data.remove(key);
        data.put("Rotation", NbtList.of(NbtTag.FLOAT, List.of(new NbtTag.NbtFloat(entity.yaw()),
                new NbtTag.NbtFloat(entity.pitch()))));
        BlockPos attached = entity.attached();
        if (attached != null) data.putInt("TileX", attached.x()).putInt("TileY", attached.y()).putInt("TileZ", attached.z());
        return data;
    }

    private static NbtCompound blockEntity(int x, int y, int z, BlockEntityData tile) throws SchematicException {
        return NbtCompound.builder()
                .putString("Id", tile.typeId())
                .putIntArray("Pos", new int[] {x, y, z})
                .put("Data", tileData(x, y, z, tile).remove("id").build())
                .build();
    }

    /**
     * A tile's NBT without {@code x}/{@code y}/{@code z}, its {@code id} first.
     *
     * @throws SchematicException ({@code MALFORMED}) if the NBT cannot be decoded
     */
    static NbtCompound.Builder tileData(int x, int y, int z, BlockEntityData tile) throws SchematicException {
        NbtCompound nbt;
        try {
            nbt = BlockEntityNbt.decode(tile);
        } catch (IOException e) {
            throw new SchematicException(Kind.MALFORMED, "block entity at " + x + "," + y + "," + z + " has bad NBT", e);
        }
        return BlockEntityNbt.normalize(tile.typeId(), nbt).toBuilder();
    }

    private static NbtCompound metadataTag(SchematicMetadata metadata, List<String> requiredMods) {
        NbtCompound.Builder meta = NbtCompound.builder();
        if (metadata.name() != null) meta.putString("Name", metadata.name());
        if (metadata.author() != null) meta.putString("Author", metadata.author());
        if (metadata.dateMillis() != null) meta.putLong("Date", metadata.dateMillis());
        meta.put("RequiredMods", NbtList.ofStrings(requiredMods));
        if (metadata.sculptory() != null) meta.put(META_KEY, assetTag(metadata.sculptory()));
        return meta.build();
    }

    /**
     * {@code parent}'s {@link #META_KEY} compound, else its {@link #LEGACY_META_KEY} one (a file written before the
     * rename), else {@code null}.
     */
    static NbtCompound ours(NbtCompound parent) {
        if (parent == null) return null;
        NbtCompound ours = parent.getCompound(META_KEY);
        return ours != null ? ours : parent.getCompound(LEGACY_META_KEY);
    }

    /** The {@code Sculptory} compound of library metadata ({@link #assetInfo} reads it). */
    static NbtCompound assetTag(AssetInfo asset) {
        BlockPos a = asset.anchor();
        return NbtCompound.builder()
                .put("Tags", NbtList.ofStrings(asset.tags()))
                .putIntArray("Anchor", new int[] {a.x(), a.y(), a.z()})
                .putIntArray("Rotations", asset.rotations().stream().mapToInt(Integer::intValue).toArray())
                .putInt("Weight", asset.weight())
                .build();
    }

    static void addNamespace(TreeSet<String> namespaces, String id) {
        int colon = id.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : id.substring(0, colon);
        if (!namespace.equals("minecraft") && !namespace.isEmpty()) namespaces.add(namespace);
    }

    private static void writeVarint(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }
}
