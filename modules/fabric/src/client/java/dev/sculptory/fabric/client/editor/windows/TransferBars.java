package dev.sculptory.fabric.client.editor.windows;

import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.layout.Column;
import dev.sculptory.fabric.client.editor.ui.layout.Row;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.editor.ui.widget.ProgressBar;
import dev.sculptory.fabric.client.session.Transfer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Progress bars for previews, exports and uploads in progress, each with a cancel button. Rows are rebuilt when the
 * set of transfers changes and updated in place otherwise. Call {@link #update} every frame.
 */
public final class TransferBars {
    private final Translator tr;
    private final Column column = new Column();
    private final Map<Transfer<?>, Row> rows = new IdentityHashMap<>();
    private final Map<Transfer<?>, ProgressBar> bars = new IdentityHashMap<>();
    private List<Transfer<?>> shown = List.of();

    public TransferBars(Translator translator) {
        this.tr = Objects.requireNonNull(translator);
        column.setGap(2);
    }

    public Node node() {
        return column;
    }

    public boolean isEmpty() {
        return shown.isEmpty();
    }

    public void update(List<Transfer<?>> transfers) {
        if (!sameTransfers(transfers)) {
            shown = new ArrayList<>(transfers);
            column.clear();
            rows.clear();
            bars.clear();
            for (Transfer<?> transfer : shown) {
                ProgressBar bar = new ProgressBar();
                bar.setGrow(1);
                Button cancel = new Button("×", transfer::cancel);
                cancel.setStyle(Button.Style.FLAT);
                cancel.setTooltip(tr.translate("sculptory.transfer.cancel"));
                Row row = Row.of(bar, cancel);
                row.setGap(2);
                rows.put(transfer, row);
                bars.put(transfer, bar);
                column.add(row);
            }
        }
        for (Transfer<?> transfer : shown) {
            ProgressBar bar = bars.get(transfer);
            if (transfer.totalBytes() <= 0) {
                bar.setIndeterminate(true);
            } else {
                bar.setProgress(transfer.progress());
            }
            bar.setText(describe(transfer, tr));
        }
    }

    private boolean sameTransfers(List<Transfer<?>> transfers) {
        if (transfers.size() != shown.size()) return false;
        for (int i = 0; i < transfers.size(); i++) {
            if (transfers.get(i) != shown.get(i)) return false;
        }
        return true;
    }

    /** "Upload house.schem  45%", "Preview trees/oak.schem  waiting". */
    public static String describe(Transfer<?> transfer, Translator tr) {
        String kind = tr.translate("sculptory.transfer.kind." + transfer.kind().name().toLowerCase(Locale.ROOT));
        String state = transfer.totalBytes() <= 0
                ? tr.translate("sculptory.transfer.waiting")
                : Math.round(transfer.progress() * 100) + "%";
        return kind + " " + transfer.label() + "  " + state;
    }
}
