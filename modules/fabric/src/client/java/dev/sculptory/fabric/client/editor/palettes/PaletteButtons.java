package dev.sculptory.fabric.client.editor.palettes;

import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.clipboard.LibraryPaths;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.layout.Row;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.editor.ui.widget.TextPrompt;
import dev.sculptory.fabric.client.editor.windows.ToolSettingsWindow;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.protocol.v2.Features;
import dev.sculptory.server.engine.Perm;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The Save palette… and Load palette… buttons of a tool's settings (Paint, Palette Paint, Scatter). Save asks for a
 * library path in a {@link TextPrompt} (checked as it is typed with the server's rules; {@code .palette.json} is added;
 * without {@code library.write} the server keeps it in the player's own folder), Load opens the {@link PalettePicker}.
 * Both are off while the server offers no library or the player may not use it ({@code clipboard}).
 */
public final class PaletteButtons {
    public static final int DIALOG_WIDTH = 220;

    private final PaletteActions actions;
    private final Supplier<Optional<EditorSession>> session;
    private final Supplier<UiContext> popups;
    private final Translator tr;

    public PaletteButtons(PaletteActions actions, Supplier<Optional<EditorSession>> session, Supplier<UiContext> popups,
                          Translator translator) {
        this.actions = Objects.requireNonNull(actions);
        this.session = Objects.requireNonNull(session);
        this.popups = Objects.requireNonNull(popups);
        this.tr = Objects.requireNonNull(translator);
    }

    /** The row for {@code tool}: its buttons follow the session each time {@link Buttons#refresh} is called. */
    public Buttons row(ToolId tool) {
        return new Buttons(tool);
    }

    /** A Tool Settings panel holding just the row (Paint and Palette Paint, whose settings are all generated). */
    public ToolSettingsWindow.Panel panel(ToolId tool) {
        return new ToolSettingsWindow.Panel() {
            private Buttons shown;

            @Override
            public Node build() {
                shown = row(tool);
                shown.refresh();
                return shown.node();
            }

            @Override
            public void refresh() {
                if (shown != null) shown.refresh();
            }
        };
    }

    /** Whether the session may save and load palettes: the library offered and {@code clipboard} granted. */
    static boolean available(Optional<EditorSession> session) {
        return session.map(s -> s.capabilities().features().has(Features.LIBRARY)
                && s.permissions().has(Perm.CLIPBOARD)).orElse(false);
    }

    /** The two buttons of one tool. */
    public final class Buttons {
        private final ToolId tool;
        private final Button save;
        private final Button load;
        private final Row row;

        private Buttons(ToolId tool) {
            if (!actions.supports(tool)) throw new IllegalArgumentException("No palettes for " + tool);
            this.tool = tool;
            save = new Button(tr.translate("sculptory.palette.save"), null);
            save.setTooltip(tr.translate("sculptory.palette.save.tooltip"));
            save.setOnClick(this::promptSave);
            save.setGrow(1);
            load = new Button(tr.translate("sculptory.palette.load"), null);
            load.setTooltip(tr.translate("sculptory.palette.load.tooltip"));
            load.setOnClick(this::pick);
            load.setGrow(1);
            row = Row.of(save, load);
            row.setGap(3);
        }

        public Node node() {
            return row;
        }

        public Button saveButton() {
            return save;
        }

        public Button loadButton() {
            return load;
        }

        /** Enables the buttons while palettes can be used. */
        public void refresh() {
            boolean on = available(session.get());
            save.setEnabled(on);
            load.setEnabled(on);
        }

        private void promptSave() {
            // Nothing to save is said at once, before any path is asked for.
            if (actions.capture(tool).isEmpty()) return;
            TextPrompt.Texts texts = new TextPrompt.Texts(tr.translate("sculptory.palette.save.title"),
                    tr.translate("sculptory.palette.save.placeholder"),
                    tr.translate("sculptory.palette.save.hint"),
                    tr.translate("sculptory.palette.save.submit"), tr.translate("sculptory.dialog.cancel"));
            TextPrompt.open(popups.get(), save.bounds(), DIALOG_WIDTH, texts, actions.suggestedPath(),
                    LibraryPaths.MAX_TYPED, typed -> check(typed, tr),
                    typed -> actions.save(tool, LibraryPaths.normalizePalette(typed)));
        }

        private void pick() {
            PalettePicker.open(popups.get(), load.bounds(), session, tr, actions.folder(),
                    path -> actions.load(tool, path));
        }
    }

    /** The line under the save prompt's field: where the palette goes, or what is wrong with the path. */
    static TextPrompt.Check check(String typed, Translator tr) {
        String path = LibraryPaths.normalizePalette(typed);
        if (path.isEmpty()) return new TextPrompt.Check(false, tr.translate("sculptory.palette.save.empty"));
        Optional<String> problem = LibraryPaths.paletteProblem(path);
        return problem.map(p -> new TextPrompt.Check(false, tr.translate("sculptory.palette.save.invalid", p)))
                .orElseGet(() -> new TextPrompt.Check(true, tr.translate("sculptory.palette.save.target", path)));
    }
}
