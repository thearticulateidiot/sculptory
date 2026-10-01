package dev.sculptory.core.mask;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.state.StateSpace;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * A short list of blocks, block tags and exact states: what a mask rule such as
 * "is one of these blocks" or "sits on top of" names. 1 to {@value #MAX_ENTRIES} entries.
 *
 * <p>Text form ({@link #format}, {@link #parse}): the entries joined by {@code ;}, a block as its id
 * ({@code minecraft:stone}), a tag with a leading {@code #} ({@code #minecraft:logs}), an exact state as its
 * {@link BlockDescriptor} text with properties ({@code minecraft:oak_stairs[facing=east,half=top,...]}).
 */
public record BlockSet(List<Entry> entries) {
    public static final int MAX_ENTRIES = 16;

    public BlockSet {
        entries = List.copyOf(entries);
        if (entries.isEmpty() || entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("A block set needs 1-" + MAX_ENTRIES + " entries");
        }
    }

    /** A set of one entry. */
    public static BlockSet of(Entry entry) {
        return new BlockSet(List.of(entry));
    }

    /** One entry of the set. */
    public sealed interface Entry permits Block, Tag, State {}

    /** Every state of a block. */
    public record Block(NamespacedId id) implements Entry {
        public Block {
            Objects.requireNonNull(id);
        }
    }

    /** Every state of every block in a block tag ({@code #minecraft:logs}). */
    public record Tag(NamespacedId tag) implements Entry {
        public Tag {
            Objects.requireNonNull(tag);
        }
    }

    /**
     * One exact state. It has properties: a block without any has one state, which {@link Block} names, so each set has
     * one text form.
     */
    public record State(BlockDescriptor state) implements Entry {
        public State {
            Objects.requireNonNull(state);
            if (state.properties().isEmpty()) throw new IllegalArgumentException("An exact state without properties");
        }
    }

    /** The text form: entries joined by {@code ;} (see the class comment). */
    public String format() {
        StringJoiner text = new StringJoiner(";");
        for (Entry entry : entries) {
            text.add(switch (entry) {
                case Block block -> block.id().value();
                case Tag tag -> "#" + tag.tag().value();
                case State state -> state.state().format();
            });
        }
        return text.toString();
    }

    /**
     * Parses {@link #format} text.
     *
     * @throws IllegalArgumentException for malformed text, an empty set or more than {@value #MAX_ENTRIES} entries
     */
    public static BlockSet parse(String text) {
        Objects.requireNonNull(text);
        if (text.isEmpty()) throw new IllegalArgumentException("An empty block set");
        List<Entry> entries = new ArrayList<>();
        for (String part : text.split(";", -1)) {
            if (part.startsWith("#")) {
                entries.add(new Tag(new NamespacedId(part.substring(1))));
            } else if (part.indexOf('[') >= 0) {
                entries.add(new State(BlockDescriptor.parse(part)));
            } else {
                entries.add(new Block(new NamespacedId(part)));
            }
            if (entries.size() > MAX_ENTRIES) throw new IllegalArgumentException("Over " + MAX_ENTRIES + " entries");
        }
        return new BlockSet(entries);
    }

    /**
     * The cells whose state is in this set, as a cell mask: blocks, tags and states or-ed together. A state
     * {@code states} does not know (a modded block that is gone, a property value it lacks) matches nothing.
     */
    public CellMask toCellMask(StateSpace states) {
        List<CellMask> parts = new ArrayList<>();
        List<NamespacedId> blocks = new ArrayList<>();
        List<Integer> handles = new ArrayList<>();
        for (Entry entry : entries) {
            switch (entry) {
                case Block block -> blocks.add(block.id());
                case Tag tag -> parts.add(new CellMask.Tag(tag.tag()));
                case State state -> {
                    int handle = states.resolve(state.state());
                    if (handle >= 0) handles.add(handle);
                }
            }
        }
        if (!blocks.isEmpty()) parts.add(0, new CellMask.Blocks(blocks));
        if (!handles.isEmpty()) parts.add(new CellMask.States(handles.stream().mapToInt(Integer::intValue).toArray()));
        if (parts.isEmpty()) return new CellMask.Not(CellMask.ANY);
        return parts.size() == 1 ? parts.get(0) : new CellMask.Or(parts);
    }
}
