package dev.sculptory.fabric.client.editor.ui;

import java.util.ArrayList;
import java.util.List;

/** Keyboard focus order: focusable, visible, enabled nodes in tree (reading) order. */
public final class FocusTraversal {
    private FocusTraversal() {
    }

    /** Focusable nodes under {@code root} in pre-order. Hidden or disabled subtrees are skipped. */
    public static List<Node> focusables(Node root) {
        List<Node> out = new ArrayList<>();
        if (root != null) {
            collect(root, out);
        }
        return out;
    }

    private static void collect(Node node, List<Node> out) {
        if (!node.isVisible() || !node.isEnabled()) {
            return;
        }
        if (node.isFocusable()) {
            out.add(node);
        }
        for (Node child : node.children()) {
            collect(child, out);
        }
    }

    /** The node after {@code current}, wrapping; the first node if {@code current} isn't in the order. */
    public static Node next(Node root, Node current) {
        List<Node> order = focusables(root);
        if (order.isEmpty()) {
            return null;
        }
        int index = order.indexOf(current);
        return order.get(index < 0 ? 0 : (index + 1) % order.size());
    }

    /** The node before {@code current}, wrapping; the last node if {@code current} isn't in the order. */
    public static Node previous(Node root, Node current) {
        List<Node> order = focusables(root);
        if (order.isEmpty()) {
            return null;
        }
        int index = order.indexOf(current);
        return order.get(index < 0 ? order.size() - 1 : (index - 1 + order.size()) % order.size());
    }
}
