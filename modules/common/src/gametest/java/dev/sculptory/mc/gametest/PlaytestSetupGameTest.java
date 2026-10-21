package dev.sculptory.mc.gametest;

import static dev.sculptory.mc.gametest.EngineTestSupport.check;
import static dev.sculptory.mc.gametest.GameTestSuite.EMPTY_STRUCTURE;

import com.mojang.authlib.GameProfile;
import dev.sculptory.mc.playtest.PlaytestSetup;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.GameRules;
import org.slf4j.LoggerFactory;

/**
 * The dev-only playtest setup ({@link PlaytestSetup}) against the GameTest server: the steps its hooks run, called
 * directly (the hooks are registered only with {@code -Dsculptory.playtest=true}). Daylight locks at day in every world,
 * mob spawning goes off, only living, unnamed hostile mobs are removed, and a joined (mock) player gets permission level
 * 4 even though this server ops at level 0, once. Everything the test changes on the shared server (two game rules, the
 * time of day, the op entry, the mock player and mobs) is put back before it ends, within the same tick, so no other
 * test sees it.
 */
public final class PlaytestSetupGameTest {
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_playtest_setup")
    @SuppressWarnings("removal") // makeMockServerPlayerInLevel is vanilla's test-only mock player
    public void setupLocksDayStopsMobsAndOpsThePlayer(GameTestHelper context) {
        MinecraftServer server = context.getLevel().getServer();
        GameRules rules = server.getGameRules();
        boolean daylight = rules.getBoolean(GameRules.RULE_DAYLIGHT);
        boolean spawning = rules.getBoolean(GameRules.RULE_DOMOBSPAWNING);
        long dayTime = server.overworld().getDayTime();
        PlayerList players = server.getPlayerList();
        ServerPlayer player = context.makeMockServerPlayerInLevel();
        GameProfile profile = player.getGameProfile();
        List<Entity> spawned = new ArrayList<>();
        try {
            // Start from the opposite of what the setup sets, so the checks below cannot pass by accident.
            rules.getRule(GameRules.RULE_DAYLIGHT).set(true, server);
            rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(true, server);
            for (ServerLevel level : server.getAllLevels()) level.setDayTime(13000);

            PlaytestSetup.lockDaylight(server);
            PlaytestSetup.stopMobSpawning(server);
            check(!rules.getBoolean(GameRules.RULE_DAYLIGHT), "doDaylightCycle still on");
            check(!rules.getBoolean(GameRules.RULE_DOMOBSPAWNING), "doMobSpawning still on");
            for (ServerLevel level : server.getAllLevels()) {
                check(level.getDayTime() == PlaytestSetup.DAY_TIME, level.dimension().location() + " is at day time "
                        + level.getDayTime());
            }

            // Hostile: a monster and a slime (an enemy that is not a Monster). Kept: a named monster and a cow.
            Entity zombie = context.spawn(EntityType.ZOMBIE, 1, 2, 1);
            Entity slime = context.spawn(EntityType.SLIME, 3, 2, 1);
            Entity named = context.spawn(EntityType.ZOMBIE, 1, 2, 3);
            named.setCustomName(Component.literal("Kept"));
            Entity cow = context.spawn(EntityType.COW, 3, 2, 3);
            spawned.addAll(List.of(zombie, slime, named, cow));
            int removed = PlaytestSetup.removeHostileMobs(spawned);
            check(removed == 2, "removed " + removed + " hostile mobs, expected 2");
            check(zombie.isRemoved() && slime.isRemoved(), "a hostile mob survived");
            check(!named.isRemoved() && !cow.isRemoved(), "a named mob or a passive mob was removed");
            check(PlaytestSetup.removeHostileMobs(spawned) == 0, "a second sweep removed more");

            // Who is opped: the GameTest server reports itself as dedicated, so the mock player is no host and is opped
            // only when named (without regard to case).
            check(!PlaytestSetup.shouldOp(server, profile, Set.of()), "an unnamed non-host would be opped");
            String names = " Someone ," + profile.getName().toUpperCase(Locale.ROOT);
            check(PlaytestSetup.shouldOp(server, profile, PlaytestSetup.opNames(names)), "a named player would not be");

            // Op: level 4 although this server's own op level (what /op gives) is 0; a second op changes nothing.
            check(!players.isOp(profile) && server.getProfilePermissions(profile) == 0, "mock player starts opped");
            check(server.getOperatorUserPermissionLevel() < PlaytestSetup.OP_LEVEL, "the server ops at level 4 itself");
            check(PlaytestSetup.op(server, player), "op reported no change");
            check(server.getProfilePermissions(profile) == PlaytestSetup.OP_LEVEL,
                    "permission level " + server.getProfilePermissions(profile));
            check(player.hasPermissions(PlaytestSetup.OP_LEVEL), "the player does not hold level 4");
            check(!PlaytestSetup.op(server, player), "a second op changed the entry");
            check(server.getProfilePermissions(profile) == PlaytestSetup.OP_LEVEL, "the second op lowered the level");
        } finally {
            players.getOps().remove(profile);
            for (Entity entity : spawned) {
                if (!entity.isRemoved()) entity.discard();
            }
            try {
                players.remove(player);
            } catch (RuntimeException e) {
                LoggerFactory.getLogger("sculptory").warn("Could not remove the mock player", e);
            }
            rules.getRule(GameRules.RULE_DAYLIGHT).set(daylight, server);
            rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(spawning, server);
            for (ServerLevel level : server.getAllLevels()) level.setDayTime(dayTime);
        }
        context.succeed();
    }
}
