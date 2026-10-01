package dev.sculptory.core.edit;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.transform.Transform;

/** Where copying programs put things, for code outside the programs (a paste's entities land in the same box). */
public final class PasteGeometry {
    private PasteGeometry() {}

    /**
     * The world box a paste of a source of {@code size} with {@code anchor} fills when its transformed anchor lands on
     * {@code origin}: the box {@code PasteProgram} writes (before the build-height cut).
     *
     * @throws IllegalArgumentException if the box leaves the int coordinate range
     */
    public static Box pasteTarget(BlockPos size, BlockPos anchor, Transform t, BlockPos origin) {
        return CopySupport.pasteTarget(size, anchor, t, origin);
    }
}
