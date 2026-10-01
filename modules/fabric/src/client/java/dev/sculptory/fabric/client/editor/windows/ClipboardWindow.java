package dev.sculptory.fabric.client.editor.windows;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.transform.Mirror;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.clipboard.ClipboardActions;
import dev.sculptory.fabric.client.editor.clipboard.LibraryPaths;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.layout.Column;
import dev.sculptory.fabric.client.editor.ui.layout.FlowRow;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.editor.ui.widget.Label;
import dev.sculptory.fabric.client.editor.ui.widget.ScrollPane;
import dev.sculptory.fabric.client.session.ClipboardCache;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.SessionNotices;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The Clipboard window: the clipboard's size, block and entity counts and origin, transfers in progress, and Paste,
 * Rotate, Flip, Flip upside down, Save as asset…, Export… (both with a Format choice: .schem, .litematic, .nbt) and
 * Clear. Rotate and the flips turn the placement in progress, or else the next paste. Call {@link #refresh()} every
 * frame while it is open.
 */
public final class ClipboardWindow {
    private final Supplier<Optional<EditorSession>> session;
    private final ClipboardActions actions;
    private final Supplier<UiContext> popups;
    private final Translator tr;
    private final Label size = Label.of("");
    private final Label blocks = Label.dim("");
    private final Label entities = Label.dim("");
    private final Label source = Label.dim("");
    private final Label turned = Label.dim("");
    private final TransferBars transfers;
    private final List<Button> needClipboard;
    private final Node root;

    public ClipboardWindow(Supplier<Optional<EditorSession>> session, ClipboardActions actions, Supplier<UiContext> popups,
            Translator translator) {
        this.session = Objects.requireNonNull(session);
        this.actions = Objects.requireNonNull(actions);
        this.popups = Objects.requireNonNull(popups);
        this.tr = Objects.requireNonNull(translator);
        this.transfers = new TransferBars(tr);
        size.setWrap(true);
        source.setWrap(true);
        turned.setWrap(true);
        Button paste = button("sculptory.clipboard.paste", "sculptory.clipboard.paste.tooltip", actions::paste);
        paste.setStyle(Button.Style.PRIMARY);
        Button rotate = button("sculptory.clipboard.rotate", "sculptory.clipboard.rotate.tooltip", actions::rotate);
        Button flip = button("sculptory.clipboard.flip", "sculptory.clipboard.flip.tooltip", actions::flip);
        Button upsideDown = button("sculptory.clipboard.upside_down", "sculptory.clipboard.upside_down.tooltip",
                actions::flipUpsideDown);
        Button save = button("sculptory.clipboard.save", "sculptory.clipboard.save.tooltip", () -> { });
        save.setOnClick(() -> SaveAssetDialog.open(popups.get(), save.bounds(), tr, suggestedName(), actions.format(),
                actions::saveClipboard));
        Button export = button("sculptory.clipboard.export", "sculptory.clipboard.export.tooltip", () -> { });
        export.setOnClick(() -> ExportDialog.open(popups.get(), export.bounds(), tr, actions.exportName(),
                actions.format(), (name, format) -> actions.exportClipboard(format, name)));
        Button clear = button("sculptory.clipboard.clear", "sculptory.clipboard.clear.tooltip", actions::forget);
        clear.setStyle(Button.Style.DANGER);
        needClipboard = List.of(paste, rotate, flip, upsideDown, save, export, clear);
        Column column = Column.of(
                size,
                blocks,
                entities,
                source,
                turned,
                transfers.node(),
                FlowRow.of(grow(paste), grow(rotate), grow(flip), grow(upsideDown)),
                FlowRow.of(grow(save), grow(export)),
                FlowRow.of(grow(clear)));
        column.setGap(5);
        root = new ScrollPane(column);
        refresh();
    }

    public Node node() {
        return root;
    }

    public void refresh() {
        Optional<ClipboardCache.Entry> current = session.get().flatMap(s -> s.clipboards().current());
        for (Button button : needClipboard) button.setEnabled(current.isPresent());
        if (current.isPresent()) {
            BlockPos dims = current.get().dims();
            size.setText(tr.translate("sculptory.clipboard.size", dims.x() + " × " + dims.y() + " × " + dims.z()));
            blocks.setText(tr.translate("sculptory.clipboard.blocks", SessionNotices.count(current.get().cells())));
            int held = current.get().entities();
            entities.setText(held == 0 ? "" : tr.translate("sculptory.clipboard.entities", SessionNotices.count(held)));
            source.setText(actions.source().isEmpty() ? "" : tr.translate("sculptory.clipboard.source", actions.source()));
        } else {
            size.setText(tr.translate("sculptory.clipboard.none"));
            blocks.setText("");
            entities.setText("");
            source.setText("");
        }
        turned.setText(current.isPresent() ? describe(actions.nextTransform(), tr) : "");
        transfers.update(session.get().map(EditorSession::transfers).orElse(List.of()));
    }

    /** "Next paste mirrored, then turned 90°", "... upside down", or "" for no transform. */
    static String describe(Transform transform, Translator tr) {
        if (transform.isIdentity()) return "";
        String degrees = Integer.toString(transform.quarterTurnsCw() * 90);
        if (transform.upsideDown()) {
            if (transform.horizontal().isIdentity()) return tr.translate("sculptory.clipboard.upside_down_only");
            return transform.mirror() == Mirror.NONE
                    ? tr.translate("sculptory.clipboard.turned_upside_down", degrees)
                    : tr.translate("sculptory.clipboard.turned_mirrored_upside_down", degrees);
        }
        return transform.mirror() == Mirror.NONE
                ? tr.translate("sculptory.clipboard.turned", degrees)
                : tr.translate("sculptory.clipboard.turned_mirrored", degrees);
    }

    private String suggestedName() {
        String from = actions.source();
        if (from.isEmpty() || from.equals("Copy") || from.equals("Cut")) return "my_build.schem";
        return LibraryPaths.stem(from) + ".schem";
    }

    private Button button(String textKey, String tooltipKey, Runnable action) {
        Button button = new Button(tr.translate(textKey), action);
        button.setTooltip(tr.translate(tooltipKey));
        return button;
    }

    private static <T extends Node> T grow(T node) {
        node.setGrow(1);
        return node;
    }
}
