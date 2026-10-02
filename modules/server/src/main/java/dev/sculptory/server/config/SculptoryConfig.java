package dev.sculptory.server.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sculptory.core.history.HistoryLimits;
import dev.sculptory.protocol.v2.Limits;
import dev.sculptory.server.library.Library;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server config, {@code config/sculptory/server.json}. Written with defaults on
 * first start; an existing file is never rewritten, so it keeps the values (and the defaults) of the build that wrote
 * it. Missing keys keep their defaults (listed in one INFO line at start) and out-of-range values are clamped (with a
 * warning) by {@link #sanitize()}.
 *
 * <p>Fails closed: a file that cannot be read or parsed leaves editing <em>disabled</em> (with an error in the log)
 * rather than silently running with defaults, and an unrecognised {@code unloadedChunks} value (matched without
 * regard to case) becomes {@link UnloadedPolicy#REFUSE}.
 *
 * <p>Plain mutable fields so Gson can bind them; treat a loaded instance as read-only.
 */
public final class SculptoryConfig {
    private static final Logger LOG = LoggerFactory.getLogger("sculptory");
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .registerTypeAdapter(UnloadedPolicy.class, new LenientEnumAdapter<>(UnloadedPolicy.class))
            .create();

    /** Master switch: when false every edit is rejected with {@code DISABLED}. */
    public boolean editingEnabled = true;
    /** Op level required for a node when no permissions mod decides it. */
    public int permissionFallbackOpLevel = 2;
    /** The singleplayer (integrated server) host may always edit. */
    public boolean singleplayerHostAlwaysAllowed = true;
    public Executor executor = new Executor();
    public History history = new History();
    public LimitsConfig limits = new LimitsConfig();
    public LibraryConfig library = new LibraryConfig();
    public ScatterConfig scatter = new ScatterConfig();
    public TransformConfig transform = new TransformConfig();
    public EntitiesConfig entities = new EntitiesConfig();
    public BuilderConfig builder = new BuilderConfig();
    public NavigateConfig navigate = new NavigateConfig();
    public UnloadedPolicy unloadedChunks = UnloadedPolicy.LOAD;
    /** What the last {@link #sanitize()} changed (not part of the file). */
    private transient List<String> adjustments;

    /** Edit executor budgets. */
    public static final class Executor {
        public int tickBudgetMsDedicated = 10;
        public int tickBudgetMsIntegrated = 20;
        /** Bulk-lane cells written per tick; 0 means no cap beyond the time budget. */
        public long maxBlocksPerTick = 0;
        /** Share of the tick budget the brush lane may use before bulk jobs run. */
        public double brushLaneShare = 0.4;
        public int maxActiveJobsGlobal = 8;
        public int maxQueuedJobs = 32;
        /**
         * Jobs one player may have waiting to start (a share of {@link #maxQueuedJobs}), so one player cannot fill the
         * queue for everyone ({@code QUEUE_FULL} beyond it).
         */
        public int maxQueuedJobsPerPlayer = 8;
        public int maxChunkTicketsPerJob = 64;
        /** Queued brush work items (dabs) before new ones are refused. */
        public int maxQueuedBrushWork = 1024;
        /** Distinct chunk columns one region op may read or write ({@code TOO_LARGE} beyond). */
        public int maxColumnsPerJob = 16_384;
    }

    /** History caps and saving it to disk. */
    public static final class History {
        public int maxEntriesPerPlayer = HistoryLimits.DEFAULTS.maxEntries();
        public long maxBytesPerPlayer = HistoryLimits.DEFAULTS.maxBytesPerPlayer();
        public long maxBytesTotal = HistoryLimits.DEFAULTS.maxBytesTotal();
        /** Save every player's history in the world folder, so it survives restarts and crashes. */
        public boolean persist = true;
        /** What the saved history files may take on disk together (live data is kept under half of it). */
        public long maxDiskBytes = 2L << 30;
        /**
         * Saved steps older than this many days are dropped (checked when a history is loaded, and for players who
         * are offline); 0 keeps them.
         */
        public int maxAgeDays = 30;
    }

    /** Default upload cap: 16 MiB of compressed {@code .schem} (the protocol's own default is higher). */
    public static final long DEFAULT_UPLOAD_BYTES = 16L << 20;
    /** Hard cap on {@code limits.maxUploadBytes}. */
    public static final long MAX_UPLOAD_BYTES = 64L << 20;

    /** Client-visible limits sent in {@code Welcome}. */
    public static final class LimitsConfig {
        public long maxOpVolume = Limits.DEFAULTS.maxOpVolume();
        public long maxClipboardVolume = Limits.DEFAULTS.maxClipboardVolume();
        public int maxBrushRadius = Limits.DEFAULTS.maxBrushRadius();
        public int maxDabRate = Limits.DEFAULTS.maxDabRate();
        /** Largest {@code .schem} upload (compressed); at most {@link #MAX_UPLOAD_BYTES}. */
        public long maxUploadBytes = DEFAULT_UPLOAD_BYTES;
        /** Also the executor's active-job cap per player. */
        public int maxJobsPerPlayer = Limits.DEFAULTS.maxJobsPerPlayer();
        /**
         * Most blocks in one selection a player sends (magic select). Sent to clients, which cap magic select by it and
         * refuse a larger selection before sending it.
         */
        public long maxSelectionCells = Limits.DEFAULT_SELECTION_CELLS;
        /**
         * Most 16³ sections one selection a player sends may touch: a spread-out selection needs many for few blocks,
         * and each takes up to about 0.6 KiB while the server holds it. Sent to clients like the cells.
         */
        public int maxSelectionSections = Limits.DEFAULT_SELECTION_SECTIONS;
        /**
         * What the selections one player sent may take on the server together (the last 4 are kept, the least recently
         * used dropped first); a single selection over it is refused.
         */
        public long maxSelectionStoreBytes = DEFAULT_SELECTION_STORE_BYTES;
        /**
         * What every player's selections may take together; past it the least recently used anywhere are dropped (their
         * owners' clients send them again when needed). At least {@link #maxSelectionStoreBytes}.
         */
        public long maxSelectionStoreBytesTotal = DEFAULT_SELECTION_STORE_BYTES_TOTAL;
    }

    /** Default selection memory: 64 MiB held per player, 512 MiB for everyone. */
    public static final long DEFAULT_SELECTION_STORE_BYTES = 64L << 20;
    public static final long DEFAULT_SELECTION_STORE_BYTES_TOTAL = 512L << 20;

    /** The asset library under {@code <gameDir>/sculptory/library/} (M2). */
    public static final class LibraryConfig {
        /** Largest {@code .schem} the library reads or writes. */
        public long maxFileBytes = Library.Settings.DEFAULTS.maxFileBytes();
        /** All library files together. */
        public long maxTotalBytes = Library.Settings.DEFAULTS.maxTotalBytes();
        /** One player's {@code _players/<uuid>/} folder (players without {@code library.write}). */
        public long maxPlayerBytes = Library.Settings.DEFAULTS.maxPlayerBytes();
        /** Days a deleted asset stays in {@code .trash/} before the server start that purges it; 0 keeps it. */
        public int trashDays = Library.Settings.DEFAULT_TRASH_DAYS;
        /** Bytes the trash may hold of shared-area deletions; the oldest go first. At least {@code maxFileBytes}. */
        public long maxTrashBytes = Library.Settings.DEFAULT_TRASH_BYTES;
        /**
         * Bytes the trash may hold of each player folder's deletions, a share of its own (the oldest of that player's
         * go first), so deleting in one's own folder never pushes out anyone else's. At least the largest file a
         * player folder can hold.
         */
        public long maxPlayerTrashBytes = Library.Settings.DEFAULT_PLAYER_TRASH_BYTES;
    }

    /** Scatter previews (M3): planning cost caps. They apply to every player, {@code limit.bypass} included. */
    public static final class ScatterConfig {
        /** Largest variant source, by its box volume. */
        public long maxSourceVolume = 262_144;
        /**
         * Planner work budget per preview, in cell checks; a plan that reaches it stops accepting and reports the
         * rest of its candidates as {@code WORK_LIMIT}. Also the up-front sanity cap: a preview whose expected
         * candidates times its largest variant's cells exceed it is refused ({@code TOO_LARGE}).
         */
        public long maxWork = 50_000_000;
        /** Share of the executor's tick budget that planning may use each tick. */
        public double tickShare = 0.25;
        /**
         * Planning time one preview may be given (the tick time actually spent on it, not the time it waits for its
         * share) before it is refused {@code TOO_LARGE}. At the defaults a dedicated server gives a lone preview
         * about 2.5 ms per tick, so 3000 ms is about a minute.
         */
        public long maxPlanningMillis = 3000;
        /**
         * How long one player's previews may block other players in a burst, in seconds: a preview's hold uses it only
         * while it keeps someone else's job queued or refuses their dab or cut ({@code AREA_BUSY}), never for a player
         * alone. It comes back at {@link #holdRefillShare}. A preview admitted with nothing left is refused
         * {@code RATE_LIMITED}.
         */
        public double holdBudgetSeconds = 30;
        /** Seconds regained per second, up to {@link #holdBudgetSeconds}: the sustained share of time spent blocking. */
        public double holdRefillShare = 0.5;
        /**
         * Most cells the trees and features of one preview may grow, all placements together; a plan that would grow more is refused {@code TOO_LARGE}.
         */
        public long maxFeatureCells = 1_048_576;
    }

    /** How pastes, moves and scatter placements turn block states. */
    public static final class TransformConfig {
        /**
         * Turn modded blocks that do not turn themselves (Farmer's Delight pots, pies and feasts; Chipped's special
         * lanterns) by their {@code facing}, {@code axis} or {@code rotation}, as WorldEdit does. Vanilla blocks are
         * unaffected, and modded blocks keep every turn they make themselves. Clients follow the server's value
         * (handshake feature {@code modded_facing_fallback}), so previews match.
         */
        public boolean moddedFacingFallback = true;
    }

    /**
     * Entities that copies, cuts, pastes, moves and stacks take along. Beyond a cap
     * the request is refused {@code TOO_LARGE}; {@code limit.bypass} lifts both, as it lifts the volume limits.
     */
    public static final class EntitiesConfig {
        /** Most entities one clipboard holds: a copy, a cut, an upload or a library load. */
        public int maxPerClipboard = 4096;
        /** Most entities one paste, move or stack places (a stack counts every copy). */
        public int maxPerJob = 16_384;
    }

    /**
     * Builder mode: Sculptory's powers in normal creative play, outside the editor. Every placement and break goes through the engine (permissions, protection, history), within these caps.
     */
    public static final class BuilderConfig {
        /** How far from the player's eyes builder mode places and breaks (Long reach), in blocks; 5-64. */
        public int maxReach = 64;
        /**
         * Blocks one player may place or break per second in builder mode, mirrored copies included (a bulldozer drag
         * sweeps at most this fast); bursts of twice as many are allowed.
         */
        public int maxBlocksPerSecond = 100;
    }

    /** Jump and Through. */
    public static final class NavigateConfig {
        /** How far from the player's eyes the block they jump to may be, in blocks; 8-1024. */
        public int maxDistance = 256;
        /** How deep a wall Through goes through, in blocks, looking for a free spot behind it; 1-256. */
        public int maxThroughDepth = 64;
    }

    public static SculptoryConfig defaults() {
        return new SculptoryConfig();
    }

    /**
     * Loads {@code file}, writing the defaults there first if it does not exist. A file that exists but cannot be
     * read or parsed (including an empty one) is left untouched; the defaults are used with editing disabled.
     */
    public static SculptoryConfig load(Path file) {
        if (!Files.exists(file)) {
            SculptoryConfig config = defaults();
            try {
                config.write(file);
                LOG.info("Wrote default Sculptory config to {}", file);
            } catch (IOException e) {
                LOG.warn("Could not write default Sculptory config to {}", file, e);
            }
            return config;
        }
        Read read = read(file);
        return read.config() != null ? read.config() : disabled(file, read.problem());
    }

    /**
     * The outcome of reading the file: the sanitized config, or why there is none ({@code problem}: the file is
     * missing, unreadable, empty or not valid JSON).
     */
    public record Read(SculptoryConfig config, String problem) {}

    /**
     * Reads {@code file} without writing anything: the config, sanitized (its {@link #adjustments()} name what was
     * clamped), or the problem. Keys the file lacks are logged as at start.
     */
    public static Read read(Path file) {
        if (!Files.exists(file)) return new Read(null, "the file doesn't exist");
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String text = readAll(reader);
            SculptoryConfig config = GSON.fromJson(text, SculptoryConfig.class);
            if (config == null) return new Read(null, "the file is empty");
            logMissingKeys(file, text);
            config.sanitize();
            return new Read(config, null);
        } catch (IOException | RuntimeException e) {
            return new Read(null, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    /**
     * Sections a reload does not apply: they are wired into the history store, the library and the state space when
     * the server starts ({@code /sculptory reload} reports them as needing a restart).
     */
    public static final List<String> RESTART_SECTIONS = List.of("history", "library", "transform");

    /** Whether the dotted {@code key} takes effect only at the next start ({@link #RESTART_SECTIONS}). */
    public static boolean needsRestart(String key) {
        String section = key.contains(".") ? key.substring(0, key.indexOf('.')) : key;
        return RESTART_SECTIONS.contains(section);
    }

    /** A setting whose value differs between two configs: its dotted key and both values as JSON text. */
    public record Change(String key, String before, String after) {
        public boolean needsRestart() {
            return SculptoryConfig.needsRestart(key);
        }

        @Override
        public String toString() {
            return key + " " + before + " -> " + after;
        }
    }

    /** The settings whose values differ from {@code before} to {@code after}, in the default file's order. */
    public static List<Change> changes(SculptoryConfig before, SculptoryConfig after) {
        Map<String, JsonElement> a = flatten(GSON.toJsonTree(before));
        Map<String, JsonElement> b = flatten(GSON.toJsonTree(after));
        List<Change> changes = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : a.entrySet()) {
            JsonElement other = b.get(entry.getKey());
            if (!entry.getValue().equals(other)) {
                changes.add(new Change(entry.getKey(), entry.getValue().toString(), String.valueOf(other)));
            }
        }
        return changes;
    }

    private static Map<String, JsonElement> flatten(JsonElement tree) {
        Map<String, JsonElement> out = new LinkedHashMap<>();
        flatten(tree, "", out);
        return out;
    }

    private static void flatten(JsonElement tree, String prefix, Map<String, JsonElement> out) {
        if (tree.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : tree.getAsJsonObject().entrySet()) {
                flatten(entry.getValue(), prefix + entry.getKey() + ".", out);
            }
        } else {
            out.put(prefix.substring(0, prefix.length() - 1), tree);
        }
    }

    /**
     * Takes the {@link #RESTART_SECTIONS} of {@code running} into this freshly read config, which is then what a reload
     * applies. The sections are shared, not copied (loaded configs are read-only). Returns this config.
     */
    public SculptoryConfig keepRestartSectionsOf(SculptoryConfig running) {
        history = running.history;
        library = running.library;
        transform = running.transform;
        return this;
    }

    /** The dotted key an {@link #adjustments()} entry is about: the text before its first space. */
    public static String adjustmentKey(String adjustment) {
        int space = adjustment.indexOf(' ');
        return space < 0 ? adjustment : adjustment.substring(0, space);
    }

    /** What {@link #sanitize()} changed, e.g. {@code limits.maxBrushRadius = 64 out of range [1, 32]; using 32}. */
    public List<String> adjustments() {
        return adjustments == null ? List.of() : List.copyOf(adjustments);
    }

    /** One INFO line naming the keys the file doesn't set (they use the defaults); never fails the load. */
    private static void logMissingKeys(Path file, String text) {
        try {
            List<String> missing = missingKeys(text);
            if (!missing.isEmpty()) {
                LOG.info("Sculptory config {}: not set there, so using the defaults: {}", file,
                        String.join(", ", missing));
            }
        } catch (RuntimeException e) {
            LOG.debug("Sculptory config: could not list the keys missing from {}", file, e);
        }
    }

    private static String readAll(Reader reader) throws IOException {
        StringBuilder text = new StringBuilder();
        char[] buffer = new char[8192];
        for (int n; (n = reader.read(buffer)) != -1; ) text.append(buffer, 0, n);
        return text.toString();
    }

    /**
     * The keys of the default file that {@code json} lacks (or sets to {@code null}), as dotted paths in the default
     * file's order; a missing section is named once ({@code library}), not key by key. Empty for JSON that isn't an
     * object (the caller has already parsed it).
     */
    static List<String> missingKeys(String json) {
        List<String> missing = new ArrayList<>();
        JsonElement tree = JsonParser.parseString(json);
        if (tree.isJsonObject()) missingKeys(GSON.toJsonTree(defaults()).getAsJsonObject(), tree.getAsJsonObject(), "",
                missing);
        return missing;
    }

    private static void missingKeys(JsonObject defaults, JsonObject file, String prefix, List<String> missing) {
        for (Map.Entry<String, JsonElement> entry : defaults.entrySet()) {
            JsonElement value = file.get(entry.getKey());
            if (value == null || value.isJsonNull()) {
                missing.add(prefix + entry.getKey());
            } else if (entry.getValue().isJsonObject() && value.isJsonObject()) {
                missingKeys(entry.getValue().getAsJsonObject(), value.getAsJsonObject(), prefix + entry.getKey() + ".",
                        missing);
            }
        }
    }

    private static SculptoryConfig disabled(Path file, String problem) {
        LOG.error("Sculptory config {} could not be read ({}). Sculptory editing is DISABLED until this is "
                + "fixed: correct the JSON (or delete the file to have the defaults written again), then run /sculptory reload "
                + "or restart the server. The file was left unchanged.", file.toAbsolutePath(), problem);
        SculptoryConfig config = defaults();
        config.editingEnabled = false;
        return config;
    }

    /** Writes this config as pretty JSON, atomically where the file system allows. */
    public void write(Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            GSON.toJson(this, writer);
            writer.write(System.lineSeparator());
        }
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    /**
     * Replaces missing sections with defaults and clamps every value into its valid range; each clamp is logged as a
     * warning and listed in {@link #adjustments()}.
     */
    public SculptoryConfig sanitize() {
        adjustments = new ArrayList<>();
        if (executor == null) executor = new Executor();
        if (history == null) history = new History();
        if (limits == null) limits = new LimitsConfig();
        if (library == null) library = new LibraryConfig();
        if (scatter == null) scatter = new ScatterConfig();
        if (transform == null) transform = new TransformConfig();
        if (entities == null) entities = new EntitiesConfig();
        if (builder == null) builder = new BuilderConfig();
        if (navigate == null) navigate = new NavigateConfig();
        if (unloadedChunks == null) {
            adjust("unloadedChunks is not one of " + Arrays.toString(UnloadedPolicy.values())
                    + "; using the safe option REFUSE");
            unloadedChunks = UnloadedPolicy.REFUSE;
        }
        permissionFallbackOpLevel = clamp("permissionFallbackOpLevel", permissionFallbackOpLevel, 0, 4);

        executor.tickBudgetMsDedicated = clamp("executor.tickBudgetMsDedicated", executor.tickBudgetMsDedicated, 1, 1000);
        executor.tickBudgetMsIntegrated = clamp("executor.tickBudgetMsIntegrated", executor.tickBudgetMsIntegrated, 1, 1000);
        executor.maxBlocksPerTick = clamp("executor.maxBlocksPerTick", executor.maxBlocksPerTick, 0, Long.MAX_VALUE);
        if (!(executor.brushLaneShare >= 0.05 && executor.brushLaneShare <= 1.0)) {
            adjust("executor.brushLaneShare = " + executor.brushLaneShare + " out of range [0.05, 1.0]; clamped");
            executor.brushLaneShare = Double.isNaN(executor.brushLaneShare)
                    ? 0.4 : Math.max(0.05, Math.min(1.0, executor.brushLaneShare));
        }
        executor.maxActiveJobsGlobal = clamp("executor.maxActiveJobsGlobal", executor.maxActiveJobsGlobal, 1, 256);
        executor.maxQueuedJobs = clamp("executor.maxQueuedJobs", executor.maxQueuedJobs, 1, 4096);
        executor.maxQueuedJobsPerPlayer = clamp("executor.maxQueuedJobsPerPlayer", executor.maxQueuedJobsPerPlayer, 1,
                4096);
        executor.maxChunkTicketsPerJob = clamp("executor.maxChunkTicketsPerJob", executor.maxChunkTicketsPerJob, 1, 1024);
        executor.maxQueuedBrushWork = clamp("executor.maxQueuedBrushWork", executor.maxQueuedBrushWork, 1, 65_536);
        executor.maxColumnsPerJob = clamp("executor.maxColumnsPerJob", executor.maxColumnsPerJob, 1, 1 << 20);

        history.maxEntriesPerPlayer = clamp("history.maxEntriesPerPlayer", history.maxEntriesPerPlayer, 1, 10_000);
        history.maxBytesPerPlayer = clamp("history.maxBytesPerPlayer", history.maxBytesPerPlayer, 1L << 20, Long.MAX_VALUE);
        history.maxBytesTotal = clamp("history.maxBytesTotal", history.maxBytesTotal, 1L << 20, Long.MAX_VALUE);
        history.maxDiskBytes = clamp("history.maxDiskBytes", history.maxDiskBytes, 16L << 20, Long.MAX_VALUE);
        history.maxAgeDays = clamp("history.maxAgeDays", history.maxAgeDays, 0, 36_500);

        limits.maxOpVolume = clamp("limits.maxOpVolume", limits.maxOpVolume, 1, Long.MAX_VALUE);
        limits.maxClipboardVolume = clamp("limits.maxClipboardVolume", limits.maxClipboardVolume, 1, Long.MAX_VALUE);
        limits.maxBrushRadius = clamp("limits.maxBrushRadius", limits.maxBrushRadius, 1, 32);
        limits.maxDabRate = clamp("limits.maxDabRate", limits.maxDabRate, 1, 1000);
        limits.maxUploadBytes = clamp("limits.maxUploadBytes", limits.maxUploadBytes, 1, MAX_UPLOAD_BYTES);
        limits.maxJobsPerPlayer = clamp("limits.maxJobsPerPlayer", limits.maxJobsPerPlayer, 1, 64);
        limits.maxSelectionCells = clamp("limits.maxSelectionCells", limits.maxSelectionCells, 1, 1L << 26);
        limits.maxSelectionSections = clamp("limits.maxSelectionSections", limits.maxSelectionSections, 1, 1 << 20);
        limits.maxSelectionStoreBytes = clamp("limits.maxSelectionStoreBytes", limits.maxSelectionStoreBytes, 1L << 20,
                4L << 30);
        limits.maxSelectionStoreBytesTotal = clamp("limits.maxSelectionStoreBytesTotal", limits.maxSelectionStoreBytesTotal,
                limits.maxSelectionStoreBytes, 64L << 30);

        library.maxFileBytes = clamp("library.maxFileBytes", library.maxFileBytes, 1024, 1L << 30);
        library.maxTotalBytes = clamp("library.maxTotalBytes", library.maxTotalBytes, 1024, Long.MAX_VALUE);
        library.maxPlayerBytes = clamp("library.maxPlayerBytes", library.maxPlayerBytes, 1024, Long.MAX_VALUE);
        library.trashDays = clamp("library.trashDays", library.trashDays, 0, Library.Settings.MAX_TRASH_DAYS);
        library.maxTrashBytes = clamp("library.maxTrashBytes", library.maxTrashBytes, library.maxFileBytes,
                Long.MAX_VALUE);
        library.maxPlayerTrashBytes = clamp("library.maxPlayerTrashBytes", library.maxPlayerTrashBytes,
                Math.min(library.maxFileBytes, library.maxPlayerBytes), Long.MAX_VALUE);

        scatter.maxSourceVolume = clamp("scatter.maxSourceVolume", scatter.maxSourceVolume, 1, 1L << 21);
        scatter.maxWork = clamp("scatter.maxWork", scatter.maxWork, 1000, 1L << 40);
        if (!(scatter.tickShare >= 0.05 && scatter.tickShare <= 1.0)) {
            adjust("scatter.tickShare = " + scatter.tickShare + " out of range [0.05, 1.0]; clamped");
            scatter.tickShare = Double.isNaN(scatter.tickShare) ? 0.25 : Math.max(0.05, Math.min(1.0, scatter.tickShare));
        }
        scatter.maxPlanningMillis = clamp("scatter.maxPlanningMillis", scatter.maxPlanningMillis, 100, 600_000);
        scatter.maxFeatureCells = clamp("scatter.maxFeatureCells", scatter.maxFeatureCells, 1, 1L << 24);
        if (!(scatter.holdBudgetSeconds >= 1 && scatter.holdBudgetSeconds <= 3600)) {
            adjust("scatter.holdBudgetSeconds = " + scatter.holdBudgetSeconds + " out of range [1, 3600]; clamped");
            scatter.holdBudgetSeconds = Double.isNaN(scatter.holdBudgetSeconds) ? 30
                    : Math.max(1, Math.min(3600, scatter.holdBudgetSeconds));
        }
        if (!(scatter.holdRefillShare >= 0.01 && scatter.holdRefillShare <= 1.0)) {
            adjust("scatter.holdRefillShare = " + scatter.holdRefillShare + " out of range [0.01, 1.0]; clamped");
            scatter.holdRefillShare = Double.isNaN(scatter.holdRefillShare) ? 0.5
                    : Math.max(0.01, Math.min(1.0, scatter.holdRefillShare));
        }

        entities.maxPerClipboard = clamp("entities.maxPerClipboard", entities.maxPerClipboard, 0, 1 << 16);
        entities.maxPerJob = clamp("entities.maxPerJob", entities.maxPerJob, 0, 1 << 20);
        builder.maxReach = clamp("builder.maxReach", builder.maxReach, 5, 64);
        builder.maxBlocksPerSecond = clamp("builder.maxBlocksPerSecond", builder.maxBlocksPerSecond, 1, 10_000);
        navigate.maxDistance = clamp("navigate.maxDistance", navigate.maxDistance, 8, 1024);
        navigate.maxThroughDepth = clamp("navigate.maxThroughDepth", navigate.maxThroughDepth, 1, 256);
        return this;
    }

    public Library.Settings toLibrarySettings() {
        return new Library.Settings(library.maxFileBytes, library.maxTotalBytes, library.maxPlayerBytes,
                Library.Settings.DEFAULTS.maxListing())
                .withTrash(library.trashDays, library.maxTrashBytes, library.maxPlayerTrashBytes);
    }

    /** The per-tick executor budget in nanoseconds. */
    public long tickBudgetNanos(boolean dedicatedServer) {
        int ms = dedicatedServer ? executor.tickBudgetMsDedicated : executor.tickBudgetMsIntegrated;
        return ms * 1_000_000L;
    }

    public Limits toLimits() {
        return new Limits(limits.maxOpVolume, limits.maxClipboardVolume, limits.maxBrushRadius, limits.maxDabRate,
                limits.maxUploadBytes, limits.maxJobsPerPlayer, limits.maxSelectionCells, limits.maxSelectionSections);
    }

    public HistoryLimits toHistoryLimits() {
        return new HistoryLimits(history.maxEntriesPerPlayer, history.maxBytesPerPlayer, history.maxBytesTotal);
    }

    /** {@code history.maxAgeDays} in milliseconds (0: no limit). */
    public long historyMaxAgeMillis() {
        return history.maxAgeDays * 86_400_000L;
    }

    private int clamp(String name, int value, int min, int max) {
        if (value >= min && value <= max) return value;
        int clamped = Math.max(min, Math.min(max, value));
        adjust(name + " = " + value + " out of range [" + min + ", " + max + "]; using " + clamped);
        return clamped;
    }

    private long clamp(String name, long value, long min, long max) {
        if (value >= min && value <= max) return value;
        long clamped = Math.max(min, Math.min(max, value));
        adjust(name + " = " + value + " out of range [" + min + ", " + max + "]; using " + clamped);
        return clamped;
    }

    /** A value {@link #sanitize()} changed: logged as a warning and kept for {@link #adjustments()}. */
    private void adjust(String message) {
        LOG.warn("Sculptory config: {}", message);
        if (adjustments == null) adjustments = new ArrayList<>();
        adjustments.add(message);
    }
}
