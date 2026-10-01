package dev.sculptory.fabric.client.session;

import dev.sculptory.core.scatter.ScatterArea;
import dev.sculptory.core.scatter.ScatterSettings;
import dev.sculptory.protocol.v2.C2S;
import java.util.List;
import java.util.Objects;

/**
 * M3. What {@link EditorSession#scatterPreview} plans: {@code C2S.ScatterPreview} without its request id, which the
 * session assigns. The variant order is the order the plan's placements index.
 */
public record ScatterPreviewRequest(ScatterArea area, C2S.ScatterPreview.Settings settings,
                                    List<C2S.ScatterPreview.Variant> variants, ScatterSettings.Transforms transforms) {
    public ScatterPreviewRequest {
        Objects.requireNonNull(area);
        Objects.requireNonNull(settings);
        Objects.requireNonNull(transforms);
        variants = List.copyOf(variants);
        if (variants.isEmpty() || variants.size() > ScatterSettings.MAX_VARIANTS) {
            throw new IllegalArgumentException("A scatter needs 1-" + ScatterSettings.MAX_VARIANTS + " variants");
        }
    }

    /** The wire message under {@code reqId}. */
    public C2S.ScatterPreview message(int reqId) {
        return new C2S.ScatterPreview(reqId, area, settings, variants, transforms);
    }
}
