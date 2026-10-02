package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.EngineTestSupport.check;

import dev.sculptory.core.Box;
import dev.sculptory.fabric.engine.ClipboardService;
import dev.sculptory.fabric.engine.impl.ServerClipboards;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.fabric.schem.FabricDataFixHook;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.library.Library;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.block.Block;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import org.slf4j.LoggerFactory;

/** Helpers for the M2 GameTests (clipboards, schematics, library). Server thread only. */
final class ClipTestSupport {
    private ClipTestSupport() {}

    /** Records the one answer a {@link ClipboardService.Reply} gets. */
    static final class Captured<T> implements ClipboardService.Reply<T> {
        T value;
        RejectReason reason;
        String detail;
        int calls;

        @Override
        public void done(T v) {
            value = v;
            calls++;
        }

        @Override
        public void failed(RejectReason r, String d) {
            reason = r;
            detail = d;
            calls++;
        }

        boolean finished() {
            return calls > 0;
        }

        /** Waits (as a timed-task check) for the answer, then requires success. */
        T get(String what) {
            check(finished(), what + " still running");
            check(calls == 1, what + " answered " + calls + " times");
            check(value != null, what + " failed: " + reason + " " + detail);
            return value;
        }

        /** Waits for the answer, then requires a failure with {@code expected}. */
        void failedWith(RejectReason expected, String what) {
            check(finished(), what + " still running");
            check(value == null && reason == expected, what + ": expected " + expected + ", got " + reason + " "
                    + detail + (value != null ? " (succeeded)" : ""));
        }
    }

    /** A private library folder for one test, under the GameTest server's run directory. */
    static Path libraryRoot(TestContext context) {
        return context.getWorld().getServer().getRunDirectory().resolve("sculptory-gametest")
                .resolve(UUID.randomUUID().toString()).resolve("library").toAbsolutePath().normalize();
    }

    /** A clipboard service over the harness's edit service and a private library, with its own bounded executor. */
    static ServerClipboards clipboards(Harness h, Path libraryRoot) {
        return new ServerClipboards(h.service, new Library(libraryRoot, Library.Settings.DEFAULTS),
                ServerClipboards.newExecutor(), FabricDataFixHook.get());
    }

    /** Begins and completes an upload in one go (the network part is the dispatcher's, unit-tested). */
    static void upload(ServerClipboards clips, net.minecraft.server.network.ServerPlayerEntity player, String name,
                       byte[] bytes, ClipboardService.Reply<ClipboardService.ClipboardInfo> reply) {
        try {
            clips.beginUpload(player, name, bytes.length).completed(bytes, reply);
        } catch (dev.sculptory.server.engine.EditRejected e) {
            reply.failed(e.reason(), e.getMessage());
        }
    }

    /**
     * A directory link: a symbolic link, or on Windows without the symlink privilege a junction ({@code mklink /J},
     * which needs none); {@code toRealPath} resolves both. False when neither can be made.
     */
    static boolean linkDirectory(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            // fall through to a junction
        }
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) return false;
        try {
            Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor() == 0 && Files.isDirectory(link);
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Deletes a test folder (best effort; links are removed, never followed). */
    static void deleteTree(Path root) {
        if (root == null || !Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (attrs.isSymbolicLink() || attrs.isOther()) {
                        Files.deleteIfExists(dir);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LoggerFactory.getLogger("sculptory").warn("Could not delete test folder {}", root, e);
        }
    }

    /**
     * Requires the cells of {@code expected} (states and block-entity NBT) to be found again with their minimum corner
     * at {@code targetMin}.
     */
    static void checkShifted(ServerWorld world, WorldSnapshot expected, BlockPos targetMin, String what) {
        checkShifted(world, expected, targetMin, what, pos -> false);
    }

    /** As above; block entities at the source positions {@code skipTile} accepts are compared by presence only. */
    static void checkShifted(ServerWorld world, WorldSnapshot expected, BlockPos targetMin, String what,
                             java.util.function.Predicate<BlockPos> skipTile) {
        Box source = expected.box;
        int dx = targetMin.getX() - source.min().x(), dy = targetMin.getY() - source.min().y();
        int dz = targetMin.getZ() - source.min().z();
        Box target = source.offset(dx, dy, dz);
        WorldSnapshot actual = EditTestSupport.capture(world, target);
        for (int y = source.min().y(); y <= source.max().y(); y++) {
            for (int z = source.min().z(); z <= source.max().z(); z++) {
                for (int x = source.min().x(); x <= source.max().x(); x++) {
                    int a = expected.get(x, y, z), b = actual.get(x + dx, y + dy, z + dz);
                    if (a != b) {
                        throw new GameTestException(what + ": at " + (x + dx) + "," + (y + dy) + "," + (z + dz)
                                + " expected " + Block.getStateFromRawId(a) + ", got " + Block.getStateFromRawId(b));
                    }
                }
            }
        }
        check(expected.tiles.size() == actual.tiles.size(), what + ": " + expected.tiles.size() + " block entities, got "
                + actual.tiles.size());
        for (Map.Entry<Long, NbtCompound> entry : expected.tiles.entrySet()) {
            BlockPos at = BlockPos.fromLong(entry.getKey()).add(dx, dy, dz);
            NbtCompound got = actual.tiles.get(at.asLong());
            if (skipTile.test(BlockPos.fromLong(entry.getKey()))) {
                check(got != null, what + ": no block entity at " + at.toShortString());
                continue;
            }
            check(Objects.equals(entry.getValue(), got), what + ": block entity at " + at.toShortString() + " expected "
                    + entry.getValue() + ", got " + got);
        }
    }
}
