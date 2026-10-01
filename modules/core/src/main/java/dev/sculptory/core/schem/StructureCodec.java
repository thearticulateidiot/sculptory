package dev.sculptory.core.schem;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.entity.EntityNbt;
import dev.sculptory.core.entity.EntitySnapshot;
import dev.sculptory.core.nbt.NbtCompound;
import dev.sculptory.core.nbt.NbtIo;
import dev.sculptory.core.nbt.NbtList;
import dev.sculptory.core.nbt.NbtTag;
import dev.sculptory.core.schem.SchematicException.Kind;
import dev.sculptory.core.state.StateSpace;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;

/**
 * Reads and writes vanilla structure files ({@code .nbt}, what a structure block saves), following the game's
 * {@code StructureTemplate.readNbt}/{@code writeNbt}.
 *
 * <p><b>Layout.</b> A gzip-compressed root compound: {@code DataVersion}, {@code size} (a list of three ints),
 * {@code palette} (a list of {@code {Name, Properties}} compounds) or {@code palettes} (a list of such lists, for
 * structures the game places with a random palette), {@code blocks} (a list of {@code {pos: [x, y, z], state:
 * palette index, nbt: block entity}}) and {@code entities} (a list of {@code {pos: [x, y, z] doubles, blockPos: [x, y,
 * z], nbt: entity}}). Positions are relative to the structure's minimum corner.
 *
 * <p><b>Reading.</b> Cells the file does not list are absent (as the structure block leaves out structure voids): a
 * paste leaves them alone. Listed blocks are kept as they are, air as air and a listed {@code structure_void} as that
 * block (as the game places it). Of several
 * palettes the first is used (reported). Block states are data-fixed from {@code DataVersion} (a file without one is
 * read as 1.13.2), block entities and entities as in {@link SchematicCodec}; unknown states become air and are
 * reported. A block listed twice keeps its last entry (reported), a block outside {@code size} is skipped (reported),
 * and a palette index outside the palette refuses the file. A hanging entity's block is its {@code blockPos}. The
 * anchor is {@code Sculptory.Anchor} when the file has one (files this codec writes), else the minimum corner.
 *
 * <p><b>Writing.</b> Present cells only (air included), the palette in first-use order over the cell order, so equal
 * clipboards encode to equal bytes; entities with {@code pos}, {@code blockPos} (a hanging entity's block, else the
 * cell holding its position) and their data with {@code id} and {@code Pos} (relative; the game replaces it when it
 * places the structure). {@code Sculptory} carries the anchor and library metadata; the game ignores it. A structure
 * block loads at most {@value #MAX_STRUCTURE_BLOCK_SIDE} blocks per side, which the writer does not enforce (the
 * server warns).
 */
public final class StructureCodec {
    /** The largest side a vanilla structure block saves or loads (1.21.1). */
    public static final int MAX_STRUCTURE_BLOCK_SIDE = 48;

    private StructureCodec() {}

    // ================================================================== reading

    /** Reads a gzip-compressed (or uncompressed) structure file. Does not close {@code in}. */
    public static Schematic read(InputStream in, StateSpace states, SchematicCodec.Limits limits, DataFixHook hook)
            throws IOException {
        Objects.requireNonNull(in);
        Objects.requireNonNull(limits);
        return decode(NbtIo.readAuto(in, limits.nbt()).value(), states, limits, hook);
    }

    /** Decodes a structure document (the root compound). */
    public static Schematic decode(NbtCompound root, StateSpace states, SchematicCodec.Limits limits, DataFixHook hook)
            throws SchematicException {
        Objects.requireNonNull(root);
        Objects.requireNonNull(states);
        Objects.requireNonNull(limits);
        Objects.requireNonNull(hook);
        return new Reader(root, states, limits, hook).read();
    }

    private static final class Reader {
        private final NbtCompound root;
        private final StateSpace states;
        private final SchematicCodec.Limits limits;
        private final DataFixHook hook;
        private FileImport in;
        private int width, height, length;

        Reader(NbtCompound root, StateSpace states, SchematicCodec.Limits limits, DataFixHook hook) {
            this.root = root;
            this.states = states;
            this.limits = limits;
            this.hook = hook;
        }

        Schematic read() throws SchematicException {
            NbtTag dataTag = root.get("DataVersion");
            if (dataTag != null && !(dataTag instanceof NbtTag.NbtInt)) throw malformed("DataVersion is not an int");
            int dataVersion = dataTag == null ? SchematicCodec.VERSION_1_DATA_VERSION : ((NbtTag.NbtInt) dataTag).value();
            int[] size = ints(root.get("size"));
            if (size == null) throw malformed("size is not a list of three ints");
            for (int i = 0; i < 3; i++) {
                if (size[i] < 1) throw malformed("size " + size[i] + "; an empty structure has nothing to place");
                if (size[i] > SchematicCodec.MAX_SIDE) {
                    throw new SchematicException(Kind.TOO_LARGE, "size " + size[i] + " > " + SchematicCodec.MAX_SIDE);
                }
            }
            width = size[0];
            height = size[1];
            length = size[2];
            long volume = (long) width * height * length;
            // The box may be mostly empty, so it is capped even without a volume limit: what can be read can be written.
            long boxCap = Math.min(limits.maxVolume(), SchematicCodec.MAX_WRITE_VOLUME);
            if (volume > boxCap) throw new SchematicException(Kind.TOO_LARGE, volume + " cells > " + boxCap);
            NbtCompound ours = SchematicCodec.ours(root);
            SchematicMetadata metadata = metadata(ours, root.getString("author"));
            BlockPos anchor = ours == null ? BlockPos.ORIGIN : SchematicCodec.vector(ours, "Anchor", BlockPos.ORIGIN);
            in = new FileImport(states, limits, hook, dataVersion, new BlockPos(width, height, length), anchor,
                    metadata.name() == null ? "structure" : "structure:" + metadata.name());
            if (dataTag == null) in.warn("no DataVersion; read as " + SchematicCodec.VERSION_1_DATA_VERSION);

            // A palette may list states no block uses (several palettes share the blocks' indices): only its size is capped.
            List<NbtCompound> palette = palette();
            if (palette.size() > limits.maxPaletteSize()) {
                throw new SchematicException(Kind.TOO_LARGE, "palette of " + palette.size() + " > "
                        + limits.maxPaletteSize() + " entries");
            }
            NbtList blocks = list("blocks");
            if (blocks == null) throw malformed("missing blocks");
            // A cell may be listed twice (the last entry wins), so the entries are capped as a clipboard's cells are.
            if (blocks.size() > limits.maxVolume()) {
                throw new SchematicException(Kind.TOO_LARGE, blocks.size() + " blocks listed > " + limits.maxVolume());
            }
            List<NbtCompound> entries = blocks.compounds();
            if (entries == null) throw malformed("blocks holds something other than compounds");
            blocks(entries, palette);
            entities();
            return new Schematic(SchematicFormat.STRUCTURE, 0, dataVersion, SchematicCodec.negate(anchor), metadata,
                    in.build(), in.report(false));
        }

        /** {@code palettes}' first list when there is one (as the game reads it), else {@code palette}. */
        private List<NbtCompound> palette() throws SchematicException {
            NbtTag palettes = root.get("palettes");
            NbtList list;
            if (palettes != null) {
                if (!(palettes instanceof NbtList all) || all.isEmpty() || !(all.get(0) instanceof NbtList first)) {
                    throw malformed("palettes is not a list of palettes");
                }
                if (all.size() > 1) in.warn("the file has " + all.size() + " palettes; the first is used");
                list = first;
            } else {
                list = list("palette");
                if (list == null) throw malformed("missing palette");
            }
            List<NbtCompound> entries = list.compounds();
            if (entries == null) throw malformed("palette holds something other than compounds");
            return entries;
        }

        private void blocks(List<NbtCompound> entries, List<NbtCompound> paletteEntries) throws SchematicException {
            int n = paletteEntries.size();
            String[] specs = new String[n];
            String[] labels = new String[n];
            for (int i = 0; i < n; i++) {
                specs[i] = FileImport.entrySpec(paletteEntries.get(i), "palette entry " + i);
                labels[i] = specs[i] != null ? specs[i] : FileImport.entryLabel(paletteEntries.get(i));
            }
            FileImport.Palette palette = in.new Palette(specs, labels);
            BitSet seen = new BitSet();
            // Cells listed more than once: the entry that wins (the last), so only its block entity is read.
            Long2IntOpenHashMap last = new Long2IntOpenHashMap();
            int outside = 0, repeated = 0, withTiles = 0;
            for (int e = 0; e < entries.size(); e++) {
                NbtCompound entry = entries.get(e);
                int[] pos = ints(entry.get("pos"));
                if (pos == null) throw malformed("block " + e + " has no pos of three ints");
                NbtTag stateTag = entry.get("state");
                if (!(stateTag instanceof NbtTag.NbtInt state)) throw malformed("block " + e + " has no int state");
                if (state.value() < 0 || state.value() >= n) {
                    throw malformed("block " + e + " uses palette index " + state.value() + ", but the palette has "
                            + n + " entries");
                }
                if (entry.contains("nbt")) withTiles++;
                int x = pos[0], y = pos[1], z = pos[2];
                if (x < 0 || y < 0 || z < 0 || x >= width || y >= height || z >= length) {
                    outside++;
                    continue;
                }
                int cell = (y * length + z) * width + x;
                if (seen.get(cell)) {
                    repeated++;
                    last.put(cell, e);
                } else {
                    seen.set(cell);
                }
                in.builder.set(x, y, z, palette.handle(state.value()));
            }
            palette.finish();
            if (outside > 0) in.warn(outside + " blocks outside the structure's size skipped");
            if (repeated > 0) in.warn(repeated + " blocks listed more than once; the last entry kept");
            if (withTiles == 0) return;
            in.checkBlockEntityCount(withTiles);
            for (int e = 0; e < entries.size(); e++) {
                NbtCompound entry = entries.get(e);
                NbtTag nbt = entry.get("nbt");
                if (nbt == null) continue;
                int[] pos = ints(entry.get("pos"));
                int x = pos[0], y = pos[1], z = pos[2];
                if (x < 0 || y < 0 || z < 0 || x >= width || y >= height || z >= length) {
                    in.tileSkipped("pos " + x + "," + y + "," + z + " outside the structure");
                    continue;
                }
                int cell = (y * length + z) * width + x;
                if (last.containsKey(cell) && last.get(cell) != e) {
                    in.tileSkipped("a block entity at " + x + "," + y + "," + z + " replaced by a later entry");
                    continue;
                }
                if (!(nbt instanceof NbtCompound data)) {
                    in.tileSkipped("nbt at " + x + "," + y + "," + z + " is not a compound");
                    continue;
                }
                in.tile(x, y, z, data.getString("id"), data);
            }
        }

        private void entities() throws SchematicException {
            NbtList list = list("entities");
            if (list == null || list.isEmpty()) return;
            in.checkEntityCount(list.size());
            List<NbtCompound> entries = list.compounds();
            if (entries == null) throw malformed("entities holds something other than compounds");
            for (NbtCompound entry : entries) {
                double[] pos = doubles(entry.get("pos"));
                NbtTag nbt = entry.get("nbt");
                if (!(nbt instanceof NbtCompound data)) {
                    in.entitySkipped("no nbt");
                    continue;
                }
                int[] blockPos = ints(entry.get("blockPos"));
                in.entity(data.getString("id"), data, pos, (compound, at) -> {
                    boolean hanging = compound.contains("TileX") || compound.contains("TileY") || compound.contains("TileZ");
                    return hanging && blockPos != null ? FileImport.near(blockPos[0], blockPos[1], blockPos[2], at) : null;
                });
            }
            in.finishEntities();
        }

        private NbtList list(String key) throws SchematicException {
            NbtTag tag = root.get(key);
            if (tag == null) return null;
            if (!(tag instanceof NbtList list)) throw malformed(key + " is not a list");
            return list;
        }
    }

    private static SchematicMetadata metadata(NbtCompound ours, String author) throws SchematicException {
        if (ours == null) return author == null ? SchematicMetadata.EMPTY
                : new SchematicMetadata(null, author, null, List.of(), null);
        NbtTag.NbtLong date = ours.get("Date", NbtTag.NbtLong.class);
        String name = ours.getString("Name");
        String by = ours.getString("Author");
        return new SchematicMetadata(name, by != null ? by : author, date == null ? null : date.value(), List.of(),
                SchematicCodec.assetInfo(ours), ours.getString("Description"));
    }

    /** A list of three ints ({@code null} when {@code tag} is not one). */
    private static int[] ints(NbtTag tag) {
        if (!(tag instanceof NbtList list) || list.size() != 3 || list.elementType() != NbtTag.INT) return null;
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) out[i] = ((NbtTag.NbtInt) list.get(i)).value();
        return out;
    }

    /** A list of three finite doubles ({@code null} when {@code tag} is not one). */
    private static double[] doubles(NbtTag tag) {
        if (!(tag instanceof NbtList list) || list.size() != 3 || list.elementType() != NbtTag.DOUBLE) return null;
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) {
            out[i] = ((NbtTag.NbtDouble) list.get(i)).value();
            if (!Double.isFinite(out[i])) return null;
        }
        return out;
    }

    private static SchematicException malformed(String message) {
        return new SchematicException(Kind.MALFORMED, message);
    }

    // ================================================================== writing

    /** Writes a gzip-compressed structure file. Does not close {@code out}. */
    public static void write(OutputStream out, Clipboard clipboard, SchematicMetadata metadata, int dataVersion)
            throws IOException {
        NbtIo.writeGzip(out, "", encode(clipboard, metadata, dataVersion));
    }

    /**
     * Encodes a structure document (see the class comment).
     *
     * @throws SchematicException ({@code TOO_LARGE}) if a side exceeds {@value SchematicCodec#MAX_SIDE}, the volume
     *     exceeds {@link SchematicCodec#MAX_WRITE_VOLUME}, or the anchor is beyond
     *     &plusmn;{@value SchematicCodec#MAX_COORDINATE}; ({@code MALFORMED}) if a tile's or entity's NBT cannot be
     *     decoded
     */
    public static NbtCompound encode(Clipboard clipboard, SchematicMetadata metadata, int dataVersion)
            throws SchematicException {
        Objects.requireNonNull(clipboard);
        Objects.requireNonNull(metadata);
        BlockPos size = clipboard.size();
        if (size.x() > SchematicCodec.MAX_SIDE || size.y() > SchematicCodec.MAX_SIDE || size.z() > SchematicCodec.MAX_SIDE) {
            throw new SchematicException(Kind.TOO_LARGE, "clipboard " + size + " exceeds " + SchematicCodec.MAX_SIDE
                    + " per side");
        }
        if (clipboard.volume() > SchematicCodec.MAX_WRITE_VOLUME) {
            throw new SchematicException(Kind.TOO_LARGE, clipboard.volume() + " cells > " + SchematicCodec.MAX_WRITE_VOLUME);
        }
        if (!SchematicCodec.withinCoordinates(clipboard.anchor())) {
            throw new SchematicException(Kind.TOO_LARGE, "anchor beyond " + SchematicCodec.MAX_COORDINATE
                    + " cannot be stored");
        }
        StateSpace states = clipboard.states();
        Int2IntOpenHashMap paletteIndex = new Int2IntOpenHashMap();
        paletteIndex.defaultReturnValue(-1);
        List<NbtTag> palette = new ArrayList<>();
        List<NbtTag> blocks = new ArrayList<>();
        SchematicException[] failure = {null};
        clipboard.forEachCell((x, y, z, state, tile) -> {
            if (state < 0 || failure[0] != null) return;
            int index = paletteIndex.get(state);
            if (index < 0) {
                index = palette.size();
                paletteIndex.put(state, index);
                palette.add(LitematicCodec.paletteEntry(states, state));
            }
            NbtCompound.Builder block = NbtCompound.builder().put("pos", ints(x, y, z)).putInt("state", index);
            if (tile != null) {
                try {
                    block.put("nbt", SchematicCodec.tileData(x, y, z, tile).build());
                } catch (SchematicException e) {
                    failure[0] = e;
                }
            }
            blocks.add(block.build());
        });
        if (failure[0] != null) throw failure[0];
        List<NbtTag> entities = new ArrayList<>(clipboard.entityCount());
        for (EntitySnapshot entity : clipboard.entities()) entities.add(entity(entity));

        NbtCompound.Builder ours = NbtCompound.builder();
        if (metadata.name() != null) ours.putString("Name", metadata.name());
        if (metadata.author() != null) ours.putString("Author", metadata.author());
        if (metadata.description() != null) ours.putString("Description", metadata.description());
        if (metadata.dateMillis() != null) ours.putLong("Date", metadata.dateMillis());
        AssetInfo asset = metadata.sculptory();
        if (asset != null) SchematicCodec.assetTag(asset).entries().forEach(ours::put);
        BlockPos anchor = clipboard.anchor();
        ours.putIntArray("Anchor", new int[] {anchor.x(), anchor.y(), anchor.z()});
        return NbtCompound.builder()
                .putInt("DataVersion", dataVersion)
                .put("size", ints(size.x(), size.y(), size.z()))
                .put("palette", NbtList.of(NbtTag.COMPOUND, palette))
                .put("blocks", NbtList.of(NbtTag.COMPOUND, blocks))
                .put("entities", NbtList.of(NbtTag.COMPOUND, entities))
                .put(SchematicCodec.META_KEY, ours.build())
                .build();
    }

    /**
     * An entity entry: {@code pos} (relative), {@code blockPos} (a hanging entity's block, else the cell holding its
     * position) and {@code nbt}: {@code id} first, its data ({@link SchematicCodec#entityData}) and {@code Pos}.
     */
    private static NbtCompound entity(EntitySnapshot entity) throws SchematicException {
        NbtCompound data = SchematicCodec.entityData(entity).build();
        NbtCompound.Builder nbt = NbtCompound.builder().putString("id", entity.typeId());
        data.entries().forEach(nbt::put);
        nbt.put("Pos", EntityNbt.doubles(entity.x(), entity.y(), entity.z()));
        BlockPos block = entity.attached() != null ? entity.attached()
                : new BlockPos((int) Math.floor(entity.x()), (int) Math.floor(entity.y()), (int) Math.floor(entity.z()));
        return NbtCompound.builder()
                .put("pos", EntityNbt.doubles(entity.x(), entity.y(), entity.z()))
                .put("blockPos", ints(block.x(), block.y(), block.z()))
                .put("nbt", nbt.build())
                .build();
    }

    private static NbtList ints(int x, int y, int z) {
        return NbtList.of(NbtTag.INT, List.of(new NbtTag.NbtInt(x), new NbtTag.NbtInt(y), new NbtTag.NbtInt(z)));
    }
}
