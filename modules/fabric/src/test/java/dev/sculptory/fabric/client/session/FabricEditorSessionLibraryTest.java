package dev.sculptory.fabric.client.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.protocol.v2.AssetAccess;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** M4 library management through {@link FabricEditorSession} against a scripted server. */
class FabricEditorSessionLibraryTest {
    private static final FakeStateSpace STATES = new FakeStateSpace();
    private static final long MS = 1_000_000L;
    private static final UUID CLIP = new UUID(5, 5);

    private FakeTransport transport;
    private AtomicLong clock;
    private FabricEditorSession session;
    private final List<Notice> notices = new ArrayList<>();

    @BeforeEach
    void setUp() {
        transport = new FakeTransport();
        clock = new AtomicLong(10_000 * MS);
        session = new FabricEditorSession(transport, () -> STATES, clock::get, "0.3.0-test");
        session.onNotice(notices::add);
    }

    private void connect(Features features) {
        session.onJoin();
        server(new S2C.Welcome(ProtocolV2.VERSION, features, Limits.DEFAULTS, Perm.mask(EnumSet.allOf(Perm.class)), 1L));
        assertEquals(SessionState.READY, session.state());
        transport.sent.clear();
    }

    private void connect() {
        connect(Features.of(Features.CLIPBOARD, Features.LIBRARY));
    }

    private void server(S2C message) {
        try {
            session.onFrame(Codec.encodeS2C(message, STATES));
        } catch (ProtocolException e) {
            throw new AssertionError(e);
        }
    }

    private void tick(long millis) {
        clock.addAndGet(millis * MS);
        session.tick();
    }

    private <T extends C2S> List<T> allSent(Class<T> type) {
        return transport.sent.stream().filter(type::isInstance).map(type::cast).toList();
    }

    private static <T> Reply<T> now(CompletionStage<Reply<T>> stage) {
        CompletableFuture<Reply<T>> future = stage.toCompletableFuture();
        assertTrue(future.isDone(), "the reply has arrived");
        return future.join();
    }

    private static boolean done(CompletionStage<?> stage) {
        return stage.toCompletableFuture().isDone();
    }

    @Test
    void eachChangeIsSentAndItsAnswerCompletesItAndIsLogged() {
        connect();
        LibraryChanges log = session.libraryChanges();
        long start = log.version();

        CompletionStage<Reply<LibraryChange>> rename = session.libraryMove("trees/oak.schem", "trees/elm.schem", false);
        C2S.LibraryMove move = allSent(C2S.LibraryMove.class).get(0);
        assertEquals(new C2S.LibraryMove(move.reqId(), false, "trees/oak.schem", "trees/elm.schem"), move);
        assertTrue(move.reqId() != S2C.LibraryChanged.PUSH, "requests never use the push id");
        server(new S2C.LibraryChanged(move.reqId(), false, "trees/oak.schem", "trees/elm.schem"));
        assertEquals(new LibraryChange(false, "trees/oak.schem", "trees/elm.schem"), now(rename).toOptional().orElseThrow());

        CompletionStage<Reply<LibraryChange>> delete = session.libraryDelete("trees/elm.schem", false);
        C2S.LibraryDelete deleteRequest = allSent(C2S.LibraryDelete.class).get(0);
        assertEquals(new C2S.LibraryDelete(deleteRequest.reqId(), false, "trees/elm.schem"), deleteRequest);
        server(new S2C.LibraryChanged(deleteRequest.reqId(), false, "trees/elm.schem", ""));
        assertTrue(now(delete).isOk());

        CompletionStage<Reply<LibraryChange>> create = session.libraryCreateFolder("rocks");
        C2S.LibraryCreateFolder createRequest = allSent(C2S.LibraryCreateFolder.class).get(0);
        server(new S2C.LibraryChanged(createRequest.reqId(), true, "", "rocks"));
        assertTrue(now(create).isOk());

        assertEquals(List.of(new LibraryChange(false, "trees/oak.schem", "trees/elm.schem"),
                new LibraryChange(false, "trees/elm.schem", ""), new LibraryChange(true, "", "rocks")),
                log.since(start).orElseThrow());
        assertTrue(notices.isEmpty(), "successes are quiet: " + notices);
    }

    @Test
    void refusalsCompleteTheRequestAndAreToastedWithTheServersDetail() {
        connect();
        long start = session.libraryChanges().version();
        CompletionStage<Reply<LibraryChange>> create = session.libraryCreateFolder("trees");
        int reqId = allSent(C2S.LibraryCreateFolder.class).get(0).reqId();
        server(new S2C.JobRejected(reqId, RejectReason.NO_PERMISSION));
        server(new S2C.Notice(S2C.Notice.Level.WARN, "sculptory.notice.request_refused",
                List.of("NO_PERMISSION", "changing the shared library needs sculptory.library.write (trees)")));
        Reply<LibraryChange> reply = now(create);
        assertInstanceOf(Reply.Refused.class, reply);
        assertEquals(RejectReason.NO_PERMISSION, ((Reply.Refused<LibraryChange>) reply).reason());
        assertEquals(1, notices.size());
        assertEquals("sculptory.reject.no_permission.detail", notices.get(0).key());
        assertEquals(List.of("changing the shared library needs sculptory.library.write (trees)"), notices.get(0).args());
        assertEquals(start, session.libraryChanges().version(), "a refusal changes nothing");
    }

    @Test
    void pushesFromTheServerAreLoggedAndInvalidOrUnaskedOnesDropped() {
        connect();
        LibraryChanges log = session.libraryChanges();
        long start = log.version();
        server(new S2C.LibraryChanged(S2C.LibraryChanged.PUSH, false, "trees/oak.schem", ""));
        server(new S2C.LibraryChanged(S2C.LibraryChanged.PUSH, true, "trees", "forest"));
        // Not valid library paths: dropped.
        server(new S2C.LibraryChanged(S2C.LibraryChanged.PUSH, false, "../evil.schem", ""));
        server(new S2C.LibraryChanged(S2C.LibraryChanged.PUSH, false, "trees", "")); // a file path without .schem
        server(new S2C.LibraryChanged(S2C.LibraryChanged.PUSH, true, ".trash", ""));
        // An answer nobody asked for, and one to a request of another kind.
        server(new S2C.LibraryChanged(77, false, "a.schem", "b.schem"));
        session.libraryList("");
        int listId = allSent(C2S.LibraryList.class).get(0).reqId();
        server(new S2C.LibraryChanged(listId, false, "a.schem", "b.schem"));
        assertEquals(List.of(new LibraryChange(false, "trees/oak.schem", ""), new LibraryChange(true, "trees", "forest")),
                log.since(start).orElseThrow());
    }

    @Test
    void anAnswerThatIsNotTheChangeAskedForFailsTheRequest() {
        connect();
        long start = session.libraryChanges().version();
        CompletionStage<Reply<LibraryChange>> rename = session.libraryMove("a/x.schem", "a/y.schem", false);
        int reqId = allSent(C2S.LibraryMove.class).get(0).reqId();
        server(new S2C.LibraryChanged(reqId, false, "a/x.schem", "somewhere/else.schem"));
        assertEquals(Reply.Failure.CORRUPT, ((Reply.Failed<LibraryChange>) now(rename)).failure());
        assertEquals(start, session.libraryChanges().version());
    }

    @Test
    void libraryWritesGoOutOneAtATime() {
        connect();
        CompletionStage<Reply<SavedAsset>> save = session.saveAsset(CLIP, "a/one.schem");
        CompletionStage<Reply<LibraryChange>> rename = session.libraryMove("a/x.schem", "a/y.schem", false);
        CompletionStage<Reply<LibraryFolder>> list = session.libraryList("a");
        assertEquals(1, allSent(C2S.SaveAsset.class).size());
        assertEquals(List.of(), allSent(C2S.LibraryMove.class), "the rename waits for the save");
        assertEquals(1, allSent(C2S.LibraryList.class).size(), "a listing does not wait behind a write");
        server(new S2C.AssetSaved(allSent(C2S.SaveAsset.class).get(0).reqId(), "a/one.schem", "ab".repeat(32)));
        assertTrue(now(save).isOk());
        assertEquals(1, allSent(C2S.LibraryMove.class).size(), "sent once the save was answered");
        assertFalse(done(rename));
        assertFalse(done(list));
    }

    @Test
    void withoutTheLibraryFeatureOrConnectionNothingIsSent() {
        connect(Features.of(Features.CLIPBOARD));
        Reply<LibraryChange> refused = now(session.libraryCreateFolder("trees"));
        assertEquals(RejectReason.DISABLED, ((Reply.Refused<LibraryChange>) refused).reason());
        assertEquals(List.of(), transport.sent);

        session.onDisconnect();
        Reply<LibraryChange> offline = now(session.libraryDelete("trees", true));
        assertEquals(Reply.Failure.DISCONNECTED, ((Reply.Failed<LibraryChange>) offline).failure());
    }

    @Test
    void anUnansweredChangeTimesOut() {
        connect();
        CompletionStage<Reply<LibraryChange>> delete = session.libraryDelete("a/x.schem", false);
        tick(31_000);
        assertEquals(Reply.Failure.TIMED_OUT, ((Reply.Failed<LibraryChange>) now(delete)).failure());
    }

    @Test
    void theLogKeepsTheLastChangesAndTellsAWindowFarBehindToListAgain() {
        LibraryChanges log = new LibraryChanges();
        assertEquals(List.of(), log.since(0).orElseThrow());
        for (int i = 0; i < LibraryChanges.MAX_KEPT + 5; i++) log.add(new LibraryChange(false, "a" + i + ".schem", ""));
        assertEquals(LibraryChanges.MAX_KEPT + 5, log.version());
        assertTrue(log.since(0).isEmpty(), "too far behind: list again");
        assertEquals(List.of(new LibraryChange(false, "a" + (LibraryChanges.MAX_KEPT + 4) + ".schem", "")),
                log.since(log.version() - 1).orElseThrow());
        LibraryChanges.NONE.add(new LibraryChange(false, "x.schem", ""));
        assertEquals(0, LibraryChanges.NONE.version(), "the shared empty log stays empty");
    }

    // ---------------------------------------------------------------- per-asset access

    private static final UUID ME = new UUID(1, 2);
    private static final UUID OTHER = new UUID(3, 4);
    private static final AssetAccess BOB_ONLY = AssetAccess.listed(List.of(new AssetAccess.Grantee(new UUID(5, 6), "Bob")));

    @Test
    void anAccessIsAskedAndAnsweredForItsPathOnly() {
        connect();
        CompletionStage<Reply<AssetAccess>> asked = session.libraryAccess("trees/oak.schem");
        C2S.LibraryAccessGet get = allSent(C2S.LibraryAccessGet.class).get(0);
        assertEquals("trees/oak.schem", get.path());
        server(new S2C.LibraryAccess(get.reqId() + 1000, "trees/oak.schem", BOB_ONLY)); // nobody asked: ignored
        assertFalse(asked.toCompletableFuture().isDone());
        server(new S2C.LibraryAccess(get.reqId(), "trees/oak.schem", BOB_ONLY));
        assertEquals(Reply.ok(BOB_ONLY), now(asked));

        CompletionStage<Reply<AssetAccess>> other = session.libraryAccess("trees/birch.schem");
        int reqId = allSent(C2S.LibraryAccessGet.class).get(1).reqId();
        server(new S2C.LibraryAccess(reqId, "trees/oak.schem", BOB_ONLY));
        assertEquals(Reply.Failure.CORRUPT, ((Reply.Failed<AssetAccess>) now(other)).failure(), "another entry's access");
    }

    @Test
    void settingAccessIsALibraryWriteAnsweredAsTheEntryChangedInPlace() {
        connect();
        long start = session.libraryChanges().version();
        AssetAccess typed = AssetAccess.listed(List.of(new AssetAccess.Grantee(null, "Carol")));
        CompletionStage<Reply<LibraryChange>> change = session.setLibraryAccess("trees/oak.schem", typed);
        C2S.LibraryAccessSet set = allSent(C2S.LibraryAccessSet.class).get(0);
        assertEquals(typed, set.access(), "names without a UUID go to the server as typed");
        CompletionStage<Reply<LibraryChange>> rename = session.libraryMove("trees/oak.schem", "trees/elm.schem", false);
        assertEquals(List.of(), allSent(C2S.LibraryMove.class), "one library write at a time: the rename waits");
        server(new S2C.LibraryChanged(set.reqId(), false, "trees/oak.schem", "trees/oak.schem"));
        assertEquals(Reply.ok(new LibraryChange(false, "trees/oak.schem", "trees/oak.schem")), now(change));
        assertEquals(List.of(new LibraryChange(false, "trees/oak.schem", "trees/oak.schem")),
                session.libraryChanges().since(start).orElseThrow(), "logged, so an open window lists again");
        assertEquals(1, allSent(C2S.LibraryMove.class).size(), "sent once the access change was answered");
        assertFalse(rename.toCompletableFuture().isDone());

        CompletionStage<Reply<LibraryChange>> again = session.setLibraryAccess("trees/elm.schem", AssetAccess.EVERYONE);
        server(new S2C.LibraryChanged(allSent(C2S.LibraryMove.class).get(0).reqId(), false, "trees/oak.schem", "trees/elm.schem"));
        int reqId = allSent(C2S.LibraryAccessSet.class).get(1).reqId();
        server(new S2C.LibraryChanged(reqId, false, "trees/elm.schem", ""));
        assertEquals(Reply.Failure.CORRUPT, ((Reply.Failed<LibraryChange>) now(again)).failure(),
                "an answer that is not the change asked for");
        // A push that an entry vanished for this player (access revoked) is logged like a deletion.
        long before = session.libraryChanges().version();
        server(new S2C.LibraryChanged(S2C.LibraryChanged.PUSH, false, "trees/elm.schem", ""));
        assertEquals(List.of(new LibraryChange(false, "trees/elm.schem", "")), session.libraryChanges().since(before).orElseThrow());
    }

    @Test
    void theSharedFolderListsOtherPlayersEntriesWithTheirRealPathsAndTheRootShowsIt() {
        connect();
        CompletionStage<Reply<LibraryFolder>> root = session.libraryList("");
        int rootId = allSent(C2S.LibraryList.class).get(0).reqId();
        server(new S2C.LibraryListing(rootId, "", List.of(
                new S2C.LibraryListing.Entry("trees", true, 0, ""),
                new S2C.LibraryListing.Entry("_shared", true, 0, ""),
                new S2C.LibraryListing.Entry("trees/oak.schem", false, 10, "ab".repeat(32),
                        S2C.LibraryListing.Entry.Kind.SCHEMATIC, true)), true));
        assertEquals(List.of("trees", "_shared"), ((Reply.Ok<LibraryFolder>) now(root)).value().entries().stream()
                .map(S2C.LibraryListing.Entry::path).toList(), "the virtual folder is kept at the root");

        CompletionStage<Reply<LibraryFolder>> shared = session.libraryList("_shared");
        int sharedId = allSent(C2S.LibraryList.class).get(1).reqId();
        S2C.LibraryListing.Entry hut = new S2C.LibraryListing.Entry("_players/" + OTHER + "/huts/hut.schem", false, 10,
                "cd".repeat(32), S2C.LibraryListing.Entry.Kind.SCHEMATIC, true);
        S2C.LibraryListing.Entry moss = new S2C.LibraryListing.Entry("_players/" + OTHER + "/moss.palette.json", false, 10,
                "", S2C.LibraryListing.Entry.Kind.PALETTE, true);
        server(new S2C.LibraryListing(sharedId, "_shared", List.of(
                hut, moss,
                new S2C.LibraryListing.Entry("_players/" + ME + "/mine.schem", false, 10, "ef".repeat(32)), // one's own
                new S2C.LibraryListing.Entry("trees/oak.schem", false, 10, "ab".repeat(32)), // the shared area
                new S2C.LibraryListing.Entry("_players/" + OTHER + "/huts", true, 0, ""), // a folder
                new S2C.LibraryListing.Entry("_shared", true, 0, "")), false));
        LibraryFolder folder = ((Reply.Ok<LibraryFolder>) now(shared)).value();
        assertEquals(List.of(hut, moss), folder.entries(), "files in other players' folders only");
        assertFalse(folder.writable());
        assertTrue(folder.entries().get(0).restricted());

        CompletionStage<Reply<LibraryFolder>> trees = session.libraryList("trees");
        int treesId = allSent(C2S.LibraryList.class).get(2).reqId();
        server(new S2C.LibraryListing(treesId, "trees", List.of(
                new S2C.LibraryListing.Entry("_shared", true, 0, ""), hut,
                new S2C.LibraryListing.Entry("trees/oak.schem", false, 10, "ab".repeat(32),
                        S2C.LibraryListing.Entry.Kind.SCHEMATIC, true)), true));
        assertEquals(List.of("trees/oak.schem"), ((Reply.Ok<LibraryFolder>) now(trees)).value().entries().stream()
                .map(S2C.LibraryListing.Entry::path).toList(), "elsewhere the virtual folder and foreign paths are dropped");
    }

    private static final class FakeTransport implements FabricEditorSession.Transport {
        final List<C2S> sent = new ArrayList<>();

        @Override
        public UUID player() {
            return ME;
        }

        @Override
        public boolean canSend() {
            return true;
        }

        @Override
        public void send(byte[] frame) {
            try {
                sent.add(Codec.decodeC2S(frame, STATES));
            } catch (ProtocolException e) {
                throw new AssertionError("The client sent an undecodable frame", e);
            }
        }
    }
}
