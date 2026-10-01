package dev.sculptory.core.nbt;

import java.util.Arrays;
import java.util.Objects;

/**
 * An immutable NBT tag. The type ids are the binary format's; {@link NbtIo} reads and writes them big-endian.
 * Array tags copy their contents in and out.
 */
public sealed interface NbtTag permits NbtTag.End, NbtTag.NbtByte, NbtTag.NbtShort, NbtTag.NbtInt, NbtTag.NbtLong,
        NbtTag.NbtFloat, NbtTag.NbtDouble, NbtTag.NbtByteArray, NbtTag.NbtString, NbtList, NbtCompound,
        NbtTag.NbtIntArray, NbtTag.NbtLongArray {
    byte END = 0;
    byte BYTE = 1;
    byte SHORT = 2;
    byte INT = 3;
    byte LONG = 4;
    byte FLOAT = 5;
    byte DOUBLE = 6;
    byte BYTE_ARRAY = 7;
    byte STRING = 8;
    byte LIST = 9;
    byte COMPOUND = 10;
    byte INT_ARRAY = 11;
    byte LONG_ARRAY = 12;

    /** The binary type id. */
    byte type();

    /** The value of a byte, short or int tag as an int; {@code null} for any other tag (or {@code null}). */
    static Integer intValue(NbtTag tag) {
        return switch (tag) {
            case NbtByte b -> (int) b.value();
            case NbtShort s -> (int) s.value();
            case NbtInt i -> i.value();
            case null, default -> null;
        };
    }

    /** Only appears as the element type of an empty list; never inside a compound. */
    record End() implements NbtTag {
        public static final End INSTANCE = new End();

        @Override
        public byte type() {
            return END;
        }
    }

    record NbtByte(byte value) implements NbtTag {
        @Override
        public byte type() {
            return BYTE;
        }
    }

    record NbtShort(short value) implements NbtTag {
        @Override
        public byte type() {
            return SHORT;
        }
    }

    record NbtInt(int value) implements NbtTag {
        @Override
        public byte type() {
            return INT;
        }
    }

    record NbtLong(long value) implements NbtTag {
        @Override
        public byte type() {
            return LONG;
        }
    }

    record NbtFloat(float value) implements NbtTag {
        @Override
        public byte type() {
            return FLOAT;
        }
    }

    record NbtDouble(double value) implements NbtTag {
        @Override
        public byte type() {
            return DOUBLE;
        }
    }

    /** A string; at most 65,535 bytes once encoded as modified UTF-8 (checked when written). */
    record NbtString(String value) implements NbtTag {
        public NbtString {
            Objects.requireNonNull(value);
        }

        @Override
        public byte type() {
            return STRING;
        }
    }

    record NbtByteArray(byte[] value) implements NbtTag {
        public NbtByteArray {
            value = value.clone();
        }

        @Override
        public byte[] value() {
            return value.clone();
        }

        public int length() {
            return value.length;
        }

        /** One element, without copying the array. */
        public byte get(int index) {
            return value[index];
        }

        /** The array itself, for the codec in this package; never modified. */
        byte[] raw() {
            return value;
        }

        @Override
        public byte type() {
            return BYTE_ARRAY;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof NbtByteArray other && Arrays.equals(value, other.value);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(value);
        }

        @Override
        public String toString() {
            return "NbtByteArray[" + value.length + "]";
        }
    }

    record NbtIntArray(int[] value) implements NbtTag {
        public NbtIntArray {
            value = value.clone();
        }

        @Override
        public int[] value() {
            return value.clone();
        }

        public int length() {
            return value.length;
        }

        /** One element, without copying the array. */
        public int get(int index) {
            return value[index];
        }

        /** The array itself, for the codec in this package; never modified. */
        int[] raw() {
            return value;
        }

        @Override
        public byte type() {
            return INT_ARRAY;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof NbtIntArray other && Arrays.equals(value, other.value);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(value);
        }

        @Override
        public String toString() {
            return value.length <= 8 ? "NbtIntArray" + Arrays.toString(value) : "NbtIntArray[" + value.length + "]";
        }
    }

    record NbtLongArray(long[] value) implements NbtTag {
        public NbtLongArray {
            value = value.clone();
        }

        @Override
        public long[] value() {
            return value.clone();
        }

        public int length() {
            return value.length;
        }

        /** One element, without copying the array. */
        public long get(int index) {
            return value[index];
        }

        /** The array itself, for the codec in this package; never modified. */
        long[] raw() {
            return value;
        }

        @Override
        public byte type() {
            return LONG_ARRAY;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof NbtLongArray other && Arrays.equals(value, other.value);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(value);
        }

        @Override
        public String toString() {
            return "NbtLongArray[" + value.length + "]";
        }
    }
}
