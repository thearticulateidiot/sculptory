package dev.sculptory.fabric.client.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.palette.BlockPalette;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.fabric.engine.Perm;
import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.protocol.v2.Codec;
import dev.sculptory.protocol.v2.Features;
import dev.sculptory.protocol.v2.Limits;
import dev.sculptory.protocol.v2.ProtocolException;
import dev.sculptory.protocol.v2.ProtocolV2;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.protocol.v2.S2C;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Palettes through {@link FabricEditorSession} against a scripted server: save, load, listings and their checks. */
class FabricEditorSessionPaletteTest {
    private static final FakeStateSpace STATES = new FakeStateSpace();
    private static final long MS = 1_000_000L;
    private static final UUID ME = new UUID(1, 2);
    private static final String OWN = "_players/" + ME;
    private static final BlockPalette MOSS = new BlockPalette(List.of(new BlockPalette.Entry("minecraft:stone", 4),
            new BlockPalette.Entry("minecraft:sea_pickle[pickles=2,waterlogged=true]", 1)));

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

    private <T extends C2S> List<T> allSent(Class<T> type) {
        return transport.sent.stream().filter(type::isInstance).map(type::cast).toList();
    }

    private static <T> Reply<T> now(CompletionStage<Reply<T>> stage) {
        CompletableFuture<Reply<T>> future = stage.toCompletableFuture();
        assertTrue(future.isDone(), "the reply has arrived");
        return future.join();
    }

    @Test
    void aSaveIsSentAndItsAnswerIsTheChangeTheServerMade() {
        connect();
        LibraryChanges log = session.libraryChanges();
        long start = log.version();
        CompletionStage<Reply<LibraryChange>> save = session.savePalette("biomes/moss.palette.json", MOSS);
        C2S.PaletteSave sent = allSent(C2S.PaletteSave.class).get(0);
        assertEquals(new C2S.PaletteSave(sent.reqId(), "biomes/moss.palette.json", MOSS), sent);
        server(new S2C.LibraryChanged(sent.reqId(), false, "", "biomes/moss.palette.json"));
        assertEquals(new LibraryChange(false, "", "biomes/moss.palette.json"), now(save).toOptional().orElseThrow());

        // Without library.write the server saves into the player's own folder, and says so.
        CompletionStage<Reply<LibraryChange>> own = session.savePalette("biomes/moss.palette.json", MOSS);
        int reqId = allSent(C2S.PaletteSave.class).get(1).reqId();
        server(new S2C.LibraryChanged(reqId, false, "", OWN + "/biomes/moss.palette.json"));
        assertEquals(OWN + "/biomes/moss.palette.json", now(own).toOptional().orElseThrow().to());

        assertEquals(List.of(new LibraryChange(false, "", "biomes/moss.palette.json"),
                new LibraryChange(false, "", OWN + "/biomes/moss.palette.json")), log.since(start).orElseThrow(),
                "an open Library window lists the folder again");
        assertTrue(notices.isEmpty(), "successes are quiet: " + notices);
    }

    @Test
    void aSaveAnswerThatIsNotThePaletteAskedForFailsTheRequest() {
        connect();
        long start = session.libraryChanges().version();
        for (S2C.LibraryChanged wrong : List.of(
                new S2C.LibraryChanged(0, false, "", "biomes/other.palette.json"),
                new S2C.LibraryChanged(0, false, "biomes/moss.palette.json", "biomes/x.palette.json"),
                new S2C.LibraryChanged(0, true, "", "biomes/moss.palette.json"),
                new S2C.LibraryChanged(0, false, "", "biomes/moss.schem"),
                new S2C.LibraryChanged(0, false, "", "_players/x/biomes/moss.palette.json"),
                new S2C.LibraryChanged(0, false, "", OWN + "/other/biomes/moss.palette.json"),
                new S2C.LibraryChanged(0, false, "", "_players/" + new UUID(3, 4) + "/biomes/moss.palette.json"))) {
            CompletionStage<Reply<LibraryChange>> save = session.savePalette("biomes/moss.palette.json", MOSS);
            List<C2S.PaletteSave> saves = allSent(C2S.PaletteSave.class);
            int reqId = saves.get(saves.size() - 1).reqId();
            server(new S2C.LibraryChanged(reqId, wrong.folder(), wrong.from(), wrong.to()));
            assertEquals(Reply.Failure.CORRUPT, ((Reply.Failed<LibraryChange>) now(save)).failure(), wrong.toString());
        }
        assertEquals(start, session.libraryChanges().version(), "nothing false is logged");
        // A save already in a player folder stays there.
        assertTrue(ClipboardTransfers.savedAt(OWN + "/a.palette.json", OWN + "/a.palette.json", ME));
        assertFalse(ClipboardTransfers.savedAt(OWN + "/a.palette.json",
                "_players/" + new UUID(3, 4) + "/" + OWN + "/a.palette.json", ME));
        // Only this player's own folder; without knowing the player, only the path asked for.
        assertTrue(ClipboardTransfers.savedAt("a.palette.json", OWN + "/a.palette.json", ME));
        assertFalse(ClipboardTransfers.savedAt("a.palette.json", "_players/" + new UUID(3, 4) + "/a.palette.json", ME));
        assertFalse(ClipboardTransfers.savedAt("a.palette.json", OWN + "/a.palette.json", null));
        assertTrue(ClipboardTransfers.savedAt("a.palette.json", "a.palette.json", null));
    }

    @Test
    void aLoadCompletesWithThePaletteAndWhatTheServerLeftOut() {
        connect();
        CompletionStage<Reply<LoadedPalette>> load = session.loadPalette("biomes/moss.palette.json");
        C2S.PaletteLoad sent = allSent(C2S.PaletteLoad.class).get(0);
        assertEquals("biomes/moss.palette.json", sent.path());
        server(new S2C.PaletteData(sent.reqId(), "biomes/moss.palette.json", MOSS, 2, List.of("modded:gone")));
        assertEquals(new LoadedPalette("biomes/moss.palette.json", MOSS, 2, List.of("modded:gone")),
                now(load).toOptional().orElseThrow());

        // Another palette than asked for, or one nobody asked for, is not taken.
        CompletionStage<Reply<LoadedPalette>> other = session.loadPalette("a.palette.json");
        int reqId = allSent(C2S.PaletteLoad.class).get(1).reqId();
        server(new S2C.PaletteData(reqId + 100, "a.palette.json", MOSS));
        assertFalse(other.toCompletableFuture().isDone());
        server(new S2C.PaletteData(reqId, "b.palette.json", MOSS));
        assertEquals(Reply.Failure.CORRUPT, ((Reply.Failed<LoadedPalette>) now(other)).failure());
    }

    @Test
    void refusalsCompleteTheRequestAndAreToastedWithTheServersDetail() {
        connect();
        CompletionStage<Reply<LoadedPalette>> load = session.loadPalette("gone.palette.json");
        int reqId = allSent(C2S.PaletteLoad.class).get(0).reqId();
        server(new S2C.JobRejected(reqId, RejectReason.INVALID));
        server(new S2C.Notice(S2C.Notice.Level.WARN, "sculptory.notice.request_refused",
                List.of("INVALID", "palette gone.palette.json: none of its 2 blocks exist on this server")));
        assertEquals(RejectReason.INVALID, assertInstanceOf(Reply.Refused.class, now(load)).reason());
        assertEquals(1, notices.size());
        assertEquals("sculptory.reject.invalid.detail", notices.get(0).key());

        long start = session.libraryChanges().version();
        CompletionStage<Reply<LibraryChange>> save = session.savePalette("moss.palette.json", MOSS);
        server(new S2C.JobRejected(allSent(C2S.PaletteSave.class).get(0).reqId(), RejectReason.NO_PERMISSION));
        assertInstanceOf(Reply.Refused.class, now(save));
        assertEquals(start, session.libraryChanges().version());
    }

    @Test
    void aSaveIsALibraryWriteAndWaitsForTheOthers() {
        connect();
        CompletionStage<Reply<LibraryChange>> rename = session.libraryMove("a/x.schem", "a/y.schem", false);
        CompletionStage<Reply<LibraryChange>> save = session.savePalette("a/p.palette.json", MOSS);
        CompletionStage<Reply<LoadedPalette>> load = session.loadPalette("a/q.palette.json");
        assertEquals(List.of(), allSent(C2S.PaletteSave.class), "the save waits for the rename");
        assertEquals(1, allSent(C2S.PaletteLoad.class).size(), "a load is no write");
        server(new S2C.LibraryChanged(allSent(C2S.LibraryMove.class).get(0).reqId(), false, "a/x.schem", "a/y.schem"));
        assertTrue(now(rename).isOk());
        assertEquals(1, allSent(C2S.PaletteSave.class).size());
        assertFalse(save.toCompletableFuture().isDone());
        assertFalse(load.toCompletableFuture().isDone());
    }

    @Test
    void withoutTheLibraryFeatureOrConnectionNothingIsSentAndUnansweredOnesTimeOut() {
        connect(Features.of(Features.CLIPBOARD));
        assertEquals(RejectReason.DISABLED,
                ((Reply.Refused<LibraryChange>) now(session.savePalette("a.palette.json", MOSS))).reason());
        assertEquals(RejectReason.DISABLED, ((Reply.Refused<LoadedPalette>) now(session.loadPalette("a.palette.json")))
                .reason());
        assertEquals(List.of(), transport.sent);

        connect();
        CompletionStage<Reply<LoadedPalette>> load = session.loadPalette("a.palette.json");
        clock.addAndGet(31_000 * MS);
        session.tick();
        assertEquals(Reply.Failure.TIMED_OUT, ((Reply.Failed<LoadedPalette>) now(load)).failure());

        session.onDisconnect();
        assertEquals(Reply.Failure.DISCONNECTED,
                ((Reply.Failed<LibraryChange>) now(session.savePalette("a.palette.json", MOSS))).failure());
    }

    @Test
    void listingsKeepPalettesOnlyAsPalettesAndChangesToThemAreFollowed() {
        connect();
        CompletionStage<Reply<LibraryFolder>> list = session.libraryList("biomes");
        int reqId = allSent(C2S.LibraryList.class).get(0).reqId();
        S2C.LibraryListing.Entry palette = new S2C.LibraryListing.Entry("biomes/moss.palette.json", false, 120, "",
                S2C.LibraryListing.Entry.Kind.PALETTE);
        S2C.LibraryListing.Entry schem = new S2C.LibraryListing.Entry("biomes/rock.schem", false, 3, "ab".repeat(32));
        server(new S2C.LibraryListing(reqId, "biomes", List.of(
                palette,
                schem,
                // A palette with a content hash could be taken for an asset: dropped.
                new S2C.LibraryListing.Entry("biomes/hashed.palette.json", false, 1, "ab".repeat(32),
                        S2C.LibraryListing.Entry.Kind.PALETTE),
                // Kinds that don't match the extension: dropped.
                new S2C.LibraryListing.Entry("biomes/fake.schem", false, 1, "", S2C.LibraryListing.Entry.Kind.PALETTE),
                new S2C.LibraryListing.Entry("biomes/fake.palette.json", false, 1, "ab".repeat(32))), true));
        assertEquals(List.of(palette, schem), now(list).toOptional().orElseThrow().entries());

        long start = session.libraryChanges().version();
        server(new S2C.LibraryChanged(S2C.LibraryChanged.PUSH, false, "", "biomes/new.palette.json"));
        server(new S2C.LibraryChanged(S2C.LibraryChanged.PUSH, false, "biomes/moss.palette.json", "biomes/m.palette.json"));
        server(new S2C.LibraryChanged(S2C.LibraryChanged.PUSH, false, "biomes/bad.palette", ""));
        assertEquals(List.of(new LibraryChange(false, "", "biomes/new.palette.json"),
                new LibraryChange(false, "biomes/moss.palette.json", "biomes/m.palette.json")),
                session.libraryChanges().since(start).orElseThrow());
    }

    private static final class FakeTransport implements FabricEditorSession.Transport {
        final List<C2S> sent = new ArrayList<>();

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

        @Override
        public UUID player() {
            return ME;
        }
    }
}
