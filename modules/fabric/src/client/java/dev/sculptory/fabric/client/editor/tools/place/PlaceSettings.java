package dev.sculptory.fabric.client.editor.tools.place;

import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.fabric.client.editor.settings.Section;
import dev.sculptory.fabric.client.editor.settings.SettingDef;
import dev.sculptory.fabric.client.editor.settings.SettingsSchema;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * The Place tool's settings: whether the source's air overwrites the world, which landing cells a paste, move or
 * stack writes (everything, only existing blocks, or only air), whether the
 * placement runs block updates (physics), which entities go along (a move or
 * stack takes those of the selection, a paste places the clipboard's unless None), and the symmetry a paste, move or
 * stack runs with (the brushes' modes around the shared
 * centre, set with M). Physics is shown only to players with the {@code physics} permission, so each tool builds its
 * schema around a permission check.
 */
public final class PlaceSettings {
    public static final String INCLUDE_AIR_KEY = "include_air";
    public static final String INTO_KEY = "into";
    public static final String PHYSICS_KEY = "physics";
    public static final String ENTITIES_KEY = "entities";
    public static final String BIOMES_KEY = "biomes";
    public static final String SYMMETRY_KEY = "symmetry";

    private final SettingDef.Bool includeAir;
    private final SettingDef.Enum<PasteOptions.Into> into;
    private final SettingDef.Bool physics;
    private final SettingDef.Enum<EntityFilter> entities;
    private final SettingDef.Bool biomes;
    private final SettingDef.Enum<Symmetry.Mode> symmetry;
    private final SettingsSchema schema;

    /** @param physicsAllowed whether the player has {@code sculptory.physics} right now */
    public PlaceSettings(BooleanSupplier physicsAllowed) {
        this(physicsAllowed, () -> true);
    }

    /**
     * @param physicsAllowed whether the player has {@code sculptory.physics} right now
     * @param biomesAllowed whether the player has {@code sculptory.biome} right now (the Biomes toggle shows only then)
     */
    public PlaceSettings(BooleanSupplier physicsAllowed, BooleanSupplier biomesAllowed) {
        Objects.requireNonNull(physicsAllowed);
        Objects.requireNonNull(biomesAllowed);
        includeAir = new SettingDef.Bool(INCLUDE_AIR_KEY, "sculptory.setting.place.include_air", false);
        into = new SettingDef.Enum<>(INTO_KEY, "sculptory.setting.place.into", PasteOptions.Into.class,
                PasteOptions.Into.EVERYTHING);
        physics = new SettingDef.Bool(PHYSICS_KEY, "sculptory.setting.place.physics", false,
                values -> physicsAllowed.getAsBoolean());
        entities = new SettingDef.Enum<>(ENTITIES_KEY, "sculptory.setting.place.entities", EntityFilter.class,
                EntityFilter.DECORATIONS);
        biomes = new SettingDef.Bool(BIOMES_KEY, "sculptory.setting.place.biomes", false,
                values -> biomesAllowed.getAsBoolean());
        symmetry = new SettingDef.Enum<>(SYMMETRY_KEY, "sculptory.setting.place.symmetry", Symmetry.Mode.class,
                Symmetry.Mode.OFF);
        schema = new SettingsSchema(List.of(new Section("", List.of(includeAir, into, physics, entities, biomes)),
                new Section("sculptory.setting.place.symmetry_section", List.of(symmetry), true)));
    }

    public SettingDef.Bool includeAir() {
        return includeAir;
    }

    /**
     * Which landing cells a paste, move or stack writes: everything, only cells holding a block that is not air, or
     * only air. Presets from before it load as Everything.
     */
    public SettingDef.Enum<PasteOptions.Into> into() {
        return into;
    }

    public SettingDef.Bool physics() {
        return physics;
    }

    /** Which entities a move or stack takes; a paste places the clipboard's entities unless this is None. */
    public SettingDef.Enum<EntityFilter> entities() {
        return entities;
    }

    /**
     * Whether a paste writes the clipboard's biomes too, and a move or stack carries the selection's biomes along
     * (protocol 6; off by default, as WorldEdit's {@code -b}; needs the {@code biome} node). One remembered value for
     * all three, like the other Place settings; unticked, a move or stack leaves biomes where they are.
     */
    public SettingDef.Bool biomes() {
        return biomes;
    }

    /** The symmetry mode a paste, move or stack runs with, around the shared centre. */
    public SettingDef.Enum<Symmetry.Mode> symmetry() {
        return symmetry;
    }

    public SettingsSchema schema() {
        return schema;
    }
}
