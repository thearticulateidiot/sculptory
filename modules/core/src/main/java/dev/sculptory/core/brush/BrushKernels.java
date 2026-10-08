package dev.sculptory.core.brush;

import java.util.Objects;

/** The kernel for each brush tool. Kernels are stateless and shared; per-stroke state lives in {@link StrokeState}. */
public final class BrushKernels {
    private static final BrushKernel[] KERNELS = new BrushKernel[BrushTool.values().length];

    static {
        for (BrushTool tool : BrushTool.values()) {
            KERNELS[tool.ordinal()] = switch (tool) {
                case SHAPE -> new ShapeKernel();
                case WEATHER -> new WeatherKernel();
                // Contract stubs (protocol 6): ws/heightmap and ws/biomes replace them with their kernels.
                case HEIGHTMAP, BIOME -> notImplemented(tool);
                default -> new TerrainKernel(tool);
            };
        }
    }

    private BrushKernels() {}

    /** A kernel that refuses every step: the brush is not built yet (the server refuses its strokes first). */
    private static BrushKernel notImplemented(BrushTool tool) {
        return (s, dabs, st, w, out) -> {
            throw new UnsupportedOperationException(tool + " is not implemented");
        };
    }

    /**
     * The kernel for {@code tool}. It throws {@link IllegalArgumentException} when applied to a spec of a
     * different tool, a dab outside the world, or a material with states outside the world's state space.
     */
    public static BrushKernel forTool(BrushTool tool) {
        return KERNELS[Objects.requireNonNull(tool).ordinal()];
    }
}
