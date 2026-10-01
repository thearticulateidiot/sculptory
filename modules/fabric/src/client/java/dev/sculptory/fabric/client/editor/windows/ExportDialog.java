package dev.sculptory.fabric.client.editor.windows;

import dev.sculptory.core.schem.SchematicFormat;
import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.clipboard.ExportFiles;
import dev.sculptory.fabric.client.editor.clipboard.LibraryPaths;
import dev.sculptory.fabric.client.editor.settings.form.SegmentedControl;
import dev.sculptory.fabric.client.editor.ui.PopupLayer;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.widget.TextPrompt;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * "Export…": the file name and the Format choice ({@code .schem}, {@code .litematic}, {@code .nbt}) for a file written
 * into {@code sculptory/exports} in the game folder. The name's extension follows the format as in
 * {@link SaveAssetDialog}; the message under the field shows the file name that will be written (made safe by
 * {@link ExportFiles#sanitize}; a numbered name if it is taken). Enter or Export runs {@code onExport} with the name
 * and the format.
 */
public final class ExportDialog {
    public static final int WIDTH = 220;
    /** The dialog last opened (there is one client); {@link #bounds} checks it is still open. */
    private static PopupLayer.Popup last;

    private ExportDialog() {}

    /** The file name an export of {@code typed} as {@code format} is written as (before numbering). */
    static String fileName(String typed, SchematicFormat format) {
        return ExportFiles.fileName(ExportFiles.sanitize(typed), 0, format.extension());
    }

    /** Where the dialog is while it is open among {@code popups} (the tutorial points at it). */
    public static Optional<Rect> bounds(PopupLayer popups) {
        PopupLayer.Popup open = last;
        if (open == null || !popups.popups().contains(open)) return Optional.empty();
        return Optional.of(open.rect()).filter(rect -> !rect.isEmpty());
    }

    public static void open(UiContext ctx, Rect anchor, Translator tr, String initial, SchematicFormat format,
                            BiConsumer<String, SchematicFormat> onExport) {
        TextPrompt.Texts texts = new TextPrompt.Texts(tr.translate("sculptory.export.title"),
                tr.translate("sculptory.export.placeholder"), tr.translate("sculptory.export.hint"),
                tr.translate("sculptory.export.export"), tr.translate("sculptory.dialog.cancel"));
        @SuppressWarnings("unchecked")
        SegmentedControl<SchematicFormat>[] control = new SegmentedControl[1];
        last = TextPrompt.open(ctx, anchor, WIDTH, texts, LibraryPaths.withFormat(initial, format), 128,
                typed -> {
                    SchematicFormat typedFormat = LibraryPaths.formatOf(typed);
                    if (control[0] != null && typedFormat != null) control[0].setSelected(typedFormat);
                    SchematicFormat chosen = control[0] == null ? format : control[0].selected();
                    String written = fileName(typed, chosen);
                    return new TextPrompt.Check(true, tr.translate("sculptory.export.target", written));
                },
                typed -> onExport.accept(typed, control[0] == null ? format : control[0].selected()),
                (field, recheck) -> {
                    control[0] = SaveAssetDialog.formatControl(tr, format, chosen -> {
                        field.setText(LibraryPaths.withFormat(field.text(), chosen));
                        recheck.run();
                    });
                    return control[0];
                });
    }
}
