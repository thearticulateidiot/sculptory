package dev.sculptory.mc.gametest;

import static dev.sculptory.mc.gametest.EditTestSupport.capture;
import static dev.sculptory.mc.gametest.EditTestSupport.checkSame;
import static dev.sculptory.mc.gametest.EditTestSupport.loadAndForce;
import static dev.sculptory.mc.gametest.EngineTestSupport.box;
import static dev.sculptory.mc.gametest.EngineTestSupport.check;
import static dev.sculptory.mc.gametest.EngineTestSupport.pos;
import static dev.sculptory.mc.gametest.GameTestSuite.EMPTY_STRUCTURE;

import com.mojang.authlib.GameProfile;
import dev.sculptory.core.Box;
import dev.sculptory.core.history.store.HistoryStore;
import dev.sculptory.core.history.store.StorageIo;
import dev.sculptory.mc.engine.impl.EditServiceHost;
import dev.sculptory.mc.engine.impl.McHistoryCodec;
import dev.sculptory.mc.gametest.DurableHistoryGameTest.Run;
import dev.sculptory.mc.gametest.EditTestSupport.Harness;
import dev.sculptory.mc.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.mc.schem.McDataFixHook;
import dev.sculptory.mc.world.BlockWriter;
import dev.sculptory.server.engine.JobResult;
import dev.sculptory.server.engine.impl.HistorySnapshot;
import dev.sculptory.server.platform.WorldBiomes;
import dev.sculptory.server.platform.WriteOptions;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;

/**
 * Undo history journals real older builds wrote ({@code src/gametest/resources/compat/<version>/history/}, frozen: see
 * the README there) undo and redo exactly with this build. Each sample was made by that version's own edit service on
 * a GameTest world, at the fixed place {@link #X0}, {@link #Z0}, by the player {@link #PLAYER}: over grass, a sign
 * ("Sculptory &lt;version&gt;"), a chest (5 diamonds, 3 oak logs) and a stone pedestal under a named armor stand
 * ({@link #STAND}), all on cherry grove in 0.4.0, it filled glass over the sign and chest, (0.4.0: a desert biome
 * fill), filled gold over part of the glass, moved the pedestal and stand 8 north, then undid the move and the gold.
 *
 * <p>Here the world is laid out as that build left it (written directly, no history), the journal is put in the
 * history folder, and the player joins: the steps read back as saved; redo brings the gold and the move back, every
 * undo then restores the world exactly (blocks, the sign's text and the chest's items, the stand with its UUID, the
 * biomes), and redo again gives the same world. Also: steps recorded by another Minecraft data version are dropped, by
 * design (they would undo with another game's block states).
 */
public final class CompatHistoryGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;
    /** The player the samples belong to (their file name). */
    static final UUID PLAYER = UUID.fromString("3f2a9c1e-7b4d-4e8a-9c3b-5d6e7f809a1b");
    /** The armor stand the samples moved. */
    static final UUID STAND = UUID.fromString("0a0b0c0d-0e0f-4a1b-8c2d-3e4f50617283");
    /** Where the samples were made: far from every other GameTest region. */
    static final int X0 = -1_600_000, Z0 = 1_600_000;
    private static final Box AREA = box(X0, 96, Z0, X0 + 15, 107, Z0 + 15);
    private static final Box GLASS = box(X0 + 1, 100, Z0 + 1, X0 + 8, 102, Z0 + 8);
    private static final Box GOLD = box(X0 + 4, 101, Z0 + 4, X0 + 6, 105, Z0 + 6);
    private static final Box MOVED = box(X0 + 10, 101, Z0 + 10, X0 + 12, 103, Z0 + 12);
    private static final int MOVE_Z = -8;
    /** The desert fill of the 0.4.0 sample: the quarts holding this box's cells. */
    private static final Box DESERT = box(X0 + 1, 100, Z0 + 9, X0 + 6, 103, Z0 + 14);
    private static final String CHERRY = "minecraft:cherry_grove";

    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_compat_history", timeoutTicks = LIMIT)
    public void journalsOfOlderBuildsUndoAndRedoExactly(GameTestHelper context) {
        Harness h = new Harness(context);
        loadAndForce(h.world, AREA);
        context.startSequence()
                .thenWaitUntil(() -> EntitiesGameTest.ready(h.world, AREA))
                .thenWaitUntil(EntitiesGameTest.once(() -> {
                    try {
                        sample(h, "0.3.0", false);
                        sample(h, "0.4.0", true);
                    } finally {
                        clearEntities(h.world);
                        EngineTestSupport.forceChunks(h.world, AREA, false);
                        h.close();
                    }
                }))
                .thenSucceed();
    }

    /** One sample set's journal: the world as its build left it, then redo, undo everything, redo everything. */
    private static void sample(Harness h, String version, boolean biomes) {
        clearEntities(h.world);
        fixture(h, version);
        WorldSnapshot original = capture(h.world, AREA);
        List<String> originalBiomes = biomes(h);

        // As the build left it: the glass (and the desert) done, the gold and the move undone.
        BlockWriter writer = h.runtime.writer(h.world, new WriteOptions(false, true));
        fill(writer, GLASS, h.state("minecraft:glass"));
        if (biomes) paint(h, DESERT, "minecraft:desert");
        WorldSnapshot glass = capture(h.world, AREA);
        List<String> glassBiomes = biomes(h);
        WorldSnapshot gold = with(glass, GOLD, Block.getId(Blocks.GOLD_BLOCK.defaultBlockState()));
        WorldSnapshot moved = movedNorth(gold);

        Path folder = folder(version);
        ServerPlayer player = player(h.context);
        Run run = new Run(h, folder, 0, StorageIo.SYSTEM);
        try {
            run.load(player);
            HistorySnapshot history = run.service.history(player);
            List<String> undo = biomes ? List.of("Biome fill · 4 biome cells", "Fill · 192 blocks")
                    : List.of("Fill · 192 blocks");
            check(history.undoLabels().equals(undo) && history.redoLabels().equals(List.of("Fill · 45 blocks",
                    "Move · 2 blocks · 2 entities")), version + ": the steps read back " + history);

            step(run, player, false, version + " redo of the gold");
            checkSame(gold, capture(h.world, AREA), version + ": the gold redone");
            step(run, player, false, version + " redo of the move");
            checkSame(moved, capture(h.world, AREA), version + ": the move redone");
            checkStand(h.world, version, false);

            step(run, player, true, version + " undo of the move");
            checkSame(gold, capture(h.world, AREA), version + ": the move undone");
            checkStand(h.world, version, true);
            step(run, player, true, version + " undo of the gold");
            checkSame(glass, capture(h.world, AREA), version + ": the gold undone");
            if (biomes) {
                check(biomes(h).equals(glassBiomes), version + ": the biomes before the desert's undo");
                step(run, player, true, version + " undo of the desert");
                check(biomes(h).equals(originalBiomes), version + ": the desert undone: " + biomes(h));
            }
            step(run, player, true, version + " undo of the glass");
            checkSame(original, capture(h.world, AREA), version + ": everything undone");
            check(biomes(h).equals(originalBiomes), version + ": the biomes as they were");
            checkSignAndChest(h.world, version);
            checkStand(h.world, version, true);

            for (int i = 0; i < (biomes ? 4 : 3); i++) step(run, player, false, version + " redo " + (i + 1));
            checkSame(moved, capture(h.world, AREA), version + ": everything redone");
            if (biomes) check(biomes(h).equals(glassBiomes), version + ": the desert redone");
            checkStand(h.world, version, false);
        } finally {
            run.stop();
            h.world.getServer().getPlayerList().getOps().remove(player.getGameProfile());
            h.world.getServer().getPlayerList().remove(player);
            GameTestSuite.deleteTree(folder);
        }
    }

    /**
     * Steps saved by another Minecraft version can't be undone with this one's block states: they are left out (by
     * design; the history of a Minecraft update starts empty) and the reason is reported.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_compat_history_dataversion", timeoutTicks = LIMIT)
    public void stepsOfAnotherMinecraftDataVersionAreDropped(GameTestHelper context) {
        Harness h = new Harness(context);
        Path folder = folder("0.4.0");
        try {
            HistoryStore store = HistoryStore.open(folder, new McHistoryCodec(h.runtime.states(),
                    McDataFixHook.currentDataVersion() + 1), HistoryStore.Settings.DEFAULTS, StorageIo.SYSTEM,
                    HistoryStore.Log.NONE);
            try {
                store.load(PLAYER);
                HistoryStore.Loaded loaded = awaitLoaded(store);
                check(loaded.entries().isEmpty() && loaded.failed().size() == 4,
                        "entries " + loaded.entries().size() + ", failed " + loaded.failed());
                check(loaded.problems().stream().anyMatch(problem -> problem.contains("data version")),
                        "the reason: " + loaded.problems());
            } finally {
                check(store.close(10_000), "the store closed");
            }
        } catch (IOException e) {
            throw new GameTestAssertException("the history store did not open: " + e);
        } finally {
            GameTestSuite.deleteTree(folder);
            h.close();
        }
        context.succeed();
    }

    private static HistoryStore.Loaded awaitLoaded(HistoryStore store) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            for (HistoryStore.Event event : store.poll()) {
                if (event instanceof HistoryStore.Loaded loaded && loaded.player().equals(PLAYER)) return loaded;
            }
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new GameTestAssertException("the history did not load");
    }

    private static void step(Run run, ServerPlayer player, boolean undo, String what) {
        JobResult result = run.step(player, undo);
        check(result.skippedConflicts() == 0, what + " met conflicts: " + result);
    }

    /** A fresh history folder holding the sample journal of {@code version}. */
    private static Path folder(String version) {
        String root = System.getProperty(EditServiceHost.HISTORY_DIR_PROPERTY);
        Path base = root != null ? Path.of(root)
                : Path.of(System.getProperty("java.io.tmpdir"), "sculptory-gametest-history");
        Path folder = base.resolve("compat-tests").resolve(UUID.randomUUID().toString()).normalize();
        String name = PLAYER + HistoryStore.EXTENSION;
        try (InputStream in = CompatHistoryGameTest.class.getResourceAsStream("/compat/" + version + "/history/"
                + name)) {
            check(in != null, "the " + version + " sample journal is missing");
            Files.createDirectories(folder);
            Files.write(folder.resolve(name), in.readAllBytes());
        } catch (IOException e) {
            throw new GameTestAssertException("copying the sample journal failed: " + e);
        }
        return folder;
    }

    /** The world before the sample's edits, as its build laid it out. */
    private static void fixture(Harness h, String version) {
        BlockWriter writer = h.runtime.writer(h.world, new WriteOptions(false, true));
        int stone = h.state("minecraft:stone"), dirt = h.state("minecraft:dirt");
        int grass = h.state("minecraft:grass_block"), air = h.state("minecraft:air");
        for (int y = AREA.min().y(); y <= AREA.max().y(); y++) {
            int state = y < 98 ? stone : y < 100 ? dirt : y == 100 ? grass : air;
            fill(writer, box(X0, y, Z0, X0 + 15, y, Z0 + 15), state);
        }
        BlockPos sign = pos(X0 + 2, 101, Z0 + 2);
        writer.write(sign.getX(), sign.getY(), sign.getZ(), h.state("minecraft:oak_sign[rotation=4]"), null);
        ((SignBlockEntity) h.world.getBlockEntity(sign))
                .setText(new SignText().setMessage(0, Component.literal("Sculptory " + version)), true);
        BlockPos chest = pos(X0 + 5, 101, Z0 + 5);
        writer.write(chest.getX(), chest.getY(), chest.getZ(), h.state("minecraft:chest[facing=east]"), null);
        ChestBlockEntity chestEntity = (ChestBlockEntity) h.world.getBlockEntity(chest);
        chestEntity.setItem(0, new ItemStack(Items.DIAMOND, 5));
        chestEntity.setItem(13, new ItemStack(Items.OAK_LOG, 3));
        writer.write(X0 + 11, 101, Z0 + 11, stone, null);
        ArmorStand stand = new ArmorStand(h.world, X0 + 11.5, 102, Z0 + 11.5);
        stand.setUUID(STAND);
        stand.setYRot(30f);
        stand.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        stand.setCustomName(Component.literal("Frozen sample"));
        check(h.world.addFreshEntity(stand), "the stand spawned");
        paint(h, AREA, CHERRY);
    }

    private static void fill(BlockWriter writer, Box box, int state) {
        for (int y = box.min().y(); y <= box.max().y(); y++) {
            for (int z = box.min().z(); z <= box.max().z(); z++) {
                for (int x = box.min().x(); x <= box.max().x(); x++) writer.write(x, y, z, state, null);
            }
        }
    }

    /** {@code base} with every cell of {@code box} holding {@code state} (and no block entity there). */
    private static WorldSnapshot with(WorldSnapshot base, Box box, int state) {
        WorldSnapshot out = new WorldSnapshot(base.box, base.states.clone());
        base.tiles.forEach((key, nbt) -> {
            BlockPos at = BlockPos.of(key);
            if (!box.contains(at.getX(), at.getY(), at.getZ())) out.tiles.put(key, nbt);
        });
        for (int y = box.min().y(); y <= box.max().y(); y++) {
            for (int z = box.min().z(); z <= box.max().z(); z++) {
                for (int x = box.min().x(); x <= box.max().x(); x++) out.states[out.index(x, y, z)] = state;
            }
        }
        return out;
    }

    /** {@code base} after the sample's move: {@link #MOVED}'s cells 8 north, air left behind. */
    private static WorldSnapshot movedNorth(WorldSnapshot base) {
        WorldSnapshot out = with(base, MOVED, Block.getId(Blocks.AIR.defaultBlockState()));
        for (int y = MOVED.min().y(); y <= MOVED.max().y(); y++) {
            for (int z = MOVED.min().z(); z <= MOVED.max().z(); z++) {
                for (int x = MOVED.min().x(); x <= MOVED.max().x(); x++) {
                    out.states[out.index(x, y, z + MOVE_Z)] = base.get(x, y, z);
                }
            }
        }
        return out;
    }

    /** The sign's text and the chest's items, back from the journal. */
    private static void checkSignAndChest(ServerLevel world, String version) {
        String text = ((SignBlockEntity) world.getBlockEntity(pos(X0 + 2, 101, Z0 + 2))).getText(true)
                .getMessage(0, false).getString();
        check(text.equals("Sculptory " + version), version + ": the sign reads \"" + text + "\"");
        ChestBlockEntity chest = (ChestBlockEntity) world.getBlockEntity(pos(X0 + 5, 101, Z0 + 5));
        check(chest.getItem(0).is(Items.DIAMOND) && chest.getItem(0).getCount() == 5
                && chest.getItem(13).is(Items.OAK_LOG) && chest.getItem(13).getCount() == 3,
                version + ": the chest holds " + chest.getItem(0) + " and " + chest.getItem(13));
    }

    /** The one armor stand: on the pedestal with its own UUID ({@code home}), or 8 north of it after the move. */
    private static void checkStand(ServerLevel world, String version, boolean home) {
        Box from = home ? MOVED : box(MOVED.min().x(), MOVED.min().y(), MOVED.min().z() + MOVE_Z, MOVED.max().x(),
                MOVED.max().y(), MOVED.max().z() + MOVE_Z);
        Box other = home ? box(MOVED.min().x(), MOVED.min().y(), MOVED.min().z() + MOVE_Z, MOVED.max().x(),
                MOVED.max().y(), MOVED.max().z() + MOVE_Z) : MOVED;
        List<Entity> there = EntitiesGameTest.entitiesIn(world, from);
        check(there.size() == 1 && there.get(0) instanceof ArmorStand, version + ": stands " + there);
        ArmorStand stand = (ArmorStand) there.get(0);
        check(stand.getCustomName() != null && stand.getCustomName().getString().equals("Frozen sample")
                && stand.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET)
                && Math.abs(stand.getYRot() - 30f) < 1e-3, version + ": the stand " + stand);
        check(Math.abs(stand.getX() - (X0 + 11.5)) < 1e-6 && Math.abs(stand.getY() - 102) < 1e-6
                && Math.abs(stand.getZ() - (Z0 + 11.5 + (home ? 0 : MOVE_Z))) < 1e-6, version + ": at " + stand.position());
        check(home == stand.getUUID().equals(STAND), version + ": the stand's UUID " + stand.getUUID());
        check(EntitiesGameTest.entitiesIn(world, other).isEmpty(), version + ": a stand left behind");
    }

    private static void clearEntities(ServerLevel world) {
        EntitiesGameTest.entitiesIn(world, AREA).forEach(Entity::discard);
    }

    /** Every quart of the area's columns, at the area's heights, in order. */
    private static List<String> biomes(Harness h) {
        WorldBiomes world = h.runtime.biomes(h.world);
        List<String> out = new ArrayList<>();
        for (int qx = AREA.min().x() >> 2; qx <= AREA.max().x() >> 2; qx++) {
            for (int qz = AREA.min().z() >> 2; qz <= AREA.max().z() >> 2; qz++) {
                for (int qy = AREA.min().y() >> 2; qy <= AREA.max().y() >> 2; qy++) {
                    out.add(world.id(world.get(qx, qy, qz)));
                }
            }
        }
        return out;
    }

    /** Sets every quart holding a cell of {@code box} to {@code id}. */
    private static void paint(Harness h, Box box, String id) {
        WorldBiomes world = h.runtime.biomes(h.world);
        int handle = world.handle(id);
        for (int qx = box.min().x() >> 2; qx <= box.max().x() >> 2; qx++) {
            for (int qz = box.min().z() >> 2; qz <= box.max().z() >> 2; qz++) {
                for (int qy = box.min().y() >> 2; qy <= box.max().y() >> 2; qy++) world.set(qx, qy, qz, handle);
            }
        }
        world.flush();
    }

    /** A creative mock player with the samples' UUID and operator rights. */
    private static ServerPlayer player(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        GameProfile profile = new GameProfile(PLAYER, "CompatSample");
        CommonListenerCookie data = CommonListenerCookie.createInitial(profile, false);
        ServerPlayer player = new ServerPlayer(world.getServer(), world, data.gameProfile(),
                data.clientInformation()) {
            @Override
            public boolean isSpectator() {
                return false;
            }

            @Override
            public boolean isCreative() {
                return true;
            }
        };
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        world.getServer().getPlayerList().placeNewPlayer(connection, player, data);
        world.getServer().getPlayerList().getOps().add(new ServerOpListEntry(profile, 2, false));
        return player;
    }
}
