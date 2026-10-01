package dev.sculptory.core.edit;

import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.edit.CopySupport.StateMapper;
import dev.sculptory.core.state.StateSpace;
import java.util.Objects;

/**
 * How a symmetric copy of a region op writes its pattern:
 * a position pattern ({@link Pattern.Single}, {@link Pattern.Weighted}, {@link Pattern.Arranged}) is evaluated at the
 * cell's pre-image (so a weighted or laid-out mix gives the copy the original's pattern, mirrored or turned: a Patches
 * copy mirrors the patches, a Gradient's line mirrors with the copy) and the state it gives is turned with the copy's
 * image, as a paste turns states. A state pattern ({@link Pattern.Waterlog}, {@link Pattern.Dry}) is evaluated on the
 * copy's own cell and its answer is not turned: it derives from the state already there (which the build turned
 * already) or is a fluid, which has no orientation.
 */
final class CopyFrame {
    private final Symmetry symmetry;
    private final Symmetry.Image inverse;
    private final StateSpace states;
    private final StateMapper mapper;

    CopyFrame(Symmetry symmetry, Symmetry.Image image, StateSpace states) {
        this.symmetry = Objects.requireNonNull(symmetry);
        this.inverse = Objects.requireNonNull(image).inverse();
        this.states = Objects.requireNonNull(states);
        this.mapper = new StateMapper(states, image.transform());
    }

    /** The x of the cell whose image is (x, z) (an int: the image of a world cell). */
    int preX(int x, int z) {
        return (int) symmetry.cellX(inverse, x, z);
    }

    /** The z of the cell whose image is (x, z). */
    int preZ(int x, int z) {
        return (int) symmetry.cellZ(inverse, x, z);
    }

    /** The state as the copy writes it: turned with the image (cached per handle). */
    int mapState(int handle) {
        return mapper.map(handle);
    }

    /**
     * {@code pattern} at the copy's cell (x, y, z): a position pattern evaluated at the pre-image, its state turned; a
     * state pattern evaluated on the cell as it is.
     */
    int apply(Pattern pattern, int x, int y, int z, int current) {
        return switch (pattern) {
            case Pattern.Single single -> mapper.map(single.state());
            case Pattern.Weighted weighted -> mapper.map(weighted.apply(states, preX(x, z), y, preZ(x, z), current));
            case Pattern.Arranged arranged -> mapper.map(arranged.apply(states, preX(x, z), y, preZ(x, z), current));
            case Pattern.Waterlog waterlog -> waterlog.apply(states, x, y, z, current);
            case Pattern.Dry dry -> dry.apply(states, x, y, z, current);
            case Pattern.SetProperty set -> set.apply(states, x, y, z, current);
            // The inner pattern as the copy sees it, then the cell's own properties kept.
            case Pattern.KeepShape keep -> states.withSharedProperties(apply(keep.inner(), x, y, z, current), current);
            case Pattern.Remap remap -> remap.apply(states, x, y, z, current);
        };
    }
}
