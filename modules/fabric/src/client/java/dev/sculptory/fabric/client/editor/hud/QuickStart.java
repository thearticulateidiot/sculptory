package dev.sculptory.fabric.client.editor.hud;

import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.input.EditorKeymap;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.ui.Insets;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.TextMeasure;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.layout.Column;
import dev.sculptory.fabric.client.editor.ui.layout.Row;
import dev.sculptory.fabric.client.editor.ui.layout.Spacer;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.editor.ui.widget.Label;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The quick start card: shown in the middle of the screen the first time the editor opens (until it is dismissed; the
 * flag is kept in {@code editor-ui.json}) and from Help > Quick start. Six lines, their keys read from the keymap each
 * time it shows: right-drag to look, the tool keys or the palette, Ctrl+K, F1, Esc and the editor key; and the
 * buttons <b>Start tutorial</b> and <b>Got it</b>. The keys stand in a column as wide as the widest of them,
 * {@link Theme#keyTextGap} left of what they do. The card is as wide as its longest line (within the screen), so no
 * line wraps where there is room. Got it, Enter and Esc hide it and mark it seen; Start tutorial does too, and opens
 * the Tutorial window (the lessons are the card's next step). It does not block the editor: the world, the windows
 * and every key work while it shows, so the player can try what it says.
 */
public final class QuickStart {
    /** Where "seen" is kept. */
    public interface Flag {
        boolean seen();

        void markSeen();
    }

    /** One line: its keys (as bound now) and what they do. */
    public record Line(String keys, String text) {}

    private final TextMeasure text;
    private final EditorKeymap keymap;
    private final Translator tr;
    private final Supplier<HelpSheet.VanillaKeys> vanillaKeys;
    private final Column lines = new Column();
    private final Button gotIt;
    /** Start tutorial: hides the card for good, like Got it, and opens the Tutorial window. */
    private final Button startTutorial;
    private Runnable onStartTutorial = () -> { };
    private final Node node;
    private Flag flag = new Flag() {
        private boolean seen;

        @Override
        public boolean seen() {
            return seen;
        }

        @Override
        public void markSeen() {
            seen = true;
        }
    };
    private List<Line> shownLines = List.of();
    private boolean shown;

    public QuickStart(TextMeasure text, Theme theme, EditorKeymap keymap, Translator translator,
            Supplier<HelpSheet.VanillaKeys> vanillaKeys) {
        this.text = Objects.requireNonNull(text);
        this.keymap = Objects.requireNonNull(keymap);
        this.tr = Objects.requireNonNull(translator);
        this.vanillaKeys = Objects.requireNonNull(vanillaKeys);
        lines.setGap(3);
        gotIt = new Button(tr.translate("sculptory.quick_start.got_it"), this::dismiss);
        gotIt.setStyle(Button.Style.PRIMARY);
        gotIt.setTooltip(tr.translate("sculptory.quick_start.got_it.tooltip"));
        Label title = Label.heading(tr.translate("sculptory.quick_start.title"));
        startTutorial = new Button(tr.translate("sculptory.quick_start.tutorial"), this::startTutorial);
        startTutorial.setTooltip(tr.translate("sculptory.quick_start.tutorial.tooltip"));
        Row buttons = Row.of(startTutorial, Spacer.flexible(), gotIt);
        buttons.setGap(theme.gap);
        Column content = Column.of(title, lines, buttons);
        content.setGap(theme.gap + 2);
        node = new Panel(content, Insets.all(theme.sheetMargin), theme.popupBackground, theme.popupBorder);
    }

    /** The card, for the editor's HUD layer above the windows. */
    public Node node() {
        return node;
    }

    public Button gotItButton() {
        return gotIt;
    }

    public Button startTutorialButton() {
        return startTutorial;
    }

    /** What Start tutorial opens (the Tutorial window). */
    public void setOnStartTutorial(Runnable onStartTutorial) {
        this.onStartTutorial = Objects.requireNonNull(onStartTutorial);
    }

    /** Start tutorial: hides the card for good (the tutorial is its next step) and opens the lessons. */
    private void startTutorial() {
        dismiss();
        onStartTutorial.run();
    }

    /** Where "seen" is kept (the editor's UI settings file); without one it lasts for this session. */
    public void setFlag(Flag flag) {
        this.flag = Objects.requireNonNull(flag);
    }

    public boolean isShown() {
        return shown;
    }

    /** The card's lines as last shown. */
    public List<Line> lines() {
        return shownLines;
    }

    /** Shows the card if it was never dismissed. Returns whether it shows now. */
    public boolean showIfNew() {
        if (!flag.seen()) {
            show();
        }
        return shown;
    }

    /** Shows the card (Help > Quick start), its keys as bound now. */
    public void show() {
        HelpSheet.VanillaKeys vanilla = vanillaKeys.get();
        List<Line> built = new ArrayList<>();
        built.add(new Line(tr.translate("sculptory.help.mouse.right_drag"), tr.translate("sculptory.quick_start.look")));
        built.add(new Line(HelpSheet.toolKeys(keymap), tr.translate("sculptory.quick_start.tools")));
        built.add(new Line(keymap.displayFirst(KeyAction.COMMAND_SEARCH), tr.translate("sculptory.quick_start.find")));
        built.add(new Line(keymap.displayFirst(KeyAction.HELP), tr.translate("sculptory.quick_start.keys")));
        built.add(new Line(tr.translate("sculptory.help.key.esc"), tr.translate("sculptory.quick_start.back")));
        built.add(new Line(vanilla.toggle(), tr.translate("sculptory.quick_start.leave")));
        shownLines = List.copyOf(built);
        lines.clear();
        String unbound = tr.translate("sculptory.keys.unbound");
        int keyWidth = KeyLineRow.keyColumnWidth(text, shownLines.stream().map(Line::keys).toList(), unbound,
                Integer.MAX_VALUE);
        for (Line line : shownLines) {
            lines.add(new KeyLineRow(line.keys(), line.text(), unbound, keyWidth));
        }
        shown = true;
    }

    /** Got it (or Enter, or Esc): hides the card and marks it seen, so it doesn't show by itself again. */
    public void dismiss() {
        shown = false;
        flag.markSeen();
    }

    /** Hides the card without marking it seen (the screenshot tour's clean slate). */
    public void hide() {
        shown = false;
    }
}
