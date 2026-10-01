package dev.sculptory.core.mask;

import dev.sculptory.core.state.StateSpace;
import java.util.List;
import java.util.Objects;

/**
 * The global mask on every edit: a cell may be written only when every entry
 * accepts it (an empty list accepts every cell), and with {@code invertAll} only when that is not so. {@link #NONE}
 * (no entries, not inverted) is the mask switched off. At most {@value #MAX_ENTRIES} entries.
 *
 * <p>{@code invertAll} keeps a legacy brush mask exact: its "Invert mask" was {@code Not(And(...))} over all its
 * parts.
 */
public record EditMask(List<MaskEntry> entries, boolean invertAll) {
    public static final int MAX_ENTRIES = 16;
    public static final EditMask NONE = new EditMask(List.of(), false);

    public EditMask {
        entries = List.copyOf(entries);
        if (entries.size() > MAX_ENTRIES) throw new IllegalArgumentException("A mask of over " + MAX_ENTRIES + " rules");
    }

    /** Whether this is {@link #NONE}: every cell may be written. */
    public boolean isOff() {
        return entries.isEmpty() && !invertAll;
    }

    /**
     * The mask bound to {@code states}, ready to test cells: block sets and tags expanded into state sets. Bind once
     * per job or stroke. {@link #NONE} binds to {@link BoundMask#ALL}.
     *
     * @throws IllegalArgumentException for an {@link MaskRule.Inside} over a {@code Region.Uploaded} (a wire reference
     *     the server resolves to its cells first)
     */
    public BoundMask bind(StateSpace states) {
        Objects.requireNonNull(states);
        return isOff() ? BoundMask.ALL : new BoundRules(this, states);
    }
}
