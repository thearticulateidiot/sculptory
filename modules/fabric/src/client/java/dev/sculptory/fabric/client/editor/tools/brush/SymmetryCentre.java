package dev.sculptory.fabric.client.editor.tools.brush;

import dev.sculptory.core.Box;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.fabric.client.editor.tool.Modifiers;
import dev.sculptory.fabric.client.editor.tool.ToolContext;
import dev.sculptory.fabric.client.editor.tool.WorldCursor;
import dev.sculptory.fabric.client.session.Notice;
import java.util.Optional;

/**
 * The symmetry centre the brushes, the Select tool and the Place tool share (the six terrain brushes, the Shape brush,
 * and the Select operations and placements), in half blocks
 * ({@code x2}, {@code z2}, as {@link Symmetry} takes them): the one set with the "Set symmetry centre" key, otherwise
 * (for the brushes) the selection's centre. It is a place in the world, like the selection, so it lives in memory for
 * the session and is not part of presets (a preset keeps which symmetry a tool uses, not where). With neither a set
 * centre nor a selection, a brush press sets it where it begins ({@link BrushPress}); a Select or Place operation with
 * a mode on needs the set centre ({@link #forOp}). Client thread only.
 */
public final class SymmetryCentre {
    /** The Gradient pattern's line, the other place in the world these tools share. */
    private final GradientLine gradientLine = new GradientLine();
    private boolean set;
    private int x2;
    private int z2;

    /** The Gradient pattern's line, shared by Palette Paint, the Shape brush and Fill as this centre is. */
    public GradientLine gradientLine() {
        return gradientLine;
    }

    public boolean isSet() {
        return set;
    }

    public int x2() {
        return x2;
    }

    public int z2() {
        return z2;
    }

    public void set(int x2, int z2) {
        this.set = true;
        this.x2 = x2;
        this.z2 = z2;
    }

    /** Forgets the set centre: the brushes follow the selection's centre again. */
    public void clear() {
        set = false;
        x2 = 0;
        z2 = 0;
    }

    /** The centre {@code {x2, z2}}: the set one, else the selection's centre, else empty. */
    public Optional<int[]> resolve(Optional<Box> selection) {
        if (set) return Optional.of(new int[] {x2, z2});
        return selection.map(SymmetryCentre::centreOf);
    }

    /**
     * The symmetry a Select operation or a placement runs with: {@link Symmetry#NONE} for {@link Symmetry.Mode#OFF},
     * {@code mode} around the set centre (fitted for Rotate 4), or empty when no centre is set (the op is refused: a
     * mirror about the selection's own centre would be a no-op, so there is no fallback).
     */
    public Optional<Symmetry> forOp(Symmetry.Mode mode) {
        if (mode == Symmetry.Mode.OFF) return Optional.of(Symmetry.NONE);
        if (!set) return Optional.empty();
        return Optional.of(around(mode, x2, z2));
    }

    /** A box's centre in half blocks: a block centre along an odd side, a block edge along an even one. */
    public static int[] centreOf(Box box) {
        return new int[] {box.min().x() + box.max().x() + 1, box.min().z() + box.max().z() + 1};
    }

    /**
     * The centre {@code mode} uses: Rotate 4 needs both coordinates on block centres or both on block edges, so when
     * they differ the one on an edge moves half a block toward negative, onto a block centre. Other modes use the centre
     * as it is.
     */
    public static int[] fitted(Symmetry.Mode mode, int x2, int z2) {
        if (mode != Symmetry.Mode.ROTATE_4 || ((x2 ^ z2) & 1) == 0) return new int[] {x2, z2};
        return (x2 & 1) == 0 ? new int[] {x2 - 1, z2} : new int[] {x2, z2 - 1};
    }

    /** {@code mode} around ({@code x2}, {@code z2}) (fitted for Rotate 4); none if the centre lies beyond the range. */
    public static Symmetry around(Symmetry.Mode mode, int x2, int z2) {
        int[] fitted = fitted(mode, x2, z2);
        try {
            return new Symmetry(mode, fitted[0], fitted[1]);
        } catch (IllegalArgumentException outOfRange) {
            return Symmetry.NONE;
        }
    }

    /**
     * The symmetry centre key: the cursor's block centre, or with Shift the block corner nearest the cursor, becomes
     * the shared centre; the same point again clears it (the brushes then follow the selection's centre). A toast says
     * what happened.
     *
     * @param symmetryOn whether the active tool has a symmetry mode set (the toast says so otherwise)
     */
    public void keyPressed(ToolContext c, WorldCursor cursor, boolean symmetryOn) {
        if (cursor == null || cursor.missed()) {
            c.notify(Notice.of(Notice.Level.INFO, TerrainBrushTool.SYMMETRY_CENTRE_AIM));
            return;
        }
        int x2, z2;
        if (Modifiers.shift(c.modifiers())) {
            x2 = 2 * (int) Math.round(cursor.hitX());
            z2 = 2 * (int) Math.round(cursor.hitZ());
        } else {
            x2 = 2 * cursor.pos().x() + 1;
            z2 = 2 * cursor.pos().z() + 1;
        }
        if (set && this.x2 == x2 && this.z2 == z2) {
            clear();
            c.notify(Notice.of(Notice.Level.INFO, TerrainBrushTool.SYMMETRY_CENTRE_CLEARED));
            return;
        }
        set(x2, z2);
        c.notify(Notice.of(Notice.Level.INFO, symmetryOn ? TerrainBrushTool.SYMMETRY_CENTRE_SET
                : TerrainBrushTool.SYMMETRY_CENTRE_SET_OFF, format(x2), format(z2)));
    }

    /** A half-block coordinate as the player reads it: "12", "12.5" or "-3.5". */
    public static String format(int half) {
        return (half & 1) == 0 ? Integer.toString(half / 2) : (half < 0 ? "-" : "") + Math.abs(half / 2) + ".5";
    }
}
