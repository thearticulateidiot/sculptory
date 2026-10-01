package dev.sculptory.core.nbt;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * An immutable NBT compound. Keeps insertion order (so encoding is deterministic), but equality ignores
 * order, like Minecraft's. Build one with {@link #builder()}.
 */
public final class NbtCompound implements NbtTag {
    public static final NbtCompound EMPTY = new NbtCompound(new LinkedHashMap<>());

    private final Map<String, NbtTag> entries;

    private NbtCompound(LinkedHashMap<String, NbtTag> entries) {
        this.entries = Collections.unmodifiableMap(entries);
    }

    public static Builder builder() {
        return new Builder(new LinkedHashMap<>());
    }

    /** A builder starting with this compound's entries, in order. */
    public Builder toBuilder() {
        return new Builder(new LinkedHashMap<>(entries));
    }

    @Override
    public byte type() {
        return COMPOUND;
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public boolean contains(String key) {
        return entries.containsKey(key);
    }

    /** The keys, in insertion order (unmodifiable). */
    public Set<String> keys() {
        return entries.keySet();
    }

    /** The entries, in insertion order (unmodifiable). */
    public Map<String, NbtTag> entries() {
        return entries;
    }

    /** The tag at {@code key}, or {@code null}. */
    public NbtTag get(String key) {
        return entries.get(key);
    }

    /** The tag at {@code key} if it is a {@code type}; {@code null} when absent or of another type. */
    public <T extends NbtTag> T get(String key, Class<T> type) {
        NbtTag tag = entries.get(key);
        return type.isInstance(tag) ? type.cast(tag) : null;
    }

    public NbtCompound getCompound(String key) {
        return get(key, NbtCompound.class);
    }

    public NbtList getList(String key) {
        return get(key, NbtList.class);
    }

    /** The string at {@code key}, or {@code null}. */
    public String getString(String key) {
        NbtString tag = get(key, NbtString.class);
        return tag == null ? null : tag.value();
    }

    /** The value of a byte, short or int tag at {@code key}, or {@code null}. */
    public Integer getInt(String key) {
        return NbtTag.intValue(entries.get(key));
    }

    /** The int array at {@code key} (copied), or {@code null}. */
    public int[] getIntArray(String key) {
        NbtIntArray tag = get(key, NbtIntArray.class);
        return tag == null ? null : tag.value();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof NbtCompound other && entries.equals(other.entries);
    }

    @Override
    public int hashCode() {
        return entries.hashCode();
    }

    @Override
    public String toString() {
        return entries.size() <= 16 ? "NbtCompound" + entries : "NbtCompound[" + entries.size() + " entries]";
    }

    /** Builds a compound. Not thread-safe; {@link #build()} may be called more than once. */
    public static final class Builder {
        private final LinkedHashMap<String, NbtTag> entries;

        private Builder(LinkedHashMap<String, NbtTag> entries) {
            this.entries = entries;
        }

        /**
         * Sets {@code key}; a key already present keeps its position.
         *
         * @throws IllegalArgumentException for an END tag
         */
        public Builder put(String key, NbtTag value) {
            Objects.requireNonNull(key);
            Objects.requireNonNull(value);
            if (value.type() == END) throw new IllegalArgumentException("END tag in a compound");
            entries.put(key, value);
            return this;
        }

        public Builder putByte(String key, byte value) {
            return put(key, new NbtByte(value));
        }

        public Builder putShort(String key, short value) {
            return put(key, new NbtShort(value));
        }

        public Builder putInt(String key, int value) {
            return put(key, new NbtInt(value));
        }

        public Builder putLong(String key, long value) {
            return put(key, new NbtLong(value));
        }

        public Builder putString(String key, String value) {
            return put(key, new NbtString(value));
        }

        public Builder putByteArray(String key, byte[] value) {
            return put(key, new NbtByteArray(value));
        }

        public Builder putIntArray(String key, int[] value) {
            return put(key, new NbtIntArray(value));
        }

        public Builder putLongArray(String key, long[] value) {
            return put(key, new NbtTag.NbtLongArray(value));
        }

        public Builder remove(String key) {
            entries.remove(key);
            return this;
        }

        public boolean contains(String key) {
            return entries.containsKey(key);
        }

        public NbtCompound build() {
            return new NbtCompound(new LinkedHashMap<>(entries));
        }
    }

    /** Wraps an already-validated map without copying (the reader's fast path). */
    static NbtCompound unchecked(LinkedHashMap<String, NbtTag> entries) {
        return new NbtCompound(entries);
    }
}
