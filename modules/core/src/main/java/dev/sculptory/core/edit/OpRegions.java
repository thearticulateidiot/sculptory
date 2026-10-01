package dev.sculptory.core.edit;

import dev.sculptory.core.region.Region;
import java.util.Objects;

/** The region a Select operation works on, read and replaced (a selection upload swaps a cell set for its reference). */
public final class OpRegions {
    private OpRegions() {}

    /** The op's region, or {@code null} for a paste or scatter commit. */
    public static Region region(OpSpec op) {
        return switch (Objects.requireNonNull(op)) {
            case OpSpec.Fill fill -> fill.region();
            case OpSpec.Replace replace -> replace.region();
            case OpSpec.Erase erase -> erase.region();
            case OpSpec.Hollow hollow -> hollow.region();
            case OpSpec.Walls walls -> walls.region();
            case OpSpec.Move move -> move.region();
            case OpSpec.Stack stack -> stack.region();
            case OpSpec.Paste paste -> null;
            case OpSpec.Overlay overlay -> overlay.region();
            case OpSpec.Naturalize naturalize -> naturalize.region();
            case OpSpec.UpdateBlocks update -> update.region();
            case OpSpec.ScatterCommit scatter -> null;
        };
    }

    /**
     * The same op on {@code region} instead of its own.
     *
     * @throws IllegalArgumentException for a paste or scatter commit, which have no region
     */
    public static OpSpec withRegion(OpSpec op, Region region) {
        Objects.requireNonNull(region);
        return switch (Objects.requireNonNull(op)) {
            case OpSpec.Fill fill -> new OpSpec.Fill(region, fill.pattern(), fill.mask(), fill.symmetry());
            case OpSpec.Replace replace -> new OpSpec.Replace(region, replace.from(), replace.to(), replace.symmetry());
            case OpSpec.Erase erase -> new OpSpec.Erase(region, erase.mask(), erase.symmetry());
            case OpSpec.Hollow hollow -> new OpSpec.Hollow(region, hollow.thickness(), hollow.inside(), hollow.symmetry());
            case OpSpec.Walls walls -> new OpSpec.Walls(region, walls.thickness(), walls.pattern(), walls.symmetry());
            case OpSpec.Move move -> new OpSpec.Move(region, move.offset(), move.t(), move.leave(), move.entities(),
                    move.symmetry(), move.into());
            case OpSpec.Stack stack -> new OpSpec.Stack(region, stack.dx(), stack.dy(), stack.dz(), stack.count(),
                    stack.entities(), stack.symmetry(), stack.into(), stack.upsideDown());
            case OpSpec.Overlay overlay -> new OpSpec.Overlay(region, overlay.pattern(), overlay.depth(),
                    overlay.symmetry());
            case OpSpec.Naturalize n -> new OpSpec.Naturalize(region, n.top(), n.topDepth(), n.middle(), n.middleDepth(),
                    n.bottom(), n.symmetry());
            case OpSpec.UpdateBlocks update -> new OpSpec.UpdateBlocks(region, update.symmetry());
            case OpSpec.Paste paste -> throw new IllegalArgumentException("A paste has no region");
            case OpSpec.ScatterCommit scatter -> throw new IllegalArgumentException("A scatter commit has no region");
        };
    }
}
