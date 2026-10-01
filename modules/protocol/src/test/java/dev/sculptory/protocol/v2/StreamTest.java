package dev.sculptory.protocol.v2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.Sha256;
import dev.sculptory.core.testing.FakeStateSpace;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class StreamTest {
    private static final long MIB = 1L << 20;
    private static final int MIN = StreamAssembler.MIN_CHUNK_BYTES;

    private static byte[] payload(int size, long seed) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        return data;
    }

    private static StreamOpen open(int id, long total) {
        return new StreamOpen(id, StreamKind.SCHEM_FILE, total, new TreeMap<>());
    }

    private static <T> List<T> ofType(List<Message> messages, Class<T> type) {
        List<T> out = new ArrayList<>();
        for (Message m : messages) if (type.isInstance(m)) out.add(type.cast(m));
        return out;
    }

    @Test
    void senderSplitsWithinFrameCapsAndReassemblesExactly() throws ProtocolException {
        byte[] data = payload(1_000_000, 1);
        StreamSender sender = new StreamSender(7, StreamKind.CLIPBOARD_PREVIEW, data, new TreeMap<>(),
                StreamSender.MAX_S2C_CHUNK, StreamAssembler.DEFAULT_WINDOW);
        List<Message> sent = sender.poll(Long.MAX_VALUE);
        assertInstanceOf(StreamOpen.class, sent.get(0));
        assertInstanceOf(StreamEnd.class, sent.get(sent.size() - 1));
        assertTrue(sender.done());
        FakeStateSpace states = new FakeStateSpace();
        StreamAssembler assembler = new StreamAssembler((StreamOpen) sent.get(0), 64 * MIB, StreamAssembler.DEFAULT_WINDOW);
        for (StreamChunk chunk : ofType(sent, StreamChunk.class)) {
            byte[] frame = Codec.encodeS2C(chunk, states);
            assertTrue(frame.length <= ProtocolV2.MAX_S2C_FRAME);
            assembler.accept((StreamChunk) Codec.decodeS2C(frame, states));
        }
        assertArrayEquals(data, assembler.finish(ofType(sent, StreamEnd.class).get(0)));
    }

    @Test
    void chunksMustArriveInSequence() throws ProtocolException {
        byte[] data = payload(10 * MIN, 2);
        StreamSender sender = new StreamSender(3, StreamKind.SCHEM_UPLOAD, data, new TreeMap<>(), MIN, Long.MAX_VALUE);
        List<StreamChunk> chunks = ofType(sender.poll(Long.MAX_VALUE), StreamChunk.class);
        assertEquals(10, chunks.size());

        StreamAssembler outOfOrder = new StreamAssembler(sender.open(), MIB, MIB);
        ProtocolException skipped = assertThrows(ProtocolException.class, () -> outOfOrder.accept(chunks.get(1)));
        assertEquals(ProtocolException.Reason.MALFORMED, skipped.reason());

        StreamAssembler duplicate = new StreamAssembler(sender.open(), MIB, MIB);
        duplicate.accept(chunks.get(0));
        duplicate.accept(chunks.get(1));
        assertThrows(ProtocolException.class, () -> duplicate.accept(chunks.get(1)), "a repeated chunk");
        assertThrows(ProtocolException.class, () -> duplicate.accept(chunks.get(0)), "an old chunk");
    }

    @Test
    void undersizedChunksAreRefusedExceptTheLast() throws ProtocolException {
        StreamAssembler assembler = new StreamAssembler(open(1, MIN + 10), MIB, MIB);
        ProtocolException tiny = assertThrows(ProtocolException.class, () -> assembler.accept(new StreamChunk(1, 0, new byte[10])));
        assertEquals(ProtocolException.Reason.MALFORMED, tiny.reason());

        StreamAssembler ok = new StreamAssembler(open(1, MIN + 10), MIB, MIB);
        ok.accept(new StreamChunk(1, 0, new byte[MIN]));
        ok.accept(new StreamChunk(1, 1, new byte[10]));
        assertEquals(MIN + 10, ok.finish(new StreamEnd(1, Sha256.digest(new byte[MIN + 10]))).length);

        StreamAssembler small = new StreamAssembler(open(1, 5), MIB, MIB);
        small.accept(new StreamChunk(1, 0, new byte[5]));
        assertEquals(5, small.receivedBytes(), "a stream smaller than the minimum is one final chunk");
    }

    @Test
    void shaMismatchAndShortStreamsFail() throws ProtocolException {
        byte[] data = payload(30, 3);
        StreamAssembler wrongHash = new StreamAssembler(open(1, 30), 100, 100);
        wrongHash.accept(new StreamChunk(1, 0, data));
        ProtocolException e = assertThrows(ProtocolException.class,
                () -> wrongHash.finish(new StreamEnd(1, Sha256.digest(new byte[] {1}))));
        assertEquals(ProtocolException.Reason.MALFORMED, e.reason());
        assertFalse(wrongHash.isOpen());

        StreamAssembler short1 = new StreamAssembler(open(1, 3 * MIN), 10 * MIN, 10 * MIN);
        short1.accept(new StreamChunk(1, 0, new byte[MIN]));
        ProtocolException shortEnd = assertThrows(ProtocolException.class,
                () -> short1.finish(new StreamEnd(1, Sha256.digest(new byte[3 * MIN]))));
        assertTrue(shortEnd.getMessage().contains("ended after"));
    }

    @Test
    void sizeCapsAreEnforced() throws ProtocolException {
        ProtocolException open = assertThrows(ProtocolException.class, () -> new StreamAssembler(open(1, 101), 100, 100));
        assertEquals(ProtocolException.Reason.TOO_LARGE, open.reason());

        StreamAssembler assembler = new StreamAssembler(open(1, 2 * MIN), 10 * MIN, 10 * MIN);
        assembler.accept(new StreamChunk(1, 0, new byte[MIN]));
        ProtocolException over = assertThrows(ProtocolException.class,
                () -> assembler.accept(new StreamChunk(1, 1, new byte[MIN + 1])));
        assertEquals(ProtocolException.Reason.TOO_LARGE, over.reason());

        assertThrows(ProtocolException.class, () -> assembler.accept(new StreamChunk(1, 1, new byte[0])), "empty chunk");
        assertThrows(ProtocolException.class, () -> assembler.accept(new StreamChunk(2, 1, new byte[MIN])), "wrong stream");
    }

    @Test
    void theBufferGrowsWithTheDataNotTheDeclaredSize() throws ProtocolException {
        // A stream declaring 64 MiB that sends one chunk holds about one chunk's worth of memory.
        StreamAssembler assembler = new StreamAssembler(open(1, 64 * MIB), 64 * MIB, 4 * MIB);
        assembler.accept(new StreamChunk(1, 0, new byte[MIN]));
        assertEquals(MIN, assembler.receivedBytes());
        assembler.abort();
        assertThrows(ProtocolException.class, () -> assembler.accept(new StreamChunk(1, 1, new byte[MIN])));
    }

    @Test
    void abortStopsTheStream() throws ProtocolException {
        StreamAssembler assembler = new StreamAssembler(open(1, 2 * MIN), 10 * MIN, 10 * MIN);
        assembler.accept(new StreamChunk(1, 0, new byte[MIN]));
        assembler.abort();
        ProtocolException e = assertThrows(ProtocolException.class, () -> assembler.accept(new StreamChunk(1, 1, new byte[MIN])));
        assertEquals(ProtocolException.Reason.UNEXPECTED, e.reason());
        assertNull(assembler.takeCredit());

        StreamSender sender = new StreamSender(4, StreamKind.SCHEM_FILE, new byte[10 * MIN], new TreeMap<>(), MIN, 100 * MIN);
        sender.poll(2 * MIN);
        StreamAbort abort = sender.abort("cancelled");
        assertEquals(new StreamAbort(4, "cancelled"), abort);
        assertTrue(sender.done());
        assertTrue(sender.poll(1000 * MIN).isEmpty());
        assertNull(sender.abort("again"));
    }

    @Test
    void creditWindowBoundsDataInFlight() throws ProtocolException {
        long window = 4 * MIB;
        byte[] data = payload((int) (10 * MIB), 4);
        StreamSender sender = new StreamSender(5, StreamKind.CLIPBOARD_PREVIEW, data, new TreeMap<>(),
                StreamSender.MAX_S2C_CHUNK, window);
        StreamAssembler assembler = new StreamAssembler(sender.open(), 64 * MIB, window);
        StreamEnd end = null;
        long granted = window;
        int stalledTicks = 0;
        int ticks = 0;
        while (end == null) {
            assertTrue(++ticks < 1000, "stream makes progress");
            List<Message> batch = sender.poll(StreamSender.SERVER_BYTES_PER_TICK);
            long batchBytes = 0;
            for (Message m : batch) {
                if (m instanceof StreamChunk chunk) {
                    assembler.accept(chunk);
                    batchBytes += chunk.length();
                } else if (m instanceof StreamEnd e) {
                    end = e;
                }
            }
            assertTrue(batchBytes <= StreamSender.SERVER_BYTES_PER_TICK, "per-tick budget");
            assertTrue(sender.sentBytes() <= granted, "never beyond the credit granted");
            if (end == null && batchBytes == 0) stalledTicks++;
            // The receiver only grants credit every 16 ticks, so the sender really stalls on credit.
            if (ticks % 16 == 0) {
                StreamCredit credit = assembler.takeCredit();
                if (credit != null) {
                    sender.credit(credit);
                    granted += credit.bytes();
                }
            }
        }
        assertArrayEquals(data, assembler.finish(end));
        assertTrue(stalledTicks > 0, "the window was exhausted at least once");
    }

    @Test
    void senderWaitsRatherThanSendingUndersizedChunks() {
        StreamSender sender = new StreamSender(6, StreamKind.SCHEM_FILE, new byte[10 * MIN], new TreeMap<>(), 4 * MIN,
                2 * MIN + 100);
        List<Message> first = sender.poll(Long.MAX_VALUE);
        assertEquals(List.of(2 * MIN + 100), ofType(first, StreamChunk.class).stream().map(StreamChunk::length).toList());
        sender.credit(new StreamCredit(6, 500));
        assertTrue(sender.poll(Long.MAX_VALUE).isEmpty(), "500 bytes of credit is under the minimum chunk");
        sender.credit(new StreamCredit(6, 10 * MIN));
        assertEquals(List.of(4 * MIN), ofType(sender.poll(4 * MIN + 10), StreamChunk.class).stream()
                .map(StreamChunk::length).toList(), "the 10-byte budget remainder is not spent on a tiny chunk");
        List<Message> rest = sender.poll(Long.MAX_VALUE);
        assertInstanceOf(StreamEnd.class, rest.get(rest.size() - 1));
        assertThrows(IllegalArgumentException.class, () -> sender.credit(new StreamCredit(7, 1)));
        assertThrows(IllegalArgumentException.class,
                () -> new StreamSender(1, StreamKind.SCHEM_FILE, new byte[1], new TreeMap<>(), MIN - 1, 10));
    }

    @Test
    void dataBeyondCreditIsAViolation() throws ProtocolException {
        StreamAssembler assembler = new StreamAssembler(open(1, 10 * MIN), 10 * MIN, 2 * MIN);
        assembler.accept(new StreamChunk(1, 0, new byte[2 * MIN]));
        ProtocolException e = assertThrows(ProtocolException.class, () -> assembler.accept(new StreamChunk(1, 1, new byte[MIN])));
        assertEquals(ProtocolException.Reason.TOO_LARGE, e.reason());
        assertTrue(e.getMessage().contains("credit"));
    }

    @Test
    void creditIsGrantedInQuarterWindowStepsAndNeverBeyondTheStream() throws ProtocolException {
        int window = 40 * MIN;
        StreamAssembler assembler = new StreamAssembler(open(1, 100 * MIN), 100 * MIN, window);
        assembler.accept(new StreamChunk(1, 0, new byte[5 * MIN]));
        assertNull(assembler.takeCredit(), "under a quarter window");
        assembler.accept(new StreamChunk(1, 1, new byte[6 * MIN]));
        assertEquals(new StreamCredit(1, 11 * MIN), assembler.takeCredit());
        assertNull(assembler.takeCredit());
        assembler.accept(new StreamChunk(1, 2, new byte[40 * MIN]));
        assertEquals(new StreamCredit(1, 40 * MIN), assembler.takeCredit());
        assembler.accept(new StreamChunk(1, 3, new byte[40 * MIN]));
        assertEquals(new StreamCredit(1, 9 * MIN), assembler.takeCredit(), "a small grant that finishes the stream");
        assembler.accept(new StreamChunk(1, 4, new byte[9 * MIN]));
        assertNull(assembler.takeCredit(), "never beyond the declared size");
    }

    @Test
    void emptyStreamOpensAndEnds() throws ProtocolException {
        StreamSender sender = new StreamSender(8, StreamKind.ASSET_PREVIEW, new byte[0], new TreeMap<>(), MIN, 0);
        List<Message> sent = sender.poll(0);
        assertEquals(2, sent.size());
        StreamAssembler assembler = new StreamAssembler((StreamOpen) sent.get(0), 10, 10);
        assertEquals(0, assembler.finish((StreamEnd) sent.get(1)).length);
    }
}
