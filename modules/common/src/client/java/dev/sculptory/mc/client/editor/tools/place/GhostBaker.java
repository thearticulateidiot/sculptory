package dev.sculptory.mc.client.editor.tools.place;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.mask.BoundMask;
import dev.sculptory.core.generate.GeneratedSource;
import dev.sculptory.core.state.OrientedStates;
import dev.sculptory.core.state.ResampledCopy;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.state.StateTips;
import dev.sculptory.core.state.TwoCellPairs;
import dev.sculptory.core.state.VerticalFlip;
import dev.sculptory.core.transform.CellResampler;
import dev.sculptory.core.transform.Orientation;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.core.world.WorldReader;
import dev.sculptory.mc.client.editor.render.ghost.GhostMapping;
import dev.sculptory.mc.client.editor.render.ghost.GhostSection;
import dev.sculptory.mc.client.editor.render.ghost.GhostVolume;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.IntPredicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the {@link GhostVolume}s the Place tool shows. Pure (no Minecraft types), so the heavy work runs off the
 * render thread; the volumes it reads must not change meanwhile (previews and captures never do).
 *
 * <ul>
 *   <li>{@link #bake}: a volume with a transform applied for real: cells moved <em>and</em> block states turned and
 *       mirrored through {@link StateSpace#rotate}/{@link StateSpace#mirror} (so stairs and logs face the way the
 *       paste will place them). The result is drawn untransformed. The model-matrix transform the renderer applies
 *       while the player is still turning the placement only moves geometry.</li>
 *   <li>{@link Capture}: a selection read from the client world a few sections per frame (a box, or only a shape's
 *       or cell set's cells), air left out, for Move and Stack previews.</li>
 *   <li>{@link Filter}: a placed volume cut down to the cells the paste-into filter would write, judged by the
 *       client world a few sections per frame.</li>
 * </ul>
 */
public final class GhostBaker {
    private static final Logger LOG = LoggerFactory.getLogger("sculptory");

    private GhostBaker() {}

    /**
     * The volume a background {@link #bake} or {@link Capture#build} made, or {@code null} while it runs, when it made
     * none, failed or was cancelled. A failure is logged, never rethrown: callers run on the render thread and fall
     * back to outlines.
     */
    public static GhostVolume finished(CompletableFuture<GhostVolume> build) {
        if (!build.isDone()) return null;
        try {
            return build.getNow(null);
        } catch (CompletionException | CancellationException e) {
            LOG.warn("Sculptory: building a ghost preview failed; showing outlines instead",
                    e.getCause() != null ? e.getCause() : e);
            return null;
        }
    }

    /** Which handles are air: the erase predicate the Place tool's volumes use. */
    public static IntPredicate air(StateSpace states) {
        Objects.requireNonNull(states);
        return handle -> StateFlags.has(states.flags(handle), StateFlags.AIR);
    }

    /**
     * How many of a flipped volume's blocks the flip upside down leaves as they are: {@link #kept} have no upside-down form ({@code VerticalFlip.KEPT}), {@link #unknown} are modded blocks
     * with properties the flip does not know. Filled by {@link #bake}; read once it is done.
     */
    public static final class FlipTally {
        private volatile long kept;
        private volatile long unknown;
        private volatile long dropped;

        public long kept() {
            return kept;
        }

        public long unknown() {
            return unknown;
        }

        /** Blocks of doors, beds and tall plants left out whole for want of room at a free angle. */
        public long dropped() {
            return dropped;
        }
    }

    /** {@link #bake(Box, Collection, Transform, StateSpace, FlipTally)} without a tally. */
    public static GhostVolume bake(Box frame, Collection<GhostSection> sections, Transform transform, StateSpace states) {
        return bake(frame, sections, transform, states, null);
    }

    /**
     * {@code source} with {@code transform} applied to its cells and states, in the transformed frame: local cells
     * {@code 0..size-1} where size is the transformed size of the source's frame.
     *
     * @param sections a snapshot of the source's sections, taken on the render thread
     * @param tally where a flipped bake counts the blocks the flip leaves as they are, or null
     */
    public static GhostVolume bake(Box frame, Collection<GhostSection> sections, Transform transform, StateSpace states,
                                   FlipTally tally) {
        Objects.requireNonNull(frame);
        Objects.requireNonNull(transform);
        Objects.requireNonNull(states);
        int sx = frame.sizeX();
        int sy = frame.sizeY();
        int sz = frame.sizeZ();
        int minX = frame.min().x();
        int minY = frame.min().y();
        int minZ = frame.min().z();
        Int2IntOpenHashMap turned = new Int2IntOpenHashMap();
        turned.defaultReturnValue(Integer.MIN_VALUE);
        boolean counting = tally != null && transform.upsideDown();
        long kept = 0;
        long unknown = 0;
        BlockBuffer out = new BlockBuffer();
        for (GhostSection section : List.copyOf(sections)) {
            int baseX = section.sectionX() << 4;
            int baseY = section.sectionY() << 4;
            int baseZ = section.sectionZ() << 4;
            for (int i = 0; i < SectionBuffer.SIZE; i++) {
                int handle = section.handle(i);
                if (handle < 0) continue;
                int x = baseX + SectionBuffer.localX(i) - minX;
                int y = baseY + SectionBuffer.localY(i) - minY;
                int z = baseZ + SectionBuffer.localZ(i) - minZ;
                int state = turned.get(handle);
                if (state == Integer.MIN_VALUE) {
                    state = transform.applyToState(states, handle);
                    turned.put(handle, state);
                }
                out.set(transform.mapX(x, z, sx, sz), transform.mapY(y, sy), transform.mapZ(x, z, sx, sz), state);
                if (counting) {
                    int kind = states.flipKind(handle);
                    if (kind == VerticalFlip.KEPT) kept++;
                    else if (kind == VerticalFlip.UNKNOWN_PROPERTIES) unknown++;
                }
            }
        }
        if (counting) {
            tally.kept = kept;
            tally.unknown = unknown;
        }
        GhostVolume volume = GhostVolume.of(out, air(states));
        BlockPos size = transform.size(sx, frame.sizeY(), sz);
        volume.setFrame(new Box(BlockPos.ORIGIN, new BlockPos(size.x() - 1, size.y() - 1, size.z() - 1)));
        return volume;
    }

    /**
     * {@link #bake(Box, Collection, Transform, StateSpace, FlipTally)} for an orientation: an exact one bakes as its
     * transform does; a free one resamples exactly as the server's paste and move do: each cell of the result box
     * ({@link Orientation#bounds}, local cells {@code 0..size-1}) takes the source cell the orientation's method gives,
     * two-cell blocks kept whole, its state turned (and with smooth slopes shaped) by the same {@link ResampledCopy} the
     * server builds. The tally then counts the blocks the turn keeps as they are (no tipped or upside-down form), the
     * modded ones it only partly knows, and the halves of two-cell blocks left out for want of room.
     */
    public static GhostVolume bake(Box frame, Collection<GhostSection> sections, Orientation orientation,
                                   StateSpace states, FlipTally tally) {
        return bake(frame, sections, orientation, states, tally, null);
    }

    /**
     * {@link #bake(Box, Collection, Orientation, StateSpace, FlipTally)} for a move: {@code moving} (frame-local) says
     * which cells of the frame move (the selection's), so its air cells, which a capture leaves out, weigh as air, and
     * two-cell blocks keep to the cells the move writes, as the server's move does. Null for a paste.
     */
    public static GhostVolume bake(Box frame, Collection<GhostSection> sections, Orientation orientation,
                                   StateSpace states, FlipTally tally, CellFilter moving) {
        Objects.requireNonNull(orientation);
        if (orientation.isExact()) return bake(frame, sections, orientation.transform(), states, tally);
        Objects.requireNonNull(frame);
        Objects.requireNonNull(states);
        Long2ObjectOpenHashMap<GhostSection> byKey = new Long2ObjectOpenHashMap<>();
        for (GhostSection section : List.copyOf(sections)) {
            byKey.put(BlockBuffer.key(section.sectionX(), section.sectionY(), section.sectionZ()), section);
        }
        BlockPos min = frame.min();
        CellResampler.SourceCells cells = new CellResampler.SourceCells() {
            @Override
            public int state(int x, int y, int z) {
                if (x < 0 || y < 0 || z < 0 || x >= frame.sizeX() || y >= frame.sizeY() || z >= frame.sizeZ()) return -1;
                int wx = min.x() + x, wy = min.y() + y, wz = min.z() + z;
                GhostSection section = byKey.get(BlockBuffer.keyOfBlock(wx, wy, wz));
                int handle = section == null ? -1 : section.handle(SectionBuffer.index(wx & 15, wy & 15, wz & 15));
                if (handle < 0 && moving != null && moving.contains(x, y, z)) return states.air();
                return handle;
            }

            @Override
            public boolean solid(int state) {
                return !StateFlags.has(states.flags(state), StateFlags.AIR);
            }
        };
        OrientedStates oriented = new OrientedStates(states, orientation);
        ResampledCopy.Tables tables = ResampledCopy.Tables.shared(states);
        LongArrayList halves = new LongArrayList();
        for (GhostSection source : byKey.values()) {
            int baseX = (source.sectionX() << 4) - min.x();
            int baseY = (source.sectionY() << 4) - min.y();
            int baseZ = (source.sectionZ() << 4) - min.z();
            for (int i = 0; i < SectionBuffer.SIZE; i++) {
                int handle = source.handle(i);
                int x = baseX + SectionBuffer.localX(i), y = baseY + SectionBuffer.localY(i);
                int z = baseZ + SectionBuffer.localZ(i);
                if (handle >= 0 && cells.state(x, y, z) >= 0) TwoCellPairs.addHalf(halves, tables.blocks(), handle, x, y, z);
            }
        }
        CellResampler base = orientation.resampler(frame.sizeX(), frame.sizeY(), frame.sizeZ());
        int[] taken = new int[3];
        // A move writes only its landing cells: the cells the method fills from a moving cell.
        TwoCellPairs.Writable writable = moving == null ? (x, y, z) -> true
                : (x, y, z) -> base.source(x, y, z, cells, taken) && cells.state(taken[0], taken[1], taken[2]) >= 0;
        ResampledCopy copy = ResampledCopy.of(base, cells, oriented, orientation.smoothSlopes(), halves.toLongArray(),
                tables, writable);
        CellResampler resampler = copy.cells();
        BlockPos size = resampler.size();
        BlockBuffer out = new BlockBuffer();
        int[] cell = new int[3];
        long kept = 0;
        long unknown = 0;
        // From each block of the source, the result cells that take it, exactly as the server's programs fill them: the
        // takers the resampler names, or for smooth (whose takers are dear) the cells within reach of where it lands,
        // each asked once. The work follows the blocks, not their sections' boxes.
        boolean dear = resampler.maxTakers() > 27;
        LongOpenHashSet asked = new LongOpenHashSet();
        int[] takers = new int[3 * resampler.maxTakers()];
        double[] point = new double[3];
        for (GhostSection source : byKey.values()) {
            int baseX = (source.sectionX() << 4) - min.x();
            int baseY = (source.sectionY() << 4) - min.y();
            int baseZ = (source.sectionZ() << 4) - min.z();
            for (int i = 0; i < SectionBuffer.SIZE; i++) {
                int handle = source.handle(i);
                if (handle < 0) continue;
                int sx = baseX + SectionBuffer.localX(i), sy = baseY + SectionBuffer.localY(i);
                int sz = baseZ + SectionBuffer.localZ(i);
                if (cells.state(sx, sy, sz) < 0) continue;
                if (!dear) {
                    int n = resampler.takers(sx, sy, sz, cells, takers);
                    for (int k = 0; k < n; k++) {
                        int x = takers[3 * k], y = takers[3 * k + 1], z = takers[3 * k + 2];
                        out.set(x, y, z, copy.state(x, y, z, handle));
                    }
                    if (tally != null) {
                        int kind = oriented.kind(handle);
                        if (kind == StateTips.KEPT) kept += n;
                        else if (kind == StateTips.UNKNOWN_PROPERTIES) unknown += n;
                    }
                    continue;
                }
                resampler.forward(sx + 0.5, sy + 0.5, sz + 0.5, point);
                int bx = (int) Math.floor(point[0]), by = (int) Math.floor(point[1]), bz = (int) Math.floor(point[2]);
                for (int y = Math.max(0, by - 2); y <= Math.min(size.y() - 1, by + 2); y++) {
                    for (int z = Math.max(0, bz - 2); z <= Math.min(size.z() - 1, bz + 2); z++) {
                        for (int x = Math.max(0, bx - 2); x <= Math.min(size.x() - 1, bx + 2); x++) {
                            if (!asked.add(((long) y * size.z() + z) * size.x() + x)) continue;
                            if (!resampler.source(x, y, z, cells, cell)) continue;
                            int from = cells.state(cell[0], cell[1], cell[2]);
                            if (from < 0) continue;
                            out.set(x, y, z, copy.state(x, y, z, from));
                            if (tally != null) {
                                int kind = oriented.kind(from);
                                if (kind == StateTips.KEPT) kept++;
                                else if (kind == StateTips.UNKNOWN_PROPERTIES) unknown++;
                            }
                        }
                    }
                }
            }
        }
        if (tally != null) {
            tally.kept = kept;
            tally.unknown = unknown;
            tally.dropped = 2L * copy.droppedPairs() + copy.lostBlocks();
        }
        GhostVolume volume = GhostVolume.of(out, air(states));
        volume.setFrame(new Box(BlockPos.ORIGIN, new BlockPos(size.x() - 1, size.y() - 1, size.z() - 1)));
        return volume;
    }

    /**
     * The ghost of a symmetric copy of a free move, as the server writes it: {@code copy} (the copy's own selection,
     * read from the world in its box) turned back into the original's frame by the image's inverse, resampled with
     * {@code orientation} (the original's), then turned by the image, so it shows the exact image of what the original
     * would write from those blocks. In the copy's result box: place it at the image of the original's result box.
     */
    public static GhostVolume bakeImage(GhostVolume copy, dev.sculptory.core.brush.Symmetry.Image image,
                                        Orientation orientation, StateSpace states) {
        Box frame = Objects.requireNonNull(copy.frame());
        GhostVolume back = bake(frame, copy.sections(), image.inverse().transform(), states);
        GhostVolume turned = bake(Objects.requireNonNull(back.frame()), back.sections(), orientation, states, null);
        return bake(Objects.requireNonNull(turned.frame()), turned.sections(), image.transform(), states);
    }

    /**
     * Generators: the ghost of a {@link GeneratedSource}, in the source's own frame (local cells {@code 0..size-1} of its
     * bounds; place it at {@code bounds.min()}). Air cells become erase ghosts, as a paste with "include air" clears
     * them. Pure: runs on any thread once the source is built.
     */
    public static GhostVolume fromSparse(GeneratedSource source, StateSpace states) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(states);
        BlockBuffer out = new BlockBuffer();
        GhostVolume volume;
        if (source.isEmpty()) {
            volume = GhostVolume.of(out, air(states));
            volume.setFrame(new Box(BlockPos.ORIGIN, BlockPos.ORIGIN));
            return volume;
        }
        Box bounds = source.bounds();
        BlockPos min = bounds.min();
        source.forEach((x, y, z, state) -> out.set(x - min.x(), y - min.y(), z - min.z(), state));
        volume = GhostVolume.of(out, air(states));
        volume.setFrame(new Box(BlockPos.ORIGIN, new BlockPos(bounds.sizeX() - 1, bounds.sizeY() - 1, bounds.sizeZ() - 1)));
        return volume;
    }

    /** The blocks of {@code box} in {@code world} at once (see {@link Capture}). */
    public static GhostVolume capture(WorldReader world, Box box) {
        Capture capture = new Capture(world, box);
        capture.step(Integer.MAX_VALUE);
        return capture.build();
    }

    /** Which cells of the box a capture takes (a shaped or magic selection's own cells). */
    @FunctionalInterface
    public interface CellFilter {
        CellFilter ALL = (x, y, z) -> true;

        boolean contains(int x, int y, int z);
    }

    /**
     * Reads the blocks of a box from the world a few sections at a time ({@link #step}, on the thread the world
     * belongs to: the render thread for the client world), then {@link #build}s the volume, which may run on any
     * thread once every section is read. Cells are box-local ({@code 0..size-1}); air and cells the filter leaves out
     * are left out.
     */
    public static final class Capture {
        private final WorldReader world;
        private final Box box;
        private final CellFilter filter;
        private final IntPredicate isAir;
        private final long[] keys;
        private final BlockBuffer out = new BlockBuffer();
        private final SectionBuffer scratch = new SectionBuffer();
        private int next;

        /** Every cell of {@code box}. */
        public Capture(WorldReader world, Box box) {
            this(world, box, CellFilter.ALL, null);
        }

        /**
         * The cells of {@code box} that {@code filter} holds, reading only the world sections {@code sectionKeys}
         * ({@code BlockBuffer.key}; null for every section of the box).
         */
        public Capture(WorldReader world, Box box, CellFilter filter, long[] sectionKeys) {
            this.world = Objects.requireNonNull(world);
            this.box = Objects.requireNonNull(box);
            this.filter = Objects.requireNonNull(filter);
            this.isAir = air(world.states());
            if (sectionKeys != null) {
                this.keys = sectionKeys.clone();
            } else {
                LongArrayList sections = new LongArrayList();
                box.forEachSectionKey(sections::add);
                this.keys = sections.toLongArray();
            }
        }

        /** Reads up to {@code maxSections} more world sections; returns whether every one has been read. */
        public boolean step(int maxSections) {
            BlockPos min = box.min();
            BlockPos max = box.max();
            for (int n = 0; n < maxSections && next < keys.length; n++) {
                long key = keys[next++];
                int sectionX = BlockBuffer.keyX(key);
                int sectionY = BlockBuffer.keyY(key);
                int sectionZ = BlockBuffer.keyZ(key);
                world.copySection(sectionX, sectionY, sectionZ, scratch);
                int fromX = Math.max(min.x(), sectionX << 4);
                int toX = Math.min(max.x(), (sectionX << 4) + 15);
                int fromY = Math.max(min.y(), sectionY << 4);
                int toY = Math.min(max.y(), (sectionY << 4) + 15);
                int fromZ = Math.max(min.z(), sectionZ << 4);
                int toZ = Math.min(max.z(), (sectionZ << 4) + 15);
                for (int y = fromY; y <= toY; y++) {
                    for (int z = fromZ; z <= toZ; z++) {
                        for (int x = fromX; x <= toX; x++) {
                            int handle = scratch.get(SectionBuffer.index(x & 15, y & 15, z & 15));
                            if (handle < 0 || isAir.test(handle) || !filter.contains(x, y, z)) continue;
                            out.set(x - min.x(), y - min.y(), z - min.z(), handle);
                        }
                    }
                }
            }
            return done();
        }

        public boolean done() {
            return next >= keys.length;
        }

        /** World sections read so far, and in all. */
        public int sectionsRead() {
            return next;
        }

        public int sections() {
            return keys.length;
        }

        /** The volume of what was read; call once {@link #done()}. */
        public GhostVolume build() {
            GhostVolume volume = GhostVolume.of(out, isAir);
            volume.setFrame(new Box(BlockPos.ORIGIN, new BlockPos(box.sizeX() - 1, box.sizeY() - 1, box.sizeZ() - 1)));
            return volume;
        }
    }

    /**
     * Cuts a placed volume down to the cells the paste-into filter would write: each cell is mapped to the world
     * cell it lands on ({@link GhostMapping}) and kept when the world there is air (for {@code AIR}) or not air (for
     * {@code EXISTING}), as the server judges it right before the write. Cells landing in chunks the client has not
     * loaded are kept, so the ghost stays whole there. Reads the world a few sections at a time ({@link #step}, on
     * the client thread); {@link #build} gives the kept cells in the volume's own frame, or the volume itself when it
     * dropped none. The volume must not change meanwhile.
     */
    public static final class Filter {
        private final WorldReader world;
        private final GhostVolume volume;
        private final GhostMapping mapping;
        private final PasteOptions.Into into;
        /** The global mask: a cell it rejects where it lands is dropped too. */
        private final BoundMask mask;
        private final IntPredicate isAir;
        private final List<GhostSection> sections;
        private final BlockBuffer out = new BlockBuffer();
        private int next;
        private boolean dropped;

        public Filter(WorldReader world, GhostVolume volume, GhostMapping mapping, PasteOptions.Into into) {
            this(world, volume, mapping, into, BoundMask.ALL);
        }

        public Filter(WorldReader world, GhostVolume volume, GhostMapping mapping, PasteOptions.Into into,
                      BoundMask mask) {
            this.mask = Objects.requireNonNull(mask);
            this.world = Objects.requireNonNull(world);
            this.volume = Objects.requireNonNull(volume);
            this.mapping = Objects.requireNonNull(mapping);
            this.into = Objects.requireNonNull(into);
            this.isAir = air(world.states());
            this.sections = List.copyOf(volume.sections());
        }

        /** Judges up to {@code maxSections} more sections of the volume; returns whether every one is done. */
        public boolean step(int maxSections) {
            for (int n = 0; n < maxSections && next < sections.size(); n++) {
                GhostSection section = sections.get(next++);
                int baseX = section.sectionX() << 4;
                int baseY = section.sectionY() << 4;
                int baseZ = section.sectionZ() << 4;
                int chunkX = Integer.MIN_VALUE;
                int chunkZ = Integer.MIN_VALUE;
                boolean loaded = false;
                for (int i = 0; i < SectionBuffer.SIZE; i++) {
                    int handle = section.handle(i);
                    if (handle < 0) continue;
                    int x = baseX + SectionBuffer.localX(i);
                    int y = baseY + SectionBuffer.localY(i);
                    int z = baseZ + SectionBuffer.localZ(i);
                    int worldX = mapping.worldX(x, z);
                    int worldZ = mapping.worldZ(x, z);
                    if ((worldX >> 4) != chunkX || (worldZ >> 4) != chunkZ) {
                        chunkX = worldX >> 4;
                        chunkZ = worldZ >> 4;
                        loaded = world.isLoaded(chunkX, chunkZ);
                    }
                    int worldY = mapping.worldY(y);
                    if (!loaded || writable(worldX, worldY, worldZ, world.get(worldX, worldY, worldZ))) {
                        out.set(x, y, z, handle);
                    } else {
                        dropped = true;
                    }
                }
            }
            return done();
        }

        public boolean done() {
            return next >= sections.size();
        }

        /** The kept cells in the volume's frame, or the volume itself when every cell is kept; call once {@link #done()}. */
        public GhostVolume build() {
            if (!dropped) return volume;
            GhostVolume filtered = GhostVolume.of(out, isAir);
            filtered.setFrame(volume.frame());
            return filtered;
        }

        /** The contract's rule on the world state a cell lands on: EXISTING wants a block that is not air, AIR air. */
        private boolean writable(int x, int y, int z, int state) {
            boolean into = switch (this.into) {
                case EVERYTHING -> true;
                case EXISTING -> !isAir.test(state);
                case AIR -> isAir.test(state);
            };
            return into && (mask.acceptsAll() || mask.test(x, y, z, state, world));
        }
    }
}
