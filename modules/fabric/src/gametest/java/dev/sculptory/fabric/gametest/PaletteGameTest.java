package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.regionCorner;
import static dev.sculptory.fabric.gametest.ScatterGameTest.FLOOR_Y;
import static dev.sculptory.fabric.gametest.ScatterGameTest.heldPlan;
import static dev.sculptory.fabric.gametest.ScatterGameTest.preview;
import static dev.sculptory.fabric.gametest.ScatterGameTest.region;

import dev.sculptory.core.palette.BlockPalette;
import dev.sculptory.core.scatter.ScatterPlan;
import dev.sculptory.core.scatter.ScatterSettings;
import dev.sculptory.core.scatter.ScatterSource;
import dev.sculptory.fabric.engine.ClipboardService;
import dev.sculptory.fabric.engine.ClipboardService.LibraryChange;
import dev.sculptory.fabric.engine.EditRejected;
import dev.sculptory.fabric.engine.Perm;
import dev.sculptory.fabric.engine.ScatterService;
import dev.sculptory.fabric.engine.impl.EditServiceHost;
import dev.sculptory.fabric.engine.impl.ServerClipboards;
import dev.sculptory.fabric.engine.impl.ServerScatter;
import dev.sculptory.fabric.gametest.ClipTestSupport.Captured;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.library.PaletteFile;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.protocol.v2.S2C;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;

/**
 * Palettes against the server's real block registry and data fixer: a save resolves every state (refusing one the
 * server doesn't know, keeping a waterlogged state waterlogged), lands in the player's own folder without
 * {@code library.write} and loads back exactly; a load upgrades states from an older game and leaves out and counts the
 * states the server doesn't know; a loaded palette's water plants plan under water in a scatter. The rights, slots and
 * pushes of palette requests, and the production service ({@link EditServiceHost#clipboards()}) forwarding them.
 * Region slots 540-559 (only 540 is used).
 */
public final class PaletteGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;

    private static EditRejected refusal(ClipboardGameTest.ThrowingRun run) {
        return ClipboardGameTest.refusal(run);
    }

    private static void run(ClipboardGameTest.ThrowingRun request) {
        try {
            request.run();
        } catch (EditRejected e) {
            throw new GameTestException("refused: " + e.reason() + " " + e.getMessage());
        }
    }

    private static BlockPalette palette(String... states) {
        List<BlockPalette.Entry> entries = new java.util.ArrayList<>();
        for (int i = 0; i < states.length; i++) entries.add(new BlockPalette.Entry(states[i], i + 1));
        return new BlockPalette(entries);
    }

    /**
     * Unknown states, two texts of one state and a schematic path are refused before any file work; a player without
     * {@code library.write} saves into their own folder; the file holds the server's own exact state text and loads back
     * the same.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_palette_save", tickLimit = LIMIT)
    public void aPaletteSaveKeepsTheServersExactStatesAndRefusesUnknownOnes(TestContext context) throws IOException {
        Harness h = new Harness(context);
        ServerPlayerEntity builder = h.addPlayer(false);
        ServerPlayerEntity useOnly = h.addPlayer(false);
        EditTestSupport.grant(builder, Perm.USE, Perm.CLIPBOARD);
        EditTestSupport.grant(useOnly, Perm.USE);
        Path root = ClipTestSupport.libraryRoot(context);
        ServerClipboards clips = ClipTestSupport.clipboards(h, root);
        String own = "_players/" + builder.getUuid();

        EditRejected unknown = refusal(() -> clips.savePalette(builder, "reef.palette.json",
                palette("minecraft:stone", "modded:gone"), new Captured<>()));
        check(unknown.reason() == RejectReason.INVALID && unknown.getMessage().contains("unknown block state: modded:gone"),
                "an unknown block: " + unknown.getMessage());
        EditRejected property = refusal(() -> clips.savePalette(builder, "reef.palette.json",
                palette("minecraft:stone[waterlogged=true]"), new Captured<>()));
        check(property.reason() == RejectReason.INVALID, "a property stone doesn't have");
        EditRejected twice = refusal(() -> clips.savePalette(builder, "reef.palette.json",
                palette("minecraft:oak_log", "minecraft:oak_log[axis=y]"), new Captured<>()));
        check(twice.getMessage().contains("listed twice"), "one state twice: " + twice.getMessage());
        check(refusal(() -> clips.savePalette(builder, "reef.schem", palette("minecraft:stone"), new Captured<>()))
                .reason() == RejectReason.INVALID, "a schematic path");
        check(refusal(() -> clips.loadPalette(builder, "trees/oak.schem", new Captured<>())).reason()
                == RejectReason.INVALID, "a schematic is never loaded as a palette");
        check(refusal(() -> clips.savePalette(useOnly, "reef.palette.json", palette("minecraft:stone"), new Captured<>()))
                .reason() == RejectReason.NO_PERMISSION, "the library needs clipboard");
        check(clips.requests(builder.getUuid()) == 0, "a refusal left a request slot taken");
        check(!Files.exists(root), "nothing was written: " + root);

        Captured<LibraryChange> saved = new Captured<>();
        Captured<ClipboardService.LoadedPalette> loaded = new Captured<>();
        Captured<ClipboardService.Listing> listing = new Captured<>();
        run(() -> clips.savePalette(builder, "biomes/reef.palette.json", palette("minecraft:sea_pickle[waterlogged=true]",
                "minecraft:seagrass", "minecraft:oak_log"), saved));
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    LibraryChange change = saved.get("save");
                    check(change.equals(new LibraryChange(false, "", own + "/biomes/reef.palette.json")),
                            "saved into the player's own folder: " + change);
                    Path file = root.resolve(own).resolve("biomes").resolve("reef.palette.json");
                    check(Files.isRegularFile(file), "no file at " + file);
                    try {
                        PaletteFile.Content content = PaletteFile.decode(Files.readAllBytes(file));
                        check(content.entries().equals(List.of(
                                new BlockPalette.Entry("minecraft:sea_pickle[pickles=1,waterlogged=true]", 1),
                                new BlockPalette.Entry("minecraft:seagrass", 2),
                                new BlockPalette.Entry("minecraft:oak_log[axis=y]", 3))), "the file: " + content);
                        check(content.dataVersion().orElse(-1) == 3955, "the game's data version: " + content);
                        check(Files.readString(file, StandardCharsets.UTF_8).contains("\"format\": \"sculptory:palette\""),
                                "a readable JSON file");
                    } catch (IOException | PaletteFile.PaletteFormatException e) {
                        throw new GameTestException("unreadable palette file: " + e);
                    }
                    run(() -> clips.loadPalette(builder, change.to(), loaded));
                    run(() -> clips.list(builder, own + "/biomes", listing));
                })
                .createAndAdd(() -> {
                    ClipboardService.LoadedPalette back = loaded.get("load");
                    check(back.dropped() == 0 && back.droppedStates().isEmpty(), "nothing left out: " + back);
                    check(back.palette().entries().get(0).state().equals("minecraft:sea_pickle[pickles=1,waterlogged=true]"),
                            "a waterlogged state stays waterlogged: " + back.palette());
                    check(back.palette().size() == 3, "all three: " + back.palette());
                    S2C.LibraryListing.Entry entry = listing.get("list").entries().get(0);
                    check(entry.kind() == S2C.LibraryListing.Entry.Kind.PALETTE && entry.contentHash().isEmpty(),
                            "listed as a palette, without a content hash: " + entry);
                    ClipTestSupport.deleteTree(root.getParent());
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * A hand-written palette from an older game: states the data fixer renames load under their new name, states this
     * server doesn't know are left out and counted (the first three named), repeats merged; a file of nothing but unknown
     * states, and a malformed file, are refused saying why.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_palette_load", tickLimit = LIMIT)
    public void aPaletteLoadUpgradesOldStatesAndCountsTheOnesThisServerDoesNotKnow(TestContext context)
            throws IOException {
        Harness h = new Harness(context);
        Path root = ClipTestSupport.libraryRoot(context);
        Files.createDirectories(root.resolve("old"));
        Files.writeString(root.resolve("old").resolve("paths.palette.json"), """
                {"format": "sculptory:palette", "version": 1, "dataVersion": 2586, "entries": [
                  {"state": "minecraft:grass_path", "weight": 5},
                  {"state": "minecraft:stone", "weight": 2},
                  {"state": "somemod:gone", "weight": 1},
                  {"state": "minecraft:stone[bogus=1]", "weight": 1},
                  {"state": "minecraft:dirt_path", "weight": 3},
                  {"state": "othermod:gone[a=b]", "weight": 1}
                ]}
                """, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("old").resolve("none.palette.json"), """
                {"format": "sculptory:palette", "version": 1, "entries": [{"state": "somemod:gone", "weight": 1}]}
                """, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("old").resolve("broken.palette.json"), "{\"format\": \"sculptory:palette\"",
                StandardCharsets.UTF_8);
        Files.writeString(root.resolve("old").resolve("newer.palette.json"),
                "{\"format\": \"sculptory:palette\", \"version\": 7, \"entries\": 1}", StandardCharsets.UTF_8);
        ServerClipboards clips = ClipTestSupport.clipboards(h, root);
        Captured<ClipboardService.LoadedPalette> old = new Captured<>();
        Captured<ClipboardService.LoadedPalette> none = new Captured<>();
        Captured<ClipboardService.LoadedPalette> broken = new Captured<>();
        Captured<ClipboardService.LoadedPalette> newer = new Captured<>();
        run(() -> clips.loadPalette(h.player, "old/paths.palette.json", old));
        run(() -> clips.loadPalette(h.player, "old/none.palette.json", none));
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    ClipboardService.LoadedPalette palette = old.get("old palette");
                    check(palette.palette().entries().equals(List.of(new BlockPalette.Entry("minecraft:dirt_path", 8),
                            new BlockPalette.Entry("minecraft:stone", 2))),
                            "grass_path upgraded to dirt_path and merged with it: " + palette.palette());
                    check(palette.dropped() == 3, "three left out: " + palette);
                    check(palette.droppedStates().equals(List.of("somemod:gone", "minecraft:stone[bogus=1]",
                            "othermod:gone[a=b]")), "named: " + palette.droppedStates());
                    none.failedWith(RejectReason.INVALID, "nothing known");
                    check(none.detail.contains("none of its 1 block exist on this server"), none.detail);
                    run(() -> clips.loadPalette(h.player, "old/broken.palette.json", broken));
                    run(() -> clips.loadPalette(h.player, "old/newer.palette.json", newer));
                })
                .createAndAdd(() -> {
                    broken.failedWith(RejectReason.INVALID, "malformed");
                    check(broken.detail.contains("not valid JSON") && !broken.detail.contains(root.toString()),
                            "says why, never where on disk: " + broken.detail);
                    newer.failedWith(RejectReason.INVALID, "newer");
                    check(newer.detail.contains("version 7"), newer.detail);
                    ClipTestSupport.deleteTree(root.getParent());
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * A palette of water plants, saved and loaded, then used as the Scatter tool uses a loaded palette (its states as
     * block variants): every placement stands on the lake's seabed in still water, none on the meadow.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_palette_scatter", tickLimit = LIMIT)
    public void aLoadedPalettesWaterPlantsPlanUnderWater(TestContext context) {
        Harness h = new Harness(context);
        Path root = ClipTestSupport.libraryRoot(context);
        ServerClipboards clips = ClipTestSupport.clipboards(h, root);
        ServerScatter scatter = new ServerScatter(h.service);
        int[] at = regionCorner(context, 540);
        int x0 = at[0], z0 = at[1];
        ScatterWaterGameTest.shore(h, x0, z0);
        Captured<LibraryChange> saved = new Captured<>();
        Captured<ClipboardService.LoadedPalette> loaded = new Captured<>();
        ScatterGameTest.Reply[] reply = new ScatterGameTest.Reply[1];
        run(() -> clips.savePalette(h.player, "reef.palette.json",
                palette("minecraft:seagrass", "minecraft:sea_pickle[pickles=3,waterlogged=true]"), saved));
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    saved.get("save");
                    run(() -> clips.loadPalette(h.player, "reef.palette.json", loaded));
                })
                .createAndAdd(() -> {
                    List<ScatterSource> sources = loaded.get("load").palette().entries().stream()
                            .<ScatterSource>map(entry -> new ScatterSource.Block(entry.state())).toList();
                    reply[0] = preview(scatter, h.player, ScatterWaterGameTest.request(region(x0, z0, x0 + 31, z0 + 15),
                            0, 23L, ScatterSettings.Fit.DEFAULT, ScatterSettings.ColumnHeight.ONE,
                            sources.toArray(new ScatterSource[0])));
                })
                .createAndAdd(() -> {
                    scatter.tick();
                    check(reply[0].finished(), "planning");
                })
                .createAndAdd(() -> {
                    ScatterService.PlanReady plan = reply[0].get("preview");
                    check(plan.placements() > 50, "only " + plan.placements() + " placements: " + plan.rejectedCounts());
                    int[] planned = new int[2];
                    for (ScatterPlan.Placement p : heldPlan(h, h.player).placements()) {
                        planned[p.variant()]++;
                        check(ScatterWaterGameTest.inLake(x0, z0, p.anchor().x(), p.anchor().z()),
                                "a placement off the lake: " + p);
                        check(p.anchor().y() == FLOOR_Y + 1, "a placement off the seabed: " + p);
                    }
                    check(planned[0] > 10 && planned[1] > 10, "both plants planned: " + planned[0] + ", " + planned[1]);
                    ClipTestSupport.deleteTree(root.getParent());
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * The rights and slots of palette requests at the service: another player's folder is refused at admission, for a
     * save and a load (admins excepted); a palette saved in a player folder is shown to that player and admins only;
     * a move that would change a file's kind is refused at admission; a second library write while one runs is refused
     * (the save slot); a player leaving while their save runs still gets the file written and the slots released.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_palette_rights", tickLimit = LIMIT)
    public void paletteRequestsFollowTheLibraryRightsAndSlots(TestContext context) throws IOException {
        Harness h = new Harness(context);
        ServerPlayerEntity alice = h.addPlayer(false);
        ServerPlayerEntity bob = h.addPlayer(false);
        EditTestSupport.grant(alice, Perm.USE, Perm.CLIPBOARD);
        EditTestSupport.grant(bob, Perm.USE, Perm.CLIPBOARD);
        Path root = ClipTestSupport.libraryRoot(context);
        ServerClipboards clips = ClipTestSupport.clipboards(h, root);
        String alices = "_players/" + alice.getUuid();
        String mine = alices + "/mine.palette.json";
        BlockPalette stone = palette("minecraft:stone");

        EditRejected intoAlices = refusal(() -> clips.savePalette(bob, alices + "/bob.palette.json", stone,
                new Captured<>()));
        check(intoAlices.reason() == RejectReason.NO_PERMISSION, "bob saving into alice's folder: " + intoAlices);
        check(clips.requests(bob.getUuid()) == 0, "a refusal left a slot taken");
        check(!Files.exists(root), "nothing written");

        Captured<LibraryChange> saved = new Captured<>();
        Captured<ClipboardService.LoadedPalette> byAdmin = new Captured<>();
        Captured<LibraryChange> first = new Captured<>();
        Captured<LibraryChange> leaving = new Captured<>();
        run(() -> clips.savePalette(alice, "mine.palette.json", stone, saved));
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    check(saved.get("alice's save").to().equals(mine), "into her own folder: " + saved.value);
                    EditRejected otherLoad = refusal(() -> clips.loadPalette(bob, mine, new Captured<>()));
                    check(otherLoad.reason() == RejectReason.NO_PERMISSION, "bob loading her palette: " + otherLoad);
                    run(() -> clips.loadPalette(h.player, mine, byAdmin));

                    // Pushes: the owner and admins see it, other players nothing.
                    LibraryChange change = saved.value;
                    check(clips.shownTo(bob, change).isEmpty(), "shown to another player");
                    check(clips.shownTo(alice, change).equals(java.util.Optional.of(change)), "not shown to alice");
                    check(clips.shownTo(h.player, change).equals(java.util.Optional.of(change)), "not shown to admins");

                    // A move never changes a file's kind: refused before any slot or file work.
                    EditRejected toSchem = refusal(() -> clips.move(alice, false, mine, alices + "/mine.schem",
                            new Captured<>()));
                    check(toSchem.reason() == RejectReason.INVALID && toSchem.getMessage().contains("kind"),
                            "a palette renamed into a schematic: " + toSchem);
                    EditRejected toPalette = refusal(() -> clips.move(alice, false, alices + "/x.schem",
                            alices + "/x.palette.json", new Captured<>()));
                    check(toPalette.reason() == RejectReason.INVALID, "a schematic renamed into a palette");
                    check(clips.requests(alice.getUuid()) == 0, "a refusal left a slot taken");

                    // One library write per player at a time: the second is refused while the first holds the slot.
                    run(() -> clips.savePalette(alice, "one.palette.json", stone, first));
                    EditRejected busy = refusal(() -> clips.savePalette(alice, "two.palette.json", stone,
                            new Captured<>()));
                    check(busy.reason() == RejectReason.QUEUE_FULL, "a second write while one runs: " + busy);
                    EditRejected busyMove = refusal(() -> clips.move(alice, false, mine, alices + "/m.palette.json",
                            new Captured<>()));
                    check(busyMove.reason() == RejectReason.QUEUE_FULL, "a change while a save runs: " + busyMove);
                })
                .createAndAdd(() -> {
                    check(byAdmin.get("admin load").palette().equals(stone), "an admin loads any folder's palette");
                    first.get("first save");
                    check(clips.requests(alice.getUuid()) == 0, "the slot is free once answered");
                    check(Files.isRegularFile(root.resolve(alices).resolve("one.palette.json")), "first save missing");
                    check(!Files.exists(root.resolve(alices).resolve("two.palette.json")), "a refused save written");

                    // A player leaving while their save runs: the file is still written and the slots come back.
                    run(() -> clips.savePalette(alice, "leaving.palette.json", stone, leaving));
                    EditServiceHost.playerLeft(h.service, null, alice); // what the DISCONNECT hook does
                })
                .createAndAdd(() -> {
                    leaving.get("save of a player who left");
                    check(Files.isRegularFile(root.resolve(alices).resolve("leaving.palette.json")), "the save lost");
                    check(clips.requests(alice.getUuid()) == 0, "a slot stayed taken after the player left");
                    ClipTestSupport.deleteTree(root.getParent());
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * The production path: the {@link ClipboardService} the network layer gets ({@link EditServiceHost#clipboards()})
     * forwards palette saves and loads and the library changes (new folder, move, delete, and who is shown them) to the
     * running server's library. Before it forwarded them, all of these were refused {@code DISABLED} in a real server.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_palette_host", tickLimit = LIMIT)
    public void theHostServiceForwardsPalettesAndLibraryChanges(TestContext context) {
        Harness h = new Harness(context);
        ClipboardService host = EditServiceHost.clipboards();
        check(EditServiceHost.findClipboards(context.getWorld().getServer()).isPresent(), "the host runs a library");
        Path root = ServerClipboards.defaultLibraryRoot();
        String folder = "palette-host-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        BlockPalette moss = palette("minecraft:moss_block", "minecraft:seagrass");
        Captured<LibraryChange> made = new Captured<>();
        Captured<LibraryChange> saved = new Captured<>();
        Captured<ClipboardService.LoadedPalette> loaded = new Captured<>();
        Captured<LibraryChange> moved = new Captured<>();
        Captured<LibraryChange> deleted = new Captured<>();
        Captured<LibraryChange> folderGone = new Captured<>();
        run(() -> host.createFolder(h.player, folder, made));
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    check(made.get("new folder").equals(new LibraryChange(true, "", folder)), "folder " + made.value);
                    run(() -> host.savePalette(h.player, folder + "/a.palette.json", moss, saved));
                })
                .createAndAdd(() -> {
                    check(saved.get("save").equals(new LibraryChange(false, "", folder + "/a.palette.json")),
                            "save " + saved.value);
                    check(Files.isRegularFile(root.resolve(folder).resolve("a.palette.json")), "no file written");
                    check(host.shownTo(h.player, saved.value).equals(java.util.Optional.of(saved.value)),
                            "pushes are decided by the running library");
                    run(() -> host.loadPalette(h.player, folder + "/a.palette.json", loaded));
                })
                .createAndAdd(() -> {
                    check(loaded.get("load").palette().equals(moss), "load " + loaded.value);
                    run(() -> host.move(h.player, false, folder + "/a.palette.json", folder + "/b.palette.json",
                            moved));
                })
                .createAndAdd(() -> {
                    check(moved.get("move").to().equals(folder + "/b.palette.json"), "move " + moved.value);
                    check(Files.isRegularFile(root.resolve(folder).resolve("b.palette.json")), "not moved on disk");
                    run(() -> host.delete(h.player, false, folder + "/b.palette.json", deleted));
                })
                .createAndAdd(() -> {
                    deleted.get("delete");
                    run(() -> host.delete(h.player, true, folder, folderGone));
                })
                .createAndAdd(() -> {
                    folderGone.get("delete folder");
                    check(!Files.exists(root.resolve(folder)), "the folder is still there");
                    h.close();
                })
                .completeIfSuccessful();
    }
}
