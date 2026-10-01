package dev.sculptory.fabric.client.editor.render;

import java.util.OptionalDouble;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;

/**
 * Render layers for editor overlays.
 *
 * <p>All layers draw into {@code MAIN_TARGET}: overlays render in {@code WorldRenderEvents.LAST},
 * after Fabulous graphics has already composited its separate targets, so vanilla's lines layer
 * ({@code ITEM_ENTITY_TARGET}) would not show there. None of them write depth, so overlay draw order
 * does not depend on depth. The "see-through" variants use an always-pass depth test.
 *
 * <p>{@code RenderLayer.of} and the {@code RenderPhase} constants are made public by Fabric API's
 * transitive access wideners, so no project access widener is needed.
 */
final class OverlayLayers {
    private OverlayLayers() {}

    /** Depth-tested lines (vertex format LINES: position, colour, normal = line direction). */
    static final RenderLayer LINES = lines("sculptory_overlay_lines", RenderPhase.LEQUAL_DEPTH_TEST);

    /** Lines that show through terrain. */
    static final RenderLayer LINES_SEE_THROUGH = lines("sculptory_overlay_lines_see_through", RenderPhase.ALWAYS_DEPTH_TEST);

    /** Depth-tested translucent quads (position, colour), pulled towards the camera to avoid z-fighting. */
    static final RenderLayer QUADS = quads("sculptory_overlay_quads", RenderPhase.LEQUAL_DEPTH_TEST);

    /** Translucent quads that show through terrain. */
    static final RenderLayer QUADS_SEE_THROUGH = quads("sculptory_overlay_quads_see_through", RenderPhase.ALWAYS_DEPTH_TEST);

    private static RenderLayer lines(String name, RenderPhase.DepthTest depthTest) {
        return RenderLayer.of(
                name,
                VertexFormats.LINES,
                VertexFormat.DrawMode.LINES,
                1536,
                false,
                false,
                RenderLayer.MultiPhaseParameters.builder()
                        .program(RenderPhase.LINES_PROGRAM)
                        .lineWidth(new RenderPhase.LineWidth(OptionalDouble.empty()))
                        .layering(RenderPhase.VIEW_OFFSET_Z_LAYERING)
                        .transparency(RenderPhase.TRANSLUCENT_TRANSPARENCY)
                        .target(RenderPhase.MAIN_TARGET)
                        .writeMaskState(RenderPhase.COLOR_MASK)
                        .cull(RenderPhase.DISABLE_CULLING)
                        .depthTest(depthTest)
                        .build(false));
    }

    private static RenderLayer quads(String name, RenderPhase.DepthTest depthTest) {
        return RenderLayer.of(
                name,
                VertexFormats.POSITION_COLOR,
                VertexFormat.DrawMode.QUADS,
                1536,
                false,
                true,
                RenderLayer.MultiPhaseParameters.builder()
                        .program(RenderPhase.COLOR_PROGRAM)
                        .layering(RenderPhase.POLYGON_OFFSET_LAYERING)
                        .transparency(RenderPhase.TRANSLUCENT_TRANSPARENCY)
                        .target(RenderPhase.MAIN_TARGET)
                        .writeMaskState(RenderPhase.COLOR_MASK)
                        .cull(RenderPhase.DISABLE_CULLING)
                        .depthTest(depthTest)
                        .build(false));
    }
}
