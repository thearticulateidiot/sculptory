package dev.sculptory.fabric.client.editor.ui.layout;

import dev.sculptory.fabric.client.editor.ui.Align;
import dev.sculptory.fabric.client.editor.ui.Node;

/** Children left to right; vertically centred by default. */
public final class Row extends FlexContainer {
    public Row() {
        super(true, Align.CENTER);
    }

    public static Row of(Node... children) {
        Row row = new Row();
        row.add(children);
        return row;
    }
}
