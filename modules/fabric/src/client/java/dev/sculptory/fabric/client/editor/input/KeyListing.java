package dev.sculptory.fabric.client.editor.input;

import dev.sculptory.fabric.client.editor.Translator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntFunction;

/**
 * How editor keys are listed for the player, shared by the Keys window and the F1 key sheet so both show the same
 * headings in the same order, name actions the same way (except that the sheet names a palette key after its tool
 * alone) and find the same actions for the same typed text. Pure: no UI, no keymap state.
 *
 * <p>Actions are listed by {@link KeyAction.Group}, every action of a group together, in group order; within a group,
 * in {@link KeyAction} order. (Grouping by runs in enum order would split a group whose actions were appended later,
 * such as {@link KeyAction#COMMAND_SEARCH} after {@link KeyAction#REMOVE_NODE}, and show its heading twice.)
 */
public final class KeyListing {
    /** One heading's actions. */
    public record Section(KeyAction.Group group, List<KeyAction> actions) {
        public Section {
            Objects.requireNonNull(group);
            actions = List.copyOf(actions);
        }
    }

    private KeyListing() {
    }

    /** Every group in {@link KeyAction.Group} order with all its actions; groups without actions are left out. */
    public static List<Section> sections() {
        List<Section> sections = new ArrayList<>();
        for (KeyAction.Group group : KeyAction.Group.values()) {
            List<KeyAction> actions = new ArrayList<>();
            for (KeyAction action : KeyAction.values()) {
                if (action.group() == group) {
                    actions.add(action);
                }
            }
            if (!actions.isEmpty()) {
                sections.add(new Section(group, actions));
            }
        }
        return List.copyOf(sections);
    }

    /** Every action in listing order: {@link #sections()} flattened. */
    public static List<KeyAction> inGroupOrder() {
        List<KeyAction> ordered = new ArrayList<>(KeyAction.values().length);
        for (Section section : sections()) {
            ordered.addAll(section.actions());
        }
        return List.copyOf(ordered);
    }

    /** The translation key of a group's heading ("Movement", "Tools", ...). */
    public static String groupKey(KeyAction.Group group) {
        return "sculptory.keys.group." + group.name().toLowerCase(Locale.ROOT);
    }

    /**
     * The name an action shows: a palette slot's is its number and the tool in it ("2 · Raise") when
     * {@code toolName} knows that slot's tool, any other action's its label.
     *
     * @param toolName the name of the tool in palette slot 1-13, or empty when unknown
     */
    public static String name(KeyAction action, Translator tr, IntFunction<Optional<String>> toolName) {
        int slot = action.toolSlot();
        if (slot > 0) {
            Optional<String> tool = toolName.apply(slot);
            if (tool.isPresent()) {
                return tr.translate("sculptory.keys.slot", Integer.toString(slot), tool.get());
            }
        }
        return tr.translate(action.labelKey());
    }

    /**
     * Whether an entry with this name and these chords (as shown, "Ctrl+D") matches what was typed in a search box:
     * every word typed starts a word of the name ("undo a" finds Undo anyway), or the typed text, spaces ignored,
     * starts one of its chords ("ctrl+d", "f1"). Case is ignored; nothing typed matches everything.
     */
    public static boolean matches(String typed, String name, List<String> chords) {
        String query = typed.strip().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) {
            return true;
        }
        String compact = query.replace(" ", "");
        for (String chord : chords) {
            if (chord.toLowerCase(Locale.ROOT).replace(" ", "").startsWith(compact)) {
                return true;
            }
        }
        List<String> words = List.of(name.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"));
        for (String token : query.split("\\s+")) {
            if (words.stream().noneMatch(word -> word.startsWith(token))) {
                return false;
            }
        }
        return true;
    }
}
