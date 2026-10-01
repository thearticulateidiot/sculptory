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
 * starts with it off. A malformed file is set aside ({@link ConfigFile#keepAside}) and the rules
 * start empty; each change of the rules is saved at once.
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
            } catch (IllegalArgumentException malformed) {
                file.keepAside(malformed.getMessage());
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
