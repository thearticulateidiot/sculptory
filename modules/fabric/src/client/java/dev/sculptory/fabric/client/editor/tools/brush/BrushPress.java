package dev.sculptory.fabric.client.editor.tools.brush;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.world.WorldReader;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.settings.SettingDef;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.editor.tool.ToolContext;
import dev.sculptory.fabric.client.editor.tool.WorldCursor;
import dev.sculptory.fabric.client.session.Notice;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What one press of a brush fixes for every server stroke of it (restarts included), shared by the terrain brushes and
 * the Shape brush: the selection when the press began ("Only inside selection" clips every stroke of the press to it)
 * and the symmetry centre, fixed the first time a stroke of the press needs one. Also the symmetry centre key, the
 * symmetry the overlay shows, and a palette's weighted pattern. Client thread only.
 */
final class BrushPress {
    private final SymmetryCentre centre;
    private final BrushServices services;
    /** The selection when the press began. */
    private Box selection;
    /** The symmetry centre {@code {x2, z2}} of the press, fixed once a stroke of it first needs one. */
    private int[] pressCentre;

    BrushPress(SymmetryCentre centre, BrushServices services) {
        this.centre = Objects.requireNonNull(centre);
        this.services = Objects.requireNonNull(services);
    }

    /** A press begins, with the selection as it is now. */
    void begin(Optional<Box> selection) {
        this.selection = selection.orElse(null);
        pressCentre = null;
    }

    /** The press ended (or was refused). */
    void end() {
        selection = null;
        pressCentre = null;
    }

    /** The selection when the press began. */
    Optional<Box> selection() {
        return Optional.ofNullable(selection);
    }

    /** Whether the press's symmetry centre is fixed. */
    boolean hasCentre() {
        return pressCentre != null;
    }

    /**
     * The press's selection clamped to the build height: the clip box of "Only inside selection". {@code null}, after
     * telling the player, when there was no selection when the press began or it lies wholly outside the build height.
     */
    Box clip(ToolContext c) {
        if (selection == null) {
            c.notify(Notice.of(Notice.Level.WARNING, TerrainBrushTool.NEEDS_SELECTION));
            return null;
        }
        WorldReader world = worldOrNull(c);
        Box clamped = world == null ? selection
                : TerrainBrushTool.clampToWorld(selection, world.bottomY(), world.topYExclusive());
        if (clamped == null) {
            c.notify(Notice.of(Notice.Level.WARNING, TerrainBrushTool.SELECTION_OUTSIDE_WORLD));
        }
        return clamped;
    }

    // ---- Symmetry ----

    /**
     * The press's symmetry for {@code mode}, around the press's centre: fixed the first time a stroke of the press needs
     * one, from the set centre or the selection when the press began, else (setting the shared centre, with a toast) the
     * block the press began on, {@code aimPos}.
     */
    Symmetry symmetry(ToolContext c, Symmetry.Mode mode, BlockPos aimPos) {
        if (mode == Symmetry.Mode.OFF) {
            return Symmetry.NONE;
        }
        if (pressCentre == null) {
            pressCentre = centre.resolve(Optional.ofNullable(selection)).orElse(null);
        }
        if (pressCentre == null && aimPos != null) {
            centre.set(2 * aimPos.x() + 1, 2 * aimPos.z() + 1);
            pressCentre = new int[] {centre.x2(), centre.z2()};
            c.notify(Notice.of(Notice.Level.INFO, TerrainBrushTool.SYMMETRY_CENTRE_STARTED, SymmetryCentre.format(centre.x2()),
                    SymmetryCentre.format(centre.z2()), services.keyLabel(KeyAction.SET_SYMMETRY_CENTRE)));
        }
        return pressCentre == null ? Symmetry.NONE : around(mode, pressCentre);
    }

    /**
     * The symmetry the world overlay shows for {@code mode}: the press's while pressing; otherwise around the set centre
     * or the selection's, or around the cursor's block {@code aimPos} (where a press would put it). Off for
     * {@link Symmetry.Mode#OFF}.
     */
    Symmetry shown(ToolContext c, Symmetry.Mode mode, boolean pressed, BlockPos aimPos) {
        if (mode == Symmetry.Mode.OFF) {
            return Symmetry.NONE;
        }
        if (pressed && pressCentre != null) {
            return around(mode, pressCentre);
        }
        Optional<int[]> resolved = centre.resolve(pressed ? Optional.ofNullable(selection) : c.selection());
        if (resolved.isPresent()) {
            return around(mode, resolved.get());
        }
        return aimPos == null ? Symmetry.NONE : around(mode, new int[] {2 * aimPos.x() + 1, 2 * aimPos.z() + 1});
    }

    /** {@code mode} around {@code centre} (fitted for Rotate 4); none if the centre lies beyond the dab range. */
    static Symmetry around(Symmetry.Mode mode, int[] centre) {
        return SymmetryCentre.around(mode, centre[0], centre[1]);
    }

    /**
     * The symmetry centre key ({@link SymmetryCentre#keyPressed}): the cursor's block centre, or with Shift the block
     * corner nearest the cursor, becomes the shared centre; the same point again clears it.
     *
     * @param symmetryOn whether the brush has a symmetry mode set (the toast says so otherwise)
     */
    void setCentre(ToolContext c, WorldCursor cursor, boolean symmetryOn) {
        centre.keyPressed(c, cursor, symmetryOn);
    }

    // ---- Material ----

    /**
     * The weighted pattern of a mix's known blocks (duplicates merged, weights added up to the maximum), with
     * {@code seed}; {@code null} after a toast when the mix is empty or none of its blocks is known. A block this game
     * does not know is left out, with a toast naming the first one.
     */
    static Pattern.Weighted palettePattern(ToolContext c, List<SettingDef.WeightedBlock> entries, long seed) {
        if (entries.isEmpty()) {
            c.notify(Notice.of(Notice.Level.WARNING, "sculptory.notice.palette_empty"));
            return null;
        }
        StateSpace states = c.states();
        Map<Integer, Integer> weights = new LinkedHashMap<>();
        String unknown = null;
        for (SettingDef.WeightedBlock entry : entries) {
            int state = states.resolve(entry.block());
            if (state < 0) {
                if (unknown == null) {
                    unknown = entry.block().format();
                }
                continue;
            }
            weights.merge(state, entry.weight(), (a, b) -> Math.min(Pattern.Weighted.MAX_WEIGHT, a + b));
        }
        if (unknown != null) {
            c.notify(Notice.of(Notice.Level.WARNING, "sculptory.notice.unknown_block", unknown));
        }
        if (weights.isEmpty()) {
            return null;
        }
        int[] handles = new int[weights.size()];
        int[] amounts = new int[weights.size()];
        int i = 0;
        for (Map.Entry<Integer, Integer> entry : weights.entrySet()) {
            handles[i] = entry.getKey();
            amounts[i] = entry.getValue();
            i++;
        }
        return new Pattern.Weighted(handles, amounts, seed);
    }

    /**
     * Adds {@code block} to a mix setting with weight 1 (a toast says so, or that it is there already, or that the mix
     * is full at {@value BrushSettings#MAX_PALETTE_ENTRIES} blocks).
     */
    static void addToMix(ToolContext c, SettingDef.WeightedBlocks mix, BlockDescriptor block) {
        SettingsValues values = c.settings();
        List<SettingDef.WeightedBlock> palette = values.get(mix);
        for (SettingDef.WeightedBlock entry : palette) {
            if (entry.block().equals(block)) {
                c.notify(Notice.of(Notice.Level.INFO, "sculptory.notice.palette_has", block.format()));
                return;
            }
        }
        if (palette.size() >= BrushSettings.MAX_PALETTE_ENTRIES) {
            c.notify(Notice.of(Notice.Level.WARNING, "sculptory.notice.palette_full",
                    Integer.toString(BrushSettings.MAX_PALETTE_ENTRIES)));
            return;
        }
        List<SettingDef.WeightedBlock> grown = new ArrayList<>(palette);
        grown.add(new SettingDef.WeightedBlock(block, 1));
        c.updateSettings(values.with(mix, grown));
        c.notify(Notice.of(Notice.Level.INFO, "sculptory.notice.palette_added", block.format()));
    }

    static WorldReader worldOrNull(ToolContext c) {
        try {
            return c.world();
        } catch (IllegalStateException noSession) {
            return null;
        }
    }
}
