package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.EditTestSupport.capture;
import static dev.sculptory.fabric.gametest.EditTestSupport.checkSame;
import static dev.sculptory.fabric.gametest.EditTestSupport.loadAndForce;
import static dev.sculptory.fabric.gametest.EngineTestSupport.box;
import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.forceChunks;
import static dev.sculptory.fabric.gametest.EngineTestSupport.pos;
import static dev.sculptory.fabric.gametest.EngineTestSupport.regionCorner;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.generate.GeneratedSource;
import dev.sculptory.core.generate.SparseUpload;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.fabric.engine.impl.ServerClipboards;
import dev.sculptory.fabric.gametest.ClipTestSupport.Captured;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.fabric.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.fabric.net.FabricTransport;
import dev.sculptory.fabric.world.BlockWriter;
import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.protocol.v2.Codec;
import dev.sculptory.protocol.v2.Features;
import dev.sculptory.protocol.v2.Handshake;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.Limits;
import dev.sculptory.protocol.v2.Message;
import dev.sculptory.protocol.v2.ProtocolException;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.protocol.v2.S2C;
import dev.sculptory.protocol.v2.StreamAbort;
import dev.sculptory.protocol.v2.StreamKind;
import dev.sculptory.protocol.v2.StreamSender;
import dev.sculptory.server.engine.ClipboardService;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.RunOptions;
import dev.sculptory.server.engine.ScatterService;
import dev.sculptory.server.net.NetSession;
import dev.sculptory.server.net.ServerDispatcher;
import dev.sculptory.server.platform.WriteOptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;

/**
 * Generators: a sparse clipboard upload pasted with
 * "include air" writes exactly its cells and nothing else, undoes and redoes exactly; refusals leave no clipboard;
 * protected cells are skipped. Region slots 820-859.
 */
public final class GenerateGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;

    /** Stone from y 100 to 103 over the area's columns. */
    private static void ground(Harness h, Box area) {
        BlockWriter writer = h.runtime.writer(h.world, WriteOptions.DEFAULT);
        int stone = h.state("minecraft:stone");
        for (int x = area.min().x(); x <= area.max().x(); x++) {
            for (int z = area.min().z(); z <= area.max().z(); z++) {
                for (int y = 100; y <= 103; y++) writer.write(x, y, z, stone, null);
            }
        }
    }

    /**
     * A generated source over two chunks: a "road" of stone bricks at y 103 (replacing stone) with cobblestone under it
     * at y 102, air cells at y 103 beside it (clearing stone) and glass in the air at y 105, with gaps between.
     */
    private static GeneratedSource source(Harness h, int x0, int z0) {
        int bricks = h.state("minecraft:stone_bricks"), cobble = h.state("minecraft:cobblestone");
        int glass = h.state("minecraft:glass"), air = h.state("minecraft:air");
        GeneratedSource.Builder builder = GeneratedSource.builder(10_000);
        for (int x = x0 + 2; x <= x0 + 29; x++) {
            builder.set(x, 103, z0 + 8, bricks).set(x, 102, z0 + 8, cobble);
            builder.set(x, 103, z0 + 7, air).set(x, 103, z0 + 9, air);
            if (x % 3 == 0) builder.set(x, 105, z0 + 8, glass);
        }
        Random random = new Random(820);
        for (int i = 0; i < 30; i++) {
            builder.set(x0 + 2 + random.nextInt(28), 104 + random.nextInt(3), z0 + 12 + random.nextInt(6), bricks);
        }
        return builder.build();
    }

    /** Every cell of {@code area} holds its source cell, else what {@code before} held. */
    private static void checkPasted(ServerWorld world, Box area, GeneratedSource source, WorldSnapshot before, String what) {
        WorldSnapshot now = capture(world, area);
        for (int y = area.min().y(); y <= area.max().y(); y++) {
            for (int z = area.min().z(); z <= area.max().z(); z++) {
                for (int x = area.min().x(); x <= area.max().x(); x++) {
                    int generated = source.get(x, y, z);
                    int want = generated >= 0 ? generated : before.get(x, y, z);
                    int got = now.get(x, y, z);
                    if (want != got) {
                        throw new GameTestException(what + " at " + x + "," + y + "," + z + ": "
                                + Block.getStateFromRawId(got) + " instead of " + Block.getStateFromRawId(want));
                    }
                }
            }
        }
    }

    private static OpSpec.Paste paste(java.util.UUID clipboardId, Box bounds) {
        return new OpSpec.Paste(new SourceRef.Clipboard(clipboardId), bounds.min(), Transform.IDENTITY,
                new PasteOptions(true, false, false));
    }

    /**
     * Through the network layer: a {@code GeneratedUpload} is granted, streamed, decoded and answered with
     * {@code ClipboardReady} and {@code UploadResult}; the paste with "include air" writes exactly the source's cells
     * (air included, replacing stone) and nothing else, as one history entry; undo restores every cell and redo writes
     * them again.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_generate_paste", tickLimit = LIMIT)
    public void aSparseUploadPastesExactlyItsCellsAndUndoesExactly(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 820);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 98, z0, x0 + 31, 110, z0 + 31);
        loadAndForce(world, area);
        ground(h, area);
        GeneratedSource source = source(h, x0, z0);
        Box bounds = source.bounds();
        WorldSnapshot before = capture(world, area);
        byte[] payload = SparseUpload.encode(source, h.runtime.states());
        ServerClipboards clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        ServerDispatcher<ServerPlayerEntity> dispatcher = new ServerDispatcher<>(h.service, clips, ScatterService.disabled(), h.runtime.permissions(),
                () -> Limits.DEFAULTS, h.runtime::states, System::nanoTime);
        Transport transport = new Transport(h, h.player);
        NetSession<ServerPlayerEntity> session = dispatcher.open(transport);
        transport.receive(dispatcher, session, Handshake.hello("test", Features.of(Features.REGION_OPS, Features.CLIPBOARD)));
        transport.receive(dispatcher, session, new C2S.GeneratedUpload(1, bounds, source.cells(), payload.length));
        S2C.UploadGrant grant = transport.first(S2C.UploadGrant.class);
        StreamSender sender = new StreamSender(grant.streamId(), StreamKind.GENERATED_UPLOAD, payload, new TreeMap<>(),
                StreamSender.MAX_C2S_CHUNK, grant.creditBytes());
        for (Message m : sender.poll(payload.length)) transport.receive(dispatcher, session, (C2S) m);
        check(sender.done(), "the whole payload fits one poll");
        RecordingListener undo = new RecordingListener(), redo = new RecordingListener();
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(!transport.sent(S2C.UploadResult.class).isEmpty(), "decoding: " + transport.sent))
                .createAndAdd(() -> {
                    S2C.UploadResult result = transport.first(S2C.UploadResult.class);
                    check(result.clipboardId() != null, "upload " + result);
                    S2C.ClipboardReady ready = transport.first(S2C.ClipboardReady.class);
                    check(ready.reqId() == 1 && ready.cells() == source.cells() && ready.entities() == 0
                            && ready.anchor().equals(BlockPos.ORIGIN)
                            && ready.dims().equals(new BlockPos(bounds.sizeX(), bounds.sizeY(), bounds.sizeZ())), "ready " + ready);
                    Clipboard held = h.service.clipboards().get(h.player.getUuid()).orElseThrow().clipboard();
                    check(held.cellCount() == source.cells() && held.tileCount() == 0 && held.entityCount() == 0,
                            "the clipboard is the source: " + held);
                    check(held.get(x0 + 2 - bounds.min().x(), 103 - bounds.min().y(), z0 + 7 - bounds.min().z())
                            == h.state("minecraft:air"), "air travels as a cell");
                    check(held.get(x0 + 2 - bounds.min().x(), 104 - bounds.min().y(), z0 + 8 - bounds.min().z()) == -1,
                            "absent cells stay absent");
                    transport.receive(dispatcher, session, new C2S.RunOp(2, paste(result.clipboardId(), bounds), false,
                            ConflictPolicy.SKIP_CONFLICTS));
                    check(transport.sent(S2C.JobAccepted.class).stream().anyMatch(a -> a.reqId() == 2),
                            "the paste was not accepted: " + transport.sent);
                })
                .createAndAdd(() -> check(!transport.sent(S2C.JobFinished.class).isEmpty(), "paste running"))
                .createAndAdd(() -> {
                    S2C.JobFinished finished = transport.first(S2C.JobFinished.class);
                    check(finished.outcome() == JobOutcome.COMPLETED && finished.skippedProtected() == 0, "paste " + finished);
                    checkPasted(world, area, source, before, "the paste");
                    check(h.history().undoLabel().startsWith("Paste · "), "history " + h.history());
                    dispatcher.close(session);
                    h.undo(undo);
                })
                .createAndAdd(() -> check(undo.result != null, "undo running"))
                .createAndAdd(() -> {
                    check(undo.result.outcome() == JobOutcome.COMPLETED && undo.result.skippedConflicts() == 0, "undo " + undo.result);
                    checkSame(before, capture(world, area), "after the undo");
                    h.redo(redo);
                })
                .createAndAdd(() -> check(redo.result != null, "redo running"))
                .createAndAdd(() -> {
                    check(redo.result.outcome() == JobOutcome.COMPLETED, "redo " + redo.result);
                    checkPasted(world, area, source, before, "the redo");
                    forceChunks(world, area, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * Refusals leave no clipboard: over the clipboard limit and without the {@code clipboard} node before the grant; a
     * payload naming a block-entity state once decoded; a malformed payload, and a stream aborted mid-way, through the
     * network layer. A well-formed one from the same player then goes through, so the refusals released the slot.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_generate_refusals", tickLimit = LIMIT)
    public void refusalsLeaveNoClipboard(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        ServerPlayerEntity builder = h.addPlayer(false);
        EditTestSupport.grant(builder, Perm.USE, Perm.CLIPBOARD, Perm.REGION);
        ServerPlayerEntity noClipboard = h.addPlayer(false);
        EditTestSupport.grant(noClipboard, Perm.USE, Perm.REGION);
        int[] at = regionCorner(context, 822);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 98, z0, x0 + 31, 110, z0 + 31);
        loadAndForce(world, area);
        GeneratedSource source = source(h, x0, z0);
        Box bounds = source.bounds();
        byte[] payload = SparseUpload.encode(source, h.runtime.states());
        ServerClipboards clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        long limit = h.runtime.config().limits.maxClipboardVolume;
        Box vast = box(x0, 0, z0, x0 + 199, 63, z0 + 199);
        check(vast.volume() > limit, "the fixture box holds the limit");
        check(refusal(() -> clips.beginGeneratedUpload(builder, vast, limit + 1, 100)).reason() == RejectReason.TOO_LARGE,
                "over the clipboard limit");
        check(refusal(() -> clips.beginGeneratedUpload(builder, bounds, source.cells(),
                h.runtime.config().limits.maxUploadBytes + 1)).reason() == RejectReason.TOO_LARGE, "over the upload bytes");
        check(refusal(() -> clips.beginGeneratedUpload(noClipboard, bounds, source.cells(), payload.length)).reason()
                == RejectReason.NO_PERMISSION, "without the clipboard node");
        check(refusal(() -> clips.beginGeneratedUpload(builder, bounds, bounds.volume() + 1, payload.length)).reason()
                == RejectReason.INVALID, "more cells than the box");
        check(!clips.building(builder.getUuid()) && !clips.building(noClipboard.getUuid()), "a refusal kept a slot");

        // The builder's clipboard before the refusals: a small good upload, which every refusal must leave in place.
        GeneratedSource.Builder small = GeneratedSource.builder(100);
        small.set(x0 + 1, 104, z0 + 1, h.state("minecraft:stone_bricks")).set(x0 + 2, 104, z0 + 1, h.state("minecraft:cobblestone"));
        GeneratedSource previous = small.build();
        byte[] previousPayload = SparseUpload.encode(previous, h.runtime.states());
        Captured<ClipboardService.ClipboardInfo> kept = new Captured<>();
        // A block-entity state: refused once decoded, and the previous clipboard stays.
        GeneratedSource.Builder chest = GeneratedSource.builder(100);
        chest.set(x0 + 5, 104, z0 + 5, h.state("minecraft:stone_bricks")).set(x0 + 6, 104, z0 + 5, h.state("minecraft:chest"));
        GeneratedSource tiles = chest.build();
        byte[] tilePayload = SparseUpload.encode(tiles, h.runtime.states());
        Captured<ClipboardService.ClipboardInfo> tileReply = new Captured<>();
        // Over the section cap (lowered for this test): refused once decoded, before anything is held.
        int sectionCap = h.runtime.config().limits.maxSelectionSections;
        Captured<ClipboardService.ClipboardInfo> spreadReply = new Captured<>();
        try {
            clips.beginGeneratedUpload(builder, previous.bounds(), previous.cells(), previousPayload.length)
                    .completed(previousPayload, kept);
        } catch (EditRejected e) {
            throw new GameTestException("unexpected refusal: " + e.getMessage());
        }

        // Through the network: garbage of the announced size, then a stream aborted mid-way.
        ServerDispatcher<ServerPlayerEntity> dispatcher = new ServerDispatcher<>(h.service, clips, ScatterService.disabled(), h.runtime.permissions(),
                () -> Limits.DEFAULTS, h.runtime::states, System::nanoTime);
        Transport transport = new Transport(h, builder);
        NetSession<ServerPlayerEntity> session = dispatcher.open(transport);
        transport.receive(dispatcher, session, Handshake.hello("test", Features.of(Features.REGION_OPS, Features.CLIPBOARD)));
        Captured<ClipboardService.ClipboardInfo> good = new Captured<>();
        java.util.UUID[] previousId = new java.util.UUID[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(kept.finished(), "uploading the previous clipboard"))
                .createAndAdd(() -> {
                    previousId[0] = kept.get("the previous clipboard").clipboardId();
                    try {
                        clips.beginGeneratedUpload(builder, tiles.bounds(), tiles.cells(), tilePayload.length)
                                .completed(tilePayload, tileReply);
                    } catch (EditRejected e) {
                        throw new GameTestException("unexpected refusal: " + e.getMessage());
                    }
                })
                .createAndAdd(() -> check(tileReply.finished(), "decoding the block-entity payload"))
                .createAndAdd(() -> {
                    tileReply.failedWith(RejectReason.INVALID, "a block-entity state");
                    check(tileReply.detail.contains("Block entities"), "detail " + tileReply.detail);
                    check(h.service.clipboards().find(builder.getUuid(), previousId[0]).isPresent(),
                            "a refused payload replaced the previous clipboard");
                    check(!clips.building(builder.getUuid()), "the refusal kept the slot");
                    // The source touches several sections; a cap of one refuses it as too large.
                    h.runtime.config().limits.maxSelectionSections = 1;
                    try {
                        clips.beginGeneratedUpload(builder, bounds, source.cells(), payload.length).completed(payload, spreadReply);
                    } catch (EditRejected e) {
                        throw new GameTestException("unexpected refusal: " + e.getMessage());
                    } finally {
                        h.runtime.config().limits.maxSelectionSections = sectionCap;
                    }
                })
                .createAndAdd(() -> check(spreadReply.finished(), "decoding over the section cap"))
                .createAndAdd(() -> {
                    spreadReply.failedWith(RejectReason.TOO_LARGE, "over the section cap");
                    check(h.service.clipboards().find(builder.getUuid(), previousId[0]).isPresent(),
                            "the section-cap refusal replaced the previous clipboard");
                    byte[] garbage = new byte[payload.length];
                    new Random(822).nextBytes(garbage);
                    transport.receive(dispatcher, session, new C2S.GeneratedUpload(3, bounds, source.cells(), garbage.length));
                    S2C.UploadGrant grant = transport.first(S2C.UploadGrant.class);
                    StreamSender sender = new StreamSender(grant.streamId(), StreamKind.GENERATED_UPLOAD, garbage,
                            new TreeMap<>(), StreamSender.MAX_C2S_CHUNK, grant.creditBytes());
                    for (Message m : sender.poll(garbage.length)) transport.receive(dispatcher, session, (C2S) m);
                })
                .createAndAdd(() -> check(!transport.sent(S2C.UploadResult.class).isEmpty(), "decoding the garbage"))
                .createAndAdd(() -> {
                    S2C.UploadResult result = transport.first(S2C.UploadResult.class);
                    check(result.reqId() == 3 && result.error() != null && result.error().startsWith("INVALID"), "garbage " + result);
                    check(transport.sent(S2C.ClipboardReady.class).isEmpty(), "a ClipboardReady for garbage");
                    check(h.service.clipboards().find(builder.getUuid(), previousId[0]).isPresent(),
                            "garbage replaced the previous clipboard");
                    transport.sent.clear();
                    // Half the payload, then the client aborts the stream.
                    transport.receive(dispatcher, session, new C2S.GeneratedUpload(4, bounds, source.cells(), payload.length));
                    S2C.UploadGrant grant = transport.first(S2C.UploadGrant.class);
                    StreamSender sender = new StreamSender(grant.streamId(), StreamKind.GENERATED_UPLOAD, payload,
                            new TreeMap<>(), StreamSender.MAX_C2S_CHUNK, grant.creditBytes());
                    for (Message m : sender.poll(Math.max(1, payload.length / 2))) {
                        if (m instanceof dev.sculptory.protocol.v2.StreamEnd) break;
                        transport.receive(dispatcher, session, (C2S) m);
                    }
                    transport.receive(dispatcher, session, new StreamAbort(grant.streamId(), "cancelled"));
                    S2C.UploadResult aborted = transport.first(S2C.UploadResult.class);
                    check(aborted.reqId() == 4 && aborted.error() != null, "aborted " + aborted);
                    check(h.service.clipboards().find(builder.getUuid(), previousId[0]).isPresent(),
                            "half a payload replaced the previous clipboard");
                    check(!clips.building(builder.getUuid()), "the aborted upload kept the slot");
                    // The slot is free: a well-formed upload goes through.
                    try {
                        clips.beginGeneratedUpload(builder, bounds, source.cells(), payload.length).completed(payload, good);
                    } catch (EditRejected e) {
                        throw new GameTestException("the good upload was refused: " + e.getMessage());
                    }
                })
                .createAndAdd(() -> check(good.finished(), "decoding the good payload"))
                .createAndAdd(() -> {
                    ClipboardService.ClipboardInfo info = good.get("the good upload");
                    check(info.cells() == source.cells(), "info " + info);
                    check(h.service.clipboards().find(builder.getUuid(), info.clipboardId()).isPresent(),
                            "the good upload did not replace the clipboard");
                    dispatcher.close(session);
                    forceChunks(world, area, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * A paste of a generated clipboard skips the cells the player may not modify (the east chunk, protected by the
     * test hook as a claim mod would) and writes the rest; the skipped cells are counted, and undo restores the rest.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_generate_protected", tickLimit = LIMIT)
    public void protectedChunkCellsAreSkipped(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        ServerPlayerEntity builder = h.addPlayer(false);
        EditTestSupport.grant(builder, Perm.USE, Perm.CLIPBOARD, Perm.REGION);
        int[] at = regionCorner(context, 824);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 98, z0, x0 + 31, 110, z0 + 31);
        loadAndForce(world, area);
        ground(h, area);
        GeneratedSource source = source(h, x0, z0);
        Box bounds = source.bounds();
        WorldSnapshot before = capture(world, area);
        byte[] payload = SparseUpload.encode(source, h.runtime.states());
        ServerClipboards clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        Captured<ClipboardService.ClipboardInfo> uploaded = new Captured<>();
        try {
            clips.beginGeneratedUpload(builder, bounds, source.cells(), payload.length).completed(payload, uploaded);
        } catch (EditRejected e) {
            throw new GameTestException("upload refused: " + e.getMessage());
        }
        long[] eastCells = {0};
        source.forEach((x, y, z, state) -> {
            if (x >= x0 + 16) eastCells[0]++;
        });
        check(eastCells[0] > 0 && eastCells[0] < source.cells(), "the source spans both chunks");
        ProtectionHook.protect(builder, world, x0 + 16, z0, x0 + 31, z0 + 31);
        RecordingListener pasted = new RecordingListener(), undo = new RecordingListener();
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(uploaded.finished(), "upload running"))
                .createAndAdd(() -> {
                    ClipboardService.ClipboardInfo info = uploaded.get("the upload");
                    check(!world.canPlayerModifyAt(builder, pos(x0 + 20, 103, z0 + 8)), "the east chunk is not protected");
                    check(world.canPlayerModifyAt(builder, pos(x0 + 10, 103, z0 + 8)), "the west chunk is protected");
                    try {
                        h.service.run(builder, paste(info.clipboardId(), bounds), RunOptions.DEFAULT, pasted);
                    } catch (EditRejected e) {
                        ProtectionHook.clear(builder);
                        throw new GameTestException("paste refused: " + e.getMessage());
                    }
                })
                .createAndAdd(() -> check(pasted.result != null, "paste running"))
                .createAndAdd(() -> {
                    ProtectionHook.clear(builder);
                    check(pasted.result.outcome() == JobOutcome.COMPLETED, "paste " + pasted.result);
                    check(pasted.result.skippedProtected() == eastCells[0], "skipped " + pasted.result.skippedProtected()
                            + " of " + eastCells[0] + " protected cells");
                    WorldSnapshot now = capture(world, area);
                    for (int y = area.min().y(); y <= area.max().y(); y++) {
                        for (int z = area.min().z(); z <= area.max().z(); z++) {
                            for (int x = area.min().x(); x <= area.max().x(); x++) {
                                int generated = source.get(x, y, z);
                                int want = generated >= 0 && x < x0 + 16 ? generated : before.get(x, y, z);
                                check(now.get(x, y, z) == want, "cell " + x + "," + y + "," + z + " after the protected paste");
                            }
                        }
                    }
                    try {
                        h.service.undo(builder, ConflictPolicy.SKIP_CONFLICTS, undo);
                    } catch (EditRejected e) {
                        throw new GameTestException("undo refused: " + e.getMessage());
                    }
                })
                .createAndAdd(() -> check(undo.result != null, "undo running"))
                .createAndAdd(() -> {
                    check(undo.result.outcome() == JobOutcome.COMPLETED, "undo " + undo.result);
                    checkSame(before, capture(world, area), "after the undo");
                    forceChunks(world, area, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * A paste is admitted on the clipboard's blocks, not its box: a sparse generated clipboard of a few blocks in a
     * box larger than {@code maxOpVolume} (and {@code maxClipboardVolume}) uploads and pastes for a player without
     * {@code limit.bypass}, while a fill of that box is refused.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_generate_sparse", tickLimit = LIMIT)
    public void aSparseClipboardInAVastBoxPastesOnItsBlocks(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        ServerPlayerEntity builder = h.addPlayer(false);
        EditTestSupport.grant(builder, Perm.USE, Perm.CLIPBOARD, Perm.REGION);
        int[] at = regionCorner(context, 826);
        int x0 = at[0], z0 = at[1];
        // 75 × 380 × 75 = 2,137,500 cells: over the default limits of 2,097,152.
        Box vast = box(x0, -60, z0, x0 + 74, 319, z0 + 74);
        long limit = h.runtime.config().limits.maxOpVolume;
        check(vast.volume() > limit && vast.volume() > h.runtime.config().limits.maxClipboardVolume, "the box is over the limits");
        Box area = box(x0, -64, z0, x0 + 74, 319, z0 + 74);
        loadAndForce(world, area);
        int bricks = h.state("minecraft:stone_bricks");
        GeneratedSource.Builder builderCells = GeneratedSource.builder(100);
        builderCells.set(x0, -60, z0, bricks).set(x0 + 74, 319, z0 + 74, bricks).set(x0 + 74, -60, z0, bricks)
                .set(x0, 319, z0 + 74, bricks);
        for (int i = 0; i < 20; i++) builderCells.set(x0 + 10 + i, 100 + i, z0 + 30, bricks);
        GeneratedSource source = builderCells.build();
        check(source.bounds().equals(vast), "bounds " + source.bounds());
        WorldSnapshot before = capture(world, box(x0, 90, z0, x0 + 74, 130, z0 + 74));
        byte[] payload = SparseUpload.encode(source, h.runtime.states());
        ServerClipboards clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        check(refusal(() -> h.service.run(builder, new OpSpec.Fill(vast, new dev.sculptory.core.edit.Pattern.Single(bricks),
                dev.sculptory.core.edit.CellMask.ANY), RunOptions.DEFAULT, null)).reason() == RejectReason.TOO_LARGE,
                "a fill of the box is over the limit");
        Captured<ClipboardService.ClipboardInfo> uploaded = new Captured<>();
        try {
            clips.beginGeneratedUpload(builder, vast, source.cells(), payload.length).completed(payload, uploaded);
        } catch (EditRejected e) {
            throw new GameTestException("upload refused: " + e.getMessage());
        }
        RecordingListener pasted = new RecordingListener();
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(uploaded.finished(), "upload running"))
                .createAndAdd(() -> {
                    ClipboardService.ClipboardInfo info = uploaded.get("the upload");
                    check(info.cells() == 24, "info " + info);
                    try {
                        h.service.run(builder, paste(info.clipboardId(), vast), RunOptions.DEFAULT, pasted);
                    } catch (EditRejected e) {
                        throw new GameTestException("the sparse paste was refused: " + e.getMessage());
                    }
                })
                .createAndAdd(() -> check(pasted.result != null, "paste running"))
                .createAndAdd(() -> {
                    check(pasted.result.outcome() == JobOutcome.COMPLETED && pasted.result.changed() == 24, "paste " + pasted.result);
                    check(world.getBlockState(pos(x0 + 74, 319, z0 + 74)).isOf(net.minecraft.block.Blocks.STONE_BRICKS),
                            "the far corner was not written");
                    WorldSnapshot now = capture(world, box(x0, 90, z0, x0 + 74, 130, z0 + 74));
                    for (int y = 90; y <= 130; y++) {
                        for (int z = z0; z <= z0 + 74; z++) {
                            for (int x = x0; x <= x0 + 74; x++) {
                                int generated = source.get(x, y, z);
                                check(now.get(x, y, z) == (generated >= 0 ? generated : before.get(x, y, z)),
                                        "cell " + x + "," + y + "," + z);
                            }
                        }
                    }
                    forceChunks(world, area, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * A schematic stores every cell of its box, so saving or exporting takes time and memory in proportion to the box: a
     * generated clipboard of two blocks in a 2,048 × 2 × 2,048 box (8,388,608 cells, over {@code maxClipboardVolume})
     * is refused {@code TOO_LARGE} for a player without {@code limit.bypass}, before anything is written, as a copy of
     * that box would be; no file appears. An op with {@code limit.bypass} is refused too over
     * {@link ServerClipboards#MAX_STORED_BOX} (a 4,096 × 64 × 4,096 box). A generated clipboard whose box fits the limit
     * saves as usual. (Without the
     * check, two blocks up to 65,535 apart made the server write up to two billion cells, as the permission review of the
     * Generate tool found.) Region slot 972 (no blocks are placed).
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_generate_save_box", tickLimit = LIMIT)
    public void aSparseClipboardWiderThanTheLimitIsNeitherSavedNorExported(TestContext context) {
        Harness h = new Harness(context);
        ServerPlayerEntity builder = h.addPlayer(false);
        EditTestSupport.grant(builder, Perm.USE, Perm.CLIPBOARD, Perm.REGION, Perm.SCHEMATIC_EXPORT);
        int[] at = regionCorner(context, 972);
        int x0 = at[0], z0 = at[1];
        int bricks = h.state("minecraft:stone_bricks");
        GeneratedSource wide = GeneratedSource.builder(10).set(x0, 104, z0, bricks).set(x0 + 2047, 105, z0 + 2047, bricks)
                .build();
        check(wide.bounds().volume() > h.runtime.config().limits.maxClipboardVolume, "the box is over the limit");
        GeneratedSource fits = GeneratedSource.builder(10).set(x0, 104, z0, bricks).set(x0 + 99, 105, z0 + 99, bricks)
                .build();
        Path root = ClipTestSupport.libraryRoot(context);
        ServerClipboards clips = ClipTestSupport.clipboards(h, root);
        Captured<ClipboardService.ClipboardInfo> wideUpload = new Captured<>();
        Captured<ClipboardService.ClipboardInfo> fitsUpload = new Captured<>();
        // Even with limit.bypass (the op), no save or export writes a box over MAX_STORED_BOX.
        GeneratedSource vast = GeneratedSource.builder(10).set(x0, 60, z0, bricks).set(x0 + 4095, 123, z0 + 4095, bricks)
                .build();
        check(vast.bounds().volume() > ServerClipboards.MAX_STORED_BOX, "the box is over the absolute cap");
        Captured<ClipboardService.ClipboardInfo> vastUpload = new Captured<>();
        Captured<ClipboardService.Saved> saved = new Captured<>();
        context.createTimedTaskRunner()
                .createAndAdd(() -> upload(clips, h.player, vast, h, vastUpload))
                .createAndAdd(() -> check(vastUpload.finished(), "uploading the vast clipboard"))
                .createAndAdd(() -> {
                    java.util.UUID id = vastUpload.get("the op's vast upload").clipboardId();
                    EditRejected save = refusal(() -> clips.save(h.player, id, "vast.schem", new Captured<>()));
                    check(save.reason() == RejectReason.TOO_LARGE && save.getMessage().contains("the most any save"),
                            "saving with limit.bypass: " + save.reason() + " " + save.getMessage());
                    EditRejected export = refusal(() -> clips.export(h.player, id, new Captured<>()));
                    check(export.reason() == RejectReason.TOO_LARGE, "exporting with limit.bypass: " + export.reason());
                    upload(clips, builder, wide, h, wideUpload);
                })
                .createAndAdd(() -> check(wideUpload.finished(), "uploading the wide clipboard"))
                .createAndAdd(() -> {
                    java.util.UUID id = wideUpload.get("the wide upload").clipboardId();
                    Captured<ClipboardService.Saved> refusedSave = new Captured<>();
                    EditRejected save = refusal(() -> clips.save(builder, id, "wide.schem", refusedSave));
                    check(save.reason() == RejectReason.TOO_LARGE, "saving: " + save.reason() + " " + save.getMessage());
                    EditRejected export = refusal(() -> clips.export(builder, id, new Captured<>()));
                    check(export.reason() == RejectReason.TOO_LARGE, "exporting: " + export.reason() + " " + export.getMessage());
                    check(!refusedSave.finished(), "a refused save answered as well");
                    check(!clips.building(builder.getUuid()), "a refusal kept a slot");
                    upload(clips, builder, fits, h, fitsUpload);
                })
                .createAndAdd(() -> check(fitsUpload.finished(), "uploading the clipboard that fits"))
                .createAndAdd(() -> {
                    java.util.UUID id = fitsUpload.get("the upload that fits").clipboardId();
                    try {
                        clips.save(builder, id, "fits.schem", saved);
                    } catch (EditRejected e) {
                        throw new GameTestException("saving a clipboard whose box fits was refused: " + e.getMessage());
                    }
                })
                .createAndAdd(() -> check(saved.finished(), "saving"))
                .createAndAdd(() -> {
                    ClipboardService.Saved done = saved.get("the save");
                    Path folder = root.resolve("_players").resolve(builder.getUuid().toString());
                    check(done.path().endsWith("fits.schem") && Files.isRegularFile(folder.resolve("fits.schem")),
                            "saved " + done);
                    check(!Files.exists(folder.resolve("wide.schem")), "the refused save wrote a file");
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * A generated clipboard may hold any plain block state, which is what a Fill places, so the upload needs
     * {@code region} as well as {@code clipboard}. A clipboard-only player is refused
     * {@code NO_PERMISSION} naming the node, directly and through the network (a {@code JobRejected}, no grant, nothing
     * streamed), and nothing is held: no clipboard slot, no clipboard. A player who loses {@code region} after the grant
     * is refused when the payload arrives, the slot released and no clipboard made. Nothing is written to the world. The
     * same payload from a player holding both nodes goes through. Region slot 974.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_generate_needs_region", tickLimit = LIMIT)
    public void aClipboardOnlyPlayerIsRefusedAndNothingIsHeld(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        ServerPlayerEntity clipboardOnly = h.addPlayer(false);
        EditTestSupport.grant(clipboardOnly, Perm.USE, Perm.CLIPBOARD);
        ServerPlayerEntity losing = h.addPlayer(false);
        EditTestSupport.grant(losing, Perm.USE, Perm.CLIPBOARD, Perm.REGION);
        int[] at = regionCorner(context, 974);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 98, z0, x0 + 31, 110, z0 + 31);
        loadAndForce(world, area);
        ground(h, area);
        GeneratedSource source = GeneratedSource.builder(100).set(x0 + 2, 104, z0 + 2, h.state("minecraft:barrier"))
                .set(x0 + 3, 104, z0 + 2, h.state("minecraft:bedrock")).build();
        byte[] payload = SparseUpload.encode(source, h.runtime.states());
        WorldSnapshot before = capture(world, area);
        ServerClipboards clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));

        EditRejected refused = refusal(() -> clips.beginGeneratedUpload(clipboardOnly, source.bounds(), source.cells(),
                payload.length));
        check(refused.reason() == RejectReason.NO_PERMISSION && refused.getMessage().contains(Perm.REGION.node()),
                "refused " + refused.reason() + " " + refused.getMessage());
        check(!clips.building(clipboardOnly.getUuid()), "the refusal kept a slot");
        check(h.service.clipboards().get(clipboardOnly.getUuid()).isEmpty(), "a clipboard was made");

        // Through the network: refused before the grant, so nothing is streamed.
        ServerDispatcher<ServerPlayerEntity> dispatcher = new ServerDispatcher<>(h.service, clips, ScatterService.disabled(), h.runtime.permissions(),
                () -> Limits.DEFAULTS, h.runtime::states, System::nanoTime);
        Transport transport = new Transport(h, clipboardOnly);
        NetSession<ServerPlayerEntity> session = dispatcher.open(transport);
        transport.receive(dispatcher, session, Handshake.hello("test", Features.of(Features.REGION_OPS, Features.CLIPBOARD)));
        transport.receive(dispatcher, session, new C2S.GeneratedUpload(5, source.bounds(), source.cells(), payload.length));
        check(transport.sent(S2C.UploadGrant.class).isEmpty(), "a clipboard-only player was granted the upload");
        check(transport.sent(S2C.JobRejected.class).stream().anyMatch(r -> r.reqId() == 5
                && r.reason() == RejectReason.NO_PERMISSION), "no NO_PERMISSION refusal: " + transport.sent);
        check(!clips.building(clipboardOnly.getUuid()) && h.service.clipboards().get(clipboardOnly.getUuid()).isEmpty(),
                "the network refusal held something");
        dispatcher.close(session);

        // region lost between the grant and the payload's arrival.
        ClipboardService.Upload upload;
        try {
            upload = clips.beginGeneratedUpload(losing, source.bounds(), source.cells(), payload.length);
        } catch (EditRejected e) {
            throw new GameTestException("a player with both nodes was refused: " + e.getMessage());
        }
        check(clips.building(losing.getUuid()), "the grant holds the clipboard slot");
        EditTestSupport.deny(losing, Perm.REGION);
        Captured<ClipboardService.ClipboardInfo> lost = new Captured<>();
        upload.completed(payload, lost);
        Captured<ClipboardService.ClipboardInfo> good = new Captured<>();
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(lost.finished(), "checking the arrived payload"))
                .createAndAdd(() -> {
                    lost.failedWith(RejectReason.NO_PERMISSION, "region lost after the grant");
                    check(!clips.building(losing.getUuid()), "the refusal kept the slot");
                    check(h.service.clipboards().get(losing.getUuid()).isEmpty(), "a clipboard was made without region");
                    checkSame(before, capture(world, area), "after the refusals");
                    // With both nodes again the same payload becomes the clipboard.
                    EditTestSupport.deny(losing);
                    try {
                        clips.beginGeneratedUpload(losing, source.bounds(), source.cells(), payload.length)
                                .completed(payload, good);
                    } catch (EditRejected e) {
                        throw new GameTestException("refused with both nodes: " + e.getMessage());
                    }
                })
                .createAndAdd(() -> check(good.finished(), "decoding with both nodes"))
                .createAndAdd(() -> {
                    check(good.get("the upload with both nodes").cells() == source.cells(), "cells");
                    checkSame(before, capture(world, area), "an upload writes nothing by itself");
                    forceChunks(world, area, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    private static void upload(ServerClipboards clips, ServerPlayerEntity player, GeneratedSource source, Harness h,
                               Captured<ClipboardService.ClipboardInfo> reply) {
        byte[] payload = SparseUpload.encode(source, h.runtime.states());
        try {
            clips.beginGeneratedUpload(player, source.bounds(), source.cells(), payload.length).completed(payload, reply);
        } catch (EditRejected e) {
            throw new GameTestException("upload refused: " + e.getMessage());
        }
    }

    private static EditRejected refusal(ClipboardGameTest.ThrowingRun run) {
        return ClipboardGameTest.refusal(run);
    }

    /** A transport collecting what the dispatcher sends, decoded, for one player. */
    private static final class Transport implements FabricTransport {
        final Harness h;
        final ServerPlayerEntity player;
        final List<S2C> sent = new ArrayList<>();

        Transport(Harness h, ServerPlayerEntity player) {
            this.h = h;
            this.player = player;
        }

        void receive(ServerDispatcher<ServerPlayerEntity> dispatcher, NetSession<ServerPlayerEntity> session, C2S message) {
            try {
                dispatcher.receive(session, Codec.encodeC2S(message, h.runtime.states()));
            } catch (ProtocolException e) {
                throw new GameTestException("encode: " + e.getMessage());
            }
        }

        <T> T first(Class<T> type) {
            List<T> all = sent(type);
            if (all.isEmpty()) throw new GameTestException("no " + type.getSimpleName() + " sent: " + sent);
            return all.get(0);
        }

        <T> List<T> sent(Class<T> type) {
            return sent.stream().filter(type::isInstance).map(type::cast).toList();
        }

        @Override
        public ServerPlayerEntity player() {
            return player;
        }

        @Override
        public boolean canSend() {
            return true;
        }

        @Override
        public void send(byte[] frame) {
            try {
                sent.add(Codec.decodeS2C(frame, h.runtime.states()));
            } catch (ProtocolException e) {
                throw new GameTestException("the server sent an undecodable frame: " + e.getMessage());
            }
        }

        @Override
        public void acknowledge(int sequence) {
        }

        @Override
        public boolean tracks(int cx, int cz) {
            return false;
        }

        @Override
        public void resendChunk(int cx, int cz) {
        }

        @Override
        public void disconnect(String reason) {
            throw new GameTestException("disconnected: " + reason);
        }
    }
}
