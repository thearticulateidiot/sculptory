package dev.sculptory.core.history.store;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * The file operations the history store uses, so tests can inject failures (a full disk, a failed rename, a torn
 * write). {@link #SYSTEM} is the real file system.
 */
public interface StorageIo {
    /** One open file, read and written at explicit positions. */
    interface File extends Closeable {
        long size() throws IOException;

        /** Reads up to {@code dst.remaining()} bytes at {@code position}; -1 at the end. */
        int read(ByteBuffer dst, long position) throws IOException;

        /** Writes all of {@code src} at {@code position}. */
        void write(ByteBuffer src, long position) throws IOException;

        void truncate(long size) throws IOException;

        /** Forces written data to the device. */
        void force() throws IOException;
    }

    /** Opens {@code file} for reading and writing, creating it if absent. */
    File open(Path file) throws IOException;

    /**
     * Whether {@code file} exists: false only when it certainly does not.
     *
     * @throws IOException when that cannot be told (a file that cannot be examined may exist, so it is never taken as
     *     absent: that would let a new, empty history be written over it)
     */
    boolean exists(Path file) throws IOException;

    /** Bytes free for this program on the file system holding {@code dir} (checked before copying a file there). */
    default long usableSpace(Path dir) throws IOException {
        return Files.getFileStore(dir).getUsableSpace();
    }

    /**
     * The regular files directly inside {@code dir}, plus any entry whose type cannot be read (it may be one; opening it
     * tells). Empty only when {@code dir} does not exist.
     *
     * @throws IOException when {@code dir} exists but cannot be listed, or the listing fails part way (never an unchecked
     *     exception for that)
     */
    List<Path> list(Path dir) throws IOException;

    void createDirectories(Path dir) throws IOException;

    /** Moves {@code source} over {@code target} atomically (replacing it), then syncs the directory where it can. */
    void replace(Path source, Path target) throws IOException;

    /** Deletes {@code file} if it exists. */
    void delete(Path file) throws IOException;

    /** Copies {@code source} to {@code target}, replacing it (a damaged file kept aside, the source left as it is). */
    void copy(Path source, Path target) throws IOException;

    StorageIo SYSTEM = new StorageIo() {
        @Override
        public File open(Path file) throws IOException {
            FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ,
                    StandardOpenOption.WRITE);
            return new File() {
                @Override
                public long size() throws IOException {
                    return channel.size();
                }

                @Override
                public int read(ByteBuffer dst, long position) throws IOException {
                    return channel.read(dst, position);
                }

                @Override
                public void write(ByteBuffer src, long position) throws IOException {
                    long at = position;
                    while (src.hasRemaining()) at += channel.write(src, at);
                }

                @Override
                public void truncate(long size) throws IOException {
                    channel.truncate(size);
                }

                @Override
                public void force() throws IOException {
                    channel.force(false);
                }

                @Override
                public void close() throws IOException {
                    channel.close();
                }
            };
        }

        /** Not {@code Files.exists}, which answers false when the file's attributes cannot be read. */
        @Override
        public boolean exists(Path file) throws IOException {
            try {
                Files.readAttributes(file, BasicFileAttributes.class);
                return true;
            } catch (NoSuchFileException absent) {
                return false;
            }
        }

        /**
         * {@code Files.isDirectory} and {@code isRegularFile} answer false when they cannot read a path's attributes, so
         * a folder or file that exists but cannot be examined would look absent: here only "does not exist" gives an
         * empty listing, and an entry that cannot be examined is listed.
         */
        @Override
        public List<Path> list(Path dir) throws IOException {
            List<Path> files = new ArrayList<>();
            DirectoryStream<Path> stream;
            try {
                stream = Files.newDirectoryStream(dir);
            } catch (NoSuchFileException absent) {
                return files;
            }
            try (stream) {
                for (Path path : stream) {
                    BasicFileAttributes attributes;
                    try {
                        attributes = Files.readAttributes(path, BasicFileAttributes.class);
                    } catch (NoSuchFileException gone) {
                        continue; // deleted since it was listed (or a link to nothing)
                    } catch (IOException unknown) {
                        files.add(path);
                        continue;
                    }
                    if (attributes.isRegularFile()) files.add(path);
                }
            } catch (DirectoryIteratorException e) {
                throw e.getCause(); // an I/O error part way through the listing
            }
            return files;
        }

        @Override
        public void createDirectories(Path dir) throws IOException {
            Files.createDirectories(dir);
        }

        @Override
        public void replace(Path source, Path target) throws IOException {
            try {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
            // Makes the rename durable where the platform allows syncing a directory (not on Windows).
            Path dir = target.toAbsolutePath().getParent();
            if (dir != null) {
                try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
                    channel.force(true);
                } catch (IOException | UnsupportedOperationException ignored) {
                    // Best effort.
                }
            }
        }

        @Override
        public void delete(Path file) throws IOException {
            Files.deleteIfExists(file);
        }

        @Override
        public void copy(Path source, Path target) throws IOException {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    };
}
