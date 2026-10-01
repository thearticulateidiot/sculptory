package dev.sculptory.protocol.v2;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * A bounds-checked reader over one frame. Every length and count is checked against its cap and against the
 * bytes actually left before anything is allocated, so hostile input cannot force a large allocation.
 */
final class WireReader {
    private final byte[] buf;
    private int pos;

    WireReader(byte[] buf) {
        this.buf = buf;
    }

    int remaining() {
        return buf.length - pos;
    }

    static ProtocolException malformed(String message) {
        return new ProtocolException(ProtocolException.Reason.MALFORMED, message);
    }

    static ProtocolException tooLarge(String message) {
        return new ProtocolException(ProtocolException.Reason.TOO_LARGE, message);
    }

    private void need(int n) throws ProtocolException {
        if (n > remaining()) throw malformed("Truncated frame");
    }

    int u8() throws ProtocolException {
        need(1);
        return buf[pos++] & 0xFF;
    }

    boolean bool() throws ProtocolException {
        int b = u8();
        if (b > 1) throw malformed("Invalid boolean " + b);
        return b == 1;
    }

    /** Unsigned LEB128 of at most 5 bytes; the result may be negative when bit 31 is set. */
    int varint() throws ProtocolException {
        int result = 0;
        for (int i = 0; i < 5; i++) {
            int b = u8();
            if (i == 4 && (b & 0xF0) != 0) throw malformed("Varint too long");
            result |= (b & 0x7F) << (7 * i);
            if ((b & 0x80) == 0) return result;
        }
        throw malformed("Varint too long");
    }

    /** Unsigned LEB128 of at most 10 bytes. */
    long varlong() throws ProtocolException {
        long result = 0;
        for (int i = 0; i < 10; i++) {
            int b = u8();
            if (i == 9 && (b & 0xFE) != 0) throw malformed("Varlong too long");
            result |= (long) (b & 0x7F) << (7 * i);
            if ((b & 0x80) == 0) return result;
        }
        throw malformed("Varlong too long");
    }

    int zigzag() throws ProtocolException {
        int raw = varint();
        return (raw >>> 1) ^ -(raw & 1);
    }

    long zigzagLong() throws ProtocolException {
        long raw = varlong();
        return (raw >>> 1) ^ -(raw & 1);
    }

    float f32() throws ProtocolException {
        need(4);
        int bits = (buf[pos] & 0xFF) << 24 | (buf[pos + 1] & 0xFF) << 16 | (buf[pos + 2] & 0xFF) << 8 | (buf[pos + 3] & 0xFF);
        pos += 4;
        return Float.intBitsToFloat(bits);
    }

    long i64() throws ProtocolException {
        need(8);
        long value = 0;
        for (int i = 0; i < 8; i++) value = (value << 8) | (buf[pos++] & 0xFF);
        return value;
    }

    UUID uuid() throws ProtocolException {
        long most = i64();
        return new UUID(most, i64());
    }

    /** Exactly {@code n} raw bytes. */
    byte[] raw(int n) throws ProtocolException {
        need(n);
        byte[] out = new byte[n];
        System.arraycopy(buf, pos, out, 0, n);
        pos += n;
        return out;
    }

    /** A byte length: never negative, never over {@code max}, never beyond the frame. */
    int byteLength(int max, String what) throws ProtocolException {
        int length = varint();
        if (length < 0) throw malformed("Negative length for " + what);
        if (length > max) throw tooLarge(what + " over " + max + " bytes");
        need(length);
        return length;
    }

    /**
     * A list or map size. Every encoded entry takes at least one byte, so a count beyond the bytes left is
     * malformed; this bounds allocation by the frame size even before the cap applies.
     */
    int count(int max, String what) throws ProtocolException {
        int count = varint();
        if (count < 0) throw malformed("Negative count for " + what);
        if (count > max) throw tooLarge(what + " over " + max + " entries");
        if (count > remaining()) throw malformed("Truncated " + what);
        return count;
    }

    byte[] bytes(int max, String what) throws ProtocolException {
        return raw(byteLength(max, what));
    }

    /** Strict UTF-8 (malformed sequences are rejected, not replaced). */
    String string(int maxBytes, String what) throws ProtocolException {
        int length = byteLength(maxBytes, what);
        try {
            String value = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(buf, pos, length))
                    .toString();
            pos += length;
            return value;
        } catch (CharacterCodingException e) {
            throw malformed("Invalid UTF-8 in " + what);
        }
    }

    <E extends Enum<E>> E enumOf(E[] values, String what) throws ProtocolException {
        int ordinal = varint();
        if (ordinal < 0 || ordinal >= values.length) throw malformed("Invalid " + what + " ordinal " + ordinal);
        return values[ordinal];
    }

    void expectEnd() throws ProtocolException {
        if (remaining() != 0) throw malformed(remaining() + " trailing bytes");
    }
}
