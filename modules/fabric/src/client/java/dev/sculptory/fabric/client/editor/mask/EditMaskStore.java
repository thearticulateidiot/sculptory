package dev.sculptory.fabric.client.editor.mask;

import dev.sculptory.core.mask.EditMask;
import dev.sculptory.core.mask.MaskText;
import dev.sculptory.fabric.client.editor.ConfigFile;
import dev.sculptory.fabric.client.session.Subscription;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Keeps the global mask's rules across games in {@code config/sculptory/editor-mask.txt}: one line of
 * {@link MaskText} (an inside rule saved as "inside the selection"). Whether the mask is on is not kept: every game
 * starts with it off. A file a newer Sculptory wrote (another version, or a rule this one doesn't know:
 * {@link MaskText.NewerFormatException}) is reported and left as it is ({@link ConfigFile#keepAsIs}): the rules start
 * empty and their changes last for this session only. Other malformed text is copied aside
 * ({@link ConfigFile#keepAside}) and replaced at once by the empty rules they start with. Each change of the rules is
 * saved at once.
 */
public final class EditMaskStore {
    public static final String FILE_NAME = "editor-mask.txt";

    private final ConfigFile file;
    private final EditMaskModel model;
    private Subscription saving;

    public EditMaskStore(ConfigFile file, EditMaskModel model) {
        this.file = Objects.requireNonNull(file);
        this.model = Objects.requireNonNull(model);
    }

    /** Reads the saved rules into the model (the mask stays off), then saves every later change. */
    public void load() {
        Optional<String> text = file.read();
        if (text.isPresent() && !text.get().isBlank()) {
            try {
                model.setRules(MaskText.decode(text.get().strip()).entries());
            } catch (MaskText.NewerFormatException newer) {
                file.keepAsIs(newer.getMessage() + "; the mask rules last for this session only");
            } catch (IllegalArgumentException malformed) {
                if (file.keepAside(malformed.getMessage()).isPresent()) save(); // at once: not copied at every start
            }
        }
        if (saving == null) saving = model.onChange(this::save);
    }

    /** Writes the rules if they changed. */
    public void save() {
        file.write(text(model) + "\n");
    }

    /** The saved form of the model's rules. */
    static String text(EditMaskModel model) {
        return MaskText.encode(new EditMask(List.copyOf(model.rules()), false));
    }
}
