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
                default -> new TerrainKernel(tool);
            };
        }
    }

    private BrushKernels() {}

    /**
     * The kernel for {@code tool}. It throws {@link IllegalArgumentException} when applied to a spec of a
     * different tool, a dab outside the world, or a material with states outside the world's state space.
     */
    public static BrushKernel forTool(BrushTool tool) {
        return KERNELS[Objects.requireNonNull(tool).ordinal()];
    }
}
