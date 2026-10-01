package dev.sculptory.fabric.client.editor.windows;

import dev.sculptory.core.schem.SchematicFormat;
import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.clipboard.LibraryPaths;
import dev.sculptory.fabric.client.editor.settings.form.SegmentedControl;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.widget.TextPrompt;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * "Save as asset…": a {@link TextPrompt} with a library path field, checked as the player types with the server's path
 * rules ({@link LibraryPaths}), and a Format choice ({@link #formatControl}: {@code .schem}, {@code .litematic},
 * {@code .nbt}). Choosing a format changes the name's extension, and typing one of those extensions chooses it; a name
 * without an extension gets the chosen one. Enter or Save runs {@code onSave} with the normalized path; Esc or Cancel
 * closes it.
 */
public final class SaveAssetDialog {
    public static final int WIDTH = 220;

    private SaveAssetDialog() {}

    /** Whether the typed text makes a valid library file path. */
    static boolean valid(String typed) {
        return valid(typed, SchematicFormat.SPONGE);
    }

    /** Whether the typed text, with {@code format}'s extension when it has none, makes a valid library file path. */
    static boolean valid(String typed, SchematicFormat format) {
        String path = LibraryPaths.normalizeFile(typed, format.extension());
        return !path.isEmpty() && LibraryPaths.fileProblem(path).isEmpty();
    }

    /** The message under the field: what is wrong with the path, or where it will be saved. */
    static String check(String typed, Translator tr) {
        return check(typed, SchematicFormat.SPONGE, tr);
    }

    static String check(String typed, SchematicFormat format, Translator tr) {
        String path = LibraryPaths.normalizeFile(typed, format.extension());
        if (path.isEmpty()) return tr.translate("sculptory.save.empty");
        Optional<String> problem = LibraryPaths.fileProblem(path);
        return problem.map(p -> tr.translate("sculptory.save.invalid", p))
                .orElseGet(() -> tr.translate("sculptory.save.target", path));
    }

    /** Opens the dialog for a {@code .schem} save. */
    public static void open(UiContext ctx, Rect anchor, Translator tr, String initial, Consumer<String> onSave) {
        open(ctx, anchor, tr, initial, SchematicFormat.SPONGE, onSave);
    }

    /** Opens the dialog with {@code format} chosen ({@code initial} takes its extension). */
    public static void open(UiContext ctx, Rect anchor, Translator tr, String initial, SchematicFormat format,
                            Consumer<String> onSave) {
        TextPrompt.Texts texts = new TextPrompt.Texts(tr.translate("sculptory.save.title"),
                tr.translate("sculptory.save.placeholder"), tr.translate("sculptory.save.hint"),
                tr.translate("sculptory.save.save"), tr.translate("sculptory.dialog.cancel"));
        @SuppressWarnings("unchecked")
        SegmentedControl<SchematicFormat>[] control = new SegmentedControl[1];
        TextPrompt.open(ctx, anchor, WIDTH, texts, LibraryPaths.withFormat(initial, format), 256,
                typed -> {
                    // A typed extension chooses its format.
                    SchematicFormat typedFormat = LibraryPaths.formatOf(typed);
                    if (control[0] != null && typedFormat != null) control[0].setSelected(typedFormat);
                    SchematicFormat chosen = control[0] == null ? format : control[0].selected();
                    return new TextPrompt.Check(valid(typed, chosen), check(typed, chosen, tr));
                },
                typed -> onSave.accept(LibraryPaths.normalizeFile(typed,
                        (control[0] == null ? format : control[0].selected()).extension())),
                (field, recheck) -> {
                    control[0] = formatControl(tr, format, chosen -> {
                        field.setText(LibraryPaths.withFormat(field.text(), chosen));
                        recheck.run();
                    });
                    return control[0];
                });
    }

    /**
     * The Format choice of the save and export dialogs: one segment per format, labelled with its extension, each
     * with a tooltip saying what reads it.
     */
    static SegmentedControl<SchematicFormat> formatControl(Translator tr, SchematicFormat selected,
                                                           Consumer<SchematicFormat> onChange) {
        SegmentedControl<SchematicFormat> control = new SegmentedControl<>(List.of(SchematicFormat.values()), selected,
                SchematicFormat::extension, onChange);
        control.setOptionTooltips(format -> tr.translate("sculptory.format." + format.name().toLowerCase(Locale.ROOT)
                + ".tooltip"));
        control.setTooltip(tr.translate("sculptory.format.tooltip"));
        return control;
    }
}
