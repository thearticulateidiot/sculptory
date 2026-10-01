package dev.sculptory.fabric.client.editor.mask;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.mask.BoundMask;
import dev.sculptory.core.mask.EditMask;
import dev.sculptory.core.mask.MaskEntry;
import dev.sculptory.core.mask.MaskRule;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.fabric.client.editor.Listeners;
import dev.sculptory.fabric.client.session.Subscription;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The player's global mask on the client: its rules as the Mask window shows them,
 * whether it is on (the Mask chip, Ctrl+M), and the mask every edit is sent under ({@link #effective()}). One per game
 * client ({@link #global()}): the editor, builder mode, the ghosts and the session's sync all read it. The rules are
 * saved ({@link EditMaskStore}); every game starts with the mask off.
 *
 * <p><b>Inside the selection.</b> The rule is kept as "inside whatever is selected" ({@link #insideSelection()}); the
 * effective mask names the selection at the time. With nothing selected it matches no cell (so Not inside the
 * selection matches every cell): it becomes {@code Height(MAX, MAX)}, a rule no cell passes.
 *
 * <p>Client thread only.
 */
public final class EditMaskModel {
    /** What an "inside the selection" rule holds before the selection is put in: a marker, never sent. */
    public static final Region SELECTION = new Region.Cuboid(Box.of(new BlockPos(0, 0, 0)));
    /** The rule an inside rule becomes while nothing is selected: no cell has this height. */
    static final MaskRule NO_SELECTION = new MaskRule.Height(Integer.MAX_VALUE, Integer.MAX_VALUE);

    private static final EditMaskModel GLOBAL = new EditMaskModel();

    private final Listeners<Runnable> listeners = new Listeners<>();
    private List<MaskEntry> rules = List.of();
    private boolean on;
    private Supplier<Optional<Region>> selection = Optional::empty;
    /** Bumped at every change to the effective mask (a toggle, the rules, the selection under an inside rule). */
    private long revision;
    private EditMask effective = EditMask.NONE;
    private long effectiveFor = -1;
    private BoundMask bound;
    private EditMask boundFor;
    private StateSpace boundStates;

    public EditMaskModel() {}

    /** The game client's mask. */
    public static EditMaskModel global() {
        return GLOBAL;
    }

    /** A rule "inside the selection" (whatever is selected when an edit is made). */
    public static MaskRule.Inside insideSelection() {
        return new MaskRule.Inside(SELECTION);
    }

    /** Whether the mask is switched on (it applies only with at least one rule). */
    public boolean on() {
        return on;
    }

    /** Whether the mask applies to edits now: on, with rules. */
    public boolean active() {
        return on && !rules.isEmpty();
    }

    public void setOn(boolean on) {
        if (this.on == on) return;
        this.on = on;
        changed();
    }

    /** Switches the mask on or off; returns whether it is on now. */
    public boolean toggle() {
        setOn(!on);
        return on;
    }

    /** The rules, in the Mask window's order; an inside rule holds {@link #SELECTION}. */
    public List<MaskEntry> rules() {
        return rules;
    }

    /**
     * Replaces the rules (at most {@value EditMask#MAX_ENTRIES}); an inside rule's region is taken as "the selection".
     */
    public void setRules(List<MaskEntry> rules) {
        List<MaskEntry> normal = new ArrayList<>(rules.size());
        for (MaskEntry entry : rules) {
            normal.add(entry.rule() instanceof MaskRule.Inside
                    ? new MaskEntry(insideSelection(), entry.not()) : Objects.requireNonNull(entry));
        }
        new EditMask(normal, false); // checks the count
        if (normal.equals(this.rules)) return;
        this.rules = List.copyOf(normal);
        changed();
    }

    /** Whether an inside rule is among the rules (the effective mask then follows the selection). */
    public boolean usesSelection() {
        for (MaskEntry entry : rules) {
            if (entry.rule() instanceof MaskRule.Inside) return true;
        }
        return false;
    }

    /** Where the selection is read from ({@code EditorContext::selectionRegion}); read only while an inside rule is on. */
    public void setSelectionSource(Supplier<Optional<Region>> source) {
        this.selection = Objects.requireNonNull(source);
        selectionChanged();
    }

    /** The selection changed: an inside rule now names the new one. */
    public void selectionChanged() {
        if (active() && usesSelection()) changed();
    }

    /**
     * The mask edits are sent under: {@link EditMask#NONE} while off or without rules, else the rules with every inside
     * rule naming the current selection (or matching nothing while none is selected; see the class comment).
     */
    public EditMask effective() {
        if (!active()) return EditMask.NONE;
        if (effectiveFor == revision) return effective;
        List<MaskEntry> entries = new ArrayList<>(rules.size());
        Optional<Region> selected = usesSelection() ? selection.get() : Optional.empty();
        for (MaskEntry entry : rules) {
            if (entry.rule() instanceof MaskRule.Inside) {
                entries.add(new MaskEntry(selected.<MaskRule>map(MaskRule.Inside::new).orElse(NO_SELECTION), entry.not()));
            } else {
                entries.add(entry);
            }
        }
        effective = new EditMask(entries, false);
        effectiveFor = revision;
        return effective;
    }

    /** Whether an inside rule is on while nothing is selected (it then matches no cell). */
    public boolean insideWithoutSelection() {
        return active() && usesSelection() && selection.get().isEmpty();
    }

    /** The effective mask bound to {@code states}, bound again only when it or the state space changes. */
    public BoundMask bound(StateSpace states) {
        EditMask mask = effective();
        if (mask.isOff()) return BoundMask.ALL;
        if (bound == null || boundStates != states || !mask.equals(boundFor)) {
            bound = mask.bind(states);
            boundFor = mask;
            boundStates = states;
        }
        return bound;
    }

    /** Bumped at every change of the effective mask. */
    public long revision() {
        return revision;
    }

    /** Runs {@code listener} after every change of the rules, the switch or (under an inside rule) the selection. */
    public Subscription onChange(Runnable listener) {
        return listeners.add(listener);
    }

    private void changed() {
        revision++;
        listeners.fire(Runnable::run);
    }
}
