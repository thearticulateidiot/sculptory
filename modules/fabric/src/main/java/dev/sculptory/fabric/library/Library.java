package dev.sculptory.fabric.library;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.sculptory.core.Sha256;
import dev.sculptory.core.schem.SchematicFiles;
import dev.sculptory.protocol.v2.AssetAccess;
import dev.sculptory.protocol.v2.RejectReason;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The server asset library: schematic files ({@value LibraryPath#EXTENSION}, {@code .litematic} and {@code .nbt}; any
 * format reads as what its content is, {@code SchematicFiles}) in folders under one root
 * ({@code <gameDir>/sculptory/library/}). Shared assets sit in {@code <category>/<name>.schem}; players without
 * {@code sculptory.library.write} keep theirs in {@code _players/<uuid>/}. The files are the source of truth.
 *
 * <p><b>Containment.</b> Every client path is first checked by {@link LibraryPath}; here, every existing file or
 * folder on the way is resolved with {@link Path#toRealPath} and must lie inside the real library root, so a
 * symbolic link (or junction) inside the library may point elsewhere inside it but never out of it. Files are
 * written to a hidden temporary file in the target folder, forced to disk, and moved over the target atomically
 * where the file system allows. A symbolic link is never replaced.
 *
 * <p><b>Access.</b> Anyone who may use the clipboard reads the shared area; {@code _players/<uuid>/} is readable
 * and writable by that player only, and by admins ({@code sculptory.admin}). Writing the shared area needs
 * {@code sculptory.library.write} (or admin).
 *
 * <p><b>Quotas</b> ({@link Settings}): bytes per file, and bytes, files and folders for the whole library and for
 * each player folder. Usage is counted once (a walk that also deletes temporary files left by a crash) and then
 * kept up to date by every write; only library files (schematics and {@value LibraryPath#PALETTE_EXTENSION}) count.
 * A write is checked against the quotas before anything is created, and folders it created are removed again if it
 * fails.
 *
 * <p><b>Index.</b> {@value #INDEX_FILE} at the root is a derived cache (compact JSON) of path, size, modification
 * time, SHA-256 of the file bytes, dimensions, cell count and tags. An entry is trusted only while size and
 * modification time still match; a missing or unreadable index is rebuilt lazily. It is written at most every
 * {@value #INDEX_SAVE_INTERVAL_MILLIS} ms ({@link #flush()} writes pending changes). A listing hashes at most
 * {@value #MAX_LISTING_HASH_BYTES} bytes of files not indexed yet; the rest show an empty hash until listed again.
 *
 * <p><b>Management</b> (M4: {@link #move}, {@link #delete}, {@link #createFolder}): {@link #checkChange} decides who
 * may change a path (the {@link #mayChangeIn} rules; the root and {@code _players}/{@code _players/<uuid>} are never
 * changed). Nothing is ever replaced: a move is a plain file-system rename without {@code REPLACE_EXISTING} (which the
 * OS refuses atomically on Windows; elsewhere the check and the rename run under this class's lock, so two requests
 * can never race), and a race or a vanished source is a clean {@code INVALID}. Links are never changed. Deleted
 * files go to {@value #TRASH_FOLDER} (hidden, never listed or readable, outside the quotas), purged after
 * {@code trashDays}; folders are deleted only when empty (the file system refuses otherwise).
 *
 * <p><b>Palettes</b> ({@value LibraryPath#PALETTE_EXTENSION} files, {@link PaletteFile}) live in the same tree under
 * the same rules: paths, access, player folders, quotas (a palette counts as a file like any other), the trash and
 * management. They are read and written at most {@value PaletteFile#MAX_BYTES} bytes, are never indexed or hashed (so
 * {@link #find} and the schematic paths never meet one), and are listed with their {@link Entry#kind}. A move never
 * changes a file's extension, so neither its kind nor its format.
 *
 * <p><b>Per-asset access</b> ({@link AccessFile}): every file is open
 * to everyone who may read its area, or {@code listed} to named players. Who may change an entry's access
 * ({@link #mayChangeAccess}) is who may rename, move or delete it; they, the listed players and admins may read a
 * listed entry ({@link #mayRead(LibraryPath, Viewer)}, which also answers for cached assets on the server thread
 * from {@link AccessStore}, without file I/O). Grants follow renames, moves and deletions (the trash keeps them). A
 * corrupt access file fails closed: admins only, nothing rewrites it. The virtual folder {@value LibraryPath#SHARED}
 * ({@link #sharedWithMe}) lists what other players granted the viewer in their own folders.
 *
 * <p><b>Messages</b> never contain server paths: I/O errors are logged in full and reported as a generic
 * "file system error" with the library path.
 *
 * <p>Blocking file I/O: call it off the server thread. Thread-safe (one lock; the access cache has its own, so
 * {@link #mayRead(LibraryPath, Viewer)} never waits for file I/O).
 */
public final class Library {
    private static final Logger LOG = LoggerFactory.getLogger("sculptory");
    public static final String INDEX_FILE = ".index.json";
    /** The per-folder access file (per-asset access), hidden like the index. */
    public static final String ACCESS_FILE = AccessFile.NAME;
    /**
     * Where deleted files go: {@code .trash/<yyyyMMdd-HHmmss-SSS>-<8 hex>/<library path>}, stamped in UTC with the
     * deletion time. Hidden, so it is never listed, never named by a {@link LibraryPath} and not counted in the quotas.
     */
    public static final String TRASH_FOLDER = ".trash";
    private static final java.util.regex.Pattern TRASH_BIN =
            java.util.regex.Pattern.compile("(\\d{8}-\\d{6}-\\d{3})-[0-9a-f]{8}");
    /** A deletion's time in its trash folder's name, in UTC. */
    private static final java.time.format.DateTimeFormatter TRASH_STAMP =
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    /** The intermediate name of a two-step rename (a change of case only): hidden, and never cleaned up as a temp file. */
    private static final String RENAME_SUFFIX = ".rename";
    public static final long INDEX_SAVE_INTERVAL_MILLIS = 2000;
    public static final long MAX_LISTING_HASH_BYTES = 64L << 20;
    private static final String TEMP_SUFFIX = ".tmp";
    /** The temporary files this class writes: {@code .<uuid>.tmp} and {@code .<uuid>.index.tmp}. */
    private static final java.util.regex.Pattern TEMP_NAME = java.util.regex.Pattern.compile(
            "\\.[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(\\.index)?\\.tmp");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /**
     * @param maxFileBytes largest file read or written
     * @param maxTotalBytes all library files together
     * @param maxPlayerBytes one {@code _players/<uuid>/} folder
     * @param maxListing entries returned by one listing
     * @param maxFiles library files in all
     * @param maxFolders folders in all
     * @param maxPlayerFiles files in one player folder
     * @param maxPlayerFolders folders in one player folder (itself included)
     * @param trashDays deleted files older than this many days are purged from {@value #TRASH_FOLDER} (at
     *     {@link #start()} and at each deletion); 0 keeps them as long as {@code maxTrashBytes} allows
     * @param maxTrashBytes what the trash may hold of files deleted from the shared area, so deleting cannot fill the
     *     disk past the quotas; a deletion that would go over purges the oldest shared deletions first
     * @param maxPlayerTrashBytes the same for each player folder's deletions, a share of its own: deleting in one's own
     *     folder only ever pushes out that player's older deletions, never anyone else's
     */
    public record Settings(long maxFileBytes, long maxTotalBytes, long maxPlayerBytes, int maxListing, int maxFiles,
                           int maxFolders, int maxPlayerFiles, int maxPlayerFolders, int trashDays,
                           long maxTrashBytes, long maxPlayerTrashBytes) {
        public static final int DEFAULT_TRASH_DAYS = 30;
        public static final int MAX_TRASH_DAYS = 3650;
        public static final long DEFAULT_TRASH_BYTES = 1L << 30;
        public static final long DEFAULT_PLAYER_TRASH_BYTES = 64L << 20;
        public static final Settings DEFAULTS = new Settings(32L << 20, 1L << 30, 64L << 20, 512);

        public Settings {
            if (maxFileBytes < 1 || maxTotalBytes < 1 || maxPlayerBytes < 1 || maxListing < 1 || maxFiles < 1
                    || maxFolders < 1 || maxPlayerFiles < 1 || maxPlayerFolders < 1 || maxTrashBytes < 1
                    || maxPlayerTrashBytes < 1) {
                throw new IllegalArgumentException("Library limits must be positive");
            }
            if (trashDays < 0 || trashDays > MAX_TRASH_DAYS) {
                throw new IllegalArgumentException("trashDays must be 0-" + MAX_TRASH_DAYS);
            }
        }

        /** With the default trash limits ({@value #DEFAULT_TRASH_DAYS} days, 1 GiB shared, 64 MiB per player). */
        public Settings(long maxFileBytes, long maxTotalBytes, long maxPlayerBytes, int maxListing, int maxFiles,
                        int maxFolders, int maxPlayerFiles, int maxPlayerFolders) {
            this(maxFileBytes, maxTotalBytes, maxPlayerBytes, maxListing, maxFiles, maxFolders, maxPlayerFiles,
                    maxPlayerFolders, DEFAULT_TRASH_DAYS, DEFAULT_TRASH_BYTES, DEFAULT_PLAYER_TRASH_BYTES);
        }

        /** Byte caps and listing size, with the default counts (20,000 files, 4,096 folders; 512 and 64 per player). */
        public Settings(long maxFileBytes, long maxTotalBytes, long maxPlayerBytes, int maxListing) {
            this(maxFileBytes, maxTotalBytes, maxPlayerBytes, maxListing, 20_000, 4_096, 512, 64);
        }

        public Settings withTrash(int days, long sharedBytes, long playerBytes) {
            return new Settings(maxFileBytes, maxTotalBytes, maxPlayerBytes, maxListing, maxFiles, maxFolders,
                    maxPlayerFiles, maxPlayerFolders, days, sharedBytes, playerBytes);
        }

        public Settings withTrashDays(int days) {
            return withTrash(days, maxTrashBytes, maxPlayerTrashBytes);
        }

        /** The trash share of an area: a player's own ({@code maxPlayerTrashBytes}), or the shared one. */
        long trashCap(UUID owner) {
            return owner == null ? maxTrashBytes : maxPlayerTrashBytes;
        }
    }

    /** Who is asking: the player, and whether they hold {@code library.write} and {@code admin}. */
    public record Viewer(UUID player, boolean write, boolean admin) {
        public Viewer {
            Objects.requireNonNull(player);
        }
    }

    /**
     * One listing entry; {@code sha256} is {@code ""} for folders, for palettes and for schematics not hashed (yet).
     * {@code kind} is the file's kind, {@code null} for a folder. {@code restricted} (per-asset access): only listed
     * players may load the file (also set while its folder's access file is corrupt).
     */
    public record Entry(String path, boolean folder, long bytes, String sha256, LibraryPath.Kind kind, boolean restricted) {
        /** An entry everyone may load. */
        public Entry(String path, boolean folder, long bytes, String sha256, LibraryPath.Kind kind) {
            this(path, folder, bytes, sha256, kind, false);
        }

        /** A folder, or a schematic everyone may load. */
        public Entry(String path, boolean folder, long bytes, String sha256) {
            this(path, folder, bytes, sha256, folder ? null : LibraryPath.Kind.SCHEMATIC, false);
        }
    }

    public record Listing(String folder, List<Entry> entries, boolean truncated) {
        public Listing {
            entries = List.copyOf(entries);
        }
    }

    /** A file's bytes and their SHA-256 (lower-case hex). */
    public record FileData(LibraryPath path, byte[] bytes, String sha256) {}

    /** What the index knows about a file's content; {@code dims} is {@code null} when it could not be read. */
    public record Info(int[] dims, long cells, List<String> tags) {
        public Info {
            tags = tags == null ? List.of() : List.copyOf(tags);
        }
    }

    /** Bytes, files and folders in use (the whole library, or one player folder). */
    public record Usage(long bytes, int files, int folders) {}

    private static final class Counter {
        long bytes;
        int files;
        int folders;

        Usage snapshot() {
            return new Usage(bytes, files, folders);
        }

        /** Counts something removed; never below zero (the library may have changed on disk since it was counted). */
        void take(long bytes, int files, int folders) {
            this.bytes = Math.max(0, this.bytes - bytes);
            this.files = Math.max(0, this.files - files);
            this.folders = Math.max(0, this.folders - folders);
        }
    }

    private final Path root;
    private final Settings settings;
    private final LongSupplier clock;
    /** Keyed by library path; {@code null} until first used. */
    private TreeMap<String, IndexRecord> index;
    private boolean indexDirty;
    private long indexSavedAt = Long.MIN_VALUE;
    /** {@code null} until the first walk. */
    private Counter total;
    /** The deletions in the trash, oldest first (bin name → size and area); {@code null} until counted. */
    private TreeMap<String, Bin> trashBins;
    /** Trash bytes per area: the shared area under the {@code null} key, each player folder under its player. */
    private final Map<UUID, Long> trashByArea = new HashMap<>();

    /** One deletion in the trash: its bytes, and the player folder it came from ({@code null}: the shared area). */
    private record Bin(long bytes, UUID owner) {}
    private final Map<UUID, Counter> players = new HashMap<>();
    /** The per-folder access files as last read (per-asset access), by folder path. */
    private final AccessStore access = new AccessStore();
    /** Folders whose corrupt access file was logged (until it reads again). */
    private final java.util.Set<String> corruptLogged = new java.util.HashSet<>();

    /**
     * The access files read so far, keyed by folder path, with their own short lock: the server thread reads them
     * ({@link #mayRead(LibraryPath, Viewer)} for cached assets, the pushes) without waiting for the library's file
     * I/O. The I/O paths refresh a folder's entry from disk ({@link #loadAccess}) under the library's lock.
     */
    private static final class AccessStore {
        private final Map<String, AccessFile.Loaded> byFolder = new HashMap<>();

        synchronized AccessFile.Loaded get(String folder) {
            return byFolder.getOrDefault(folder, AccessFile.Loaded.MISSING);
        }

        synchronized AccessFile.Loaded known(String folder) {
            return byFolder.get(folder);
        }

        synchronized void put(String folder, AccessFile.Loaded loaded) {
            byFolder.put(folder, loaded);
        }

        /** Forgets a folder and everything below it (deleted: read again when next seen). */
        synchronized void dropUnder(String folder) {
            byFolder.keySet().removeIf(key -> key.equals(folder) || key.startsWith(folder + "/"));
        }

        /**
         * A folder was renamed: what was read for it and everything below it now answers under the new paths (the
         * files moved along, so nothing is open for a moment until the folder is read again).
         */
        synchronized void moveUnder(String from, String to) {
            Map<String, AccessFile.Loaded> moved = new HashMap<>();
            for (Map.Entry<String, AccessFile.Loaded> entry : byFolder.entrySet()) {
                String key = entry.getKey();
                if (key.equals(from) || key.startsWith(from + "/")) moved.put(to + key.substring(from.length()), entry.getValue());
            }
            dropUnder(from);
            byFolder.putAll(moved);
        }
    }

    public Library(Path root, Settings settings) {
        this(root, settings, System::currentTimeMillis);
    }

    /** With a clock (milliseconds) for the index debounce (tests). */
    public Library(Path root, Settings settings, LongSupplier clock) {
        this.root = root.toAbsolutePath().normalize();
        this.settings = Objects.requireNonNull(settings);
        this.clock = Objects.requireNonNull(clock);
    }

    public Path root() {
        return root;
    }

    public Settings settings() {
        return settings;
    }

    // ================================================================== access rules

    /**
     * The area rule: whether the viewer may list the folder {@code path}, or read a file there as far as its folder
     * goes (the shared area is everyone's, a player folder its player's and admins'). A file's own access comes on
     * top: {@link #mayRead(LibraryPath, Viewer)}.
     */
    public static boolean mayReadArea(LibraryPath path, Viewer viewer) {
        Optional<UUID> owner = path.owner();
        return owner.isEmpty() || viewer.admin() || owner.get().equals(viewer.player());
    }

    /**
     * Whether the viewer may read {@code path} (per-asset access): a folder by the area rule; a file by its access
     * as last read from its folder's access file ({@link AccessStore}, so this never does file I/O and may run on
     * the server thread): an open file by the area rule, a listed one by its list, whoever may change its access, and
     * admins; a file in a folder whose access file is corrupt by admins only. The I/O paths ({@link #read}) refresh
     * the file first and ask again.
     */
    public boolean mayRead(LibraryPath path, Viewer viewer) {
        if (!path.isFile()) return mayReadArea(path, viewer);
        return allowed(access.get(path.parent().toString()), path, viewer);
    }

    /**
     * Whether the viewer could read the file {@code path} under {@code access} (per-asset access, pure): for a
     * change's push, before and after it.
     */
    public static boolean readableUnder(LibraryPath path, AssetAccess access, Viewer viewer) {
        if (viewer.admin()) return true;
        if (access.restricted()) return access.names(viewer.player()) || mayChangeAccess(path, viewer);
        return mayReadArea(path, viewer);
    }

    /** {@link #readableUnder} for the file's folder as read: admins only while its access file is corrupt. */
    private static boolean allowed(AccessFile.Loaded loaded, LibraryPath file, Viewer viewer) {
        if (viewer.admin()) return true;
        if (loaded.corrupt()) return false;
        return readableUnder(file, loaded.of(file.name()), viewer);
    }

    /**
     * Whether the viewer may see and change the file's access (per-asset access): exactly who may rename, move or
     * delete it ({@link #checkChange}): its owner in their own folder, {@code library.write} holders in the shared
     * area, admins anywhere.
     */
    public static boolean mayChangeAccess(LibraryPath file, Viewer viewer) {
        return file.isFile() && ownerAllows(file, viewer);
    }

    /**
     * The file's access as last read (per-asset access; no file I/O, server thread): for the pushes of a change that
     * moves or removes it, which are computed from the access before the change. {@code null} while its folder's
     * access file is corrupt (nobody but admins reads it then).
     */
    public AssetAccess accessOf(LibraryPath file) {
        if (!file.isFile()) return null;
        AccessFile.Loaded loaded = access.get(file.parent().toString());
        return loaded.corrupt() ? null : loaded.of(file.name());
    }

    /**
     * Whether the file is restricted as last read (per-asset access; no file I/O): listed, or in a folder whose
     * access file is corrupt. What a change of such a file must evict from the caches before it runs.
     */
    public boolean restricted(LibraryPath file) {
        if (!file.isFile()) return false;
        AccessFile.Loaded loaded = access.get(file.parent().toString());
        return loaded.corrupt() || loaded.of(file.name()).restricted();
    }

    /** Whether the viewer may write the file {@code path}. */
    public static boolean mayWrite(LibraryPath path, Viewer viewer) {
        if (!path.isFile()) return false;
        return ownerAllows(path, viewer);
    }

    /**
     * Whether the viewer may create folders in {@code folder} and rename, move and delete what it holds (a listing's
     * {@code writable} flag): the shared area needs {@code library.write}, a player folder is its player's, admins may
     * change everything. {@code _players} itself holds only player folders, which nobody creates by hand.
     */
    public static boolean mayChangeIn(LibraryPath folder, Viewer viewer) {
        if (folder.isFile() || (folder.inPlayersArea() && folder.segments().size() == 1)) return false;
        return ownerAllows(folder, viewer);
    }

    /**
     * Checks that the viewer may create, rename, move or delete {@code path}, file or folder, under the same rules as
     * {@link #mayChangeIn}. The root and the reserved {@code _players} folders are refused {@code INVALID}; a missing
     * right is {@code NO_PERMISSION}.
     */
    public static void checkChange(LibraryPath path, Viewer viewer) throws LibraryException {
        if (path.isRoot()) throw new LibraryException(RejectReason.INVALID, "the library folder itself can't be changed");
        if (path.reserved()) {
            throw new LibraryException(RejectReason.INVALID, "'" + path + "' is made by the server ("
                    + LibraryPath.PLAYERS + " and the player folders in it can't be created, renamed or deleted)");
        }
        if (ownerAllows(path, viewer)) return;
        throw new LibraryException(RejectReason.NO_PERMISSION, path.owner().isEmpty()
                ? "changing the shared library needs sculptory.library.write (" + path + ")"
                : "another player's folder needs sculptory.admin (" + path + ")");
    }

    /** The shared area: {@code library.write} or admin; a player folder: its player or an admin. */
    private static boolean ownerAllows(LibraryPath path, Viewer viewer) {
        Optional<UUID> owner = path.owner();
        if (owner.isEmpty()) return viewer.write() || viewer.admin();
        return viewer.admin() || owner.get().equals(viewer.player());
    }

    // ================================================================== operations

    /**
     * Creates the root, deletes temporary files a crash left behind and counts the usage (which runs on first use
     * anyway), then purges old trash ({@link #purgeTrash()}). Call it at server start.
     */
    public synchronized void start() throws LibraryException {
        Path realRoot = realRoot();
        ensureUsage(realRoot);
        loadAllAccess(realRoot);
        int purged = purgeTrash();
        if (purged > 0) {
            LOG.info("Sculptory library: purged {} deletion(s) older than {} days from {}", purged,
                    settings.trashDays(), TRASH_FOLDER);
        }
    }

    /** The library's usage (after {@link #start()} or any write). */
    public synchronized Usage usage() throws LibraryException {
        ensureUsage(realRoot());
        return total.snapshot();
    }

    /** One player's folder usage. */
    public synchronized Usage usage(UUID player) throws LibraryException {
        ensureUsage(realRoot());
        Counter counter = players.get(player);
        return counter == null ? new Usage(0, 0, 0) : counter.snapshot();
    }

    /**
     * Lists a folder: sub-folders first, then files, each sorted by name, at most {@code maxListing}. Files the viewer
     * may not read (per-asset access) are left out, and the root ends with the virtual {@value LibraryPath#SHARED}
     * folder ({@link #sharedWithMe}).
     */
    public synchronized Listing list(LibraryPath folder, Viewer viewer) throws LibraryException {
        if (folder.isFile()) throw new LibraryException(RejectReason.INVALID, "not a folder: " + folder);
        if (!mayReadArea(folder, viewer)) throw new LibraryException(RejectReason.NO_PERMISSION, "not your folder: " + folder);
        Path realRoot = realRoot();
        Path real = folder.isRoot() ? realRoot : realExisting(folder.resolve(root), realRoot, folder);
        requireSpelt(folder);
        if (!Files.isDirectory(real)) throw new LibraryException(RejectReason.INVALID, "not a folder: " + folder);
        boolean playersRoot = folder.segments().size() == 1 && folder.inPlayersArea();
        List<Entry> folders = new ArrayList<>();
        List<LibraryPath> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(real)) {
            for (Path child : stream) {
                String name = child.getFileName().toString();
                if (!visible(folder, playersRoot, name, viewer)) continue;
                Path childReal = realOrNull(child);
                if (childReal == null || !childReal.startsWith(realRoot)) continue;
                if (Files.isDirectory(childReal)) {
                    folders.add(new Entry(folder.child(name, false).toString(), true, 0, ""));
                } else if (!playersRoot && !(folder.isRoot() && name.equals(LibraryPath.PLAYERS))
                        && LibraryPath.Kind.of(name) != null && Files.isRegularFile(childReal)) {
                    files.add(folder.child(name, true));
                }
            }
        } catch (IOException e) {
            throw ioFailure("list", folder, e);
        }
        folders.sort(Comparator.comparing(Entry::path));
        if (folder.isRoot()) folders.add(new Entry(LibraryPath.SHARED, true, 0, ""));
        files.sort(Comparator.comparing(LibraryPath::toString));
        AccessFile.Loaded loaded = loadAccess(folder, real);
        files.removeIf(file -> !allowed(loaded, file, viewer));
        boolean truncated = folders.size() + files.size() > settings.maxListing();
        List<Entry> entries = new ArrayList<>(folders.subList(0, Math.min(folders.size(), settings.maxListing())));
        long[] hashBudget = {MAX_LISTING_HASH_BYTES};
        for (LibraryPath file : files) {
            if (entries.size() >= settings.maxListing()) break;
            Entry entry = fileEntry(file, realRoot, loaded, hashBudget);
            if (entry != null) entries.add(entry);
        }
        saveIndexSoon();
        return new Listing(folder.toString(), entries, truncated);
    }

    /**
     * A file's listing entry (a palette is never indexed or hashed: no content hash can lead to one), or
     * {@code null} when it vanished or cannot be read since its folder was read. {@code hashBudget[0]} is what a
     * listing may still hash.
     */
    private Entry fileEntry(LibraryPath file, Path realRoot, AccessFile.Loaded loaded, long[] hashBudget) {
        boolean restricted = loaded.corrupt() || loaded.of(file.name()).restricted();
        try {
            Path fileReal = realExisting(file.resolve(root), realRoot, file);
            if (file.isPalette()) {
                return new Entry(file.toString(), false, Files.size(fileReal), "", LibraryPath.Kind.PALETTE, restricted);
            }
            IndexRecord record = indexed(file, fileReal, null, hashBudget[0]);
            if (record.hashed) hashBudget[0] -= record.size;
            return new Entry(file.toString(), false, record.size, record.sha256 == null ? "" : record.sha256,
                    LibraryPath.Kind.SCHEMATIC, restricted);
        } catch (LibraryException | IOException e) {
            return null;
        }
    }

    /**
     * Reads a file: a schematic at most {@code maxFileBytes}, a palette at most {@value PaletteFile#MAX_BYTES} (and
     * never indexed). The file's access is read again from disk first (per-asset access).
     */
    public synchronized FileData read(LibraryPath file, Viewer viewer) throws LibraryException {
        if (!file.isFile()) throw new LibraryException(RejectReason.INVALID, "not a file: " + file);
        // As last read, before any file access, then again from disk once the file is found. A file the viewer may
        // not read and a file that is not there get the same answer (readRefusal), so a refusal tells nothing about
        // whether a name exists.
        if (!mayRead(file, viewer)) throw readRefusal(file, viewer);
        Path realRoot = realRoot();
        Path real;
        try {
            real = realExisting(file.resolve(root), realRoot, file);
            requireSpelt(file);
        } catch (LibraryException e) {
            throw e.reason() == RejectReason.INVALID ? notShared(file) : e;
        }
        requireReadable(file, real.getParent(), viewer);
        if (!Files.isRegularFile(real)) throw notShared(file);
        byte[] bytes;
        try {
            bytes = readBounded(real, maxBytes(file), file);
        } catch (IOException e) {
            throw ioFailure("read", file, e);
        }
        String sha = Sha256.digest(bytes).hex();
        if (file.isPalette()) return new FileData(file, bytes, sha);
        try {
            indexed(file, real, bytes, Long.MAX_VALUE);
            saveIndexSoon();
        } catch (IOException e) {
            LOG.warn("Sculptory library: could not index {}", file, e);
        }
        return new FileData(file, bytes, sha);
    }

    /**
     * Writes a file atomically, creating its folders; replaces an existing regular file. The quotas are checked
     * before anything is created; on failure the folders this call created are removed again. A palette is at most
     * {@value PaletteFile#MAX_BYTES} bytes and is not indexed ({@code info} is ignored for it).
     *
     * @return the SHA-256 of {@code bytes}
     */
    public synchronized String write(LibraryPath file, byte[] bytes, Viewer viewer, Info info) throws LibraryException {
        if (!file.isFile()) throw new LibraryException(RejectReason.INVALID, "not a file: " + file);
        if (!mayWrite(file, viewer)) throw new LibraryException(RejectReason.NO_PERMISSION, "may not write " + file);
        if (bytes.length > maxBytes(file)) {
            throw new LibraryException(RejectReason.TOO_LARGE, bytes.length + " bytes > " + maxBytes(file));
        }
        Path realRoot = realRoot();
        ensureUsage(realRoot);
        List<Path> missing = missingFolders(file.parent(), realRoot);
        Path folder = file.parent().resolve(root);
        Path target = folder.resolve(file.name());
        long existing;
        try {
            if (Files.isSymbolicLink(target)) throw new LibraryException(RejectReason.INVALID, file + " is a link");
            boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
            if (exists && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new LibraryException(RejectReason.INVALID, file + " exists and is not a file");
            }
            existing = exists ? Files.size(target) : -1;
        } catch (IOException e) {
            throw ioFailure("write", file, e);
        }
        Optional<UUID> owner = file.owner();
        Counter mine = owner.map(id -> players.computeIfAbsent(id, k -> new Counter())).orElse(null);
        long delta = bytes.length - Math.max(0, existing);
        int newFiles = existing < 0 ? 1 : 0;
        int newFolders = missing.size();
        checkQuota(total, delta, newFiles, newFolders, settings.maxTotalBytes(), settings.maxFiles(),
                settings.maxFolders(), "the library");
        if (mine != null) {
            int ownFolders = countInPlayerFolder(missing, owner.get());
            checkQuota(mine, delta, newFiles, ownFolders, settings.maxPlayerBytes(), settings.maxPlayerFiles(),
                    settings.maxPlayerFolders(), "the player folder");
        }
        List<Path> created = new ArrayList<>();
        Path temp = folder.resolve("." + UUID.randomUUID() + TEMP_SUFFIX);
        try {
            for (Path level : missing) {
                Files.createDirectory(level);
                created.add(level);
            }
            realExisting(folder, realRoot, file.parent());
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | LibraryException e) {
            deleteQuietly(temp);
            for (int i = created.size() - 1; i >= 0; i--) deleteQuietly(created.get(i));
            if (e instanceof LibraryException library) throw library;
            throw ioFailure("write", file, (IOException) e);
        }
        total.bytes += delta;
        total.files += newFiles;
        total.folders += created.size();
        if (mine != null) {
            mine.bytes += delta;
            mine.files += newFiles;
            mine.folders += countInPlayerFolder(created, owner.get());
        }
        // A new file starts open: a stale grant under its name (left by a failed removal once) goes. A replaced file
        // keeps its access.
        if (existing < 0) dropGrantQuietly(file, folder);
        String sha = Sha256.digest(bytes).hex();
        if (file.isPalette()) return sha;
        try {
            BasicFileAttributes attrs = Files.readAttributes(target, BasicFileAttributes.class);
            index().put(file.toString(), new IndexRecord(attrs.size(), attrs.lastModifiedTime().toMillis(), sha,
                    info == null ? header(bytes) : info, true));
            indexDirty = true;
            saveIndexSoon();
        } catch (IOException e) {
            LOG.warn("Sculptory library: could not index {}", file, e);
        }
        return sha;
    }

    // ================================================================== management (M4)

    /**
     * Renames or moves a file, or renames a folder in place ({@code from} and {@code to} are both files, or both
     * folders with the same parent). Nothing is ever replaced: refused ({@code INVALID}) when {@code to} exists (except
     * for a change of case on a file system that ignores case), when {@code from} is gone or is a link, when the
     * target folder does not exist (a player's own folder is made on the way, as for a save), or when a path inside a
     * renamed folder would pass {@value LibraryPath#MAX_LENGTH} characters. {@link #checkChange} applies to both
     * paths. A file moved into or out of a player folder moves its bytes between the quotas (checked first). The
     * index entries go along, so the content hash still finds the file under its new name.
     */
    public synchronized void move(LibraryPath from, LibraryPath to, Viewer viewer) throws LibraryException {
        if (from.isFile() != to.isFile()) {
            throw new LibraryException(RejectReason.INVALID, "a file stays a file and a folder a folder");
        }
        if (from.kind() != to.kind()) {
            throw new LibraryException(RejectReason.INVALID, "a file keeps its kind (" + from.kind().extension() + ")");
        }
        if (from.isFile() && !from.extension().equals(to.extension())) {
            throw new LibraryException(RejectReason.INVALID, "a file keeps its extension (" + from.extension() + ")");
        }
        checkChange(from, viewer);
        checkChange(to, viewer);
        if (from.equals(to)) throw new LibraryException(RejectReason.INVALID, "the new name is the old one: " + to);
        if (!from.isFile() && !from.parent().equals(to.parent())) {
            throw new LibraryException(RejectReason.INVALID, "folders are renamed in place (" + from + " → " + to + ")");
        }
        Path realRoot = realRoot();
        ensureUsage(realRoot);
        plainFolder(from.parent(), realRoot);
        Path source = from.resolve(root);
        BasicFileAttributes attrs = entryAttributes(source, from);
        requireKind(attrs, from);
        requireEntrySpelling(from, source, realRoot);
        List<Path> missing = targetFolders(to.parent(), realRoot);
        plainExistingPart(to.parent(), missing.size(), realRoot);
        Path target = to.resolve(root);
        boolean caseOnly = false;
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            caseOnly = from.parent().equals(to.parent()) && from.name().equalsIgnoreCase(to.name())
                    && sameEntry(source, target);
            if (!caseOnly) throw new LibraryException(RejectReason.INVALID, "already exists: " + to);
        }
        if (!from.isFile()) checkLengthsInside(source, to);

        Optional<UUID> fromOwner = from.owner();
        Optional<UUID> toOwner = to.owner();
        boolean crossing = from.isFile() && !fromOwner.equals(toOwner);
        long bytes = from.isFile() ? attrs.size() : 0;
        checkQuota(total, 0, 0, missing.size(), settings.maxTotalBytes(), settings.maxFiles(), settings.maxFolders(),
                "the library");
        Counter receiving = toOwner.map(id -> players.computeIfAbsent(id, k -> new Counter())).orElse(null);
        if (receiving != null) {
            checkQuota(receiving, crossing ? bytes : 0, crossing ? 1 : 0, countInPlayerFolder(missing, toOwner.get()),
                    settings.maxPlayerBytes(), settings.maxPlayerFiles(), settings.maxPlayerFolders(),
                    "the player folder");
        }
        // The grant goes first (per-asset access): a target folder whose access file cannot be written is refused
        // before anything moves, and a restricted entry is never open for a moment under its new name.
        AssetAccess grant = from.isFile() ? grantOf(from, source.getParent()) : null;
        List<Path> created = createFolders(missing, to);
        if (grant != null && grant.restricted()) {
            try {
                putGrant(to, target.getParent(), grant);
            } catch (LibraryException e) {
                removeCreated(created);
                throw e;
            }
        }
        try {
            if (caseOnly) {
                renameInTwoSteps(source, target);
            } else {
                Files.move(source, target); // no REPLACE_EXISTING, no ATOMIC_MOVE (which may replace)
            }
        } catch (IOException e) {
            if (grant != null && grant.restricted()) dropGrantQuietly(to, target.getParent());
            removeCreated(created);
            throw moveFailure(e, from, to);
        }
        if (grant != null && grant.restricted()) dropGrantQuietly(from, source.getParent());
        // An open file takes no grant along; a stale entry under its new name (left by a failed removal once) would
        // restrict it to nobody's benefit, so it goes.
        if (grant != null && !grant.restricted()) dropGrantQuietly(to, target.getParent());
        if (!from.isFile()) {
            access.moveUnder(from.toString(), to.toString());
            for (String logged : new ArrayList<>(corruptLogged)) {
                if (logged.equals(from.toString()) || logged.startsWith(from + "/")) {
                    corruptLogged.remove(logged);
                    corruptLogged.add(to + logged.substring(from.toString().length()));
                }
            }
        }
        total.folders += created.size();
        if (receiving != null) receiving.folders += countInPlayerFolder(created, toOwner.get());
        if (crossing) {
            fromOwner.map(players::get).ifPresent(counter -> counter.take(bytes, 1, 0));
            if (receiving != null) {
                receiving.bytes += bytes;
                receiving.files++;
            }
        }
        reindex(from, to, target);
    }

    /**
     * Deletes a file into the trash ({@value #TRASH_FOLDER}, see there; its index entry is dropped, so its hash no
     * longer finds it), or deletes an empty folder. A folder that holds anything, including files the library does not
     * show, is refused ({@code INVALID}) and left alone; so is a link or a path that is gone. {@link #checkChange}
     * applies.
     *
     * @return where the file went, relative to the library root ({@code ""} for a folder)
     */
    public synchronized String delete(LibraryPath path, Viewer viewer) throws LibraryException {
        checkChange(path, viewer);
        Path realRoot = realRoot();
        ensureUsage(realRoot);
        plainFolder(path.parent(), realRoot);
        Path source = path.resolve(root);
        BasicFileAttributes attrs = entryAttributes(source, path);
        requireKind(attrs, path);
        requireEntrySpelling(path, source, realRoot);
        Optional<Counter> owner = path.owner().map(id -> players.computeIfAbsent(id, k -> new Counter()));
        if (!path.isFile()) {
            try {
                // A folder holding only its access file is empty: the file (stale by then) goes first.
                if (childNames(source).equals(List.of(ACCESS_FILE))) {
                    Files.deleteIfExists(source.resolve(ACCESS_FILE));
                }
                Files.delete(source); // the file system refuses a folder that is not empty
            } catch (DirectoryNotEmptyException e) {
                throw new LibraryException(RejectReason.INVALID, notEmpty(source, path));
            } catch (NoSuchFileException e) {
                throw notFound(path);
            } catch (IOException e) {
                throw ioFailure("delete", path, e);
            }
            access.dropUnder(path.toString());
            total.take(0, 0, 1);
            owner.ifPresent(counter -> counter.take(0, 0, 1));
            return "";
        }
        AssetAccess grant = grantOf(path, source.getParent());
        long size = attrs.size();
        UUID area = path.owner().orElse(null);
        if (size > settings.trashCap(area)) {
            throw new LibraryException(RejectReason.TOO_LARGE, path + " is larger than the trash may hold ("
                    + settings.trashCap(area) + " bytes" + (area == null ? "" : " for a player folder") + ")");
        }
        Path trash = root.resolve(TRASH_FOLDER);
        boolean madeTrash = false;
        try {
            if (Files.exists(trash, LinkOption.NOFOLLOW_LINKS)) {
                // A link or junction could lead out of the library: deleted files never go through one.
                if (!plainDirectory(trash)) {
                    throw new LibraryException(RejectReason.INVALID, "the library's " + TRASH_FOLDER
                            + " is not a plain folder; an admin must fix it on disk");
                }
            } else {
                Files.createDirectory(trash);
                madeTrash = true;
            }
        } catch (IOException e) {
            throw ioFailure("delete", path, e);
        }
        ensureTrash();
        purgeOld();
        String stamp = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(clock.getAsLong()),
                java.time.ZoneOffset.UTC).format(TRASH_STAMP) + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path bin = trash.resolve(stamp);
        Path target = path.resolve(bin);
        boolean madeBin = false;
        try {
            Files.createDirectory(bin); // never an existing one, so a failure below removes only what this made
            madeBin = true;
            Files.createDirectories(target.getParent());
            Files.move(source, target);
        } catch (IOException e) {
            if (madeBin) deleteTree(bin);
            if (madeTrash && trashBins.isEmpty()) deleteQuietly(trash);
            throw moveFailure(e, path, null);
        }
        if (grant.restricted()) {
            // The trash keeps the grant (per-asset access), beside the file, so a restore on disk can keep it.
            try {
                AccessFile.write(target.getParent(), Map.of(path.name(), grant));
            } catch (IOException e) {
                LOG.warn("Sculptory library: could not keep the access of {} in the trash", path, e);
            }
            dropGrantQuietly(path, source.getParent());
        }
        addBin(stamp, new Bin(size, area));
        // Room under the area's share, made only now that the file is safely in the trash: the oldest deletions of
        // the same area go first (this one is the newest, and alone it fits), never another area's.
        fitArea(area, stamp);
        total.take(attrs.size(), 1, 0);
        owner.ifPresent(counter -> counter.take(attrs.size(), 1, 0));
        if (index().remove(path.toString()) != null) {
            indexDirty = true;
            saveIndexSoon();
        }
        LOG.info("Sculptory library: {} deleted by {}, kept as {}/{}/{}", path, viewer.player(), TRASH_FOLDER, stamp,
                path);
        return TRASH_FOLDER + "/" + stamp + "/" + path;
    }

    /**
     * Creates a folder. Refused ({@code INVALID}) when it exists or its parent does not (a player's own folder is made
     * on the way, as for a save); the folder quotas apply. {@link #checkChange} applies.
     */
    public synchronized void createFolder(LibraryPath folder, Viewer viewer) throws LibraryException {
        if (folder.isFile()) throw new LibraryException(RejectReason.INVALID, "not a folder: " + folder);
        checkChange(folder, viewer);
        Path realRoot = realRoot();
        ensureUsage(realRoot);
        List<Path> missing = new ArrayList<>(targetFolders(folder.parent(), realRoot));
        plainExistingPart(folder.parent(), missing.size(), realRoot);
        Path target = folder.resolve(root);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new LibraryException(RejectReason.INVALID, "already exists: " + folder);
        }
        missing.add(target);
        Optional<UUID> owner = folder.owner();
        checkQuota(total, 0, 0, missing.size(), settings.maxTotalBytes(), settings.maxFiles(), settings.maxFolders(),
                "the library");
        Counter mine = owner.map(id -> players.computeIfAbsent(id, k -> new Counter())).orElse(null);
        if (mine != null) {
            checkQuota(mine, 0, 0, countInPlayerFolder(missing, owner.get()), settings.maxPlayerBytes(),
                    settings.maxPlayerFiles(), settings.maxPlayerFolders(), "the player folder");
        }
        List<Path> created = createFolders(missing, folder);
        total.folders += created.size();
        if (mine != null) mine.folders += countInPlayerFolder(created, owner.get());
    }

    /**
     * Deletes the trash of deletions older than {@code trashDays} (by the stamp in their folder name; 0 keeps
     * everything). Links inside are removed, never followed. Called by {@link #start()}.
     *
     * @return how many deletions were purged
     */
    public synchronized int purgeTrash() {
        trashBins = null; // counted afresh: an admin may have restored or removed deletions on disk
        ensureTrash();
        int before = trashBins.size();
        purgeOld();
        for (UUID area : new ArrayList<>(trashByArea.keySet())) fitArea(area, null);
        return before - trashBins.size();
    }

    /** The trash's bytes (deletions the library made and still keeps), all areas together. */
    public synchronized long trashBytes() {
        ensureTrash();
        long sum = 0;
        for (long bytes : trashByArea.values()) sum += bytes;
        return sum;
    }

    /** The trash bytes of one area: a player folder's deletions, or the shared area's ({@code player} null). */
    public synchronized long trashBytes(UUID player) {
        ensureTrash();
        return trashByArea.getOrDefault(player, 0L);
    }

    /**
     * Counts the deletions in the trash once: only folders named like a deletion and reached without a link (a trash
     * that is itself a link or junction holds nothing the library will purge). Other entries are left alone. A
     * deletion belongs to the player folder it came from ({@link #binOwner}), else to the shared area.
     */
    private void ensureTrash() {
        if (trashBins != null) return;
        trashBins = new TreeMap<>();
        trashByArea.clear();
        Path trash = root.resolve(TRASH_FOLDER);
        if (!plainDirectory(trash)) return;
        try (DirectoryStream<Path> bins = Files.newDirectoryStream(trash)) {
            for (Path bin : bins) {
                String name = bin.getFileName().toString();
                if (stampMillis(name) < 0 || !plainDirectory(bin)) continue; // not one of the library's
                addBin(name, new Bin(treeBytes(bin), binOwner(bin)));
            }
        } catch (IOException e) {
            LOG.warn("Sculptory library: could not count {}", trash, e);
        }
    }

    /**
     * The player whose folder a deletion came from: a bin holding only {@code _players/<uuid>/...} (as the library
     * writes them); anything else counts as the shared area's.
     */
    private static UUID binOwner(Path bin) {
        List<String> top = childNames(bin);
        if (!top.equals(List.of(LibraryPath.PLAYERS))) return null;
        Path players = bin.resolve(LibraryPath.PLAYERS);
        if (!plainDirectory(players)) return null;
        List<String> owners = childNames(players);
        if (owners.size() != 1 || !LibraryPath.validPlayerFolder(owners.get(0))) return null;
        return UUID.fromString(owners.get(0));
    }

    private static List<String> childNames(Path folder) {
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder)) {
            for (Path child : stream) names.add(child.getFileName().toString());
        } catch (IOException e) {
            return List.of();
        }
        return names;
    }

    private void addBin(String name, Bin bin) {
        trashBins.put(name, bin);
        trashByArea.merge(bin.owner(), bin.bytes(), Long::sum);
    }

    /**
     * Drops the oldest deletions of {@code area} (never another area's, and never {@code keep}) until its bytes fit its
     * share ({@link Settings#trashCap}).
     */
    private void fitArea(UUID area, String keep) {
        long cap = settings.trashCap(area);
        if (trashByArea.getOrDefault(area, 0L) <= cap) return;
        for (Map.Entry<String, Bin> entry : new ArrayList<>(trashBins.entrySet())) {
            if (trashByArea.getOrDefault(area, 0L) <= cap) return;
            if (!java.util.Objects.equals(entry.getValue().owner(), area) || entry.getKey().equals(keep)) continue;
            dropBin(entry.getKey());
        }
    }

    /** Drops the deletions older than {@code trashDays} (their names sort by time, so the oldest come first). */
    private void purgeOld() {
        if (settings.trashDays() <= 0) return;
        long cutoff = clock.getAsLong() - settings.trashDays() * 86_400_000L;
        while (!trashBins.isEmpty() && stampMillis(trashBins.firstKey()) < cutoff) dropBin(trashBins.firstKey());
    }

    /** When a deletion was made, from its bin's name; -1 when the name is not a deletion's. */
    private static long stampMillis(String name) {
        java.util.regex.Matcher matcher = TRASH_BIN.matcher(name);
        if (!matcher.matches()) return -1;
        try {
            return java.time.LocalDateTime.parse(matcher.group(1), TRASH_STAMP).toInstant(java.time.ZoneOffset.UTC)
                    .toEpochMilli();
        } catch (java.time.DateTimeException e) {
            return -1;
        }
    }

    /** Deletes one deletion from the trash for good. */
    private void dropBin(String name) {
        Bin dropped = trashBins.remove(name);
        if (dropped == null) return;
        long left = trashByArea.getOrDefault(dropped.owner(), 0L) - dropped.bytes();
        if (left > 0) {
            trashByArea.put(dropped.owner(), left);
        } else {
            trashByArea.remove(dropped.owner());
        }
        Path bin = root.resolve(TRASH_FOLDER).resolve(name);
        if (plainDirectory(bin)) deleteTree(bin);
    }

    /** Whether {@code path} is a folder reached without following a link or junction. */
    private static boolean plainDirectory(Path path) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return attrs.isDirectory() && !attrs.isSymbolicLink() && !attrs.isOther();
        } catch (IOException e) {
            return false;
        }
    }

    /** The bytes of the regular files under {@code top}, not following links. */
    private static long treeBytes(Path top) {
        long[] bytes = {0};
        try {
            Files.walkFileTree(top, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return attrs.isOther() && !dir.equals(top) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()) bytes[0] += attrs.size();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOG.warn("Sculptory library: could not count {}", top, e);
        }
        return bytes[0];
    }

    /**
     * The real location of an existing folder, which must be reached without a link anywhere on the way: a change
     * through a link could land in another area (another player's folder) than its path says.
     */
    private Path plainFolder(LibraryPath folder, Path realRoot) throws LibraryException {
        Path real = folder.isRoot() ? realRoot : realExisting(folder.resolve(root), realRoot, folder);
        if (!real.equals(folder.resolve(realRoot))) {
            throw new LibraryException(RejectReason.INVALID, folder + " is reached through a link; an admin changes it on disk");
        }
        requireSpelling(folder, real, realRoot);
        if (!Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
            throw new LibraryException(RejectReason.INVALID, "not a folder: " + folder);
        }
        return real;
    }

    /**
     * Refuses a path spelt differently from the entry on disk ({@code trees/OAK.schem} for {@code trees/oak.schem},
     * which a file system that ignores case would open): per-asset access is keyed by the exact name, so every read,
     * listing, change and access change names the entry exactly as it is spelt on disk. {@code real} is the entry's
     * real path (case as on disk), inside {@code realRoot}. Pure given the paths.
     */
    static void requireSpelling(LibraryPath shown, Path real, Path realRoot) throws LibraryException {
        if (!spelt(shown, realRoot.relativize(real))) throw notFound(shown);
    }

    /** Whether {@code relative} (a real path under the root) is spelt segment for segment as {@code shown}. */
    private static boolean spelt(LibraryPath shown, Path relative) {
        if (shown.isRoot()) return true;
        List<String> segments = shown.segments();
        if (relative.getNameCount() != segments.size()) return false;
        for (int i = 0; i < segments.size(); i++) {
            if (!relative.getName(i).toString().equals(segments.get(i))) return false;
        }
        return true;
    }

    /**
     * {@link #requireSpelling} for an existing entry that may be reached through a link inside the library (reads and
     * listings allow those): its real path then differs from the path shown, so each level's own directory entry
     * decides, by the exact name.
     */
    private void requireSpelt(LibraryPath shown) throws LibraryException {
        if (shown.isRoot()) return;
        Path lexical = shown.resolve(root);
        try {
            if (spelt(shown, realRoot().relativize(lexical.toRealPath()))) return;
        } catch (NoSuchFileException e) {
            throw notFound(shown);
        } catch (IOException e) {
            throw ioFailure("resolve", shown, e);
        }
        Path current = root;
        for (String segment : shown.segments()) {
            if (!hasEntryNamed(current, segment)) throw notFound(shown);
            current = current.resolve(segment);
        }
    }

    /** Whether {@code dir} holds an entry named exactly {@code name} (a file system may ignore case; this does not). */
    private static boolean hasEntryNamed(Path dir, String name) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                if (child.getFileName().toString().equals(name)) return true;
            }
        } catch (IOException e) {
            return false;
        }
        return false;
    }

    /** {@link #requireSpelling} for an existing regular file whose folders are plain (no link on the way). */
    private static void requireEntrySpelling(LibraryPath shown, Path entry, Path realRoot) throws LibraryException {
        Path real;
        try {
            real = entry.toRealPath(); // a link would have been refused by entryAttributes already
        } catch (NoSuchFileException e) {
            throw notFound(shown);
        } catch (IOException e) {
            throw ioFailure("resolve", shown, e);
        }
        requireSpelling(shown, real, realRoot);
    }

    /** {@link #plainFolder} for the deepest level of {@code folder} that exists ({@code missing} levels do not). */
    private void plainExistingPart(LibraryPath folder, int missing, Path realRoot) throws LibraryException {
        LibraryPath existing = folder;
        for (int i = 0; i < missing; i++) existing = existing.parent();
        plainFolder(existing, realRoot);
    }

    /** The attributes of an existing entry, not following links; a link (or junction) is refused. */
    private static BasicFileAttributes entryAttributes(Path path, LibraryPath shown) throws LibraryException {
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            throw notFound(shown);
        } catch (IOException e) {
            throw ioFailure("read", shown, e);
        }
        if (attrs.isSymbolicLink() || attrs.isOther()) {
            throw new LibraryException(RejectReason.INVALID, shown + " is a link; an admin changes it on disk");
        }
        return attrs;
    }

    /** A file path must name a regular file, a folder path a folder. */
    private static void requireKind(BasicFileAttributes attrs, LibraryPath path) throws LibraryException {
        if (path.isFile() ? !attrs.isRegularFile() : !attrs.isDirectory()) {
            throw new LibraryException(RejectReason.INVALID, (path.isFile() ? "not a file: " : "not a folder: ") + path);
        }
    }

    /**
     * The levels of {@code folder} to create before something is put in it: none when it exists (and is a folder
     * inside the library); only the reserved player-folder levels may be missing.
     */
    private List<Path> targetFolders(LibraryPath folder, Path realRoot) throws LibraryException {
        List<Path> missing = missingFolders(folder, realRoot);
        if (!missing.isEmpty() && !(folder.inPlayersArea() && folder.segments().size() <= 2)) {
            throw new LibraryException(RejectReason.INVALID, "no such folder: " + folder);
        }
        return missing;
    }

    /** Creates {@code levels} in order (each must not exist); on failure removes what it made and refuses. */
    private List<Path> createFolders(List<Path> levels, LibraryPath shown) throws LibraryException {
        List<Path> created = new ArrayList<>();
        try {
            for (Path level : levels) {
                Files.createDirectory(level);
                created.add(level);
            }
        } catch (FileAlreadyExistsException e) {
            removeCreated(created);
            throw new LibraryException(RejectReason.INVALID, "already exists: " + shown);
        } catch (IOException e) {
            removeCreated(created);
            throw ioFailure("create", shown, e);
        }
        return created;
    }

    private static void removeCreated(List<Path> created) {
        for (int i = created.size() - 1; i >= 0; i--) deleteQuietly(created.get(i));
    }

    /** Whether two existing paths are one directory entry (a name that differs only in case on such a file system). */
    private static boolean sameEntry(Path a, Path b) throws LibraryException {
        try {
            Path realA = a.toRealPath(LinkOption.NOFOLLOW_LINKS);
            Path realB = b.toRealPath(LinkOption.NOFOLLOW_LINKS);
            return realA.equals(realB) && realA.getFileName().toString().equals(realB.getFileName().toString())
                    && Files.isSameFile(a, b);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * A change of case only ({@code Oak.schem} to {@code oak.schem}) where the file system ignores case: plain moves
     * treat the target as the file itself, so it goes through a hidden intermediate name, each step refusing to
     * replace anything; a failed second step moves it back.
     */
    private static void renameInTwoSteps(Path source, Path target) throws IOException {
        Path middle = source.resolveSibling("." + UUID.randomUUID() + RENAME_SUFFIX);
        Files.move(source, middle);
        try {
            Files.move(middle, target);
        } catch (IOException e) {
            try {
                Files.move(middle, source);
            } catch (IOException back) {
                e.addSuppressed(back);
                LOG.error("Sculptory library: {} was left as {} after a failed rename", source, middle, back);
            }
            throw e;
        }
    }

    /** Refuses a folder rename that would take a path inside it past {@link LibraryPath#MAX_LENGTH} characters. */
    private void checkLengthsInside(Path folder, LibraryPath to) throws LibraryException {
        int[] longest = {0};
        try {
            Files.walkFileTree(folder, EnumSet.noneOf(java.nio.file.FileVisitOption.class), LibraryPath.MAX_DEPTH,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            note(dir);
                            return attrs.isOther() && !dir.equals(folder) ? FileVisitResult.SKIP_SUBTREE
                                    : FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            note(file);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            return FileVisitResult.CONTINUE;
                        }

                        private void note(Path entry) {
                            if (entry.equals(folder)) return;
                            Path relative = folder.relativize(entry);
                            for (Path name : relative) {
                                if (!LibraryPath.validName(name.toString())) return; // not a library entry
                            }
                            int length = 0;
                            for (Path name : relative) length += 1 + name.toString().length();
                            longest[0] = Math.max(longest[0], length);
                        }
                    });
        } catch (IOException e) {
            throw ioFailure("read", to, e);
        }
        if (to.toString().length() + longest[0] > LibraryPath.MAX_LENGTH) {
            throw new LibraryException(RejectReason.INVALID, "a path inside " + to + " would be longer than "
                    + LibraryPath.MAX_LENGTH + " characters");
        }
    }

    /** Moves the index entries of {@code from} (a file, or everything under a folder) to {@code to}. */
    private void reindex(LibraryPath from, LibraryPath to, Path target) {
        TreeMap<String, IndexRecord> map = index();
        boolean changed = false;
        if (from.isFile()) {
            IndexRecord record = map.remove(from.toString());
            if (record != null) {
                changed = true;
                try {
                    // A rename keeps size and modification time. If they differ, the record was stale already (the
                    // file changed on disk): it is dropped, and the next listing hashes the file again.
                    BasicFileAttributes attrs = Files.readAttributes(target, BasicFileAttributes.class);
                    if (attrs.size() == record.size && attrs.lastModifiedTime().toMillis() == record.mtime) {
                        map.put(to.toString(), record);
                    }
                } catch (IOException e) {
                    LOG.warn("Sculptory library: could not index {}", to, e);
                }
            }
        } else {
            String prefix = from + "/";
            List<Map.Entry<String, IndexRecord>> inside =
                    new ArrayList<>(map.subMap(prefix, true, prefix + Character.MAX_VALUE, true).entrySet());
            for (Map.Entry<String, IndexRecord> entry : inside) {
                map.remove(entry.getKey());
                map.put(to + entry.getKey().substring(from.toString().length()), entry.getValue());
                changed = true;
            }
        }
        if (changed) {
            indexDirty = true;
            saveIndexSoon();
        }
    }

    /** Why a folder could not be deleted: what it holds, as the library shows it. */
    private static String notEmpty(Path folder, LibraryPath shown) {
        int listed = 0;
        int other = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder)) {
            for (Path child : stream) {
                String name = child.getFileName().toString();
                boolean entry = LibraryPath.validName(name)
                        && (Files.isDirectory(child) || LibraryPath.Kind.of(name) != null);
                if (entry) {
                    listed++;
                } else {
                    other++;
                }
            }
        } catch (IOException e) {
            return "the folder is not empty: " + shown;
        }
        if (listed > 0) return "the folder is not empty: " + shown + " holds " + listed + " entr" + (listed == 1 ? "y" : "ies");
        return "the folder holds " + other + " file(s) the library doesn't show; an admin removes them on disk: " + shown;
    }

    /** A move (or a move into the trash, {@code to} null) that the file system refused. */
    private static LibraryException moveFailure(IOException e, LibraryPath from, LibraryPath to) {
        if (to != null && (e instanceof FileAlreadyExistsException || e instanceof DirectoryNotEmptyException)) {
            return new LibraryException(RejectReason.INVALID, "already exists: " + to);
        }
        if (e instanceof NoSuchFileException) return notFound(from);
        return ioFailure(to == null ? "delete" : "move", from, e);
    }

    private static LibraryException notFound(LibraryPath path) {
        return new LibraryException(RejectReason.INVALID, "not found: " + path);
    }

    /** Deletes a folder tree without following links (a link or junction is removed itself). Best effort. */
    private static void deleteTree(Path top) {
        if (!Files.exists(top, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            Files.walkFileTree(top, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (attrs.isSymbolicLink() || attrs.isOther()) {
                        Files.deleteIfExists(dir);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOG.warn("Sculptory library: could not delete {}", top, e);
        }
    }

    /**
     * The readable file whose bytes have this SHA-256, among the indexed files (every file listed, read or written
     * since the index was built) that are unchanged since. Per-asset access applies (its folder's access file is read
     * again), so a hash never leads to a payload the viewer may not read.
     */
    public synchronized Optional<LibraryPath> find(String sha256, Viewer viewer) {
        Path realRoot;
        try {
            realRoot = realRoot();
        } catch (LibraryException e) {
            return Optional.empty();
        }
        for (Map.Entry<String, IndexRecord> entry : index().entrySet()) {
            if (!sha256.equals(entry.getValue().sha256)) continue;
            LibraryPath path;
            try {
                path = LibraryPath.file(entry.getKey());
            } catch (LibraryPathException e) {
                continue;
            }
            try {
                Path real = realExisting(path.resolve(root), realRoot, path);
                BasicFileAttributes attrs = Files.readAttributes(real, BasicFileAttributes.class);
                if (attrs.isRegularFile() && attrs.size() == entry.getValue().size
                        && attrs.lastModifiedTime().toMillis() == entry.getValue().mtime
                        && allowed(loadAccess(path.parent(), real.getParent()), path, viewer)) {
                    return Optional.of(path);
                }
            } catch (LibraryException | IOException e) {
                // stale entry
            }
        }
        return Optional.empty();
    }

    // ================================================================== per-asset access

    /**
     * Who may load the file (per-asset access), as its folder's access file says now. Only who may change it may
     * ask ({@link #mayChangeAccess}: {@code NO_PERMISSION}); a missing file is {@code INVALID}, and so is a folder
     * whose access file is corrupt (an admin fixes it on disk).
     */
    public synchronized AssetAccess access(LibraryPath file, Viewer viewer) throws LibraryException {
        Path real = accessTarget(file, viewer);
        AccessFile.Loaded loaded = loadAccess(file.parent(), real.getParent());
        if (loaded.corrupt()) throw corruptAccess(file.parent());
        return loaded.of(file.name());
    }

    /**
     * Sets who may load the file (per-asset access); rights as for {@link #access}. Every grantee must have a UUID
     * (the caller resolves names). Written atomically into the folder's access file under this lock, so two changes
     * to different entries of one folder both persist.
     *
     * @return the access before the change
     */
    public synchronized AssetAccess setAccess(LibraryPath file, AssetAccess next, Viewer viewer) throws LibraryException {
        Objects.requireNonNull(next);
        if (!next.resolved()) throw new LibraryException(RejectReason.INVALID, "every granted player needs a UUID");
        Path real = accessTarget(file, viewer);
        Path folder = real.getParent();
        AccessFile.Loaded loaded = loadAccess(file.parent(), folder);
        if (loaded.corrupt()) throw corruptAccess(file.parent());
        AssetAccess before = loaded.of(file.name());
        Map<String, AssetAccess> entries = new TreeMap<>(loaded.entries());
        if (next.restricted()) {
            entries.put(file.name(), next);
        } else {
            entries.remove(file.name());
        }
        writeAccess(file.parent(), folder, entries);
        LOG.info("Sculptory library: access of {} set by {} to {}{}", file, viewer.player(), next.mode(),
                next.restricted() ? " (" + next.players().size() + " player(s))" : "");
        return before;
    }

    /**
     * The virtual {@value LibraryPath#SHARED} folder (per-asset access): every file in another player's folder whose
     * access lists the viewer, with its real path, as a listing gives it (sorted by path, at most {@code maxListing},
     * {@code truncated} beyond). Walks the other players' folders without following links; the viewer's own folder is
     * theirs anyway and is skipped.
     */
    public synchronized Listing sharedWithMe(Viewer viewer) throws LibraryException {
        Path realRoot = realRoot();
        Path playersRoot = root.resolve(LibraryPath.PLAYERS);
        List<LibraryPath> files = new ArrayList<>();
        if (plainDirectory(playersRoot)) {
            try (DirectoryStream<Path> owners = Files.newDirectoryStream(playersRoot)) {
                for (Path ownerDir : owners) {
                    String name = ownerDir.getFileName().toString();
                    if (!LibraryPath.validPlayerFolder(name) || name.equals(viewer.player().toString())
                            || !plainDirectory(ownerDir)) {
                        continue;
                    }
                    collectGranted(ownerDir, LibraryPath.ROOT.child(LibraryPath.PLAYERS, false).child(name, false),
                            viewer, files, 2);
                }
            } catch (IOException e) {
                throw ioFailure("list", LibraryPath.ROOT, e);
            }
        }
        files.sort(Comparator.comparing(LibraryPath::toString));
        boolean truncated = files.size() > settings.maxListing();
        List<Entry> entries = new ArrayList<>();
        long[] hashBudget = {MAX_LISTING_HASH_BYTES};
        for (LibraryPath file : files) {
            if (entries.size() >= settings.maxListing()) break;
            Entry entry = fileEntry(file, realRoot, access.get(file.parent().toString()), hashBudget);
            if (entry != null) entries.add(entry);
        }
        saveIndexSoon();
        return new Listing(LibraryPath.SHARED, entries, truncated);
    }

    /** Adds to {@code out} the files under {@code dir} (library path {@code folder}) granted to the viewer. */
    private void collectGranted(Path dir, LibraryPath folder, Viewer viewer, List<LibraryPath> out, int depth) {
        AccessFile.Loaded loaded = loadAccess(folder, dir);
        for (Map.Entry<String, AssetAccess> entry : loaded.entries().entrySet()) {
            if (!entry.getValue().names(viewer.player())) continue;
            Path file = dir.resolve(entry.getKey());
            if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) out.add(folder.child(entry.getKey(), true));
        }
        if (depth >= LibraryPath.MAX_DEPTH - 1) return;
        try (DirectoryStream<Path> children = Files.newDirectoryStream(dir)) {
            for (Path child : children) {
                String name = child.getFileName().toString();
                if (!LibraryPath.validName(name) || !plainDirectory(child)) continue;
                collectGranted(child, folder.child(name, false), viewer, out, depth + 1);
            }
        } catch (IOException e) {
            LOG.warn("Sculptory library: could not walk {}", folder, e);
        }
    }

    /** The real path of an existing file whose access the viewer may see and change. */
    private Path accessTarget(LibraryPath file, Viewer viewer) throws LibraryException {
        if (!file.isFile()) throw new LibraryException(RejectReason.INVALID, "not a file: " + file);
        if (!mayChangeAccess(file, viewer)) {
            throw new LibraryException(RejectReason.NO_PERMISSION, file.owner().isEmpty()
                    ? "changing who may load a shared entry needs sculptory.library.write (" + file + ")"
                    : "another player's entry needs sculptory.admin (" + file + ")");
        }
        Path realRoot = realRoot();
        plainFolder(file.parent(), realRoot);
        Path source = file.resolve(root);
        requireKind(entryAttributes(source, file), file);
        requireEntrySpelling(file, source, realRoot);
        return source;
    }

    private static LibraryException corruptAccess(LibraryPath folder) {
        return new LibraryException(RejectReason.INVALID, "the access file of " + (folder.isRoot() ? "the library folder" : folder)
                + " is unreadable; an admin fixes " + ACCESS_FILE + " on disk");
    }

    /**
     * The folder's access as on disk now: read again when the file changed (by size and time) since it was last
     * read, else as cached. A corrupt file is logged once until it reads again.
     */
    private AccessFile.Loaded loadAccess(LibraryPath folder, Path realFolder) {
        String key = folder.toString();
        AccessFile.Loaded known = access.known(key);
        BasicFileAttributes attrs = AccessFile.attributes(realFolder);
        if (known != null && (attrs == null ? known.missing() : known.matches(attrs))) return known;
        AccessFile.Loaded loaded = AccessFile.read(realFolder);
        if (loaded.corrupt()) {
            if (corruptLogged.add(key)) {
                LOG.error("Sculptory library: {} in {} is unreadable; its entries are open to admins only until it is"
                        + " fixed or removed", ACCESS_FILE, folder.isRoot() ? "the library folder" : folder);
            }
        } else {
            corruptLogged.remove(key);
        }
        access.put(key, loaded);
        return loaded;
    }

    /** Reads every access file in the library once (server start), so the cache answers for every folder. */
    private void loadAllAccess(Path realRoot) {
        Path trash = root.resolve(TRASH_FOLDER);
        try {
            Files.walkFileTree(root, EnumSet.noneOf(java.nio.file.FileVisitOption.class), LibraryPath.MAX_DEPTH + 2,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            if (dir.equals(trash)) return FileVisitResult.SKIP_SUBTREE;
                            if (!dir.equals(root) && (attrs.isSymbolicLink() || attrs.isOther() || !insideRoot(dir, realRoot))) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            LibraryPath folder = folderOf(dir);
                            if (folder == null) return FileVisitResult.SKIP_SUBTREE;
                            if (Files.exists(dir.resolve(ACCESS_FILE), LinkOption.NOFOLLOW_LINKS)) loadAccess(folder, dir);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            LOG.warn("Sculptory library: could not read the access files", e);
        }
    }

    /** The library path of a folder on disk, or {@code null} when it is not one the library names. */
    private LibraryPath folderOf(Path dir) {
        if (dir.equals(root)) return LibraryPath.ROOT;
        try {
            return LibraryPath.folder(root.relativize(dir).toString().replace('\\', '/'));
        } catch (LibraryPathException | IllegalArgumentException e) {
            return null;
        }
    }

    /** Refuses a read of the file the viewer may not make, by its folder's access file as on disk now. */
    private void requireReadable(LibraryPath file, Path realFolder, Viewer viewer) throws LibraryException {
        loadAccess(file.parent(), realFolder);
        if (!mayRead(file, viewer)) throw readRefusal(file, viewer);
    }

    /**
     * Why a read is refused, from the access as last read. A folder that is not the viewer's (and no grant there
     * names them) is {@code NO_PERMISSION} whatever the name: the folder cannot be listed either. Anywhere the viewer
     * may look, a restricted entry they may not read answers exactly as a missing one ({@link #notShared}), so names
     * cannot be enumerated.
     */
    public LibraryException readRefusal(LibraryPath file, Viewer viewer) {
        AccessFile.Loaded loaded = access.get(file.parent().toString());
        boolean named = !loaded.corrupt() && loaded.of(file.name()).names(viewer.player());
        if (!mayReadArea(file, viewer) && !named) {
            return new LibraryException(RejectReason.NO_PERMISSION, "not your folder: " + file);
        }
        return notShared(file);
    }

    /** The one answer for a file that is missing or not shared with the viewer: neither tells which. */
    static LibraryException notShared(LibraryPath file) {
        return new LibraryException(RejectReason.INVALID, "not found, or not shared with you: " + file);
    }

    /**
     * The access of a file about to be renamed, moved or deleted (per-asset access), as on disk now; a corrupt access
     * file refuses the change (its entries cannot be told apart from restricted ones).
     */
    private AssetAccess grantOf(LibraryPath file, Path realFolder) throws LibraryException {
        AccessFile.Loaded loaded = loadAccess(file.parent(), realFolder);
        if (loaded.corrupt()) throw corruptAccess(file.parent());
        return loaded.of(file.name());
    }

    /** Adds a grant to a folder's access file (a corrupt one refuses). */
    private void putGrant(LibraryPath file, Path realFolder, AssetAccess grant) throws LibraryException {
        AccessFile.Loaded loaded = loadAccess(file.parent(), realFolder);
        if (loaded.corrupt()) throw corruptAccess(file.parent());
        Map<String, AssetAccess> entries = new TreeMap<>(loaded.entries());
        entries.put(file.name(), grant);
        writeAccess(file.parent(), realFolder, entries);
    }

    /** Removes a file's grant from its folder's access file; a failure is logged (a stale grant only restricts). */
    private void dropGrantQuietly(LibraryPath file, Path realFolder) {
        AccessFile.Loaded loaded = loadAccess(file.parent(), realFolder);
        if (loaded.corrupt() || !loaded.entries().containsKey(file.name())) return;
        Map<String, AssetAccess> entries = new TreeMap<>(loaded.entries());
        entries.remove(file.name());
        try {
            writeAccess(file.parent(), realFolder, entries);
        } catch (LibraryException e) {
            LOG.warn("Sculptory library: the access of {} was left in its old folder's {}", file, ACCESS_FILE);
        }
    }

    /** Writes a folder's access file and caches what was written. */
    private void writeAccess(LibraryPath folder, Path realFolder, Map<String, AssetAccess> entries) throws LibraryException {
        try {
            AccessFile.write(realFolder, entries);
        } catch (IOException e) {
            throw ioFailure("write access of", folder, e);
        }
        BasicFileAttributes attrs = AccessFile.attributes(realFolder);
        access.put(folder.toString(), attrs == null ? AccessFile.Loaded.MISSING
                : new AccessFile.Loaded(entries, false, attrs.size(), attrs.lastModifiedTime().toMillis()));
        corruptLogged.remove(folder.toString());
    }

    /** The index's knowledge of a file (for tests and search), or empty. */
    public synchronized Optional<Info> info(LibraryPath file) {
        IndexRecord record = index().get(file.toString());
        return record == null ? Optional.empty() : Optional.of(record.info());
    }

    /** Records what a parse of the file learned (dimensions, tags), if the index entry still matches its hash. */
    public synchronized void remember(LibraryPath file, String sha256, Info info) {
        IndexRecord record = index().get(file.toString());
        if (record == null || !sha256.equals(record.sha256)) return;
        index().put(file.toString(), new IndexRecord(record.size, record.mtime, record.sha256, info, true));
        indexDirty = true;
        saveIndexSoon();
    }

    /** Writes the index now if it has unsaved changes (server stop, periodic flush). */
    public synchronized void flush() {
        if (indexDirty) saveIndex();
    }

    // ================================================================== internals

    /**
     * Whether a directory entry of {@code folder} is shown: valid names only (so hidden, temporary and index files
     * never are); in {@code _players}, only the viewer's own folder (every one for admins); at the root,
     * {@code _players} only when the viewer has a folder there (or is an admin).
     */
    private boolean visible(LibraryPath folder, boolean playersRoot, String name, Viewer viewer) {
        if (playersRoot) {
            return LibraryPath.validPlayerFolder(name) && (viewer.admin() || name.equals(viewer.player().toString()));
        }
        if (folder.isRoot() && name.equals(LibraryPath.PLAYERS)) {
            return viewer.admin() || Files.isDirectory(root.resolve(LibraryPath.PLAYERS).resolve(viewer.player().toString()));
        }
        return LibraryPath.validName(name);
    }

    private static void checkQuota(Counter used, long bytes, int files, int folders, long maxBytes, int maxFiles,
                                   int maxFolders, String what) throws LibraryException {
        if (used.bytes + bytes > maxBytes) {
            throw new LibraryException(RejectReason.TOO_LARGE, what + " is full (" + used.bytes + " of " + maxBytes + " bytes)");
        }
        if ((long) used.files + files > maxFiles) {
            throw new LibraryException(RejectReason.TOO_LARGE, what + " holds " + used.files + " files (at most " + maxFiles + ")");
        }
        if ((long) used.folders + folders > maxFolders) {
            throw new LibraryException(RejectReason.TOO_LARGE, what + " holds " + used.folders + " folders (at most "
                    + maxFolders + ")");
        }
    }

    /** The folder levels of {@code folder} that do not exist yet, outermost first; existing levels are checked. */
    private List<Path> missingFolders(LibraryPath folder, Path realRoot) throws LibraryException {
        List<Path> missing = new ArrayList<>();
        Path current = root;
        for (String segment : folder.segments()) {
            current = current.resolve(segment);
            if (!missing.isEmpty() || !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                missing.add(current);
                continue;
            }
            Path real = realExisting(current, realRoot, folder);
            if (!Files.isDirectory(real)) throw new LibraryException(RejectReason.INVALID, folder + " is not a folder");
        }
        return missing;
    }

    /** How many of {@code folders} lie in (or are) the player's folder. */
    private int countInPlayerFolder(List<Path> folders, UUID player) {
        Path own = root.resolve(LibraryPath.PLAYERS).resolve(player.toString());
        int n = 0;
        for (Path folder : folders) {
            if (folder.startsWith(own)) n++;
        }
        return n;
    }

    /** Counts usage with one walk (not following links) and deletes temporary files; once. */
    private void ensureUsage(Path realRoot) throws LibraryException {
        if (total != null) return;
        Counter all = new Counter();
        Map<UUID, Counter> byPlayer = new HashMap<>();
        Path playersRoot = root.resolve(LibraryPath.PLAYERS);
        Path trash = root.resolve(TRASH_FOLDER);
        try {
            Files.walkFileTree(root, EnumSet.noneOf(java.nio.file.FileVisitOption.class), LibraryPath.MAX_DEPTH + 2,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            if (dir.equals(root)) return FileVisitResult.CONTINUE;
                            // Deleted files are not the library's any more: the trash counts toward no quota.
                            if (dir.equals(trash)) return FileVisitResult.SKIP_SUBTREE;
                            // Never descend through links or junctions (Windows reports junctions as directories
                            // that are also "other"), nor into anything that resolves outside the library.
                            if (attrs.isSymbolicLink() || attrs.isOther() || !insideRoot(dir, realRoot)) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            all.folders++;
                            Counter owner = ownerOf(dir, playersRoot, byPlayer);
                            if (owner != null) owner.folders++;
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            String name = file.getFileName().toString();
                            if (TEMP_NAME.matcher(name).matches() && attrs.isRegularFile() && !attrs.isOther()) {
                                deleteQuietly(file);
                                return FileVisitResult.CONTINUE;
                            }
                            if (!attrs.isRegularFile() || LibraryPath.Kind.of(name) == null || name.startsWith(".")) {
                                return FileVisitResult.CONTINUE;
                            }
                            all.bytes += attrs.size();
                            all.files++;
                            Counter owner = ownerOf(file, playersRoot, byPlayer);
                            if (owner != null) {
                                owner.bytes += attrs.size();
                                owner.files++;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            throw ioFailure("count", LibraryPath.ROOT, e);
        }
        total = all;
        players.clear();
        players.putAll(byPlayer);
    }

    private static boolean insideRoot(Path path, Path realRoot) {
        try {
            return path.toRealPath().startsWith(realRoot);
        } catch (IOException e) {
            return false;
        }
    }

    private static Counter ownerOf(Path path, Path playersRoot, Map<UUID, Counter> byPlayer) {
        if (!path.startsWith(playersRoot) || path.getNameCount() <= playersRoot.getNameCount()) return null;
        String name = path.getName(playersRoot.getNameCount()).toString();
        if (!LibraryPath.validPlayerFolder(name)) return null;
        return byPlayer.computeIfAbsent(UUID.fromString(name), k -> new Counter());
    }

    private Path realRoot() throws LibraryException {
        try {
            Files.createDirectories(root);
            return root.toRealPath();
        } catch (IOException e) {
            throw ioFailure("open", LibraryPath.ROOT, e);
        }
    }

    /** The real location of an existing entry, which must lie inside the real root. */
    private static Path realExisting(Path path, Path realRoot, LibraryPath shown) throws LibraryException {
        Path real;
        try {
            real = path.toRealPath();
        } catch (NoSuchFileException e) {
            throw new LibraryException(RejectReason.INVALID, "not found: " + shown);
        } catch (IOException e) {
            throw ioFailure("resolve", shown, e);
        }
        if (!real.startsWith(realRoot)) {
            throw new LibraryException(RejectReason.INVALID, shown + " leads outside the library");
        }
        return real;
    }

    private static Path realOrNull(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return null;
        }
    }

    /** Logs the full error (with server paths) and returns a refusal that names only the library path. */
    private static LibraryException ioFailure(String action, LibraryPath path, IOException e) {
        LOG.warn("Sculptory library: could not {} {}", action, path.isRoot() ? "the library folder" : path, e);
        return new LibraryException(RejectReason.INVALID, "file system error (" + action + " "
                + (path.isRoot() ? "library" : path.toString()) + ")");
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOG.warn("Sculptory library: could not delete {}", path, e);
        }
    }

    /** The largest file of this kind read or written: {@code maxFileBytes}, and for a palette its own cap too. */
    private long maxBytes(LibraryPath file) {
        return file.isPalette() ? Math.min(PaletteFile.MAX_BYTES, settings.maxFileBytes()) : settings.maxFileBytes();
    }

    private static byte[] readBounded(Path file, long max, LibraryPath shown) throws IOException, LibraryException {
        long size = Files.size(file);
        if (size > max) throw new LibraryException(RejectReason.TOO_LARGE, shown + " is " + size + " bytes > " + max);
        try (InputStream in = Files.newInputStream(file)) {
            byte[] bytes = in.readNBytes((int) max + 1);
            if (bytes.length > max) throw new LibraryException(RejectReason.TOO_LARGE, shown + " grew past " + max + " bytes");
            return bytes;
        }
    }

    /**
     * The file's index record, refreshed when size or time changed ({@code bytes} if already read). Unindexed files
     * over {@code hashBudget} (or the file cap) are recorded without a hash.
     */
    private IndexRecord indexed(LibraryPath file, Path real, byte[] bytes, long hashBudget)
            throws IOException, LibraryException {
        BasicFileAttributes attrs = Files.readAttributes(real, BasicFileAttributes.class);
        long size = attrs.size(), mtime = attrs.lastModifiedTime().toMillis();
        IndexRecord record = index().get(file.toString());
        if (record != null && record.size == size && record.mtime == mtime && record.sha256 != null
                && !record.sha256.isEmpty()
                && (bytes == null || record.sha256.equals(Sha256.digest(bytes).hex()))) {
            return new IndexRecord(record.size, record.mtime, record.sha256, record.info, false);
        }
        if (bytes == null && (size > settings.maxFileBytes() || size > hashBudget)) {
            // Not hashed now: not worth indexing an empty entry.
            return new IndexRecord(size, mtime, "", new Info(null, 0, List.of()), false);
        }
        byte[] content = bytes != null ? bytes : readBounded(real, settings.maxFileBytes(), file);
        record = new IndexRecord(size, mtime, Sha256.digest(content).hex(), header(content), true);
        index().put(file.toString(), record);
        indexDirty = true;
        return record;
    }

    /**
     * Dimensions and tags from a schematic's NBT, whatever its format (players write the library: untrusted limits),
     * best effort.
     */
    static Info header(byte[] bytes) {
        SchematicFiles.Header header = SchematicFiles.header(bytes);
        int[] dims = header.dims();
        return new Info(dims, dims == null ? 0 : (long) dims[0] * dims[1] * dims[2], header.tags());
    }

    private TreeMap<String, IndexRecord> index() {
        if (index != null) return index;
        index = new TreeMap<>();
        Path file = root.resolve(INDEX_FILE);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return index;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            IndexFile read = GSON.fromJson(reader, IndexFile.class);
            if (read != null && read.entries != null) {
                read.entries.forEach((path, dto) -> {
                    if (dto != null && path != null) index.put(path, dto.toRecord());
                });
            }
        } catch (IOException | JsonParseException | IllegalStateException e) {
            LOG.warn("Sculptory library: ignoring an unreadable {} (it is rebuilt as needed): {}", file, e.toString());
            index.clear();
        }
        return index;
    }

    /** Saves the index when it changed and the last save is at least {@value #INDEX_SAVE_INTERVAL_MILLIS} ms old. */
    private void saveIndexSoon() {
        if (!indexDirty) return;
        long now = clock.getAsLong();
        if (indexSavedAt != Long.MIN_VALUE && now - indexSavedAt < INDEX_SAVE_INTERVAL_MILLIS) return;
        saveIndex();
    }

    private void saveIndex() {
        IndexFile out = new IndexFile();
        index().forEach((path, record) -> out.entries.put(path, IndexDto.of(record)));
        Path file = root.resolve(INDEX_FILE);
        Path temp = root.resolve("." + UUID.randomUUID() + ".index" + TEMP_SUFFIX);
        try {
            Files.createDirectories(root);
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                GSON.toJson(out, writer);
            }
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            indexDirty = false;
            indexSavedAt = clock.getAsLong();
        } catch (IOException e) {
            LOG.warn("Sculptory library: could not save {}", file, e);
        } finally {
            deleteQuietly(temp);
        }
    }

    /** {@code hashed}: computed by this call (for the listing's hash budget); not stored. */
    private record IndexRecord(long size, long mtime, String sha256, Info info, boolean hashed) {}

    /** Gson form of the index. */
    private static final class IndexFile {
        int version = 1;
        TreeMap<String, IndexDto> entries = new TreeMap<>();
    }

    private static final class IndexDto {
        long size;
        long mtime;
        String sha256;
        int[] dims;
        long cells;
        List<String> tags;

        static IndexDto of(IndexRecord record) {
            IndexDto dto = new IndexDto();
            dto.size = record.size;
            dto.mtime = record.mtime;
            dto.sha256 = record.sha256;
            dto.dims = record.info.dims();
            dto.cells = record.info.cells();
            dto.tags = record.info.tags().isEmpty() ? null : record.info.tags();
            return dto;
        }

        IndexRecord toRecord() {
            int[] d = dims != null && dims.length == 3 ? dims : null;
            return new IndexRecord(size, mtime, sha256, new Info(d, cells, tags), false);
        }
    }
}
