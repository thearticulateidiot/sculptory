package dev.sculptory.fabric.client.editor.presets;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.fabric.client.editor.EditorContext;
import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.editor.settings.Validation;
import dev.sculptory.fabric.client.editor.tool.Tool;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.Notice;
import dev.sculptory.protocol.v2.Limits;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * Named presets per tool, behind the Tool Settings window's preset row. Each tool has its own list ({@link ToolId})
 * and remembers the preset last selected; "Default" ({@code ""}) is the tool's built-in defaults.
 *
 * <p><b>Applying</b> (selecting a preset) replaces the tool's settings through {@link EditorContext#updateSettings},
 * so the window, prediction and validation follow as for any other change, and replaces the tool's extras
 * ({@link PresetExtra}). {@link PresetResolver} fits the saved values to this game and server; a mask or filter
 * section it can't apply exactly is left as the tool has it, so a preset never widens where an edit applies. What it
 * had to change is toasted once per kind, naming the preset; the preset itself is never rewritten because of that, and
 * shows as "(modified)" while a section of it is withheld.
 *
 * <p><b>Saving</b>: Save overwrites the selected preset with the current settings, Save as adds one; Save, Save as,
 * Rename, Delete and a new selection write the file at once. When a save fails the lists go back to what the file
 * holds (a new selection stays, only its remembering failed). When the file couldn't be used, or saving can't go on,
 * presets are read-only ({@link #access()}). Presets are not part of the world history (no undo). Client thread only.
 */
public final class Presets {
    /** Whether presets can be saved, and if not, why. */
    public enum Access {
        WRITABLE,
        /** The file had another version or didn't parse; it is left unchanged. */
        FILE_UNUSABLE,
        /** A save found the file changed outside the game, or its outcome was uncertain. */
        SAVING_STOPPED
    }

    /** How many names a toast lists before "…". */
    private static final int LISTED = 4;

    private final PresetStore store;
    private final EditorContext ctx;
    private final Translator tr;
    private final Predicate<BlockDescriptor> blockAvailable;
    private final Map<ToolId, Map<String, PresetExtra>> extras = new HashMap<>();
    private final Map<ToolId, Resolution> resolutions = new HashMap<>();
    private PresetBook book = PresetBook.EMPTY;
    private Access access = Access.WRITABLE;
    private boolean restored;
    private int version;

    /** What the selected preset gives now; reused while nothing it depends on changes. */
    private record Resolution(String selected, int version, Limits limits, EditorSession session, SettingsValues values,
                              Map<String, String> extras, boolean complete) {}

    /** @param blockAvailable whether a block exists in the game joined (mod blocks may not); must not throw */
    public Presets(PresetStore store, EditorContext ctx, Translator tr, Predicate<BlockDescriptor> blockAvailable) {
        this.store = Objects.requireNonNull(store);
        this.ctx = Objects.requireNonNull(ctx);
        this.tr = Objects.requireNonNull(tr);
        this.blockAvailable = Objects.requireNonNull(blockAvailable);
    }

    /** Reads the file; call once at startup. */
    public void load() {
        PresetStore.Loaded loaded = store.load();
        book = loaded.book();
        access = loaded.writable() ? Access.WRITABLE : Access.FILE_UNUSABLE;
        version++;
        if (loaded.skipped() > 0) {
            ctx.notify(Notice.of(Notice.Level.WARNING, "sculptory.preset.notice.unreadable",
                    PresetStore.FILE_NAME, Integer.toString(loaded.skipped())));
        }
    }

    /** Presets of {@code tool} also hold {@code extra}, saved under {@code key}. */
    public void addExtra(ToolId tool, String key, PresetExtra extra) {
        extras.computeIfAbsent(Objects.requireNonNull(tool), id -> new LinkedHashMap<>())
                .put(Objects.requireNonNull(key), Objects.requireNonNull(extra));
    }

    public Access access() {
        return access;
    }

    /** True when nothing can be saved this session ({@link #access()} says why). */
    public boolean readOnly() {
        return access != Access.WRITABLE;
    }

    /** Bumped whenever the lists, a selection or {@link #access()} change. */
    public int version() {
        return version;
    }

    /** The tool's presets, sorted by name ignoring case. */
    public List<String> names(ToolId tool) {
        return book.tool(tool).names();
    }

    /** The preset last selected for the tool, or {@code ""} for Default. */
    public String selected(ToolId tool) {
        return book.tool(tool).selected();
    }

    /** Whether the tool has {@value PresetNames#MAX_PER_TOOL} presets already. */
    public boolean isFull(ToolId tool) {
        return book.tool(tool).isFull();
    }

    /** Whether the tool's settings (or extras) differ from what applying the selected preset gives now. */
    public boolean isModified(ToolId tool) {
        Resolution resolution = resolution(tool);
        if (!resolution.complete() || !ctx.settings(tool).equals(resolution.values())) {
            return true;
        }
        for (Map.Entry<String, PresetExtra> extra : extras(tool).entrySet()) {
            if (!extra.getValue().matches(resolution.extras().getOrDefault(extra.getKey(), ""))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Why {@code typed} can't name a new preset of the tool (or {@code except}'s new name), as a translatable error.
     * The built-in "Default" counts as taken.
     */
    public Validation nameProblem(ToolId tool, String typed, String except) {
        List<String> taken = new ArrayList<>(names(tool));
        taken.add(tr.translate("sculptory.preset.default"));
        return PresetNames.problem(typed, taken, except);
    }

    // ---- Selecting ----

    /** Applies preset {@code name} ({@code ""}: Default) to the tool and remembers the choice. */
    public void select(ToolId tool, String name) {
        Objects.requireNonNull(name);
        if (!name.isEmpty() && book.tool(tool).find(name).isEmpty()) {
            return;
        }
        apply(tool, name);
        if (!selected(tool).equals(name)) {
            change(tool, book.tool(tool).select(name));
            persist();
        }
    }

    /**
     * Applies each tool's remembered preset, once per game start (the first time the editor opens, when the server's
     * limits are known).
     */
    public void restoreSelections() {
        restoreSelections(Set.of());
    }

    /**
     * {@link #restoreSelections()} for every tool but {@code restoredElsewhere}: the tools whose last settings came back
     * from {@link ToolSettingsStore} (their preset stays selected, "(modified)" if they differ from it). This brings a
     * tool's setup back where the last settings weren't saved (before that file existed, or a tool new in this build).
     */
    public void restoreSelections(Set<ToolId> restoredElsewhere) {
        if (restored) {
            return;
        }
        restored = true;
        for (Tool tool : ctx.tools().paletteOrder()) {
            ToolId id = tool.descriptor().id();
            if (!selected(id).isEmpty() && !restoredElsewhere.contains(id)) {
                apply(id, selected(id));
            }
        }
    }

    private void apply(ToolId tool, String name) {
        Preset preset = preset(tool, name);
        Tool target = requireTool(tool);
        PresetResolver.Result result = PresetResolver.resolve(ctx.settings(tool), preset.values(), limits(),
                blockAvailable);
        ctx.updateSettings(tool, result.values());
        List<String> skipped = new ArrayList<>();
        for (String key : result.skipped()) {
            skipped.add(target.schema().def(key).map(def -> tr.translate(def.labelKey())).orElse(key));
        }
        List<String> unavailable = new ArrayList<>(result.unavailable());
        Map<String, PresetExtra> toolExtras = extras(tool);
        for (Map.Entry<String, PresetExtra> extra : toolExtras.entrySet()) {
            PresetExtra.Applied applied = extra.getValue().apply(preset.extras().getOrDefault(extra.getKey(), ""));
            skipped.addAll(applied.skipped());
            unavailable.addAll(applied.unavailable());
        }
        preset.extras().keySet().stream().filter(key -> !toolExtras.containsKey(key)).forEach(skipped::add);

        String shown = display(name);
        if (!result.adjusted().isEmpty()) {
            List<String> changes = result.adjusted().stream()
                    .map(change -> tr.translate(change.def().labelKey()) + " " + change.from() + " → " + change.to())
                    .toList();
            ctx.notify(Notice.of(Notice.Level.WARNING, "sculptory.preset.notice.adjusted", shown, list(changes)));
        }
        if (!skipped.isEmpty()) {
            ctx.notify(Notice.of(Notice.Level.WARNING, "sculptory.preset.notice.skipped", shown, list(skipped)));
        }
        if (!unavailable.isEmpty()) {
            ctx.notify(Notice.of(Notice.Level.WARNING, "sculptory.preset.notice.unavailable", shown,
                    list(unavailable)));
        }
        if (!result.unmatched().isEmpty()) {
            ctx.notify(Notice.of(Notice.Level.WARNING, "sculptory.preset.notice.unmatched", shown,
                    list(result.unmatched())));
        }
        for (PresetResolver.Withheld section : result.withheld()) {
            List<String> labels = section.problems().stream().map(def -> tr.translate(def.labelKey())).toList();
            String title = section.section().titleKey().isEmpty() ? "" : tr.translate(section.section().titleKey());
            ctx.notify(Notice.of(Notice.Level.WARNING, "sculptory.preset.notice.withheld", shown, title,
                    list(labels)));
        }
    }

    // ---- Saving ----

    /** Save: overwrites the selected preset with the current settings. Not for Default. */
    public boolean save(ToolId tool) {
        String name = selected(tool);
        if (name.isEmpty() || readOnly()) {
            return false;
        }
        Preset previous = book.tool(tool).find(name).orElseThrow();
        return commit(tool, book.tool(tool).with(capture(tool, name).keepingOther(previous)),
                "sculptory.preset.notice.saved", name);
    }

    /** Save as: a new preset from the current settings, which becomes the selected one. */
    public boolean saveAs(ToolId tool, String typed) {
        String name = PresetNames.normalize(typed);
        if (readOnly() || isFull(tool) || !nameProblem(tool, name, null).isValid()) {
            return false;
        }
        return commit(tool, book.tool(tool).with(capture(tool, name)).select(name), "sculptory.preset.notice.saved",
                name);
    }

    public boolean rename(ToolId tool, String from, String typed) {
        String name = PresetNames.normalize(typed);
        if (readOnly() || book.tool(tool).find(from).isEmpty() || !nameProblem(tool, name, from).isValid()) {
            return false;
        }
        if (name.equals(from)) {
            return true;
        }
        return commit(tool, book.tool(tool).renamed(from, name), "sculptory.preset.notice.renamed", name);
    }

    /** Deletes a preset; when it was selected, Default is (the settings stay as they are). */
    public boolean delete(ToolId tool, String name) {
        if (readOnly() || book.tool(tool).find(name).isEmpty()) {
            return false;
        }
        return commit(tool, book.tool(tool).without(name), "sculptory.preset.notice.deleted", name);
    }

    private Preset capture(ToolId tool, String name) {
        SortedMap<String, String> captured = new TreeMap<>();
        for (Map.Entry<String, PresetExtra> extra : extras(tool).entrySet()) {
            PresetExtra.Capture capture = extra.getValue().capture();
            if (!capture.text().isEmpty()) {
                captured.put(extra.getKey(), capture.text());
            }
            if (capture.note() != null) {
                ctx.notify(capture.note());
            }
        }
        return new Preset(name, ctx.settings(tool).encode(), captured);
    }

    /** Changes a tool's presets and saves them; if saving fails, the lists go back to what the file holds. */
    private boolean commit(ToolId tool, ToolPresets presets, String successKey, String name) {
        PresetBook before = book;
        change(tool, presets);
        if (!persist()) {
            book = before;
            version++;
            return false;
        }
        ctx.notify(Notice.of(Notice.Level.SUCCESS, successKey, name));
        return true;
    }

    private void change(ToolId tool, ToolPresets presets) {
        book = book.with(tool, presets);
        version++;
    }

    /**
     * Writes the file. A failure is reported by the file itself; when saving can't go on (the file changed outside the
     * game, or a save's outcome was uncertain) presets turn read-only.
     */
    private boolean persist() {
        if (readOnly()) {
            return false;
        }
        boolean saved = store.save(book);
        if (!saved && !store.writable()) {
            access = Access.SAVING_STOPPED;
            version++;
        }
        return saved;
    }

    // ---- Resolving ----

    private Resolution resolution(ToolId tool) {
        String name = selected(tool);
        Limits limits = limits();
        EditorSession session = ctx.session().orElse(null);
        Resolution cached = resolutions.get(tool);
        if (cached != null && cached.selected().equals(name) && cached.version() == version
                && Objects.equals(cached.limits(), limits) && cached.session() == session) {
            return cached;
        }
        Preset preset = preset(tool, name);
        // A withheld filter section keeps whatever the tool has, so the tool never shows the preset exactly.
        PresetResolver.Result result = PresetResolver.resolve(SettingsValues.defaults(requireTool(tool).schema()),
                preset.values(), limits, blockAvailable);
        Resolution resolution = new Resolution(name, version, limits, session, result.values(), preset.extras(),
                result.withheld().isEmpty());
        resolutions.put(tool, resolution);
        return resolution;
    }

    /** The preset {@code name}, or for {@code ""} the tool's built-in defaults as a preset. */
    private Preset preset(ToolId tool, String name) {
        if (name.isEmpty()) {
            return new Preset("", SettingsValues.defaults(requireTool(tool).schema()).encode());
        }
        return book.tool(tool).find(name).orElseThrow(() -> new IllegalArgumentException("No preset " + name));
    }

    private Tool requireTool(ToolId tool) {
        return ctx.tools().get(tool).orElseThrow(() -> new IllegalArgumentException("Unknown tool: " + tool));
    }

    private Map<String, PresetExtra> extras(ToolId tool) {
        return extras.getOrDefault(tool, Map.of());
    }

    private Limits limits() {
        return ctx.session().map(session -> session.permissions().limits()).orElse(null);
    }

    private String display(String name) {
        return name.isEmpty() ? tr.translate("sculptory.preset.default") : name;
    }

    /** Up to {@value #LISTED} items, then "…". */
    private static String list(List<String> items) {
        List<String> shown = new ArrayList<>(items.subList(0, Math.min(LISTED, items.size())));
        if (items.size() > LISTED) {
            shown.add("…");
        }
        return String.join(", ", shown);
    }
}
