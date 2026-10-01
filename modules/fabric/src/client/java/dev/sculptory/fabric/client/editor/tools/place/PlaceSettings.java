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
    public static final String SYMMETRY_KEY = "symmetry";

    private final SettingDef.Bool includeAir;
    private final SettingDef.Enum<PasteOptions.Into> into;
    private final SettingDef.Bool physics;
    private final SettingDef.Enum<EntityFilter> entities;
    private final SettingDef.Enum<Symmetry.Mode> symmetry;
    private final SettingsSchema schema;

    /** @param physicsAllowed whether the player has {@code sculptory.physics} right now */
    public PlaceSettings(BooleanSupplier physicsAllowed) {
        Objects.requireNonNull(physicsAllowed);
        includeAir = new SettingDef.Bool(INCLUDE_AIR_KEY, "sculptory.setting.place.include_air", false);
        into = new SettingDef.Enum<>(INTO_KEY, "sculptory.setting.place.into", PasteOptions.Into.class,
                PasteOptions.Into.EVERYTHING);
        physics = new SettingDef.Bool(PHYSICS_KEY, "sculptory.setting.place.physics", false,
                values -> physicsAllowed.getAsBoolean());
        entities = new SettingDef.Enum<>(ENTITIES_KEY, "sculptory.setting.place.entities", EntityFilter.class,
                EntityFilter.DECORATIONS);
        symmetry = new SettingDef.Enum<>(SYMMETRY_KEY, "sculptory.setting.place.symmetry", Symmetry.Mode.class,
                Symmetry.Mode.OFF);
        schema = new SettingsSchema(List.of(new Section("", List.of(includeAir, into, physics, entities)),
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

    /** The symmetry mode a paste, move or stack runs with, around the shared centre. */
    public SettingDef.Enum<Symmetry.Mode> symmetry() {
        return symmetry;
    }

    public SettingsSchema schema() {
        return schema;
    }
}
