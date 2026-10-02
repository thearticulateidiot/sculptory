package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.ClipboardGameTest.copy;
import static dev.sculptory.fabric.gametest.ClipboardGameTest.paste;
import static dev.sculptory.fabric.gametest.EditTestSupport.capture;
import static dev.sculptory.fabric.gametest.EditTestSupport.checkSame;
import static dev.sculptory.fabric.gametest.EditTestSupport.loadAndForce;
import static dev.sculptory.fabric.gametest.EngineTestSupport.box;
import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.forceChunks;
import static dev.sculptory.fabric.gametest.EngineTestSupport.pos;
import static dev.sculptory.fabric.gametest.EngineTestSupport.regionCorner;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.nbt.BlockEntityNbt;
import dev.sculptory.core.nbt.NbtCompound;
import dev.sculptory.core.nbt.NbtIo;
import dev.sculptory.core.nbt.NbtLimits;
import dev.sculptory.core.nbt.NbtList;
import dev.sculptory.core.nbt.NbtTag;
import dev.sculptory.core.schem.Schematic;
import dev.sculptory.core.schem.SchematicCodec;
import dev.sculptory.core.schem.SchematicFiles;
import dev.sculptory.core.schem.SchematicFormat;
import dev.sculptory.core.schem.SchematicMetadata;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.fabric.engine.ClipboardService;
import dev.sculptory.fabric.engine.impl.ServerClipboards;
import dev.sculptory.fabric.gametest.ClipTestSupport.Captured;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.fabric.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.fabric.schem.FabricDataFixHook;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.S2C;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.Perm;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;

/**
 * {@code .schem} export and import of Chipped and Farmer's Delight builds: states and
 * block entities survive exactly; file tiles are sanitized for everyone as for vanilla, so modded signs (Farmer's
 * Delight canvas signs) keep their text and lose click events; states this game does not have are reported and become
 * air, never another state.
 */
public final class FidelitySchematicGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;

    /**
     * Export (op and non-op), read back, upload and paste of the modded build: the file holds every state and block
     * entity, the upload reports nothing lost or removed, and the paste equals the source, canvas-sign text included.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_fidelity_schem", tickLimit = LIMIT)
    public void moddedSchemRoundTrip(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        ServerPlayerEntity builder = h.addPlayer(false);
        EditTestSupport.grant(builder, Perm.USE, Perm.CLIPBOARD, Perm.SCHEMATIC_EXPORT);
        int[] at = regionCorner(context, 220);
        int x0 = at[0] + 4, y0 = 100, z0 = at[1] + 4;
        Box source = FidelitySupport.buildBox(x0, y0, z0);
        Box all = box(x0 - 4, y0, z0 - 4, x0 + 27, y0 + 3, z0 + 11);
        loadAndForce(world, all);
        FidelitySupport.build(h, world, x0, y0, z0);
        Path root = ClipTestSupport.libraryRoot(context);
        ServerClipboards clips = ClipTestSupport.clipboards(h, root);
        WorldSnapshot[] before = new WorldSnapshot[1];
        List<Captured<ClipboardService.ClipboardInfo>> copies = new ArrayList<>();
        Captured<ClipboardService.Outbound> exported = new Captured<>();
        Captured<ClipboardService.Outbound> builderExported = new Captured<>();
        Captured<ClipboardService.ClipboardInfo> uploaded = new Captured<>();
        RecordingListener paste = new RecordingListener();
        BlockPos target = new BlockPos(x0 + 16 + 1, y0, z0 + 2);
        context.createTimedTaskRunner()
                .createAndAdd(FidelitySupport.settled(context, world, all))
                .createAndAdd(() -> {
                    before[0] = capture(world, source);
                    copies.add(copy(clips, h.player, source, source.min().offset(1, 0, 2)));
                    copies.add(copy(clips, builder, source, source.min()));
                })
                .createAndAdd(() -> {
                    copies.get(0).get("copy");
                    copies.get(1).get("builder copy");
                })
                .createAndAdd(() -> {
                    run(() -> clips.export(h.player, copies.get(0).get("copy").clipboardId(), exported));
                    run(() -> clips.export(builder, copies.get(1).get("builder copy").clipboardId(), builderExported));
                })
                .createAndAdd(() -> {
                    ClipboardService.Outbound out = exported.get("export");
                    check(out.notices().isEmpty(), "export notices " + out.notices());
                    Schematic read = read(h, out.payload());
                    check(read.report().isLossless() && read.report().blockEntities() == FidelitySupport.TILES.size(),
                            "report " + read.report());
                    Clipboard original = h.service.clipboards().get(h.player.getUuid()).orElseThrow().clipboard();
                    String difference = difference(original, read.clipboard());
                    check(difference == null, "read back differently: " + difference);
                    check(read.clipboard().contentHash().equals(original.contentHash()), "content hash differs");
                    // A non-op's export is sanitized: the canvas signs are text only, with their text.
                    ClipboardService.Outbound mine = builderExported.get("builder export");
                    check(mine.notices().isEmpty(), "builder export notices " + mine.notices());
                    Schematic builderRead = read(h, mine.payload());
                    check(signLine(builderRead.clipboard().tile(4, 1, 4)).contains("Kitchen")
                            && signLine(builderRead.clipboard().tile(6, 2, 4)).contains("Pantry"),
                            "a non-op export lost the canvas sign text");
                    ClipTestSupport.upload(clips, h.player, "kitchen.schem", out.payload(), uploaded);
                })
                .createAndAdd(() -> {
                    ClipboardService.ClipboardInfo info = uploaded.get("upload");
                    check(info.notices().isEmpty(), "upload notices " + info.notices());
                    paste(h, h.player, info.clipboardId(), target, Transform.IDENTITY, paste);
                })
                .createAndAdd(() -> check(paste.result != null, "paste running"))
                .createAndAdd(() -> {
                    check(paste.result.outcome() == JobOutcome.COMPLETED && paste.result.strippedNbt() == 0,
                            "paste " + paste.result);
                    ClipTestSupport.checkShifted(world, before[0], pos(x0 + 16, y0, z0), "the file pasted back");
                    forceChunks(world, all, false);
                    ClipTestSupport.deleteTree(root.getParent());
                    h.close();
                })
                .completeIfSuccessful();
    }

    /** The modded build exported as a Litematica file, read back identically, uploaded, pasted and undone exactly. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_fidelity_litematic", tickLimit = LIMIT)
    public void moddedLitematicRoundTrip(TestContext context) {
        moddedFormatRoundTrip(context, 1017, SchematicFormat.LITEMATIC);
    }

    /** The modded build exported as a structure file, read back identically, uploaded, pasted and undone exactly. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_fidelity_structure", tickLimit = LIMIT)
    public void moddedStructureRoundTrip(TestContext context) {
        moddedFormatRoundTrip(context, 1018, SchematicFormat.STRUCTURE);
    }

    private static void moddedFormatRoundTrip(TestContext context, int slot, SchematicFormat format) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, slot);
        int x0 = at[0] + 4, y0 = 100, z0 = at[1] + 4;
        Box source = FidelitySupport.buildBox(x0, y0, z0);
        Box all = box(x0 - 4, y0, z0 - 4, x0 + 27, y0 + 3, z0 + 11);
        Box target = source.offset(16, 0, 0);
        loadAndForce(world, all);
        FidelitySupport.build(h, world, x0, y0, z0);
        Path root = ClipTestSupport.libraryRoot(context);
        ServerClipboards clips = ClipTestSupport.clipboards(h, root);
        WorldSnapshot[] before = new WorldSnapshot[2];
        List<Captured<ClipboardService.ClipboardInfo>> copies = new ArrayList<>();
        Captured<ClipboardService.Outbound> exported = new Captured<>();
        Captured<ClipboardService.ClipboardInfo> uploaded = new Captured<>();
        RecordingListener paste = new RecordingListener();
        RecordingListener undo = new RecordingListener();
        context.createTimedTaskRunner()
                .createAndAdd(FidelitySupport.settled(context, world, all))
                .createAndAdd(() -> {
                    before[0] = capture(world, source);
                    before[1] = capture(world, target);
                    copies.add(copy(clips, h.player, source, source.min().offset(1, 0, 2)));
                })
                .createAndAdd(() -> run(() -> clips.export(h.player, copies.get(0).get("copy").clipboardId(), format,
                        exported)))
                .createAndAdd(() -> {
                    ClipboardService.Outbound out = exported.get("export");
                    check(out.notices().isEmpty(), "export notices " + out.notices());
                    Schematic read;
                    try {
                        read = SchematicFiles.read(new ByteArrayInputStream(out.payload()), h.runtime.states(),
                                SchematicCodec.Limits.DEFAULT, FabricDataFixHook.get());
                    } catch (IOException e) {
                        throw new GameTestException("unreadable " + format + " file: " + e);
                    }
                    check(read.format() == format && read.report().isLossless()
                            && read.report().blockEntities() == FidelitySupport.TILES.size(), "report " + read.report());
                    Clipboard original = h.service.clipboards().get(h.player.getUuid()).orElseThrow().clipboard();
                    String difference = difference(original, read.clipboard());
                    check(difference == null, format + " read back differently: " + difference);
                    check(read.clipboard().contentHash().equals(original.contentHash()), "content hash differs");
                    ClipTestSupport.upload(clips, h.player, "kitchen" + format.extension(), out.payload(), uploaded);
                })
                .createAndAdd(() -> {
                    ClipboardService.ClipboardInfo info = uploaded.get("upload");
                    check(info.notices().isEmpty(), "upload notices " + info.notices());
                    paste(h, h.player, info.clipboardId(), new BlockPos(x0 + 16 + 1, y0, z0 + 2), Transform.IDENTITY,
                            paste);
                })
                .createAndAdd(() -> check(paste.result != null, "paste running"))
                .createAndAdd(() -> {
                    check(paste.result.outcome() == JobOutcome.COMPLETED && paste.result.strippedNbt() == 0,
                            "paste " + paste.result);
                    ClipTestSupport.checkShifted(world, before[0], pos(x0 + 16, y0, z0), "the " + format + " file pasted");
                    h.undo(undo);
                })
                .createAndAdd(() -> check(undo.result != null, "undo running"))
                .createAndAdd(() -> {
                    check(undo.result.outcome() == JobOutcome.COMPLETED, "undo " + undo.result);
                    checkSame(before[1], capture(world, target), "the pasted area after undo");
                    checkSame(before[0], capture(world, source), "the source");
                    forceChunks(world, all, false);
                    ClipTestSupport.deleteTree(root.getParent());
                    h.close();
                })
                .completeIfSuccessful();
    }

    /** A sign message with a {@code run_command} click event. */
    private static final String CLICK = "{\"text\":\"Eat\",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/op @a\"}}";

    /**
     * A file whose Farmer's Delight canvas signs (standing and hanging) carry a click event: an upload sanitizes them
     * for everyone, ops included, so they keep their text and lose the click event, and a non-op may place them.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_fidelity_schem_signs", tickLimit = LIMIT)
    public void moddedSignsFromFilesKeepTextAndLoseClickEvents(TestContext context) throws IOException {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        ServerPlayerEntity builder = h.addPlayer(false);
        EditTestSupport.grant(builder, Perm.USE, Perm.CLIPBOARD, Perm.SCHEMATIC_IMPORT);
        int[] at = regionCorner(context, 221);
        Box region = box(at[0], 100, at[1], at[0] + 15, 103, at[1] + 7);
        loadAndForce(world, region);
        Clipboard.Builder content = Clipboard.builder(h.runtime.states(), new BlockPos(3, 1, 1));
        content.set(0, 0, 0, h.state("farmersdelight:canvas_sign[rotation=0,waterlogged=false]"));
        content.setTile(0, 0, 0, signTile("farmersdelight:canvas_sign"));
        content.set(1, 0, 0, h.state("farmersdelight:hanging_canvas_sign[attached=false,rotation=0,waterlogged=false]"));
        content.setTile(1, 0, 0, signTile("farmersdelight:hanging_canvas_sign"));
        content.set(2, 0, 0, h.state("minecraft:oak_sign[rotation=0,waterlogged=false]"));
        content.setTile(2, 0, 0, signTile("minecraft:sign"));
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        SchematicCodec.write(file, content.build(), SchematicMetadata.EMPTY, FabricDataFixHook.currentDataVersion());
        Path root = ClipTestSupport.libraryRoot(context);
        ServerClipboards clips = ClipTestSupport.clipboards(h, root);
        Captured<ClipboardService.ClipboardInfo> fromOp = new Captured<>();
        Captured<ClipboardService.ClipboardInfo> fromBuilder = new Captured<>();
        RecordingListener opPaste = new RecordingListener();
        RecordingListener builderPaste = new RecordingListener();
        ClipTestSupport.upload(clips, h.player, "signs.schem", file.toByteArray(), fromOp);
        ClipTestSupport.upload(clips, builder, "signs.schem", file.toByteArray(), fromBuilder);
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    ClipboardService.ClipboardInfo op = fromOp.get("op upload");
                    ClipboardService.ClipboardInfo mine = fromBuilder.get("builder upload");
                    S2C.Notice cleaned = new S2C.Notice(S2C.Notice.Level.WARN, ServerClipboards.NOTICE_IMPORT_OPERATOR_NBT,
                            List.of("0", "3"));
                    check(op.notices().equals(List.of(cleaned)) && mine.notices().equals(op.notices()),
                            "notices " + op.notices() + " / " + mine.notices());
                    paste(h, h.player, op.clipboardId(), new BlockPos(at[0] + 2, 100, at[1] + 2), Transform.IDENTITY,
                            opPaste);
                    paste(h, builder, mine.clipboardId(), new BlockPos(at[0] + 2, 100, at[1] + 5), Transform.IDENTITY,
                            builderPaste);
                })
                .createAndAdd(() -> check(opPaste.result != null && builderPaste.result != null, "pastes running"))
                .createAndAdd(() -> {
                    check(opPaste.result.strippedNbt() == 0 && builderPaste.result.strippedNbt() == 0,
                            "pastes " + opPaste.result + " / " + builderPaste.result);
                    for (int row : new int[] {2, 5}) {
                        for (int dx = 0; dx < 3; dx++) {
                            SignBlockEntity sign = FidelitySupport.entity(world, at[0] + 2 + dx, 100, at[1] + row,
                                    SignBlockEntity.class);
                            Text first = sign.getFrontText().getMessage(0, false);
                            check(first.getString().equals("Eat") && first.getStyle().getClickEvent() == null,
                                    "sign " + dx + " in row " + row + ": '" + first.getString() + "', click "
                                            + first.getStyle().getClickEvent());
                            check(sign.getFrontText().getMessage(1, false).getString().equals("Farmer's Delight"),
                                    "sign " + dx + " in row " + row + " lost its second line");
                        }
                    }
                    forceChunks(world, region, false);
                    ClipTestSupport.deleteTree(root.getParent());
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * A file naming states this game does not have (a missing Chipped variant, a Farmer's Delight property value and
     * property that do not exist, a block of a mod that is not installed): each is reported with its cell count and
     * becomes air, the block entities on those cells are reported as skipped, and everything else (the pot, cabinet
     * and basket contents included) pastes exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_fidelity_schem_unknown", tickLimit = LIMIT)
    public void unknownModdedStatesAreReportedNotReplaced(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 222);
        int x0 = at[0] + 4, y0 = 100, z0 = at[1] + 4;
        Box row = box(x0, y0, z0, x0 + 7, y0 + 1, z0);
        Box all = box(x0 - 4, y0, z0 - 4, x0 + 27, y0 + 3, z0 + 11);
        loadAndForce(world, all);
        FidelitySupport.build(h, world, x0, y0, z0);
        WorldSnapshot[] before = new WorldSnapshot[1];
        Map<String, String> renames = Map.of(
                "farmersdelight:stove[facing=east,lit=false]", "create:blaze_burner[facing=east]",
                "farmersdelight:skillet[facing=west,support=false,waterlogged=false]", "chipped:skillet_of_the_future",
                "farmersdelight:apple_pie[bites=2,facing=west]", "farmersdelight:apple_pie[bites=9,facing=west]",
                "farmersdelight:roast_chicken_block[facing=east,servings=3]",
                "farmersdelight:roast_chicken_block[facing=east,flavor=spicy,servings=3]");
        Path root = ClipTestSupport.libraryRoot(context);
        ServerClipboards clips = ClipTestSupport.clipboards(h, root);
        List<Captured<ClipboardService.ClipboardInfo>> copied = new ArrayList<>();
        Captured<ClipboardService.Outbound> exported = new Captured<>();
        Captured<ClipboardService.ClipboardInfo> uploaded = new Captured<>();
        RecordingListener paste = new RecordingListener();
        byte[][] file = new byte[1][];
        context.createTimedTaskRunner()
                .createAndAdd(FidelitySupport.settled(context, world, all))
                .createAndAdd(() -> {
                    before[0] = capture(world, row);
                    copied.add(copy(clips, h.player, row, row.min()));
                })
                .createAndAdd(() -> copied.get(0).get("copy"))
                .createAndAdd(() -> run(() -> clips.export(h.player, copied.get(0).value.clipboardId(), exported)))
                .createAndAdd(() -> {
                    file[0] = renamePalette(exported.get("export").payload(), renames);
                    Schematic read = read(h, file[0]);
                    check(read.report().unknownStates().keySet().equals(new java.util.TreeSet<>(renames.values()))
                            && read.report().unknownCells() == 4, "unknown states " + read.report().unknownStates());
                    check(read.report().blockEntities() == 4 && read.report().blockEntitiesSkipped() == 2,
                            "block entities " + read.report());
                    int air = h.runtime.states().air();
                    for (int x : new int[] {0, 2, 6, 7}) {
                        check(read.clipboard().get(x, 1, 0) == air && read.clipboard().tile(x, 1, 0) == null,
                                "unknown cell " + x + " became " + h.runtime.states().format(read.clipboard().get(x, 1, 0)));
                    }
                    ClipTestSupport.upload(clips, h.player, "unknown.schem", file[0], uploaded);
                })
                .createAndAdd(() -> {
                    ClipboardService.ClipboardInfo info = uploaded.get("upload");
                    List<String> keys = info.notices().stream().map(S2C.Notice::key).toList();
                    check(keys.equals(List.of(ServerClipboards.NOTICE_PREFIX + "import_unknown_states",
                            ServerClipboards.NOTICE_PREFIX + "import_block_entities_skipped")), "notices " + info.notices());
                    check(info.notices().get(0).args().subList(0, 2).equals(List.of("4", "4")),
                            "unknown-state notice " + info.notices().get(0));
                    paste(h, h.player, info.clipboardId(), new BlockPos(x0 + 16, y0, z0), Transform.IDENTITY, paste);
                })
                .createAndAdd(() -> check(paste.result != null, "paste running"))
                .createAndAdd(() -> {
                    check(paste.result.outcome() == JobOutcome.COMPLETED, "paste " + paste.result);
                    Box pasted = row.offset(16, 0, 0);
                    WorldSnapshot now = capture(world, pasted);
                    WorldSnapshot expected = capture(world, row);
                    // The unknown cells stay air (the paste skips air); the rest equals the source row.
                    for (int x : new int[] {0, 2, 6, 7}) {
                        check(world.getBlockState(pos(x0 + 16 + x, y0 + 1, z0)).isAir(),
                                "unknown cell " + x + " was filled");
                        expected.states[expected.index(x0 + x, y0 + 1, z0)] = now.get(x0 + 16 + x, y0 + 1, z0);
                        expected.tiles.remove(pos(x0 + x, y0 + 1, z0).asLong());
                    }
                    checkSame(before[0], capture(world, row), "the source row");
                    ClipTestSupport.checkShifted(world, expected, pos(x0 + 16, y0, z0), "the known cells");
                    forceChunks(world, all, false);
                    ClipTestSupport.deleteTree(root.getParent());
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * Refusal: canvas and hanging canvas sign tiles (and a vanilla sign tile) carrying a {@code Command}, on
     * command-block cells in a file. Sanitized to sign text, they are still refused by type when a non-op pastes them:
     * each command block keeps a default block entity, nothing is stripped, and undo is exact
     * ({@link SchematicLibraryGameTest#refuseSignTilesOnCommandBlocks}).
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_fidelity_schem_forged", tickLimit = LIMIT)
    public void moddedSignTilesOnCommandBlocksAreRefused(TestContext context) throws IOException {
        SchematicLibraryGameTest.refuseSignTilesOnCommandBlocks(context, 223,
                List.of("farmersdelight:canvas_sign", "farmersdelight:hanging_canvas_sign", "minecraft:sign"));
    }

    // ------------------------------------------------------------------ helpers

    private static BlockEntityData signTile(String type) {
        NbtCompound back = NbtCompound.builder()
                .put("messages", NbtList.ofStrings(List.of("\"\"", "\"\"", "\"\"", "\"\"")))
                .putString("color", "black").putByte("has_glowing_text", (byte) 0).build();
        NbtCompound front = back.toBuilder()
                .put("messages", NbtList.ofStrings(List.of(CLICK, "{\"text\":\"Farmer's Delight\"}", "\"\"", "\"\"")))
                .putString("color", "orange").build();
        return BlockEntityNbt.toNbtBytes(type, NbtCompound.builder()
                .put("front_text", front).put("back_text", back).putByte("is_waxed", (byte) 1).build());
    }

    /** The first front line of a sign tile's NBT ("" when it has none). */
    private static String signLine(BlockEntityData tile) {
        if (tile == null) return "";
        try {
            NbtCompound front = BlockEntityNbt.decode(tile).getCompound("front_text");
            NbtList messages = front == null ? null : front.getList("messages");
            return messages == null || messages.size() == 0 ? "" : messages.items().get(0).toString();
        } catch (IOException e) {
            return "";
        }
    }

    private static Schematic read(Harness h, byte[] bytes) {
        try {
            return SchematicCodec.read(new ByteArrayInputStream(bytes), h.runtime.states(), SchematicCodec.Limits.DEFAULT,
                    FabricDataFixHook.get());
        } catch (IOException e) {
            throw new GameTestException("unreadable file: " + e);
        }
    }

    /** The v3 file with palette entries renamed (a file written by a game with other mods or mod versions). */
    private static byte[] renamePalette(byte[] file, Map<String, String> renames) {
        try {
            NbtIo.Root root = NbtIo.readAuto(new ByteArrayInputStream(file), NbtLimits.DEFAULT);
            NbtCompound schem = root.value().getCompound("Schematic");
            NbtCompound blocks = schem.getCompound("Blocks");
            NbtCompound.Builder palette = NbtCompound.builder();
            int renamed = 0;
            for (Map.Entry<String, NbtTag> entry : blocks.getCompound("Palette").entries().entrySet()) {
                String name = renames.getOrDefault(entry.getKey(), entry.getKey());
                if (!name.equals(entry.getKey())) renamed++;
                palette.put(name, entry.getValue());
            }
            check(renamed == renames.size(), "renamed " + renamed + " palette entries of " + renames.size());
            NbtCompound out = root.value().toBuilder().put("Schematic", schem.toBuilder()
                    .put("Blocks", blocks.toBuilder().put("Palette", palette.build()).build()).build()).build();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            NbtIo.writeGzip(bytes, root.name(), out);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new GameTestException("cannot rewrite the file: " + e);
        }
    }

    /** The first difference between two clipboards (states, canonical tile bytes), or {@code null}. */
    private static String difference(Clipboard expected, Clipboard actual) {
        if (!expected.size().equals(actual.size())) return "size " + expected.size() + " vs " + actual.size();
        if (!expected.anchor().equals(actual.anchor())) return "anchor " + expected.anchor() + " vs " + actual.anchor();
        BlockPos size = expected.size();
        for (int y = 0; y < size.y(); y++) {
            for (int z = 0; z < size.z(); z++) {
                for (int x = 0; x < size.x(); x++) {
                    if (expected.get(x, y, z) != actual.get(x, y, z)) return "state at " + x + "," + y + "," + z;
                    BlockEntityData a = expected.tile(x, y, z), b = actual.tile(x, y, z);
                    if ((a == null) != (b == null)) return "tile presence at " + x + "," + y + "," + z;
                    if (a != null && !Arrays.equals(BlockEntityNbt.canonicalBytes(a), BlockEntityNbt.canonicalBytes(b))) {
                        return "tile at " + x + "," + y + "," + z;
                    }
                }
            }
        }
        return null;
    }

    private static void run(ClipboardGameTest.ThrowingRun request) {
        try {
            request.run();
        } catch (EditRejected e) {
            throw new GameTestException("refused: " + e.getMessage());
        }
    }
}
