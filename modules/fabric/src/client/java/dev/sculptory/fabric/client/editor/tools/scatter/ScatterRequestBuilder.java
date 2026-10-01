package dev.sculptory.fabric.client.editor.tools.scatter;

import dev.sculptory.core.scatter.ScatterArea;
import dev.sculptory.core.scatter.ScatterSettings;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.session.ScatterPreviewRequest;
import dev.sculptory.protocol.v2.C2S;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Maps the Scatter tool's area, settings and mix onto a {@link ScatterPreviewRequest}. Variants that cannot be
 * planned right now (a clipboard the server no longer holds, an asset whose preview failed) are left out; the plan's
 * variant indices then refer to {@link Ready#variants()}. Pure.
 */
public final class ScatterRequestBuilder {
    private ScatterRequestBuilder() {}

    /** The result of {@link #build}. */
    public sealed interface Result permits Ready, Missing {}

    /**
     * A request, and the mix entries its variant indices stand for (in order).
     */
    public record Ready(ScatterPreviewRequest request, List<ScatterMix.Variant> variants) implements Result {
        public Ready {
            Objects.requireNonNull(request);
            variants = List.copyOf(variants);
        }
    }

    /** Nothing can be sent yet; {@code reasonKey} says why (a translation key). */
    public record Missing(String reasonKey) implements Result {
        public static final Missing NO_AREA = new Missing("sculptory.scatter.status.no_area");
        public static final Missing NO_VARIANTS = new Missing("sculptory.scatter.status.no_variants");
        public static final Missing NO_USABLE_VARIANTS = new Missing("sculptory.scatter.status.no_usable_variants");

        public Missing {
            Objects.requireNonNull(reasonKey);
        }
    }

    public static Result build(PaintedArea area, SettingsValues values, ScatterToolSettings settings,
                               List<ScatterMix.Variant> mix, Predicate<ScatterMix.Variant> usable) {
        Objects.requireNonNull(values);
        Objects.requireNonNull(settings);
        Optional<ScatterArea> scatterArea = area.toArea();
        if (scatterArea.isEmpty()) return Missing.NO_AREA;
        return build(scatterArea.get(), settings.previewSettings(values), settings.transforms(values), mix, usable);
    }

    /**
     * Alt+click's one-spot request: {@code spot} (a stamp of radius 0), a target count of one and {@code seed}, with the
     * tool's other settings (filters, fit, transforms) as they are.
     */
    public static Result buildOne(ScatterArea spot, long seed, SettingsValues values, ScatterToolSettings settings,
                                  List<ScatterMix.Variant> mix, Predicate<ScatterMix.Variant> usable) {
        Objects.requireNonNull(values);
        Objects.requireNonNull(settings);
        C2S.ScatterPreview.Settings wanted = settings.previewSettings(values);
        C2S.ScatterPreview.Settings one = new C2S.ScatterPreview.Settings(seed, wanted.spacing(),
                new ScatterSettings.Density.Count(1), wanted.surface(), wanted.fit(), wanted.columnHeight());
        return build(spot, one, settings.transforms(values), mix, usable);
    }

    private static Result build(ScatterArea area, C2S.ScatterPreview.Settings preview,
                                ScatterSettings.Transforms transforms, List<ScatterMix.Variant> mix,
                                Predicate<ScatterMix.Variant> usable) {
        Objects.requireNonNull(usable);
        if (mix.isEmpty()) return Missing.NO_VARIANTS;
        List<ScatterMix.Variant> sent = new ArrayList<>();
        List<C2S.ScatterPreview.Variant> variants = new ArrayList<>();
        for (ScatterMix.Variant variant : mix) {
            if (!usable.test(variant)) continue;
            sent.add(variant);
            variants.add(new C2S.ScatterPreview.Variant(variant.source(), variant.weight()));
        }
        if (variants.isEmpty()) return Missing.NO_USABLE_VARIANTS;
        return new Ready(new ScatterPreviewRequest(area, preview, variants, transforms), sent);
    }
}
