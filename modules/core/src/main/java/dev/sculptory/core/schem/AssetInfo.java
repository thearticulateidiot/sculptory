package dev.sculptory.core.schem;

import dev.sculptory.core.BlockPos;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Library metadata stored under {@code Metadata.Sculptory} in a schematic.
 *
 * @param tags free-form search tags
 * @param anchor the asset's anchor as saved; informational: on read, the clipboard anchor always comes from the
 *     standard {@code Offset} (which the writer keeps equal to {@code -anchor})
 * @param rotations the clockwise quarter turns scatter may apply, distinct and sorted (0-3)
 * @param weight the default weight in a scatter mix, at least 1
 */
public record AssetInfo(List<String> tags, BlockPos anchor, List<Integer> rotations, int weight) {
    public static final List<Integer> ALL_ROTATIONS = List.of(0, 1, 2, 3);

    public AssetInfo {
        tags = List.copyOf(tags);
        Objects.requireNonNull(anchor);
        TreeSet<Integer> turns = new TreeSet<>();
        for (int turn : rotations) {
            if (turn < 0 || turn > 3) throw new IllegalArgumentException("Rotation must be 0-3 quarter turns: " + turn);
            turns.add(turn);
        }
        rotations = List.copyOf(turns);
        if (weight < 1) throw new IllegalArgumentException("Weight must be at least 1");
    }
}
