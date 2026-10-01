package dev.sculptory.fabric.client.editor.ui.layout;

import dev.sculptory.fabric.client.editor.ui.Node;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A node with an editable list of children. */
public abstract class Container extends Node {
    private final List<Node> children = new ArrayList<>();
    private final List<Node> view = Collections.unmodifiableList(children);

    public Container add(Node... nodes) {
        for (Node node : nodes) {
            children.add(adopt(node));
        }
        return this;
    }

    public Container add(List<? extends Node> nodes) {
        for (Node node : nodes) {
            children.add(adopt(node));
        }
        return this;
    }

    public void remove(Node node) {
        if (children.remove(node)) {
            release(node);
        }
    }

    public void clear() {
        for (Node child : children) {
            release(child);
        }
        children.clear();
    }

    @Override
    public List<Node> children() {
        return view;
    }

    /** Children that take part in layout (the visible ones). */
    protected final List<Node> visibleChildren() {
        List<Node> out = new ArrayList<>(children.size());
        for (Node child : children) {
            if (child.isVisible()) {
                out.add(child);
            }
        }
        return out;
    }
}
