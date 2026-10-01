package dev.sculptory.protocol.v2;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/**
 * A growable big-endian byte sink with a hard size cap. Every write checks the cap first, so encoding an
 * oversized message fails with {@link ProtocolException.Reason#TOO_LARGE} without building the whole frame.
 */
final class WireWriter {
    private final int max;
    private byte[] buf;
    private int size;

    WireWriter(int max) {
        if (max < 1) throw new IllegalArgumentException("Writer cap must be positive");
        this.max = max;
        this.buf = new byte[Math.min(256, max)];
    }

    int max() {
        return max;
    }

    int size() {
        return size;
    }

    byte[] toByteArray() {
        return Arrays.copyOf(buf, size);
    }

    private void ensure(int extra) throws ProtocolException {
        long needed = (long) size + extra;
        if (needed > max) {
            throw new ProtocolException(ProtocolException.Reason.TOO_LARGE, "Frame exceeds " + max + " bytes");
        }
        if (needed > buf.length) {
            long grown = Math.max(needed, (long) buf.length * 2);
            buf = Arrays.copyOf(buf, (int) Math.min(max, grown));
        }
    }

    void u8(int value) throws ProtocolException {
        ensure(1);
        buf[size++] = (byte) value;
    }

    void bool(boolean value) throws ProtocolException {
        u8(value ? 1 : 0);
    }

    /** Unsigned LEB128, 1-5 bytes. */
    void varint(int value) throws ProtocolException {
        while ((value & ~0x7F) != 0) {
            u8((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        u8(value);
    }

    /** Unsigned LEB128, 1-10 bytes. */
    void varlong(long value) throws ProtocolException {
        while ((value & ~0x7FL) != 0) {
            u8((int) (value & 0x7F) | 0x80);
            value >>>= 7;
        }
        u8((int) value);
    }

    /** Signed int as a zigzag varint (small magnitudes stay short). */
    void zigzag(int value) throws ProtocolException {
        varint((value << 1) ^ (value >> 31));
    }

    /** Signed long as a zigzag varlong. */
    void zigzagLong(long value) throws ProtocolException {
        varlong((value << 1) ^ (value >> 63));
    }

    void f32(float value) throws ProtocolException {
        int bits = Float.floatToRawIntBits(value);
        ensure(4);
        buf[size++] = (byte) (bits >>> 24);
        buf[size++] = (byte) (bits >>> 16);
        buf[size++] = (byte) (bits >>> 8);
        buf[size++] = (byte) bits;
    }

    void i64(long value) throws ProtocolException {
        ensure(8);
        for (int shift = 56; shift >= 0; shift -= 8) buf[size++] = (byte) (value >>> shift);
    }

    void uuid(UUID id) throws ProtocolException {
        i64(id.getMostSignificantBits());
        i64(id.getLeastSignificantBits());
    }

    /** Raw bytes with no length prefix. */
    void raw(byte[] bytes) throws ProtocolException {
        ensure(bytes.length);
        System.arraycopy(bytes, 0, buf, size, bytes.length);
        size += bytes.length;
    }

    /** Appends everything written to {@code other}. */
    void raw(WireWriter other) throws ProtocolException {
        ensure(other.size);
        System.arraycopy(other.buf, 0, buf, size, other.size);
        size += other.size;
    }

    /** Length-prefixed bytes. */
    void bytes(byte[] bytes, int maxBytes, String what) throws ProtocolException {
        if (bytes.length > maxBytes) {
            throw new ProtocolException(ProtocolException.Reason.TOO_LARGE, what + " over " + maxBytes + " bytes");
        }
        varint(bytes.length);
        raw(bytes);
    }

    /** Length-prefixed UTF-8, at most {@code maxBytes} encoded bytes. */
    void string(String value, int maxBytes, String what) throws ProtocolException {
        bytes(value.getBytes(StandardCharsets.UTF_8), maxBytes, what);
    }

    /** A list or map size, checked against the same cap the decoder applies. */
    void count(int count, int maxCount, String what) throws ProtocolException {
        if (count > maxCount) {
            throw new ProtocolException(ProtocolException.Reason.TOO_LARGE, what + " over " + maxCount + " entries");
        }
        varint(count);
    }

    void enumValue(Enum<?> value) throws ProtocolException {
        varint(value.ordinal());
    }
}
