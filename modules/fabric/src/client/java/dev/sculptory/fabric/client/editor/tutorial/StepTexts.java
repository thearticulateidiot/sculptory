package dev.sculptory.fabric.client.editor.tutorial;

import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.hud.HelpSheet;
import dev.sculptory.fabric.client.editor.input.EditorKeymap;
import java.util.Objects;
import java.util.function.Supplier;

/** A step's instruction in words, its keys as the player has them bound now. */
public final class StepTexts {
    public static final String UNBOUND = Lesson.PREFIX + "unbound";

    /** The builder ring key's default, shown until the client says how it is bound. */
    public static final String DEFAULT_RING_KEY = "G";

    private final Translator tr;
    private final EditorKeymap keymap;
    private final Supplier<HelpSheet.VanillaKeys> vanilla;
    private Supplier<String> ringKey = () -> DEFAULT_RING_KEY;

    public StepTexts(Translator translator, EditorKeymap keymap, Supplier<HelpSheet.VanillaKeys> vanilla) {
        this.tr = Objects.requireNonNull(translator);
        this.keymap = Objects.requireNonNull(keymap);
        this.vanilla = Objects.requireNonNull(vanilla);
    }

    /** The builder-mode ring key as bound now (a vanilla key binding the builder client owns). */
    public void setRingKey(Supplier<String> ringKey) {
        this.ringKey = Objects.requireNonNull(ringKey);
    }

    public String text(Step step) {
        Object[] args = step.args().stream().map(this::resolve).toArray();
        return tr.translate(step.textKey(), args);
    }

    /** One argument as shown now. */
    public String resolve(Arg arg) {
        return switch (arg) {
            case Arg.Key key -> {
                String shown = keymap.displayFirst(key.action());
                yield shown.isEmpty() ? tr.translate(UNBOUND) : shown;
            }
            case Arg.Vanilla key -> {
                HelpSheet.VanillaKeys keys = vanilla.get();
                yield switch (key.key()) {
                    case TOGGLE -> keys.toggle();
                    case MOVE -> keys.move();
                    case UP -> keys.up();
                    case DOWN -> keys.down();
                    case RING -> ringKey.get();
                };
            }
            case Arg.Text text -> tr.translate(text.key());
        };
    }
}
