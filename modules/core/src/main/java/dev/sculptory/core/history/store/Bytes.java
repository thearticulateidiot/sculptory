package dev.sculptory.core.history.store;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/** Big-endian byte building and bounds-checked reading for the history journal. */
final class Bytes {
    private Bytes() {}

    /** A growable output buffer. */
    static final class Writer {
        private byte[] data;
        private int size;

        Writer(int capacity) {
            data = new byte[Math.max(16, capacity)];
        }

        int size() {
            return size;
        }

        byte[] toArray() {
            return Arrays.copyOf(data, size);
        }

        /** The backing array; bytes past {@link #size()} are unspecified. */
        byte[] array() {
            return data;
        }

        private void ensure(int more) {
            if (size + more <= data.length) return;
            long wanted = Math.max((long) data.length * 2, (long) size + more);
            if (wanted > Integer.MAX_VALUE - 16) throw new IllegalStateException("Record too large");
            data = Arrays.copyOf(data, (int) wanted);
        }

        Writer u8(int v) {
            ensure(1);
            data[size++] = (byte) v;
            return this;
        }

        Writer u16(int v) {
            ensure(2);
            data[size++] = (byte) (v >>> 8);
            data[size++] = (byte) v;
            return this;
        }

        Writer i32(int v) {
            ensure(4);
            data[size++] = (byte) (v >>> 24);
            data[size++] = (byte) (v >>> 16);
            data[size++] = (byte) (v >>> 8);
            data[size++] = (byte) v;
            return this;
        }

        Writer i64(long v) {
            i32((int) (v >>> 32));
            return i32((int) v);
        }

        Writer uuid(UUID id) {
            i64(id.getMostSignificantBits());
            return i64(id.getLeastSignificantBits());
        }

        /** Unsigned LEB128. */
        Writer varint(int v) {
            if (v < 0) throw new IllegalArgumentException("Negative varint " + v);
            while ((v & ~0x7F) != 0) {
                u8((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            return u8(v);
        }

        Writer bytes(byte[] b) {
            return bytes(b, 0, b.length);
        }

        Writer bytes(byte[] b, int offset, int length) {
            ensure(length);
            System.arraycopy(b, offset, data, size, length);
            size += length;
            return this;
        }

        /** Varint length, then UTF-8. */
        Writer utf(String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            varint(b.length);
            return bytes(b);
        }
    }

    /** Reads a byte range; every read past the end, or of an impossible value, throws {@link CorruptDataException}. */
    static final class Reader {
        private final byte[] data;
        private int pos;
        private final int end;

        Reader(byte[] data) {
            this(data, 0, data.length);
        }

        Reader(byte[] data, int offset, int length) {
            this.data = data;
            this.pos = offset;
            this.end = offset + length;
        }

        int remaining() {
            return end - pos;
        }

        int position() {
            return pos;
        }

        private void need(int n) throws CorruptDataException {
            if (n < 0 || n > end - pos) throw new CorruptDataException("Record ends early");
        }

        int u8() throws CorruptDataException {
            need(1);
            return data[pos++] & 0xFF;
        }

        int u16() throws CorruptDataException {
            need(2);
            int v = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
            pos += 2;
            return v;
        }

        int i32() throws CorruptDataException {
            need(4);
            int v = ((data[pos] & 0xFF) << 24) | ((data[pos + 1] & 0xFF) << 16) | ((data[pos + 2] & 0xFF) << 8)
                    | (data[pos + 3] & 0xFF);
            pos += 4;
            return v;
        }

        long i64() throws CorruptDataException {
            long high = i32() & 0xFFFFFFFFL;
            return (high << 32) | (i32() & 0xFFFFFFFFL);
        }

        UUID uuid() throws CorruptDataException {
            long most = i64();
            return new UUID(most, i64());
        }

        int varint() throws CorruptDataException {
            int value = 0;
            for (int shift = 0; shift < 35; shift += 7) {
                int b = u8();
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    if (value < 0) throw new CorruptDataException("Varint out of range");
                    return value;
                }
            }
            throw new CorruptDataException("Varint too long");
        }

        /** A varint no larger than {@code max}. */
        int varint(int max, String what) throws CorruptDataException {
            int v = varint();
            if (v > max) throw new CorruptDataException(what + " " + v + " > " + max);
            return v;
        }

        byte[] bytes(int n) throws CorruptDataException {
            need(n);
            byte[] b = Arrays.copyOfRange(data, pos, pos + n);
            pos += n;
            return b;
        }

        /** Varint length (at most {@code maxBytes}), then strict UTF-8. */
        String utf(int maxBytes) throws CorruptDataException {
            int n = varint(maxBytes, "String length");
            need(n);
            try {
                String s = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(data, pos, n))
                        .toString();
                pos += n;
                return s;
            } catch (CharacterCodingException e) {
                throw new CorruptDataException("Malformed UTF-8", e);
            }
        }

        void expectEnd() throws CorruptDataException {
            if (pos != end) throw new CorruptDataException((end - pos) + " unexpected bytes at the end of a record");
        }
    }
}
