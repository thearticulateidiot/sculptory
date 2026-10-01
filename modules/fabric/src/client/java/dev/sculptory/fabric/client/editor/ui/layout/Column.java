package dev.sculptory.fabric.client.editor.ui.layout;

import dev.sculptory.fabric.client.editor.ui.Align;
import dev.sculptory.fabric.client.editor.ui.Node;

/** Children top to bottom; stretched to the column's width by default. */
public final class Column extends FlexContainer {
    public Column() {
        super(false, Align.STRETCH);
    }

    public static Column of(Node... children) {
        Column column = new Column();
        column.add(children);
        return column;
    }
}
