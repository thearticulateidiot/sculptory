package dev.sculptory.fabric.client.editor.check;

import dev.sculptory.core.Box;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * The block states of a box of the world at one moment (the integrated server's or the client's), compared cell by cell.
 * Taken on the thread that owns the world.
 */
public final class Cells {
    /** A cell that differs between two snapshots. */
    public record Change(int x, int y, int z, BlockState before, BlockState after) {
        @Override
        public String toString() {
            return x + " " + y + " " + z + ": " + describe(before) + " -> " + describe(after);
        }
    }

    private final Box box;
    private final int[] states;

    private Cells(Box box, int[] states) {
        this.box = box;
        this.states = states;
    }

    /** The states of {@code box} in {@code world} now. */
    public static Cells of(World world, Box box) {
        int[] states = new int[Math.toIntExact(box.volume())];
        BlockPos.Mutable at = new BlockPos.Mutable();
        int i = 0;
        for (int y = box.min().y(); y <= box.max().y(); y++) {
            for (int z = box.min().z(); z <= box.max().z(); z++) {
                for (int x = box.min().x(); x <= box.max().x(); x++) {
                    states[i++] = Block.getRawIdFromState(world.getBlockState(at.set(x, y, z)));
                }
            }
        }
        return new Cells(box, states);
    }

    public Box box() {
        return box;
    }

    public BlockState at(int x, int y, int z) {
        return Block.getStateFromRawId(states[index(x, y, z)]);
    }

    private int index(int x, int y, int z) {
        if (!box.contains(x, y, z)) {
            throw new IllegalArgumentException(x + " " + y + " " + z + " is outside " + box);
        }
        return ((y - box.min().y()) * box.sizeZ() + (z - box.min().z())) * box.sizeX() + (x - box.min().x());
    }

    /** A copy to write expected states into: what the world should look like after an edit. */
    public Editor edit() {
        return new Editor(box, states.clone());
    }

    /** Writes into a copy of a snapshot. */
    public static final class Editor {
        private final Cells cells;

        private Editor(Box box, int[] states) {
            this.cells = new Cells(box, states);
        }

        public Editor set(int x, int y, int z, BlockState state) {
            cells.states[cells.index(x, y, z)] = Block.getRawIdFromState(state);
            return this;
        }

        public BlockState at(int x, int y, int z) {
            return cells.at(x, y, z);
        }

        public Cells done() {
            return new Cells(cells.box, cells.states.clone());
        }
    }

    /** Cells whose state matches. */
    public long count(Predicate<BlockState> which) {
        long count = 0;
        for (int state : states) {
            if (which.test(Block.getStateFromRawId(state))) {
                count++;
            }
        }
        return count;
    }

    /** Cells in {@code part} (inside this box) whose state matches. */
    public long count(Box part, Predicate<BlockState> which) {
        long count = 0;
        for (int y = part.min().y(); y <= part.max().y(); y++) {
            for (int z = part.min().z(); z <= part.max().z(); z++) {
                for (int x = part.min().x(); x <= part.max().x(); x++) {
                    if (which.test(at(x, y, z))) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /** The cells that differ from {@code after} (the same box), in order. */
    public List<Change> diff(Cells after) {
        if (!box.equals(after.box)) {
            throw new IllegalArgumentException("Different boxes: " + box + " and " + after.box);
        }
        List<Change> changes = new ArrayList<>();
        int i = 0;
        for (int y = box.min().y(); y <= box.max().y(); y++) {
            for (int z = box.min().z(); z <= box.max().z(); z++) {
                for (int x = box.min().x(); x <= box.max().x(); x++) {
                    if (states[i] != after.states[i]) {
                        changes.add(new Change(x, y, z, Block.getStateFromRawId(states[i]),
                                Block.getStateFromRawId(after.states[i])));
                    }
                    i++;
                }
            }
        }
        return changes;
    }

    /** Whether every cell equals {@code other}'s. */
    public boolean same(Cells other) {
        return box.equals(other.box) && java.util.Arrays.equals(states, other.states);
    }

    /** At most {@code limit} changes as text, with the total ("3 cells differ: ..."). */
    public static String summary(List<Change> changes, int limit) {
        if (changes.isEmpty()) {
            return "no cell differs";
        }
        StringBuilder out = new StringBuilder(changes.size() + (changes.size() == 1 ? " cell differs: " : " cells differ: "));
        for (int i = 0; i < Math.min(limit, changes.size()); i++) {
            out.append(i == 0 ? "" : "; ").append(changes.get(i));
        }
        if (changes.size() > limit) {
            out.append("; ...");
        }
        return out.toString();
    }

    /** A state as {@code minecraft:stone} or {@code minecraft:water[level=3]}. */
    public static String describe(BlockState state) {
        String text = state.toString();
        // "Block{minecraft:stone}[...]" -> "minecraft:stone[...]"
        return text.startsWith("Block{") ? text.substring(6).replaceFirst("}", "") : text;
    }
}
