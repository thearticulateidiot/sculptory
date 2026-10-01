package dev.sculptory.core.brush;

import dev.sculptory.core.Box;
import dev.sculptory.core.edit.Pattern;
import java.util.Objects;

/**
 * Everything that fixes a stroke's output, sent once in {@code StrokeBegin}.
 *
 * @param radius 1-32 blocks
 * @param strength 0-1
 * @param material the blocks written; required for PAINT and PALETTE, and for SHAPE unless it carves, may be
 *     {@code null} otherwise; a mix laid out by steepness ({@code MixLayout.Steepness}) only for PALETTE
 * @param mask which surface columns the brush may change
 * @param depth paint/palette layer thickness, 0-32 (0 for tools that ignore it)
 * @param flattenY target surface y for FLATTEN
 * @param seed seed for any per-stroke randomness
 * @param clip the only cells the stroke may write ("only inside selection"), or {@code null} for no limit.
 *     Cells outside it are neither written nor count as changed; it only narrows what the stroke writes.
 *     Corners within ±{@value #CLIP_MAX_HORIZONTAL} (x, z) and ±{@value #CLIP_MAX_Y} (y); the server
 *     checks it against the world's real bounds.
 * @param symmetry how each dab is replicated ({@link Symmetry#NONE}: not at all); the kernel applies a dab and its
 *     copies as one step, and the clip box and mask apply to every copy
 * @param shapeSpec what the Shape brush places: required for {@link BrushTool#SHAPE}, {@code null} for every other
 *     tool. The Shape brush ignores strength, falloff, shape, mask, depth and flattenY ({@link #shape(int, ShapeSpec,
 *     Pattern, long, Box, Symmetry)} fills them with fixed values).
 * @param mode how Raise, Lower, Smooth and Flatten sculpt ({@link SculptMode}); {@link SculptMode#TERRAIN} for every
 *     other tool. In {@link SculptMode#SURFACE} a spec ignores flattenY and depth.
 * @param plane Surface-mode Flatten's plane, fixed for the press: required for FLATTEN in {@link SculptMode#SURFACE},
 *     {@code null} otherwise
 * @param weather what the Weather brush does: required for {@link BrushTool#WEATHER}, {@code null} for every other tool
 */
public record BrushSpec(BrushTool tool, int radius, float strength, Falloff falloff, Shape shape, Pattern material,
                        SurfaceMask mask, int depth, int flattenY, long seed, Box clip, Symmetry symmetry,
                        ShapeSpec shapeSpec, SculptMode mode, SurfacePlane plane, WeatherSpec weather) {
    public static final int MIN_RADIUS = 1;
    public static final int MAX_RADIUS = 32;
    public static final int MAX_DEPTH = 32;
    /** Largest |x| or |z| of a clip corner: the kernel's dab limit, beyond the vanilla world border. */
    public static final int CLIP_MAX_HORIZONTAL = 1 << 25;
    /** Largest |y| of a clip corner: beyond any world height a dimension can have. */
    public static final int CLIP_MAX_Y = 4096;

    public BrushSpec {
        Objects.requireNonNull(tool);
        Objects.requireNonNull(falloff);
        Objects.requireNonNull(shape);
        Objects.requireNonNull(mask);
        Objects.requireNonNull(symmetry);
        if (radius < MIN_RADIUS || radius > MAX_RADIUS) {
            throw new IllegalArgumentException("Brush radius must be " + MIN_RADIUS + "-" + MAX_RADIUS);
        }
        if (!(strength >= 0f && strength <= 1f)) throw new IllegalArgumentException("Brush strength must be 0-1");
        if (depth < 0 || depth > MAX_DEPTH) throw new IllegalArgumentException("Brush depth must be 0-" + MAX_DEPTH);
        if ((tool == BrushTool.SHAPE) != (shapeSpec != null)) {
            throw new IllegalArgumentException(tool == BrushTool.SHAPE ? "SHAPE needs a shape" : tool + " takes no shape");
        }
        boolean needsMaterial = tool == BrushTool.PAINT || tool == BrushTool.PALETTE
                || (tool == BrushTool.SHAPE && shapeSpec.usesMaterial());
        if (material == null && needsMaterial) throw new IllegalArgumentException(tool + " needs a material");
        if (Pattern.needsSteepness(material) && tool != BrushTool.PALETTE) {
            // Only Palette Paint measures the ground's steepness.
            throw new IllegalArgumentException("A Steepness pattern works only in Palette Paint, not " + tool);
        }
        if (clip != null && !clipInRange(clip)) throw new IllegalArgumentException("Brush clip box out of range: " + clip);
        Objects.requireNonNull(mode);
        if (mode == SculptMode.SURFACE && !SculptMode.surfaceTool(tool)) {
            throw new IllegalArgumentException(tool + " has no Surface mode");
        }
        boolean needsPlane = tool == BrushTool.FLATTEN && mode == SculptMode.SURFACE;
        if ((plane != null) != needsPlane) {
            throw new IllegalArgumentException(needsPlane ? "Surface Flatten needs a plane" : "Only Surface Flatten takes a plane");
        }
        if ((tool == BrushTool.WEATHER) != (weather != null)) {
            throw new IllegalArgumentException(tool == BrushTool.WEATHER ? "WEATHER needs a weather mode"
                    : tool + " takes no weather mode");
        }
    }

    /** A brush that is not the Weather brush (no weather mode). */
    public BrushSpec(BrushTool tool, int radius, float strength, Falloff falloff, Shape shape, Pattern material,
                     SurfaceMask mask, int depth, int flattenY, long seed, Box clip, Symmetry symmetry,
                     ShapeSpec shapeSpec, SculptMode mode, SurfacePlane plane) {
        this(tool, radius, strength, falloff, shape, material, mask, depth, flattenY, seed, clip, symmetry, shapeSpec,
                mode, plane, null);
    }

    /** A brush in the Terrain mode ({@link SculptMode#TERRAIN}). */
    public BrushSpec(BrushTool tool, int radius, float strength, Falloff falloff, Shape shape, Pattern material,
                     SurfaceMask mask, int depth, int flattenY, long seed, Box clip, Symmetry symmetry,
                     ShapeSpec shapeSpec) {
        this(tool, radius, strength, falloff, shape, material, mask, depth, flattenY, seed, clip, symmetry, shapeSpec,
                SculptMode.TERRAIN, null);
    }

    /** A terrain brush (no Shape brush data). */
    public BrushSpec(BrushTool tool, int radius, float strength, Falloff falloff, Shape shape, Pattern material,
                     SurfaceMask mask, int depth, int flattenY, long seed, Box clip, Symmetry symmetry) {
        this(tool, radius, strength, falloff, shape, material, mask, depth, flattenY, seed, clip, symmetry, null);
    }

    /** A brush without symmetry. */
    public BrushSpec(BrushTool tool, int radius, float strength, Falloff falloff, Shape shape, Pattern material,
                     SurfaceMask mask, int depth, int flattenY, long seed, Box clip) {
        this(tool, radius, strength, falloff, shape, material, mask, depth, flattenY, seed, clip, Symmetry.NONE);
    }

    /** A brush that may write anywhere (no clip box), without symmetry. */
    public BrushSpec(BrushTool tool, int radius, float strength, Falloff falloff, Shape shape, Pattern material,
                     SurfaceMask mask, int depth, int flattenY, long seed) {
        this(tool, radius, strength, falloff, shape, material, mask, depth, flattenY, seed, null);
    }

    /**
     * A Shape brush: the fields it ignores are strength 1, no falloff, a circle, any surface, depth 0 and flattenY 0.
     *
     * @param material the blocks placed; may be {@code null} when the shape carves
     */
    public static BrushSpec shape(int radius, ShapeSpec shape, Pattern material, long seed, Box clip, Symmetry symmetry) {
        return new BrushSpec(BrushTool.SHAPE, radius, 1f, Falloff.CONSTANT, Shape.CIRCLE, material, SurfaceMask.ANY, 0, 0,
                seed, clip, symmetry, Objects.requireNonNull(shape));
    }

    /** This spec with {@code clip} as its clip box ({@code null}: none). */
    public BrushSpec withClip(Box clip) {
        return new BrushSpec(tool, radius, strength, falloff, shape, material, mask, depth, flattenY, seed, clip, symmetry,
                shapeSpec, mode, plane, weather);
    }

    /** This spec with {@code symmetry}. */
    public BrushSpec withSymmetry(Symmetry symmetry) {
        return new BrushSpec(tool, radius, strength, falloff, shape, material, mask, depth, flattenY, seed, clip, symmetry,
                shapeSpec, mode, plane, weather);
    }

    /**
     * This spec in the Surface mode ({@link SculptMode#SURFACE}) with Flatten's {@code plane} ({@code null} for the other
     * tools); flattenY is kept but not used.
     */
    public BrushSpec withSurface(SurfacePlane plane) {
        return new BrushSpec(tool, radius, strength, falloff, shape, material, mask, depth, flattenY, seed, clip, symmetry,
                shapeSpec, SculptMode.SURFACE, plane, weather);
    }

    /** Whether this spec sculpts in the Surface mode. */
    public boolean surface() {
        return mode == SculptMode.SURFACE;
    }

    /**
     * How far a dab's writes reach from the dab's block along each axis, in blocks, whatever the shape's facing: the
     * radius for the terrain brushes (horizontally; the Surface mode's ball too); for the Shape brush half its box's longest side, since a shape may
     * lie on its side, plus one for a symmetric copy whose fitted centre lies in the next block
     * ({@link ShapeStamp#reachBox}). The resync footprints use it; the server checks a Shape dab's exact boxes.
     */
    public int reach() {
        return shapeSpec == null ? radius : Math.max(radius, shapeSpec.axialSize(radius) / 2) + 1;
    }

    private static boolean clipInRange(Box box) {
        return Math.abs((long) box.min().x()) <= CLIP_MAX_HORIZONTAL && Math.abs((long) box.max().x()) <= CLIP_MAX_HORIZONTAL
                && Math.abs((long) box.min().z()) <= CLIP_MAX_HORIZONTAL && Math.abs((long) box.max().z()) <= CLIP_MAX_HORIZONTAL
                && Math.abs((long) box.min().y()) <= CLIP_MAX_Y && Math.abs((long) box.max().y()) <= CLIP_MAX_Y;
    }
}
