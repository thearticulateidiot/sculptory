package dev.sculptory.core.clipboard;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.Sha256;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.edit.CellPredicate;
import dev.sculptory.core.edit.SourceBlocks;
import dev.sculptory.core.entity.EntityNbt;
import dev.sculptory.core.entity.EntitySnapshot;
import dev.sculptory.core.nbt.BlockEntityNbt;
import dev.sculptory.core.nbt.NbtCompound;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.Regions;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.world.WorldReader;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable block content in local coordinates {@code [0, size)}, with block-entity tiles, an anchor (the local
 * cell that lands on the paste origin; it may lie outside the box) and a content hash. It is what a copy
 * produces, what a schematic loads into, and what a paste consumes through {@link #toSource()}.
 *
 * <p>Cells may be absent (a masked copy): absent cells are never pasted. Handles belong to {@link #states()}.
 *
 * <p><b>Content hash.</b> SHA-256 over a canonical encoding that does not depend on handles or storage: the
 * size, the anchor, then every non-empty 16³ section in ascending key order with its present cells (index and a
 * reference into a palette of {@link StateSpace#format} strings in first-use order) and its tiles (index, type,
 * then a flag and the {@link BlockEntityNbt#tryCanonicalBytes key-sorted NBT}, or the raw bytes when they do not
 * decode), then the palette strings; strings are modified UTF-8. A clipboard holding entities then adds them, in
 * {@link EntitySnapshot#CANONICAL_ORDER} (type, position, angles, attachment and key-sorted NBT); a clipboard without
 * entities hashes exactly as before entities existed, so library hashes stay valid. The {@link #source()} description
 * and entity trust are not part of it. Equal content therefore has an equal hash across servers and restarts.
 *
 * <p><b>Entities</b> ({@link #entities()}) are {@link EntitySnapshot}s in local coordinates, kept in canonical order
 * with key-sorted NBT; they travel with the clipboard through pastes, schematics and the library.
 */
public final class Clipboard {
    // The hash domain keeps the pre-rename name on purpose: library and history hashes must stay valid.
    private static final byte[] HASH_MAGIC = "BuilderSuite clipboard v2\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ENTITY_MAGIC = "entities\0".getBytes(StandardCharsets.US_ASCII);

    private final StateSpace states;
    /** Local coordinates, compacted. Never exposed or mutated after construction. */
    private final BlockBuffer blocks;
    private final BlockPos size;
    private final BlockPos anchor;
    private final long cellCount;
    private final String source;
    /** In {@link EntitySnapshot#CANONICAL_ORDER}, with key-sorted NBT; unmodifiable. */
    private final List<EntitySnapshot> entities;
    /** {@link #entityTotal()}, counted when the entities were made canonical (off the server thread). */
    private final long entityTotal;
    /** {@link #untrustedEntityTypes()}, found at the same time. */
    private final Set<String> untrustedEntityTypes;
    private final Sha256 contentHash;

    private Clipboard(StateSpace states, BlockBuffer blocks, BlockPos size, BlockPos anchor, String source,
                      Entities entities, Sha256 contentHash) {
        this.states = states;
        this.blocks = blocks;
        this.size = size;
        this.anchor = anchor;
        this.cellCount = blocks.cellCount();
        this.source = source;
        this.entities = entities.list();
        this.entityTotal = entities.total();
        this.untrustedEntityTypes = entities.untrustedTypes();
        this.contentHash = contentHash != null ? contentHash : hash(states, blocks, size, anchor, this.entities);
    }

    /** A builder for a clipboard of {@code size} cells (each side at least 1) using handles of {@code states}. */
    public static Builder builder(StateSpace states, BlockPos size) {
        return new Builder(states, size);
    }

    /**
     * Copies {@code box} out of {@code world}: local (0, 0, 0) is {@code box.min()} and the anchor is
     * {@code origin - box.min()}. Cells {@code mask} rejects (tested against the world state) are left absent;
     * {@code null} keeps every cell. The caller makes sure the chunks are loaded and bounds the volume.
     */
    public static Clipboard copyOf(WorldReader world, Box box, BlockPos origin, CellPredicate mask, String source) {
        return copyOf(world, new Region.Cuboid(box), box, origin, mask, source);
    }

    /**
     * Copies the cells of {@code region} inside {@code box} (the clipboard's box, e.g. the region's bounds cut to the
     * build height) out of {@code world}: local (0, 0, 0) is {@code box.min()}, the anchor is {@code origin - box.min()},
     * and every cell outside the region, or rejected by {@code mask} ({@code null} keeps every cell), is absent. Only
     * the sections holding region cells are read. The caller makes sure the chunks are loaded and bounds the volume.
     */
    public static Clipboard copyOf(WorldReader world, Region region, Box box, BlockPos origin, CellPredicate mask,
                                   String source) {
        Objects.requireNonNull(world);
        Objects.requireNonNull(region);
        Objects.requireNonNull(box);
        Objects.requireNonNull(origin);
        Builder builder = builder(world.states(), new BlockPos(box.sizeX(), box.sizeY(), box.sizeZ()))
                .anchor(new BlockPos(Math.subtractExact(origin.x(), box.min().x()),
                        Math.subtractExact(origin.y(), box.min().y()),
                        Math.subtractExact(origin.z(), box.min().z())))
                .source(source);
        BlockPos min = box.min(), max = box.max();
        SectionBuffer section = new SectionBuffer();
        int[] rows = new int[Regions.ROWS];
        for (long key : Regions.sectionKeysBetween(region, min.y(), max.y())) {
            if (Regions.rows(region, key, min.y(), max.y(), rows) == 0) continue;
            int ox = BlockBuffer.keyX(key) << 4, oy = BlockBuffer.keyY(key) << 4, oz = BlockBuffer.keyZ(key) << 4;
            world.copySection(BlockBuffer.keyX(key), BlockBuffer.keyY(key), BlockBuffer.keyZ(key), section);
            // Cells of the box only: a region may reach past it in x and z only if the caller's box is narrower.
            int xMask = xMask(min.x() - ox, max.x() - ox);
            for (int r = 0; r < Regions.ROWS; r++) {
                int y = oy + (r >>> 4), z = oz + (r & 15);
                if (z < min.z() || z > max.z()) continue;
                for (int bits = rows[r] & xMask; bits != 0; bits &= bits - 1) {
                    int x = ox + Integer.numberOfTrailingZeros(bits);
                    int i = SectionBuffer.index(x - ox, y - oy, z - oz);
                    int state = section.get(i);
                    if (state < 0 || (mask != null && !mask.test(x, y, z, state))) continue;
                    int lx = x - min.x(), ly = y - min.y(), lz = z - min.z();
                    builder.set(lx, ly, lz, state);
                    BlockEntityData tile = section.tile(i);
                    if (tile != null) builder.setTile(lx, ly, lz, tile);
                }
            }
        }
        return builder.build();
    }

    /** The 16-bit mask of local x from {@code from} to {@code to}, each clamped into 0..15 (empty when disjoint). */
    private static int xMask(int from, int to) {
        int lo = Math.max(0, from), hi = Math.min(15, to);
        return lo > hi ? 0 : ((1 << (hi - lo + 1)) - 1) << lo;
    }

    public StateSpace states() {
        return states;
    }

    /** The box size (x, y, z), each at least 1. */
    public BlockPos size() {
        return size;
    }

    /** The local cell that lands on the paste origin (may be outside the box). */
    public BlockPos anchor() {
        return anchor;
    }

    /** Present cells. */
    public long cellCount() {
        return cellCount;
    }

    public long volume() {
        return (long) size.x() * size.y() * size.z();
    }

    public Sha256 contentHash() {
        return contentHash;
    }

    /** Free-form description of where the content came from (not hashed). */
    public String source() {
        return source;
    }

    public boolean contains(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < size.x() && y < size.y() && z < size.z();
    }

    /** The state at a local cell, or -1 when absent or outside the box. */
    public int get(int x, int y, int z) {
        return contains(x, y, z) ? blocks.get(x, y, z) : -1;
    }

    /** The tile at a local cell, or {@code null}. */
    public BlockEntityData tile(int x, int y, int z) {
        return contains(x, y, z) ? blocks.tile(x, y, z) : null;
    }

    /** Number of tiles. */
    public int tileCount() {
        int tiles = 0;
        for (long key : blocks.sortedKeys()) tiles += blocks.section(key).tileCount();
        return tiles;
    }

    @FunctionalInterface
    public interface CellVisitor {
        /** {@code state} is -1 (and {@code tile} {@code null}) for an absent cell. */
        void visit(int x, int y, int z, int state, BlockEntityData tile);
    }

    /**
     * Visits every cell of the box, present or not, in schematic order: x fastest, then z, then y. Takes time
     * proportional to the volume.
     */
    public void forEachCell(CellVisitor visitor) {
        Objects.requireNonNull(visitor);
        for (int y = 0; y < size.y(); y++) {
            for (int z = 0; z < size.z(); z++) {
                for (int x0 = 0; x0 < size.x(); x0 += 16) {
                    SectionBuffer section = blocks.section(BlockBuffer.key(x0 >> 4, y >> 4, z >> 4));
                    int x1 = Math.min(size.x(), x0 + 16);
                    for (int x = x0; x < x1; x++) {
                        if (section == null) {
                            visitor.visit(x, y, z, -1, null);
                            continue;
                        }
                        int i = SectionBuffer.index(x & 15, y & 15, z & 15);
                        visitor.visit(x, y, z, section.get(i), section.tile(i));
                    }
                }
            }
        }
    }

    @FunctionalInterface
    public interface TilePredicate {
        /** {@code state} is the state of the cell holding {@code tile}. */
        boolean test(int state, BlockEntityData tile);
    }

    /** Whether any tile, with the state of its cell, passes {@code test}. Takes time proportional to the tiles. */
    public boolean anyTile(TilePredicate test) {
        Objects.requireNonNull(test);
        boolean[] found = {false};
        for (long key : blocks.sortedKeys()) {
            SectionBuffer section = blocks.section(key);
            if (section.tileCount() == 0) continue;
            section.forEachTile((i, tile) -> {
                if (!found[0] && test.test(section.get(i), tile)) found[0] = true;
            });
            if (found[0]) return true;
        }
        return false;
    }

    /** A deep copy of the cells (local coordinates). */
    public BlockBuffer copyBlocks() {
        BlockBuffer copy = new BlockBuffer();
        for (long key : blocks.sortedKeys()) copy.putSection(key, blocks.section(key).copy());
        return copy;
    }

    /** The paste source for {@code SourceRef} resolution: a copy of the cells, the size and the anchor. */
    public SourceBlocks toSource() {
        return new SourceBlocks(copyBlocks(), size, anchor);
    }

    /** This content with another anchor (the hash changes: the anchor is part of the content). */
    public Clipboard withAnchor(BlockPos newAnchor) {
        return new Clipboard(states, blocks, size, Objects.requireNonNull(newAnchor), source, entitiesAsMade(), null);
    }

    /** This content with another source description (same hash). */
    public Clipboard withSource(String newSource) {
        return new Clipboard(states, blocks, size, anchor, Objects.requireNonNull(newSource), entitiesAsMade(),
                contentHash);
    }

    /**
     * This content with {@code newEntities} instead of its entities (canonicalized: sorted, key-sorted NBT); the hash
     * changes unless the entities are the same.
     */
    public Clipboard withEntities(List<EntitySnapshot> newEntities) {
        return new Clipboard(states, blocks, size, anchor, source, Entities.of(newEntities), null);
    }

    /** The entities, in canonical order (unmodifiable). */
    public List<EntitySnapshot> entities() {
        return entities;
    }

    /** The entities at the top: those {@link #entities()} lists, without their passengers. */
    public int entityCount() {
        return entities.size();
    }

    /**
     * Every entity the clipboard would place: those {@link #entities()} lists and all their passengers (an entity whose
     * data does not decode counts as one). What the entity caps count. Counted when the clipboard was made.
     */
    public long entityTotal() {
        return entityTotal;
    }

    /**
     * The types (as the game loads them, {@link EntityNbt#loadedId}) of the untrusted entities and of all their
     * passengers: what decides whether placing them needs the operator-NBT right. Found when the clipboard was made.
     */
    public Set<String> untrustedEntityTypes() {
        return untrustedEntityTypes;
    }

    private Entities entitiesAsMade() {
        return new Entities(entities, entityTotal, untrustedEntityTypes);
    }

    /** Approximate heap footprint. */
    public long estimatedBytes() {
        long bytes = 128 + 2L * source.length() + blocks.estimatedBytes();
        for (EntitySnapshot entity : entities) bytes += entity.estimatedBytes();
        return bytes;
    }

    /** Same content hash and source description. */
    @Override
    public boolean equals(Object o) {
        return o instanceof Clipboard other && contentHash.equals(other.contentHash) && source.equals(other.source);
    }

    @Override
    public int hashCode() {
        return contentHash.hashCode();
    }

    @Override
    public String toString() {
        return "Clipboard[size=" + size.x() + "x" + size.y() + "x" + size.z() + ", anchor=" + anchor
                + ", cells=" + cellCount + (entities.isEmpty() ? "" : ", entities=" + entities.size())
                + ", hash=" + contentHash.hex().substring(0, 12) + ", source=" + source + "]";
    }

    /**
     * A clipboard's entities and what is known of them: in canonical order with key-sorted NBT (unmodifiable), their
     * total with passengers, and the types of the untrusted ones and their passengers. Each entity's NBT is decoded
     * once, where the clipboard is made (off the server thread for copies, uploads and library loads).
     */
    private record Entities(List<EntitySnapshot> list, long total, Set<String> untrustedTypes) {
        static final Entities NONE = new Entities(List.of(), 0, Set.of());

        static Entities of(List<EntitySnapshot> list) {
            if (list.isEmpty()) return NONE;
            List<EntitySnapshot> sorted = new ArrayList<>(list.size());
            long total = 0;
            java.util.TreeSet<String> untrusted = new java.util.TreeSet<>();
            for (EntitySnapshot entity : list) {
                Objects.requireNonNull(entity);
                NbtCompound decoded;
                try {
                    decoded = EntityNbt.decode(entity.nbt());
                } catch (java.io.IOException undecodable) {
                    decoded = null;
                }
                byte[] canonical = decoded == null ? entity.nbt() : EntityNbt.canonical(decoded);
                sorted.add(entity.withNbt(canonical, entity.trusted()));
                total += 1 + (decoded == null ? 0 : EntityNbt.riderCount(decoded));
                if (!entity.trusted()) {
                    untrusted.add(EntityNbt.loadedId(entity.typeId()));
                    if (decoded != null) riderTypes(decoded, untrusted);
                }
            }
            sorted.sort(EntitySnapshot.CANONICAL_ORDER);
            return new Entities(List.copyOf(sorted), total, Set.copyOf(untrusted));
        }

        private static void riderTypes(NbtCompound entity, Set<String> types) {
            java.util.ArrayDeque<NbtCompound> open = new java.util.ArrayDeque<>();
            open.push(entity);
            while (!open.isEmpty()) {
                for (NbtCompound rider : EntityNbt.riders(open.pop())) {
                    types.add(EntityNbt.loadedId(rider.getString("id")));
                    open.push(rider);
                }
            }
        }
    }

    private static Sha256 hash(StateSpace states, BlockBuffer blocks, BlockPos size, BlockPos anchor,
                               List<EntitySnapshot> entities) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        Int2IntOpenHashMap paletteIndex = new Int2IntOpenHashMap();
        paletteIndex.defaultReturnValue(-1);
        List<String> palette = new ArrayList<>();
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                new DigestOutputStream(OutputStream.nullOutputStream(), digest), 1 << 16))) {
            out.write(HASH_MAGIC);
            for (int v : new int[] {size.x(), size.y(), size.z(), anchor.x(), anchor.y(), anchor.z()}) out.writeInt(v);
            for (long key : blocks.sortedKeys()) {
                SectionBuffer section = blocks.section(key);
                if (section.isEmpty()) continue;
                out.writeInt(BlockBuffer.keyX(key));
                out.writeInt(BlockBuffer.keyY(key));
                out.writeInt(BlockBuffer.keyZ(key));
                out.writeInt(section.presentCount());
                IOException[] failure = {null};
                section.forEachPresent(i -> {
                    int state = section.get(i);
                    int ref = paletteIndex.get(state);
                    if (ref < 0) {
                        ref = palette.size();
                        paletteIndex.put(state, ref);
                        palette.add(states.format(state));
                    }
                    try {
                        out.writeShort(i);
                        out.writeInt(ref);
                    } catch (IOException e) {
                        failure[0] = e;
                    }
                });
                if (failure[0] != null) throw failure[0];
                out.writeInt(section.tileCount());
                section.forEachTile((i, tile) -> {
                    try {
                        out.writeShort(i);
                        out.writeUTF(new NamespacedId(tile.typeId()).value());
                        // A flag keeps a raw (undecodable) tile from ever colliding with a canonical one.
                        byte[] nbt = BlockEntityNbt.tryCanonicalBytes(tile);
                        out.writeByte(nbt != null ? 1 : 0);
                        if (nbt == null) nbt = tile.nbtBytes();
                        out.writeInt(nbt.length);
                        out.write(nbt);
                    } catch (IOException e) {
                        failure[0] = e;
                    }
                });
                if (failure[0] != null) throw failure[0];
            }
            out.writeInt(-1);
            out.writeInt(palette.size());
            for (String state : palette) out.writeUTF(state);
            // Only clipboards with entities hash them, so every hash made before entities existed stays valid.
            if (!entities.isEmpty()) {
                out.write(ENTITY_MAGIC);
                out.writeInt(entities.size());
                for (EntitySnapshot entity : entities) {
                    out.writeUTF(entity.typeId());
                    out.writeDouble(entity.x());
                    out.writeDouble(entity.y());
                    out.writeDouble(entity.z());
                    out.writeFloat(entity.yaw());
                    out.writeFloat(entity.pitch());
                    BlockPos attached = entity.attached();
                    out.writeByte(attached == null ? 0 : 1);
                    if (attached != null) {
                        out.writeInt(attached.x());
                        out.writeInt(attached.y());
                        out.writeInt(attached.z());
                    }
                    byte[] nbt = entity.nbt();
                    out.writeInt(nbt.length);
                    out.write(nbt);
                }
            }
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
        return Sha256.ofBytes(digest.digest());
    }

    /** Builds a {@link Clipboard}. Not thread-safe. The builder may keep building after {@link #build()}. */
    public static final class Builder {
        private final StateSpace states;
        private final BlockPos size;
        private final BlockBuffer blocks = new BlockBuffer();
        private BlockPos anchor = BlockPos.ORIGIN;
        private String source = "";
        private final List<EntitySnapshot> entities = new ArrayList<>();
        private long lastKey;
        private SectionBuffer lastSection;

        private Builder(StateSpace states, BlockPos size) {
            this.states = Objects.requireNonNull(states);
            this.size = Objects.requireNonNull(size);
            if (size.x() < 1 || size.y() < 1 || size.z() < 1) throw new IllegalArgumentException("Clipboard size " + size);
            // Every local cell must have a section key.
            BlockBuffer.key((size.x() - 1) >> 4, (size.y() - 1) >> 4, (size.z() - 1) >> 4);
        }

        public BlockPos size() {
            return size;
        }

        /**
         * Sets a local cell.
         *
         * @throws IllegalArgumentException outside the box or for a state outside the state space
         */
        public Builder set(int x, int y, int z, int state) {
            section(x, y, z).set(SectionBuffer.index(x & 15, y & 15, z & 15), checkState(state));
            return this;
        }

        /**
         * Sets (or with {@code null} removes) the tile of a cell that is already set.
         *
         * @throws IllegalArgumentException outside the box, or when the tile's type is not a namespaced id
         * @throws IllegalStateException when the cell is absent
         */
        public Builder setTile(int x, int y, int z, BlockEntityData tile) {
            if (tile != null) new NamespacedId(tile.typeId());
            section(x, y, z).setTile(SectionBuffer.index(x & 15, y & 15, z & 15), tile);
            return this;
        }

        /** The state set at a local cell so far, or -1. */
        public int get(int x, int y, int z) {
            checkCell(x, y, z);
            return blocks.get(x, y, z);
        }

        /** The local cell that lands on the paste origin; default (0, 0, 0). */
        public Builder anchor(BlockPos value) {
            anchor = Objects.requireNonNull(value);
            return this;
        }

        public Builder source(String value) {
            source = Objects.requireNonNull(value);
            return this;
        }

        /** Adds an entity (local coordinates; it need not lie inside the box). */
        public Builder addEntity(EntitySnapshot entity) {
            entities.add(Objects.requireNonNull(entity));
            return this;
        }

        /** Entities added so far. */
        public int entityCount() {
            return entities.size();
        }

        public Clipboard build() {
            return new Clipboard(states, blocks.compact(), size, anchor, source, Entities.of(entities), null);
        }

        private SectionBuffer section(int x, int y, int z) {
            checkCell(x, y, z);
            long key = BlockBuffer.key(x >> 4, y >> 4, z >> 4);
            if (lastSection == null || key != lastKey) {
                lastSection = blocks.sectionOrCreate(key);
                lastKey = key;
            }
            return lastSection;
        }

        private void checkCell(int x, int y, int z) {
            if (x < 0 || y < 0 || z < 0 || x >= size.x() || y >= size.y() || z >= size.z()) {
                throw new IllegalArgumentException("Cell outside clipboard: " + x + "," + y + "," + z);
            }
        }

        private int checkState(int state) {
            if (state < 0 || state >= states.size()) throw new IllegalArgumentException("State " + state + " outside the state space");
            return state;
        }
    }
}
