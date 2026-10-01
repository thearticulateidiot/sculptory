package dev.sculptory.fabric.client.editor.commands;

import dev.sculptory.fabric.client.editor.input.KeyAction;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * One thing the player can do in the editor, as the menu bar, the command search and the help show it: a stable id,
 * a label (translation key and arguments), its menu, the key that also runs it (shown live from the keymap, so
 * rebinding updates every menu), whether it can run now and why not, what it does, and optionally a check mark
 * (windows, toggles). {@link #run} goes through the same code as the key or button it stands for.
 *
 * <p>A command may be hidden (Undo anyway while nothing is on offer), may wait for a later part of the editor to
 * {@linkplain CommandRegistry#provide provide} its action (hidden until then), or may hold a submenu of
 * {@linkplain #children child commands} (UI size).
 */
public final class Command {
    private static final BooleanSupplier ALWAYS = () -> true;

    private final String id;
    private final String labelKey;
    private final List<String> labelArgs;
    private final CommandMenu menu;
    private final KeyAction key;
    private final Supplier<String> keyText;
    private final String tooltipKey;
    private final Supplier<Availability> availability;
    private final Runnable action;
    private final BooleanSupplier checked;
    private final BooleanSupplier visible;
    private final boolean separatorBefore;
    private final boolean provided;
    private final List<Command> children;

    private Command(Builder builder) {
        this.id = builder.id;
        this.labelKey = builder.labelKey;
        this.labelArgs = List.copyOf(builder.labelArgs);
        this.menu = builder.menu;
        this.key = builder.key;
        this.keyText = builder.keyText;
        this.tooltipKey = builder.tooltipKey;
        this.availability = builder.availability;
        this.action = builder.action;
        this.checked = builder.checked;
        this.visible = builder.visible;
        this.separatorBefore = builder.separatorBefore;
        this.provided = builder.provided;
        this.children = List.copyOf(builder.children);
        if (action == null && !provided && children.isEmpty()) {
            throw new IllegalArgumentException("Command " + id + " does nothing");
        }
    }

    /** A command {@code id} in {@code menu}, labelled by {@code labelKey}. */
    public static Builder builder(String id, CommandMenu menu, String labelKey) {
        return new Builder(id, menu, labelKey);
    }

    public String id() {
        return id;
    }

    public String labelKey() {
        return labelKey;
    }

    public List<String> labelArgs() {
        return labelArgs;
    }

    public CommandMenu menu() {
        return menu;
    }

    /** The keymap action whose chords are shown as this command's key. */
    public Optional<KeyAction> key() {
        return Optional.ofNullable(key);
    }

    /** A key shown instead of a keymap action's (a vanilla key, like the one that leaves the editor). */
    Optional<Supplier<String>> keyText() {
        return Optional.ofNullable(keyText);
    }

    /** A description shown as the item's tooltip while it can run, or empty. */
    public Optional<String> tooltipKey() {
        return Optional.ofNullable(tooltipKey);
    }

    /** Whether it can run now, and why not. */
    public Availability availability() {
        return availability.get();
    }

    /** Whether the command shows a check mark at all. */
    public boolean isCheckable() {
        return checked != null;
    }

    /** Whether the check mark is on (always false for commands without one). */
    public boolean isChecked() {
        return checked != null && checked.getAsBoolean();
    }

    /** Whether it is shown now (before any provider is considered; see {@link CommandRegistry#isVisible}). */
    boolean visibleNow() {
        return visible.getAsBoolean();
    }

    /** A line goes above it in its menu. */
    public boolean separatorBefore() {
        return separatorBefore;
    }

    /** Its action comes from {@link CommandRegistry#provide}; hidden until then. */
    public boolean isProvided() {
        return provided;
    }

    /** The submenu's commands (empty for an ordinary command). */
    public List<Command> children() {
        return children;
    }

    /** The command's own action, if it has one (provided commands and submenus don't). */
    Optional<Runnable> action() {
        return Optional.ofNullable(action);
    }

    @Override
    public String toString() {
        return id;
    }

    /** Builds a {@link Command}. */
    public static final class Builder {
        private final String id;
        private final CommandMenu menu;
        private final String labelKey;
        private List<String> labelArgs = List.of();
        private KeyAction key;
        private Supplier<String> keyText;
        private String tooltipKey;
        private Supplier<Availability> availability = () -> Availability.OK;
        private Runnable action;
        private BooleanSupplier checked;
        private BooleanSupplier visible = ALWAYS;
        private boolean separatorBefore;
        private boolean provided;
        private List<Command> children = List.of();

        private Builder(String id, CommandMenu menu, String labelKey) {
            this.id = Objects.requireNonNull(id);
            this.menu = Objects.requireNonNull(menu);
            this.labelKey = Objects.requireNonNull(labelKey);
        }

        public Builder labelArgs(String... args) {
            this.labelArgs = List.of(args);
            return this;
        }

        /** The keymap action whose chords are shown (live) as the command's key. */
        public Builder key(KeyAction key) {
            this.key = Objects.requireNonNull(key);
            return this;
        }

        /** A key that isn't in the editor keymap (a vanilla binding), shown as given. */
        public Builder keyText(Supplier<String> keyText) {
            this.keyText = Objects.requireNonNull(keyText);
            return this;
        }

        public Builder tooltip(String tooltipKey) {
            this.tooltipKey = Objects.requireNonNull(tooltipKey);
            return this;
        }

        public Builder availability(Supplier<Availability> availability) {
            this.availability = Objects.requireNonNull(availability);
            return this;
        }

        public Builder action(Runnable action) {
            this.action = Objects.requireNonNull(action);
            return this;
        }

        public Builder checked(BooleanSupplier checked) {
            this.checked = Objects.requireNonNull(checked);
            return this;
        }

        public Builder visible(BooleanSupplier visible) {
            this.visible = Objects.requireNonNull(visible);
            return this;
        }

        public Builder separatorBefore() {
            this.separatorBefore = true;
            return this;
        }

        /** The action (and check mark) come later from {@link CommandRegistry#provide}; hidden until then. */
        public Builder provided() {
            this.provided = true;
            return this;
        }

        public Builder children(List<Command> children) {
            this.children = List.copyOf(children);
            return this;
        }

        public Command build() {
            return new Command(this);
        }
    }
}
