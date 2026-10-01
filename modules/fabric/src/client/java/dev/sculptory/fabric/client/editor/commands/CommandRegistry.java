package dev.sculptory.fabric.client.editor.commands;

import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.input.EditorKeymap;
import dev.sculptory.fabric.client.editor.ui.widget.MenuItem;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * Every {@link Command} of the editor in menu order, so the menu bar, the command search and the help show the same
 * names, keys and availability. Keys are read from the live {@link EditorKeymap} each time they are shown. Running a
 * command here checks its availability first and remembers it among the last {@value #RECENT} run (for the command
 * search's empty query; kept for the session only).
 *
 * <p>Commands marked {@linkplain Command.Builder#provided provided} stay hidden until a later part of the editor calls
 * {@link #provide} with their action: the Notifications window and the Quick start card hook in that way.
 */
public final class CommandRegistry {
    /** How many recently run commands are remembered. */
    public static final int RECENT = 8;

    private record Provider(Runnable action, BooleanSupplier checked) {}

    private final EditorKeymap keymap;
    private final Translator tr;
    private final List<Command> commands = new ArrayList<>();
    private final Map<String, Command> byId = new LinkedHashMap<>();
    private final Map<String, Command> parents = new HashMap<>();
    private final Map<String, Provider> providers = new HashMap<>();
    private final Deque<String> recent = new ArrayDeque<>();

    public CommandRegistry(EditorKeymap keymap, Translator translator) {
        this.keymap = Objects.requireNonNull(keymap);
        this.tr = Objects.requireNonNull(translator);
    }

    public Translator translator() {
        return tr;
    }

    // ---- Registration ----

    /** Adds a command at the end of its menu. Ids (children's included) are unique. */
    public Command register(Command command) {
        index(command);
        for (Command child : command.children()) {
            index(child);
            parents.put(child.id(), command);
        }
        commands.add(command);
        return command;
    }

    private void index(Command command) {
        if (byId.putIfAbsent(command.id(), command) != null) {
            throw new IllegalArgumentException("Command already registered: " + command.id());
        }
    }

    /**
     * Supplies the action (and check mark, or null for none) of a command registered as
     * {@linkplain Command.Builder#provided provided}; it shows from now on.
     */
    public void provide(String id, Runnable action, BooleanSupplier checked) {
        Command command = get(id).orElseThrow(() -> new IllegalArgumentException("No command " + id));
        if (!command.isProvided()) {
            throw new IllegalArgumentException("Command " + id + " has its own action");
        }
        providers.put(id, new Provider(Objects.requireNonNull(action), checked));
    }

    public Optional<Command> get(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** The top-level commands, in menu bar order within each menu. */
    public List<Command> commands() {
        return List.copyOf(commands);
    }

    /** The commands of one menu, in order (hidden ones included). */
    public List<Command> menu(CommandMenu menu) {
        return commands.stream().filter(command -> command.menu() == menu).toList();
    }

    // ---- State ----

    /** Shown now: its own condition holds, and a provided command has its provider. */
    public boolean isVisible(Command command) {
        return command.visibleNow() && (!command.isProvided() || providers.containsKey(command.id()));
    }

    public boolean isCheckable(Command command) {
        if (command.isProvided()) {
            Provider provider = providers.get(command.id());
            return provider != null && provider.checked() != null;
        }
        return command.isCheckable();
    }

    public boolean isChecked(Command command) {
        if (command.isProvided()) {
            Provider provider = providers.get(command.id());
            return provider != null && provider.checked() != null && provider.checked().getAsBoolean();
        }
        return command.isChecked();
    }

    /** The command's label in words. */
    public String label(Command command) {
        return tr.translate(command.labelKey(), command.labelArgs());
    }

    /** The key that also runs the command, as bound now ("" when unbound or keyless). */
    public String keyText(Command command) {
        if (command.keyText().isPresent()) {
            return command.keyText().get().get();
        }
        return command.key().map(keymap::displayFirst).orElse("");
    }

    public Availability availability(Command command) {
        return command.availability();
    }

    // ---- Running ----

    /**
     * Runs a command if it is shown and can run now, and remembers it as recently run. Returns its availability: when
     * that says no, nothing ran.
     */
    public Availability run(Command command) {
        if (!isVisible(command)) {
            return Availability.no("sculptory.command.reason.unavailable");
        }
        Availability availability = command.availability();
        if (!availability.enabled()) {
            return availability;
        }
        if (!command.children().isEmpty()) {
            return Availability.no("sculptory.command.reason.unavailable");
        }
        noteRun(command.id());
        Provider provider = providers.get(command.id());
        if (provider != null) {
            provider.action().run();
        } else {
            command.action().orElseThrow().run();
        }
        return availability;
    }

    /** Runs command {@code id}; see {@link #run(Command)}. */
    public Availability run(String id) {
        return get(id).map(this::run).orElse(Availability.no("sculptory.command.reason.unavailable"));
    }

    /** Remembers {@code id} as the most recently run entry (commands, and the search's settings and presets). */
    public void noteRun(String id) {
        recent.remove(id);
        recent.addFirst(id);
        while (recent.size() > RECENT) {
            recent.removeLast();
        }
    }

    /** The ids run most recently, newest first. */
    public List<String> recent() {
        return List.copyOf(recent);
    }

    // ---- Menus ----

    /** The menu's items as they are now: labels, keys, checks, availability, separators. */
    public List<MenuItem> menuItems(CommandMenu menu) {
        return menuItems(menu(menu));
    }

    /** Menu items for {@code list} (hidden commands left out, separators only between shown items). */
    public List<MenuItem> menuItems(List<Command> list) {
        List<MenuItem> items = new ArrayList<>();
        for (Command command : list) {
            if (!isVisible(command)) {
                continue;
            }
            if (command.separatorBefore() && !items.isEmpty() && !items.get(items.size() - 1).isSeparator()) {
                items.add(MenuItem.separator());
            }
            items.add(menuItem(command));
        }
        return items;
    }

    private MenuItem menuItem(Command command) {
        String label = label(command);
        if (!command.children().isEmpty()) {
            return MenuItem.submenu(label, () -> menuItems(command.children()));
        }
        MenuItem item = MenuItem.action(label, keyText(command), () -> run(command)).checked(isChecked(command));
        Availability availability = command.availability();
        if (!availability.enabled()) {
            return item.disabled(availability.reason(tr));
        }
        return command.tooltipKey().map(key -> item.tooltip(tr.translate(key))).orElse(item);
    }

    // ---- Search ----

    /**
     * Every command the search can find, in menu order: each shown command, and each child of a submenu as
     * "UI size: 75%". Availability is read now.
     */
    public List<SearchEntry> searchEntries() {
        List<SearchEntry> entries = new ArrayList<>();
        for (Command command : commands) {
            if (!isVisible(command)) {
                continue;
            }
            if (command.children().isEmpty()) {
                entries.add(entry(command, label(command)));
                continue;
            }
            for (Command child : command.children()) {
                if (isVisible(child)) {
                    entries.add(entry(child, tr.translate("sculptory.search.child", label(command), label(child))));
                }
            }
        }
        return entries;
    }

    private SearchEntry entry(Command command, String name) {
        CommandMenu menu = parents.containsKey(command.id()) ? parents.get(command.id()).menu() : command.menu();
        return new SearchEntry(command.id(), name, tr.translate(menu.titleKey()), keyText(command),
                command.availability(), () -> run(command));
    }
}
