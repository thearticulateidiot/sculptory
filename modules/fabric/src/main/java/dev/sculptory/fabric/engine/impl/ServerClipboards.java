package dev.sculptory.fabric.engine.impl;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.Sha256;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.CellPredicate;
import dev.sculptory.core.edit.EditProgram;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.entity.EntitySnapshot;
import dev.sculptory.core.generate.GeneratedSource;
import dev.sculptory.core.generate.SparseUpload;
import dev.sculptory.core.generate.SparseUploadException;
import dev.sculptory.core.history.EntityState;
import dev.sculptory.core.mask.BoundMask;
import dev.sculptory.core.nbt.NbtException;
import dev.sculptory.core.nbt.NbtLimitException;
import dev.sculptory.core.palette.BlockPalette;
import dev.sculptory.core.region.CellSet;
import dev.sculptory.core.region.CellSetFormatException;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.Regions;
import dev.sculptory.core.schem.AssetInfo;
import dev.sculptory.core.schem.DataFixHook;
import dev.sculptory.core.schem.Schematic;
import dev.sculptory.core.schem.SchematicCodec;
import dev.sculptory.core.schem.SchematicException;
import dev.sculptory.core.schem.SchematicFiles;
import dev.sculptory.core.schem.SchematicFormat;
import dev.sculptory.core.schem.SchematicMetadata;
import dev.sculptory.core.schem.SchematicReport;
import dev.sculptory.core.schem.StructureCodec;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.world.WorldReader;
import dev.sculptory.fabric.config.FolderMigration;
import dev.sculptory.fabric.perm.FabricPermissionService;
import dev.sculptory.fabric.world.FabricEntities;
import dev.sculptory.fabric.world.FabricTile;
import dev.sculptory.fabric.world.FabricWorldReader;
import dev.sculptory.fabric.world.EntityTypeRules;
import dev.sculptory.protocol.v2.AssetAccess;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.protocol.v2.S2C;
import dev.sculptory.protocol.v2.StreamKind;
import dev.sculptory.server.config.SculptoryConfig;
import dev.sculptory.server.engine.ClipboardService;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.JobListener;
import dev.sculptory.server.engine.JobTicket;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.RunOptions;
import dev.sculptory.server.engine.impl.AssetCache;
import dev.sculptory.server.engine.impl.EditMasks;
import dev.sculptory.server.engine.impl.EntityColumns;
import dev.sculptory.server.engine.impl.EntityJobs;
import dev.sculptory.server.engine.impl.EntityWork;
import dev.sculptory.server.engine.impl.PlayerClipboards;
import dev.sculptory.server.engine.impl.RequestSlots;
import dev.sculptory.server.library.Library;
import dev.sculptory.server.library.LibraryException;
import dev.sculptory.server.library.LibraryPath;
import dev.sculptory.server.library.LibraryPathException;
import dev.sculptory.server.library.PaletteFile;
import dev.sculptory.server.net.PreviewPayload;
import dev.sculptory.server.schem.EntitySanitizer;
import dev.sculptory.server.schem.FileEntities;
import dev.sculptory.server.schem.TileSanitizer;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The server {@link ClipboardService} for one {@link EngineEditService}: clipboards, previews, schematic import and
 * export, and the asset library. Server thread only; file I/O, parsing and encoding
 * run on this server's own bounded executor ({@link #newExecutor()}: {@value #IO_THREADS} threads, a queue of
 * {@value #IO_QUEUE} tasks, refusing with {@code QUEUE_FULL} beyond it) and hop back with
 * {@code MinecraftServer.execute}.
 *
 * <p><b>Permissions.</b> Everything needs {@code use} and {@code clipboard} (and editing enabled); a cut also needs
 * {@code region}, an upload {@code schematic.import}, an export {@code schematic.export}. Library access follows
 * {@link Library}: shared folders are readable by all, writing them needs {@code library.write}; a save without it
 * lands in the player's {@code _players/<uuid>/} folder (the reply names the real path). Library management (M4:
 * rename, move, delete, new folder) needs the right to change every path it touches ({@link Library#checkChange}:
 * {@code library.write} in the shared area, admin in another player's folder, nothing more in the player's own),
 * checked at admission and again by the library; it never redirects into the player's folder. Palettes
 * ({@link #savePalette}, {@link #loadPalette}) follow the same rules as saved and loaded assets, and management works
 * on them as on assets; a file keeps its kind, so no rename or move turns a palette into a schematic. Per-asset
 * access ({@link #access}, {@link #setAccess}) restricts a file to listed
 * players: every load, preview (by path or by hash), palette load and scatter variant checks
 * {@link Library#mayRead(LibraryPath, Viewer)} at admission, and the library again on the I/O thread; who may change
 * an entry's access is who may manage it. The virtual folder {@value LibraryPath#SHARED} lists what others granted
 * the player.
 *
 * <p><b>Work in flight</b> is limited per player by {@link RequestSlots}: {@value RequestSlots#MAX_TASKS} requests,
 * one clipboard or schematic decode (copies, library loads, asset previews, and uploads from {@code UploadBegin}
 * on), one save. Decodes therefore queue fairly, one per player, on the executor's FIFO queue.
 *
 * <p><b>Copy.</b> A copy takes a region (a box, a shape or a resolved cell set; an unresolved upload is
 * {@code SELECTION_NOT_LOADED}, a shape of too many rows {@code TOO_LARGE}). The clipboard's box is the region's bounds
 * cut to the build height, and cells outside the region are absent. The box is limited to {@code maxClipboardVolume}
 * ({@code TOO_LARGE}; {@code limit.bypass} lifts it), by its volume as for a box: the snapshot read is bounded by the box
 * (a paste is admitted on the clipboard's present cells). Its source must pass the source-read rule ({@link EngineEditService#requireReadable}: every column holding a
 * region cell writable by the player, else {@code PROTECTED}) and be loaded ({@code UNLOADED}; copies never load
 * chunks). The player's queued dabs are applied first; the sections holding region cells are then snapshotted on the
 * server thread at once (a bounded read, at most the volume cap), and the {@link Clipboard} is built from the snapshot
 * off the server thread. A cut refuses {@code AREA_BUSY} while a job holds one of those sections, and admits its
 * {@code Erase} job (with the same region and mask) right after the snapshot, before any other job can change the area;
 * the erase is an ordinary, undoable job. Captured tiles stay server-captured, so they are trusted when the player
 * pastes them; only a bypass copy of an area the player could not write turns them into untrusted copies.
 *
 * <p><b>Selection uploads</b> ({@link #beginSelectionUpload}) need {@code use}
 * and {@code region} or {@code clipboard}, hold a request slot (not the clipboard slot) from the grant to the decode, and
 * are decoded off the server thread with {@code CellSet.decode} under the configured caps ({@code limits.maxSelectionCells},
 * {@code limits.maxSelectionSections}); the set must have the hash, bounds and cell count announced.
 *
 * <p><b>Files are untrusted.</b> Uploads (at most {@code maxUploadBytes}, compressed) and library files are parsed
 * off the server thread with {@link SchematicFiles} (any format, told by the content, not the file name) under
 * {@code SchematicCodec.Limits.untrustedUpload()} (NBT, heap
 * and block-entity caps) and {@code maxClipboardVolume} cells unless {@code limit.bypass}, upgraded by the
 * {@link DataFixHook}, and then <b>sanitized for everyone, operators included</b> ({@link TileSanitizer}: command,
 * structure and jigsaw blocks, spawners and lecterns lose their NBT; signs keep only their text, as a
 * {@code SanitizedTile} that everyone may place). An opt-in to keep file NBT does not exist in v1. Their other tiles
 * are plain {@code NbtBytes} (untrusted). What an import removed or could not keep is reported as notices (counts and
 * at most {@value #MAX_NOTICE_NAMES} state names).
 *
 * <p><b>Leaving the server.</b> Export and saving to the library sanitize the same way for players who may not
 * handle operator NBT ({@code nbt.operator} or creative level-2 op), so command text is neither sent to them nor
 * stored by them.
 *
 * <p>Messages sent to the client never carry exception text that may contain server paths: unexpected errors are
 * logged and answered with a generic detail.
 */
public final class ServerClipboards implements ClipboardService<ServerPlayerEntity> {
    private static final Logger LOG = LoggerFactory.getLogger("sculptory");
    /** Largest preview payload sent. */
    public static final long MAX_PREVIEW_BYTES = 32L << 20;
    /** Largest exported {@code .schem} sent. */
    public static final long MAX_EXPORT_BYTES = 64L << 20;
    /**
     * The largest box a save or export writes, {@code limit.bypass} or not: 268,435,456 cells (1,024 × 256 × 1,024).
     * A Sponge or Litematica schematic holds every cell of its box, so writing one takes time and memory in proportion
     * to the box; a structure file lists its present cells instead ({@link #MAX_STRUCTURE_CELLS}).
     */
    public static final long MAX_STORED_BOX = 1L << 28;
    /**
     * Present cells a structure ({@code .nbt}) save or export may list: each is an NBT compound built in memory before
     * the file is written (about 40 bytes of NBT, more for a block entity), so the cap keeps the encoder to a few
     * hundred megabytes at worst, {@code limit.bypass} or not.
     */
    public static final long MAX_STRUCTURE_CELLS = 1L << 20;
    /** Unknown state names quoted in an import notice. */
    public static final int MAX_NOTICE_NAMES = 3;
    /**
     * One more than a player's request cap ({@link RequestSlots#MAX_TASKS}): however heavy, one player's work (two
     * huge exports, say) never holds every thread, so others' requests keep moving.
     */
    public static final int IO_THREADS = RequestSlots.MAX_TASKS + 1;
    public static final int IO_QUEUE = 64;
    public static final String NOTICE_PREFIX = "sculptory.notice.";
    public static final String NOTICE_IMPORT_OPERATOR_NBT = NOTICE_PREFIX + "import_operator_nbt_removed";
    public static final String NOTICE_EXPORT_OPERATOR_NBT = NOTICE_PREFIX + "export_operator_nbt_removed";
    public static final String NOTICE_IMPORT_ENTITY_DATA = NOTICE_PREFIX + "import_entity_data_removed";
    public static final String NOTICE_EXPORT_ENTITY_DATA = NOTICE_PREFIX + "export_entity_data_removed";
    /** A structure file larger than a vanilla structure block loads: its width, height, length and the block's limit. */
    public static final String NOTICE_EXPORT_STRUCTURE_SIZE = NOTICE_PREFIX + "export_structure_too_large";
    /** Scheduled block and fluid updates a file held, which are not imported: their count. */
    public static final String NOTICE_IMPORT_TICKS = NOTICE_PREFIX + "import_ticks_skipped";
    /** Entities (passengers counted) a copy may take with {@code limit.bypass}: as many as a file may hold. */
    static final int MAX_BYPASS_ENTITIES = SchematicCodec.Limits.DEFAULT_MAX_ENTITIES;
    /**
     * Entity data one copy may take, for everyone: the entity and block-entity data a file may hold
     * ({@code SchematicCodec.Limits.untrustedUpload().maxTotalTileBytes()}, 32 MiB).
     */
    static final long MAX_ENTITY_BYTES = 32L << 20;

    private final EngineEditService edits;
    private final EngineRuntime runtime;
    private final MinecraftServer server;
    private final FabricPermissionService permissions;
    private final Library library;
    private final ExecutorService io;
    private final DataFixHook fixes;
    private final RequestSlots slots = new RequestSlots();
    /** Set by {@link #shutdown()}: finished work only releases its leases. */
    private volatile boolean closing;

    public ServerClipboards(EngineEditService edits, Library library, ExecutorService io, DataFixHook fixes) {
        this.edits = Objects.requireNonNull(edits);
        this.runtime = edits.runtime();
        this.server = edits.server();
        this.permissions = runtime.permissions();
        this.library = Objects.requireNonNull(library);
        this.io = Objects.requireNonNull(io);
        this.fixes = Objects.requireNonNull(fixes);
        edits.attachLibrary(library); // cached assets are checked against per-asset access
    }

    /** The config in effect: the runtime's, which {@code /sculptory reload} replaces (checks read it when they run). */
    private SculptoryConfig config() {
        return runtime.config();
    }

    /** {@code <gameDir>/sculptory/library}. */
    public static Path defaultLibraryRoot() {
        return FolderMigration.gameDir().resolve("library");
    }

    /**
     * A bounded executor for clipboard work: {@value #IO_THREADS} daemon threads and a FIFO queue of
     * {@value #IO_QUEUE} tasks; beyond it, {@code execute} throws {@link RejectedExecutionException}.
     */
    public static ExecutorService newExecutor() {
        AtomicInteger threads = new AtomicInteger();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(IO_THREADS, IO_THREADS, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(IO_QUEUE),
                task -> {
                    Thread thread = new Thread(task, "Sculptory I/O #" + threads.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    public Library library() {
        return library;
    }

    public EngineEditService edits() {
        return edits;
    }

    /** Whether a clipboard (or schematic decode) of the player's is in progress. */
    public boolean building(UUID player) {
        return slots.building(player);
    }

    /** The player's requests in flight. */
    public int requests(UUID player) {
        return slots.tasks(player);
    }

    /** Server start: prepares the library off the server thread (temp-file cleanup, usage count). */
    public void start() {
        try {
            io.execute(() -> {
                try {
                    library.start();
                } catch (LibraryException e) {
                    LOG.warn("Sculptory library could not start: {}", e.getMessage());
                }
            });
        } catch (RejectedExecutionException e) {
            LOG.warn("Sculptory library start skipped: the executor is not running");
        }
    }

    /** Periodic (every couple of seconds): writes the library index if it changed. */
    public void flushSoon() {
        try {
            io.execute(library::flush);
        } catch (RejectedExecutionException e) {
            // busy: the next flush or shutdown writes it
        }
    }

    /** Server stop: lets queued work finish (up to 5 s), then writes the library index. */
    public void shutdown() {
        closing = true;
        io.shutdown();
        try {
            if (!io.awaitTermination(5, TimeUnit.SECONDS)) io.shutdownNow();
        } catch (InterruptedException e) {
            io.shutdownNow();
            Thread.currentThread().interrupt();
        }
        library.flush();
    }

    // ================================================================== copy

    @Override
    public JobTicket copy(ServerPlayerEntity p, Region region, BlockPos origin, boolean cut, CellMask mask,
                          EntityFilter entities, JobListener cutListener, Reply<ClipboardInfo> callerReply)
            throws EditRejected {
        checkThread();
        Objects.requireNonNull(region);
        Objects.requireNonNull(origin);
        Objects.requireNonNull(mask);
        Objects.requireNonNull(entities);
        var reply = once(Objects.requireNonNull(callerReply));
        requireBasics(p);
        if (cut) require(p, Perm.REGION);
        boolean bypass = permissions.has(p, Perm.LIMIT_BYPASS);
        EngineEditService.admitRegion(region, bypass);
        UUID owner = p.getUuid();
        ServerWorld world = p.getServerWorld();
        Box area = EngineEditService.inBuildHeight(region.bounds(), world);
        long max = config().limits.maxClipboardVolume;
        if (area.volume() > max && !bypass) throw new EditRejected(RejectReason.TOO_LARGE, area.volume() + " > " + max + " blocks");
        try {
            Math.subtractExact(origin.x(), area.min().x());
            Math.subtractExact(origin.y(), area.min().y());
            Math.subtractExact(origin.z(), area.min().z());
        } catch (ArithmeticException e) {
            throw new EditRejected(RejectReason.INVALID, "origin too far from the box");
        }
        StateSpace states = runtime.states();
        CellPredicate predicate;
        try {
            predicate = mask.bind(states);
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            throw new EditRejected(RejectReason.INVALID, "mask names a state outside the state space");
        }
        // The global mask is judged where the blocks are taken from: a copy and a cut take
        // only the cells it accepts, as the world is now; the cut's erase is masked the same way where it writes.
        BoundMask global = EditMasks.current(owner);
        // A box is read by its columns and sections; any other region by the columns and sections holding its cells.
        boolean box = region instanceof Region.Cuboid;
        long[] sections = box ? null : edits.regionSections(region, area.min().y(), area.max().y());
        Long2ObjectOpenHashMap<long[]> columns = box ? null : edits.regionColumns(region, area.min().y(), area.max().y());
        boolean trusted = box ? edits.requireReadable(p, world, area) : edits.requireReadable(p, world, columns, area);
        FabricWorldReader reader = runtime.reader(world);
        if (box) {
            for (int cx = area.min().x() >> 4; cx <= area.max().x() >> 4; cx++) {
                for (int cz = area.min().z() >> 4; cz <= area.max().z() >> 4; cz++) {
                    if (!reader.isLoaded(cx, cz)) throw new EditRejected(RejectReason.UNLOADED, "chunk " + cx + "," + cz);
                }
            }
        } else {
            for (long column : columns.keySet()) {
                int cx = Regions.columnX(column), cz = Regions.columnZ(column);
                if (!reader.isLoaded(cx, cz)) throw new EditRejected(RejectReason.UNLOADED, "chunk " + cx + "," + cz);
            }
        }
        if (cut && (box ? runtime.executor().isLockedFor(world, area, owner)
                : runtime.executor().isLockedFor(world, sections, owner))) {
            throw new EditRejected(RejectReason.AREA_BUSY);
        }
        // Entities are read with the blocks: all of them or none, never a part. Only
        // the chunks the region's cells are in (and their neighbours near a cell) count, never every chunk of its bounds.
        long[] entityColumns = entities == EntityFilter.NONE ? null
                : EntityColumns.of(region, area.min().y(), area.max().y());
        if (entityColumns != null) {
            String unloaded = FabricEntities.firstUnloaded(world, entityColumns);
            if (unloaded != null) {
                throw new EditRejected(RejectReason.UNLOADED, "the entities of chunk " + unloaded + " are not loaded");
            }
        }
        // The player's queued dabs are older than this copy: apply them first, so the snapshot (and a cut's erase,
        // which would otherwise flush them after the snapshot) sees them. Before the lease: this may refuse.
        edits.commitStroke(owner);
        RequestSlots.Lease lease = slots.acquire(owner, true, false);
        long connection = edits.connection(owner);

        Long2ObjectOpenHashMap<SectionBuffer> snapshot = new Long2ObjectOpenHashMap<>();
        List<EntitySnapshot> taken = new ArrayList<>();
        List<EntityState> takenStates = new ArrayList<>();
        List<UUID> takenIds = new ArrayList<>();
        try {
            CellPredicate entityCells = global.acceptsAll() ? predicate
                    : (x, y, z, before) -> predicate.test(x, y, z, before) && global.test(x, y, z, before, reader);
            takeEntities(p, world, region, entityColumns, area, entities, entityCells, trusted, cut, taken, takenStates,
                    takenIds);
            LongConsumer capture = key -> {
                SectionBuffer section = new SectionBuffer();
                reader.copySection(BlockBuffer.keyX(key), BlockBuffer.keyY(key), BlockBuffer.keyZ(key), section);
                // A bypass copy of an area the player could not write: its tiles are not theirs to vouch for.
                if (!trusted) section.forEachTile((i, tile) -> section.setTile(i, untrusted(tile)));
                snapshot.put(key, section);
            };
            if (box) {
                area.forEachSectionKey(capture);
            } else {
                for (long key : sections) capture.accept(key);
            }
            // A mask reading neighbours reads those around the region too: the loaded sections next to it.
            if (global.reach() > 0) captureHalo(snapshot, reader, world, capture);
        } catch (EditRejected | RuntimeException e) {
            lease.release();
            throw e;
        }
        WorldReader view = new SnapshotReader(states, world.getBottomY(), world.getTopY(), snapshot);
        CellPredicate masked = global.acceptsAll() ? predicate
                : (x, y, z, before) -> predicate.test(x, y, z, before) && global.test(x, y, z, before, view);
        // A cut under a mask that reads neighbours decides its cells once, here, over the snapshot: the clipboard takes
        // them and the erase removes exactly them (judged again later, a neighbour could read otherwise).
        CellSet decided = cut && global.reach() > 0 ? acceptedCells(region, area, view, masked) : null;
        CellPredicate cells = decided == null ? masked : (x, y, z, before) -> decided.contains(x, y, z);
        String source = (cut ? "cut " : "copy ") + EngineEditService.worldId(world);
        AtomicBoolean abandoned = new AtomicBoolean();
        offThread(lease, () -> {
                    Clipboard blocks = Clipboard.copyOf(view, region, area, origin, cells, source);
                    return taken.isEmpty() ? blocks : blocks.withEntities(taken);
                },
                clipboard -> {
                    if (!abandoned.get()) install(p, connection, clipboard, List.of(), reply);
                },
                (reason, detail) -> {
                    if (!abandoned.get()) reply.failed(reason, detail);
                });
        if (!cut) return null;
        try {
            // Admitted now, before any tick: the erase removes exactly what the snapshot holds (the same cells and
            // mask), and the entities taken.
            EntityWork removal = takenIds.isEmpty() ? null : EntityJobs.cut(takenStates, takenIds, entities, region, area);
            if (decided != null && !decided.isEmpty()) {
                return edits.run(p, new OpSpec.Erase(new Region.Cells(decided), CellMask.ANY), RunOptions.DEFAULT,
                        cutListener, removal, true);
            }
            return edits.run(p, new OpSpec.Erase(box ? new Region.Cuboid(area) : region, mask), RunOptions.DEFAULT,
                    cutListener, removal);
        } catch (EditRejected | RuntimeException e) {
            abandoned.set(true); // the refusal is the answer; the clipboard being built is dropped
            throw e;
        }
    }

    /** The cells of {@code region} inside {@code area} that {@code accepts} takes, read from {@code view}. */
    private static CellSet acceptedCells(Region region, Box area, WorldReader view, CellPredicate accepts) {
        CellSet.Builder cells = CellSet.builder();
        for (int y = area.min().y(); y <= area.max().y(); y++) {
            for (int z = area.min().z(); z <= area.max().z(); z++) {
                for (int x = area.min().x(); x <= area.max().x(); x++) {
                    if (region.contains(x, y, z) && accepts.test(x, y, z, view.get(x, y, z))) cells.add(x, y, z);
                }
            }
        }
        return cells.build();
    }

    /**
     * Adds to {@code snapshot} the sections around it (all 26 neighbours) that are inside the build height and in a loaded
     * chunk, for a global mask that reads neighbours; the snapshot reader then counts only the chunks it holds as loaded.
     */
    private static void captureHalo(Long2ObjectOpenHashMap<SectionBuffer> snapshot, FabricWorldReader reader,
                                    ServerWorld world, LongConsumer capture) {
        LongOpenHashSet halo = new LongOpenHashSet();
        int bottom = world.getBottomSectionCoord(), top = world.getTopSectionCoord();
        for (long key : snapshot.keySet().toLongArray()) {
            int sx = BlockBuffer.keyX(key), sy = BlockBuffer.keyY(key), sz = BlockBuffer.keyZ(key);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int y = sy + dy;
                        if (y < bottom || y >= top) continue;
                        long next = BlockBuffer.key(sx + dx, y, sz + dz);
                        if (!snapshot.containsKey(next) && reader.isLoaded(sx + dx, sz + dz)) halo.add(next);
                    }
                }
            }
        }
        halo.forEach(capture);
    }

    /**
     * Reads what a copy of {@code region} takes of its entities (looking in the chunk {@code columns} of
     * {@link EntityColumns#of(Region, int, int)}): those whose block is in the region, inside {@code area} (the region
     * clipped to the build height; positions are relative to its corner, the clipboard's) and passes the copy's mask
     * (tested on the block the entity belongs to, {@link FabricEntities#cell}). Returns their snapshots, and
     * for a cut also their states and UUIDs (its erase removes them). {@code TOO_LARGE} past
     * {@code entities.maxPerClipboard} entities, passengers counted (with {@code limit.bypass}, past
     * {@value #MAX_BYPASS_ENTITIES}), or past {@value #MAX_ENTITY_BYTES} bytes of entity data for anyone: the snapshots
     * are made in this tick.
     */
    private void takeEntities(ServerPlayerEntity p, ServerWorld world, Region region, long[] columns, Box area,
                              EntityFilter filter, CellPredicate mask, boolean trusted, boolean cut,
                              List<EntitySnapshot> taken, List<EntityState> states, List<UUID> ids) throws EditRejected {
        if (filter == EntityFilter.NONE) return;
        FabricWorldReader reader = runtime.reader(world);
        List<Entity> found = new ArrayList<>();
        long total = 0;
        for (Entity entity : FabricEntities.inRegion(world, region, filter, columns)) {
            net.minecraft.util.math.BlockPos cell = FabricEntities.cell(entity);
            int x = cell.getX(), y = cell.getY(), z = cell.getZ();
            if (!area.contains(x, y, z) || !mask.test(x, y, z, reader.get(x, y, z))) continue;
            found.add(entity);
            total += FabricEntities.takenCount(entity, filter);
        }
        long max = permissions.has(p, Perm.LIMIT_BYPASS) ? MAX_BYPASS_ENTITIES : config().entities.maxPerClipboard;
        if (total > max) {
            throw new EditRejected(RejectReason.TOO_LARGE, total + " entities > " + max + " (or set Entities to None)");
        }
        long bytes = 0;
        for (Entity entity : found) {
            EntitySnapshot snapshot;
            try {
                snapshot = FabricEntities.snapshot(entity, filter, area.min(), trusted);
            } catch (IllegalArgumentException e) {
                throw new EditRejected(RejectReason.TOO_LARGE, "a " + FabricEntities.typeId(entity)
                        + " holds too much data to copy");
            }
            if (snapshot == null) continue;
            bytes += snapshot.estimatedBytes();
            if (bytes > MAX_ENTITY_BYTES) {
                throw new EditRejected(RejectReason.TOO_LARGE, "the entities hold more than " + MAX_ENTITY_BYTES
                        + " bytes of data (or set Entities to None)");
            }
            taken.add(snapshot);
            if (!cut) continue;
            EntityState state = FabricEntities.state(entity, filter);
            if (state != null) {
                states.add(state);
                ids.add(entity.getUuid());
            }
        }
    }

    // ================================================================== previews and export

    @Override
    public void preview(ServerPlayerEntity p, SourceRef source, Reply<Outbound> callerReply) throws EditRejected {
        checkThread();
        Objects.requireNonNull(source);
        var reply = once(Objects.requireNonNull(callerReply));
        requireBasics(p);
        switch (source) {
            case SourceRef.Clipboard ref -> {
                PlayerClipboards.Held held = edits.clipboards().find(p.getUuid(), ref.id())
                        .orElseThrow(() -> new EditRejected(RejectReason.INVALID, "unknown clipboard"));
                Clipboard clipboard = held.clipboard();
                TreeMap<String, String> meta = new TreeMap<>();
                meta.put("format", PreviewPayload.FORMAT_NAME);
                meta.put("clipboardId", held.id().toString());
                meta.put("contentHash", clipboard.contentHash().hex());
                offThread(slots.acquire(p.getUuid(), false, false), () -> previewPayload(clipboard),
                        payload -> reply.done(new Outbound(StreamKind.CLIPBOARD_PREVIEW, payload, meta)), reply::failed);
            }
            case SourceRef.Asset ref -> {
                String hash = ref.contentHash();
                Library.Viewer viewer = edits.libraryViewer(p);
                var cached = edits.assets().get(hash).filter(asset -> library.mayRead(asset.path(), viewer));
                if (cached.isPresent()) {
                    AssetCache.Asset asset = cached.get();
                    offThread(slots.acquire(p.getUuid(), false, false), () -> previewPayload(asset.clipboard()),
                            payload -> reply.done(assetPreview(asset, payload, List.of())), reply::failed);
                    return;
                }
                SchematicCodec.Limits limits = importLimits(p);
                long seen = edits.assets().version(); // a library change while this runs makes its path stale
                offThread(slots.acquire(p.getUuid(), true, false), () -> {
                            LibraryPath path = library.find(hash, viewer)
                                    .orElseThrow(() -> new EditRejected(RejectReason.INVALID, "unknown asset " + hash));
                            Library.FileData data = library.read(path, viewer);
                            if (!data.sha256().equals(hash)) throw new EditRejected(RejectReason.INVALID, path + " changed");
                            Parsed parsed = parse(data.bytes(), limits);
                            library.remember(path, hash, info(parsed.clipboard(), parsed.schematic().metadata()));
                            AssetCache.Asset asset = new AssetCache.Asset(hash, path,
                                    parsed.clipboard().withSource("library:" + path),
                                    parsed.schematic().metadata().sculptory());
                            return new LoadedPreview(asset, previewPayload(asset.clipboard()), parsed.notices());
                        },
                        loaded -> {
                            edits.assets().putIfUnchanged(loaded.asset(), seen);
                            reply.done(assetPreview(loaded.asset(), loaded.payload(), loaded.notices()));
                        },
                        reply::failed);
            }
        }
    }

    private record LoadedPreview(AssetCache.Asset asset, byte[] payload, List<S2C.Notice> notices) {}

    private static Outbound assetPreview(AssetCache.Asset asset, byte[] payload, List<S2C.Notice> notices) {
        TreeMap<String, String> meta = new TreeMap<>();
        meta.put("format", PreviewPayload.FORMAT_NAME);
        meta.put("contentHash", asset.hash());
        meta.put("path", asset.path().toString());
        meta.put("clipboardHash", asset.clipboard().contentHash().hex());
        return new Outbound(StreamKind.ASSET_PREVIEW, payload, meta, notices);
    }

    private static byte[] previewPayload(Clipboard clipboard) throws EditRejected {
        byte[] payload = PreviewPayload.encode(clipboard, FabricEntities::size);
        if (payload.length > MAX_PREVIEW_BYTES) {
            throw new EditRejected(RejectReason.TOO_LARGE, "preview of " + payload.length + " bytes");
        }
        return payload;
    }

    @Override
    public void export(ServerPlayerEntity p, UUID clipboardId, SchematicFormat format, Reply<Outbound> callerReply)
            throws EditRejected {
        checkThread();
        Objects.requireNonNull(clipboardId);
        Objects.requireNonNull(format);
        var reply = once(Objects.requireNonNull(callerReply));
        requireBasics(p);
        require(p, Perm.SCHEMATIC_EXPORT);
        PlayerClipboards.Held held = edits.clipboards().find(p.getUuid(), clipboardId)
                .orElseThrow(() -> new EditRejected(RejectReason.INVALID, "unknown clipboard"));
        Clipboard clipboard = held.clipboard();
        requireStorableBox(p, clipboard, format);
        boolean sanitize = !permissions.mayWriteOperatorNbt(p);
        EntityTypeRules rules = EntityTypeRules.scan(p.getServerWorld());
        SchematicMetadata metadata = metadata(p, "clipboard", clipboard);
        TreeMap<String, String> meta = new TreeMap<>();
        meta.put("clipboardId", held.id().toString());
        meta.put("fileName", "clipboard" + format.extension());
        meta.put("format", format.name());
        meta.put("dataVersion", Integer.toString(fixes.targetDataVersion()));
        meta.put("contentHash", clipboard.contentHash().hex());
        offThread(slots.acquire(p.getUuid(), false, false), () -> {
                    Leaving clean = leaving(clipboard, sanitize, rules);
                    byte[] bytes = encode(format, clean.clipboard(), metadata);
                    if (bytes.length > MAX_EXPORT_BYTES) {
                        throw new EditRejected(RejectReason.TOO_LARGE, "schematic of " + bytes.length + " bytes");
                    }
                    return new Outbound(StreamKind.SCHEM_FILE, bytes, meta, withFormatNotices(format, clean));
                },
                reply::done, reply::failed);
    }

    // ================================================================== uploads

    @Override
    public Upload beginUpload(ServerPlayerEntity p, String fileName, long totalBytes) throws EditRejected {
        checkThread();
        requireBasics(p);
        require(p, Perm.SCHEMATIC_IMPORT);
        long max = config().limits.maxUploadBytes;
        if (totalBytes < 1) throw new EditRejected(RejectReason.INVALID, "empty upload");
        if (totalBytes > max) throw new EditRejected(RejectReason.TOO_LARGE, totalBytes + " > " + max + " bytes");
        // Reserved now, so a transfer is never wasted on a refusal for being busy once it arrives.
        RequestSlots.Lease lease = slots.acquire(p.getUuid(), true, false);
        long connection = edits.connection(p.getUuid());
        String source = "upload:" + clip(fileName == null ? "" : fileName, 64);
        return new Upload() {
            /** The lease went to the executor (which releases it) or was released: nothing left to do. */
            private boolean handedOff;

            @Override
            public long maxBytes() {
                return totalBytes;
            }

            /**
             * Checks the player again and hands the bytes to the executor. Until the hand-off (including when a
             * check throws), the lease stays with this upload, and {@link #abort()} releases it.
             */
            @Override
            public void completed(byte[] bytes, Reply<ClipboardInfo> reply) {
                checkThread();
                Objects.requireNonNull(bytes);
                Reply<ClipboardInfo> once = once(reply);
                if (handedOff) return;
                try {
                    requireBasics(p);
                    require(p, Perm.SCHEMATIC_IMPORT);
                    if (bytes.length > max) throw new EditRejected(RejectReason.TOO_LARGE, bytes.length + " > " + max + " bytes");
                } catch (EditRejected e) {
                    abort();
                    once.failed(e.reason(), e.getMessage());
                    return;
                }
                SchematicCodec.Limits limits = importLimits(p);
                handedOff = true;
                try {
                    offThread(lease, () -> parse(bytes, limits),
                            parsed -> install(p, connection, parsed.clipboard().withSource(source), parsed.notices(),
                                    once),
                            once::failed);
                } catch (EditRejected e) {
                    once.failed(e.reason(), e.getMessage()); // offThread released the lease
                }
            }

            @Override
            public void abort() {
                if (handedOff) return;
                handedOff = true;
                lease.release();
            }
        };
    }

    // ================================================================== selection uploads (regions)

    @Override
    public SelectionUpload beginSelectionUpload(ServerPlayerEntity p, Sha256 hash, Box bounds, long cells,
                                                long totalBytes) throws EditRejected {
        checkThread();
        Objects.requireNonNull(hash);
        Objects.requireNonNull(bounds);
        requireSelectionUpload(p);
        if (cells < 1 || cells > bounds.volume() || totalBytes < 1) {
            throw new EditRejected(RejectReason.INVALID, "a selection of " + cells + " cells in " + totalBytes + " bytes");
        }
        CellSet.Limits limits = selectionLimits();
        if (cells > limits.maxCells()) {
            throw new EditRejected(RejectReason.TOO_LARGE, cells + " > " + limits.maxCells() + " blocks in a selection");
        }
        long maxBytes = Math.min(limits.maxCompressedBytes(), config().limits.maxUploadBytes);
        if (totalBytes > maxBytes) throw new EditRejected(RejectReason.TOO_LARGE, totalBytes + " > " + maxBytes + " bytes");
        // A request slot, not the clipboard slot: a selection may go up while a copy is being made.
        RequestSlots.Lease lease = slots.acquire(p.getUuid(), false, false);
        long storeBytes = config().limits.maxSelectionStoreBytes;
        long totalStoreBytes = config().limits.maxSelectionStoreBytesTotal;
        return new SelectionUpload() {
            /** The lease went to the executor (which releases it) or was released: nothing left to do. */
            private boolean handedOff;

            @Override
            public long maxBytes() {
                return totalBytes;
            }

            @Override
            public long storeBytes() {
                return storeBytes;
            }

            @Override
            public long totalStoreBytes() {
                return totalStoreBytes;
            }

            /** Checks the player again and hands the bytes to the executor, as {@link Upload#completed} does. */
            @Override
            public void completed(byte[] bytes, Reply<CellSet> reply) {
                checkThread();
                Objects.requireNonNull(bytes);
                Reply<CellSet> once = once(reply);
                if (handedOff) return;
                try {
                    requireSelectionUpload(p);
                    if (bytes.length > totalBytes) {
                        throw new EditRejected(RejectReason.TOO_LARGE, bytes.length + " > " + totalBytes + " bytes");
                    }
                } catch (EditRejected e) {
                    abort();
                    once.failed(e.reason(), e.getMessage());
                    return;
                }
                handedOff = true;
                try {
                    offThread(lease, () -> decodeSelection(bytes, limits, hash, bounds, cells), once::done, once::failed);
                } catch (EditRejected e) {
                    once.failed(e.reason(), e.getMessage()); // offThread released the lease
                }
            }

            @Override
            public void abort() {
                if (handedOff) return;
                handedOff = true;
                lease.release();
            }
        };
    }

    /** The decoding caps of an uploaded selection, from the config. */
    CellSet.Limits selectionLimits() {
        return CellSet.Limits.of(config().limits.maxSelectionCells, config().limits.maxSelectionSections);
    }

    private void requireSelectionUpload(ServerPlayerEntity p) throws EditRejected {
        if (!config().editingEnabled) throw new EditRejected(RejectReason.DISABLED);
        require(p, Perm.USE);
        // A selection serves region ops and copies: either right will do.
        if (!permissions.has(p, Perm.REGION)) require(p, Perm.CLIPBOARD);
    }

    /**
     * Decodes an uploaded selection off the server thread: {@code INVALID} for malformed bytes or a set other than the
     * one announced (hash, bounds, cells), {@code TOO_LARGE} over the caps.
     */
    static CellSet decodeSelection(byte[] bytes, CellSet.Limits limits, Sha256 hash, Box bounds, long cells)
            throws EditRejected {
        CellSet set;
        try {
            set = CellSet.decode(bytes, limits);
        } catch (CellSetFormatException e) {
            throw new EditRejected(e.tooLarge() ? RejectReason.TOO_LARGE : RejectReason.INVALID,
                    "the selection could not be read: " + e.getMessage());
        }
        if (set.isEmpty() || set.size() != cells || !set.bounds().equals(bounds) || !set.hash().equals(hash)) {
            throw new EditRejected(RejectReason.INVALID, "the selection is not the one announced");
        }
        return set;
    }

    // ================================================================== generated uploads (generators)

    /**
     * A sparse clipboard made on the client: {@code use},
     * {@code clipboard} and {@code region}. It may hold any plain block state the server knows, as a Fill may place one,
     * so it needs what a Fill needs ({@code region}) besides what the paste that follows needs ({@code clipboard}); not
     * {@code schematic.import}: nothing here is a file. Then the cells within {@code maxClipboardVolume} unless
     * {@code limit.bypass}, the bytes within {@code maxUploadBytes}, and the player's clipboard slot from the grant to
     * the answer, as for a {@code .schem} upload. The nodes are checked again when the payload has arrived.
     */
    @Override
    public Upload beginGeneratedUpload(ServerPlayerEntity p, Box bounds, long cells, long totalBytes) throws EditRejected {
        checkThread();
        Objects.requireNonNull(bounds);
        requireGenerate(p);
        if (cells < 1 || cells > bounds.volume() || totalBytes < 1) {
            throw new EditRejected(RejectReason.INVALID, "a generated clipboard of " + cells + " cells in " + totalBytes + " bytes");
        }
        // limit.bypass lifts the cell cap as it lifts the volume limits (the decode's memory stays bounded by the section
        // cap and the upload bytes).
        long maxCells = permissions.has(p, Perm.LIMIT_BYPASS) ? Long.MAX_VALUE : config().limits.maxClipboardVolume;
        if (cells > maxCells) throw new EditRejected(RejectReason.TOO_LARGE, cells + " > " + maxCells + " blocks");
        long maxBytes = config().limits.maxUploadBytes;
        if (totalBytes > maxBytes) throw new EditRejected(RejectReason.TOO_LARGE, totalBytes + " > " + maxBytes + " bytes");
        RequestSlots.Lease lease = slots.acquire(p.getUuid(), true, false);
        long connection = edits.connection(p.getUuid());
        return new Upload() {
            private boolean handedOff;

            @Override
            public long maxBytes() {
                return totalBytes;
            }

            @Override
            public void completed(byte[] bytes, Reply<ClipboardInfo> reply) {
                checkThread();
                Objects.requireNonNull(bytes);
                Reply<ClipboardInfo> once = once(reply);
                if (handedOff) return;
                long max;
                try {
                    requireGenerate(p);
                    if (bytes.length > totalBytes) {
                        throw new EditRejected(RejectReason.TOO_LARGE, bytes.length + " > " + totalBytes + " bytes");
                    }
                    max = permissions.has(p, Perm.LIMIT_BYPASS) ? Long.MAX_VALUE : config().limits.maxClipboardVolume;
                } catch (EditRejected e) {
                    abort();
                    once.failed(e.reason(), e.getMessage());
                    return;
                }
                StateSpace states = runtime.states();
                // The section cap bounds the decode's memory (bitmaps and buffers per section) whatever the cell cap:
                // the selection uploads' cap, which limit.bypass does not lift either.
                int maxSections = config().limits.maxSelectionSections;
                handedOff = true;
                try {
                    offThread(lease, () -> generatedClipboard(decodeGenerated(bytes, states, bounds, cells, max, maxSections),
                            states),
                            clipboard -> {
                                // Asked again once decoded (the nodes may have changed meanwhile), as for the grant.
                                try {
                                    requireGenerate(p);
                                } catch (EditRejected e) {
                                    once.failed(e.reason(), e.getMessage());
                                    return;
                                }
                                install(p, connection, clipboard, List.of(), once);
                            }, once::failed);
                } catch (EditRejected e) {
                    once.failed(e.reason(), e.getMessage()); // offThread released the lease
                }
            }

            @Override
            public void abort() {
                if (handedOff) return;
                handedOff = true;
                lease.release();
            }
        };
    }

    /**
     * Decodes a sparse upload off the server thread: {@code TOO_LARGE} over the caps, {@code INVALID} for anything
     * malformed, a state the server does not know, or a block-entity state.
     */
    static GeneratedSource decodeGenerated(byte[] bytes, StateSpace states, Box bounds, long cells, long maxCells,
                                           int maxSections) throws EditRejected {
        try {
            return SparseUpload.decode(bytes, states, bounds, cells, maxCells, maxSections);
        } catch (SparseUploadException e) {
            RejectReason reason = e.kind() == SparseUploadException.Kind.TOO_LARGE ? RejectReason.TOO_LARGE : RejectReason.INVALID;
            throw new EditRejected(reason, "the generated blocks could not be read: " + e.getMessage());
        }
    }

    /**
     * The clipboard of a decoded upload, exactly as a copy of a magic selection makes one: the box is the source's
     * bounds, local (0, 0, 0) is its minimum corner, the anchor is (0, 0, 0), absent cells are absent, no tiles.
     */
    static Clipboard generatedClipboard(GeneratedSource source, StateSpace states) {
        Box bounds = source.bounds();
        BlockPos min = bounds.min();
        Clipboard.Builder builder = Clipboard.builder(states, new BlockPos(bounds.sizeX(), bounds.sizeY(), bounds.sizeZ()))
                .anchor(BlockPos.ORIGIN)
                .source("generated");
        source.forEach((x, y, z, state) -> builder.set(x - min.x(), y - min.y(), z - min.z(), state));
        return builder.build();
    }

    // ================================================================== library

    /**
     * Lists a folder, or the virtual {@value LibraryPath#SHARED} folder (per-asset access: what other players granted
     * this one, with the entries' real paths, never writable).
     */
    @Override
    public void list(ServerPlayerEntity p, String folder, Reply<Listing> callerReply) throws EditRejected {
        checkThread();
        var reply = once(Objects.requireNonNull(callerReply));
        requireBasics(p);
        Library.Viewer viewer = edits.libraryViewer(p);
        boolean shared = folder.equals(LibraryPath.SHARED);
        LibraryPath path = shared ? null : folderPath(folder);
        if (!shared && !Library.mayReadArea(path, viewer)) throw new EditRejected(RejectReason.NO_PERMISSION, "not your folder");
        boolean writable = !shared && Library.mayChangeIn(path, viewer);
        offThread(slots.acquire(p.getUuid(), false, false),
                () -> shared ? library.sharedWithMe(viewer) : library.list(path, viewer),
                listing -> {
                    List<S2C.LibraryListing.Entry> entries = new ArrayList<>(listing.entries().size());
                    for (Library.Entry entry : listing.entries()) {
                        entries.add(new S2C.LibraryListing.Entry(entry.path(), entry.folder(), entry.bytes(), entry.sha256(),
                                entry.folder() ? S2C.LibraryListing.Entry.Kind.FOLDER
                                        : entry.kind() == LibraryPath.Kind.PALETTE ? S2C.LibraryListing.Entry.Kind.PALETTE
                                        : S2C.LibraryListing.Entry.Kind.SCHEMATIC, entry.restricted()));
                    }
                    reply.done(new Listing(folder, entries, listing.truncated(), writable));
                },
                reply::failed);
    }

    // ================================================================== library management (M4)

    /**
     * Renames or moves a file, or renames a folder in place ({@link Library#move}). Needs {@code use} and
     * {@code clipboard}, plus the right to change both paths ({@link Library#checkChange}: {@code library.write} for
     * the shared area, admin for another player's folder, nothing more for the player's own), checked here and again
     * by the library. Takes the player's save slot (one library write at a time). Loaded assets keep their hash and
     * take the new path.
     */
    @Override
    public void move(ServerPlayerEntity p, boolean folder, String from, String to, Reply<LibraryChange> callerReply)
            throws EditRejected {
        checkThread();
        var reply = once(Objects.requireNonNull(callerReply));
        requireBasics(p);
        LibraryPath source = folder ? folderPath(from) : anyFilePath(from);
        LibraryPath target = folder ? folderPath(to) : anyFilePath(to);
        Library.Viewer viewer = edits.libraryViewer(p);
        allowChange(source, viewer);
        allowChange(target, viewer);
        if (source.equals(target)) throw new EditRejected(RejectReason.INVALID, "the new name is the old one");
        if (source.kind() != target.kind()) {
            throw new EditRejected(RejectReason.INVALID, "a file keeps its kind (" + source.kind().extension() + ")");
        }
        if (!folder && !source.extension().equals(target.extension())) {
            throw new EditRejected(RejectReason.INVALID, "a file keeps its extension (" + source.extension() + ")");
        }
        if (folder && !source.parent().equals(target.parent())) {
            throw new EditRejected(RejectReason.INVALID, "folders are renamed in place, not moved");
        }
        AssetAccess before = library.restricted(source) ? library.accessOf(source) : null; // open: the area rule
        RequestSlots.Lease lease = slots.acquire(p.getUuid(), false, true);
        evictRestrictedUnder(source);
        offThread(lease, () -> {
                    library.move(source, target, viewer);
                    return new LibraryChange(folder, source.toString(), target.toString(), before);
                },
                change -> {
                    edits.assets().moved(source, target);
                    edits.scatterPlans().moved(source, target);
                    reply.done(change);
                },
                reply::failed);
    }

    /**
     * Per-asset access. Before a rename, move or delete runs on the I/O thread, the cached assets and held scatter
     * plans from restricted files it touches ({@code changed}, or anything inside that folder) are dropped on the
     * server thread: the grant leaves its old name at once over there, so a check by the old path in between would
     * find an open name. Open files keep their cache and plans (the old name stays open, rightly).
     */
    private void evictRestrictedUnder(LibraryPath changed) {
        edits.assets().removeIf(asset -> AssetCache.inIgnoringCase(asset.path(), changed) && library.restricted(asset.path()));
        edits.scatterPlans().dropUsing(path -> AssetCache.inIgnoringCase(path, changed) && library.restricted(path));
    }

    /**
     * Deletes a file into the library's trash, or an empty folder ({@link Library#delete}); rights as for
     * {@link #move}. Loaded assets from that file are dropped.
     */
    @Override
    public void delete(ServerPlayerEntity p, boolean folder, String path, Reply<LibraryChange> callerReply)
            throws EditRejected {
        checkThread();
        var reply = once(Objects.requireNonNull(callerReply));
        requireBasics(p);
        LibraryPath target = folder ? folderPath(path) : anyFilePath(path);
        Library.Viewer viewer = edits.libraryViewer(p);
        allowChange(target, viewer);
        AssetAccess before = library.restricted(target) ? library.accessOf(target) : null; // open: the area rule
        RequestSlots.Lease lease = slots.acquire(p.getUuid(), false, true);
        evictRestrictedUnder(target);
        offThread(lease, () -> {
                    library.delete(target, viewer);
                    return new LibraryChange(folder, target.toString(), "", before);
                },
                change -> {
                    edits.assets().removed(target);
                    edits.scatterPlans().removed(target);
                    reply.done(change);
                },
                reply::failed);
    }

    /** Creates a folder ({@link Library#createFolder}); rights as for {@link #move}. */
    @Override
    public void createFolder(ServerPlayerEntity p, String path, Reply<LibraryChange> callerReply) throws EditRejected {
        checkThread();
        var reply = once(Objects.requireNonNull(callerReply));
        requireBasics(p);
        LibraryPath folder = folderPath(path);
        Library.Viewer viewer = edits.libraryViewer(p);
        allowChange(folder, viewer);
        offThread(slots.acquire(p.getUuid(), false, true), () -> {
                    library.createFolder(folder, viewer);
                    return new LibraryChange(true, "", folder.toString());
                },
                reply::done, reply::failed);
    }

    /**
     * The change as {@code p} may see it: nothing without {@code use} and {@code clipboard} (or while editing is off);
     * a path in a player folder that is not theirs (and they are no admin) becomes {@code ""}.
     */
    @Override
    public Optional<LibraryChange> shownTo(ServerPlayerEntity p, LibraryChange change) {
        if (!config().editingEnabled || !permissions.has(p, Perm.USE) || !permissions.has(p, Perm.CLIPBOARD)) {
            return Optional.empty();
        }
        Library.Viewer viewer = edits.libraryViewer(p);
        // The source by its access before the change (its grant has moved or gone by now), the target as it is.
        String from = readable(change.from(), change.folder(), change.fromAccess(), viewer) ? change.from() : "";
        String to = readable(change.to(), change.folder(), null, viewer) ? change.to() : "";
        if (from.isEmpty() && to.isEmpty()) return Optional.empty();
        return Optional.of(new LibraryChange(change.folder(), from, to));
    }

    private boolean readable(String path, boolean folder, AssetAccess access, Library.Viewer viewer) {
        if (path.isEmpty()) return false;
        try {
            LibraryPath parsed = folder ? LibraryPath.folder(path) : LibraryPath.anyFile(path);
            return access == null ? library.mayRead(parsed, viewer) : Library.readableUnder(parsed, access, viewer);
        } catch (LibraryPathException e) {
            return false;
        }
    }

    // ================================================================== per-asset access

    /** Who may load the file; only who may change that may ask ({@link Library#access}). */
    @Override
    public void access(ServerPlayerEntity p, String path, Reply<AssetAccess> callerReply) throws EditRejected {
        checkThread();
        var reply = once(Objects.requireNonNull(callerReply));
        requireBasics(p);
        LibraryPath file = anyFilePath(path);
        Library.Viewer viewer = edits.libraryViewer(p);
        allowAccessChange(file, viewer);
        offThread(slots.acquire(p.getUuid(), false, false), () -> library.access(file, viewer), reply::done, reply::failed);
    }

    /**
     * Sets who may load the file ({@link Library#setAccess}); rights as for {@link #access}, checked here and again by
     * the library. Grantees the client named without a UUID are resolved first: online players on the server thread,
     * the rest through the server's user cache on the I/O thread (a name it never saw is asked of the session
     * service, which may take a moment); one that cannot be resolved refuses the whole change with {@code INVALID}.
     * Takes the player's save slot. Afterwards, every online player who lost the right to read the file loses their
     * held scatter plan if it used the file, as after a deletion.
     */
    @Override
    public void setAccess(ServerPlayerEntity p, String path, AssetAccess access, Reply<AccessChange> callerReply)
            throws EditRejected {
        checkThread();
        Objects.requireNonNull(access);
        var reply = once(Objects.requireNonNull(callerReply));
        requireBasics(p);
        LibraryPath file = anyFilePath(path);
        Library.Viewer viewer = edits.libraryViewer(p);
        allowAccessChange(file, viewer);
        List<AssetAccess.Grantee> resolved = new ArrayList<>(access.players().size());
        List<AssetAccess.Grantee> unresolved = new ArrayList<>();
        for (AssetAccess.Grantee grantee : access.players()) {
            if (grantee.uuid() != null) {
                // The name stored and shown is the server's, never the client's: the online player's now, else the
                // cache's later; only when neither knows the UUID does the sent name stand, and then it must be one.
                ServerPlayerEntity online = server.getPlayerManager().getPlayer(grantee.uuid());
                if (online != null) {
                    resolved.add(new AssetAccess.Grantee(grantee.uuid(), online.getGameProfile().getName()));
                } else if (PLAYER_NAME.matcher(grantee.name()).matches()) {
                    resolved.add(grantee);
                } else {
                    throw new EditRejected(RejectReason.INVALID, "not a player name: " + clip(grantee.name(), 64));
                }
                continue;
            }
            if (!PLAYER_NAME.matcher(grantee.name()).matches()) {
                throw new EditRejected(RejectReason.INVALID, "not a player name: " + clip(grantee.name(), 64));
            }
            ServerPlayerEntity online = server.getPlayerManager().getPlayer(grantee.name());
            if (online != null) {
                resolved.add(new AssetAccess.Grantee(online.getUuid(), online.getGameProfile().getName()));
            } else {
                unresolved.add(grantee);
            }
        }
        offThread(slots.acquire(p.getUuid(), false, true), () -> {
                    List<AssetAccess.Grantee> players = new ArrayList<>(resolved.size() + unresolved.size());
                    for (AssetAccess.Grantee grantee : resolved) players.add(knownName(grantee));
                    for (AssetAccess.Grantee grantee : unresolved) players.add(lookUp(grantee.name()));
                    AssetAccess next = withPlayers(access, players);
                    AssetAccess before = library.setAccess(file, next, viewer);
                    return new AccessChange(file.toString(), before, next);
                },
                change -> {
                    endPlansOfRevoked(file, change);
                    reply.done(change);
                },
                reply::failed);
    }

    /** A Minecraft player name, as the user cache may be asked for it. */
    private static final java.util.regex.Pattern PLAYER_NAME = java.util.regex.Pattern.compile("[A-Za-z0-9_]{1,16}");

    /** {@code access} with {@code players} in place of its own (repeated players merged; {@code INVALID} otherwise). */
    private static AssetAccess withPlayers(AssetAccess access, List<AssetAccess.Grantee> players) throws EditRejected {
        if (!access.restricted()) return access;
        Map<UUID, AssetAccess.Grantee> byUuid = new java.util.LinkedHashMap<>();
        for (AssetAccess.Grantee grantee : players) byUuid.putIfAbsent(grantee.uuid(), grantee);
        try {
            return AssetAccess.listed(new ArrayList<>(byUuid.values()));
        } catch (IllegalArgumentException e) {
            throw new EditRejected(RejectReason.INVALID, e.getMessage());
        }
    }

    /**
     * A grantee with a UUID under the name the server's user cache holds for it (off the server thread; the cache
     * alone, no lookup), else under the validated name sent.
     */
    private AssetAccess.Grantee knownName(AssetAccess.Grantee grantee) {
        net.minecraft.util.UserCache cache = server.getUserCache();
        Optional<com.mojang.authlib.GameProfile> profile = cache == null ? Optional.empty() : cache.getByUuid(grantee.uuid());
        String known = profile.map(com.mojang.authlib.GameProfile::getName).orElse(null);
        return known == null || known.isEmpty() || known.equals(grantee.name()) ? grantee
                : new AssetAccess.Grantee(grantee.uuid(), known);
    }

    /** A player by name through the server's user cache (off the server thread); unknown is {@code INVALID}. */
    private AssetAccess.Grantee lookUp(String name) throws EditRejected {
        net.minecraft.util.UserCache cache = server.getUserCache();
        Optional<com.mojang.authlib.GameProfile> profile = cache == null ? Optional.empty() : cache.findByName(name);
        if (profile.isEmpty() || profile.get().getId() == null) {
            throw new EditRejected(RejectReason.INVALID, "unknown player: " + name);
        }
        String known = profile.get().getName();
        return new AssetAccess.Grantee(profile.get().getId(), known == null || known.isEmpty() ? name : known);
    }

    /**
     * Drops the held scatter plans using {@code file} of the players who may no longer read it (a plan whose owner is
     * not online is dropped too: it would be swept anyway).
     */
    private void endPlansOfRevoked(LibraryPath file, AccessChange change) {
        for (UUID owner : edits.scatterPlans().ownersUsing(file)) {
            ServerPlayerEntity other = server.getPlayerManager().getPlayer(owner);
            boolean keeps = other != null && Library.readableUnder(file, change.after(), edits.libraryViewer(other));
            if (!keeps) edits.scatterPlans().dropIfUses(owner, file);
        }
    }

    /**
     * The access change as {@code p} may see it: the path twice when they may read the file afterwards, the path then
     * {@code ""} when they could before but no longer, nothing otherwise (or without {@code use} and
     * {@code clipboard}, or while editing is off).
     */
    @Override
    public Optional<LibraryChange> shownAccessChange(ServerPlayerEntity p, AccessChange change) {
        if (!config().editingEnabled || !permissions.has(p, Perm.USE) || !permissions.has(p, Perm.CLIPBOARD)) {
            return Optional.empty();
        }
        LibraryPath file;
        try {
            file = LibraryPath.anyFile(change.path());
        } catch (LibraryPathException e) {
            return Optional.empty();
        }
        Library.Viewer viewer = edits.libraryViewer(p);
        boolean before = Library.readableUnder(file, change.before(), viewer);
        boolean after = Library.readableUnder(file, change.after(), viewer);
        if (after) return Optional.of(new LibraryChange(false, change.path(), change.path()));
        if (before) return Optional.of(new LibraryChange(false, change.path(), ""));
        return Optional.empty();
    }

    /** {@link Library#mayChangeAccess}, as a refusal before any work. */
    private static void allowAccessChange(LibraryPath file, Library.Viewer viewer) throws EditRejected {
        if (Library.mayChangeAccess(file, viewer)) return;
        throw new EditRejected(RejectReason.NO_PERMISSION, file.owner().isEmpty()
                ? "changing who may load a shared entry needs sculptory.library.write (" + file + ")"
                : "another player's entry needs sculptory.admin (" + file + ")");
    }

    /**
     * Refuses at admission a load from a folder the player may not read at all ({@code NO_PERMISSION}, whatever the
     * name). A restricted entry the player may not read is left to {@link Library#read} on the I/O thread, which
     * answers exactly as it does for a missing file: an admission-time refusal would carry the reason prefix an
     * asynchronous one lacks, and so tell the names apart.
     */
    private void requireReadable(LibraryPath file, Library.Viewer viewer) throws EditRejected {
        if (library.mayRead(file, viewer)) return;
        LibraryException refusal = library.readRefusal(file, viewer);
        if (refusal.reason() == RejectReason.NO_PERMISSION) throw new EditRejected(refusal.reason(), refusal.getMessage());
    }

    /** {@link Library#checkChange}, as a refusal before any work. */
    private static void allowChange(LibraryPath path, Library.Viewer viewer) throws EditRejected {
        try {
            Library.checkChange(path, viewer);
        } catch (LibraryException e) {
            throw new EditRejected(e.reason(), e.getMessage());
        }
    }

    @Override
    public void load(ServerPlayerEntity p, String path, Reply<ClipboardInfo> callerReply) throws EditRejected {
        checkThread();
        var reply = once(Objects.requireNonNull(callerReply));
        requireBasics(p);
        LibraryPath file = filePath(path);
        Library.Viewer viewer = edits.libraryViewer(p);
        requireReadable(file, viewer);
        SchematicCodec.Limits limits = importLimits(p);
        long connection = edits.connection(p.getUuid());
        long seen = edits.assets().version(); // a library change while this runs makes its path stale
        offThread(slots.acquire(p.getUuid(), true, false), () -> {
                    Library.FileData data = library.read(file, viewer);
                    Parsed parsed = parse(data.bytes(), limits);
                    library.remember(file, data.sha256(), info(parsed.clipboard(), parsed.schematic().metadata()));
                    return new LoadedFile(data.sha256(), parsed);
                },
                loaded -> {
                    Clipboard clipboard = loaded.parsed().clipboard().withSource("library:" + file);
                    edits.assets().putIfUnchanged(new AssetCache.Asset(loaded.sha256(), file, clipboard,
                            loaded.parsed().schematic().metadata().sculptory()), seen);
                    install(p, connection, clipboard, loaded.parsed().notices(), reply);
                },
                reply::failed);
    }

    private record LoadedFile(String sha256, Parsed parsed) {}

    @Override
    public void save(ServerPlayerEntity p, UUID clipboardId, String path, Reply<Saved> callerReply) throws EditRejected {
        checkThread();
        Objects.requireNonNull(clipboardId);
        var reply = once(Objects.requireNonNull(callerReply));
        requireBasics(p);
        PlayerClipboards.Held held = edits.clipboards().find(p.getUuid(), clipboardId)
                .orElseThrow(() -> new EditRejected(RejectReason.INVALID, "unknown clipboard"));
        LibraryPath requested = filePath(path);
        Library.Viewer viewer = edits.libraryViewer(p);
        LibraryPath target = saveTarget(requested, viewer);
        if (target == null) throw new EditRejected(RejectReason.INVALID, "path too long for the player folder");
        if (!Library.mayWrite(target, viewer)) throw new EditRejected(RejectReason.NO_PERMISSION, "may not write " + target);
        Clipboard clipboard = held.clipboard();
        requireStorableBox(p, clipboard, target.format());
        boolean sanitize = !permissions.mayWriteOperatorNbt(p);
        EntityTypeRules rules = EntityTypeRules.scan(p.getServerWorld());
        SchematicMetadata metadata = metadata(p, target.stem(), clipboard);
        SchematicFormat format = target.format();
        offThread(slots.acquire(p.getUuid(), false, true), () -> {
                    Leaving clean = leaving(clipboard, sanitize, rules);
                    byte[] bytes = encode(format, clean.clipboard(), metadata);
                    String sha = library.write(target, bytes, viewer, info(clean.clipboard(), metadata));
                    return new Saved(target.toString(), sha, withFormatNotices(format, clean));
                },
                reply::done, reply::failed);
    }

    /**
     * Where a save lands: as requested for players who may write the shared library (or for paths already in the
     * players area), otherwise the same path under the player's own folder.
     */
    static LibraryPath saveTarget(LibraryPath requested, Library.Viewer viewer) {
        if (requested.inPlayersArea() || viewer.write() || viewer.admin()) return requested;
        return requested.under(viewer.player());
    }

    // ================================================================== palettes

    /**
     * Saves a block palette ({@link PaletteFile}) where {@link #save} would save an asset of that path
     * ({@link #saveTarget}: the player's own folder without {@code library.write}), replacing a palette of that name.
     * Needs {@code use} and {@code clipboard}. Every state is resolved against the server's state space on the server
     * thread, before any file work, and the file keeps this server's own state text; a state it doesn't know is
     * {@code INVALID} naming it. Takes the player's save slot.
     */
    @Override
    public void savePalette(ServerPlayerEntity p, String path, BlockPalette palette, Reply<LibraryChange> callerReply)
            throws EditRejected {
        checkThread();
        Objects.requireNonNull(palette);
        var reply = once(Objects.requireNonNull(callerReply));
        requireBasics(p);
        LibraryPath requested = palettePath(path);
        Library.Viewer viewer = edits.libraryViewer(p);
        LibraryPath target = saveTarget(requested, viewer);
        if (target == null) throw new EditRejected(RejectReason.INVALID, "path too long for the player folder");
        if (!Library.mayWrite(target, viewer)) throw new EditRejected(RejectReason.NO_PERMISSION, "may not write " + target);
        byte[] bytes;
        try {
            bytes = PaletteFile.encode(PaletteFile.canonical(palette, runtime.states()), fixes.targetDataVersion());
        } catch (PaletteFile.PaletteFormatException e) {
            throw new EditRejected(RejectReason.INVALID, "palette " + requested.name() + ": " + e.getMessage());
        }
        offThread(slots.acquire(p.getUuid(), false, true), () -> {
                    library.write(target, bytes, viewer, null);
                    return new LibraryChange(false, "", target.toString());
                },
                reply::done, reply::failed);
    }

    /**
     * Reads a library palette for the player (read access as for assets). Off the server thread: the file is read
     * (at most {@value PaletteFile#MAX_BYTES} bytes), parsed as untrusted ({@code INVALID} saying what is wrong), and
     * resolved against the server's state space ({@link PaletteFile#resolve}: states from an older game upgraded,
     * unknown ones left out and counted; {@code INVALID} when none is left).
     */
    @Override
    public void loadPalette(ServerPlayerEntity p, String path, Reply<LoadedPalette> callerReply) throws EditRejected {
        checkThread();
        var reply = once(Objects.requireNonNull(callerReply));
        requireBasics(p);
        LibraryPath file = palettePath(path);
        Library.Viewer viewer = edits.libraryViewer(p);
        requireReadable(file, viewer);
        StateSpace states = runtime.states();
        offThread(slots.acquire(p.getUuid(), false, false), () -> {
                    Library.FileData data = library.read(file, viewer);
                    PaletteFile.Loaded loaded;
                    try {
                        loaded = PaletteFile.resolve(PaletteFile.decode(data.bytes()), states, fixes);
                    } catch (PaletteFile.PaletteFormatException e) {
                        throw new EditRejected(RejectReason.INVALID, "palette " + file + ": " + e.getMessage());
                    }
                    return new LoadedPalette(file.toString(), loaded.palette(), loaded.dropped(), loaded.droppedStates());
                },
                reply::done, reply::failed);
    }

    // ================================================================== helpers

    /** A server-captured tile as untrusted content (a copy); other tiles are untrusted already. */
    static BlockEntityData untrusted(BlockEntityData tile) {
        return tile instanceof FabricTile captured && captured.serverCaptured()
                ? FabricTile.of(captured.typeId(), captured.copyNbt()) : tile;
    }

    /**
     * Installs a finished clipboard for a player still online on the connection that asked for it
     * ({@code connection}, {@link EngineEditService#connection}) and answers with its info. A clipboard finished after
     * the player left, even if they have reconnected since, is dropped: it would replace the new connection's clipboard
     * with one its client never asked for.
     */
    private void install(ServerPlayerEntity p, long connection, Clipboard clipboard, List<S2C.Notice> notices,
                         Reply<ClipboardInfo> reply) {
        if (server.getPlayerManager().getPlayer(p.getUuid()) == null || !edits.isConnection(p.getUuid(), connection)) {
            reply.failed(RejectReason.INVALID, "the player left");
            return;
        }
        PlayerClipboards.Held held = edits.clipboards().install(p.getUuid(), clipboard);
        reply.done(new ClipboardInfo(held.id(), clipboard.size(), clipboard.anchor(), clipboard.cellCount(),
                clipboard.estimatedBytes(), (int) Math.min(Integer.MAX_VALUE, clipboard.entityTotal()), notices));
    }

    /** A parsed file: the decoded schematic, its sanitized clipboard and the notices for the player. */
    record Parsed(Schematic schematic, Clipboard clipboard, List<S2C.Notice> notices) {}

    /**
     * Parses untrusted bytes (upload or library file) under {@code limits} and sanitizes them. Off the server thread.
     * Entities of types this game does not know, never places (players, items, projectiles, primed TNT, withers...) or
     * will not summon are left out, as roots or passengers ({@link FileEntities}), and counted with the entities the
     * file could not give; operator-only entity data is removed for everyone ({@link EntitySanitizer}). Both follow
     * {@link EntityTypeRules#current()}, which fails closed (every entity left out) before the server's scan.
     */
    private Parsed parse(byte[] bytes, SchematicCodec.Limits limits) throws IOException {
        Schematic schematic = SchematicFiles.read(new ByteArrayInputStream(bytes), runtime.states(), limits, fixes);
        TileSanitizer.Result clean = TileSanitizer.sanitize(schematic.clipboard());
        EntityTypeRules rules = EntityTypeRules.current();
        FileEntities.Result allowed = FileEntities.clean(clean.clipboard(),
                type -> rules.never(type) || !FabricEntities.knownType(type), rules::hanging);
        EntitySanitizer.Result entities = EntitySanitizer.sanitize(allowed.clipboard(), rules::operator);
        List<S2C.Notice> notices = new ArrayList<>(notices(schematic, allowed.skipped()));
        notices.addAll(sanitizeNotices(clean, NOTICE_IMPORT_OPERATOR_NBT));
        notices.addAll(entityNotices(entities, NOTICE_IMPORT_ENTITY_DATA));
        return new Parsed(schematic, entities.clipboard(), notices);
    }

    /** A clipboard leaving the server (export, save) and what was removed from it. */
    record Leaving(Clipboard clipboard, List<S2C.Notice> notices) {}

    /**
     * {@code clipboard} as it may leave the server with its player: with operator-only block-entity and entity data
     * removed when {@code sanitize} (players without the operator-NBT right), as it is otherwise.
     */
    static Leaving leaving(Clipboard clipboard, boolean sanitize, EntityTypeRules rules) {
        if (!sanitize) return new Leaving(clipboard, List.of());
        TileSanitizer.Result tiles = TileSanitizer.sanitize(clipboard);
        EntitySanitizer.Result entities = EntitySanitizer.sanitize(tiles.clipboard(), rules::operator);
        List<S2C.Notice> notices = new ArrayList<>(sanitizeNotices(tiles, NOTICE_EXPORT_OPERATOR_NBT));
        notices.addAll(entityNotices(entities, NOTICE_EXPORT_ENTITY_DATA));
        return new Leaving(entities.clipboard(), notices);
    }

    /** {@code [removed block entities, signs without click events]} when anything was removed. */
    static List<S2C.Notice> sanitizeNotices(TileSanitizer.Result result, String key) {
        if (!result.changed()) return List.of();
        return List.of(new S2C.Notice(S2C.Notice.Level.WARN, key,
                List.of(Integer.toString(result.dropped()), Integer.toString(result.signsCleaned()))));
    }

    /** {@code [entities that lost operator-only data]} when any did. */
    static List<S2C.Notice> entityNotices(EntitySanitizer.Result result, String key) {
        if (!result.changed()) return List.of();
        return List.of(new S2C.Notice(S2C.Notice.Level.WARN, key, List.of(Integer.toString(result.stripped()))));
    }

    /**
     * A Sponge or Litematica schematic stores every cell of its box (absent ones as air), so saving or exporting one
     * takes time and memory in proportion to the box, not to the blocks: the box must fit
     * {@code limits.maxClipboardVolume} unless the player has {@code limit.bypass}, as for a copy of that box, and
     * never over {@link #MAX_STORED_BOX}. Copies, loads and imports of players without {@code limit.bypass} already fit;
     * a generated clipboard, counted by its blocks, may not (two blocks far apart), and is refused {@code TOO_LARGE}
     * here, before any work. A structure file lists its present cells instead, one NBT compound each, all built in
     * memory before they are written: never more than {@link #MAX_STRUCTURE_CELLS} of them, {@code limit.bypass} or
     * not (a structure block loads {@value StructureCodec#MAX_STRUCTURE_BLOCK_SIDE} per side, about a tenth of that).
     */
    static void requireStorableBox(Clipboard clipboard, SchematicFormat format, long maxClipboardVolume, boolean bypass)
            throws EditRejected {
        if (format == SchematicFormat.STRUCTURE && clipboard.cellCount() > MAX_STRUCTURE_CELLS) {
            throw new EditRejected(RejectReason.TOO_LARGE, "a structure file lists every block: " + clipboard.cellCount()
                    + " > " + MAX_STRUCTURE_CELLS + " blocks, the most a structure file holds (save it as .schem or"
                    + " .litematic)");
        }
        if (clipboard.volume() > MAX_STORED_BOX) {
            throw new EditRejected(RejectReason.TOO_LARGE, "a schematic holds every block of its box: " + clipboard.volume()
                    + " > " + MAX_STORED_BOX + " blocks, the most any save or export writes");
        }
        if (clipboard.volume() > maxClipboardVolume && !bypass) {
            throw new EditRejected(RejectReason.TOO_LARGE, "a schematic holds every block of its box: " + clipboard.volume()
                    + " > " + maxClipboardVolume + " blocks");
        }
    }

    private void requireStorableBox(ServerPlayerEntity p, Clipboard clipboard, SchematicFormat format) throws EditRejected {
        requireStorableBox(clipboard, format, config().limits.maxClipboardVolume, permissions.has(p, Perm.LIMIT_BYPASS));
    }

    private byte[] encode(SchematicFormat format, Clipboard clipboard, SchematicMetadata metadata) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(1 << 20, clipboard.volume() / 4 + 1024));
        SchematicFiles.write(format, out, clipboard, metadata, fixes.targetDataVersion());
        return out.toByteArray();
    }

    /**
     * The notices of a clipboard leaving the server in {@code format}: what sanitizing removed and, for a structure file
     * larger than a vanilla structure block loads ({@value StructureCodec#MAX_STRUCTURE_BLOCK_SIDE} per side), a
     * warning that a structure block cannot load it (other tools and {@code /place} can).
     */
    static List<S2C.Notice> withFormatNotices(SchematicFormat format, Leaving clean) {
        BlockPos size = clean.clipboard().size();
        int max = StructureCodec.MAX_STRUCTURE_BLOCK_SIDE;
        if (format != SchematicFormat.STRUCTURE || (size.x() <= max && size.y() <= max && size.z() <= max)) {
            return clean.notices();
        }
        List<S2C.Notice> notices = new ArrayList<>(clean.notices());
        notices.add(new S2C.Notice(S2C.Notice.Level.WARN, NOTICE_EXPORT_STRUCTURE_SIZE, List.of(
                Integer.toString(size.x()), Integer.toString(size.y()), Integer.toString(size.z()),
                Integer.toString(max))));
        return notices;
    }

    /**
     * {@code SchematicCodec.Limits.untrustedUpload()} (NBT and heap caps, block entities at most 1 MiB each and
     * 32 MiB in all) with the player's volume limit: {@code maxClipboardVolume}, or only the NBT caps with
     * {@code limit.bypass}. Used for uploads and for every library file, since players write the library too.
     */
    private SchematicCodec.Limits importLimits(ServerPlayerEntity p) {
        // Asked on the server thread before every parse: the entity rules the parse follows are known by then.
        EntityTypeRules.scan(p.getServerWorld());
        boolean bypass = permissions.has(p, Perm.LIMIT_BYPASS);
        long max = bypass ? Long.MAX_VALUE : config().limits.maxClipboardVolume;
        SchematicCodec.Limits untrusted = SchematicCodec.Limits.untrustedUpload();
        // Entities as copies take them: entities.maxPerClipboard, unless limit.bypass.
        int entities = bypass ? SchematicCodec.Limits.DEFAULT_MAX_ENTITIES : config().entities.maxPerClipboard;
        return new SchematicCodec.Limits(untrusted.nbt(), max, untrusted.maxPaletteSize(), untrusted.maxBlockEntities(),
                untrusted.maxTileBytes(), untrusted.maxTotalTileBytes(), entities);
    }

    private static SchematicMetadata metadata(ServerPlayerEntity p, String name, Clipboard clipboard) {
        return new SchematicMetadata(name, p.getGameProfile().getName(), System.currentTimeMillis(), List.of(),
                new AssetInfo(List.of(), clipboard.anchor(), AssetInfo.ALL_ROTATIONS, 1));
    }

    private static Library.Info info(Clipboard clipboard, SchematicMetadata metadata) {
        BlockPos size = clipboard.size();
        List<String> tags = metadata.sculptory() == null ? List.of() : metadata.sculptory().tags();
        return new Library.Info(new int[] {size.x(), size.y(), size.z()}, clipboard.cellCount(), tags);
    }

    /**
     * What an import lost, as notices for the player (bounded: counts and a few names); {@code unknownEntities} entities
     * of types this game does not know count with the entities the file could not give.
     */
    static List<S2C.Notice> notices(Schematic schematic, int unknownEntities) {
        SchematicReport report = schematic.report();
        List<S2C.Notice> notices = new ArrayList<>();
        if (!report.unknownStates().isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Map.Entry<String, Long> entry : report.unknownStates().entrySet()) {
                if (names.size() == MAX_NOTICE_NAMES) break;
                names.add(clip(entry.getKey(), 200));
            }
            notices.add(new S2C.Notice(S2C.Notice.Level.WARN, NOTICE_PREFIX + "import_unknown_states",
                    List.of(Long.toString(report.unknownCells()), Integer.toString(report.unknownStates().size()),
                            String.join(", ", names))));
        }
        if (report.blockEntitiesSkipped() > 0) {
            notices.add(new S2C.Notice(S2C.Notice.Level.WARN, NOTICE_PREFIX + "import_block_entities_skipped",
                    List.of(Integer.toString(report.blockEntitiesSkipped()))));
        }
        if (report.entitiesSkipped() + unknownEntities > 0) {
            notices.add(new S2C.Notice(S2C.Notice.Level.WARN, NOTICE_PREFIX + "import_entities_skipped",
                    List.of(Integer.toString(report.entitiesSkipped() + unknownEntities))));
        }
        if (report.biomesSkipped()) {
            notices.add(new S2C.Notice(S2C.Notice.Level.INFO, NOTICE_PREFIX + "import_biomes_skipped", List.of()));
        }
        if (report.ticksSkipped() > 0) {
            notices.add(new S2C.Notice(S2C.Notice.Level.INFO, NOTICE_IMPORT_TICKS,
                    List.of(Integer.toString(report.ticksSkipped()))));
        }
        if (report.newerDataVersion()) {
            notices.add(new S2C.Notice(S2C.Notice.Level.WARN, NOTICE_PREFIX + "import_newer_version",
                    List.of(Integer.toString(schematic.dataVersion()))));
        }
        return notices;
    }

    private static LibraryPath folderPath(String text) throws EditRejected {
        try {
            return LibraryPath.folder(Objects.requireNonNull(text));
        } catch (LibraryPathException e) {
            throw new EditRejected(RejectReason.INVALID, "library path: " + e.getMessage());
        }
    }

    private static LibraryPath filePath(String text) throws EditRejected {
        try {
            return LibraryPath.file(Objects.requireNonNull(text));
        } catch (LibraryPathException e) {
            throw new EditRejected(RejectReason.INVALID, "library path: " + e.getMessage());
        }
    }

    /** A palette's path (palettes); never a schematic's. */
    private static LibraryPath palettePath(String text) throws EditRejected {
        try {
            return LibraryPath.palette(Objects.requireNonNull(text));
        } catch (LibraryPathException e) {
            throw new EditRejected(RejectReason.INVALID, "library path: " + e.getMessage());
        }
    }

    /** A file of either kind, for management (rename, move, delete work on schematics and palettes alike). */
    private static LibraryPath anyFilePath(String text) throws EditRejected {
        try {
            return LibraryPath.anyFile(Objects.requireNonNull(text));
        } catch (LibraryPathException e) {
            throw new EditRejected(RejectReason.INVALID, "library path: " + e.getMessage());
        }
    }

    private void requireBasics(ServerPlayerEntity p) throws EditRejected {
        if (!config().editingEnabled) throw new EditRejected(RejectReason.DISABLED);
        require(p, Perm.USE);
        require(p, Perm.CLIPBOARD);
    }

    /** What a generated upload needs: {@link #requireBasics} and {@code region}, as a Fill of those blocks would. */
    private void requireGenerate(ServerPlayerEntity p) throws EditRejected {
        requireBasics(p);
        require(p, Perm.REGION);
    }

    private void require(ServerPlayerEntity p, Perm node) throws EditRejected {
        if (!permissions.has(p, node)) throw new EditRejected(RejectReason.NO_PERMISSION, node.node());
    }

    private void checkThread() {
        if (!server.isOnThread()) throw new IllegalStateException("ServerClipboards must be used on the server thread");
    }

    private static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    /** Work for the I/O executor. */
    @FunctionalInterface
    interface Work<T> {
        T run() throws Exception;
    }

    /** A failure to report on the server thread. */
    @FunctionalInterface
    interface Failure {
        void failed(RejectReason reason, String detail);
    }

    /**
     * Runs {@code work} on the executor, then (on the server thread) releases {@code lease} and calls {@code then}
     * or {@code failure}. Exceptions map to reasons: {@code EditRejected} and {@code LibraryException} keep theirs,
     * limits ({@code SchematicException TOO_LARGE}, {@code NbtLimitException}) are {@code TOO_LARGE}, malformed
     * content is {@code INVALID}; anything else is logged and answered {@code INVALID} with a generic detail, since
     * exception text may contain server paths. If {@code then} throws, it is logged and {@code failure} answers
     * {@code INVALID "internal error"} (the replies are {@link #once} guarded, so an answer already given stands).
     * While the service is {@link #shutdown() closing}, only the lease is released.
     *
     * @throws EditRejected {@code QUEUE_FULL} when the executor refuses the work ({@code lease} is released and
     *     nothing will be answered)
     */
    private <T> void offThread(RequestSlots.Lease lease, Work<T> work, Consumer<T> then, Failure failure)
            throws EditRejected {
        Runnable task = () -> {
            T value;
            try {
                value = work.run();
            } catch (Throwable e) {
                RejectReason reason = reasonOf(e);
                String detail;
                if (reason == null || !safeMessage(e)) {
                    LOG.error("Sculptory: clipboard work failed", e);
                    detail = reason == null ? "internal error" : "unreadable data";
                    if (reason == null) reason = RejectReason.INVALID;
                } else {
                    detail = e.getMessage() == null ? reason.name() : e.getMessage();
                }
                RejectReason finalReason = reason;
                onServer(lease, () -> failure.failed(finalReason, detail));
                return;
            }
            onServer(lease, () -> {
                try {
                    then.accept(value);
                } catch (RuntimeException e) {
                    LOG.error("Sculptory: clipboard reply failed", e);
                    failure.failed(RejectReason.INVALID, "internal error");
                }
            });
        };
        try {
            io.execute(task);
        } catch (RejectedExecutionException e) {
            lease.release();
            throw new EditRejected(RejectReason.QUEUE_FULL, "the server is busy");
        }
    }

    /**
     * Releases the lease and runs {@code action} on the server thread. Once {@link #shutdown()} began, the server may
     * run this inline on an I/O thread: then only the lease is released, and no session or clipboard state is
     * touched.
     */
    private void onServer(RequestSlots.Lease lease, Runnable action) {
        if (closing) {
            lease.release();
            return;
        }
        server.execute(() -> {
            lease.release();
            if (closing) return;
            try {
                action.run();
            } catch (RuntimeException e) {
                LOG.error("Sculptory: clipboard reply failed", e);
            }
        });
    }

    /**
     * A reply that answers at most once: after {@code done} returned or {@code failed} was called, later calls are
     * ignored. A {@code done} that threw counts as unanswered, so a following {@code failed} still reaches the client.
     */
    static <T> Reply<T> once(Reply<T> reply) {
        if (reply instanceof Once<T>) return reply;
        return new Once<>(reply);
    }

    private static final class Once<T> implements Reply<T> {
        private final Reply<T> reply;
        private boolean answered;

        Once(Reply<T> reply) {
            this.reply = Objects.requireNonNull(reply);
        }

        @Override
        public void done(T value) {
            if (answered) return;
            reply.done(value);
            answered = true;
        }

        @Override
        public void failed(RejectReason reason, String detail) {
            if (answered) return;
            answered = true;
            reply.failed(reason, detail);
        }
    }

    private static RejectReason reasonOf(Throwable e) {
        return switch (e) {
            case EditRejected rejected -> rejected.reason();
            case LibraryException library -> library.reason();
            case SchematicException schem -> schem.kind() == SchematicException.Kind.TOO_LARGE
                    ? RejectReason.TOO_LARGE : RejectReason.INVALID;
            case NbtLimitException limit -> RejectReason.TOO_LARGE;
            case IOException io -> RejectReason.INVALID;
            default -> null;
        };
    }

    /** Messages written by this code base about content (never file-system paths). */
    private static boolean safeMessage(Throwable e) {
        return e instanceof EditRejected || e instanceof LibraryException || e instanceof SchematicException
                || e instanceof NbtException;
    }

    /**
     * The snapshot of a copy as a {@link WorldReader} for {@code Clipboard.copyOf} off the server thread: only the
     * snapshotted sections exist; everything else reads as air.
     */
    private static final class SnapshotReader implements WorldReader {
        private final StateSpace states;
        private final int bottomY;
        private final int topY;
        private final Long2ObjectOpenHashMap<SectionBuffer> sections;
        /** The chunk columns {@link #sections} holds, built on first use. */
        private volatile LongOpenHashSet columns;

        SnapshotReader(StateSpace states, int bottomY, int topY, Long2ObjectOpenHashMap<SectionBuffer> sections) {
            this.states = states;
            this.bottomY = bottomY;
            this.topY = topY;
            this.sections = sections;
        }

        @Override
        public StateSpace states() {
            return states;
        }

        @Override
        public int bottomY() {
            return bottomY;
        }

        @Override
        public int topYExclusive() {
            return topY;
        }

        /** A chunk it holds a section of (a global mask's neighbours elsewhere match nothing, as in a bulk edit). */
        @Override
        public boolean isLoaded(int cx, int cz) {
            if (columns == null) {
                LongOpenHashSet held = new LongOpenHashSet();
                for (long key : sections.keySet().toLongArray()) {
                    held.add(EditProgram.column(BlockBuffer.keyX(key), BlockBuffer.keyZ(key)));
                }
                columns = held;
            }
            return columns.contains(EditProgram.column(cx, cz));
        }

        @Override
        public int get(int x, int y, int z) {
            SectionBuffer section = sections.get(BlockBuffer.keyOfBlock(x, y, z));
            if (section == null) return states.air();
            int state = section.get(SectionBuffer.index(x & 15, y & 15, z & 15));
            return state < 0 ? states.air() : state;
        }

        @Override
        public BlockEntityData tile(int x, int y, int z) {
            SectionBuffer section = sections.get(BlockBuffer.keyOfBlock(x, y, z));
            return section == null ? null : section.tile(SectionBuffer.index(x & 15, y & 15, z & 15));
        }

        @Override
        public void copySection(int sx, int sy, int sz, SectionBuffer into) {
            into.clearAll();
            SectionBuffer section = sections.get(BlockBuffer.key(sx, sy, sz));
            if (section == null) {
                for (int i = 0; i < SectionBuffer.SIZE; i++) into.set(i, states.air());
                return;
            }
            for (int i = 0; i < SectionBuffer.SIZE; i++) {
                int state = section.get(i);
                into.set(i, state < 0 ? states.air() : state);
            }
            section.forEachTile(into::setTile);
        }
    }
}
