package dev.sculptory.mc.playtest;

import com.mojang.authlib.GameProfile;
import dev.sculptory.mc.Sculptory;
import dev.sculptory.mc.loader.Loaders;
import dev.sculptory.mc.loader.ServerLoader;
import dev.sculptory.mc.perm.McPermissionService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.ServerOpList;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.GameRules;

/**
 * The conditions every playtest runs under, for dev runs only: the tester is an operator, the time is locked at day and
 * no mobs spawn. With {@code -Dsculptory.playtest=true} (scripts/playtest.ps1 and scripts/playtest-server.ps1 pass it
 * by default; {@code -PbsPlaytest} on the loader modules' run tasks), every server this JVM runs (each singleplayer
 * world's integrated server in turn, or a dev server) gets:
 * <ul>
 *   <li>when it has started: {@code doDaylightCycle} and {@code doMobSpawning} off, the difficulty peaceful (so no
 *       hostile mob spawns at all, not from spawners either, and one loaded later far away despawns on its first tick),
 *       the time of day set to {@link #DAY_TIME} ({@code /time set day}) in every world, and the hostile mobs in the
 *       loaded chunks removed;</li>
 *   <li>when a player joins: the singleplayer host, and on any server the players named in
 *       {@code -Dsculptory.playtest.ops} (default {@value #DEFAULT_OPS}, the dev clients' name), get an operator entry
 *       at level {@value #OP_LEVEL}, which the client learns at once; other players stay as they are, so a second
 *       non-op tester ({@code -User Guest}) still sees the refusals;</li>
 *   <li>{@value #SWEEP_DELAY_TICKS} ticks after a join: the hostile mobs removed once more, since the chunks around the
 *       player (and the mobs saved in them) load only after the join.</li>
 * </ul>
 * Each step logs one line starting "Sculptory playtest setup:". The hooks are registered once per JVM and apply to each
 * server as it starts, so a singleplayer world opened, closed and opened again gets them again.
 *
 * <p>The operator entry is added at level 4 directly rather than with {@code PlayerList.op}, which uses the server's
 * operator level: 2 on the integrated server ({@code IntegratedServer.getOperatorUserPermissionLevel}) and 0 on the
 * GameTest server. With an entry, {@code MinecraftServer.getProfilePermissions} returns the entry's level whatever the
 * world's "allow cheats" setting, and {@code PlayerList.sendPlayerPermissionLevel} tells the client (entity event
 * 24 + level, which {@code LocalPlayer} turns into its permission level) and resends the command tree. Adding an entry
 * writes the server's ops.json (in the run directory, for an integrated server).
 *
 * <p>Never shipped: the Fabric, NeoForge and Paper jars leave this package out, and the loaders' entrypoints start it
 * only when its class file is there ({@code DevClasses}). A real install gets the same conditions by hand.
 */
public final class PlaytestSetup {
    /** The system property that switches the setup on ({@code true}). */
    public static final String PROPERTY = "sculptory.playtest";
    /** The system property naming the players to op on any server, comma separated. */
    public static final String OPS_PROPERTY = "sculptory.playtest.ops";
    public static final String DEFAULT_OPS = "BuilderDev";
    /** {@code /time set day}. */
    public static final long DAY_TIME = 1000;
    public static final int OP_LEVEL = 4;
    static final int SWEEP_DELAY_TICKS = 100;

    private static boolean installed;
    /** The sweeps due after a join; touched on server threads, one server at a time. */
    private static final List<PendingSweep> pending = new CopyOnWriteArrayList<>();

    private record PendingSweep(MinecraftServer server, int tick, String playerName) {
    }

    private PlaytestSetup() {
    }

    /** Registers the hooks through {@link Loaders#server()}; once per JVM. */
    public static synchronized void install() {
        if (installed) return;
        installed = true;
        Set<String> opNames = opNames(System.getProperty(OPS_PROPERTY, DEFAULT_OPS));
        Sculptory.LOG.info("Sculptory playtest setup is on (-D{}=true): ops the singleplayer host and {}; locks day; "
                + "turns mobs off (no spawning, peaceful)", PROPERTY,
                opNames.isEmpty() ? "nobody else" : String.join(", ", opNames));
        ServerLoader loader = Loaders.server();
        loader.onServerStarted(PlaytestSetup::serverStarted);
        // Join may arrive on a network thread (ServerLoader): hop onto the server thread.
        loader.onPlayerJoin(player -> {
            MinecraftServer server = player.server;
            if (server.isSameThread()) {
                playerJoined(server, player, opNames);
            } else {
                server.execute(() -> playerJoined(server, player, opNames));
            }
        });
        loader.onEndServerTick(PlaytestSetup::sweepIfDue);
        loader.onServerStopped(server -> pending.removeIf(sweep -> sweep.server() == server));
    }

    private static void serverStarted(MinecraftServer server) {
        lockDaylight(server);
        stopMobSpawning(server);
        makePeaceful(server);
        int removed = removeHostileMobs(server);
        Sculptory.LOG.info("Sculptory playtest setup: daylight locked at day; mob spawning off; difficulty {}; removed {} "
                + "hostile mob(s)", server.getWorldData().getDifficulty().getKey(), removed);
    }

    private static void playerJoined(MinecraftServer server, ServerPlayer player, Set<String> opNames) {
        GameProfile profile = player.getGameProfile();
        String name = profile.getName();
        if (shouldOp(server, profile, opNames)) {
            boolean changed = op(server, player);
            // The level the server now gives the player (what its commands and the client see), read back.
            int level = server.getProfilePermissions(profile);
            Sculptory.LOG.info("Sculptory playtest setup: {} {} (permission level {})", changed ? "opped" : "kept op",
                    name, level);
        }
        pending.add(new PendingSweep(server, server.getTickCount() + SWEEP_DELAY_TICKS, name));
    }

    private static void sweepIfDue(MinecraftServer server) {
        for (PendingSweep sweep : pending) {
            if (sweep.server() != server || server.getTickCount() < sweep.tick()) continue;
            pending.remove(sweep);
            int removed = removeHostileMobs(server);
            Sculptory.LOG.info("Sculptory playtest setup: removed {} hostile mob(s) loaded after {} joined", removed,
                    sweep.playerName());
        }
    }

    /** {@code doDaylightCycle} off and the time of day at {@link #DAY_TIME} in every world. */
    public static void lockDaylight(MinecraftServer server) {
        server.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        // The other dimensions read the overworld's time; setting each is what /time set does.
        for (ServerLevel level : server.getAllLevels()) level.setDayTime(DAY_TIME);
    }

    /** {@code doMobSpawning} off. */
    public static void stopMobSpawning(MinecraftServer server) {
        server.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
    }

    /**
     * The difficulty peaceful, even where the world locked its difficulty (a hardcore world stays hard: vanilla keeps
     * it so). The server sends every player the new difficulty.
     */
    public static void makePeaceful(MinecraftServer server) {
        server.setDifficulty(Difficulty.PEACEFUL, true);
    }

    /** Removes the hostile mobs ({@link #isHostile}) loaded in every world; returns how many. */
    public static int removeHostileMobs(MinecraftServer server) {
        int removed = 0;
        for (ServerLevel level : server.getAllLevels()) removed += removeHostileMobs(level.getAllEntities());
        return removed;
    }

    /** Removes the hostile mobs among {@code entities}; returns how many. */
    public static int removeHostileMobs(Iterable<? extends Entity> entities) {
        // Collected first: discarding while walking a world's entity list would change the list under the walk.
        List<Entity> hostile = new ArrayList<>();
        for (Entity entity : entities) {
            if (isHostile(entity)) hostile.add(entity);
        }
        hostile.forEach(Entity::discard);
        return hostile.size();
    }

    /**
     * A living hostile mob: anything vanilla counts as an enemy (monsters, and slimes, phantoms, ghasts, shulkers and
     * hoglins, which are not {@code Monster}s), except the ender dragon, whose removal would upset the End's dragon
     * fight, and a mob with a name tag, which someone placed on purpose.
     */
    public static boolean isHostile(Entity entity) {
        return entity instanceof Enemy && !(entity instanceof EnderDragon) && entity.isAlive() && !entity.hasCustomName();
    }

    /** Whether the setup ops this player: the singleplayer host, or a player named in {@code opNames}. */
    public static boolean shouldOp(MinecraftServer server, GameProfile profile, Set<String> opNames) {
        return McPermissionService.isSingleplayerHost(server.isDedicatedServer(), server.getSingleplayerProfile(), profile)
                || opNames.contains(profile.getName().toLowerCase(Locale.ROOT));
    }

    /**
     * Gives the player an operator entry at {@link #OP_LEVEL} (keeping a higher one) and sends the client its new
     * level. Returns false when the player already had such an entry.
     */
    public static boolean op(MinecraftServer server, ServerPlayer player) {
        PlayerList players = server.getPlayerList();
        ServerOpList ops = players.getOps();
        GameProfile profile = player.getGameProfile();
        ServerOpListEntry entry = ops.get(profile);
        if (entry != null && entry.getLevel() >= OP_LEVEL) return false;
        ops.add(new ServerOpListEntry(profile, OP_LEVEL, entry != null && entry.getBypassesPlayerLimit()));
        players.sendPlayerPermissionLevel(player);
        return true;
    }

    /** The comma-separated player names, lower case (names are matched without regard to case). */
    public static Set<String> opNames(String list) {
        return Arrays.stream(list.split(",")).map(String::trim).filter(name -> !name.isEmpty())
                .map(name -> name.toLowerCase(Locale.ROOT)).collect(Collectors.toCollection(TreeSet::new));
    }
}
