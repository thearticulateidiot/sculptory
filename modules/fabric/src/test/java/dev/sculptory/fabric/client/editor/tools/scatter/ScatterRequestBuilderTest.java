package dev.sculptory.fabric.client.editor.tools.scatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.scatter.ScatterArea;
import dev.sculptory.core.scatter.ScatterSettings;
import dev.sculptory.core.scatter.ScatterSource;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.fabric.client.editor.settings.SettingDef;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.session.ScatterPreviewRequest;
import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.protocol.v2.Codec;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** How the Scatter tool's area, settings and mix become a {@code ScatterPreview}. */
class ScatterRequestBuilderTest {
    private static final String HASH_A = "aa".repeat(32);
    private static final String HASH_B = "bb".repeat(32);

    private final ScatterToolSettings settings = new ScatterToolSettings();
    private final PaintedArea area = new PaintedArea();
    private final ScatterMix mix = new ScatterMix();
    private SettingsValues values = SettingsValues.defaults(settings.schema());

    private ScatterRequestBuilder.Result build() {
        return ScatterRequestBuilder.build(area, values, settings, mix.variants(), variant -> true);
    }

    private ScatterPreviewRequest request() {
        return assertInstanceOf(ScatterRequestBuilder.Ready.class, build()).request();
    }

    private static BlockDescriptor block(String id) {
        return BlockDescriptor.of(new NamespacedId(id));
    }

    private void paintAndMix() {
        area.add(ScatterArea.Stamp.paint(5, 5, 6));
        area.add(ScatterArea.Stamp.erase(5, 5, 1));
        mix.add(new SourceRef.Asset(HASH_A), "trees/oak.schem");
        mix.add(new SourceRef.Asset(HASH_B), "rocks/boulder.schem");
        mix.setWeight(1, 250);
    }

    @Test
    void theDefaultsMapOntoAPreviewOfThePaintedStamps() {
        paintAndMix();
        ScatterPreviewRequest request = request();
        assertEquals(new ScatterArea.Stamps(List.of(ScatterArea.Stamp.paint(5, 5, 6), ScatterArea.Stamp.erase(5, 5, 1))),
                request.area());
        assertEquals(new C2S.ScatterPreview.Settings(1L, 4, new ScatterSettings.Density.Fraction(0.1), SurfaceMask.ANY,
                new ScatterSettings.Fit(false, 0.5)), request.settings());
        assertEquals(List.of(new C2S.ScatterPreview.Variant(new SourceRef.Asset(HASH_A), ScatterMix.DEFAULT_WEIGHT),
                new C2S.ScatterPreview.Variant(new SourceRef.Asset(HASH_B), 250)), request.variants());
        assertEquals(ScatterSettings.Transforms.ALL, request.transforms());
    }

    @Test
    void densityAsATargetCountAndTheFitAreMapped() {
        paintAndMix();
        values = values.with(settings.densityMode, ScatterToolSettings.DensityMode.COUNT)
                .with(settings.densityCount, 250)
                .with(settings.allowInFluid, true)
                .with(settings.support, 80)
                .with(settings.survive, false)
                .with(settings.spacing, 12)
                .with(settings.seed, -77L)
                .with(settings.columnHeight, new dev.sculptory.fabric.client.editor.settings.SettingDef.IntSpan(2, 5));
        C2S.ScatterPreview.Settings mapped = request().settings();
        assertEquals(new ScatterSettings.ColumnHeight(2, 5), mapped.columnHeight());
        assertEquals(new ScatterSettings.Density.Count(250), mapped.density());
        assertEquals(new ScatterSettings.Fit(true, 0.8, false), mapped.fit());
        assertEquals(12, mapped.spacing());
        assertEquals(-77L, mapped.seed());
    }

    @Test
    void aPercentageBecomesAShareOfTheEligibleColumns() {
        paintAndMix();
        values = values.with(settings.densityPercent, 2.5);
        assertEquals(new ScatterSettings.Density.Fraction(0.025), request().settings().density());
    }

    @Test
    void theFiltersBecomeSurfaceConjunctsAndFullRangesMeanNoLimit() {
        paintAndMix();
        values = values.with(settings.slope, new SettingDef.IntSpan(1, 6))
                .with(settings.elevation, new SettingDef.IntSpan(60, 90))
                .with(settings.substrate, List.of(block("minecraft:grass_block"), block("minecraft:dirt"),
                        block("minecraft:grass_block")));
        SurfaceMask surface = request().settings().surface();
        assertEquals(new SurfaceMask.And(List.of(
                new SurfaceMask.SurfaceBlocks(new CellMask.Blocks(List.of(new NamespacedId("minecraft:grass_block"),
                        new NamespacedId("minecraft:dirt")))),
                new SurfaceMask.Elevation(60, 90),
                new SurfaceMask.Slope(1, 6))), surface);
        ScatterSettings.Filters filters = ScatterSettings.Filters.of(surface);
        assertEquals(60, filters.minY());
        assertEquals(6, filters.maxSlope());

        values = values.with(settings.slope, new SettingDef.IntSpan(0, ScatterToolSettings.MAX_SLOPE))
                .with(settings.elevation, new SettingDef.IntSpan(ScatterToolSettings.MIN_Y, ScatterToolSettings.MAX_Y))
                .with(settings.substrate, List.of());
        assertSame(SurfaceMask.ANY, request().settings().surface());
    }

    @Test
    void theAllowedTurnsAndMirroringBecomeTheTransforms() {
        paintAndMix();
        values = values.with(settings.turn0, false).with(settings.turn180, false).with(settings.turn270, false)
                .with(settings.mirror, false);
        assertEquals(new ScatterSettings.Transforms(0b0010, false), request().transforms());
        values = values.with(settings.turn90, false).with(settings.mirror, true);
        assertEquals(new ScatterSettings.Transforms(0b0001, true), request().transforms(),
                "with no turn ticked, placements keep their orientation");
    }

    @Test
    void theSelectionBoxIsSentAsARegion() {
        paintAndMix();
        dev.sculptory.core.Box box = dev.sculptory.core.Box.of(new dev.sculptory.core.BlockPos(0, 50, 0),
                new dev.sculptory.core.BlockPos(63, 90, 63));
        area.useBox(box);
        assertEquals(new ScatterArea.Region(box), request().area());
    }

    @Test
    void unusableVariantsAreLeftOutAndTheIndicesFollowTheVariantsSent() {
        paintAndMix();
        mix.add(new SourceRef.Clipboard(new UUID(4, 4)), "Copy");
        ScatterRequestBuilder.Ready ready = assertInstanceOf(ScatterRequestBuilder.Ready.class,
                ScatterRequestBuilder.build(area, values, settings, mix.variants(),
                        variant -> !variant.source().equals(new ScatterSource.Held(new SourceRef.Asset(HASH_A)))));
        assertEquals(List.of("rocks/boulder.schem", "Copy"),
                ready.variants().stream().map(ScatterMix.Variant::name).toList());
        assertEquals(2, ready.request().variants().size());
        assertEquals(ScatterRequestBuilder.Missing.NO_USABLE_VARIANTS,
                ScatterRequestBuilder.build(area, values, settings, mix.variants(), variant -> false));
    }

    @Test
    void nothingIsSentWithoutAnAreaOrAVariant() {
        assertEquals(ScatterRequestBuilder.Missing.NO_AREA, build());
        area.add(ScatterArea.Stamp.paint(0, 0, 3));
        assertEquals(ScatterRequestBuilder.Missing.NO_VARIANTS, build());
    }

    @Test
    void theRequestIsAValidWireMessage() throws Exception {
        paintAndMix();
        values = values.with(settings.substrate, List.of(block("minecraft:stone")))
                .with(settings.slope, new SettingDef.IntSpan(0, 3));
        FakeStateSpace states = new FakeStateSpace();
        C2S.ScatterPreview message = request().message(12);
        assertEquals(message, Codec.decodeC2S(Codec.encodeC2S(message, states), states));
    }

    @Test
    void onlyChangesBeyondThePaintingRadiusChangeThePlan() {
        SettingsValues radius = values.with(settings.radius, 20);
        assertFalse(settings.affectsPlan(values, radius));
        assertTrue(settings.affectsPlan(values, values.with(settings.seed, 9L)));
        assertTrue(settings.affectsPlan(radius, radius.with(settings.spacing, 9)));
    }

    // ---- The mix ----

    @Test
    void theMixKeepsOneEntryPerSourceUpToItsCapAndOneClipboard() {
        assertEquals(ScatterMix.AddResult.ADDED, mix.add(new SourceRef.Asset(HASH_A), "a"));
        assertEquals(ScatterMix.AddResult.DUPLICATE, mix.add(new SourceRef.Asset(HASH_A), "a again"));
        assertEquals(ScatterMix.AddResult.ADDED, mix.add(new SourceRef.Clipboard(new UUID(1, 1)), "Copy"));
        assertEquals(ScatterMix.AddResult.REPLACED_CLIPBOARD, mix.add(new SourceRef.Clipboard(new UUID(2, 2)), "Cut"),
                "the server holds one clipboard: the older id is dead");
        assertEquals(List.of("a", "Cut"), mix.variants().stream().map(ScatterMix.Variant::name).toList());
        for (int i = mix.size(); i < ScatterMix.MAX_VARIANTS; i++) {
            mix.add(new SourceRef.Asset(String.format("%064x", i)), "v" + i);
        }
        assertEquals(ScatterMix.AddResult.FULL, mix.add(new SourceRef.Asset(HASH_B), "one too many"));
        assertTrue(mix.setWeight(0, 5_000));
        assertEquals(ScatterMix.MAX_WEIGHT, mix.variants().get(0).weight(), "weights are clamped");
        assertFalse(mix.setWeight(0, 1_000));
        mix.remove(0);
        assertEquals(ScatterMix.MAX_VARIANTS - 1, mix.size());
    }

    /** Blocks share the mix with the other sources: its cap, one entry per exact state; they never replace a clipboard. */
    @Test
    void blocksShareTheMixByExactState() {
        ScatterSource poppy = new ScatterSource.Block("minecraft:poppy");
        assertEquals(ScatterMix.AddResult.ADDED, mix.add(poppy, "Poppy"));
        assertEquals(ScatterMix.AddResult.DUPLICATE, mix.add(new ScatterSource.Block("minecraft:poppy"), "Poppy"));
        assertEquals(ScatterMix.AddResult.ADDED,
                mix.add(new ScatterSource.Block("minecraft:pink_petals[facing=north,flower_amount=2]"), "Pink Petals"));
        assertEquals(ScatterMix.AddResult.ADDED,
                mix.add(new ScatterSource.Block("minecraft:pink_petals[facing=east,flower_amount=2]"), "Pink Petals"),
                "another state of the same block is another variant");
        assertEquals(ScatterMix.AddResult.ADDED, mix.add(new SourceRef.Clipboard(new UUID(1, 1)), "Copy"));
        assertEquals(ScatterMix.AddResult.ADDED, mix.add(new ScatterSource.Block("minecraft:dandelion"), "Dandelion"));
        assertEquals(List.of("Poppy", "Pink Petals", "Pink Petals", "Copy", "Dandelion"),
                mix.variants().stream().map(ScatterMix.Variant::name).toList());
        assertFalse(ScatterMix.isClipboard(poppy));
        assertFalse(ScatterMix.isAsset(poppy));
        assertTrue(mix.setWeight(0, 3));
        assertEquals(3, mix.variants().get(0).weight());

        for (int i = mix.size(); i < ScatterMix.MAX_VARIANTS; i++) {
            mix.add(new SourceRef.Asset(String.format("%064x", i)), "v" + i);
        }
        assertEquals(ScatterMix.AddResult.FULL, mix.add(new ScatterSource.Block("minecraft:allium"), "Allium"));
        mix.remove(0);
        assertEquals(ScatterMix.AddResult.ADDED, mix.add(poppy, "Poppy"), "back in once removed");
        assertEquals(poppy, mix.variants().get(ScatterMix.MAX_VARIANTS - 1).source());
    }

    @Test
    void aBlockVariantIsSentAsItsStateText() {
        area.add(ScatterArea.Stamp.paint(5, 5, 6));
        mix.add(new ScatterSource.Block("minecraft:tall_grass[half=lower]"), "Tall Grass");
        mix.add(new SourceRef.Asset(HASH_A), "trees/oak.schem");
        assertEquals(List.of(
                new C2S.ScatterPreview.Variant(new ScatterSource.Block("minecraft:tall_grass[half=lower]"),
                        ScatterMix.DEFAULT_WEIGHT),
                new C2S.ScatterPreview.Variant(new SourceRef.Asset(HASH_A), ScatterMix.DEFAULT_WEIGHT)),
                request().variants());
        assertTrue(request().settings().fit().survive(), "only where it can survive, by default");
    }
}
