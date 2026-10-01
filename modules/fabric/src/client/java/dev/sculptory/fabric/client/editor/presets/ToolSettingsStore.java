package dev.sculptory.fabric.client.editor.presets;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.fabric.SculptoryMod;
import dev.sculptory.fabric.client.editor.ConfigFile;
import dev.sculptory.fabric.client.editor.EditorContext;
import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.settings.SettingsSchema;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.editor.tool.Tool;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.session.Notice;
import dev.sculptory.protocol.v2.Limits;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * Each tool's settings as the player last left them, kept across game restarts in
 * {@code config/sculptory/editor-tool-settings.json} ({@link ToolSettingsFile}), so a tool comes back as it was
 * whether or not its settings were saved in a preset.
 *
 * <p><b>Loading</b> ({@link #load}, at startup): a missing file gives nothing to restore. An empty one is reported and
 * replaced by the next save. One that can't be used (not JSON, not a settings file) is copied aside as
 * {@code editor-tool-settings.json.bad} ({@link ConfigFile#keepAside}), reported, and replaced by the next save; one of
 * another version (a newer Sculptory's) is reported and left unchanged ({@link ConfigFile#keepAsIs}), and the
 * settings then last for the session only. Entries that can't be read are skipped, with one notice, and since the
 * next save drops them the file as it was is first copied aside the same way ({@link ConfigFile#copyAside}).
 *
 * <p><b>Restoring</b> ({@link #restore}, once per game start when the editor first opens and the server's limits are
 * known): every tool the file lists gets its saved values, fitted to this game and server as a preset's are
 * ({@link PresetResolver}: values that don't decode are skipped, numbers clamped to the setting's bounds and the
 * server's limits, blocks this game lacks left out where they would be placed, a filter section that doesn't fit whole
 * stays at its defaults), with a notice naming the tool for each kind of change; and its extras (the Scatter mix).
 * Settings the tool doesn't have, and tools this build doesn't have, are left unused. A tool whose restore fails
 * (an error in it) is reported and skipped; the others are still restored. The caller restores the selected preset
 * of the tools not restored ({@link Presets#restoreSelections(Set)}), as before this file existed.
 *
 * <p><b>Saving</b>: every change of a tool's settings ({@link #changed}) is saved once nothing has changed for
 * {@link #SETTLE_MS} ({@link #saveIfSettled}, every tick: a dragged slider doesn't write at every step), and the whole
 * state when the editor closes and at shutdown ({@link #saveNow}; the extras, which change without a settings change,
 * then too). Nothing is saved before the file was restored, so a game closed without opening the editor keeps the
 * file as it was. Writes go through {@link ConfigFile} ({@code AtomicFileStore}: never half-written, never over a
 * change made outside the game); a change whose save failed stays pending and is tried again at the next change and
 * when the editor closes. Every registered tool is written, with the values that differ from its defaults; a value
 * the player hasn't changed since it was restored is written back as the file had it (a radius the server's limit
 * clamped, a block this game lacks, text that didn't decode), not as restore fitted it, so the file keeps what the
 * player set until they set something else; settings and tools this build doesn't have are written back as they
 * were read. Presets are unchanged: they are still saved only by Save and Save as. Client thread only.
 */
public final class ToolSettingsStore {
    public static final String FILE_NAME = "editor-tool-settings.json";
    /** How long the settings must stay unchanged before a change is written. */
    public static final long SETTLE_MS = 750;
    /** How many names a notice lists before "…". */
    private static final int LISTED = 4;

    private final ConfigFile file;
    private final EditorContext ctx;
    private final Translator tr;
    private final Predicate<BlockDescriptor> blockAvailable;
    private final Map<ToolId, Map<String, PresetExtra>> extras = new HashMap<>();
    /** What the file held when it was read: what is restored, and what this build leaves unused but keeps. */
    private ToolSettingsFile loaded = ToolSettingsFile.EMPTY;
    /**
     * Per tool, each setting's text as {@link #restore} left it, dropped once the player changes that setting: while a
     * setting still holds that, the file's own text for it is written back.
     */
    private final Map<ToolId, Map<String, String>> restoredValues = new HashMap<>();
    /** As {@link #restoredValues}, for the extras. */
    private final Map<ToolId, Map<String, String>> restoredExtras = new HashMap<>();
    private boolean restored;
    private boolean pending;
    /** A settled save failed: the next try waits for another change (not every tick), or the editor closing. */
    private boolean failed;
    /** Changes are kept for this session only (the screenshot tour). */
    private boolean sessionOnly;
    private long changedAtMs;

    /** @param blockAvailable whether a block exists in the game joined (mod blocks may not); must not throw */
    public ToolSettingsStore(ConfigFile file, EditorContext ctx, Translator tr,
            Predicate<BlockDescriptor> blockAvailable) {
        this.file = Objects.requireNonNull(file);
        this.ctx = Objects.requireNonNull(ctx);
        this.tr = Objects.requireNonNull(tr);
        this.blockAvailable = Objects.requireNonNull(blockAvailable);
    }

    /** Tool {@code tool} also keeps {@code extra}, saved under {@code key} (as its presets do). */
    public void addExtra(ToolId tool, String key, PresetExtra extra) {
        extras.computeIfAbsent(Objects.requireNonNull(tool), id -> new LinkedHashMap<>())
                .put(Objects.requireNonNull(key), Objects.requireNonNull(extra));
    }

    /** Reads the file; call once at startup, before {@link #restore}. */
    public void load() {
        loaded = ToolSettingsFile.EMPTY;
        Optional<String> text = file.read();
        if (text.isEmpty()) {
            return; // missing, or unreadable (the file reported why)
        }
        if (text.get().isBlank()) {
            file.report("the file is empty; the tools start from their defaults and it is written again at the next"
                    + " save");
            return;
        }
        try {
            loaded = ToolSettingsFile.fromJson(text.get());
        } catch (ToolSettingsFile.OtherVersionException otherVersion) {
            file.keepAsIs(otherVersion.getMessage() + "; tool settings last for this session only");
            return;
        } catch (IllegalArgumentException unusable) {
            file.keepAside(unusable.getMessage() + "; the tools start from their defaults");
            return;
        }
        if (loaded.skipped() > 0) {
            // The next save drops what couldn't be read: the file as it was is kept beside it.
            String count = Integer.toString(loaded.skipped());
            Optional<Path> aside = file.copyAside(count + " saved tool settings couldn't be read");
            ctx.notify(aside.isPresent()
                    ? Notice.of(Notice.Level.WARNING, "sculptory.tool_settings.notice.unreadable_kept", FILE_NAME,
                            count, aside.get().getFileName().toString())
                    : Notice.of(Notice.Level.WARNING, "sculptory.tool_settings.notice.unreadable", FILE_NAME,
                            count));
        }
    }

    /**
     * Gives each registered tool the file lists its saved settings and extras; only the first call does anything (once
     * per game start). Returns the tools restored: the caller restores the selected preset of the others.
     */
    public Set<ToolId> restore() {
        if (restored) {
            return Set.of();
        }
        restored = true;
        Set<ToolId> done = new LinkedHashSet<>();
        for (Tool tool : ctx.tools().paletteOrder()) {
            ToolId id = tool.descriptor().id();
            ToolSettingsFile.Entry entry = loaded.tools().get(id.value());
            if (entry == null) {
                continue;
            }
            try {
                apply(tool, entry);
                done.add(id);
            } catch (RuntimeException failure) {
                // One tool's error doesn't cost the others their settings; its preset is restored instead.
                SculptoryMod.LOG.warn("Sculptory: restoring the last settings of {} failed", id.value(), failure);
                restoredValues.remove(id);
                restoredExtras.remove(id);
                ctx.notify(Notice.of(Notice.Level.WARNING, "sculptory.tool_settings.notice.failed",
                        tr.translate(tool.descriptor().nameKey()), String.valueOf(failure.getMessage())));
            }
        }
        return done;
    }

    /**
     * Nothing is saved from now on: the settings as they change last for this session only. The dev-only screenshot
     * tour changes settings on its way, in a game folder the player may use too.
     */
    public void keepForSessionOnly() {
        sessionOnly = true;
    }

    /** Whether {@link #restore} has run (saving starts then). */
    public boolean restored() {
        return restored;
    }

    private void apply(Tool tool, ToolSettingsFile.Entry entry) {
        ToolId id = tool.descriptor().id();
        SettingsSchema schema = tool.schema();
        // Settings this tool doesn't have (a newer build's) are left unused: they are kept in the file, not reported.
        Map<String, String> known = new TreeMap<>(entry.values());
        known.keySet().removeIf(key -> schema.def(key).isEmpty());
        PresetResolver.Result result = PresetResolver.resolve(SettingsValues.defaults(schema), known, limits(),
                blockAvailable);
        ctx.updateSettings(id, result.values());
        restoredValues.put(id, new HashMap<>(result.values().encode()));
        String name = tr.translate(tool.descriptor().nameKey());
        List<String> skipped = new ArrayList<>();
        for (String key : result.skipped()) {
            skipped.add(schema.def(key).map(def -> tr.translate(def.labelKey())).orElse(key));
        }
        List<String> unavailable = new ArrayList<>(result.unavailable());
        Map<String, String> restoredExtra = new HashMap<>();
        restoredExtras.put(id, restoredExtra);
        for (Map.Entry<String, PresetExtra> extra : extras.getOrDefault(id, Map.of()).entrySet()) {
            String text = entry.extra().get(extra.getKey());
            if (text != null) {
                PresetExtra.Applied applied = extra.getValue().apply(text);
                restoredExtra.put(extra.getKey(), extra.getValue().capture().text());
                skipped.addAll(applied.skipped());
                unavailable.addAll(applied.unavailable());
            }
        }
        if (!result.adjusted().isEmpty()) {
            List<String> changes = result.adjusted().stream()
                    .map(change -> tr.translate(change.def().labelKey()) + " " + change.from() + " → " + change.to())
                    .toList();
            ctx.notify(Notice.of(Notice.Level.WARNING, "sculptory.tool_settings.notice.adjusted", name,
                    list(changes)));
        }
        if (!skipped.isEmpty()) {
            ctx.notify(Notice.of(Notice.Level.WARNING, "sculptory.tool_settings.notice.skipped", name,
                    list(skipped)));
        }
        if (!unavailable.isEmpty()) {
            ctx.notify(Notice.of(Notice.Level.WARNING, "sculptory.tool_settings.notice.unavailable", name,
                    list(unavailable)));
        }
        for (PresetResolver.Withheld section : result.withheld()) {
            List<String> labels = section.problems().stream().map(def -> tr.translate(def.labelKey())).toList();
            String title = section.section().titleKey().isEmpty() ? "" : tr.translate(section.section().titleKey());
            ctx.notify(Notice.of(Notice.Level.WARNING, "sculptory.tool_settings.notice.withheld", name, title,
                    list(labels)));
        }
    }

    // ---- Saving ----

    /** A tool's settings changed: saved once they settle ({@link #saveIfSettled}). */
    public void changed(long nowMs) {
        pending = true;
        failed = false;
        changedAtMs = nowMs;
    }

    /**
     * Saves a change if nothing has changed for {@link #SETTLE_MS}; call every tick. A save that fails (the file
     * reports why) leaves the change pending for the next change or {@link #saveNow}, not for the next tick.
     */
    public void saveIfSettled(long nowMs) {
        if (pending && !failed && nowMs - changedAtMs >= SETTLE_MS) {
            failed = !saveNow();
        }
    }

    /** Whether a change is waiting to be saved (still settling, or its save failed). */
    public boolean pending() {
        return pending;
    }

    /**
     * Saves every tool's state now (the editor closes, the game stops), extras included; the file is written only when
     * that differs from what it holds. Nothing before {@link #restore}. Returns whether the file now holds it; a
     * change stays pending until it does.
     */
    public boolean saveNow() {
        if (!restored || sessionOnly) {
            return false;
        }
        boolean saved = file.write(capture().toJson());
        pending = !saved;
        return saved;
    }

    /**
     * What would be saved now: every registered tool's state, and what the file held that this build doesn't use. A
     * setting or extra still as {@link #restore} left it is written as the file had it (see the class comment); once
     * the player changes it, its current text is written from then on.
     */
    public ToolSettingsFile capture() {
        SortedMap<String, ToolSettingsFile.Entry> tools = new TreeMap<>(loaded.tools());
        for (Tool tool : ctx.tools().paletteOrder()) {
            ToolId id = tool.descriptor().id();
            SettingsSchema schema = tool.schema();
            ToolSettingsFile.Entry before = loaded.tools().getOrDefault(id.value(), ToolSettingsFile.Entry.DEFAULTS);
            Map<String, PresetExtra> toolExtras = extras.getOrDefault(id, Map.of());
            Map<String, String> asRestored = restoredValues.getOrDefault(id, Map.of());
            SortedMap<String, String> values = new TreeMap<>();
            before.values().forEach((key, text) -> {
                if (schema.def(key).isEmpty()) {
                    values.put(key, text);
                }
            });
            SortedMap<String, String> defaults = SettingsValues.defaults(schema).encode();
            ctx.settings(id).encode().forEach((key, text) -> {
                String written = unchangedSinceRestore(key, text, asRestored, before.values(), defaults.get(key));
                if (written != null) {
                    values.put(key, written);
                }
            });
            Map<String, String> extrasAsRestored = restoredExtras.getOrDefault(id, Map.of());
            SortedMap<String, String> extra = new TreeMap<>();
            before.extra().forEach((key, text) -> {
                if (!toolExtras.containsKey(key)) {
                    extra.put(key, text);
                }
            });
            toolExtras.forEach((key, state) -> {
                String written = unchangedSinceRestore(key, state.capture().text(), extrasAsRestored, before.extra(),
                        "");
                if (written != null) {
                    extra.put(key, written);
                }
            });
            tools.put(id.value(), new ToolSettingsFile.Entry(values, extra));
        }
        return new ToolSettingsFile(tools, 0);
    }

    /**
     * The text to write for {@code key} holding {@code text} now: the file's own text where the value is still what
     * restore left it (and the file had one), else the current text unless it is the default (null: not written).
     * A value the player changed is forgotten as restored, so setting it back to the fitted value later writes that.
     */
    private static String unchangedSinceRestore(String key, String text, Map<String, String> asRestored,
            Map<String, String> inFile, String defaultText) {
        String restoredText = asRestored.get(key);
        if (restoredText != null) {
            if (text.equals(restoredText)) {
                String original = inFile.get(key);
                if (original != null) {
                    return original;
                }
            } else {
                asRestored.remove(key);
            }
        }
        return text.equals(defaultText) ? null : text;
    }

    private Limits limits() {
        return ctx.session().map(session -> session.permissions().limits()).orElse(null);
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
