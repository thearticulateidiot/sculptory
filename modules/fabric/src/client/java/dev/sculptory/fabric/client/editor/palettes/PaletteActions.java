package dev.sculptory.fabric.client.editor.palettes;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.palette.BlockPalette;
import dev.sculptory.core.palette.PalettePattern;
import dev.sculptory.core.scatter.ScatterSource;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.fabric.client.editor.clipboard.LibraryPaths;
import dev.sculptory.fabric.client.editor.settings.SettingDef;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tools.brush.BrushSettings;
import dev.sculptory.fabric.client.editor.tools.brush.ShapeSettings;
import dev.sculptory.fabric.client.editor.tools.scatter.ScatterMix;
import dev.sculptory.fabric.client.editor.tools.scatter.ScatterTool;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.LibraryChange;
import dev.sculptory.fabric.client.session.LoadedPalette;
import dev.sculptory.fabric.client.session.Notice;
import dev.sculptory.fabric.client.session.Reply;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.library.LibraryPath;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Named block palettes for the Paint, Palette Paint, Scatter and Shape tools (the Tool Settings window's Save palette… and
 * Load palette…): what a tool's blocks are as a palette, and what loading one does to the tool. Nothing else about the
 * tool changes.
 *
 * <ul>
 *   <li><b>Save:</b> Paint's material (weight 1), Palette Paint's or the Shape brush's weighted blocks (its mix,
 *       whether or not it places the mix now) in their order with their Pattern settings, or
 *       the Scatter mix's block rows
 *       (clipboard and asset rows are left out, and the player is told how many). States are written as this game's
 *       exact state text; the same state twice is merged.</li>
 *   <li><b>Patterns:</b> a load into Palette Paint or the Shape brush sets their Pattern settings from the palette (the
 *       Shape brush can't lay out by steepness: such a palette loads there as Random, with a toast); Paint and Scatter
 *       ignore it, and save Random.</li>
 *   <li><b>Load:</b> into Palette Paint, it replaces the mix (which holds {@value BrushSettings#MAX_PALETTE_ENTRIES}
 *       blocks, as a palette does, so nothing is cut); into Paint, a one-block palette becomes the material and a
 *       larger one switches to Palette Paint with that mix; into the Shape brush, it replaces the mix and sets the brush to
 *       place the mix; into Scatter, it replaces the mix's block rows and keeps its asset
 *       and clipboard rows (blocks that can't be scattered, and those beyond {@value ScatterMix#MAX_VARIANTS}
 *       variants, are left out).</li>
 *   <li><b>Toasts</b> say what was saved or loaded, and what was left out: states the server doesn't know (counted by
 *       the server), states this game doesn't know, blocks the tool can't use, and what didn't fit.</li>
 * </ul>
 *
 * The last folder a palette was saved to or loaded from is remembered for the next save and pick. Client thread only.
 */
public final class PaletteActions {
    /** Where a first save goes. */
    public static final String DEFAULT_FOLDER = "palettes";
    public static final String DEFAULT_NAME = "my_palette";
    private static final String KEY = "sculptory.palette.";

    /** What the actions reach of the editor. */
    public interface Host {
        Optional<EditorSession> session();

        /** A tool's settings. */
        SettingsValues settings(ToolId id);

        /** Replaces a tool's settings (the Tool Settings window follows). */
        void updateSettings(ToolId id, SettingsValues values);

        /** Makes a tool active; false (after a toast saying why) when it can't be. */
        boolean selectTool(ToolId id);

        void notify(Notice notice);

        /** This game's block states, once a world with Sculptory is joined. */
        Optional<StateSpace> states();
    }

    /** A tool's blocks as a palette, and how many of its rows could not go into one. */
    public record Capture(BlockPalette palette, int leftOut) {
        public Capture {
            Objects.requireNonNull(palette);
        }
    }

    private final Host host;
    private final BrushSettings paint;
    private final BrushSettings palette;
    private final ScatterTool scatter;
    /** The folder of the last palette saved or loaded, or {@code null} before the first. */
    private String folder;

    /**
     * @param paint the Paint tool's settings, {@code palette} Palette Paint's
     * @param scatter the Scatter tool, or {@code null} without one
     */
    public PaletteActions(Host host, BrushSettings paint, BrushSettings palette, ScatterTool scatter) {
        this.host = Objects.requireNonNull(host);
        this.paint = Objects.requireNonNull(paint);
        this.palette = Objects.requireNonNull(palette);
        if (paint.material == null || palette.palette == null) {
            throw new IllegalArgumentException("Paint's and Palette Paint's settings are needed");
        }
        this.scatter = scatter;
    }

    /** Whether a tool has palettes: Paint, Palette Paint, Shape, and Scatter when there is one. */
    public boolean supports(ToolId tool) {
        return tool.equals(ToolId.PAINT) || tool.equals(ToolId.PALETTE) || tool.equals(ToolId.SHAPE)
                || (tool.equals(ToolId.SCATTER) && scatter != null);
    }

    /** The folder the palette picker opens in: the last one used, else the library root. */
    public String folder() {
        return folder == null ? "" : folder;
    }

    /** What the save prompt starts with: the last folder used (else {@value #DEFAULT_FOLDER}) and a name. */
    public String suggestedPath() {
        String name = DEFAULT_NAME + LibraryPath.PALETTE_EXTENSION;
        return LibraryPaths.join(folder == null ? DEFAULT_FOLDER : folder, name);
    }

    // ================================================================== save

    /** The tool's blocks as a palette; empty, after a toast saying why, when it has none. */
    public Optional<Capture> capture(ToolId tool) {
        requireSupported(tool);
        Map<String, Integer> weights = new LinkedHashMap<>();
        int leftOut = 0;
        if (tool.equals(ToolId.PAINT)) {
            leftOut += add(weights, host.settings(ToolId.PAINT).get(paint.material).format(), 1);
        } else if (tool.equals(ToolId.PALETTE)) {
            for (SettingDef.WeightedBlock entry : host.settings(ToolId.PALETTE).get(palette.palette)) {
                leftOut += add(weights, entry.block().format(), entry.weight());
            }
        } else if (tool.equals(ToolId.SHAPE)) {
            for (SettingDef.WeightedBlock entry : host.settings(ToolId.SHAPE).get(ShapeSettings.PALETTE)) {
                leftOut += add(weights, entry.block().format(), entry.weight());
            }
        } else {
            for (ScatterMix.Variant variant : scatter.mix().variants()) {
                if (variant.source() instanceof ScatterSource.Block block) {
                    leftOut += add(weights, block.state(), variant.weight());
                } else {
                    leftOut++;
                }
            }
        }
        if (weights.isEmpty()) {
            host.notify(Notice.of(Notice.Level.INFO, KEY + "nothing_to_save"));
            return Optional.empty();
        }
        List<BlockPalette.Entry> entries = new ArrayList<>();
        weights.forEach((state, weight) -> entries.add(new BlockPalette.Entry(state, weight)));
        if (entries.size() > BlockPalette.MAX_ENTRIES) {
            leftOut += entries.size() - BlockPalette.MAX_ENTRIES;
            entries.subList(BlockPalette.MAX_ENTRIES, entries.size()).clear();
        }
        return Optional.of(new Capture(new BlockPalette(entries, pattern(tool)), leftOut));
    }

    /**
     * The pattern a tool's palette is saved with: Palette Paint's and the Shape brush's Pattern settings;
     * Random for Paint and Scatter, which have none.
     */
    private PalettePattern pattern(ToolId tool) {
        if (tool.equals(ToolId.PALETTE)) return palette.mixPattern.palettePattern(host.settings(ToolId.PALETTE));
        if (tool.equals(ToolId.SHAPE)) return ShapeSettings.PATTERN.palettePattern(host.settings(ToolId.SHAPE));
        return PalettePattern.RANDOM;
    }

    /**
     * Adds a state to a palette being made, as this game's exact state text and merged with the same state; returns 1
     * when it can't go into a palette (text over the size cap), else 0.
     */
    private int add(Map<String, Integer> weights, String text, int weight) {
        String exact = exact(text);
        if (exact.isBlank() || exact.getBytes(StandardCharsets.UTF_8).length > BlockPalette.MAX_STATE_BYTES) return 1;
        weights.merge(exact, weight, (a, b) -> Math.min(BlockPalette.MAX_WEIGHT, a + b));
        return 0;
    }

    /** This game's own text for a state (every property given), or the text as it is when it can't be resolved. */
    private String exact(String text) {
        Optional<StateSpace> states = host.states();
        if (states.isEmpty()) return text;
        try {
            int handle = states.get().parse(text);
            return handle < 0 ? text : states.get().format(handle);
        } catch (RuntimeException e) {
            return text;
        }
    }

    /**
     * Saves the tool's blocks as the palette {@code path} (a {@code .palette.json} path; the server may put it in the
     * player's own folder). Completes with whether it was saved; the session toasts refusals.
     */
    public CompletionStage<Boolean> save(ToolId tool, String path) {
        Optional<EditorSession> session = host.session();
        Optional<Capture> capture = capture(tool);
        if (session.isEmpty() || capture.isEmpty()) return CompletableFuture.completedFuture(false);
        return session.get().savePalette(path, capture.get().palette()).thenApply(reply -> {
            if (!(reply instanceof Reply.Ok<LibraryChange> ok)) return false;
            String written = ok.value().to();
            folder = LibraryPaths.parent(written);
            host.notify(Notice.of(Notice.Level.SUCCESS, KEY + "saved", written,
                    Integer.toString(capture.get().palette().size())));
            if (capture.get().leftOut() > 0) {
                host.notify(Notice.of(Notice.Level.INFO, KEY + "left_out", Integer.toString(capture.get().leftOut())));
            }
            return true;
        });
    }

    // ================================================================== load

    /** Loads the palette {@code path} into the tool. Completes with whether the tool changed. */
    public CompletionStage<Boolean> load(ToolId tool, String path) {
        requireSupported(tool);
        Optional<EditorSession> session = host.session();
        if (session.isEmpty()) return CompletableFuture.completedFuture(false);
        return session.get().loadPalette(path).thenApply(reply -> {
            if (!(reply instanceof Reply.Ok<LoadedPalette> ok)) return false;
            folder = LibraryPaths.parent(ok.value().path());
            return apply(tool, ok.value());
        });
    }

    /** Puts a loaded palette into the tool (see the class comment); returns whether the tool changed. */
    public boolean apply(ToolId tool, LoadedPalette loaded) {
        requireSupported(tool);
        String name = LibraryPaths.stem(loaded.path());
        if (loaded.dropped() > 0) {
            host.notify(Notice.of(Notice.Level.WARNING, KEY + "server_dropped", Integer.toString(loaded.dropped()),
                    name, String.join(", ", loaded.droppedStates())));
        }
        if (tool.equals(ToolId.SCATTER)) return applyScatter(name, loaded.palette());
        List<SettingDef.WeightedBlock> blocks = brushBlocks(loaded.palette());
        if (blocks.isEmpty()) {
            host.notify(Notice.of(Notice.Level.WARNING, KEY + "nothing_usable", name));
            return false;
        }
        if (tool.equals(ToolId.PAINT) && blocks.size() == 1) {
            BlockDescriptor block = blocks.get(0).block();
            host.updateSettings(ToolId.PAINT, host.settings(ToolId.PAINT).with(paint.material, block));
            host.notify(Notice.of(Notice.Level.SUCCESS, KEY + "loaded_material", name, block.format()));
            return true;
        }
        if (tool.equals(ToolId.SHAPE)) {
            // As many blocks as Palette Paint's mix: it loads whole, and the brush places it from now on.
            SettingsValues shape = ShapeSettings.PATTERN.withPalettePattern(host.settings(ToolId.SHAPE),
                    loaded.palette().pattern());
            host.updateSettings(ToolId.SHAPE, shape.with(ShapeSettings.PALETTE, blocks)
                    .with(ShapeSettings.BLOCKS, ShapeSettings.Blocks.PALETTE));
            host.notify(Notice.of(Notice.Level.SUCCESS, KEY + "loaded", name, Integer.toString(blocks.size())));
            if (loaded.palette().pattern().kind() == PalettePattern.Kind.STEEPNESS) {
                // Only Palette Paint measures the ground's steepness.
                host.notify(Notice.of(Notice.Level.INFO, KEY + "steepness_as_random", name));
            }
            return true;
        }
        // A palette holds at most BlockPalette.MAX_ENTRIES blocks, as many as Palette Paint's mix: it loads whole.
        host.updateSettings(ToolId.PALETTE, palette.mixPattern.withPalettePattern(host.settings(ToolId.PALETTE),
                loaded.palette().pattern()).with(palette.palette, blocks));
        if (tool.equals(ToolId.PAINT)) {
            host.notify(Notice.of(Notice.Level.SUCCESS, KEY + "loaded_switched", name,
                    Integer.toString(blocks.size())));
            host.selectTool(ToolId.PALETTE);
        } else {
            host.notify(Notice.of(Notice.Level.SUCCESS, KEY + "loaded", name, Integer.toString(blocks.size())));
        }
        return true;
    }

    /** The palette's blocks as a brush takes them; those this game can't read or doesn't know are left out. */
    private List<SettingDef.WeightedBlock> brushBlocks(BlockPalette loaded) {
        Optional<StateSpace> states = host.states();
        List<SettingDef.WeightedBlock> blocks = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (BlockPalette.Entry entry : loaded.entries()) {
            BlockDescriptor block;
            try {
                block = BlockDescriptor.parse(entry.state());
            } catch (IllegalArgumentException malformed) {
                unknown.add(entry.state());
                continue;
            }
            if (states.isPresent() && resolve(states.get(), block) < 0) {
                unknown.add(entry.state());
                continue;
            }
            blocks.add(new SettingDef.WeightedBlock(block, entry.weight()));
        }
        unknownHere(unknown);
        return blocks;
    }

    private static int resolve(StateSpace states, BlockDescriptor block) {
        try {
            return states.resolve(block);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private void unknownHere(List<String> unknown) {
        if (unknown.isEmpty()) return;
        host.notify(Notice.of(Notice.Level.WARNING, KEY + "unknown_here", Integer.toString(unknown.size()),
                String.join(", ", unknown.subList(0, Math.min(3, unknown.size())))));
    }

    /**
     * Replaces the Scatter mix's block rows with the palette's blocks (as {@link ScatterTool#blockVariantText} makes
     * them; repeats merged), after its asset and clipboard rows, within {@value ScatterMix#MAX_VARIANTS} variants.
     */
    private boolean applyScatter(String name, BlockPalette loaded) {
        Optional<EditorSession> session = host.session();
        if (session.isPresent() && !ScatterTool.mayScatterBlocks(session.get().permissions())) {
            host.notify(Notice.of(Notice.Level.WARNING, "sculptory.notice.scatter_block_permission",
                    Perm.BRUSH.node(), Perm.REGION.node()));
            return false;
        }
        List<ScatterMix.Variant> kept = new ArrayList<>();
        for (ScatterMix.Variant variant : scatter.mix().variants()) {
            if (!(variant.source() instanceof ScatterSource.Block)) kept.add(variant);
        }
        Map<String, Integer> blocks = new LinkedHashMap<>();
        List<String> refused = new ArrayList<>();
        for (BlockPalette.Entry entry : loaded.entries()) {
            Optional<String> text = scatter.blockVariantText(entry.state());
            if (text.isEmpty()) {
                refused.add(entry.state());
                continue;
            }
            blocks.merge(text.get(), entry.weight(), (a, b) -> Math.min(ScatterMix.MAX_WEIGHT, a + b));
        }
        if (!refused.isEmpty()) {
            host.notify(Notice.of(Notice.Level.WARNING, KEY + "not_scatterable", Integer.toString(refused.size()),
                    String.join(", ", refused.subList(0, Math.min(3, refused.size())))));
        }
        if (blocks.isEmpty()) {
            host.notify(Notice.of(Notice.Level.WARNING, KEY + "nothing_usable", name));
            return false;
        }
        int room = ScatterMix.MAX_VARIANTS - kept.size();
        List<ScatterMix.Variant> variants = new ArrayList<>(kept);
        int added = 0;
        for (Map.Entry<String, Integer> block : blocks.entrySet()) {
            if (added == room) break;
            variants.add(new ScatterMix.Variant(new ScatterSource.Block(block.getKey()),
                    scatter.blockVariantName(block.getKey()), block.getValue()));
            added++;
        }
        if (added < blocks.size()) {
            host.notify(Notice.of(Notice.Level.WARNING, KEY + "truncated_scatter", name, Integer.toString(added),
                    Integer.toString(blocks.size()), Integer.toString(ScatterMix.MAX_VARIANTS)));
        }
        if (added == 0) return false;
        scatter.replaceMix(variants);
        host.notify(Notice.of(Notice.Level.SUCCESS, KEY + "loaded_scatter", name, Integer.toString(added),
                Integer.toString(kept.size())));
        return true;
    }

    private void requireSupported(ToolId tool) {
        if (!supports(tool)) throw new IllegalArgumentException("No palettes for " + tool);
    }
}
