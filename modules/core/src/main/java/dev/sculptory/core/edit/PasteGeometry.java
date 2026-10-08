package dev.sculptory.core.edit;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.transform.Orientation;
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

    /**
     * {@link #pasteTarget(BlockPos, BlockPos, Transform, BlockPos)} for an orientation (protocol 6): the result box
     * ({@link Orientation#bounds}) placed so that the anchor's cell, mapped by {@link Orientation#mapCell}, lands on
     * {@code origin}. For an exact orientation it is the transform's box.
     *
     * @throws IllegalArgumentException if the box leaves the int coordinate range
     */
    public static Box pasteTarget(BlockPos size, BlockPos anchor, Orientation orientation, BlockPos origin) {
        if (orientation.isExact()) return pasteTarget(size, anchor, orientation.transform(), origin);
        BlockPos landed;
        try {
            landed = orientation.mapCell(anchor.x(), anchor.y(), anchor.z(), size.x(), size.y(), size.z());
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Destination is outside the coordinate range");
        }
        return CopySupport.boxAt((long) origin.x() - landed.x(), (long) origin.y() - landed.y(),
                (long) origin.z() - landed.z(), orientation.bounds(size.x(), size.y(), size.z()).size());
    }
}
