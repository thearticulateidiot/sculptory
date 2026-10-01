package dev.sculptory.fabric.world;

import dev.sculptory.core.entity.EntityNbt;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.BlockAttachedEntity;
import net.minecraft.registry.Registries;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What Sculptory knows of each registered entity type, found once per server by
 * making one detached instance of every type (never added to a world):
 * <ul>
 *   <li><b>operator-only</b>: its instances answer {@code Entity.entityDataRequiresOperator()} (in vanilla 1.21.1 the
 *       command block minecart, the spawner minecart and the falling block); its data is treated like operator-only
 *       block-entity NBT ({@code fabric.schem.EntitySanitizer});</li>
 *   <li><b>never placed</b>: {@link FabricEntities#kind} says {@code NEVER} (players, items, projectiles, primed TNT,
 *       lightning, withers, the ender dragon...), or the game does not let it be summoned: entities of such a type are
 *       left out of files (roots and passengers) and never spawned from a clipboard;</li>
 *   <li><b>hanging</b>: a {@link BlockAttachedEntity} (item frames, paintings), whose {@code TileX/Y/Z} say which
 *       block it belongs to; any other type's are ignored.</li>
 * </ul>
 * A type whose instance cannot be made is treated as operator-only and never placed (fail closed). Before the scan
 * (it runs when the server has started; {@link #scan} runs it on demand) every type is operator-only and never placed,
 * so nothing from a file slips through unchecked. Type ids are compared as the game reads them when it loads an entity
 * ({@link EntityNbt#loadedId}).
 */
public final class EntityTypeRules {
    private static final Logger LOG = LoggerFactory.getLogger("sculptory");
    /** Vanilla's operator-only types, kept whatever a scan finds. */
    public static final Set<String> VANILLA_OPERATOR = Set.of("minecraft:command_block_minecart",
            "minecraft:spawner_minecart", "minecraft:falling_block");
    /** Before any scan: everything operator-only and never placed. */
    private static final EntityTypeRules BEFORE_SCAN = new EntityTypeRules(Set.of(), Set.of(), Set.of(), false);
    private static volatile EntityTypeRules scanned;

    private final Set<String> operator;
    private final Set<String> never;
    private final Set<String> hanging;
    private final boolean ready;

    private EntityTypeRules(Set<String> operator, Set<String> never, Set<String> hanging, boolean ready) {
        this.operator = operator;
        this.never = never;
        this.hanging = hanging;
        this.ready = ready;
    }

    /**
     * The rules, scanning the registry once with {@code world} (server thread). Later calls return the same rules.
     */
    public static EntityTypeRules scan(World world) {
        EntityTypeRules known = scanned;
        if (known != null) return known;
        synchronized (EntityTypeRules.class) {
            if (scanned != null) return scanned;
            TreeSet<String> operator = new TreeSet<>(VANILLA_OPERATOR);
            TreeSet<String> never = new TreeSet<>();
            TreeSet<String> hanging = new TreeSet<>();
            int failed = 0;
            for (EntityType<?> type : Registries.ENTITY_TYPE) {
                String id = EntityType.getId(type).toString();
                Entity entity;
                try {
                    entity = type.create(world);
                } catch (Throwable e) {
                    if (failed++ == 0) {
                        LOG.warn("Sculptory could not make a {} to learn what it is; its data is treated as "
                                + "operator-only and it is never placed from a file", id, e);
                    }
                    operator.add(id);
                    never.add(id);
                    continue;
                }
                if (entity == null) {
                    never.add(id); // the player: not made this way
                    continue;
                }
                try {
                    if (entity.entityDataRequiresOperator()) operator.add(id);
                    if (!type.isSummonable() || FabricEntities.kind(entity) == FabricEntities.Kind.NEVER) never.add(id);
                    if (entity instanceof BlockAttachedEntity) hanging.add(id);
                } catch (Throwable e) {
                    if (failed++ == 0) LOG.warn("Sculptory could not learn what a {} is", id, e);
                    operator.add(id);
                    never.add(id);
                }
            }
            known = new EntityTypeRules(Set.copyOf(operator), Set.copyOf(never), Set.copyOf(hanging), true);
            scanned = known;
            LOG.info("Sculptory: entity types whose data is operator-only: {}; never placed: {} types; hanging: {}",
                    operator, never.size(), hanging);
            return known;
        }
    }

    /** The rules of the last scan, or the fail-closed rules before one ran (safe from any thread). */
    public static EntityTypeRules current() {
        EntityTypeRules known = scanned;
        return known != null ? known : BEFORE_SCAN;
    }

    /** Whether the registry has been scanned. */
    public boolean ready() {
        return ready;
    }

    /** Whether only operators may write this type's data (every type before the scan). */
    public boolean operator(String typeId) {
        return !ready || operator.contains(EntityNbt.loadedId(typeId));
    }

    /** Whether entities of this type are never placed from a clipboard or file (every type before the scan). */
    public boolean never(String typeId) {
        return !ready || never.contains(EntityNbt.loadedId(typeId));
    }

    /** Whether this type hangs on a block (its {@code TileX/Y/Z} count); none before the scan. */
    public boolean hanging(String typeId) {
        return ready && hanging.contains(EntityNbt.loadedId(typeId));
    }
}
