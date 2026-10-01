package dev.sculptory.fabric.client.util;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.sculptory.fabric.client.util.FileStoreException.Kind;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/** Ported from the legacy PresetPersistenceTest storage checks, generalized to raw bytes. */
class AtomicFileStoreTest {
    private static final byte[] FIRST = "{\"first\": true}\n".getBytes(UTF_8);
    private static final byte[] SECOND = "{\"second\": \"a longer document\"}\n".getBytes(UTF_8);

    @TempDir
    Path dir;

    /** An unchecked error type, so it can't be confused with a JUnit assertion failure. */
    private static final class InjectedFatal extends Error {
        InjectedFatal() {
            super("injected fatal error");
        }
    }

    private static Kind kindOf(Executable action) {
        return assertThrows(FileStoreException.class, action).kind();
    }

    private static List<Path> privateTemps(Path target) throws IOException {
        String prefix = "." + target.getFileName() + ".";
        try (var siblings = Files.list(target.getParent())) {
            return siblings.filter(path -> path.getFileName().toString().startsWith(prefix)
                    && path.getFileName().toString().endsWith(".tmp")).sorted().toList();
        }
    }

    private static AtomicFileStore storeWith(AtomicFileStore.Hooks hooks) {
        return new AtomicFileStore(AtomicFileStore.DEFAULT_MAX_BYTES, hooks);
    }

    // ---- Normal operation ----

    @Test
    void missingFileLoadsEmptyAndFirstSaveCreatesItAndItsParents() throws IOException {
        Path file = dir.resolve("config/sculptory/editor-layout.json");
        AtomicFileStore store = new AtomicFileStore();
        assertEquals(Optional.empty(), store.load(file));
        assertTrue(store.canSave(file));
        store.save(file, FIRST);
        assertArrayEquals(FIRST, Files.readAllBytes(file));
        assertArrayEquals(FIRST, new AtomicFileStore().load(file).orElseThrow());
        assertEquals(List.of(), privateTemps(file));
    }

    @Test
    void successiveSavesReplaceTheFileExactly() throws IOException {
        Path file = dir.resolve("keys.json");
        AtomicFileStore store = new AtomicFileStore();
        store.load(file);
        store.save(file, FIRST);
        store.save(file, SECOND);
        assertArrayEquals(SECOND, new AtomicFileStore().load(file).orElseThrow());
        store.save(file, new byte[0]);
        assertTrue(Files.exists(file));
        assertArrayEquals(new byte[0], new AtomicFileStore().load(file).orElseThrow(), "empty is not missing");
    }

    @Test
    void loadReturnsACopy() throws IOException {
        Path file = dir.resolve("presets.dat");
        Files.write(file, FIRST);
        AtomicFileStore store = new AtomicFileStore();
        byte[] loaded = store.load(file).orElseThrow();
        loaded[0] = 'X';
        store.save(file, SECOND);
        assertArrayEquals(SECOND, Files.readAllBytes(file), "mutating the returned array doesn't fake a change");
    }

    @Test
    void saveBeforeLoadIsRefused() {
        Path file = dir.resolve("never-loaded.json");
        assertEquals(Kind.NOT_LOADED, kindOf(() -> new AtomicFileStore().save(file, FIRST)));
        assertFalse(Files.exists(file));
    }

    // ---- Refusals on load ----

    @Test
    void directoryTargetIsRefused() throws IOException {
        Path target = dir.resolve("presets.dat");
        Files.createDirectory(target);
        AtomicFileStore store = new AtomicFileStore();
        assertEquals(Kind.NOT_REGULAR_FILE, kindOf(() -> store.load(target)));
        assertFalse(store.canSave(target));
        assertEquals(Kind.NOT_LOADED, kindOf(() -> store.save(target, FIRST)));
        assertTrue(Files.isDirectory(target));
    }

    @Test
    void symbolicLinkTargetIsRefusedWithoutFollowingIt() throws IOException {
        Path real = dir.resolve("real.dat");
        Files.write(real, FIRST);
        Path link = dir.resolve("presets.dat");
        assumeTrue(createSymbolicLink(link, real.getFileName()), "the OS refused to create a symbolic link");
        AtomicFileStore store = new AtomicFileStore();
        assertEquals(Kind.NOT_REGULAR_FILE, kindOf(() -> store.load(link)));
        assertEquals(Kind.NOT_LOADED, kindOf(() -> store.save(link, SECOND)));
        assertArrayEquals(FIRST, Files.readAllBytes(real), "the link's referent is untouched");
    }

    /** Windows without Developer Mode throws "A required privilege is not held by the client". */
    private static boolean createSymbolicLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (UnsupportedOperationException | IOException refused) {
            return false;
        }
    }

    @Test
    void oversizedFileIsRefusedAndPreserved() throws IOException {
        Path file = dir.resolve("presets.dat");
        Files.write(file, new byte[17]);
        AtomicFileStore store = new AtomicFileStore(16);
        assertEquals(Kind.TOO_LARGE, kindOf(() -> store.load(file)));
        assertEquals(17, Files.size(file));
    }

    @Test
    void fileAtExactlyTheLimitLoads() throws IOException {
        Path file = dir.resolve("presets.dat");
        Files.write(file, new byte[16]);
        assertEquals(16, new AtomicFileStore(16).load(file).orElseThrow().length);
    }

    @Test
    void oversizedCandidateIsRefusedBeforeAnyIo() throws IOException {
        Path file = dir.resolve("absent-parent/presets.dat");
        AtomicFileStore store = new AtomicFileStore(16);
        store.load(file);
        assertEquals(Kind.TOO_LARGE, kindOf(() -> store.save(file, new byte[17])));
        assertFalse(Files.exists(file.getParent()), "rejected before the directory was created");
        assertTrue(store.canSave(file));
    }

    @Test
    void unreadableFileFailsToLoad() throws IOException {
        Path file = dir.resolve("presets.dat");
        Files.write(file, FIRST);
        assumeTrue(Files.getFileStore(file).supportsFileAttributeView(PosixFileAttributeView.class),
                "POSIX permissions not supported here");
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(file);
        Files.setPosixFilePermissions(file, Set.of());
        try {
            assumeFalse(Files.isReadable(file), "running with privileges that ignore permissions");
            AtomicFileStore store = new AtomicFileStore();
            assertEquals(Kind.READ_FAILED, kindOf(() -> store.load(file)));
            assertFalse(store.canSave(file));
        } finally {
            Files.setPosixFilePermissions(file, original);
        }
    }

    // ---- Writing ----

    @Test
    void shortWritesLoopUntilTheWholeCandidateIsWritten() throws IOException {
        Path file = dir.resolve("presets.dat");
        AtomicFileStore store = storeWith(new AtomicFileStore.Hooks() {
            @Override
            public int write(FileChannel channel, ByteBuffer source) throws IOException {
                ByteBuffer slice = source.slice();
                slice.limit(Math.min(3, source.remaining()));
                int written = channel.write(slice);
                source.position(source.position() + written);
                return written;
            }
        });
        store.load(file);
        store.save(file, SECOND);
        assertArrayEquals(SECOND, Files.readAllBytes(file));
    }

    @Test
    void failureBeforePublishingKeepsTheTargetAndCanBeRetried() throws IOException {
        Path file = dir.resolve("presets.dat");
        Files.write(file, FIRST);
        Path unrelated = dir.resolve("unrelated.tmp");
        Files.write(unrelated, new byte[] {9});
        AtomicBoolean failOnce = new AtomicBoolean(true);
        AtomicFileStore store = storeWith(new AtomicFileStore.Hooks() {
            @Override
            public void beforePublish(Path temporary, Path target) throws IOException {
                if (failOnce.getAndSet(false)) {
                    throw new IOException("injected prepublish failure");
                }
            }
        });
        store.load(file);
        FileStoreException failure = assertThrows(FileStoreException.class, () -> store.save(file, SECOND));
        assertEquals(Kind.WRITE_FAILED, failure.kind());
        assertTrue(failure.retryable());
        assertTrue(failure.getMessage().contains("injected prepublish failure"), failure.getMessage());
        assertArrayEquals(FIRST, Files.readAllBytes(file));
        assertTrue(Files.exists(unrelated), "unrelated siblings are left alone");
        assertEquals(List.of(), privateTemps(file), "only our own temporary is cleaned up");

        store.save(file, SECOND);
        assertArrayEquals(SECOND, Files.readAllBytes(file));
    }

    @Test
    void failedMoveReconcilesToTheOldFileAsRetryable() throws IOException {
        Path file = dir.resolve("presets.dat");
        Files.write(file, FIRST);
        AtomicFileStore store = storeWith(new AtomicFileStore.Hooks() {
            @Override
            public void move(Path temporary, Path target) throws IOException {
                throw new IOException("injected atomic move failure");
            }
        });
        store.load(file);
        assertEquals(Kind.WRITE_FAILED, kindOf(() -> store.save(file, SECOND)));
        assertArrayEquals(FIRST, Files.readAllBytes(file));
        assertTrue(store.canSave(file));
        assertEquals(List.of(), privateTemps(file));
    }

    @Test
    void checkedExceptionAfterASuccessfulMoveCountsAsSuccess() throws IOException {
        assertPostMoveFailureIsSuccess(new IOException("injected post-move report"));
    }

    @Test
    void runtimeExceptionAfterASuccessfulMoveCountsAsSuccess() throws IOException {
        assertPostMoveFailureIsSuccess(new IllegalStateException("injected post-move runtime failure"));
    }

    private void assertPostMoveFailureIsSuccess(Exception injected) throws IOException {
        Path file = dir.resolve("presets.dat");
        Files.write(file, FIRST);
        AtomicFileStore store = storeWith(new AtomicFileStore.Hooks() {
            @Override
            public void move(Path temporary, Path target) throws IOException {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                if (injected instanceof IOException io) {
                    throw io;
                }
                throw (RuntimeException) injected;
            }
        });
        store.load(file);
        store.save(file, SECOND);
        assertArrayEquals(SECOND, Files.readAllBytes(file));
        assertTrue(store.canSave(file));
    }

    @Test
    void failureAfterResourcesCloseCountsAsSuccess() throws IOException {
        Path file = dir.resolve("presets.dat");
        Files.write(file, FIRST);
        AtomicBoolean failOnce = new AtomicBoolean(true);
        AtomicFileStore store = storeWith(new AtomicFileStore.Hooks() {
            @Override
            public void afterPublishResourcesClosed(Path target) throws IOException {
                if (failOnce.getAndSet(false)) {
                    throw new IOException("injected resource-close failure");
                }
            }
        });
        store.load(file);
        store.save(file, SECOND);
        assertArrayEquals(SECOND, Files.readAllBytes(file));
        store.save(file, FIRST);
        assertArrayEquals(FIRST, Files.readAllBytes(file), "the store's memory matches the new file");
    }

    @Test
    void savingIdenticalBytesWhenTheMoveFailsIsConsistent() throws IOException {
        Path file = dir.resolve("presets.dat");
        Files.write(file, FIRST);
        Path foreign = dir.resolve(".presets.dat.foreign.tmp");
        Files.write(foreign, new byte[] {4, 2});
        AtomicFileStore store = storeWith(new AtomicFileStore.Hooks() {
            @Override
            public void move(Path temporary, Path target) throws IOException {
                throw new IOException("injected equal-document move failure");
            }
        });
        store.load(file);
        store.save(file, FIRST);
        assertArrayEquals(FIRST, Files.readAllBytes(file));
        assertEquals(List.of(foreign), privateTemps(file), "our temporary is gone, the foreign one kept");
    }

    // ---- Uncertain outcomes ----

    @Test
    void fatalErrorAfterPublishingIsRethrownAndBlocksSavesUntilReload() throws IOException {
        Path file = dir.resolve("presets.dat");
        Files.write(file, FIRST);
        AtomicBoolean fatalOnce = new AtomicBoolean(true);
        AtomicFileStore store = storeWith(new AtomicFileStore.Hooks() {
            @Override
            public void move(Path temporary, Path target) throws IOException {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                if (fatalOnce.getAndSet(false)) {
                    throw new InjectedFatal();
                }
            }
        });
        store.load(file);
        assertThrows(InjectedFatal.class, () -> store.save(file, SECOND));
        assertFalse(store.canSave(file));
        assertEquals(Kind.UNCERTAIN, kindOf(() -> store.save(file, FIRST)));
        assertArrayEquals(SECOND, Files.readAllBytes(file));

        assertArrayEquals(SECOND, store.load(file).orElseThrow());
        store.save(file, FIRST);
        assertArrayEquals(FIRST, Files.readAllBytes(file));
    }

    @Test
    void unrecognisedOutcomeIsUncertainAndNeverOverwritten() throws IOException {
        Path file = dir.resolve("presets.dat");
        Files.write(file, FIRST);
        byte[] unknown = {7, 7, 7};
        AtomicBoolean corruptOnce = new AtomicBoolean(true);
        AtomicFileStore store = storeWith(new AtomicFileStore.Hooks() {
            @Override
            public void move(Path temporary, Path target) throws IOException {
                if (corruptOnce.getAndSet(false)) {
                    Files.write(target, unknown);
                    throw new IOException("injected uncertain move");
                }
                AtomicFileStore.Hooks.SYSTEM.move(temporary, target);
            }
        });
        store.load(file);
        FileStoreException failure = assertThrows(FileStoreException.class, () -> store.save(file, SECOND));
        assertEquals(Kind.UNCERTAIN, failure.kind());
        assertFalse(failure.retryable());
        assertArrayEquals(unknown, Files.readAllBytes(file));
        assertEquals(Kind.UNCERTAIN, kindOf(() -> store.save(file, SECOND)));
        assertArrayEquals(unknown, Files.readAllBytes(file), "not deleted or overwritten again");

        assertArrayEquals(unknown, store.load(file).orElseThrow());
        store.save(file, SECOND);
        assertArrayEquals(SECOND, Files.readAllBytes(file));
    }

    // ---- Other writers ----

    @Test
    void externalChangeIsNeverOverwritten() throws IOException {
        Path file = dir.resolve("presets.dat");
        AtomicFileStore store = new AtomicFileStore();
        store.load(file);
        store.save(file, FIRST);
        Files.write(file, SECOND);
        assertEquals(Kind.CHANGED_EXTERNALLY, kindOf(() -> store.save(file, FIRST)));
        assertArrayEquals(SECOND, Files.readAllBytes(file));
        assertEquals(Kind.CHANGED_EXTERNALLY, kindOf(() -> store.save(file, FIRST)), "still refused until reload");

        store.load(file);
        store.save(file, FIRST);
        assertArrayEquals(FIRST, Files.readAllBytes(file));
    }

    @Test
    void externalCreationAndDeletionAreChangesToo() throws IOException {
        Path created = dir.resolve("created.dat");
        AtomicFileStore store = new AtomicFileStore();
        store.load(created);
        Files.write(created, SECOND);
        assertEquals(Kind.CHANGED_EXTERNALLY, kindOf(() -> store.save(created, FIRST)));
        assertArrayEquals(SECOND, Files.readAllBytes(created));

        Path deleted = dir.resolve("deleted.dat");
        Files.write(deleted, FIRST);
        store.load(deleted);
        Files.delete(deleted);
        assertEquals(Kind.CHANGED_EXTERNALLY, kindOf(() -> store.save(deleted, SECOND)));
        assertFalse(Files.exists(deleted));
    }

    @Test
    void nonRegularCompanionLockIsRefusedBeforeTouchingTheTarget() throws IOException {
        Path file = dir.resolve("presets.dat");
        AtomicFileStore store = new AtomicFileStore();
        store.load(file);
        Path companion = dir.resolve("presets.dat.lock");
        Files.createDirectory(companion);
        FileStoreException failure = assertThrows(FileStoreException.class, () -> store.save(file, FIRST));
        assertEquals(Kind.WRITE_FAILED, failure.kind());
        assertTrue(Files.isDirectory(companion));
        assertFalse(Files.exists(file));
        assertTrue(store.canSave(file));
    }

    @Test
    void heldLockReportsBusyWithoutTouchingTheTarget() throws IOException {
        Path file = dir.resolve("presets.dat");
        AtomicFileStore store = new AtomicFileStore();
        store.load(file);
        Path companion = dir.resolve("presets.dat.lock");
        try (FileChannel channel = FileChannel.open(companion, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            FileStoreException failure = assertThrows(FileStoreException.class, () -> store.save(file, FIRST));
            assertEquals(Kind.BUSY, failure.kind());
            assertTrue(failure.retryable());
        }
        assertFalse(Files.exists(file));
        store.save(file, FIRST);
        assertArrayEquals(FIRST, Files.readAllBytes(file), "works once the other writer lets go");
    }

    @Test
    void separateStoresOnTheSameFileDetectEachOthersWrites() throws IOException {
        Path file = dir.resolve("presets.dat");
        AtomicFileStore one = new AtomicFileStore();
        AtomicFileStore two = new AtomicFileStore();
        one.load(file);
        two.load(file);
        one.save(file, FIRST);
        assertEquals(Kind.CHANGED_EXTERNALLY, kindOf(() -> two.save(file, SECOND)));
        assertArrayEquals(FIRST, Files.readAllBytes(file));
    }

    @Test
    void relativeAndAbsoluteSpellingsShareOneBaseline() throws IOException {
        Path file = dir.resolve("presets.dat");
        AtomicFileStore store = new AtomicFileStore();
        store.load(dir.resolve("sub/../presets.dat"));
        store.save(file, FIRST);
        assertArrayEquals(FIRST, Files.readAllBytes(file));
    }

    @Test
    void invalidLimitsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new AtomicFileStore(0));
        assertThrows(IllegalArgumentException.class, () -> new AtomicFileStore(Integer.MAX_VALUE));
    }
}
