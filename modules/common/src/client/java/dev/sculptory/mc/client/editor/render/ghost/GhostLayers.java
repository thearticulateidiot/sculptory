package dev.sculptory.mc.client.editor.render.ghost;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

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
 * in the loader's world-render-last hook, after Fabulous graphics composited its targets, so vanilla's translucent layer
 * (which targets the translucent framebuffer) cannot be used. The block atlas is sampled with mipmaps.
 */
final class GhostLayers {
    private GhostLayers() {}

    static final RenderType DEPTH = layer(
            "sculptory_ghost_depth", RenderStateShard.RENDERTYPE_CUTOUT_SHADER, RenderStateShard.NO_TRANSPARENCY, RenderStateShard.DEPTH_WRITE);

    static final RenderType COLOUR = layer(
            "sculptory_ghost_colour", RenderStateShard.RENDERTYPE_TRANSLUCENT_SHADER, RenderStateShard.TRANSLUCENT_TRANSPARENCY, RenderStateShard.COLOR_WRITE);

    private static RenderType layer(
            String name, RenderStateShard.ShaderStateShard program, RenderStateShard.TransparencyStateShard transparency, RenderStateShard.WriteMaskStateShard writeMask) {
        return RenderType.create(
                name,
                DefaultVertexFormat.BLOCK,
                VertexFormat.Mode.QUADS,
                256,
                false,
                false,
                RenderType.CompositeState.builder()
                        .setLightmapState(RenderStateShard.LIGHTMAP)
                        .setShaderState(program)
                        .setTextureState(RenderStateShard.BLOCK_SHEET_MIPPED)
                        .setTransparencyState(transparency)
                        .setOutputState(RenderStateShard.MAIN_TARGET)
                        .setWriteMaskState(writeMask)
                        .setCullState(RenderStateShard.NO_CULL)
                        .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                        .createCompositeState(false));
    }
}
