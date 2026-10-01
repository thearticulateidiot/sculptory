package dev.sculptory.core.schem;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.BlockPos;
import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.entity.EntityNbt;
import dev.sculptory.core.entity.EntitySnapshot;
import dev.sculptory.core.nbt.NbtCompound;
import dev.sculptory.core.nbt.NbtIo;
import dev.sculptory.core.nbt.NbtList;
import dev.sculptory.core.nbt.NbtTag;
import dev.sculptory.core.schem.SchematicException.Kind;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.state.StateSpace;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reads and writes Litematica schematics ({@code .litematic}), versions 2 to 6 in and version 6 (sub-version 1, as
 * Litematica for Minecraft 1.21.1 writes) out, following Litematica's {@code LitematicaSchematic} and
 * {@code LitematicaBitArray}.
 *
 * <p><b>Layout.</b> A gzip-compressed root compound: {@code Version}, {@code SubVersion} (6 only),
 * {@code MinecraftDataVersion}, {@code Metadata} ({@code Name}, {@code Author}, {@code Description},
 * {@code RegionCount}, {@code TotalVolume}, {@code TotalBlocks}, {@code TimeCreated}, {@code TimeModified},
 * {@code EnclosingSize {x, y, z}}) and {@code Regions}, a compound of named regions. A region has:
 * <ul>
 *   <li>{@code Position {x, y, z}}: its first corner relative to the schematic origin, and {@code Size {x, y, z}}:
 *       its extent from there, each component non-zero and negative where the region runs the other way (a size of
 *       -5 from x = 10 covers x = 6..10). The region's box is the two corners' enclosing box;</li>
 *   <li>{@code BlockStatePalette}: a list of {@code {Name, Properties}} compounds (index 0 is air in files Litematica
 *       writes), and {@code BlockStates}: palette indices packed into longs, cell order {@code x + z*sx + y*sx*sz} from
 *       the box's minimum corner, {@code bits = max(2, ceil(log2(palette size)))} bits each, low bits first, a value
 *       running over into the next long where it does not fit (unlike the game's chunk format), with exactly
 *       {@code ceil(volume * bits / 64)} longs;</li>
 *   <li>{@code TileEntities}: block-entity compounds with {@code x}/{@code y}/{@code z} relative to the box's minimum
 *       corner; {@code Entities}: entity compounds whose {@code Pos} is relative to the region's {@code Position} (its
 *       first corner, not its minimum);</li>
 *   <li>{@code PendingBlockTicks}, {@code PendingFluidTicks} (versions 3 and 5 on): scheduled updates, counted and
 *       not imported.</li>
 * </ul>
 *
 * <p><b>Reading.</b> Every region lands at its place in one clipboard, the regions' enclosing box; cells between
 * regions are absent (never pasted). Where regions overlap the one listed later wins, its block entity included.
 * The clipboard's anchor is the schematic origin. Block states are data-fixed from {@code MinecraftDataVersion} (a
 * file without one is read as 1.13.2, as a Sponge version 1 file), block entities and entities as in
 * {@link SchematicCodec}; unknown states become air and are reported. Version 1 files (Minecraft 1.12, before block
 * states were flattened) and versions after 6 are refused ({@code UNSUPPORTED}). A hanging entity's block is taken
 * from {@code TileX/Y/Z} only in files this codec wrote (they carry {@code Metadata.Sculptory}); Litematica keeps
 * the original world's there, so for its files the server derives the block from the position, as Litematica does.
 *
 * <p><b>Writing.</b> One region named after the schematic (or {@code Unnamed}) whose {@code Position} is
 * {@code -anchor}, so the anchor is the schematic origin; absent cells are written as air, as Litematica has no
 * absent cells. {@code Metadata.Sculptory} carries the library metadata ({@link AssetInfo}); Litematica ignores it.
 *
 * <p><b>Untrusted input.</b> As {@link SchematicCodec}: the regions' combined volume (not only their enclosing box)
 * must fit {@code maxVolume}, a region's palette may hold at most one entry more than its cells, and palette entries
 * are resolved only when a cell uses them.
 */
public final class LitematicCodec {
    /** The version written: Litematica's for Minecraft 1.20.5 to 1.21.4. */
    public static final int VERSION = 6;
    /** The sub-version written with {@link #VERSION}. */
    public static final int SUB_VERSION = 1;
    /** The oldest version read (1.13 on). */
    public static final int MIN_READ_VERSION = 2;
    /** Most regions a file may hold. */
    public static final int MAX_REGIONS = 1024;
    /** The region name written when the metadata has no name. */
    public static final String DEFAULT_REGION = "Unnamed";

    private LitematicCodec() {}

    // ================================================================== reading

    /** Reads a gzip-compressed (or uncompressed) Litematica schematic. Does not close {@code in}. */
    public static Schematic read(InputStream in, StateSpace states, SchematicCodec.Limits limits, DataFixHook hook)
            throws IOException {
        Objects.requireNonNull(in);
        Objects.requireNonNull(limits);
        return decode(NbtIo.readAuto(in, limits.nbt()).value(), states, limits, hook);
    }

    /** Decodes a Litematica document (the root compound). */
    public static Schematic decode(NbtCompound root, StateSpace states, SchematicCodec.Limits limits, DataFixHook hook)
            throws SchematicException {
        Objects.requireNonNull(root);
        Objects.requireNonNull(states);
        Objects.requireNonNull(limits);
        Objects.requireNonNull(hook);
        return new Reader(root, states, limits, hook).read();
    }

    /** One region as read: its name, its box's minimum corner (origin frame), its first corner and its extent. */
    private record Region(String name, NbtCompound tag, long minX, long minY, long minZ, int sx, int sy, int sz,
                          BlockPos position) {
        long volume() {
            return (long) sx * sy * sz;
        }

        boolean contains(long x, long y, long z) {
            return x >= minX && y >= minY && z >= minZ && x < minX + sx && y < minY + sy && z < minZ + sz;
        }

        boolean intersects(Region other) {
            return minX < other.minX + other.sx && other.minX < minX + sx && minY < other.minY + other.sy
                    && other.minY < minY + sy && minZ < other.minZ + other.sz && other.minZ < minZ + sz;
        }
    }

    private static final class Reader {
        private final NbtCompound root;
        private final StateSpace states;
        private final SchematicCodec.Limits limits;
        private final DataFixHook hook;
        private FileImport in;
        /** The enclosing box's minimum corner (origin frame). */
        private long originX, originY, originZ;
        private int tileEntries;

        Reader(NbtCompound root, StateSpace states, SchematicCodec.Limits limits, DataFixHook hook) {
            this.root = root;
            this.states = states;
            this.limits = limits;
            this.hook = hook;
        }

        Schematic read() throws SchematicException {
            NbtTag versionTag = root.get("Version");
            if (!(versionTag instanceof NbtTag.NbtInt v)) throw malformed("missing Version");
            int version = v.value();
            if (version < MIN_READ_VERSION || version > VERSION) {
                throw new SchematicException(Kind.UNSUPPORTED, "Litematica schematic version " + version
                        + " is not supported (versions " + MIN_READ_VERSION + " to " + VERSION + " are)");
            }
            NbtTag dataTag = root.get("MinecraftDataVersion");
            if (dataTag != null && !(dataTag instanceof NbtTag.NbtInt)) throw malformed("MinecraftDataVersion is not an int");
            int dataVersion = dataTag == null ? SchematicCodec.VERSION_1_DATA_VERSION : ((NbtTag.NbtInt) dataTag).value();
            NbtCompound meta = compound(root, "Metadata", false);
            SchematicMetadata metadata = metadata(meta);
            NbtCompound regionsTag = compound(root, "Regions", true);
            List<Region> regions = regions(regionsTag);

            long maxX = Long.MIN_VALUE, maxY = Long.MIN_VALUE, maxZ = Long.MIN_VALUE;
            originX = originY = originZ = Long.MAX_VALUE;
            for (Region region : regions) {
                originX = Math.min(originX, region.minX());
                originY = Math.min(originY, region.minY());
                originZ = Math.min(originZ, region.minZ());
                maxX = Math.max(maxX, region.minX() + region.sx() - 1);
                maxY = Math.max(maxY, region.minY() + region.sy() - 1);
                maxZ = Math.max(maxZ, region.minZ() + region.sz() - 1);
            }
            long width = maxX - originX + 1, height = maxY - originY + 1, length = maxZ - originZ + 1;
            if (width > SchematicCodec.MAX_SIDE || height > SchematicCodec.MAX_SIDE || length > SchematicCodec.MAX_SIDE) {
                throw new SchematicException(Kind.TOO_LARGE, "the regions span " + width + " × " + height + " × " + length
                        + ", more than " + SchematicCodec.MAX_SIDE + " per side");
            }
            // The box may be mostly empty, so it is capped even without a volume limit: what can be read can be written.
            long boxCap = Math.min(limits.maxVolume(), SchematicCodec.MAX_WRITE_VOLUME);
            if (width * height * length > boxCap) {
                throw new SchematicException(Kind.TOO_LARGE, "the regions' box holds " + (width * height * length)
                        + " cells > " + boxCap);
            }
            BlockPos offset = new BlockPos((int) originX, (int) originY, (int) originZ);
            if (!SchematicCodec.withinCoordinates(offset)) {
                throw malformed("region corner " + offset + " is beyond " + SchematicCodec.MAX_COORDINATE);
            }
            in = new FileImport(states, limits, hook, dataVersion,
                    new BlockPos((int) width, (int) height, (int) length), SchematicCodec.negate(offset),
                    metadata.name() == null ? "litematic" : "litematic:" + metadata.name());
            for (int i = 0; i < regions.size(); i++) blocks(regions.get(i));
            for (int i = 0; i < regions.size(); i++) tiles(regions, i);
            int entityEntries = 0;
            for (Region region : regions) {
                NbtList list = list(region, "Entities");
                if (list != null) entityEntries += list.size();
            }
            in.checkEntityCount(entityEntries);
            boolean ours = SchematicCodec.ours(meta) != null;
            for (Region region : regions) entities(region, ours);
            in.finishEntities();
            for (Region region : regions) {
                for (String key : List.of("PendingBlockTicks", "PendingFluidTicks")) {
                    NbtList ticks = list(region, key);
                    if (ticks != null) in.ticksSkipped(ticks.size());
                }
            }
            return new Schematic(SchematicFormat.LITEMATIC, version, dataVersion, offset, metadata, in.build(),
                    in.report(false));
        }

        /** The regions in file order, checked: corners in range, sizes non-zero and bounded, volume within limits. */
        private List<Region> regions(NbtCompound regionsTag) throws SchematicException {
            if (regionsTag.isEmpty()) throw malformed("no regions; an empty schematic has nothing to place");
            if (regionsTag.size() > MAX_REGIONS) {
                throw new SchematicException(Kind.TOO_LARGE, regionsTag.size() + " regions > " + MAX_REGIONS);
            }
            List<Region> regions = new ArrayList<>(regionsTag.size());
            long total = 0;
            for (Map.Entry<String, NbtTag> entry : regionsTag.entries().entrySet()) {
                String name = clip(entry.getKey());
                if (!(entry.getValue() instanceof NbtCompound tag)) throw malformed("region " + name + " is not a compound");
                BlockPos position = xyz(tag, "Position", "region " + name);
                BlockPos size = xyz(tag, "Size", "region " + name);
                if (!SchematicCodec.withinCoordinates(position)) {
                    throw malformed("region " + name + " Position " + position + " is beyond " + SchematicCodec.MAX_COORDINATE);
                }
                int[] extent = {size.x(), size.y(), size.z()};
                long[] corner = {position.x(), position.y(), position.z()};
                long[] min = new long[3];
                int[] sides = new int[3];
                for (int axis = 0; axis < 3; axis++) {
                    int s = extent[axis];
                    if (s == 0) throw malformed("region " + name + " has a size of 0; an empty region has nothing to place");
                    long abs = Math.abs((long) s);
                    if (abs > SchematicCodec.MAX_SIDE) {
                        throw new SchematicException(Kind.TOO_LARGE, "region " + name + " is " + abs + " long, more than "
                                + SchematicCodec.MAX_SIDE);
                    }
                    sides[axis] = (int) abs;
                    // Litematica: the far corner is Position + Size - sign(Size).
                    min[axis] = s > 0 ? corner[axis] : corner[axis] + s + 1;
                }
                Region region = new Region(name, tag, min[0], min[1], min[2], sides[0], sides[1], sides[2], position);
                total += region.volume();
                if (total > limits.maxVolume()) {
                    throw new SchematicException(Kind.TOO_LARGE, "the regions hold more than " + limits.maxVolume()
                            + " cells together");
                }
                regions.add(region);
            }
            return regions;
        }

        /** Reads a region's cells into the clipboard. */
        private void blocks(Region region) throws SchematicException {
            String what = "region " + region.name();
            NbtList paletteList = list(region, "BlockStatePalette");
            if (paletteList == null) throw malformed(what + " has no BlockStatePalette");
            long volume = region.volume();
            // Litematica always keeps air at index 0, so a palette may hold one entry more than the cells.
            int maxEntries = (int) Math.min(limits.maxPaletteSize(), volume + 1);
            if (paletteList.size() > maxEntries) {
                throw new SchematicException(Kind.TOO_LARGE, what + " has a palette of " + paletteList.size() + " > "
                        + maxEntries + " entries");
            }
            if (paletteList.isEmpty()) throw malformed(what + " has an empty palette");
            List<NbtCompound> entries = paletteList.compounds();
            if (entries == null) throw malformed(what + " BlockStatePalette holds something other than compounds");
            String[] specs = new String[entries.size()];
            String[] labels = new String[entries.size()];
            for (int i = 0; i < entries.size(); i++) {
                specs[i] = FileImport.entrySpec(entries.get(i), what + " palette entry " + i);
                labels[i] = specs[i] != null ? specs[i] : FileImport.entryLabel(entries.get(i));
            }
            FileImport.Palette palette = in.new Palette(specs, labels);
            int n = entries.size();
            int bits = Math.max(2, Integer.SIZE - Integer.numberOfLeadingZeros(n - 1));
            NbtTag statesTag = region.tag().get("BlockStates");
            if (!(statesTag instanceof NbtTag.NbtLongArray packed)) throw malformed(what + " has no BlockStates long array");
            long expected = (volume * bits + 63) / 64;
            // Litematica never checks the array's length; some writers pad it, so a longer array reads (the extra longs
            // are ignored) and only a shorter one is malformed.
            if (packed.length() < expected) {
                throw malformed(what + " BlockStates has " + packed.length() + " longs; " + region.sx() + " × "
                        + region.sy() + " × " + region.sz() + " cells of " + bits + " bits need " + expected);
            }
            int baseX = (int) (region.minX() - originX), baseY = (int) (region.minY() - originY);
            int baseZ = (int) (region.minZ() - originZ);
            long mask = (1L << bits) - 1;
            long index = 0;
            for (int y = 0; y < region.sy(); y++) {
                for (int z = 0; z < region.sz(); z++) {
                    for (int x = 0; x < region.sx(); x++, index++) {
                        long bit = index * bits;
                        int word = (int) (bit >>> 6), shift = (int) (bit & 63);
                        long value = packed.get(word) >>> shift;
                        if (shift + bits > 64) value |= packed.get(word + 1) << (64 - shift);
                        int entry = (int) (value & mask);
                        if (entry >= n) {
                            throw malformed(what + " BlockStates holds palette index " + entry + " at " + x + "," + y
                                    + "," + z + ", but the palette has " + n + " entries");
                        }
                        in.builder.set(baseX + x, baseY + y, baseZ + z, palette.handle(entry));
                    }
                }
            }
            palette.finish();
        }

        /** Attaches region {@code i}'s block entities, except on cells a later region covers. */
        private void tiles(List<Region> regions, int i) throws SchematicException {
            Region region = regions.get(i);
            NbtList list = list(region, "TileEntities");
            if (list == null || list.isEmpty()) return;
            tileEntries += list.size();
            in.checkBlockEntityCount(tileEntries);
            List<NbtCompound> entries = list.compounds();
            if (entries == null) throw malformed("region " + region.name() + " TileEntities holds something other than compounds");
            List<Region> later = new ArrayList<>();
            for (int j = i + 1; j < regions.size(); j++) {
                if (regions.get(j).intersects(region)) later.add(regions.get(j));
            }
            for (NbtCompound entry : entries) {
                Integer x = entry.getInt("x"), y = entry.getInt("y"), z = entry.getInt("z");
                if (x == null || y == null || z == null) {
                    in.tileSkipped("no x/y/z in region " + region.name());
                    continue;
                }
                long wx = region.minX() + x, wy = region.minY() + y, wz = region.minZ() + z;
                if (x < 0 || y < 0 || z < 0 || x >= region.sx() || y >= region.sy() || z >= region.sz()) {
                    in.tileSkipped("x/y/z " + x + "," + y + "," + z + " outside region " + region.name());
                    continue;
                }
                Region over = null;
                for (Region other : later) {
                    if (other.contains(wx, wy, wz)) over = other;
                }
                if (over != null) {
                    in.tileSkipped(entry.getString("id") + " at " + x + "," + y + "," + z + " of region " + region.name()
                            + " is covered by region " + over.name());
                    continue;
                }
                in.tile((int) (wx - originX), (int) (wy - originY), (int) (wz - originZ), entry.getString("id"), entry);
            }
        }

        /**
         * Reads a region's entities: {@code Pos} is relative to the region's {@code Position}. A hanging entity's
         * {@code TileX/Y/Z} are used only in files this codec wrote ({@code ours}), where they are relative to the
         * region's {@code Position} too; Litematica keeps the original world's, which cannot be placed.
         */
        private void entities(Region region, boolean ours) throws SchematicException {
            NbtList list = list(region, "Entities");
            if (list == null || list.isEmpty()) return;
            List<NbtCompound> entries = list.compounds();
            if (entries == null) throw malformed("region " + region.name() + " Entities holds something other than compounds");
            BlockPos p = region.position();
            long dx = p.x() - originX, dy = p.y() - originY, dz = p.z() - originZ;
            FileImport.Attachment attachment = (compound, at) -> {
                if (!ours) return null;
                Integer tx = compound.getInt("TileX"), ty = compound.getInt("TileY"), tz = compound.getInt("TileZ");
                if (tx == null || ty == null || tz == null) return null;
                return FileImport.near(tx + dx, ty + dy, tz + dz, at);
            };
            for (NbtCompound entry : entries) {
                double[] pos = EntityNbt.position(entry);
                if (pos != null) {
                    pos[0] += dx;
                    pos[1] += dy;
                    pos[2] += dz;
                }
                in.entity(entry.getString("id"), entry, pos, attachment);
            }
        }

        private NbtList list(Region region, String key) throws SchematicException {
            NbtTag tag = region.tag().get(key);
            if (tag == null) return null;
            if (!(tag instanceof NbtList list)) throw malformed("region " + region.name() + " " + key + " is not a list");
            return list;
        }
    }

    private static SchematicMetadata metadata(NbtCompound meta) throws SchematicException {
        if (meta == null) return SchematicMetadata.EMPTY;
        NbtTag.NbtLong created = meta.get("TimeCreated", NbtTag.NbtLong.class);
        return new SchematicMetadata(meta.getString("Name"), meta.getString("Author"),
                created == null ? null : created.value(), List.of(),
                SchematicCodec.assetInfo(SchematicCodec.ours(meta)), meta.getString("Description"));
    }

    /** A {@code {x, y, z}} compound of ints. */
    private static BlockPos xyz(NbtCompound tag, String key, String what) throws SchematicException {
        NbtTag value = tag.get(key);
        if (!(value instanceof NbtCompound xyz)) throw malformed(what + " has no " + key);
        Integer x = xyz.getInt("x"), y = xyz.getInt("y"), z = xyz.getInt("z");
        if (x == null || y == null || z == null) throw malformed(what + " " + key + " is not {x, y, z}");
        return new BlockPos(x, y, z);
    }

    private static NbtCompound compound(NbtCompound tag, String key, boolean required) throws SchematicException {
        NbtTag value = tag.get(key);
        if (value == null) {
            if (required) throw malformed("missing " + key);
            return null;
        }
        if (!(value instanceof NbtCompound compound)) throw malformed(key + " is not a compound");
        return compound;
    }

    private static String clip(String name) {
        return name.length() > 64 ? name.substring(0, 64) + "…" : name;
    }

    private static SchematicException malformed(String message) {
        return new SchematicException(Kind.MALFORMED, message);
    }

    // ================================================================== writing

    /** Writes a gzip-compressed Litematica version 6 schematic. Does not close {@code out}. */
    public static void write(OutputStream out, Clipboard clipboard, SchematicMetadata metadata, int dataVersion)
            throws IOException {
        NbtIo.writeGzip(out, "", encode(clipboard, metadata, dataVersion));
    }

    /**
     * Encodes a Litematica version 6 document with one region (see the class comment). The palette starts with air and
     * then follows first use over the cell order, so equal clipboards encode to equal bytes; timestamps come from
     * {@link SchematicMetadata#dateMillis()} (0 without one).
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
        long volume = clipboard.volume();
        if (volume > SchematicCodec.MAX_WRITE_VOLUME) {
            throw new SchematicException(Kind.TOO_LARGE, volume + " cells > " + SchematicCodec.MAX_WRITE_VOLUME);
        }
        if (!SchematicCodec.withinCoordinates(clipboard.anchor())) {
            throw new SchematicException(Kind.TOO_LARGE, "anchor beyond " + SchematicCodec.MAX_COORDINATE
                    + " cannot be stored");
        }
        StateSpace states = clipboard.states();
        int air = states.air();
        // Pass 1: the palette (air first), the block entities and the count of blocks.
        Int2IntOpenHashMap paletteIndex = new Int2IntOpenHashMap();
        paletteIndex.defaultReturnValue(-1);
        List<NbtTag> palette = new ArrayList<>();
        paletteIndex.put(air, 0);
        palette.add(paletteEntry(states, air));
        long[] blocks = {0};
        List<NbtTag> tiles = new ArrayList<>();
        SchematicException[] failure = {null};
        clipboard.forEachCell((x, y, z, state, tile) -> {
            int h = state < 0 ? air : state;
            if (paletteIndex.get(h) < 0) {
                paletteIndex.put(h, palette.size());
                palette.add(paletteEntry(states, h));
            }
            if (!StateFlags.has(states.flags(h), StateFlags.AIR)) blocks[0]++;
            if (tile != null && failure[0] == null) {
                try {
                    tiles.add(tileEntry(x, y, z, tile));
                } catch (SchematicException e) {
                    failure[0] = e;
                }
            }
        });
        if (failure[0] != null) throw failure[0];
        // Pass 2: pack each cell's entry in cell order, running over long boundaries as LitematicaBitArray does.
        int bits = Math.max(2, Integer.SIZE - Integer.numberOfLeadingZeros(palette.size() - 1));
        long[] packed = new long[(int) ((volume * bits + 63) / 64)];
        long[] next = {0};
        clipboard.forEachCell((x, y, z, state, tile) -> {
            long value = paletteIndex.get(state < 0 ? air : state);
            long bit = next[0]++ * bits;
            int word = (int) (bit >>> 6), shift = (int) (bit & 63);
            packed[word] |= value << shift;
            if (shift + bits > 64) packed[word + 1] |= value >>> (64 - shift);
        });
        List<NbtTag> entities = new ArrayList<>(clipboard.entityCount());
        for (EntitySnapshot entity : clipboard.entities()) entities.add(entityEntry(entity));

        BlockPos position = SchematicCodec.negate(clipboard.anchor());
        String name = metadata.name() == null || metadata.name().isBlank() ? DEFAULT_REGION : metadata.name();
        NbtCompound region = NbtCompound.builder()
                .put("Position", xyz(position))
                .put("Size", xyz(size))
                .put("BlockStatePalette", NbtList.of(NbtTag.COMPOUND, palette))
                .putLongArray("BlockStates", packed)
                .put("TileEntities", NbtList.of(NbtTag.COMPOUND, tiles))
                .put("Entities", NbtList.of(NbtTag.COMPOUND, entities))
                .put("PendingBlockTicks", NbtList.of(NbtTag.COMPOUND, List.of()))
                .put("PendingFluidTicks", NbtList.of(NbtTag.COMPOUND, List.of()))
                .build();
        long time = metadata.dateMillis() == null ? 0 : metadata.dateMillis();
        NbtCompound.Builder meta = NbtCompound.builder()
                .putString("Name", name)
                .putString("Author", metadata.author() == null ? "" : metadata.author())
                .putString("Description", metadata.description() == null ? "" : metadata.description())
                .putInt("RegionCount", 1)
                .putInt("TotalVolume", (int) volume)
                .putInt("TotalBlocks", (int) blocks[0])
                .putLong("TimeCreated", time)
                .putLong("TimeModified", time)
                .put("EnclosingSize", xyz(size));
        if (metadata.sculptory() != null) meta.put(SchematicCodec.META_KEY, SchematicCodec.assetTag(metadata.sculptory()));
        return NbtCompound.builder()
                .putInt("MinecraftDataVersion", dataVersion)
                .putInt("Version", VERSION)
                .putInt("SubVersion", SUB_VERSION)
                .put("Metadata", meta.build())
                .put("Regions", NbtCompound.builder().put(name, region).build())
                .build();
    }

    /** A {@code {Name, Properties}} palette entry, as the game's {@code NbtHelper.fromBlockState} writes it. */
    public static NbtCompound paletteEntry(StateSpace states, int handle) {
        BlockDescriptor d = states.describe(handle);
        NbtCompound.Builder entry = NbtCompound.builder().putString("Name", d.block().value());
        if (!d.properties().isEmpty()) {
            NbtCompound.Builder properties = NbtCompound.builder();
            d.properties().forEach(properties::putString);
            entry.put("Properties", properties.build());
        }
        return entry.build();
    }

    /** A block entity: its NBT ({@code id} first) with {@code x}/{@code y}/{@code z} relative to the box. */
    private static NbtCompound tileEntry(int x, int y, int z, BlockEntityData tile) throws SchematicException {
        return SchematicCodec.tileData(x, y, z, tile).putInt("x", x).putInt("y", y).putInt("z", z).build();
    }

    /**
     * An entity: {@code id} first, its data ({@link SchematicCodec#entityData}: a hanging entity's {@code TileX/Y/Z}
     * relative, which Litematica ignores) and {@code Pos} relative to the region's {@code Position} (the box's minimum
     * corner, as written); no {@code UUID}.
     */
    private static NbtCompound entityEntry(EntitySnapshot entity) throws SchematicException {
        NbtCompound data = SchematicCodec.entityData(entity).build();
        NbtCompound.Builder entry = NbtCompound.builder().putString("id", entity.typeId());
        data.entries().forEach(entry::put);
        return entry.put("Pos", EntityNbt.doubles(entity.x(), entity.y(), entity.z())).build();
    }

    private static NbtCompound xyz(BlockPos p) {
        return NbtCompound.builder().putInt("x", p.x()).putInt("y", p.y()).putInt("z", p.z()).build();
    }
}
