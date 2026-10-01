package dev.sculptory.fabric.client.editor.render.ghost;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;

/**
 * GL state for the two ghost passes. The layers are only used for {@code startDrawing}/{@code endDrawing} (which set
 * and restore the state); meshes live in their own vertex buffers.
 *
 * <ol>
 *   <li>{@link #DEPTH}: depth only (colour writes off), terrain cutout program, so transparent texels are discarded
 *       and only the front-most ghost surface wins the depth test.</li>
 *   <li>{@link #COLOUR}: translucent program with alpha blending, depth test {@code LEQUAL} against that depth, depth
 *       writes off: each pixel shows one ghost surface, without sorting.</li>
 * </ol>
 * Both draw with culling disabled (mirrored placements reverse the winding) into {@code MAIN_TARGET}: ghosts render
 * in {@code WorldRenderEvents.LAST}, after Fabulous graphics composited its targets, so vanilla's translucent layer
 * (which targets the translucent framebuffer) cannot be used. The block atlas is sampled with mipmaps.
 */
final class GhostLayers {
    private GhostLayers() {}

    static final RenderLayer DEPTH = layer(
            "sculptory_ghost_depth", RenderPhase.CUTOUT_PROGRAM, RenderPhase.NO_TRANSPARENCY, RenderPhase.DEPTH_MASK);

    static final RenderLayer COLOUR = layer(
            "sculptory_ghost_colour", RenderPhase.TRANSLUCENT_PROGRAM, RenderPhase.TRANSLUCENT_TRANSPARENCY, RenderPhase.COLOR_MASK);

    private static RenderLayer layer(
            String name, RenderPhase.ShaderProgram program, RenderPhase.Transparency transparency, RenderPhase.WriteMaskState writeMask) {
        return RenderLayer.of(
                name,
                VertexFormats.POSITION_COLOR_TEXTURE_LIGHT_NORMAL,
                VertexFormat.DrawMode.QUADS,
                256,
                false,
                false,
                RenderLayer.MultiPhaseParameters.builder()
                        .lightmap(RenderPhase.ENABLE_LIGHTMAP)
                        .program(program)
                        .texture(RenderPhase.MIPMAP_BLOCK_ATLAS_TEXTURE)
                        .transparency(transparency)
                        .target(RenderPhase.MAIN_TARGET)
                        .writeMaskState(writeMask)
                        .cull(RenderPhase.DISABLE_CULLING)
                        .depthTest(RenderPhase.LEQUAL_DEPTH_TEST)
                        .build(false));
    }
}
