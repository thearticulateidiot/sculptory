package dev.sculptory.core.nbt;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An immutable NBT list: elements of one tag type. An empty list may have any element type
 * ({@link NbtTag#END} when unknown).
 */
public final class NbtList implements NbtTag {
    public static final NbtList EMPTY = new NbtList(END, List.of());

    private final byte elementType;
    private final List<NbtTag> items;

    private NbtList(byte elementType, List<NbtTag> items) {
        this.elementType = elementType;
        this.items = items;
    }

    /**
     * A list of {@code items}, all of {@code elementType}.
     *
     * @throws IllegalArgumentException if an element has another type, or a non-empty list has element type END
     */
    public static NbtList of(byte elementType, List<? extends NbtTag> items) {
        List<NbtTag> copy = List.copyOf(items);
        if (elementType < END || elementType > LONG_ARRAY) throw new IllegalArgumentException("Bad NBT type " + elementType);
        if (!copy.isEmpty() && elementType == END) throw new IllegalArgumentException("Non-empty list of END");
        for (NbtTag item : copy) {
            if (item.type() != elementType) {
                throw new IllegalArgumentException("List of type " + elementType + " holds a " + item.type());
            }
        }
        return new NbtList(elementType, copy);
    }

    /** A list whose element type is that of its first element (END when empty). */
    public static NbtList of(List<? extends NbtTag> items) {
        return of(items.isEmpty() ? END : items.get(0).type(), items);
    }

    /** A list of string tags. */
    public static NbtList ofStrings(List<String> values) {
        List<NbtTag> tags = new ArrayList<>(values.size());
        for (String value : values) tags.add(new NbtString(value));
        return of(STRING, tags);
    }

    @Override
    public byte type() {
        return LIST;
    }

    public byte elementType() {
        return elementType;
    }

    public int size() {
        return items.size();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public NbtTag get(int index) {
        return items.get(index);
    }

    /** The elements (unmodifiable). */
    public List<NbtTag> items() {
        return items;
    }

    /** The elements as compounds, or {@code null} if this is a non-empty list of another type. */
    public List<NbtCompound> compounds() {
        if (items.isEmpty()) return List.of();
        if (elementType != COMPOUND) return null;
        List<NbtCompound> out = new ArrayList<>(items.size());
        for (NbtTag item : items) out.add((NbtCompound) item);
        return out;
    }

    /** The elements as strings, or {@code null} if this is a non-empty list of another type. */
    public List<String> strings() {
        if (items.isEmpty()) return List.of();
        if (elementType != STRING) return null;
        List<String> out = new ArrayList<>(items.size());
        for (NbtTag item : items) out.add(((NbtString) item).value());
        return out;
    }

    /** Equal element types (ignored when both are empty) and equal elements. */
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof NbtList other)) return false;
        if (items.isEmpty() && other.items.isEmpty()) return true;
        return elementType == other.elementType && items.equals(other.items);
    }

    @Override
    public int hashCode() {
        return items.hashCode();
    }

    @Override
    public String toString() {
        return items.size() <= 8 ? "NbtList" + items : "NbtList[" + items.size() + " of type " + elementType + "]";
    }

    static NbtList unchecked(byte elementType, List<NbtTag> items) {
        return new NbtList(elementType, Objects.requireNonNull(items));
    }

    private static final NbtList[] EMPTY_OF_TYPE = new NbtList[LONG_ARRAY + 1];

    static {
        for (byte type = END; type <= LONG_ARRAY; type++) {
            EMPTY_OF_TYPE[type] = type == END ? EMPTY : new NbtList(type, List.of());
        }
    }

    /** The shared empty list of an element type (the reader keeps the type it read). */
    static NbtList emptyOf(byte elementType) {
        return EMPTY_OF_TYPE[elementType];
    }
}
