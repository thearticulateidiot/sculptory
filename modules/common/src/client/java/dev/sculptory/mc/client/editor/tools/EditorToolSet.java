package dev.sculptory.mc.client.editor.tools;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.brush.BrushTool;
import dev.sculptory.mc.client.editor.tool.Tool;
import dev.sculptory.mc.client.editor.tool.ToolDescriptor;
import dev.sculptory.mc.client.editor.tool.ToolId;
import dev.sculptory.mc.client.editor.tool.ToolRegistry;
import dev.sculptory.mc.client.editor.tools.brush.BrushServices;
import dev.sculptory.mc.client.editor.tools.brush.ShapeBrushTool;
import dev.sculptory.mc.client.editor.tools.brush.SymmetryCentre;
import dev.sculptory.mc.client.editor.tools.brush.TerrainBrushTool;
import dev.sculptory.mc.client.editor.tools.extrude.ExtrudeTool;
import dev.sculptory.mc.client.editor.tools.fluid.FluidTool;
import dev.sculptory.mc.client.editor.tools.tinker.TinkerTool;
import dev.sculptory.mc.client.editor.tools.generate.GenerateTool;
import dev.sculptory.mc.client.editor.tools.place.PlaceTool;
import dev.sculptory.mc.client.editor.tools.scatter.ScatterTool;
import dev.sculptory.server.engine.Perm;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The editor palette, slots 1-17: Select, Raise, Lower, Smooth, Flatten, Paint, Palette Paint, Place, Scatter, Shape
 * (slot 10, key 0), Generate (slot 11, key -), Extrude (slot 12, key =), Fluid (slot 13, key [), Tinker (slot 14,
 * key ]), Weather (slot 15, key \), Heightmap (slot 16, key ;) and Biome (slot 17, key ').
 *
 * <p>Slots 2-7 and 15 are the {@link TerrainBrushTool}s, slot 8 the {@link PlaceTool}, slot 9 the {@link ScatterTool},
 * slot 10 the {@link ShapeBrushTool}, slot 11 the {@link GenerateTool}, slot 12 the {@link ExtrudeTool}, slot 13 the
 * {@link FluidTool} and slot 14 the {@link TinkerTool}. A tool whose id is in {@link #COMING_SOON} is shown disabled in
 * the palette with its milestone (none at the moment). Slots 16 and 17 are the Heightmap and Biome brushes
 * ({@link TerrainBrushTool}s of {@link BrushTool#HEIGHTMAP} and {@link BrushTool#BIOME}).
 */
public final class EditorToolSet {
    /** Tools shown disabled, with the milestone that brings them. */
    public static final Map<ToolId, String> COMING_SOON = Map.of();
    /** The active block of tool sets built without an editor (tests): stone. */
    private static final BlockDescriptor STONE = BlockDescriptor.of(new NamespacedId("minecraft:stone"));

    private EditorToolSet() {}

    /** Registers the ten palette tools in slot order, with brushes, Place and Scatter that draw nothing. */
    public static void register(ToolRegistry registry, Tool select) {
        register(registry, select, BrushServices.headless());
    }

    /** Registers the ten palette tools in slot order; the brushes use {@code brushes}, Place draws nothing. */
    public static void register(ToolRegistry registry, Tool select, BrushServices brushes) {
        register(registry, select, brushes, new PlaceTool(PlaceTool.Services.headless(), () -> false));
    }

    /** Registers the ten palette tools in slot order, with a Scatter tool that draws nothing. */
    public static void register(ToolRegistry registry, Tool select, BrushServices brushes, Tool place) {
        register(registry, select, brushes, place, new ScatterTool(ScatterTool.Services.headless()));
    }

    /** Registers the ten palette tools in slot order; the Shape brush places stone. */
    public static void register(ToolRegistry registry, Tool select, BrushServices brushes, Tool place, Tool scatter) {
        register(registry, select, brushes, place, scatter, () -> STONE);
    }

    /**
     * Registers the palette tools in slot order with a symmetry centre of their own and a Generate tool that draws
     * nothing (tests); the Shape brush places {@code activeBlock} (the editor's active block) unless it is set to a mix.
     */
    public static void register(ToolRegistry registry, Tool select, BrushServices brushes, Tool place, Tool scatter,
                                Supplier<BlockDescriptor> activeBlock) {
        register(registry, select, brushes, place, scatter, activeBlock, new SymmetryCentre(),
                new GenerateTool(GenerateTool.Services.headless()));
    }

    /** As {@link #register(ToolRegistry, Tool, BrushServices, Tool, Tool, Supplier)} with the shared centre. */
    public static void register(ToolRegistry registry, Tool select, BrushServices brushes, Tool place, Tool scatter,
                                Supplier<BlockDescriptor> activeBlock, SymmetryCentre centre) {
        register(registry, select, brushes, place, scatter, activeBlock, centre,
                new GenerateTool(GenerateTool.Services.headless()));
    }

    /** As {@link #register(ToolRegistry, Tool, BrushServices, Tool, Tool, Supplier)} with a given Generate tool. */
    public static void register(ToolRegistry registry, Tool select, BrushServices brushes, Tool place, Tool scatter,
                                Supplier<BlockDescriptor> activeBlock, Tool generate) {
        register(registry, select, brushes, place, scatter, activeBlock, new SymmetryCentre(), generate);
    }

    /**
     * As {@link #register(ToolRegistry, Tool, BrushServices, Tool, Tool, Supplier, SymmetryCentre, Tool, Tool)} with an
     * Extrude tool that draws nothing and confirms at once (tests), sharing {@code centre}.
     */
    public static void register(ToolRegistry registry, Tool select, BrushServices brushes, Tool place, Tool scatter,
                                Supplier<BlockDescriptor> activeBlock, SymmetryCentre centre, Tool generate) {
        register(registry, select, brushes, place, scatter, activeBlock, centre, generate, ExtrudeTool.headless(centre));
    }

    /**
     * As {@link #register(ToolRegistry, Tool, BrushServices, Tool, Tool, Supplier, SymmetryCentre, Tool, Tool, Tool)}
     * with a Fluid tool that confirms at once and draws nothing (tests).
     */
    public static void register(ToolRegistry registry, Tool select, BrushServices brushes, Tool place, Tool scatter,
                                Supplier<BlockDescriptor> activeBlock, SymmetryCentre centre, Tool generate, Tool extrude) {
        register(registry, select, brushes, place, scatter, activeBlock, centre, generate, extrude,
                new FluidTool(FluidTool.Services.headless(), brushes, centre));
    }

    /**
     * As {@link #register(ToolRegistry, Tool, BrushServices, Tool, Tool, Supplier, SymmetryCentre, Tool, Tool, Tool,
     * Tool)} with a Tinker tool that sees no entities and opens no panel (tests).
     */
    public static void register(ToolRegistry registry, Tool select, BrushServices brushes, Tool place, Tool scatter,
                                Supplier<BlockDescriptor> activeBlock, SymmetryCentre centre, Tool generate, Tool extrude,
                                Tool fluid) {
        register(registry, select, brushes, place, scatter, activeBlock, centre, generate, extrude, fluid,
                new TinkerTool(TinkerTool.Services.headless(), null, null));
    }

    /**
     * Registers the seventeen palette tools in slot order, the nine brushes and the Fluid tool sharing {@code centre}
     * (the editor gives the Select, Place and Extrude tools the same one, so M sets it for every
     * tool); the Shape brush places {@code activeBlock} (the editor's
     * active block) unless it is set to a mix.
     */
    public static void register(ToolRegistry registry, Tool select, BrushServices brushes, Tool place, Tool scatter,
                                Supplier<BlockDescriptor> activeBlock, SymmetryCentre centre, Tool generate, Tool extrude,
                                Tool fluid, Tool tinker) {
        registry.register(select);
        for (Tool tool : List.of(
                new TerrainBrushTool(BrushTool.RAISE, brushes, centre),
                new TerrainBrushTool(BrushTool.LOWER, brushes, centre),
                new TerrainBrushTool(BrushTool.SMOOTH, brushes, centre),
                new TerrainBrushTool(BrushTool.FLATTEN, brushes, centre),
                new TerrainBrushTool(BrushTool.PAINT, brushes, centre),
                new TerrainBrushTool(BrushTool.PALETTE, brushes, centre),
                place,
                scatter,
                new ShapeBrushTool(brushes, centre, activeBlock),
                generate,
                extrude,
                fluid,
                tinker,
                new TerrainBrushTool(BrushTool.WEATHER, brushes, centre),
                new TerrainBrushTool(BrushTool.HEIGHTMAP, brushes, centre),
                new TerrainBrushTool(BrushTool.BIOME, brushes, centre))) {
            registry.register(tool);
        }
    }

    /** The milestone that brings a disabled tool, if it is one. */
    public static Optional<String> comingSoon(ToolId id) {
        return Optional.ofNullable(COMING_SOON.get(id));
    }
}
