package dev.sculptory.mc.client.editor.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.OptionalDouble;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

/**
 * Render layers for editor overlays.
 *
 * <p>All layers draw into {@code MAIN_TARGET}: overlays render in the loader's world-render-last hook,
 * after Fabulous graphics has already composited its separate targets, so vanilla's lines layer
 * ({@code ITEM_ENTITY_TARGET}) would not show there. None of them write depth, so overlay draw order
 * does not depend on depth. The "see-through" variants use an always-pass depth test.
 *
 * <p>{@code RenderType.create} and the {@code RenderStateShard} constants used here are made public by
 * {@code sculptory.accesswidener} (Fabric API widens them too, but this code compiles without it).
 */
final class OverlayLayers {
    private OverlayLayers() {}

    /** Depth-tested lines (vertex format LINES: position, colour, normal = line direction). */
    static final RenderType LINES = lines("sculptory_overlay_lines", RenderStateShard.LEQUAL_DEPTH_TEST);

    /** Lines that show through terrain. */
    static final RenderType LINES_SEE_THROUGH = lines("sculptory_overlay_lines_see_through", RenderStateShard.NO_DEPTH_TEST);

    /** Depth-tested translucent quads (position, colour), pulled towards the camera to avoid z-fighting. */
    static final RenderType QUADS = quads("sculptory_overlay_quads", RenderStateShard.LEQUAL_DEPTH_TEST);

    /** Translucent quads that show through terrain. */
    static final RenderType QUADS_SEE_THROUGH = quads("sculptory_overlay_quads_see_through", RenderStateShard.NO_DEPTH_TEST);

    private static RenderType lines(String name, RenderStateShard.DepthTestStateShard depthTest) {
        return RenderType.create(
                name,
                DefaultVertexFormat.POSITION_COLOR_NORMAL,
                VertexFormat.Mode.LINES,
                1536,
                false,
                false,
                RenderType.CompositeState.builder()
                        .setShaderState(RenderStateShard.RENDERTYPE_LINES_SHADER)
                        .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.empty()))
                        .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
                        .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                        .setOutputState(RenderStateShard.MAIN_TARGET)
                        .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                        .setCullState(RenderStateShard.NO_CULL)
                        .setDepthTestState(depthTest)
                        .createCompositeState(false));
    }

    private static RenderType quads(String name, RenderStateShard.DepthTestStateShard depthTest) {
        return RenderType.create(
                name,
                DefaultVertexFormat.POSITION_COLOR,
                VertexFormat.Mode.QUADS,
                1536,
                false,
                true,
                RenderType.CompositeState.builder()
                        .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                        .setLayeringState(RenderStateShard.POLYGON_OFFSET_LAYERING)
                        .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                        .setOutputState(RenderStateShard.MAIN_TARGET)
                        .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                        .setCullState(RenderStateShard.NO_CULL)
                        .setDepthTestState(depthTest)
                        .createCompositeState(false));
    }
}
