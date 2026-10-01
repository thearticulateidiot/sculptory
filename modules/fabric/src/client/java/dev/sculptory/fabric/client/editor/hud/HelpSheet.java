package dev.sculptory.fabric.client.editor.hud;

import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.input.EditorKeymap;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.input.KeyListing;
import dev.sculptory.fabric.client.editor.tool.ToolRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.function.IntFunction;

/**
 * What the F1 key sheet lists: every {@link KeyAction} with its chords from the live keymap, under the Keys window's
 * headings in the same order ({@link KeyListing}), plus the mouse gestures and the player's own vanilla keys that
 * work in the editor, each in the group it belongs to. An unbound action is listed with no keys. Pure; the sheet
 * itself is {@link KeySheet}.
 */
public final class HelpSheet {
    /** Between two keys of one row, as the keymap joins them. */
    private static final String KEY_SEPARATOR = " / ";

    /**
     * One row: the keys (as shown, several joined with " / "; empty when unbound) and what they do.
     *
     * @param action the keymap action, or null for a mouse gesture or vanilla key
     */
    public record Line(String keys, String text, KeyAction action) {
        public Line {
            Objects.requireNonNull(keys);
            Objects.requireNonNull(text);
        }

        /** A row that is not a keymap action. */
        public Line(String keys, String text) {
            this(keys, text, null);
        }

        /** The keys one by one ("Ctrl+Y", "Ctrl+Shift+Z"); empty when unbound. */
        public List<String> keyList() {
            return keys.isEmpty() ? List.of() : List.of(keys.split(" / "));
        }
    }

    /** A titled group of rows. */
    public record Group(KeyAction.Group group, String title, List<Line> lines) {
        public Group {
            Objects.requireNonNull(group);
            Objects.requireNonNull(title);
            lines = List.copyOf(lines);
        }
    }

    /** The player's vanilla keys shown on the sheet, e.g. ("W A S D", "Space", "Left Shift", "B", "T", "/", "E"). */
    public record VanillaKeys(String move, String up, String down, String toggle, String chat, String command,
            String inventory) {}

    private HelpSheet() {}

    /** The sheet with keymap actions named by their labels ("Palette slot 2"). */
    public static List<Group> build(EditorKeymap keymap, Translator tr, VanillaKeys vanilla) {
        return build(keymap, tr, vanilla, slot -> Optional.empty());
    }

    /**
     * The sheet, every group of {@link KeyListing#sections()} in order.
     *
     * @param toolName the name of the tool in palette slot 1-13, to name its key after it ({@link #rowName})
     */
    public static List<Group> build(EditorKeymap keymap, Translator tr, VanillaKeys vanilla,
            IntFunction<Optional<String>> toolName) {
        VanillaKeys named = named(vanilla, tr);
        List<Group> groups = new ArrayList<>();
        for (KeyListing.Section section : KeyListing.sections()) {
            List<Line> lines = new ArrayList<>(before(section.group(), tr, named));
            for (KeyAction action : section.actions()) {
                StringJoiner keys = new StringJoiner(KEY_SEPARATOR);
                keymap.chords(action).forEach(chord -> keys.add(readable(chord.display(), tr)));
                lines.add(new Line(keys.toString(), rowName(action, tr, toolName), action));
            }
            lines.addAll(after(section.group(), tr, named));
            groups.add(new Group(section.group(), tr.translate(KeyListing.groupKey(section.group())), lines));
        }
        return groups;
    }

    /**
     * A key's name as the sheet shows it: the slash key by its name ("Slash", "Ctrl+Slash"), since a bare "/" reads
     * as the sheet's own separator between keys ("T / / / E"). Other names as they are.
     */
    static String readable(String key, Translator tr) {
        if (key.equals("/")) {
            return tr.translate("sculptory.help.key.slash");
        }
        if (key.endsWith("+/")) {
            return key.substring(0, key.length() - 1) + tr.translate("sculptory.help.key.slash");
        }
        return key;
    }

    /** The vanilla keys with the slash key named ({@link #readable}); the movement keys are shown as given. */
    private static VanillaKeys named(VanillaKeys vanilla, Translator tr) {
        return new VanillaKeys(vanilla.move(), readable(vanilla.up(), tr), readable(vanilla.down(), tr),
                readable(vanilla.toggle(), tr), readable(vanilla.chat(), tr), readable(vanilla.command(), tr),
                readable(vanilla.inventory(), tr));
    }

    /**
     * The rows matching what is typed in the sheet's filter box, by what they do or by one of their keys
     * ({@link KeyListing#matches}); groups left without rows are dropped. Nothing typed keeps everything.
     */
    public static List<Group> filter(List<Group> groups, String typed) {
        List<Group> shown = new ArrayList<>();
        for (Group group : groups) {
            List<Line> lines = group.lines().stream()
                    .filter(line -> KeyListing.matches(typed, line.text(), line.keyList()))
                    .toList();
            if (!lines.isEmpty()) {
                shown.add(new Group(group.group(), group.title(), lines));
            }
        }
        return shown;
    }

    /**
     * What a row says an action does: a palette slot's the name of its tool ("Raise"), since the key beside it stands
     * for the slot (the Keys window, where keys are changed, names it "2 · Raise"); any other action's its name in the
     * Keys window ({@link KeyListing#name}).
     */
    private static String rowName(KeyAction action, Translator tr, IntFunction<Optional<String>> toolName) {
        int slot = action.toolSlot();
        Optional<String> tool = slot > 0 ? toolName.apply(slot) : Optional.empty();
        return tool.orElseGet(() -> KeyListing.name(action, tr, toolName));
    }

    /** "1–9, 0, -, =, [" when the tool keys are the defaults, else each slot's keys ("-" for an unbound one). */
    public static String toolKeys(EditorKeymap keymap) {
        boolean defaults = true;
        for (int slot = 1; slot <= ToolRegistry.PALETTE_SLOTS; slot++) {
            KeyAction action = KeyAction.toolSlot(slot);
            if (!keymap.chords(action).equals(action.defaultChords())) {
                defaults = false;
            }
        }
        if (defaults) {
            return "1–9, 0, -, =, [";
        }
        StringJoiner keys = new StringJoiner(" ");
        for (int slot = 1; slot <= ToolRegistry.PALETTE_SLOTS; slot++) {
            String display = keymap.displayFirst(KeyAction.toolSlot(slot));
            keys.add(display.isEmpty() ? "-" : display);
        }
        return keys.toString();
    }

    /** The rows listed above a group's actions: looking and moving, and the Select tool's mouse gestures. */
    private static List<Line> before(KeyAction.Group group, Translator tr, VanillaKeys vanilla) {
        return switch (group) {
            case MOVEMENT -> List.of(
                    new Line(tr.translate("sculptory.help.mouse.right_drag"), tr.translate("sculptory.help.look")),
                    new Line(vanilla.move(), tr.translate("sculptory.help.move")),
                    new Line(vanilla.up() + " / " + vanilla.down(), tr.translate("sculptory.help.fly")));
            case SELECTION -> List.of(
                    new Line(tr.translate("sculptory.help.mouse.left_drag"), tr.translate("sculptory.help.select")),
                    new Line(tr.translate("sculptory.help.mouse.drag_handle"), tr.translate("sculptory.help.resize")),
                    new Line(tr.translate("sculptory.help.mouse.ctrl_drag"), tr.translate("sculptory.help.move_box")),
                    new Line(tr.translate("sculptory.help.mouse.shift_click"), tr.translate("sculptory.help.grow")),
                    new Line(tr.translate("sculptory.help.mouse.click"), tr.translate("sculptory.help.magic")),
                    new Line(tr.translate("sculptory.help.mouse.shift_alt_click"),
                            tr.translate("sculptory.help.magic_add")),
                    new Line(tr.translate("sculptory.help.key.esc"), tr.translate("sculptory.help.magic_stop")));
            default -> List.of();
        };
    }

    /** The rows listed below a group's actions: leaving the editor, the brushes' Alt, the vanilla screens. */
    private static List<Line> after(KeyAction.Group group, Translator tr, VanillaKeys vanilla) {
        return switch (group) {
            case MOVEMENT -> List.of(
                    new Line(vanilla.toggle(), tr.translate("sculptory.help.toggle")),
                    new Line(tr.translate("sculptory.help.key.esc"), tr.translate("sculptory.help.escape")));
            case TOOLS -> List.of(new Line(tr.translate("sculptory.help.key.alt"), tr.translate("sculptory.help.invert")));
            case WINDOWS -> List.of(new Line(vanilla.chat() + " / " + vanilla.command() + " / " + vanilla.inventory(),
                    tr.translate("sculptory.help.suspend")));
            default -> List.of();
        };
    }
}
