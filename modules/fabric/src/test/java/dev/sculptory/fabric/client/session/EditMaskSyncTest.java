package dev.sculptory.fabric.client.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.Sha256;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.mask.BlockSet;
import dev.sculptory.core.mask.EditMask;
import dev.sculptory.core.mask.MaskEntry;
import dev.sculptory.core.mask.MaskRule;
import dev.sculptory.core.region.CellSet;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.fabric.client.editor.mask.EditMaskModel;
import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.protocol.v2.Codec;
import dev.sculptory.protocol.v2.Features;
import dev.sculptory.protocol.v2.Limits;
import dev.sculptory.protocol.v2.ProtocolException;
import dev.sculptory.protocol.v2.ProtocolV2;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.protocol.v2.S2C;
import dev.sculptory.server.engine.Perm;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The global mask's copy on the server: sent after the Welcome and each change, before
 * any edit that follows a change, never to a server without {@code edit_mask}; refusals fail closed on the client too.
 */
class EditMaskSyncTest {
    private static final FakeStateSpace STATES = new FakeStateSpace();
    private static final long MS = 1_000_000L;
    private static final Box BOX = Box.of(new BlockPos(0, 60, 0), new BlockPos(9, 70, 9));
    private static final EditMask STONE = new EditMask(List.of(MaskEntry.of(new MaskRule.Is(BlockSet.parse("minecraft:stone")))),
            false);

    private final List<C2S> sent = new ArrayList<>();
    private final List<Notice> notices = new ArrayList<>();
    private final AtomicLong clock = new AtomicLong(10_000 * MS);
    private EditMaskModel model;
    private FabricEditorSession session;

    @BeforeEach
    void setUp() {
        model = new EditMaskModel();
        session = new FabricEditorSession(new FabricEditorSession.Transport() {
            @Override
            public boolean canSend() {
                return true;
            }

            @Override
            public void send(byte[] frame) {
                try {
                    sent.add(Codec.decodeC2S(frame, STATES));
                } catch (ProtocolException e) {
                    throw new AssertionError(e);
                }
            }
        }, () -> STATES, clock::get, "0.2.0-test");
        session.onNotice(notices::add);
        session.followMask(model);
    }

    private void connect(Features features) {
        session.onJoin();
        server(new S2C.Welcome(ProtocolV2.VERSION, features, Limits.DEFAULTS, Perm.mask(EnumSet.of(Perm.USE, Perm.REGION)),
                1L));
    }

    private void server(S2C message) {
        try {
            session.onFrame(Codec.encodeS2C(message, STATES));
        } catch (ProtocolException e) {
            throw new AssertionError(e);
        }
    }

    private ToolResult fill() {
        OpSpec op = new OpSpec.Fill(BOX, new Pattern.Single(STATES.state("minecraft:dirt")), CellMask.ANY);
        return session.send(new ToolAction.RunOp(op)).toCompletableFuture().getNow(null);
    }

    private List<C2S.SetEditMask> masksSent() {
        return sent.stream().filter(C2S.SetEditMask.class::isInstance).map(C2S.SetEditMask.class::cast).toList();
    }

    private void tick(long millis) {
        clock.addAndGet(millis * MS);
        session.tick();
    }

    @Test
    void aMaskOnAtTheWelcomeGoesOutAndOneOffDoesNot() {
        model.setRules(STONE.entries());
        connect(Features.of(Features.REGION_OPS, Features.EDIT_MASK));
        assertTrue(masksSent().isEmpty(), "off: the server starts with no mask, nothing to send");
        model.setOn(true);
        assertEquals(List.of(STONE), masksSent().stream().map(C2S.SetEditMask::mask).toList());
        session.onDisconnect();
        sent.clear();
        connect(Features.of(Features.REGION_OPS, Features.EDIT_MASK));
        assertEquals(List.of(STONE), masksSent().stream().map(C2S.SetEditMask::mask).toList(), "sent again after a Welcome");
    }

    @Test
    void aChangeNotYetSentGoesOutBeforeTheNextEdit() {
        model.setRules(STONE.entries());
        connect(Features.of(Features.REGION_OPS, Features.EDIT_MASK));
        model.setOn(true);
        sent.clear();
        // A burst: the second change waits out the quiet time...
        EditMask dirt = new EditMask(List.of(MaskEntry.of(new MaskRule.Is(BlockSet.parse("minecraft:dirt")))), false);
        model.setRules(dirt.entries());
        assertTrue(masksSent().isEmpty(), "held for the burst");
        // ...unless an edit comes first: then it goes out right before it.
        fill();
        assertEquals(2, sent.size());
        assertEquals(dirt, assertInstanceOf(C2S.SetEditMask.class, sent.get(0)).mask());
        assertInstanceOf(C2S.RunOp.class, sent.get(1));
        tick(500);
        assertEquals(1, masksSent().size(), "nothing more to send");
        // Off: the server hears it before the next edit too.
        sent.clear();
        model.setOn(false);
        model.setOn(true);
        model.setOn(false);
        fill();
        assertEquals(EditMask.NONE, assertInstanceOf(C2S.SetEditMask.class, sent.get(0)).mask());
        assertInstanceOf(C2S.RunOp.class, sent.get(sent.size() - 1));
    }

    @Test
    void aServerWithoutMasksGetsNoEditsWhileTheMaskIsOn() {
        model.setRules(STONE.entries());
        model.setOn(true);
        connect(Features.of(Features.REGION_OPS));
        sent.clear();
        ToolResult result = fill();
        ToolResult.Rejected rejected = assertInstanceOf(ToolResult.Rejected.class, result);
        assertTrue(rejected.detail().contains("cannot apply the mask"), rejected.detail());
        assertTrue(sent.isEmpty(), "nothing sent: " + sent);
        assertTrue(notices.stream().anyMatch(n -> n.key().equals(EditMaskSync.NOTICE_NO_SUPPORT)), "no notice: " + notices);
        model.setOn(false);
        assertNull(fill() instanceof ToolResult.Rejected r ? r : null, "the mask off: edits go out again");
        assertInstanceOf(C2S.RunOp.class, sent.get(sent.size() - 1));
    }

    @Test
    void aRefusedMaskFailsClosedUntilItChangesAndAPassingRefusalIsRetried() {
        model.setRules(STONE.entries());
        connect(Features.of(Features.REGION_OPS, Features.EDIT_MASK));
        model.setOn(true);
        C2S.SetEditMask first = masksSent().get(0);
        server(S2C.EditMaskState.refused(first.reqId(), RejectReason.INVALID, "bad"));
        sent.clear();
        ToolResult refused = fill();
        assertEquals(RejectReason.INVALID, assertInstanceOf(ToolResult.Rejected.class, refused).reason());
        assertTrue(sent.isEmpty(), "a mask refused for good is not sent again, and neither is the edit");
        // A change is sent, and lifts the client's refusal.
        EditMask dirt = new EditMask(List.of(MaskEntry.of(new MaskRule.Is(BlockSet.parse("minecraft:dirt")))), false);
        tick(500);
        model.setRules(dirt.entries());
        assertEquals(dirt, masksSent().get(0).mask());
        C2S.SetEditMask second = masksSent().get(0);
        // Over the rate limit: sent again a moment later; an answer to an older request is ignored.
        server(S2C.EditMaskState.refused(second.reqId(), RejectReason.RATE_LIMITED, ""));
        server(S2C.EditMaskState.accepted(first.reqId()));
        sent.clear();
        tick(100);
        assertTrue(masksSent().isEmpty(), "too soon");
        tick(500);
        assertEquals(dirt, masksSent().get(0).mask(), "retried");
        server(S2C.EditMaskState.accepted(masksSent().get(0).reqId()));
        sent.clear();
        assertNull(fill() instanceof ToolResult.Rejected r ? r : null);
        assertEquals(1, sent.size(), "accepted: only the edit goes out");
    }

    @Test
    void anInsideRuleNamesTheSelectionOrNothing() {
        Region box = new Region.Cuboid(BOX);
        Optional<Region>[] selection = new Optional[] {Optional.empty()};
        model.setSelectionSource(() -> selection[0]);
        model.setRules(List.of(MaskEntry.of(EditMaskModel.insideSelection())));
        connect(Features.of(Features.REGION_OPS, Features.EDIT_MASK));
        model.setOn(true);
        assertTrue(model.insideWithoutSelection());
        MaskRule none = masksSent().get(0).mask().entries().get(0).rule();
        assertEquals(new MaskRule.Height(Integer.MAX_VALUE, Integer.MAX_VALUE), none, "no selection: matches no block");
        selection[0] = Optional.of(box);
        model.selectionChanged();
        tick(500);
        assertEquals(new MaskRule.Inside(box), masksSent().get(masksSent().size() - 1).mask().entries().get(0).rule());
    }

    // ---------------------------------------------------------------- cell sets (the sync alone)

    /** A link whose uploads the test answers. */
    private final class Link implements EditMaskSync.Link {
        final List<C2S> out = new ArrayList<>();
        final List<CompletableFuture<Reply<Sha256>>> uploads = new ArrayList<>();
        final List<Sha256> forgotten = new ArrayList<>();
        int reqIds = 1;

        @Override
        public long now() {
            return clock.get();
        }

        @Override
        public boolean ready() {
            return true;
        }

        @Override
        public Features features() {
            return Features.of(Features.EDIT_MASK);
        }

        @Override
        public int nextReqId() {
            return reqIds++;
        }

        @Override
        public String send(C2S message) {
            out.add(message);
            return null;
        }

        @Override
        public CompletionStage<Reply<Sha256>> upload(CellSet cells) {
            CompletableFuture<Reply<Sha256>> future = new CompletableFuture<>();
            uploads.add(future);
            return future;
        }

        @Override
        public void forgetUpload(Sha256 hash) {
            forgotten.add(hash);
        }

        @Override
        public void notice(Notice notice) {
            notices.add(notice);
        }
    }

    @Test
    void anInsideRuleOnACellSetUploadsItFirst() {
        CellSet cells = CellSet.of(new Region.Cuboid(Box.of(new BlockPos(1, 2, 3), new BlockPos(2, 2, 3))), 10);
        model.setSelectionSource(() -> Optional.of(new Region.Cells(cells)));
        model.setRules(List.of(MaskEntry.of(EditMaskModel.insideSelection())));
        Link link = new Link();
        EditMaskSync sync = new EditMaskSync(model, link);
        sync.welcome();
        model.setOn(true);
        assertEquals(1, link.uploads.size());
        assertTrue(link.out.isEmpty(), "the mask waits for its set");
        String refused = sync.gate(new C2S.RunOp(9, new OpSpec.Fill(BOX, new Pattern.Single(0), CellMask.ANY), false,
                ConflictPolicy.SKIP_CONFLICTS));
        assertNotNull(refused, "an edit while the set uploads is refused");
        assertTrue(refused.startsWith("QUEUE_FULL"), refused);
        Sha256 hash = Sha256.digest(new byte[] {4});
        link.uploads.get(0).complete(new Reply.Ok<>(hash));
        C2S.SetEditMask mask = assertInstanceOf(C2S.SetEditMask.class, link.out.get(0));
        assertEquals(new MaskRule.Inside(new Region.Uploaded(hash, cells.bounds(), cells.size())),
                mask.mask().entries().get(0).rule());
        assertNull(sync.gate(new C2S.RunOp(10, new OpSpec.Fill(BOX, new Pattern.Single(0), CellMask.ANY), false,
                ConflictPolicy.SKIP_CONFLICTS)));
        // The server dropped the set: it is forgotten, uploaded again, and the mask sent again.
        sync.state(S2C.EditMaskState.refused(mask.reqId(), RejectReason.SELECTION_NOT_LOADED, ""));
        assertEquals(List.of(hash), link.forgotten);
        clock.addAndGet(600 * MS);
        sync.tick(clock.get());
        assertEquals(2, link.uploads.size(), "uploaded again");
    }
}
