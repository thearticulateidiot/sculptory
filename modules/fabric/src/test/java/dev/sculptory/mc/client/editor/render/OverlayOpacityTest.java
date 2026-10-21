package dev.sculptory.mc.client.editor.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.sculptory.mc.client.editor.ui.UiOpacity;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * View > Opacity…'s Tool outlines setting: one factor on the alpha of every overlay colour, which reaches the outline
 * renderer's vertices ({@link LineBatch}) and never hides a colour completely.
 */
class OverlayOpacityTest {
    @AfterEach
    void opaqueAgain() {
        OverlayOpacity.set(1.0F);
    }

    @Test
    void theFactorScalesAlphaOnlyAndKeepsEveryVisibleColourVisible() {
        assertEquals(0xCC7FD4FF, OverlayOpacity.apply(0xCC7FD4FF), "at 100% colours pass unchanged");
        OverlayOpacity.set(0.5F);
        assertEquals(0x807FD4FF, OverlayOpacity.apply(0xFF7FD4FF));
        assertEquals(0x0B7FD4FF, OverlayOpacity.apply(0x167FD4FF), "a faint fill gets fainter");
        assertEquals(0.275F, OverlayOpacity.alpha(0.55F), 1e-6F, "the ghosts' colour pass");
        OverlayOpacity.set(0.1F);
        assertEquals(0x01FFFFFF, OverlayOpacity.apply(0x04FFFFFF), "never down to nothing");
        assertEquals(0x00FFFFFF, OverlayOpacity.apply(0x00FFFFFF), "an invisible colour stays invisible");
        OverlayOpacity.set(0.01F);
        assertEquals(0.1F, OverlayOpacity.factor(), "10% at least");
        OverlayOpacity.set(3.0F);
        assertEquals(1.0F, OverlayOpacity.factor());
        OverlayOpacity.set(Float.NaN);
        assertEquals(1.0F, OverlayOpacity.factor());
    }

    @Test
    void theToolOutlinesSettingReachesTheOutlineRenderersVertices() {
        UiOpacity opacity = new UiOpacity();
        OverlayOpacity.follow(opacity);
        PoseStack.Pose entry = new PoseStack().last();
        RecordingConsumer full = new RecordingConsumer();
        LineBatch.emitLine(full, entry, 0, 0, 0, 1, 2, 3, 0xFFFF7A7A);
        assertEquals(List.of(0xFF, 0xFF), full.alphas);

        opacity.set(UiOpacity.Values.DEFAULT.withToolOutlines(25));
        RecordingConsumer quarter = new RecordingConsumer();
        LineBatch.emitLine(quarter, entry, 0, 0, 0, 1, 2, 3, 0xFFFF7A7A);
        assertEquals(List.of(64, 64), quarter.alphas, "both ends of the line at 25%");
        assertEquals(List.of(0xFF, 0xFF), quarter.reds, "the colour itself is kept");

        opacity.set(UiOpacity.Values.DEFAULT.withToolOutlines(10));
        RecordingConsumer faint = new RecordingConsumer();
        LineBatch.emitLine(faint, entry, 0, 0, 0, 1, 0, 0, 0x08FFFFFF);
        assertEquals(List.of(1, 1), faint.alphas, "a faint line at the lowest setting still shows");
    }

    /** Keeps the colour of each vertex a line writes. */
    private static final class RecordingConsumer implements VertexConsumer {
        final List<Integer> alphas = new ArrayList<>();
        final List<Integer> reds = new ArrayList<>();

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            reds.add(red);
            alphas.add(alpha);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }
    }
}
