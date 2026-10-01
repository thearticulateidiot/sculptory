package dev.sculptory.fabric.client.util;

import dev.sculptory.fabric.client.util.FileStoreException.Kind;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Crash-safe storage for small client files: editor layout, keymap and presets. Callers encode and
 * decode; this class only moves bytes, and never leaves a half-written file.
 *
 * <p><b>Load</b> reads the whole file (never following a symbolic link at the target, refusing
 * directories and files over the size limit) and remembers what it read.
 *
 * <p><b>Save</b> publishes new bytes only if the file still holds what this store last loaded or
 * saved, so edits made outside the game are never silently overwritten:
 * <ol>
 *   <li>the bytes are checked against the size limit before any I/O;</li>
 *   <li>a cooperative lock is taken on the companion {@code <name>.lock} file;</li>
 *   <li>the target is re-read and compared with the remembered contents;</li>
 *   <li>the bytes go to a private temporary file in the same directory, which is forced to disk;</li>
 *   <li>the temporary file is atomically moved over the target.</li>
 * </ol>
 * If anything fails once the move has been attempted, the target is read back: exactly the new
 * bytes counts as success, exactly the old bytes as a retryable failure, anything else as
 * {@link Kind#UNCERTAIN}, after which saves to that path are refused until it is loaded again.
 *
 * <p>Instances are thread-safe. Companion {@code .lock} files are left in place.
 */
public final class AtomicFileStore {
    public static final int DEFAULT_MAX_BYTES = 1 << 20;

    /** Fault-injection seam for tests; production code uses {@link #SYSTEM}. */
    interface Hooks {
        Hooks SYSTEM = new Hooks() {
        };

        default int write(FileChannel channel, ByteBuffer source) throws IOException {
            return channel.write(source);
        }

        default void beforePublish(Path temporary, Path target) throws IOException {
        }

        default void move(Path temporary, Path target) throws IOException {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }

        default void afterPublishResourcesClosed(Path target) throws IOException {
        }
    }

    /** What is (or was) at the target: missing, or exact bytes. */
    private record Target(boolean missing, byte[] bytes) {
        static final Target MISSING = new Target(true, null);

        static Target present(byte[] bytes) {
            return new Target(false, bytes);
        }

        boolean sameAs(Target other) {
            return other != null && missing == other.missing && (missing || Arrays.equals(bytes, other.bytes));
        }

        boolean holds(byte[] candidate) {
            return !missing && Arrays.equals(bytes, candidate);
        }
    }

    private record Temporary(Path path, FileChannel channel) {
    }

    private final Map<Path, Target> baselines = new HashMap<>();
    private final Set<Path> uncertain = new HashSet<>();
    private final int maxBytes;
    private final Hooks hooks;

    public AtomicFileStore() {
        this(DEFAULT_MAX_BYTES);
    }

    public AtomicFileStore(int maxBytes) {
        this(maxBytes, Hooks.SYSTEM);
    }

    AtomicFileStore(int maxBytes, Hooks hooks) {
        if (maxBytes < 1 || maxBytes > Integer.MAX_VALUE - 16) {
            throw new IllegalArgumentException("maxBytes out of range: " + maxBytes);
        }
        this.maxBytes = maxBytes;
        this.hooks = Objects.requireNonNull(hooks);
    }

    public int maxBytes() {
        return maxBytes;
    }

    /**
     * Reads the file. Returns empty if it doesn't exist. Either way, a later {@link #save} is allowed
     * as long as the file doesn't change in between.
     */
    public synchronized Optional<byte[]> load(Path path) throws FileStoreException {
        Path file = normalize(path);
        baselines.remove(file);
        uncertain.remove(file);
        Target target = readTarget(file);
        baselines.put(file, target);
        return target.missing() ? Optional.empty() : Optional.of(target.bytes().clone());
    }

    /** True if the path was loaded (or saved) and no uncertain save has happened since. */
    public synchronized boolean canSave(Path path) {
        Path file = normalize(path);
        return baselines.containsKey(file) && !uncertain.contains(file);
    }

    /** Atomically replaces the file's contents; see the class description for the guarantees. */
    public synchronized void save(Path path, byte[] bytes) throws FileStoreException {
        Path file = normalize(path);
        byte[] candidate = bytes.clone();
        if (candidate.length > maxBytes) {
            throw new FileStoreException(Kind.TOO_LARGE, file,
                    "Not saved: " + candidate.length + " bytes exceeds the " + maxBytes + "-byte limit", null);
        }
        if (uncertain.contains(file)) {
            throw new FileStoreException(Kind.UNCERTAIN, file,
                    "Not saved: an earlier save of " + file.getFileName()
                            + " had an uncertain outcome; load it again before saving", null);
        }
        Target baseline = baselines.get(file);
        if (baseline == null) {
            throw new FileStoreException(Kind.NOT_LOADED, file,
                    "Not saved: " + file.getFileName() + " must be loaded before it is saved", null);
        }
        Path parent = file.getParent();
        if (parent == null) {
            throw new FileStoreException(Kind.WRITE_FAILED, file, "Not saved: the path has no parent directory", null);
        }

        Path temporary = null;
        Target prior = null;
        boolean publishing = false;
        try {
            Files.createDirectories(parent);
            try (FileChannel lockChannel = openLockChannel(file);
                 FileLock ignored = acquire(lockChannel, file)) {
                Target current = readTarget(file);
                if (!current.sameAs(baseline)) {
                    throw new FileStoreException(Kind.CHANGED_EXTERNALLY, file,
                            "Not saved: " + file.getFileName()
                                    + " changed outside Sculptory since it was loaded; load it again first", null);
                }
                prior = current;
                Temporary created = createTemporary(file, parent);
                temporary = created.path();
                try (FileChannel output = created.channel()) {
                    writeFully(output, candidate);
                    output.force(true);
                }
                hooks.beforePublish(temporary, file);
                publishing = true;
                hooks.move(temporary, file);
            }
            hooks.afterPublishResourcesClosed(file);
            baselines.put(file, Target.present(candidate));
        } catch (IOException | RuntimeException failure) {
            if (publishing) {
                Target after = readQuietly(file);
                if (after != null && after.holds(candidate)) {
                    baselines.put(file, Target.present(candidate));
                    return;
                }
                if (after != null && after.sameAs(prior)) {
                    throw new FileStoreException(Kind.WRITE_FAILED, file,
                            "Not saved; the previous file is intact: " + describe(failure), failure);
                }
                uncertain.add(file);
                throw new FileStoreException(Kind.UNCERTAIN, file,
                        "Save outcome uncertain: " + file.getFileName()
                                + " matches neither the old nor the new contents; load it again before saving. "
                                + describe(failure), failure);
            }
            if (failure instanceof FileStoreException typed) {
                throw typed;
            }
            throw new FileStoreException(Kind.WRITE_FAILED, file,
                    "Not saved; the previous file is intact: " + describe(failure), failure);
        } catch (Error fatal) {
            if (publishing) {
                uncertain.add(file);
            }
            throw fatal;
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Best effort: a stray private temporary is harmless.
                }
            }
        }
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private void writeFully(FileChannel output, byte[] candidate) throws IOException {
        ByteBuffer source = ByteBuffer.wrap(candidate);
        int emptyWrites = 0;
        while (source.hasRemaining()) {
            int written = hooks.write(output, source);
            if (written < 0) {
                throw new IOException("Unexpected end while writing");
            }
            if (written == 0) {
                if (++emptyWrites > 1024) {
                    throw new IOException("Write made no progress");
                }
            } else {
                emptyWrites = 0;
            }
        }
    }

    private static FileChannel openLockChannel(Path file) throws IOException {
        Path lockPath = file.resolveSibling(file.getFileName() + ".lock");
        for (int attempt = 0; attempt < 32; attempt++) {
            try {
                return FileChannel.open(lockPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ,
                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            } catch (FileAlreadyExistsException exists) {
                BasicFileAttributes attributes = Files.readAttributes(lockPath, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isRegularFile()) {
                    throw new IOException("The companion lock " + lockPath.getFileName() + " is not a regular file");
                }
                try {
                    // READ+WRITE avoids blocking on a FIFO swapped in after the attribute check.
                    return FileChannel.open(lockPath, StandardOpenOption.READ, StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS);
                } catch (NoSuchFileException removed) {
                    // Another writer cleaned it up; try creating it again.
                }
            }
        }
        throw new IOException("Could not open the companion lock for " + file.getFileName());
    }

    private static FileLock acquire(FileChannel channel, Path file) throws FileStoreException {
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException heldInThisProcess) {
            lock = null;
        } catch (IOException failure) {
            throw new FileStoreException(Kind.WRITE_FAILED, file,
                    "Not saved: could not lock " + file.getFileName() + ": " + describe(failure), failure);
        }
        if (lock == null) {
            throw new FileStoreException(Kind.BUSY, file,
                    "Not saved: " + file.getFileName() + " is being saved by another writer; try again", null);
        }
        return lock;
    }

    private static Temporary createTemporary(Path file, Path parent) throws IOException {
        String prefix = "." + file.getFileName() + ".";
        for (int attempt = 0; attempt < 32; attempt++) {
            Path candidate = parent.resolve(prefix + UUID.randomUUID() + ".tmp");
            try {
                FileChannel channel = FileChannel.open(candidate, StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                return new Temporary(candidate, channel);
            } catch (FileAlreadyExistsException collision) {
                // Pick a new name; never adopt or delete a path we didn't create.
            }
        }
        throw new IOException("Could not allocate a temporary file next to " + file.getFileName());
    }

    private Target readTarget(Path file) throws FileStoreException {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            return Target.MISSING;
        } catch (IOException failure) {
            throw new FileStoreException(Kind.READ_FAILED, file,
                    "Could not read " + file.getFileName() + ": " + describe(failure), failure);
        }
        if (!attributes.isRegularFile()) {
            throw new FileStoreException(Kind.NOT_REGULAR_FILE, file,
                    file.getFileName() + " is not a regular file (it is a directory, link or device)", null);
        }
        if (attributes.size() > maxBytes) {
            throw tooLarge(file);
        }
        byte[] buffer = new byte[(int) Math.min(maxBytes + 1L, attributes.size() + 1)];
        int used = 0;
        int emptyReads = 0;
        try (FileChannel input = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            while (true) {
                if (used == buffer.length) {
                    if (buffer.length > maxBytes) {
                        throw tooLarge(file);
                    }
                    buffer = Arrays.copyOf(buffer, (int) Math.min(maxBytes + 1L, buffer.length * 2L));
                }
                int read = input.read(ByteBuffer.wrap(buffer, used, buffer.length - used));
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    if (++emptyReads > 1024) {
                        throw new IOException("Read made no progress");
                    }
                } else {
                    used += read;
                    emptyReads = 0;
                }
            }
        } catch (FileStoreException typed) {
            throw typed;
        } catch (NoSuchFileException removed) {
            return Target.MISSING;
        } catch (IOException failure) {
            throw new FileStoreException(Kind.READ_FAILED, file,
                    "Could not read " + file.getFileName() + ": " + describe(failure), failure);
        }
        if (used > maxBytes) {
            throw tooLarge(file);
        }
        return Target.present(Arrays.copyOf(buffer, used));
    }

    private FileStoreException tooLarge(Path file) {
        return new FileStoreException(Kind.TOO_LARGE, file,
                file.getFileName() + " is larger than the " + maxBytes + "-byte limit", null);
    }

    private Target readQuietly(Path file) {
        try {
            return readTarget(file);
        } catch (IOException | RuntimeException failure) {
            return null;
        }
    }

    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
