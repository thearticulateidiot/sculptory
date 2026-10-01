package dev.sculptory.fabric.client.editor.windows;

import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.input.EditorKeymap;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.input.KeyChord;
import dev.sculptory.fabric.client.editor.input.KeyListing;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.layout.Column;
import dev.sculptory.fabric.client.editor.ui.layout.FlowRow;
import dev.sculptory.fabric.client.editor.ui.layout.Row;
import dev.sculptory.fabric.client.editor.ui.layout.Spacer;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.editor.ui.widget.KeyCaptureButton;
import dev.sculptory.fabric.client.editor.ui.widget.Label;
import dev.sculptory.fabric.client.editor.ui.widget.ScrollPane;
import dev.sculptory.fabric.client.editor.ui.widget.SectionHeading;
import dev.sculptory.fabric.client.editor.ui.widget.TextInput;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * The Keys window: every editor action on one line (its name, its chords as buttons, "+", and Reset while it differs
 * from its defaults; the line wraps in a narrow window), group by group ({@link KeyListing#inGroupOrder}), each
 * group under its {@link SectionHeading heading} (a band, with space above it), all under a search box that narrows
 * the list to the actions whose name or key matches what is typed ({@link KeyListing#matches}). A palette slot's key
 * is named after the tool in that slot ("2 · Raise"). Clicking a chord makes it listen ({@link KeyCaptureButton}):
 * the next key press with its modifiers replaces the chord, Backspace or Delete removes it,
 * Esc keeps it. "+" adds a chord (up to {@link #MAX_CHORDS}), "×" removes one, Reset restores an action's defaults
 * and "Reset all keys" every default after the editor's confirm dialog. A mouse button or a scroll turn is taken only
 * for actions whose defaults already use one (the eyedropper, sizes, fly speed): the router only matches those there.
 *
 * <p>Every change is applied to the live keymap at once (the router, tools and tooltips share it) and saved to the
 * key file through the {@link Host}; a failed save toasts and the keys stay in use until the game closes. Chords that
 * collide with the player's own keys, or with each other, are red, with the reason as a tooltip, and are saved all
 * the same: the existing conflict rules apply. So is an action that went without its default key because the key file
 * gives it to another action.
 */
public final class KeysWindow {
    /** What the window needs from the editor. */
    public interface Host {
        /** Saves the keymap to the key file; false when it could not be saved. */
        boolean save(EditorKeymap keymap);

        /** Shows an error to the player. */
        void toast(String message);

        /** Asks before a large change and runs {@code onConfirm} if the player agrees. */
        void confirm(String message, Runnable onConfirm);

        /** The keymap changed: refresh whatever else shows keys. */
        default void changed() {
        }

        /** The name of the tool in palette slot {@code slot} (1-13), to name its key after it; empty when unknown. */
        default Optional<String> toolName(int slot) {
            return Optional.empty();
        }
    }

    /** One action's controls, for tests: the chord buttons, their "×" buttons, the "+" button (null when full), Reset. */
    public record Controls(List<KeyCaptureButton> chords, List<Button> removes, KeyCaptureButton add, Button reset) {
    }

    public static final int PROBLEM_COLOR = 0xFFE5655D;
    /** The most chords one action can have: as many as the largest default set, and at least two. */
    public static final int MAX_CHORDS = maxChords();

    private final EditorKeymap keymap;
    private final Translator tr;
    private final Host host;
    private final String fileHint;
    private final Column column = new Column();
    private final TextInput search;
    private final Node root;
    /** What the search box holds, lower case and trimmed. */
    private String query = "";
    private final Map<KeyAction, Controls> controls = new EnumMap<>(KeyAction.class);
    private Button resetAll;

    public KeysWindow(EditorKeymap keymap, Translator tr, String fileHint, Host host) {
        this.keymap = Objects.requireNonNull(keymap);
        this.tr = Objects.requireNonNull(tr);
        this.fileHint = Objects.requireNonNull(fileHint);
        this.host = Objects.requireNonNull(host);
        column.setGap(1);
        search = new TextInput("", this::filter);
        search.setPlaceholder(tr.translate("sculptory.keys.search"));
        search.setTooltip(tr.translate("sculptory.keys.search.tooltip"));
        // Keeps its height however long the list below it is.
        search.setMinSize(30, Theme.DARK.controlHeight);
        ScrollPane rows = new ScrollPane(column);
        rows.setGrow(1);
        Column content = Column.of(search, rows);
        content.setGap(4);
        root = content;
        rebuild();
    }

    public Node node() {
        return root;
    }

    /** Shows the rows again from the live keymap (its reserved keys are read anew each time the editor opens). */
    public void refresh() {
        rebuild();
    }

    public Controls controls(KeyAction action) {
        return controls.get(action);
    }

    public Button resetAllButton() {
        return resetAll;
    }

    /** The search box above the rows. */
    public TextInput searchInput() {
        return search;
    }

    /**
     * The name an action shows: a palette slot's is its number and the tool in it ("2 · Raise"), any other action's
     * its label.
     */
    public String actionName(KeyAction action) {
        return KeyListing.name(action, tr, host::toolName);
    }

    /** Puts {@code text} in the search box and shows only the actions it matches. */
    public void search(String text) {
        search.setText(text);
        filter(text);
    }

    private void filter(String text) {
        query = text.strip().toLowerCase(Locale.ROOT);
        rebuild();
    }

    /** The actions shown now, in order (all of them while the search box is empty). */
    public List<KeyAction> shownActions() {
        return List.copyOf(controls.keySet());
    }

    /** Whether the action takes chords of that kind: those whose defaults have one, since only those are matched. */
    public static boolean accepts(KeyAction action, KeyChord.Input input) {
        for (KeyChord chord : action.defaultChords()) {
            if (chord.input() == input) {
                return true;
            }
        }
        return false;
    }

    private static int maxChords() {
        int most = 2;
        for (KeyAction action : KeyAction.values()) {
            most = Math.max(most, action.defaultChords().size());
        }
        return most;
    }

    // ---- Changes ----

    private void replace(KeyAction action, int index, KeyChord chord) {
        List<KeyChord> chords = new ArrayList<>(keymap.chords(action));
        chords.set(index, chord);
        apply(action, chords);
    }

    private void remove(KeyAction action, int index) {
        List<KeyChord> chords = new ArrayList<>(keymap.chords(action));
        chords.remove(index);
        apply(action, chords);
    }

    private void add(KeyAction action, KeyChord chord) {
        List<KeyChord> chords = new ArrayList<>(keymap.chords(action));
        if (chords.size() < MAX_CHORDS) {
            chords.add(chord);
        }
        apply(action, chords);
    }

    private void apply(KeyAction action, List<KeyChord> chords) {
        keymap.bind(action, chords);
        commit();
    }

    private void resetAll() {
        host.confirm(tr.translate("sculptory.keys.reset_all.confirm"), () -> {
            keymap.resetToDefaults();
            commit();
        });
    }

    /** Saves the live keymap, tells the editor and shows the window's rows again. */
    private void commit() {
        if (!host.save(keymap)) {
            host.toast(tr.translate("sculptory.keys.save_failed", fileHint));
        }
        host.changed();
        rebuild();
    }

    private static Optional<KeyChord> chord(KeyCaptureButton.Capture capture) {
        try {
            return Optional.of(switch (capture.kind()) {
                case KEY -> KeyChord.key(capture.code(), capture.modifiers());
                case MOUSE -> KeyChord.mouse(capture.code(), capture.modifiers());
                case SCROLL -> KeyChord.scroll(capture.modifiers());
            });
        } catch (IllegalArgumentException unknownKey) {
            return Optional.empty();
        }
    }

    // ---- Building ----

    private void rebuild() {
        column.clear();
        controls.clear();
        KeyAction.Group group = null;
        for (KeyAction action : KeyListing.inGroupOrder()) {
            List<String> chords = keymap.chords(action).stream().map(KeyChord::display).toList();
            if (!KeyListing.matches(query, actionName(action), chords)) {
                continue;
            }
            if (action.group() != group) {
                // A group's heading shows only above an action that matches; after other rows, with space above.
                boolean first = group == null;
                group = action.group();
                column.add(new SectionHeading(tr.translate(KeyListing.groupKey(group)))
                        .setSpaceAbove(first ? 0 : Theme.DARK.headingSpaceAbove));
            }
            addRows(action);
        }
        if (controls.isEmpty()) {
            column.add(Label.dim(tr.translate("sculptory.keys.no_match")));
        }
        Label hint = Label.dim(tr.translate("sculptory.keys.hint"));
        hint.setWrap(true);
        Label footer = Label.dim(tr.translate("sculptory.keys.footer", fileHint));
        footer.setWrap(true);
        resetAll = new Button(tr.translate("sculptory.keys.reset_all"), this::resetAll);
        Column tail = Column.of(hint, footer, Row.of(Spacer.flexible(), resetAll));
        tail.setGap(3);
        column.add(tail);
    }

    private void addRows(KeyAction action) {
        List<KeyChord> chords = keymap.chords(action);
        Optional<String> problem = problem(keymap, action, tr);

        Label name = Label.of(actionName(action));
        name.setGrow(1);
        if (problem.isPresent()) {
            name.setColor(PROBLEM_COLOR);
            name.setTooltip(problem.get());
        }
        Button reset = new Button(tr.translate("sculptory.keys.reset"), () -> apply(action, action.defaultChords()));
        reset.setStyle(Button.Style.FLAT);
        reset.setTooltip(tr.translate("sculptory.keys.reset.tooltip"));
        boolean changed = !chords.equals(action.defaultChords());
        reset.setEnabled(changed);
        reset.setVisible(changed);

        // One line per action: name, chords, +, Reset; wrapping when the window is narrow.
        FlowRow keys = FlowRow.of(name);
        keys.setGap(2);
        List<KeyCaptureButton> chordButtons = new ArrayList<>();
        List<Button> removes = new ArrayList<>();
        for (int i = 0; i < chords.size(); i++) {
            int index = i;
            KeyChord chord = chords.get(i);
            KeyCaptureButton button = capture(action, chord.display(), new KeyCaptureButton.Listener() {
                @Override
                public void captured(KeyCaptureButton.Capture capture) {
                    chord(capture).ifPresent(next -> replace(action, index, next));
                }

                @Override
                public void cleared() {
                    remove(action, index);
                }
            });
            Optional<EditorKeymap.Problem> chordProblem = keymap.problem(action, chord);
            if (chordProblem.isPresent()) {
                button.setTextColor(PROBLEM_COLOR);
                button.setTooltip(problem.orElse(null));
            } else {
                button.setTooltip(tr.translate("sculptory.keys.change.tooltip"));
            }
            Button remove = new Button(tr.translate("sculptory.keys.remove"), () -> remove(action, index));
            remove.setStyle(Button.Style.FLAT);
            remove.setTooltip(tr.translate("sculptory.keys.remove.tooltip"));
            chordButtons.add(button);
            removes.add(remove);
            keys.add(button, remove);
        }
        if (chords.isEmpty()) {
            Label none = Label.dim(tr.translate("sculptory.keys.unbound"));
            if (problem.isPresent()) {
                none.setColor(PROBLEM_COLOR);
                none.setTooltip(problem.get());
            }
            keys.add(none);
        }
        KeyCaptureButton add = null;
        if (chords.size() < MAX_CHORDS) {
            add = capture(action, tr.translate("sculptory.keys.add"), new KeyCaptureButton.Listener() {
                @Override
                public void captured(KeyCaptureButton.Capture capture) {
                    chord(capture).ifPresent(next -> add(action, next));
                }

                @Override
                public void cleared() {
                }
            });
            add.setStyle(Button.Style.FLAT);
            add.setTooltip(tr.translate("sculptory.keys.add.tooltip"));
            keys.add(add);
        }
        keys.add(reset);
        controls.put(action, new Controls(List.copyOf(chordButtons), List.copyOf(removes), add, reset));
        column.add(keys);
    }

    private KeyCaptureButton capture(KeyAction action, String text, KeyCaptureButton.Listener listener) {
        KeyCaptureButton button = new KeyCaptureButton(text, tr.translate("sculptory.keys.listening"), listener);
        button.setAcceptsMouse(accepts(action, KeyChord.Input.MOUSE));
        button.setAcceptsScroll(accepts(action, KeyChord.Input.SCROLL));
        return button;
    }

    /** Why an action's chords don't all work, in plain language, if they don't. */
    public static Optional<String> problem(EditorKeymap keymap, KeyAction action, Translator tr) {
        StringJoiner reasons = new StringJoiner("\n");
        for (KeyChord chord : keymap.yielded(action)) {
            String other = keymap.boundElsewhere(chord, action).map(a -> tr.translate(a.labelKey())).orElse("?");
            reasons.add(tr.translate("sculptory.keys.yielded", chord.display(), other));
        }
        for (KeyChord chord : keymap.chords(action)) {
            keymap.problem(action, chord).ifPresent(problem -> {
                switch (problem) {
                    case EditorKeymap.Problem.Reserved reserved ->
                            reasons.add(tr.translate("sculptory.keys.reserved", chord.display(), reserved.keyName()));
                    case EditorKeymap.Problem.Conflict conflict -> {
                        StringJoiner others = new StringJoiner(", ");
                        conflict.others().forEach(other -> others.add(tr.translate(other.labelKey())));
                        reasons.add(tr.translate(conflict.winner() ? "sculptory.keys.conflict_wins"
                                : "sculptory.keys.conflict_loses", chord.display(), others.toString()));
                    }
                }
            });
        }
        String text = reasons.toString();
        return text.isEmpty() ? Optional.empty() : Optional.of(text);
    }
}
