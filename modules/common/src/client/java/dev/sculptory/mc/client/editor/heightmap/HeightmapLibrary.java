package dev.sculptory.mc.client.editor.heightmap;

import dev.sculptory.core.brush.HeightImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The height images the Heightmap brush and the Generate tool's Heightmap import pick from: the built-in pictures
 * (shipped as {@code assets/sculptory/heightmaps/<name>.png}), then every PNG in the builder's own folder
 * ({@code <game dir>/sculptory/heightmaps/}, not its subfolders), by file name.
 *
 * <p>{@link #refresh} lists the folder again (cheap) and hands only the files that are new or changed (size or time) to
 * the decoder executor: the game decodes them off the client thread, and they join the list once read, shrunk to at
 * most {@value PngImages#MAX_LOADED} pixels a side ({@link PngImages}). A file that can't be used (not a PNG, damaged,
 * over {@value PngImages#MAX_SIDE} pixels a side or {@value #MAX_FILE_BYTES} bytes, a name the presets can't hold) is
 * skipped and returned by a {@link #refresh} once per version, so the caller can say so in one line. The entries keep
 * their order: built-ins as listed, then files by name, ignoring case. Each entry's {@code version} changes when its
 * picture does (thumbnails key on it). Client thread only, but for the decoding.
 */
public final class HeightmapLibrary {
    private static final Logger LOG = LoggerFactory.getLogger("sculptory");
    /** The built-in pictures, in the picker's order. */
    public static final List<String> BUILT_IN = List.of("hill", "mountain", "crater", "mesa", "ridge", "dunes", "volcano",
            "terraces");
    /** Where the built-ins are, on the class path. */
    public static final String BUILT_IN_PATH = "/assets/sculptory/heightmaps/";
    /** The largest file read from the folder. */
    public static final long MAX_FILE_BYTES = 16L << 20;
    /** The image the brush and the import start with. */
    public static final HeightmapRef DEFAULT = HeightmapRef.builtIn("hill");

    /** One picture: what names it, the image, and a number that changes whenever the image does. */
    public record Entry(HeightmapRef ref, HeightImage image, long version) {
        public Entry {
            Objects.requireNonNull(ref);
            Objects.requireNonNull(image);
        }
    }

    /** A file skipped, and why. */
    public record BadFile(String name, PngImages.Reason reason) {}

    /** Opens a built-in picture's PNG, or returns {@code null} when it is missing. */
    @FunctionalInterface
    public interface BuiltIns {
        InputStream open(String name) throws IOException;

        /** The mod's own pictures, from the class path. */
        BuiltIns CLASS_PATH = name -> HeightmapLibrary.class.getResourceAsStream(BUILT_IN_PATH + name + ".png");
    }

    private static HeightmapLibrary builtInOnly;

    private final Path folder;
    private final BuiltIns builtIns;
    private final Executor decoder;
    private final List<Entry> builtInEntries = new ArrayList<>();
    /** The folder's pictures by file name. */
    private final TreeMap<String, Entry> files = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    /** What each listed file was last seen as (decoded, or handed to the decoder). */
    private final Map<String, Stamp> seen = new HashMap<>();
    /** Decodes finished on the decoder's thread, waiting for the client thread. */
    private final Queue<Decoded> decoded = new ConcurrentLinkedQueue<>();
    /** Bad file versions already reported, and those found since the last {@link #refresh}. */
    private final Set<Stamp> reported = new HashSet<>();
    private final List<BadFile> unreported = new ArrayList<>();
    private long versions;
    private boolean builtInsRead;

    private record Stamp(String name, long size, long modified) {}

    /** A decode's outcome: the image, or why the file can't be used. */
    private record Decoded(Stamp stamp, HeightImage image, PngImages.Reason reason) {}

    /**
     * A library decoding on the calling thread.
     *
     * @param folder the builder's heightmaps folder, or {@code null} for the built-ins alone
     */
    public HeightmapLibrary(Path folder, BuiltIns builtIns) {
        this(folder, builtIns, Runnable::run);
    }

    /** @param decoder where the folder's files are read and decoded (off the client thread in the game) */
    public HeightmapLibrary(Path folder, BuiltIns builtIns, Executor decoder) {
        this.folder = folder;
        this.builtIns = Objects.requireNonNull(builtIns);
        this.decoder = Objects.requireNonNull(decoder);
    }

    /** The built-in pictures alone, read from the class path (tests, tool sets built without the game). */
    public static synchronized HeightmapLibrary builtInOnly() {
        if (builtInOnly == null) builtInOnly = new HeightmapLibrary(null, BuiltIns.CLASS_PATH);
        return builtInOnly;
    }

    /** The builder's folder, or empty for a library of built-ins only. */
    public Optional<Path> folder() {
        return Optional.ofNullable(folder);
    }

    /** Every picture: the built-ins, then the folder's files read so far. */
    public List<Entry> entries() {
        readBuiltIns();
        drain();
        List<Entry> all = new ArrayList<>(builtInEntries);
        all.addAll(files.values());
        return all;
    }

    /** The picture {@code ref} names, if there is one now. */
    public Optional<Entry> entry(HeightmapRef ref) {
        Objects.requireNonNull(ref);
        if (ref.builtIn()) {
            readBuiltIns();
            return builtInEntries.stream().filter(e -> e.ref().equals(ref)).findFirst();
        }
        drain();
        return Optional.ofNullable(files.get(ref.name())).filter(e -> e.ref().name().equals(ref.name()));
    }

    /** The image {@code ref} names, if there is one now. */
    public Optional<HeightImage> image(HeightmapRef ref) {
        return entry(ref).map(Entry::image);
    }

    /**
     * Lists the folder again (creating it when it is missing, so the builder can find it): new and changed PNGs go to the
     * decoder, removed ones are dropped. Returns the files found unusable since the last call, each version once.
     */
    public List<BadFile> refresh() {
        readBuiltIns();
        if (folder != null) relist();
        drain();
        List<BadFile> bad = new ArrayList<>(unreported);
        unreported.clear();
        bad.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return bad;
    }

    private void relist() {
        Map<String, Stamp> now = new HashMap<>();
        try {
            Files.createDirectories(folder);
            try (DirectoryStream<Path> listing = Files.newDirectoryStream(folder)) {
                for (Path path : listing) {
                    String name = path.getFileName().toString();
                    if (!name.toLowerCase(Locale.ROOT).endsWith(".png")) continue;
                    BasicFileAttributes attributes;
                    try {
                        attributes = Files.readAttributes(path, BasicFileAttributes.class);
                    } catch (IOException gone) {
                        continue;
                    }
                    if (!attributes.isRegularFile()) continue;
                    now.put(name, new Stamp(name, attributes.size(), attributes.lastModifiedTime().toMillis()));
                }
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("Sculptory: could not list the heightmaps folder {}: {}", folder, e.toString());
            return;
        }
        files.keySet().removeIf(name -> !now.containsKey(name));
        seen.keySet().removeIf(name -> !now.containsKey(name));
        for (Stamp stamp : now.values()) {
            if (stamp.equals(seen.get(stamp.name()))) continue;
            seen.put(stamp.name(), stamp);
            files.remove(stamp.name());
            PngImages.Reason early = !HeightmapRef.valid(stamp.name()) ? PngImages.Reason.BAD_NAME
                    : stamp.size() > MAX_FILE_BYTES ? PngImages.Reason.FILE_TOO_LARGE : null;
            if (early != null) {
                decoded.add(new Decoded(stamp, null, early));
                continue;
            }
            Path path = folder.resolve(stamp.name());
            try {
                decoder.execute(() -> decoded.add(decode(stamp, path)));
            } catch (RejectedExecutionException stopping) {
                seen.remove(stamp.name());
            }
        }
    }

    /** Reads and decodes one file (on the decoder's thread). */
    private static Decoded decode(Stamp stamp, Path path) {
        try {
            return new Decoded(stamp, PngImages.read(Files.readAllBytes(path)), null);
        } catch (PngImages.BadImageException e) {
            return new Decoded(stamp, null, e.reason());
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return new Decoded(stamp, null, PngImages.Reason.UNREADABLE);
        }
    }

    /** Takes in the decodes that finished, dropping those of a file changed or removed since. */
    private void drain() {
        Decoded done;
        while ((done = decoded.poll()) != null) {
            Stamp stamp = done.stamp();
            if (!stamp.equals(seen.get(stamp.name()))) continue;
            if (done.image() != null) {
                files.put(stamp.name(), new Entry(HeightmapRef.file(stamp.name()), done.image(), ++versions));
            } else if (reported.add(stamp)) {
                unreported.add(new BadFile(stamp.name(), done.reason()));
            }
        }
    }

    private void readBuiltIns() {
        if (builtInsRead) return;
        builtInsRead = true;
        for (String name : BUILT_IN) {
            try (InputStream in = builtIns.open(name)) {
                if (in == null) {
                    LOG.warn("Sculptory: the built-in heightmap {} is missing", name);
                    continue;
                }
                builtInEntries.add(new Entry(HeightmapRef.builtIn(name), PngImages.read(in.readAllBytes()), ++versions));
            } catch (IOException | PngImages.BadImageException | RuntimeException e) {
                LOG.warn("Sculptory: the built-in heightmap {} could not be read: {}", name, e.toString());
            }
        }
    }
}
