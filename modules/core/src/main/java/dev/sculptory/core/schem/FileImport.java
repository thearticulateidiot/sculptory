package dev.sculptory.core.schem;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.BlockPos;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.buffer.NbtBytes;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.entity.EntityNbt;
import dev.sculptory.core.entity.EntitySnapshot;
import dev.sculptory.core.nbt.BlockEntityNbt;
import dev.sculptory.core.nbt.NbtCompound;
import dev.sculptory.core.nbt.NbtIo;
import dev.sculptory.core.nbt.NbtTag;
import dev.sculptory.core.schem.SchematicException.Kind;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.state.StateSpace;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * What every schematic reader shares ({@link SchematicCodec}, {@link LitematicCodec}, {@link StructureCodec}): the
 * clipboard being built, palette entries resolved (and data-fixed) on first use with unknown entries reported and read
 * as air, block entities attached to the cells that can hold them, entities read as untrusted snapshots, the byte and
 * count caps of {@link SchematicCodec.Limits}, and the warnings of the {@link SchematicReport}. Nothing is dropped
 * silently: every loss is counted or warned about.
 *
 * <p>Work is bounded by the file's cells, not its declared sizes: a palette entry is resolved only when a cell uses it,
 * and a block entity is data-fixed only when its cell holds a block-entity state and no earlier entry claimed it.
 */
final class FileImport {
    /** A palette entry not resolved yet. */
    private static final int UNRESOLVED = -2;
    /** How far (blocks, per axis) a hanging entity's block may be from its position before it is ignored. */
    static final int MAX_ATTACHMENT_DISTANCE = 3;

    final StateSpace states;
    final SchematicCodec.Limits limits;
    final DataFixHook hook;
    final int dataVersion;
    final boolean fix;
    final int width, height, length;
    final Clipboard.Builder builder;
    private final List<String> warnings = new ArrayList<>();
    private int hiddenWarnings;
    private final TreeMap<String, Long> unknown = new TreeMap<>();
    private final BitSet claimed = new BitSet();
    private long tileBytes;
    /** Entities read so far, passengers included (toward {@code maxEntities}). */
    private long entityTotal;
    private int tilesAttached, tilesSkipped, entitiesRead, entitiesSkipped, ridersDropped, ticksSkipped;
    private String firstEntityProblem;

    /** Where a hanging entity's block is, given its (data-fixed) compound and position relative to the box. */
    interface Attachment {
        BlockPos find(NbtCompound entity, double[] pos);
    }

    FileImport(StateSpace states, SchematicCodec.Limits limits, DataFixHook hook, int dataVersion, BlockPos size,
               BlockPos anchor, String source) {
        this.states = Objects.requireNonNull(states);
        this.limits = Objects.requireNonNull(limits);
        this.hook = Objects.requireNonNull(hook);
        this.dataVersion = dataVersion;
        this.fix = dataVersion < hook.targetDataVersion();
        this.width = size.x();
        this.height = size.y();
        this.length = size.z();
        this.builder = Clipboard.builder(states, size).anchor(anchor).source(source);
    }

    // ------------------------------------------------------------------ palettes

    /**
     * A palette read lazily: entry {@code i} is resolved (data-fixed, then parsed) the first time a cell uses it. An
     * entry whose text is {@code null} (an entry that could not be turned into state text at all) counts as unknown
     * under its {@code label}.
     */
    final class Palette {
        private final String[] specs;
        private final String[] labels;
        private final int[] handles;
        private final long[] unknownCells;

        Palette(String[] specs, String[] labels) {
            this.specs = specs;
            this.labels = labels;
            this.handles = new int[specs.length];
            this.unknownCells = new long[specs.length];
            Arrays.fill(handles, UNRESOLVED);
        }

        int size() {
            return specs.length;
        }

        /** The handle of entry {@code index} (air when unknown, which is counted), resolving it on first use. */
        int handle(int index) {
            int handle = handles[index];
            if (handle == UNRESOLVED) {
                handle = specs[index] == null ? -1 : resolve(specs[index]);
                handles[index] = handle;
            }
            if (handle < 0) {
                unknownCells[index]++;
                return states.air();
            }
            return handle;
        }

        /** Reports the unknown entries cells used. Call once, after the last cell. */
        void finish() {
            for (int index = 0; index < specs.length; index++) {
                if (unknownCells[index] > 0) {
                    unknown.merge(labels[index], unknownCells[index], Long::sum);
                    warn("unknown state " + labels[index] + " (" + unknownCells[index] + " cells) became air");
                }
            }
        }
    }

    /** The handle of state text after the data fix, or -1 if unknown (or the fix failed). */
    int resolve(String spec) {
        String fixed = spec;
        if (fix) {
            try {
                fixed = Objects.requireNonNull(hook.fixBlockState(spec, dataVersion));
            } catch (RuntimeException failed) {
                warn("data fix failed for state " + spec + ": " + failed);
                return -1;
            }
        }
        return states.parse(fixed);
    }

    /**
     * The state text of a vanilla-style palette entry {@code {Name: "ns:id", Properties: {key: "value"}}}
     * (Litematica and structure files), or {@code null} when it names no valid state text (unknown then, under
     * {@link #entryLabel}).
     *
     * @throws SchematicException ({@code MALFORMED}) for an entry without a string {@code Name} or with properties that
     *     are not strings
     */
    static String entrySpec(NbtCompound entry, String what) throws SchematicException {
        String name = entry.getString("Name");
        if (name == null) throw malformed(what + " has no Name");
        NbtTag props = entry.get("Properties");
        Map<String, String> properties = new LinkedHashMap<>();
        if (props != null) {
            if (!(props instanceof NbtCompound compound)) throw malformed(what + ".Properties is not a compound");
            if (compound.size() > BlockDescriptor.MAX_PROPERTIES) {
                throw malformed(what + " has " + compound.size() + " properties");
            }
            for (Map.Entry<String, NbtTag> property : compound.entries().entrySet()) {
                if (!(property.getValue() instanceof NbtTag.NbtString value)) {
                    throw malformed(what + " property " + property.getKey() + " is not a string");
                }
                properties.put(property.getKey(), value.value());
            }
        }
        try {
            return BlockDescriptor.of(new NamespacedId(name.indexOf(':') < 0 ? "minecraft:" + name : name), properties)
                    .format();
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    /** How a palette entry is named in the report: its state text, or its raw name and properties. */
    static String entryLabel(NbtCompound entry) {
        String name = entry.getString("Name");
        String label = name == null ? "?" : name;
        NbtCompound props = entry.getCompound("Properties");
        if (props != null && !props.isEmpty()) label += props;
        return label.length() > 256 ? label.substring(0, 256) + "…" : label;
    }

    // ------------------------------------------------------------------ block entities

    /** Checks the number of block-entity entries before any is read. */
    void checkBlockEntityCount(int entries) throws SchematicException {
        if (entries > limits.maxBlockEntities()) {
            throw new SchematicException(Kind.TOO_LARGE, entries + " block entities > " + limits.maxBlockEntities());
        }
    }

    /**
     * Attaches the block entity {@code id} with {@code data} (its fields; {@code id}, {@code x}, {@code y}, {@code z}
     * are ignored) to local cell (x, y, z). The cheap checks come first, so the data fixer runs at most once per cell
     * that holds a block-entity state. A refusal is counted and warned about.
     *
     * @throws SchematicException ({@code TOO_LARGE}) over the per-tile or total tile byte caps
     */
    void tile(int x, int y, int z, String id, NbtCompound data) throws SchematicException {
        String problem = attach(x, y, z, id, data);
        if (problem == null) {
            tilesAttached++;
        } else {
            tileSkipped(problem);
        }
    }

    /** Counts a block entity that could not be read at all (no position, say). */
    void tileSkipped(String problem) {
        tilesSkipped++;
        warn("block entity skipped: " + problem);
    }

    private String attach(int x, int y, int z, String id, NbtCompound data) throws SchematicException {
        if (x < 0 || y < 0 || z < 0 || x >= width || y >= height || z >= length) {
            return "Pos " + x + "," + y + "," + z + " outside the schematic";
        }
        String at = " at " + x + "," + y + "," + z;
        if (id == null || id.isEmpty()) return "no Id" + at;
        int state = builder.get(x, y, z);
        if (state < 0) return id + at + " on a cell the file leaves empty";
        if (!StateFlags.has(states.flags(state), StateFlags.HAS_BLOCK_ENTITY)) {
            return id + at + " on " + states.format(state) + ", which has no block entity";
        }
        int index = (y * length + z) * width + x;
        if (claimed.get(index)) return "second block entity" + at;
        claimed.set(index);

        NbtCompound compound = BlockEntityNbt.normalize(id, data);
        if (fix) {
            try {
                compound = Objects.requireNonNull(hook.fixBlockEntity(compound, dataVersion));
            } catch (RuntimeException failed) {
                return "data fix failed for " + id + at + ": " + failed;
            }
            String fixedId = compound.getString("id");
            if (fixedId != null && !fixedId.isEmpty()) id = fixedId;
        }
        String typeId = namespaced(id);
        if (typeId == null) return "invalid block entity id " + id + at;
        byte[] bytes;
        try {
            bytes = NbtIo.toBytes(BlockEntityNbt.normalize(typeId, compound));
        } catch (IllegalArgumentException unencodable) {
            return typeId + at + ": " + unencodable.getMessage();
        }
        if (bytes.length > limits.maxTileBytes()) {
            throw new SchematicException(Kind.TOO_LARGE,
                    "block entity" + at + " has " + bytes.length + " bytes > " + limits.maxTileBytes());
        }
        tileBytes += bytes.length;
        if (tileBytes > limits.maxTotalTileBytes()) {
            throw new SchematicException(Kind.TOO_LARGE, "block entities exceed " + limits.maxTotalTileBytes() + " bytes");
        }
        builder.setTile(x, y, z, new NbtBytes(typeId, bytes));
        return null;
    }

    // ------------------------------------------------------------------ entities

    /** Checks the number of entity entries before any is read. */
    void checkEntityCount(int entries) throws SchematicException {
        if (entries > limits.maxEntities()) {
            throw new SchematicException(Kind.TOO_LARGE, entries + " entities > " + limits.maxEntities());
        }
    }

    /**
     * Reads one entity: {@code id} and {@code data} (its fields; an {@code id}/{@code Id} in it is ignored) at
     * {@code pos}, relative to the box's minimum corner. The data is upgraded with {@link DataFixHook#fixEntity}; its
     * {@code Rotation} gives the heading, and {@code attachment} a hanging entity's block. At most
     * {@link EntityNbt#MAX_RIDERS} passengers are kept; the entity and its passengers count toward {@code maxEntities}.
     * A refusal is counted (the first reason is warned about in {@link #finishEntities}).
     *
     * @throws SchematicException ({@code TOO_LARGE}) over the entity count or the tile byte caps, which entities share
     *     with block entities
     */
    void entity(String id, NbtCompound data, double[] pos, Attachment attachment) throws SchematicException {
        String problem = readEntity(id, data, pos, attachment);
        if (problem == null) {
            entitiesRead++;
        } else {
            entitySkipped(problem);
        }
    }

    /** Counts an entity that could not be read at all. */
    void entitySkipped(String problem) {
        entitiesSkipped++;
        if (firstEntityProblem == null) firstEntityProblem = problem;
    }

    private String readEntity(String id, NbtCompound data, double[] pos, Attachment attachment)
            throws SchematicException {
        if (pos == null) return "no Pos";
        if (id == null || id.isEmpty()) return "no Id";
        NbtCompound.Builder whole = NbtCompound.builder().putString("id", id);
        data.entries().forEach((key, value) -> {
            if (!key.equals("id") && !key.equals("Id")) whole.put(key, value);
        });
        NbtCompound compound = whole.build();
        if (fix) {
            try {
                compound = Objects.requireNonNull(hook.fixEntity(compound, dataVersion));
            } catch (RuntimeException failed) {
                return "data fix failed for " + id + ": " + failed;
            }
            String fixedId = compound.getString("id");
            if (fixedId != null && !fixedId.isEmpty()) id = fixedId;
        }
        String typeId = namespaced(id);
        if (typeId == null) return "invalid entity id " + id;
        BlockPos attached = attachment.find(compound, pos);
        BlockPos cell = attached != null ? attached : new BlockPos((int) Math.floor(pos[0]), (int) Math.floor(pos[1]),
                (int) Math.floor(pos[2]));
        if (cell.x() < 0 || cell.y() < 0 || cell.z() < 0 || cell.x() >= width || cell.y() >= height
                || cell.z() >= length) {
            return typeId + " outside the schematic";
        }
        float[] rotation = EntityNbt.rotation(compound);
        int[] dropped = {0};
        NbtCompound kept = EntityNbt.snapshotData(compound, dropped);
        byte[] bytes;
        try {
            bytes = NbtIo.toBytes(kept);
        } catch (IllegalArgumentException unencodable) {
            return typeId + ": " + unencodable.getMessage();
        }
        ridersDropped += dropped[0];
        entityTotal += 1 + EntityNbt.riderCount(kept);
        if (entityTotal > limits.maxEntities()) {
            throw new SchematicException(Kind.TOO_LARGE, "more than " + limits.maxEntities()
                    + " entities (passengers included)");
        }
        if (bytes.length > Math.min(limits.maxTileBytes(), EntitySnapshot.MAX_NBT_BYTES)) {
            throw new SchematicException(Kind.TOO_LARGE, "entity " + typeId + " has " + bytes.length + " bytes");
        }
        tileBytes += bytes.length;
        if (tileBytes > limits.maxTotalTileBytes()) {
            throw new SchematicException(Kind.TOO_LARGE, "block entities and entities exceed "
                    + limits.maxTotalTileBytes() + " bytes");
        }
        builder.addEntity(new EntitySnapshot(typeId, pos[0], pos[1], pos[2],
                rotation == null ? 0 : rotation[0], rotation == null ? 0 : rotation[1], attached, bytes, false));
        return null;
    }

    /** Warns about the entities skipped (with the first reason) and the passengers left out. Call once. */
    void finishEntities() {
        if (entitiesSkipped > 0) warn(entitiesSkipped + " entities skipped (first: " + firstEntityProblem + ")");
        if (ridersDropped > 0) {
            entitiesSkipped += ridersDropped;
            warn(ridersDropped + " passengers left out (an entity keeps at most " + EntityNbt.MAX_RIDERS + ")");
        }
    }

    /** Counts entities a file holds that are skipped whole (a Sponge version 1 file's), with one warning. */
    void entitiesSkipped(int count, String why) {
        entitiesSkipped += count;
        warn(count + " entities skipped (" + why + ")");
    }

    // ------------------------------------------------------------------ the rest

    /** Counts scheduled block and fluid updates the file holds, which are not imported. */
    void ticksSkipped(int count) {
        ticksSkipped += count;
    }

    void warn(String warning) {
        if (warnings.size() < SchematicReport.MAX_WARNINGS) {
            warnings.add(warning);
        } else {
            hiddenWarnings++;
        }
    }

    /**
     * The report, after the palettes, block entities and entities are finished: {@code biomes} whether biome data was
     * skipped. Adds the warnings for a newer data version and for skipped scheduled updates.
     */
    SchematicReport report(boolean biomes) {
        boolean newer = dataVersion > hook.targetDataVersion();
        if (ticksSkipped > 0) warn(ticksSkipped + " scheduled block and fluid updates skipped (not imported)");
        if (newer) warn("DataVersion " + dataVersion + " is newer than " + hook.targetDataVersion());
        List<String> all = new ArrayList<>(warnings);
        if (hiddenWarnings > 0) all.add("... " + hiddenWarnings + " more");
        return new SchematicReport(unknown, tilesAttached, tilesSkipped, entitiesRead, entitiesSkipped, biomes, newer,
                all, ticksSkipped);
    }

    Clipboard build() {
        return builder.build();
    }

    /** A valid namespaced id ({@code minecraft:} added when there is no namespace), or {@code null}. */
    static String namespaced(String id) {
        String full = id.indexOf(':') < 0 ? "minecraft:" + id : id;
        try {
            return new NamespacedId(full).value();
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    /** A hanging entity's block given as three ints, or {@code null} when it lies more than a few blocks away. */
    static BlockPos near(long x, long y, long z, double[] pos) {
        if (Math.abs(x - Math.floor(pos[0])) > MAX_ATTACHMENT_DISTANCE
                || Math.abs(y - Math.floor(pos[1])) > MAX_ATTACHMENT_DISTANCE
                || Math.abs(z - Math.floor(pos[2])) > MAX_ATTACHMENT_DISTANCE) {
            return null;
        }
        return new BlockPos((int) x, (int) y, (int) z);
    }

    static SchematicException malformed(String message) {
        return new SchematicException(Kind.MALFORMED, message);
    }
}
