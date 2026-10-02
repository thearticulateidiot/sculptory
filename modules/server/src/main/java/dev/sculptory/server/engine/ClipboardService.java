package dev.sculptory.server.engine;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.Sha256;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.palette.BlockPalette;
import dev.sculptory.core.region.CellSet;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.schem.SchematicFormat;
import dev.sculptory.protocol.v2.AssetAccess;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.protocol.v2.S2C;
import dev.sculptory.protocol.v2.StreamKind;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.JobListener;
import dev.sculptory.server.engine.JobTicket;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Server clipboards, schematics and the asset library (M2), called by the protocol dispatcher on the server
 * thread. A request that is refused up front throws {@link EditRejected} and changes nothing. Everything else
 * answers through its {@link Reply}, on the server thread, exactly once, usually on a later tick: capture
 * finishing, file I/O and encoding run off the server thread.
 *
 * @param <P> the platform's player type
 */
public interface ClipboardService<P> {
    /** The answer to an asynchronous request; called on the server thread, exactly once. */
    interface Reply<T> {
        void done(T value);

        /** Nothing was produced; {@code detail} is for the log and a notice. */
        void failed(RejectReason reason, String detail);
    }

    /**
     * The player's clipboard after a copy, library load or upload.
     *
     * @param bytes estimated server memory held by the clipboard
     * @param notices what the import could not keep (unknown states, skipped entities...), for the player
     * @param entities the entities the clipboard holds
     */
    record ClipboardInfo(UUID clipboardId, BlockPos dims, BlockPos anchor, long cells, long bytes, int entities,
                         List<S2C.Notice> notices) {
        public ClipboardInfo {
            Objects.requireNonNull(clipboardId);
            Objects.requireNonNull(dims);
            Objects.requireNonNull(anchor);
            notices = List.copyOf(notices);
        }

        /** A clipboard without entities. */
        public ClipboardInfo(UUID clipboardId, BlockPos dims, BlockPos anchor, long cells, long bytes,
                             List<S2C.Notice> notices) {
            this(clipboardId, dims, anchor, cells, bytes, 0, notices);
        }
    }

    /**
     * A server-to-client stream to open: a preview or a schematic file.
     *
     * @param notices what was removed on the way (e.g. operator NBT from an asset), for the player
     */
    record Outbound(StreamKind kind, byte[] payload, SortedMap<String, String> meta, List<S2C.Notice> notices) {
        public Outbound {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(payload);
            meta = Collections.unmodifiableSortedMap(new TreeMap<>(meta));
            notices = List.copyOf(notices);
        }

        public Outbound(StreamKind kind, byte[] payload, SortedMap<String, String> meta) {
            this(kind, payload, meta, List.of());
        }
    }

    /**
     * A granted upload: it holds the player's clipboard slot from {@code UploadBegin} until it completes or is
     * aborted, so a transfer never ends in a refusal for being busy. Exactly one of {@link #completed} and
     * {@link #abort} takes effect; later calls do nothing. Server thread only.
     */
    interface Upload {
        /** The largest stream to accept. */
        long maxBytes();

        /** Every byte arrived intact: parse it (untrusted) into the player's clipboard. */
        void completed(byte[] bytes, Reply<ClipboardInfo> reply);

        /** The transfer failed, stalled or the player left: release the reservation. */
        void abort();
    }

    /**
     * One library folder; {@code truncated} when it held more entries than one listing returns; {@code writable} when
     * the player may create folders in it and rename, move and delete its entries (M4).
     */
    record Listing(String folder, List<S2C.LibraryListing.Entry> entries, boolean truncated, boolean writable) {
        public Listing {
            Objects.requireNonNull(folder);
            entries = List.copyOf(entries);
        }
    }

    /**
     * A library change made by a management request (M4), as the dispatcher sends it ({@code LibraryChanged}):
     * {@code from} is {@code ""} for a created folder, {@code to} is {@code ""} for a deleted entry.
     * {@code fromAccess} (per-asset access): who could load the file at {@code from} before the change, so the pushes
     * to other players ({@link #shownTo}) are computed from the access before it rather than after (a renamed or
     * deleted restricted entry is not named to players who could never see it); {@code null} for a folder, an open file, a new
     * file, or when unknown (the area rule then decides, which is what an open file's readability is).
     */
    record LibraryChange(boolean folder, String from, String to, AssetAccess fromAccess) {
        public LibraryChange {
            Objects.requireNonNull(from);
            Objects.requireNonNull(to);
            if (from.isEmpty() && to.isEmpty()) throw new IllegalArgumentException("A library change names a path");
        }

        public LibraryChange(boolean folder, String from, String to) {
            this(folder, from, to, null);
        }
    }

    /**
     * Where an asset was saved (which may differ from the requested path) and the SHA-256 of its file.
     *
     * @param notices what was removed before saving (operator NBT), for the player
     */
    record Saved(String path, String contentHash, List<S2C.Notice> notices) {
        public Saved {
            Objects.requireNonNull(path);
            Objects.requireNonNull(contentHash);
            notices = List.copyOf(notices);
        }

        public Saved(String path, String contentHash) {
            this(path, contentHash, List.of());
        }
    }

    /**
     * Copies the masked cells of {@code region} (a box, a shape or a cell set, never an unresolved
     * {@code Region.Uploaded}): the clipboard's box is the region's bounds, and cells outside the region are absent;
     * {@code origin} becomes the anchor. It takes along the entities {@code entities} takes whose block is in the
     * region and passes the mask. With {@code cut}, the same cells are then
     * erased, and those entities removed, by a job (one undoable history entry) that reports to {@code cutListener}.
     *
     * @return the erase job of a cut, or {@code null}
     */
    JobTicket copy(P p, Region region, BlockPos origin, boolean cut, CellMask mask,
                   EntityFilter entities, JobListener cutListener, Reply<ClipboardInfo> reply) throws EditRejected;

    /**
     * {@link #copy(P, Region, BlockPos, boolean, CellMask, EntityFilter, JobListener, Reply) Copies} a
     * box without entities.
     */
    default JobTicket copy(P p, Box box, BlockPos origin, boolean cut, CellMask mask,
                           JobListener cutListener, Reply<ClipboardInfo> reply) throws EditRejected {
        return copy(p, new Region.Cuboid(box), origin, cut, mask, EntityFilter.NONE, cutListener, reply);
    }

    /** A {@code CLIPBOARD_PREVIEW} (the player's clipboard) or {@code ASSET_PREVIEW} (a library asset) payload. */
    void preview(P p, SourceRef source, Reply<Outbound> reply) throws EditRejected;

    /**
     * The player's clipboard as a file of {@code format} ({@code SCHEM_FILE}: Sponge v3 {@code .schem}, Litematica
     * {@code .litematic} or a structure {@code .nbt}); the meta's {@code fileName} has the format's extension.
     */
    void export(P p, UUID clipboardId, SchematicFormat format, Reply<Outbound> reply)
            throws EditRejected;

    /** {@link #export(P, UUID, SchematicFormat, Reply)} as a Sponge {@code .schem}. */
    default void export(P p, UUID clipboardId, Reply<Outbound> reply) throws EditRejected {
        export(p, clipboardId, SchematicFormat.SPONGE, reply);
    }

    /**
     * Checks that the player may upload a schematic file ({@code .schem}, {@code .litematic} or {@code .nbt}: the
     * server reads it as what its content is) of {@code totalBytes} and reserves what its decoding will need.
     */
    Upload beginUpload(P p, String fileName, long totalBytes) throws EditRejected;

    /**
     * A granted selection upload ({@code SelectionUpload}): it holds a request
     * slot from the grant until it completes or is aborted. Exactly one of {@link #completed} and {@link #abort} takes
     * effect; later calls do nothing. Server thread only.
     */
    interface SelectionUpload {
        /** The largest stream to accept. */
        long maxBytes();

        /** What the player's uploaded sets may hold together on this connection ({@code estimatedBytes}). */
        long storeBytes();

        /** What every connection's uploaded sets may hold together; past it the least recently used anywhere go. */
        long totalStoreBytes();

        /** Every byte arrived intact: decode it (untrusted) off the server thread and check it is the set announced. */
        void completed(byte[] bytes, Reply<CellSet> reply);

        /** The transfer failed, stalled or the player left: release the reservation. */
        void abort();
    }

    /**
     * Regions. Checks that the player may upload a selection ({@code use}, and {@code region} or {@code clipboard}) of
     * {@code cells} cells in {@code totalBytes} compressed bytes, and reserves what its decoding will need; the set must
     * then have {@code hash}, {@code bounds} and {@code cells}. Refused with {@code DISABLED} by services without an
     * engine.
     */
    default SelectionUpload beginSelectionUpload(P p, Sha256 hash, Box bounds, long cells,
                                                 long totalBytes) throws EditRejected {
        throw new EditRejected(RejectReason.DISABLED);
    }

    /**
     * Generators. Checks that the player may upload a sparse
     * clipboard made on their client ({@code use}, {@code clipboard} and {@code region}: it may hold any plain block
     * state the server knows, as a Fill may place one, and pasting it needs {@code clipboard}; it is not a file import)
     * of {@code cells} cells with exactly
     * {@code bounds} in {@code totalBytes} bytes, and reserves the player's clipboard slot as {@link #beginUpload}
     * does. The payload ({@code core.generate.SparseUpload}) is decoded off the server thread once it has arrived and
     * becomes the player's clipboard, absent cells absent, no tiles, no entities. Refused with {@code DISABLED} by
     * services without an engine.
     */
    default Upload beginGeneratedUpload(P p, Box bounds, long cells, long totalBytes)
            throws EditRejected {
        throw new EditRejected(RejectReason.DISABLED);
    }

    void list(P p, String folder, Reply<Listing> reply) throws EditRejected;

    /** Loads a library asset into the player's clipboard. */
    void load(P p, String path, Reply<ClipboardInfo> reply) throws EditRejected;

    /** Saves the player's clipboard into the library. */
    void save(P p, UUID clipboardId, String path, Reply<Saved> reply) throws EditRejected;

    /**
     * M4. Renames or moves a library file, or renames a folder in place; never replaces anything. Permissions are
     * checked for both paths. Refused with {@code DISABLED} by services without a library.
     */
    default void move(P p, boolean folder, String from, String to, Reply<LibraryChange> reply)
            throws EditRejected {
        throw new EditRejected(RejectReason.DISABLED);
    }

    /** M4. Deletes a library file into the trash, or an empty folder. */
    default void delete(P p, boolean folder, String path, Reply<LibraryChange> reply)
            throws EditRejected {
        throw new EditRejected(RejectReason.DISABLED);
    }

    /** M4. Creates a library folder. */
    default void createFolder(P p, String path, Reply<LibraryChange> reply) throws EditRejected {
        throw new EditRejected(RejectReason.DISABLED);
    }

    /**
     * M4. {@code change} as {@code viewer} may see it, for pushing to an open Library window: a path they may not read
     * becomes {@code ""}; empty when they may see neither path, or may not use the library at all.
     */
    default Optional<LibraryChange> shownTo(P viewer, LibraryChange change) {
        return Optional.empty();
    }

    /**
     * Palettes. A library palette as this server knows it ({@code PaletteData}): the entries whose states it knows,
     * and how many it left out, the first few named.
     */
    record LoadedPalette(String path, BlockPalette palette, int dropped, List<String> droppedStates) {
        public LoadedPalette {
            Objects.requireNonNull(path);
            Objects.requireNonNull(palette);
            droppedStates = List.copyOf(droppedStates);
        }
    }

    /**
     * Palettes. Saves a block palette as a {@code .palette.json} library file (replacing a palette of that name), under
     * the library's rules as for a saved asset: the player's own folder unless they hold {@code library.write}, the
     * quotas, the player's save slot. Every state must be one the server knows ({@code INVALID} otherwise). Answers
     * with the change made ({@code from} {@code ""}, {@code to} the path written). Refused with {@code DISABLED} by
     * services without a library.
     */
    default void savePalette(P p, String path, BlockPalette palette, Reply<LibraryChange> reply)
            throws EditRejected {
        throw new EditRejected(RejectReason.DISABLED);
    }

    /** Palettes. Reads a library palette; states the server doesn't know are left out and counted. */
    default void loadPalette(P p, String path, Reply<LoadedPalette> reply) throws EditRejected {
        throw new EditRejected(RejectReason.DISABLED);
    }

    /**
     * Per-asset access. A change of who may load the file {@code path}: its access before and after, from which each
     * other player's view of the change follows ({@link #shownAccessChange}).
     */
    record AccessChange(String path, AssetAccess before, AssetAccess after) {
        public AccessChange {
            Objects.requireNonNull(path);
            Objects.requireNonNull(before);
            Objects.requireNonNull(after);
        }

        /** The change as the requester sees it: the entry changed in place. */
        public LibraryChange asLibraryChange() {
            return new LibraryChange(false, path, path);
        }
    }

    /**
     * Per-asset access. Who may load the library file {@code path}; only who may change that may ask
     * ({@code NO_PERMISSION} otherwise). Refused with {@code DISABLED} by services without a library.
     */
    default void access(P p, String path, Reply<AssetAccess> reply) throws EditRejected {
        throw new EditRejected(RejectReason.DISABLED);
    }

    /**
     * Per-asset access. Sets who may load the library file {@code path} (rights as for {@link #access}). Grantees
     * without a UUID are resolved by name (online players, then the server's user cache); one that cannot be is
     * {@code INVALID} and nothing changes. Takes the player's save slot. Answers with the access before and after.
     */
    default void setAccess(P p, String path, AssetAccess access, Reply<AccessChange> reply)
            throws EditRejected {
        throw new EditRejected(RejectReason.DISABLED);
    }

    /**
     * Per-asset access. {@code change} as {@code viewer} may see it, for pushing to an open Library window: the path
     * twice when they may read the file afterwards, the path then {@code ""} when they could before but no longer
     * (it vanishes for them), empty when neither (or they may not use the library at all).
     */
    default Optional<LibraryChange> shownAccessChange(P viewer, AccessChange change) {
        return Optional.empty();
    }

    /** Refuses everything with {@code DISABLED} (no engine running). */
    static <P> ClipboardService<P> disabled() {
        return new ClipboardService<>() {
            @Override
            public JobTicket copy(P p, Region region, BlockPos origin, boolean cut, CellMask mask,
                                  EntityFilter entities, JobListener cutListener, Reply<ClipboardInfo> reply)
                    throws EditRejected {
                throw new EditRejected(RejectReason.DISABLED);
            }

            @Override
            public void preview(P p, SourceRef source, Reply<Outbound> reply) throws EditRejected {
                throw new EditRejected(RejectReason.DISABLED);
            }

            @Override
            public void export(P p, UUID clipboardId, SchematicFormat format, Reply<Outbound> reply)
                    throws EditRejected {
                throw new EditRejected(RejectReason.DISABLED);
            }

            @Override
            public Upload beginUpload(P p, String fileName, long totalBytes) throws EditRejected {
                throw new EditRejected(RejectReason.DISABLED);
            }

            @Override
            public void list(P p, String folder, Reply<Listing> reply) throws EditRejected {
                throw new EditRejected(RejectReason.DISABLED);
            }

            @Override
            public void load(P p, String path, Reply<ClipboardInfo> reply) throws EditRejected {
                throw new EditRejected(RejectReason.DISABLED);
            }

            @Override
            public void save(P p, UUID clipboardId, String path, Reply<Saved> reply) throws EditRejected {
                throw new EditRejected(RejectReason.DISABLED);
            }
        };
    }
}
