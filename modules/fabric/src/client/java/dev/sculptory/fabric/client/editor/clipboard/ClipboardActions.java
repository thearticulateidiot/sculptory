package dev.sculptory.fabric.client.editor.clipboard;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.schem.SchematicFormat;
import dev.sculptory.core.transform.Mirror;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.tool.RegionWork;
import dev.sculptory.fabric.client.editor.tools.place.PlaceTool;
import dev.sculptory.fabric.client.session.ClipboardCache;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.ExportedFile;
import dev.sculptory.fabric.client.session.LibraryFolder;
import dev.sculptory.fabric.client.session.Notice;
import dev.sculptory.fabric.client.session.Permissions;
import dev.sculptory.fabric.client.session.Reply;
import dev.sculptory.fabric.client.session.SavedAsset;
import dev.sculptory.fabric.client.session.SessionNotices;
import dev.sculptory.fabric.client.session.Transfer;
import dev.sculptory.server.engine.Perm;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

/**
 * The clipboard, library and file actions behind the keys (Ctrl+C, Ctrl+X, Ctrl+V), the Selection, Clipboard and
 * Library windows and dropped files. Each checks what it can on the client (a selection, a clipboard, the permission,
 * the size limits), toasts why it cannot run, and reports success; the session toasts server refusals. Client thread
 * only; file reads and writes run on {@link Host#io()} and report back through {@link Host#mainThread()}.
 */
public final class ClipboardActions {
    /** What the actions need from the editor. */
    public interface Host {
        Optional<EditorSession> session();

        /** The selection's bounds. */
        Optional<Box> selection();

        /** The selection: a box, a shape or a cell set. */
        Optional<Region> selectionRegion();

        /** Exact cell counts of large shapes, off the client thread. */
        RegionWork regionWork();

        void notify(Notice notice);

        /** Starts the Place tool with a request (it says itself when it cannot); returns whether it started. */
        boolean place(PlaceTool.Request request);

        /** The Place tool while it is the active tool, for Rotate and Flip. */
        Optional<PlaceTool> activePlaceTool();

        String keyLabel(KeyAction action);

        /** {@code <gameDir>/sculptory/exports}. */
        Path exportDirectory();

        /** Where files are read and written. */
        Executor io();

        /** Back to the client thread. */
        Executor mainThread();

        /** Which entities Copy and Cut take (the Select tool's Entities setting). */
        default EntityFilter copyEntities() {
            return EntityFilter.DECORATIONS;
        }
    }

    private final Host host;
    /** The transform the next paste starts with (the Clipboard window's Rotate and Flip without a placement). */
    private Transform nextTransform = Transform.IDENTITY;
    /** Where the current clipboard came from, for the Clipboard window ("Copy", "Cut", a library path, a file). */
    private String source = "";
    private Optional<Path> lastExport = Optional.empty();
    private long exports;
    /** The format last chosen for an export or a save (the dialogs start with it). */
    private SchematicFormat format = SchematicFormat.SPONGE;

    public ClipboardActions(Host host) {
        this.host = Objects.requireNonNull(host);
    }

    /** Where the current clipboard came from, or "" when unknown. */
    public String source() {
        return source;
    }

    /** The transform the next paste starts with. */
    public Transform nextTransform() {
        return nextTransform;
    }

    /** The last file exported in this session. */
    public Optional<Path> lastExport() {
        return lastExport;
    }

    /** The format last chosen for an export or a save ({@code .schem} at first): the dialogs start with it. */
    public SchematicFormat format() {
        return format;
    }

    /** Remembers the format chosen in a dialog. */
    public void setFormat(SchematicFormat chosen) {
        format = Objects.requireNonNull(chosen);
    }

    // ---- Copy, cut, paste, move, stack ----

    /**
     * Copies (or cuts) the selection's blocks (exactly those of a shape or cell set) into the clipboard, anchored at
     * the bottom centre of its bounds, and toasts "Copied 12,345 blocks — Ctrl+V to paste".
     */
    public CompletionStage<Reply<ClipboardCache.Entry>> copySelection(boolean cut) {
        Optional<EditorSession> session = host.session();
        Optional<Region> selection = host.selectionRegion();
        if (session.isEmpty()) return refusedHere();
        if (selection.isEmpty()) {
            notify(Notice.Level.INFO, "sculptory.notice.select_first");
            return refusedHere();
        }
        Permissions permissions = session.get().permissions();
        if (!require(permissions, Perm.CLIPBOARD) || (cut && !require(permissions, Perm.REGION))) return refusedHere();
        Region region = selection.get();
        if (!RegionWork.countable(region)) {
            notify(Notice.Level.WARNING, "sculptory.notice.too_large", SessionNotices.count(RegionWork.atMost(region)),
                    SessionNotices.count(permissions.limits().maxClipboardVolume()));
            return refusedHere();
        }
        OptionalLong cells = host.regionWork().countNow(region);
        if (cells.isPresent() || RegionWork.atMost(region) <= clipboardLimit(permissions)) {
            return copyCounted(session.get(), region, cells, cut);
        }
        // A large shape: its exact count decides the limit, and is counted off the client thread first.
        notify(Notice.Level.INFO, "sculptory.notice.counting");
        return host.regionWork().count(region)
                .thenCompose(count -> copyCounted(session.get(), region, OptionalLong.of(count), cut));
    }

    /** The rest of {@link #copySelection}: {@code cells} is exact, or empty when the bounds are under the limit. */
    private CompletionStage<Reply<ClipboardCache.Entry>> copyCounted(EditorSession session, Region region,
            OptionalLong cells, boolean cut) {
        if (cells.isPresent() && cells.getAsLong() == 0) {
            notify(Notice.Level.INFO, "sculptory.notice.empty_shape");
            return refusedHere();
        }
        Permissions permissions = session.permissions();
        long volume = cells.orElse(RegionWork.atMost(region));
        if (volume > clipboardLimit(permissions)) {
            notify(Notice.Level.WARNING, "sculptory.notice.too_large", SessionNotices.count(volume),
                    SessionNotices.count(permissions.limits().maxClipboardVolume()));
            return refusedHere();
        }
        return session.copy(region, bottomCentre(region.bounds()), cut, host.copyEntities()).thenApply(reply -> {
            if (reply instanceof Reply.Ok<ClipboardCache.Entry> ok) {
                source = cut ? "Cut" : "Copy";
                nextTransform = Transform.IDENTITY;
                notify(Notice.Level.SUCCESS, cut ? "sculptory.notice.cut" : "sculptory.notice.copied",
                        SessionNotices.count(ok.value().cells()), host.keyLabel(KeyAction.PASTE));
            }
            return reply;
        });
    }

    /** Ctrl+V: places the current clipboard with the Place tool. */
    public boolean paste() {
        Optional<EditorSession> session = host.session();
        if (session.isEmpty()) return false;
        Optional<ClipboardCache.Entry> current = session.get().clipboards().current();
        if (current.isEmpty()) {
            notify(Notice.Level.INFO, "sculptory.notice.nothing_copied", host.keyLabel(KeyAction.COPY));
            return false;
        }
        if (!require(session.get().permissions(), Perm.CLIPBOARD)) return false;
        return host.place(new PlaceTool.Request.Paste(new SourceRef.Clipboard(current.get().clipboardId()),
                source.isEmpty() ? "clipboard" : source, nextTransform));
    }

    /** Moves the selection (its own blocks) with the Place tool. */
    public boolean move() {
        Optional<Region> region = selectionFor(Perm.REGION);
        return region.isPresent() && host.place(new PlaceTool.Request.Move(region.get()));
    }

    /** Stacks copies of the selection (its own blocks) with the Place tool. */
    public boolean stack() {
        Optional<Region> region = selectionFor(Perm.REGION);
        return region.isPresent() && host.place(new PlaceTool.Request.Stack(region.get()));
    }

    /** The Library's Place: previews the asset (which also lets the server paste it) in the Place tool. */
    public boolean placeAsset(String path, String contentHash) {
        Optional<EditorSession> session = host.session();
        if (session.isEmpty() || !require(session.get().permissions(), Perm.CLIPBOARD)) return false;
        Optional<SourceRef> asset = LibraryFolder.asset(contentHash);
        if (asset.isEmpty()) {
            // Not hashed yet by the server (listed before it was indexed): refresh the folder first.
            notify(Notice.Level.INFO, "sculptory.notice.asset_not_indexed", path);
            return false;
        }
        return host.place(new PlaceTool.Request.Paste(asset.get(), path, Transform.IDENTITY));
    }

    // ---- Rotate and flip without a placement (the Clipboard window) ----

    /** Turns the clipboard a quarter clockwise: the placement in progress, or else the next paste. */
    public void rotate() {
        rotate(1);
    }

    /**
     * Turns the clipboard by {@code quarterTurnsCw} (negative: counter-clockwise): the placement in progress, or else
     * the next paste (the menu bar's Rotate right and Rotate left).
     */
    public void rotate(int quarterTurnsCw) {
        Optional<PlaceTool> place = host.activePlaceTool();
        if (place.isPresent() && place.get().rotate(quarterTurnsCw)) return;
        nextTransform = nextTransform.compose(Transform.rotation(quarterTurnsCw));
    }

    /** Mirrors the clipboard east-west: the placement in progress, or else the next paste. */
    public void flip() {
        flip(Mirror.X);
    }

    /**
     * Mirrors the clipboard ({@link Mirror#X} east-west, {@link Mirror#Z} north-south): the placement in progress, or
     * else the next paste (the menu bar's Flip left–right and Flip front–back).
     */
    public void flip(Mirror mirror) {
        Optional<PlaceTool> place = host.activePlaceTool();
        if (place.isPresent() && place.get().mirror(mirror)) return;
        nextTransform = nextTransform.compose(new Transform(0, mirror));
    }

    /**
     * Flips the clipboard upside down, or back: the placement in progress (a stack's copies too), or else the next paste
     * (the Clipboard window's and Tool Settings' Flip upside down, and the menu bar's).
     */
    public void flipUpsideDown() {
        Optional<PlaceTool> place = host.activePlaceTool();
        if (place.isPresent() && place.get().flipUpsideDown()) return;
        nextTransform = nextTransform.compose(Transform.UPSIDE_DOWN);
    }

    /** Clear: forgets the clipboard on this client. */
    public void forget() {
        host.session().ifPresent(session -> session.clipboards().forgetCurrent());
        source = "";
        nextTransform = Transform.IDENTITY;
        notify(Notice.Level.INFO, "sculptory.notice.clipboard_cleared");
    }

    // ---- Library ----

    /** Loads a library asset into the clipboard. */
    public CompletionStage<Reply<ClipboardCache.Entry>> load(String path) {
        Optional<EditorSession> session = host.session();
        if (session.isEmpty() || !require(session.get().permissions(), Perm.CLIPBOARD)) return refusedHere();
        return session.get().libraryLoad(path).thenApply(reply -> {
            if (reply instanceof Reply.Ok<ClipboardCache.Entry> ok) {
                source = path;
                nextTransform = Transform.IDENTITY;
                notify(Notice.Level.SUCCESS, "sculptory.notice.loaded", path, SessionNotices.count(ok.value().cells()),
                        host.keyLabel(KeyAction.PASTE));
            }
            return reply;
        });
    }

    /**
     * Saves the current clipboard as {@code path} (as typed: {@code .schem} is added when it has no extension, the rules
     * are checked here); the path's extension is the file's format.
     */
    public CompletionStage<Reply<SavedAsset>> saveClipboard(String typed) {
        Optional<EditorSession> session = host.session();
        if (session.isEmpty()) return refusedHere();
        Optional<ClipboardCache.Entry> current = session.get().clipboards().current();
        if (current.isEmpty()) {
            notify(Notice.Level.INFO, "sculptory.notice.nothing_copied", host.keyLabel(KeyAction.COPY));
            return refusedHere();
        }
        Optional<String> path = checkedPath(typed);
        if (path.isEmpty()) return refusedHere();
        return save(session.get(), current.get(), path.get());
    }

    /** Copies the selection, then saves that clipboard as {@code path}. */
    public CompletionStage<Reply<SavedAsset>> saveSelection(String typed) {
        Optional<String> path = checkedPath(typed);
        if (path.isEmpty()) return refusedHere();
        return copySelection(false).thenCompose(reply -> reply instanceof Reply.Ok<ClipboardCache.Entry> ok
                ? save(host.session().orElseThrow(), ok.value(), path.get())
                : CompletableFuture.completedFuture(Reply.failed(Reply.Failure.CANCELLED, "")));
    }

    private CompletionStage<Reply<SavedAsset>> save(EditorSession session, ClipboardCache.Entry entry, String path) {
        SchematicFormat saved = LibraryPaths.formatOf(path);
        if (saved != null) format = saved;
        return session.saveAsset(entry.clipboardId(), path).thenApply(reply -> {
            if (reply instanceof Reply.Ok<SavedAsset> ok) {
                notify(Notice.Level.SUCCESS, "sculptory.notice.saved", ok.value().path());
            }
            return reply;
        });
    }

    /** The normalized path, or empty after a toast saying what is wrong with it. */
    private Optional<String> checkedPath(String typed) {
        String path = LibraryPaths.normalizeFile(typed);
        Optional<String> problem = LibraryPaths.fileProblem(path);
        if (problem.isPresent()) {
            notify(Notice.Level.WARNING, "sculptory.notice.bad_library_path", problem.get());
            return Optional.empty();
        }
        return Optional.of(path);
    }

    // ---- Export ----

    /** Exports the current clipboard in the last chosen format ({@link #format}) under its suggested name. */
    public Optional<Transfer<ExportedFile>> exportClipboard() {
        return exportClipboard(format, "");
    }

    /**
     * Exports the current clipboard as a file of {@code chosen} named {@code name} (made safe by
     * {@link ExportFiles#sanitize}; {@code ""} for the suggested name) to {@code <gameDir>/sculptory/exports/} and
     * toasts the path; the format is remembered for the next export or save.
     */
    public Optional<Transfer<ExportedFile>> exportClipboard(SchematicFormat chosen, String name) {
        setFormat(chosen);
        Optional<EditorSession> session = host.session();
        if (session.isEmpty() || !require(session.get().permissions(), Perm.SCHEMATIC_EXPORT)) return Optional.empty();
        Optional<ClipboardCache.Entry> current = session.get().clipboards().current();
        if (current.isEmpty()) {
            notify(Notice.Level.INFO, "sculptory.notice.nothing_copied", host.keyLabel(KeyAction.COPY));
            return Optional.empty();
        }
        return Optional.of(export(session.get(), current.get(), chosen, name));
    }

    /** Copies the selection, then exports it in the last chosen format. */
    public void exportSelection() {
        exportSelection(format, "");
    }

    /** Copies the selection, then exports it as a file of {@code chosen} named {@code name} (as for the clipboard). */
    public void exportSelection(SchematicFormat chosen, String name) {
        setFormat(chosen);
        Optional<EditorSession> session = host.session();
        if (session.isEmpty() || !require(session.get().permissions(), Perm.SCHEMATIC_EXPORT)) return;
        copySelection(false).thenAccept(reply -> {
            if (reply instanceof Reply.Ok<ClipboardCache.Entry> ok) export(session.get(), ok.value(), chosen, name);
        });
    }

    /** Exports started this session (the tutorial counts them). */
    public long exports() {
        return exports;
    }

    private Transfer<ExportedFile> export(EditorSession session, ClipboardCache.Entry entry, SchematicFormat chosen,
                                          String name) {
        exports++;
        String stem = name == null || name.isBlank() ? exportStem() : name;
        Transfer<ExportedFile> transfer = session.export(entry.clipboardId(), chosen);
        transfer.result().thenAccept(reply -> {
            if (reply instanceof Reply.Ok<ExportedFile> ok) {
                write(stem.isEmpty() ? ok.value().fileName() : stem, ok.value().bytes(), chosen);
            }
        });
        return transfer;
    }

    /** The name an export suggests: where the clipboard came from (a library path or file), else "clipboard". */
    public String exportName() {
        String stem = exportStem();
        return stem.isEmpty() ? ExportFiles.DEFAULT_STEM : stem;
    }

    /** A file name from where the clipboard came from (a library path or file), or "" for the server's default. */
    private String exportStem() {
        if (source.isEmpty() || source.equals("Copy") || source.equals("Cut")) return "";
        return LibraryPaths.stem(source);
    }

    private void write(String name, byte[] bytes, SchematicFormat chosen) {
        Path directory = host.exportDirectory();
        host.io().execute(() -> {
            try {
                Path written = ExportFiles.write(directory, name, bytes, chosen.extension());
                host.mainThread().execute(() -> {
                    lastExport = Optional.of(written);
                    notify(Notice.Level.SUCCESS, "sculptory.notice.exported", written.toString());
                });
            } catch (IOException | RuntimeException e) {
                String problem = e.getMessage() == null ? e.toString() : e.getMessage();
                host.mainThread().execute(() -> notify(Notice.Level.ERROR, "sculptory.notice.export_failed", problem));
            }
        });
    }

    // ---- Upload (dropped files) ----

    /**
     * Files dropped onto the editor: the first schematic ({@code .schem}, {@code .litematic} or {@code .nbt}; the server
     * reads it as what its content is) is uploaded; once the server has read it, it is the clipboard and the Place tool
     * opens with it.
     */
    public void upload(List<Path> files) {
        upload(files, null);
    }

    /**
     * As {@link #upload(List)}, but once the server has read the file, {@code then} gets its name and the new clipboard
     * instead of the Place tool (the Scatter tool adds it to its mix). {@code null} opens the Place tool.
     */
    public void upload(List<Path> files, BiConsumer<String, ClipboardCache.Entry> then) {
        Optional<EditorSession> session = host.session();
        if (session.isEmpty()) return;
        List<Path> schematics = files.stream()
                .filter(file -> file.getFileName() != null
                        && SchematicFormat.ofFileName(file.getFileName().toString()) != null)
                .toList();
        if (schematics.isEmpty()) {
            notify(Notice.Level.INFO, "sculptory.notice.not_schem");
            return;
        }
        Path file = schematics.get(0);
        String name = file.getFileName().toString();
        if (files.size() > 1) notify(Notice.Level.INFO, "sculptory.notice.upload_one", name);
        Permissions permissions = session.get().permissions();
        if (!require(permissions, Perm.SCHEMATIC_IMPORT)) return;
        long max = maxUploadBytes(permissions);
        host.io().execute(() -> {
            try {
                byte[] bytes = readAtMost(file, max);
                if (bytes == null) {
                    host.mainThread().execute(() -> notify(Notice.Level.WARNING, "sculptory.notice.file_too_large",
                            name, SessionNotices.count(max)));
                    return;
                }
                host.mainThread().execute(() -> sendUpload(name, bytes, then));
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                String problem = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                host.mainThread().execute(() -> notify(Notice.Level.WARNING, "sculptory.notice.file_unreadable", name,
                        problem));
            }
        });
    }

    /** The largest file uploaded: the server's limit, but never over {@link Transfer#MAX_UPLOAD_BYTES}. */
    public static long maxUploadBytes(Permissions permissions) {
        return Math.max(0, Math.min(permissions.limits().maxUploadBytes(), Transfer.MAX_UPLOAD_BYTES));
    }

    /**
     * The file's bytes, or {@code null} when it holds more than {@code max}: its size is checked first, and the read
     * itself stops one byte past {@code max}, so a file that grows meanwhile is refused too.
     */
    public static byte[] readAtMost(Path file, long max) throws IOException {
        if (Files.size(file) > max) return null;
        try (InputStream in = Files.newInputStream(file)) {
            byte[] bytes = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, max + 1));
            return bytes.length > max ? null : bytes;
        }
    }

    private void sendUpload(String name, byte[] bytes, BiConsumer<String, ClipboardCache.Entry> then) {
        Optional<EditorSession> session = host.session();
        if (session.isEmpty()) return;
        session.get().upload(name, bytes).result().thenAccept(reply -> {
            if (reply instanceof Reply.Ok<ClipboardCache.Entry> ok) {
                source = name;
                nextTransform = Transform.IDENTITY;
                notify(Notice.Level.SUCCESS, "sculptory.notice.uploaded", name, SessionNotices.count(ok.value().cells()));
                if (then != null) {
                    then.accept(name, ok.value());
                } else {
                    host.place(new PlaceTool.Request.Paste(new SourceRef.Clipboard(ok.value().clipboardId()), name,
                            Transform.IDENTITY));
                }
            }
        });
    }

    // ---- Helpers ----

    /** The world cell at the bottom centre of a box: where a copy is anchored, so pastes sit on the terrain. */
    public static BlockPos bottomCentre(Box box) {
        return new BlockPos(box.min().x() + box.sizeX() / 2, box.min().y(), box.min().z() + box.sizeZ() / 2);
    }

    private Optional<Region> selectionFor(Perm perm) {
        Optional<EditorSession> session = host.session();
        if (session.isEmpty()) return Optional.empty();
        Optional<Region> selection = host.selectionRegion();
        if (selection.isEmpty()) {
            notify(Notice.Level.INFO, "sculptory.notice.select_first");
            return Optional.empty();
        }
        OptionalLong cells = host.regionWork().countNow(selection.get());
        if (cells.isPresent() && cells.getAsLong() == 0) {
            notify(Notice.Level.INFO, "sculptory.notice.empty_shape");
            return Optional.empty();
        }
        return require(session.get().permissions(), perm) ? selection : Optional.empty();
    }

    private static long clipboardLimit(Permissions permissions) {
        return permissions.has(Perm.LIMIT_BYPASS) ? Long.MAX_VALUE : permissions.limits().maxClipboardVolume();
    }

    private boolean require(Permissions permissions, Perm perm) {
        if (permissions.has(perm)) return true;
        notify(Notice.Level.WARNING, "sculptory.notice.needs_permission", perm.node());
        return false;
    }

    private void notify(Notice.Level level, String key, String... args) {
        host.notify(Notice.of(level, key, args));
    }

    /** A request refused on this client (already toasted). */
    private static <T> CompletionStage<Reply<T>> refusedHere() {
        return CompletableFuture.completedFuture(Reply.failed(Reply.Failure.CANCELLED, "refused on the client"));
    }
}
