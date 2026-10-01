package dev.sculptory.fabric.client.editor.ui.window;

import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.Size;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Declares a window: a stable id (the key in the saved layout), a translation key for the title,
 * where it opens by default, its minimum size, and a supplier that builds its content.
 *
 * <pre>{@code
 * WindowSpec spec = WindowSpec.builder("tool_settings", "sculptory.window.tool_settings",
 *                 () -> new ScrollPane(SettingsForm.build(...)))
 *         .anchor(Corner.TOP_RIGHT, 6, 24)   // 6 px from the right edge, 24 px from the top
 *         .size(180, 220)
 *         .minSize(120, 60)
 *         .build();
 * }</pre>
 *
 * @param offsetX distance from the anchor corner to the window's nearest vertical edge
 * @param offsetY distance from the anchor corner to the window's nearest horizontal edge
 * @param content builds the window's content; called when the window is first shown and after
 *                {@link Window#rebuildContent()}
 */
public record WindowSpec(
        String id,
        String titleKey,
        Corner anchor,
        int offsetX,
        int offsetY,
        Size size,
        Size minSize,
        Supplier<? extends Node> content,
        boolean openByDefault,
        boolean closable,
        boolean collapsible,
        boolean resizable) {

    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]{1,64}");

    public WindowSpec {
        Objects.requireNonNull(id, "id");
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Window id must match [a-z0-9_.-]{1,64}: " + id);
        }
        Objects.requireNonNull(titleKey, "titleKey");
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(content, "content");
        minSize = new Size(Math.max(40, minSize.width()), Math.max(20, minSize.height()));
        size = new Size(Math.max(minSize.width(), size.width()), Math.max(minSize.height(), size.height()));
    }

    public static Builder builder(String id, String titleKey, Supplier<? extends Node> content) {
        return new Builder(id, titleKey, content);
    }

    public static final class Builder {
        private final String id;
        private final String titleKey;
        private final Supplier<? extends Node> content;
        private Corner anchor = Corner.TOP_LEFT;
        private int offsetX = 8;
        private int offsetY = 8;
        private Size size = new Size(180, 160);
        private Size minSize = new Size(80, 40);
        private boolean openByDefault = true;
        private boolean closable = true;
        private boolean collapsible = true;
        private boolean resizable = true;

        private Builder(String id, String titleKey, Supplier<? extends Node> content) {
            this.id = id;
            this.titleKey = titleKey;
            this.content = content;
        }

        /** Default position: {@code offsetX}/{@code offsetY} pixels in from {@code corner}. */
        public Builder anchor(Corner corner, int offsetX, int offsetY) {
            this.anchor = corner;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            return this;
        }

        public Builder size(int width, int height) {
            this.size = new Size(width, height);
            return this;
        }

        public Builder minSize(int width, int height) {
            this.minSize = new Size(width, height);
            return this;
        }

        public Builder openByDefault(boolean open) {
            this.openByDefault = open;
            return this;
        }

        public Builder closable(boolean closable) {
            this.closable = closable;
            return this;
        }

        public Builder collapsible(boolean collapsible) {
            this.collapsible = collapsible;
            return this;
        }

        public Builder resizable(boolean resizable) {
            this.resizable = resizable;
            return this;
        }

        public WindowSpec build() {
            return new WindowSpec(id, titleKey, anchor, offsetX, offsetY, size, minSize, content,
                    openByDefault, closable, collapsible, resizable);
        }
    }
}
