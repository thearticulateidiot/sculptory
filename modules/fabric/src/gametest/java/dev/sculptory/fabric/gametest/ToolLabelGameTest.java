package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.EditTestSupport.loadAndForce;
import static dev.sculptory.fabric.gametest.EngineTestSupport.box;
import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.forceChunks;
import static dev.sculptory.fabric.gametest.EngineTestSupport.regionCorner;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.fabric.engine.impl.ServerClipboards;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.net.FabricTransport;
import dev.sculptory.fabric.world.BlockWriter;
import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.protocol.v2.Codec;
import dev.sculptory.protocol.v2.Features;
import dev.sculptory.protocol.v2.Handshake;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.Limits;
import dev.sculptory.protocol.v2.OpLabel;
import dev.sculptory.protocol.v2.ProtocolException;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.protocol.v2.S2C;
import dev.sculptory.server.engine.ScatterService;
import dev.sculptory.server.net.NetSession;
import dev.sculptory.server.net.ServerDispatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;

/**
 * History names per tool: a {@code RunOp} carrying a tool label makes a job and
 * a history entry named after the tool ("Flood · 16 blocks"), one that carries none keeps the op's name ("Fill · …"),
 * and a label that does not fit the op is refused as {@code INVALID} with nothing run. Through the network layer, on a
 * real world. Region slot 1080.
 */
public final class ToolLabelGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;

    /** One op after another, each named after its tool; a misfit label is refused; no label keeps the op's name. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_tool_labels", tickLimit = LIMIT)
    public void stepsAreNamedAfterTheirTools(TestContext context) {
        Harness h = new Harness(context);
        int[] at = regionCorner(context, 1080);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 98, z0, x0 + 15, 112, z0 + 15);
        loadAndForce(h.world, area);
        BlockWriter writer = h.runtime.writer(h.world, BlockWriter.Options.DEFAULT);
        int stone = h.state("minecraft:stone"), water = h.state("minecraft:water[level=0]"), air = h.state("minecraft:air");
        for (int x = x0; x <= x0 + 15; x++) {
            for (int z = z0; z <= z0 + 15; z++) {
                for (int y = 100; y <= 103; y++) writer.write(x, y, z, stone, null);
            }
        }
        // A pool to drain: 4 by 4 of water on the stone.
        for (int x = x0; x < x0 + 4; x++) {
            for (int z = z0; z < z0 + 4; z++) writer.write(x, 104, z, water, null);
        }
        ServerClipboards clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        ServerDispatcher<ServerPlayerEntity> dispatcher = new ServerDispatcher<>(h.service, clips, ScatterService.disabled(), h.runtime.permissions(),
                () -> Limits.DEFAULTS, h.runtime::states, System::nanoTime);
        Transport transport = new Transport(h, h.player);
        NetSession<ServerPlayerEntity> session = dispatcher.open(transport);
        transport.receive(dispatcher, session, Handshake.hello("test", Features.of(Features.REGION_OPS, Features.CLIPBOARD)));

        Region flood = new Region.Cuboid(box(x0 + 8, 104, z0, x0 + 11, 104, z0 + 3));       // air over the stone
        Region drain = new Region.Cuboid(box(x0, 104, z0, x0 + 3, 104, z0 + 3));            // the pool
        Region extrude = new Region.Cuboid(box(x0 + 12, 103, z0, x0 + 13, 103, z0 + 1));    // the stone's top layer
        Region carve = new Region.Cuboid(box(x0, 103, z0 + 8, x0 + 1, 103, z0 + 9));
        Region smear = new Region.Cuboid(box(x0 + 4, 103, z0 + 8, x0 + 5, 103, z0 + 9));
        Box copied = box(x0 + 8, 103, z0 + 8, x0 + 9, 103, z0 + 9);
        List<String> expected = new ArrayList<>();
        UUID[] clipboard = new UUID[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> run(transport, dispatcher, session, 1,
                        new OpSpec.Fill(flood, new Pattern.Waterlog(water), CellMask.ANY), OpLabel.FLOOD))
                .createAndAdd(() -> {}) // one tick for the job to finish
                .createAndAdd(() -> {
                    finished(transport, 1, "the flood");
                    expected.add(0, "Flood · 16 blocks");
                    checkLabels(h, expected);
                    run(transport, dispatcher, session, 2, new OpSpec.Fill(drain, new Pattern.Dry(), CellMask.ANY), OpLabel.DRAIN);
                })
                .createAndAdd(() -> {}) // one tick for the job to finish
                .createAndAdd(() -> {
                    finished(transport, 2, "the drain");
                    expected.add(0, "Drain · 16 blocks");
                    checkLabels(h, expected);
                    run(transport, dispatcher, session, 3, new OpSpec.Stack(extrude, 0, 1, 0, 1, EntityFilter.NONE), OpLabel.EXTRUDE);
                })
                .createAndAdd(() -> {}) // one tick for the job to finish
                .createAndAdd(() -> {
                    finished(transport, 3, "the extrude");
                    expected.add(0, "Extrude · 4 blocks");
                    checkLabels(h, expected);
                    run(transport, dispatcher, session, 4, new OpSpec.Erase(carve, CellMask.ANY), OpLabel.CARVE);
                })
                .createAndAdd(() -> {}) // one tick for the job to finish
                .createAndAdd(() -> {
                    finished(transport, 4, "the carve");
                    expected.add(0, "Carve · 4 blocks");
                    checkLabels(h, expected);
                    run(transport, dispatcher, session, 5, new OpSpec.Move(smear, new BlockPos(0, 1, 0), Transform.IDENTITY,
                            new Pattern.Single(air), EntityFilter.NONE), OpLabel.SMEAR);
                })
                .createAndAdd(() -> {}) // one tick for the job to finish
                .createAndAdd(() -> {
                    finished(transport, 5, "the smear");
                    expected.add(0, "Smear · 8 blocks");
                    checkLabels(h, expected);
                    transport.receive(dispatcher, session, new C2S.Copy(6, new Region.Cuboid(copied), copied.min(), false,
                            CellMask.ANY, EntityFilter.NONE));
                })
                .createAndAdd(() -> {}) // one tick for the job to finish
                .createAndAdd(() -> {
                    S2C.ClipboardReady ready = transport.first(S2C.ClipboardReady.class);
                    check(ready.reqId() == 6 && ready.cells() == 4, "copy " + ready);
                    clipboard[0] = ready.clipboardId();
                    run(transport, dispatcher, session, 7, paste(clipboard[0], new BlockPos(x0 + 8, 105, z0 + 8)), OpLabel.ROAD);
                })
                .createAndAdd(() -> {}) // one tick for the job to finish
                .createAndAdd(() -> {
                    finished(transport, 7, "the road");
                    expected.add(0, "Road · 4 blocks");
                    checkLabels(h, expected);
                    run(transport, dispatcher, session, 8, paste(clipboard[0], new BlockPos(x0 + 8, 107, z0 + 8)), OpLabel.ROOF);
                })
                .createAndAdd(() -> {}) // one tick for the job to finish
                .createAndAdd(() -> {
                    finished(transport, 8, "the roof");
                    expected.add(0, "Roof · 4 blocks");
                    checkLabels(h, expected);
                    // A label that does not fit its op is refused before anything runs.
                    transport.receive(dispatcher, session, new C2S.RunOp(9, new OpSpec.Erase(carve, CellMask.ANY), false,
                            ConflictPolicy.SKIP_CONFLICTS, OpLabel.FLOOD));
                    S2C.JobRejected rejected = transport.sent(S2C.JobRejected.class).stream().filter(r -> r.reqId() == 9)
                            .findFirst().orElseThrow(() -> new GameTestException("not refused: " + transport.sent));
                    check(rejected.reason() == RejectReason.INVALID, "refused as " + rejected.reason());
                    check(transport.sent(S2C.JobAccepted.class).stream().noneMatch(a -> a.reqId() == 9), "nothing ran");
                    checkLabels(h, expected);
                    // No label: the op's own name.
                    run(transport, dispatcher, session, 10, new OpSpec.Fill(flood, new Pattern.Single(stone), CellMask.ANY),
                            OpLabel.NONE);
                })
                .createAndAdd(() -> {}) // one tick for the job to finish
                .createAndAdd(() -> {
                    finished(transport, 10, "the fill");
                    expected.add(0, "Fill · 16 blocks");
                    checkLabels(h, expected);
                    dispatcher.close(session);
                    forceChunks(h.world, area, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    private static OpSpec.Paste paste(UUID clipboardId, BlockPos origin) {
        return new OpSpec.Paste(new SourceRef.Clipboard(clipboardId), origin, Transform.IDENTITY, new PasteOptions(true, false, false));
    }

    private static void run(Transport transport, ServerDispatcher<ServerPlayerEntity> dispatcher, NetSession<ServerPlayerEntity> session, int reqId, OpSpec op,
                            OpLabel label) {
        transport.receive(dispatcher, session, new C2S.RunOp(reqId, op, false, ConflictPolicy.SKIP_CONFLICTS, label));
        check(transport.sent(S2C.JobAccepted.class).stream().anyMatch(a -> a.reqId() == reqId),
                "request " + reqId + " was not accepted: " + transport.sent);
    }

    /** The job of {@code reqId} finished completely. */
    private static void finished(Transport transport, int reqId, String what) {
        UUID job = transport.sent(S2C.JobAccepted.class).stream().filter(a -> a.reqId() == reqId).findFirst()
                .orElseThrow(() -> new GameTestException(what + " was not accepted")).jobId();
        S2C.JobFinished done = transport.sent(S2C.JobFinished.class).stream().filter(f -> f.jobId().equals(job)).findFirst()
                .orElseThrow(() -> new GameTestException(what + " has not finished: " + transport.sent));
        check(done.outcome() == JobOutcome.COMPLETED && done.changed() > 0, what + " " + done);
    }

    /** The undo labels, newest first, are exactly {@code expected}. */
    private static void checkLabels(Harness h, List<String> expected) {
        List<String> labels = h.history().undoLabels();
        check(labels.equals(expected), "history " + labels + ", expected " + expected);
    }

    /** A transport collecting what the dispatcher sends, decoded (as GenerateGameTest's). */
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
