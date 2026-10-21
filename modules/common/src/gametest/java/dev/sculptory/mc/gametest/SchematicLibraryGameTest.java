package dev.sculptory.mc.gametest;

import static dev.sculptory.mc.gametest.ClipboardGameTest.copy;
import static dev.sculptory.mc.gametest.ClipboardGameTest.decorate;
import static dev.sculptory.mc.gametest.ClipboardGameTest.paste;
import static dev.sculptory.mc.gametest.ClipboardGameTest.refusal;
import static dev.sculptory.mc.gametest.EditTestSupport.capture;
import static dev.sculptory.mc.gametest.EditTestSupport.checkSame;
import static dev.sculptory.mc.gametest.EditTestSupport.loadAndForce;
import static dev.sculptory.mc.gametest.EngineTestSupport.box;
import static dev.sculptory.mc.gametest.EngineTestSupport.check;
import static dev.sculptory.mc.gametest.EngineTestSupport.forceChunks;
import static dev.sculptory.mc.gametest.EngineTestSupport.pos;
import static dev.sculptory.mc.gametest.EngineTestSupport.regionCorner;
import static dev.sculptory.mc.gametest.GameTestSuite.EMPTY_STRUCTURE;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.Sha256;
import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.nbt.BlockEntityNbt;
import dev.sculptory.core.nbt.NbtCompound;
import dev.sculptory.core.nbt.NbtIo;
import dev.sculptory.core.nbt.NbtLimits;
import dev.sculptory.core.nbt.NbtList;
import dev.sculptory.core.nbt.NbtTag;
import dev.sculptory.core.schem.AssetInfo;
import dev.sculptory.core.schem.Schematic;
import dev.sculptory.core.schem.SchematicCodec;
import dev.sculptory.core.schem.SchematicMetadata;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.mc.gametest.ClipTestSupport.Captured;
import dev.sculptory.mc.gametest.EditTestSupport.Harness;
import dev.sculptory.mc.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.mc.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.mc.schem.McDataFixHook;
import dev.sculptory.mc.world.BlockWriter;
import dev.sculptory.mc.world.McStateSpace;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.protocol.v2.S2C;
import dev.sculptory.protocol.v2.StreamKind;
import dev.sculptory.server.engine.ClipboardService;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.RunOptions;
import dev.sculptory.server.engine.impl.ServerClipboards;
import dev.sculptory.server.net.PreviewPayload;
import dev.sculptory.server.platform.WriteOptions;
import dev.sculptory.server.schem.SanitizedTile;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import org.slf4j.LoggerFactory;

/**
 * Schematic export, import (data fixes, untrusted NBT) and the asset library against a real server.
 */
public final class SchematicLibraryGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;

    /** The first difference between two clipboards (states, tiles), or {@code null}. */
    static String difference(Clipboard expected, Clipboard actual) {
        if (!expected.size().equals(actual.size())) return "size " + expected.size() + " vs " + actual.size();
        if (!expected.anchor().equals(actual.anchor())) return "anchor " + expected.anchor() + " vs " + actual.anchor();
        BlockPos size = expected.size();
        for (int y = 0; y < size.y(); y++) {
            for (int z = 0; z < size.z(); z++) {
                for (int x = 0; x < size.x(); x++) {
                    int a = expected.get(x, y, z), b = actual.get(x, y, z);
                    if (a != b) return "state at " + x + "," + y + "," + z + ": " + a + " vs " + b;
                    BlockEntityData ta = expected.tile(x, y, z), tb = actual.tile(x, y, z);
                    if ((ta == null) != (tb == null)) return "tile presence at " + x + "," + y + "," + z;
                    if (ta != null && !Arrays.equals(BlockEntityNbt.canonicalBytes(ta), BlockEntityNbt.canonicalBytes(tb))) {
                        return "tile at " + x + "," + y + "," + z + ": " + decode(ta) + " vs " + decode(tb);
                    }
                }
            }
        }
        return null;
    }

    private static String decode(BlockEntityData tile) {
        try {
            return BlockEntityNbt.decode(tile).toString();
        } catch (IOException e) {
            return "undecodable " + e;
        }
    }

    /** Our v3 writer, our reader: the same blocks, block entities and anchor, and the file pastes back exactly. */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_schem_v3", timeoutTicks = LIMIT)
    public void schemV3RoundTrip(GameTestHelper context) {
        Harness h = new Harness(context);
        ServerLevel world = h.world;
        int[] at = regionCorner(context, 50);
        Box source = box(at[0], 100, at[1], at[0] + 7, 103, at[1] + 7);
        Box all = box(at[0], 100, at[1], at[0] + 31, 103, at[1] + 7);
        loadAndForce(world, all);
        decorate(h, world, source);
        WorldSnapshot before = capture(world, source);
        Path root = ClipTestSupport.libraryRoot(context);
        var clips = ClipTestSupport.clipboards(h, root);
        Captured<ClipboardService.ClipboardInfo> copied = copy(clips, h.player, source, source.min().offset(1, 0, 2));
        Captured<ClipboardService.Outbound> exported = new Captured<>();
        Captured<ClipboardService.ClipboardInfo> uploaded = new Captured<>();
        RecordingListener paste = new RecordingListener();
        check(McDataFixHook.currentDataVersion() == 3955, "data version " + McDataFixHook.currentDataVersion());
        context.startSequence()
                .thenWaitUntil(() -> {
                    ClipboardService.ClipboardInfo info = copied.get("copy");
                    try {
                        clips.export(h.player, info.clipboardId(), exported);
                    } catch (EditRejected e) {
                        throw new GameTestAssertException("export refused: " + e.getMessage());
                    }
                })
                .thenWaitUntil(() -> {
                    ClipboardService.Outbound out = exported.get("export");
                    check(out.kind() == StreamKind.SCHEM_FILE && out.meta().get("dataVersion").equals("3955"), "meta " + out.meta());
                    byte[] bytes = out.payload();
                    try {
                        NbtCompound file = NbtIo.readAuto(new ByteArrayInputStream(bytes), NbtLimits.DEFAULT).value();
                        NbtCompound schem = file.getCompound("Schematic");
                        check(schem != null && schem.getInt("Version") == 3 && schem.getInt("DataVersion") == 3955,
                                "not a v3 file: " + (schem == null ? file.keys() : schem.keys()));
                        check(Arrays.equals(schem.getIntArray("Offset"), new int[] {-1, 0, -2}), "offset");
                        Schematic read = SchematicCodec.read(new ByteArrayInputStream(bytes), h.runtime.states(),
                                SchematicCodec.Limits.DEFAULT, McDataFixHook.get());
                        check(read.report().isLossless() && read.report().blockEntities() == 4, "report " + read.report());
                        Clipboard original = h.service.clipboards().get(h.player.getUUID()).orElseThrow().clipboard();
                        String difference = difference(original, read.clipboard());
                        check(difference == null, "read back differently: " + difference);
                        check(read.clipboard().contentHash().equals(original.contentHash()), "content hash differs");
                    } catch (IOException e) {
                        throw new GameTestAssertException("cannot read our own file: " + e);
                    }
                    ClipTestSupport.upload(clips, h.player, "roundtrip.schem", bytes, uploaded);
                })
                .thenWaitUntil(() -> {
                    ClipboardService.ClipboardInfo info = uploaded.get("upload");
                    // The file's command block is sanitized even for an op; nothing else is lost.
                    check(info.notices().equals(List.of(operatorNotice(1, 0))), "notices " + info.notices());
                    paste(h, h.player, info.clipboardId(), new BlockPos(at[0] + 17, 100, at[1] + 2), Transform.IDENTITY, paste);
                })
                .thenWaitUntil(() -> check(paste.result != null, "paste running"))
                .thenWaitUntil(() -> {
                    check(paste.result.outcome() == JobOutcome.COMPLETED && paste.result.strippedNbt() == 0, "paste " + paste.result);
                    net.minecraft.core.BlockPos command = pos(at[0] + 1, 103, at[1] + 6);
                    ClipTestSupport.checkShifted(world, before, pos(at[0] + 16, 100, at[1]), "the file pasted back",
                            p -> p.equals(command));
                    CommandBlockEntity pasted = (CommandBlockEntity) world.getBlockEntity(command.offset(16, 0, 0));
                    check(pasted != null && pasted.getCommandBlock().getCommand().isEmpty(), "the file's command survived");
                    forceChunks(world, all, false);
                    ClipTestSupport.deleteTree(root.getParent());
                    h.close();
                })
                .thenSucceed();
    }

    /** A Sponge v2 file from 1.16.5 (DataVersion 2586): grass_path becomes dirt_path and the old sign text survives. */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_schem_fix", timeoutTicks = LIMIT)
    public void schemOldDataVersionFixed(GameTestHelper context) throws IOException {
        Harness h = new Harness(context);
        ServerLevel world = h.world;
        McStateSpace states = h.runtime.states();
        check("minecraft:dirt_path".equals(McDataFixHook.get().fixBlockState("minecraft:grass_path", 2586)),
                "the hook did not rename grass_path");
        NbtCompound palette = NbtCompound.builder()
                .putInt("minecraft:grass_path", 0)
                .putInt("minecraft:oak_sign[rotation=4,waterlogged=false]", 1)
                .putInt("minecraft:stone", 2)
                .build();
        NbtCompound sign = NbtCompound.builder()
                .putIntArray("Pos", new int[] {1, 0, 0})
                .putString("Id", "minecraft:sign")
                .putString("Text1", "{\"text\":\"Hello\"}")
                .putString("Text2", "{\"text\":\"from 1.16\"}")
                .putString("Text3", "{\"text\":\"\"}")
                .putString("Text4", "{\"text\":\"\"}")
                .putString("Color", "black")
                .build();
        NbtCompound v2 = NbtCompound.builder()
                .putInt("Version", 2)
                .putInt("DataVersion", 2586)
                .putShort("Width", (short) 3)
                .putShort("Height", (short) 1)
                .putShort("Length", (short) 1)
                .putIntArray("Offset", new int[] {0, 0, 0})
                .putInt("PaletteMax", 3)
                .put("Palette", palette)
                .putByteArray("BlockData", new byte[] {0, 1, 2})
                .put("BlockEntities", NbtList.of(NbtTag.COMPOUND, List.of(sign)))
                .build();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NbtIo.writeGzip(bytes, "Schematic", v2);
        Schematic read = SchematicCodec.read(new ByteArrayInputStream(bytes.toByteArray()), states,
                SchematicCodec.Limits.DEFAULT, McDataFixHook.get());
        check(read.formatVersion() == 2 && read.dataVersion() == 2586, "read " + read.formatVersion() + " " + read.dataVersion());
        check(read.report().unknownStates().isEmpty(), "unknown states " + read.report().unknownStates());
        check(states.format(read.clipboard().get(0, 0, 0)).equals("minecraft:dirt_path"),
                "grass_path became " + states.format(read.clipboard().get(0, 0, 0)));
        check(states.format(read.clipboard().get(2, 0, 0)).equals("minecraft:stone"), "stone changed");
        BlockEntityData tile = read.clipboard().tile(1, 0, 0);
        check(tile != null && tile.typeId().equals("minecraft:sign"), "sign tile " + tile);
        check(BlockEntityNbt.decode(tile).contains("front_text"), "sign NBT not upgraded: " + decode(tile));

        int[] at = regionCorner(context, 51);
        Box region = box(at[0], 100, at[1], at[0] + 7, 101, at[1] + 7);
        loadAndForce(world, region);
        Path root = ClipTestSupport.libraryRoot(context);
        var clips = ClipTestSupport.clipboards(h, root);
        Captured<ClipboardService.ClipboardInfo> uploaded = new Captured<>();
        RecordingListener paste = new RecordingListener();
        ClipTestSupport.upload(clips, h.player, "old.schem", bytes.toByteArray(), uploaded);
        context.startSequence()
                .thenWaitUntil(() -> {
                    ClipboardService.ClipboardInfo info = uploaded.get("upload");
                    paste(h, h.player, info.clipboardId(), new BlockPos(at[0] + 2, 100, at[1] + 2), Transform.IDENTITY, paste);
                })
                .thenWaitUntil(() -> check(paste.result != null, "paste running"))
                .thenWaitUntil(() -> {
                    check(world.getBlockState(pos(at[0] + 2, 100, at[1] + 2)).is(net.minecraft.world.level.block.Blocks.DIRT_PATH),
                            "no dirt path");
                    SignBlockEntity placed = (SignBlockEntity) world.getBlockEntity(pos(at[0] + 3, 100, at[1] + 2));
                    check(placed != null, "no sign block entity");
                    check(placed.getFrontText().getMessage(0, false).getString().equals("Hello")
                            && placed.getFrontText().getMessage(1, false).getString().equals("from 1.16"),
                            "sign text " + placed.getFrontText().getMessage(0, false).getString());
                    forceChunks(world, region, false);
                    ClipTestSupport.deleteTree(root.getParent());
                    h.close();
                })
                .thenSucceed();
    }

    static S2C.Notice operatorNotice(int dropped, int signs) {
        return new S2C.Notice(S2C.Notice.Level.WARN, ServerClipboards.NOTICE_IMPORT_OPERATOR_NBT,
                List.of(Integer.toString(dropped), Integer.toString(signs)));
    }

    /** A sign message with a {@code run_command} click event. */
    private static final String CLICK = "{\"text\":\"Click\",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/op @a\"}}";

    /** A v3 file: an auto command block that ops everyone, and a sign whose text runs a command when clicked. */
    private static byte[] operatorFile(Harness h) throws IOException {
        Clipboard.Builder content = Clipboard.builder(h.runtime.states(), new BlockPos(2, 1, 1));
        content.set(0, 0, 0, h.state("minecraft:command_block[facing=up]"));
        content.setTile(0, 0, 0, BlockEntityNbt.toNbtBytes("minecraft:command_block",
                NbtCompound.builder().putString("Command", "op everyone").putByte("auto", (byte) 1).build()));
        content.set(1, 0, 0, h.state("minecraft:oak_sign[rotation=0]"));
        NbtCompound back = NbtCompound.builder()
                .put("messages", NbtList.ofStrings(List.of("\"\"", "\"\"", "\"\"", "\"\"")))
                .putString("color", "black").putByte("has_glowing_text", (byte) 0).build();
        NbtCompound front = back.toBuilder()
                .put("messages", NbtList.ofStrings(List.of(CLICK, "\"\"", "\"\"", "\"\""))).build();
        content.setTile(1, 0, 0, BlockEntityNbt.toNbtBytes("minecraft:sign", NbtCompound.builder()
                .put("front_text", front).put("back_text", back).putByte("is_waxed", (byte) 0).build()));
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        SchematicCodec.write(file, content.build(), new SchematicMetadata("cmd", "test", 0L, List.of(),
                new AssetInfo(List.of(), BlockPos.ORIGIN, AssetInfo.ALL_ROTATIONS, 1)), 3955);
        return file.toByteArray();
    }

    /** The pasted command block has no command and the sign (if any) no click event. */
    private static void checkSanitized(ServerLevel world, net.minecraft.core.BlockPos command, boolean signText) {
        CommandBlockEntity block = (CommandBlockEntity) world.getBlockEntity(command);
        check(block != null && block.getCommandBlock().getCommand().isEmpty(), "a file's command survived at "
                + command.toShortString());
        SignBlockEntity sign = (SignBlockEntity) world.getBlockEntity(command.east());
        check(sign != null, "no sign");
        net.minecraft.network.chat.Component first = sign.getFrontText().getMessage(0, false);
        check(first.getStyle().getClickEvent() == null, "a click event survived");
        check(first.getString().equals(signText ? "Click" : ""), "sign text '" + first.getString() + "'");
    }

    /**
     * Operator NBT from a file is sanitized for everyone, ops included: the command block loses its command and the
     * sign its click event, but everyone, non-ops included, keeps the sign text (a sanitized sign is plain text).
     * Copies of world blocks stay trusted. The upload's clipboard slot is reserved at {@code UploadBegin}.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_schem_opnbt", timeoutTicks = LIMIT)
    public void operatorNbtStrippedForNonOpUpload(GameTestHelper context) throws IOException {
        Harness h = new Harness(context);
        ServerLevel world = h.world;
        ServerPlayer builder = h.addPlayer(false);
        EditTestSupport.grant(builder, Perm.USE, Perm.CLIPBOARD, Perm.SCHEMATIC_IMPORT);
        check(!h.runtime.permissions().mayWriteOperatorNbt(builder), "the builder may write operator NBT");
        byte[] file = operatorFile(h);
        int[] at = regionCorner(context, 52);
        Box region = box(at[0], 100, at[1], at[0] + 15, 103, at[1] + 7);
        loadAndForce(world, region);
        // A command block in the world, which the builder may copy (no protection here): world copies stay trusted.
        net.minecraft.core.BlockPos worldBlock = pos(at[0] + 10, 100, at[1] + 2);
        h.runtime.writer(world, new dev.sculptory.server.platform.WriteOptions(false, true))
                .write(worldBlock.getX(), worldBlock.getY(), worldBlock.getZ(), h.state("minecraft:command_block[facing=up]"), null);
        ((CommandBlockEntity) world.getBlockEntity(worldBlock)).getCommandBlock().setCommand("say trusted");
        Path root = ClipTestSupport.libraryRoot(context);
        var clips = ClipTestSupport.clipboards(h, root);
        Captured<ClipboardService.ClipboardInfo> fromBuilder = new Captured<>();
        Captured<ClipboardService.ClipboardInfo> fromOp = new Captured<>();
        Captured<ClipboardService.ClipboardInfo> worldCopy = new Captured<>();
        RecordingListener builderPaste = new RecordingListener();
        RecordingListener opPaste = new RecordingListener();
        RecordingListener copyPaste = new RecordingListener();
        ClipTestSupport.upload(clips, builder, "cmd.schem", file, fromBuilder);
        ClipboardService.Upload opUpload;
        try {
            opUpload = clips.beginUpload(h.player, "cmd.schem", file.length);
        } catch (EditRejected e) {
            throw new GameTestAssertException("upload refused: " + e.getMessage());
        }
        // Reserved at UploadBegin: while it transfers, the op starts no other clipboard or decode.
        check(clips.building(h.player.getUUID()), "the upload reserved nothing");
        check(refusal(() -> clips.beginUpload(h.player, "again.schem", 10)).reason() == RejectReason.QUEUE_FULL,
                "a second upload while one is reserved");
        check(refusal(() -> clips.load(h.player, "x/any.schem", new Captured<>())).reason() == RejectReason.QUEUE_FULL,
                "a library load while an upload is reserved");
        opUpload.completed(file, fromOp);
        context.startSequence()
                .thenWaitUntil(() -> {
                    ClipboardService.ClipboardInfo b = fromBuilder.get("builder upload");
                    ClipboardService.ClipboardInfo o = fromOp.get("op upload");
                    check(!clips.building(h.player.getUUID()) && clips.requests(h.player.getUUID()) == 0,
                            "the upload slot was not released");
                    check(b.notices().equals(List.of(operatorNotice(1, 1))) && o.notices().equals(b.notices()),
                            "notices " + b.notices() + " / " + o.notices());
                    // Only the player's own clipboard is a source; physics needs its permission.
                    check(refusal(() -> h.service.run(builder, new OpSpec.Paste(new SourceRef.Clipboard(o.clipboardId()),
                            new BlockPos(at[0] + 8, 100, at[1] + 2), Transform.IDENTITY, PasteOptions.DEFAULT),
                            RunOptions.DEFAULT, null)).reason() == RejectReason.INVALID, "pasted another player's clipboard");
                    check(refusal(() -> h.service.run(builder, new OpSpec.Paste(new SourceRef.Clipboard(b.clipboardId()),
                            new BlockPos(at[0] + 8, 100, at[1] + 2), Transform.IDENTITY, new PasteOptions(false, true)),
                            RunOptions.DEFAULT, null)).reason() == RejectReason.NO_PERMISSION, "physics without the node");
                    paste(h, builder, b.clipboardId(), new BlockPos(at[0] + 2, 100, at[1] + 2), Transform.IDENTITY, builderPaste);
                    paste(h, h.player, o.clipboardId(), new BlockPos(at[0] + 2, 100, at[1] + 5), Transform.IDENTITY, opPaste);
                })
                .thenWaitUntil(() -> check(builderPaste.result != null && opPaste.result != null, "pastes running"))
                .thenWaitUntil(() -> {
                    // The sanitized sign is plain text: a non-op places it too, with its text and no click event.
                    check(builderPaste.result.strippedNbt() == 0, "builder paste " + builderPaste.result);
                    check(opPaste.result.strippedNbt() == 0, "op paste " + opPaste.result);
                    checkSanitized(world, pos(at[0] + 2, 100, at[1] + 2), true);
                    checkSanitized(world, pos(at[0] + 2, 100, at[1] + 5), true);
                    try {
                        clips.copy(builder, new Box(new BlockPos(worldBlock.getX(), 100, worldBlock.getZ()),
                                        new BlockPos(worldBlock.getX(), 100, worldBlock.getZ())),
                                new BlockPos(worldBlock.getX(), 100, worldBlock.getZ()), false,
                                dev.sculptory.core.edit.CellMask.ANY, null, worldCopy);
                    } catch (EditRejected e) {
                        throw new GameTestAssertException("copy refused: " + e.getMessage());
                    }
                })
                .thenWaitUntil(() -> {
                    ClipboardService.ClipboardInfo info = worldCopy.get("world copy");
                    paste(h, builder, info.clipboardId(), new BlockPos(at[0] + 6, 100, at[1] + 2), Transform.IDENTITY, copyPaste);
                })
                .thenWaitUntil(() -> check(copyPaste.result != null, "paste running"))
                .thenWaitUntil(() -> {
                    check(copyPaste.result.strippedNbt() == 0, "paste of a world copy " + copyPaste.result);
                    CommandBlockEntity copied = (CommandBlockEntity) world.getBlockEntity(pos(at[0] + 6, 100, at[1] + 2));
                    check(copied != null && copied.getCommandBlock().getCommand().equals("say trusted"),
                            "a world copy lost its trusted command");
                    forceChunks(world, region, false);
                    ClipTestSupport.deleteTree(root.getParent());
                    h.close();
                })
                .thenSucceed();
    }

    /** An op loading a library file gets it sanitized too (a file anyone may have saved there). */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_lib_opnbt", timeoutTicks = LIMIT)
    public void libraryLoadSanitizesOperatorNbt(GameTestHelper context) throws IOException {
        Harness h = new Harness(context);
        ServerLevel world = h.world;
        int[] at = regionCorner(context, 54);
        Box region = box(at[0], 100, at[1], at[0] + 7, 101, at[1] + 7);
        loadAndForce(world, region);
        Path root = ClipTestSupport.libraryRoot(context);
        Files.createDirectories(root.resolve("planted"));
        Files.write(root.resolve("planted").resolve("cmd.schem"), operatorFile(h));
        var clips = ClipTestSupport.clipboards(h, root);
        Captured<ClipboardService.ClipboardInfo> loaded = new Captured<>();
        RecordingListener paste = new RecordingListener();
        run(() -> clips.load(h.player, "planted/cmd.schem", loaded));
        context.startSequence()
                .thenWaitUntil(() -> {
                    ClipboardService.ClipboardInfo info = loaded.get("load");
                    check(info.notices().equals(List.of(operatorNotice(1, 1))), "notices " + info.notices());
                    paste(h, h.player, info.clipboardId(), new BlockPos(at[0] + 2, 100, at[1] + 2), Transform.IDENTITY, paste);
                })
                .thenWaitUntil(() -> check(paste.result != null, "paste running"))
                .thenWaitUntil(() -> {
                    checkSanitized(world, pos(at[0] + 2, 100, at[1] + 2), true);
                    forceChunks(world, region, false);
                    ClipTestSupport.deleteTree(root.getParent());
                    h.close();
                })
                .thenSucceed();
    }

    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_schem_forged_signs", timeoutTicks = LIMIT)
    public void signTilesOnCommandBlocksAreRefused(GameTestHelper context) throws IOException {
        refuseSignTilesOnCommandBlocks(context, 56, List.of("minecraft:sign", "minecraft:hanging_sign"));
    }

    /**
     * Refusal: a file puts sign-typed tiles (sign text plus a {@code Command} and {@code auto}) on command-block
     * cells. The sanitizer reduces each to sign text, a {@link SanitizedTile}, which {@code BlockWriter} never strips;
     * what keeps it off the command block is the writer's type check. So a non-op's upload and paste leave every
     * command block with a default block entity (no command, no sign text), strip nothing, count one tile failure per
     * cell, and undo exactly. {@code signTypes} are the tiles' types (the fidelity GameTests add modded ones).
     */
    static void refuseSignTilesOnCommandBlocks(GameTestHelper context, int slot, List<String> signTypes)
            throws IOException {
        Harness h = new Harness(context);
        ServerLevel world = h.world;
        ServerPlayer builder = h.addPlayer(false);
        EditTestSupport.grant(builder, Perm.USE, Perm.CLIPBOARD, Perm.SCHEMATIC_IMPORT);
        int[] at = regionCorner(context, slot);
        int n = signTypes.size();
        Box region = box(at[0], 100, at[1], at[0] + 15, 103, at[1] + 7);
        loadAndForce(world, region);
        int command = h.state("minecraft:command_block[conditional=false,facing=up]");
        Clipboard.Builder content = Clipboard.builder(h.runtime.states(), new BlockPos(n, 1, 1));
        for (int i = 0; i < n; i++) {
            content.set(i, 0, 0, command);
            content.setTile(i, 0, 0, forgedSign(signTypes.get(i)));
        }
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        SchematicCodec.write(file, content.build(), SchematicMetadata.EMPTY, McDataFixHook.currentDataVersion());
        Box target = box(at[0] + 2, 100, at[1] + 2, at[0] + 1 + n, 100, at[1] + 2);
        WorldSnapshot before = capture(world, target);
        // A command block with its default block entity, to compare against.
        net.minecraft.core.BlockPos reference = pos(at[0] + 2, 100, at[1] + 5);
        h.runtime.writer(world, WriteOptions.DEFAULT).write(reference.getX(), reference.getY(), reference.getZ(),
                command, null);
        Path root = ClipTestSupport.libraryRoot(context);
        var clips = ClipTestSupport.clipboards(h, root);
        Captured<ClipboardService.ClipboardInfo> uploaded = new Captured<>();
        RecordingListener paste = new RecordingListener();
        RecordingListener undo = new RecordingListener();
        ClipTestSupport.upload(clips, builder, "forged.schem", file.toByteArray(), uploaded);
        context.startSequence()
                .thenWaitUntil(() -> {
                    ClipboardService.ClipboardInfo info = uploaded.get("upload");
                    check(info.notices().equals(List.of(operatorNotice(0, n))), "notices " + info.notices());
                    Clipboard held = h.service.clipboards().get(builder.getUUID()).orElseThrow().clipboard();
                    // The writer itself: each sanitized tile is refused by type, none is stripped.
                    BlockWriter writer = h.runtime.writer(world, WriteOptions.DEFAULT);
                    for (int i = 0; i < n; i++) {
                        BlockEntityData tile = held.tile(i, 0, 0);
                        check(tile instanceof SanitizedTile, "tile " + i + " is " + tile);
                        check(writer.write(at[0] + 2 + i, 101, at[1] + 5, command, tile) == null,
                                "a " + signTypes.get(i) + " tile was loaded into a command block");
                    }
                    check(writer.tileFailures() == n && writer.strippedNbt() == 0
                            && writer.firstTileFailure().contains("does not fit"), "writer: " + writer.tileFailures()
                            + " failures, " + writer.strippedNbt() + " stripped, first " + writer.firstTileFailure());
                    paste(h, builder, info.clipboardId(), target.min(), Transform.IDENTITY, paste);
                })
                .thenWaitUntil(() -> check(paste.result != null, "paste running"))
                .thenWaitUntil(() -> {
                    check(paste.result.outcome() == JobOutcome.COMPLETED && paste.result.changed() == n
                            && paste.result.strippedNbt() == 0, "paste " + paste.result);
                    net.minecraft.nbt.CompoundTag fresh = world.getBlockEntity(reference)
                            .saveWithId(world.registryAccess());
                    for (int i = 0; i < n; i++) {
                        net.minecraft.core.BlockPos cell = pos(target.min().x() + i, 100, target.min().z());
                        check(net.minecraft.world.level.block.Block.getId(world.getBlockState(cell)) == command,
                                "cell " + i + " is " + world.getBlockState(cell));
                        CommandBlockEntity block = (CommandBlockEntity) world.getBlockEntity(cell);
                        check(block != null && block.getCommandBlock().getCommand().isEmpty(),
                                "a forged " + signTypes.get(i) + " tile set a command");
                        check(fresh.equals(block.saveWithId(world.registryAccess())),
                                "command block " + i + " does not have a default block entity: "
                                        + block.saveWithId(world.registryAccess()));
                    }
                    try {
                        h.service.undo(builder, ConflictPolicy.SKIP_CONFLICTS, undo);
                    } catch (EditRejected e) {
                        throw new GameTestAssertException("undo refused: " + e.getMessage());
                    }
                })
                .thenWaitUntil(() -> check(undo.result != null, "undo running"))
                .thenWaitUntil(() -> {
                    check(undo.result.outcome() == JobOutcome.COMPLETED && undo.result.skippedConflicts() == 0,
                            "undo " + undo.result);
                    checkSame(before, capture(world, target), "after undoing the paste");
                    forceChunks(world, region, false);
                    ClipTestSupport.deleteTree(root.getParent());
                    h.close();
                })
                .thenSucceed();
    }

    /** A tile of {@code type} with sign text on both sides and a command block's {@code Command} and {@code auto}. */
    private static BlockEntityData forgedSign(String type) {
        NbtCompound side = NbtCompound.builder()
                .put("messages", NbtList.ofStrings(List.of("{\"text\":\"forged\"}", "\"\"", "\"\"", "\"\"")))
                .putString("color", "black").putByte("has_glowing_text", (byte) 0).build();
        return BlockEntityNbt.toNbtBytes(type, NbtCompound.builder().put("front_text", side).put("back_text", side)
                .putByte("is_waxed", (byte) 0).putString("Command", "op @a").putByte("auto", (byte) 1).build());
    }

    /**
     * Operator NBT does not leave the server with a player who may not handle it: a non-op's export and library save
     * of a (trusted) world copy hold the command block without its command; an op's keep it.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_schem_export_opnbt", timeoutTicks = LIMIT)
    public void exportAndSaveStripOperatorNbtForNonOps(GameTestHelper context) {
        Harness h = new Harness(context);
        ServerLevel world = h.world;
        ServerPlayer builder = h.addPlayer(false);
        EditTestSupport.grant(builder, Perm.USE, Perm.CLIPBOARD, Perm.SCHEMATIC_EXPORT);
        int[] at = regionCorner(context, 55);
        Box source = box(at[0], 100, at[1], at[0] + 7, 103, at[1] + 7);
        loadAndForce(world, source);
        decorate(h, world, source);
        Path root = ClipTestSupport.libraryRoot(context);
        var clips = ClipTestSupport.clipboards(h, root);
        Captured<ClipboardService.ClipboardInfo> builderCopy = copy(clips, builder, source, source.min());
        Captured<ClipboardService.ClipboardInfo> opCopy = copy(clips, h.player, source, source.min());
        Captured<ClipboardService.Outbound> builderExport = new Captured<>();
        Captured<ClipboardService.Outbound> opExport = new Captured<>();
        Captured<ClipboardService.Saved> builderSave = new Captured<>();
        context.startSequence()
                .thenWaitUntil(() -> {
                    run(() -> clips.export(builder, builderCopy.get("builder copy").clipboardId(), builderExport));
                    run(() -> clips.export(h.player, opCopy.get("op copy").clipboardId(), opExport));
                })
                .thenWaitUntil(() -> {
                    ClipboardService.Outbound b = builderExport.get("builder export");
                    check(b.notices().equals(List.of(new S2C.Notice(S2C.Notice.Level.WARN,
                            ServerClipboards.NOTICE_EXPORT_OPERATOR_NBT, List.of("1", "0")))), "notices " + b.notices());
                    check(commandIn(h, b.payload()) == null, "a non-op export carried the command");
                    ClipboardService.Outbound o = opExport.get("op export");
                    check(o.notices().isEmpty() && "say sculptory".equals(commandIn(h, o.payload())), "the op export");
                    run(() -> clips.save(builder, builderCopy.value.clipboardId(), "mine/copy.schem", builderSave));
                })
                .thenWaitUntil(() -> {
                    ClipboardService.Saved saved = builderSave.get("builder save");
                    check(saved.notices().size() == 1, "save notices " + saved.notices());
                    try {
                        check(commandIn(h, Files.readAllBytes(root.resolve(saved.path()))) == null,
                                "a non-op save stored the command");
                    } catch (IOException e) {
                        throw new GameTestAssertException("the saved file is missing: " + e);
                    }
                    forceChunks(world, source, false);
                    ClipTestSupport.deleteTree(root.getParent());
                    h.close();
                })
                .thenSucceed();
    }

    /** The command of the command block in a decorated file ({@code null} when it has no block entity). */
    private static String commandIn(Harness h, byte[] file) {
        try {
            Schematic read = SchematicCodec.read(new ByteArrayInputStream(file), h.runtime.states(),
                    SchematicCodec.Limits.DEFAULT, McDataFixHook.get());
            BlockEntityData tile = read.clipboard().tile(1, 3, 6);
            return tile == null ? null : BlockEntityNbt.decode(tile).getString("Command");
        } catch (IOException e) {
            throw new GameTestAssertException("unreadable file: " + e);
        }
    }

    /** A clipboard for {@code player} made in memory: 2 x 2 x 2 stone. */
    private static UUID giveClipboard(Harness h, ServerPlayer player) {
        Clipboard.Builder content = Clipboard.builder(h.runtime.states(), new BlockPos(2, 2, 2));
        for (int y = 0; y < 2; y++) {
            for (int z = 0; z < 2; z++) {
                for (int x = 0; x < 2; x++) content.set(x, y, z, h.state("minecraft:stone"));
            }
        }
        return h.service.clipboards().install(player.getUUID(), content.build()).id();
    }

    /** Traversal, absolute paths, drive letters, other separators and device names never reach the file system. */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_lib_traversal", timeoutTicks = LIMIT)
    public void libraryPathTraversalRefused(GameTestHelper context) {
        Harness h = new Harness(context);
        ServerPlayer builder = h.addPlayer(false);
        EditTestSupport.grant(builder, Perm.USE, Perm.CLIPBOARD);
        Path root = ClipTestSupport.libraryRoot(context);
        Path sandbox = root.getParent();
        var clips = ClipTestSupport.clipboards(h, root);
        UUID opClip = giveClipboard(h, h.player);
        UUID builderClip = giveClipboard(h, builder);
        List<String> bad = List.of("../evil.schem", "../../evil.schem", "a/../../evil.schem", "a/b/../c.schem",
                "/evil.schem", "//server/share/evil.schem", "C:/evil.schem", "C:evil.schem", "c:\\evil.schem",
                "a\\..\\evil.schem", "..\\evil.schem", "CON.schem", "nul.schem", "sub/COM1.schem", "lpt9.x.schem",
                ".hidden.schem", "a//b.schem", "evil.txt", "evil", "_players/../evil.schem", "a b.schem",
                "a/./b.schem", "evil.schem:stream", "trailing./x.schem");
        for (String path : bad) {
            check(refusal(() -> clips.save(h.player, opClip, path, new Captured<>())).reason() == RejectReason.INVALID,
                    "save " + path);
            check(refusal(() -> clips.load(h.player, path, new Captured<>())).reason() == RejectReason.INVALID,
                    "load " + path);
        }
        for (String folder : List.of("..", "../..", "a/..", "/", "C:", "a\\b", "CON", "a/", ".git")) {
            check(refusal(() -> clips.list(h.player, folder, new Captured<>())).reason() == RejectReason.INVALID,
                    "list " + folder);
        }
        String other = "_players/" + UUID.randomUUID();
        check(refusal(() -> clips.save(builder, builderClip, other + "/x.schem", new Captured<>())).reason()
                == RejectReason.NO_PERMISSION, "save into another player's folder");
        check(refusal(() -> clips.load(builder, other + "/x.schem", new Captured<>())).reason()
                == RejectReason.NO_PERMISSION, "load from another player's folder");
        check(refusal(() -> clips.list(builder, other, new Captured<>())).reason() == RejectReason.NO_PERMISSION,
                "list another player's folder");
        check(clips.requests(h.player.getUUID()) == 0 && !clips.building(h.player.getUUID()), "a refusal left state behind");
        Captured<ClipboardService.Saved> good = new Captured<>();
        try {
            clips.save(h.player, opClip, "ok/fine.schem", good);
        } catch (EditRejected e) {
            throw new GameTestAssertException("a valid save was refused: " + e.getMessage());
        }
        context.startSequence()
                .thenWaitUntil(() -> {
                    check(good.get("valid save").path().equals("ok/fine.schem"), "saved at " + good.value.path());
                    check(Files.isRegularFile(root.resolve("ok").resolve("fine.schem")), "the valid file is missing");
                    try (Stream<Path> files = Files.walk(sandbox)) {
                        List<Path> stray = files.filter(Files::isRegularFile)
                                .filter(p -> !p.startsWith(root) || p.getFileName().toString().contains("evil")).toList();
                        check(stray.isEmpty(), "files outside the library: " + stray);
                    } catch (IOException e) {
                        throw new GameTestAssertException("cannot scan the sandbox: " + e);
                    }
                    ClipTestSupport.deleteTree(sandbox);
                    h.close();
                })
                .thenSucceed();
    }

    /** A symbolic link inside the library that leads out of it is refused for listing, loading and saving. */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_lib_symlink", timeoutTicks = LIMIT)
    public void libraryPathSymlinkRefused(GameTestHelper context) throws IOException {
        Harness h = new Harness(context);
        Path root = ClipTestSupport.libraryRoot(context);
        Path sandbox = root.getParent();
        Path outside = Files.createDirectories(sandbox.resolve("outside"));
        Files.createDirectories(root);
        ByteArrayOutputStream secret = new ByteArrayOutputStream();
        Clipboard.Builder content = Clipboard.builder(h.runtime.states(), new BlockPos(1, 1, 1));
        content.set(0, 0, 0, h.state("minecraft:diamond_block"));
        SchematicCodec.write(secret, content.build(), SchematicMetadata.EMPTY, 3955);
        Files.write(outside.resolve("secret.schem"), secret.toByteArray());
        if (!ClipTestSupport.linkDirectory(root.resolve("escape"), outside)) {
            LoggerFactory.getLogger("sculptory").info("libraryPathSymlinkRefused skipped: neither symbolic links nor "
                    + "junctions can be made here");
            ClipTestSupport.deleteTree(sandbox);
            h.close();
            context.succeed();
            return;
        }
        var clips = ClipTestSupport.clipboards(h, root);
        UUID clip = giveClipboard(h, h.player);
        Captured<ClipboardService.Saved> saved = new Captured<>();
        Captured<ClipboardService.ClipboardInfo> loaded = new Captured<>();
        Captured<ClipboardService.Listing> listed = new Captured<>();
        try {
            clips.save(h.player, clip, "escape/new.schem", saved);
            clips.load(h.player, "escape/secret.schem", loaded);
            // At most two requests per player are in flight: a third is refused before any work.
            check(refusal(() -> clips.list(h.player, "escape", listed)).reason() == RejectReason.QUEUE_FULL,
                    "a third request in flight");
        } catch (EditRejected e) {
            throw new GameTestAssertException("refused before touching the file system: " + e.getMessage());
        }
        context.startSequence()
                .thenWaitUntil(() -> check(saved.finished() && loaded.finished(), "requests running"))
                .thenWaitUntil(() -> run(() -> clips.list(h.player, "escape", listed)))
                .thenWaitUntil(() -> check(listed.finished(), "listing running"))
                .thenWaitUntil(() -> {
                    saved.failedWith(RejectReason.INVALID, "save through the link");
                    loaded.failedWith(RejectReason.INVALID, "load through the link");
                    listed.failedWith(RejectReason.INVALID, "list through the link");
                    try (Stream<Path> files = Files.list(outside)) {
                        check(files.map(p -> p.getFileName().toString()).toList().equals(List.of("secret.schem")),
                                "something was written outside the library");
                    } catch (IOException e) {
                        throw new GameTestAssertException("cannot list: " + e);
                    }
                    ClipTestSupport.deleteTree(sandbox);
                    h.close();
                })
                .thenSucceed();
    }

    /**
     * Save a copy as an asset, find it in its folder with the same hash, preview and paste it by hash, load it as a
     * clipboard and paste that: both pastes equal the original. A player without {@code library.write} saves into
     * their own folder.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_lib_roundtrip", timeoutTicks = LIMIT)
    public void saveAssetThenLoad(GameTestHelper context) {
        Harness h = new Harness(context);
        ServerLevel world = h.world;
        ServerPlayer builder = h.addPlayer(false);
        EditTestSupport.grant(builder, Perm.USE, Perm.CLIPBOARD);
        int[] at = regionCorner(context, 53);
        Box source = box(at[0], 100, at[1], at[0] + 7, 103, at[1] + 7);
        Box all = box(at[0], 100, at[1], at[0] + 47, 103, at[1] + 7);
        loadAndForce(world, all);
        decorate(h, world, source);
        WorldSnapshot before = capture(world, source);
        Path root = ClipTestSupport.libraryRoot(context);
        var clips = ClipTestSupport.clipboards(h, root);
        Captured<ClipboardService.ClipboardInfo> copied = copy(clips, h.player, source, source.min());
        Captured<ClipboardService.Saved> saved = new Captured<>();
        Captured<ClipboardService.Listing> listed = new Captured<>();
        Captured<ClipboardService.Outbound> preview = new Captured<>();
        Captured<ClipboardService.ClipboardInfo> loaded = new Captured<>();
        Captured<ClipboardService.Saved> builderSaved = new Captured<>();
        RecordingListener assetPaste = new RecordingListener();
        RecordingListener clipboardPaste = new RecordingListener();
        String[] hash = new String[1];
        context.startSequence()
                .thenWaitUntil(() -> {
                    ClipboardService.ClipboardInfo info = copied.get("copy");
                    run(() -> clips.save(h.player, info.clipboardId(), "tests/house.schem", saved));
                    run(() -> clips.save(builder, giveClipboard(h, builder), "tests/mine.schem", builderSaved));
                })
                .thenWaitUntil(() -> {
                    ClipboardService.Saved result = saved.get("save");
                    check(result.path().equals("tests/house.schem"), "saved at " + result.path());
                    hash[0] = result.contentHash();
                    try {
                        byte[] bytes = Files.readAllBytes(root.resolve("tests").resolve("house.schem"));
                        check(Sha256.digest(bytes).hex().equals(hash[0]), "the hash is not the file's");
                    } catch (IOException e) {
                        throw new GameTestAssertException("the saved file is missing: " + e);
                    }
                    ClipboardService.Saved mine = builderSaved.get("builder save");
                    String expected = "_players/" + builder.getUUID() + "/tests/mine.schem";
                    check(mine.path().equals(expected), "the builder's save went to " + mine.path());
                    check(Files.isRegularFile(root.resolve(expected)), "the builder's file is missing");
                    run(() -> clips.list(h.player, "tests", listed));
                })
                .thenWaitUntil(() -> {
                    ClipboardService.Listing listing = listed.get("list");
                    check(listing.entries().size() == 1 && listing.entries().get(0).path().equals("tests/house.schem")
                            && listing.entries().get(0).contentHash().equals(hash[0]), "listing " + listing.entries());
                    run(() -> clips.preview(h.player, new SourceRef.Asset(hash[0]), preview));
                })
                .thenWaitUntil(() -> {
                    ClipboardService.Outbound out = preview.get("asset preview");
                    check(out.kind() == StreamKind.ASSET_PREVIEW && hash[0].equals(out.meta().get("contentHash")), "meta " + out.meta());
                    try {
                        PreviewPayload.Decoded decoded = PreviewPayload.decode(out.payload(), 64L << 20);
                        check(decoded.dims().equals(new BlockPos(8, 4, 8)) && decoded.cells() == source.volume(),
                                "preview " + decoded.dims() + " " + decoded.cells());
                        check("minecraft:chest[facing=east,type=single,waterlogged=false]".equals(decoded.get(3, 1, 3)),
                                "preview cell " + decoded.get(3, 1, 3));
                    } catch (IOException e) {
                        throw new GameTestAssertException("undecodable preview: " + e);
                    }
                    run(() -> h.service.run(h.player, new OpSpec.Paste(new SourceRef.Asset(hash[0]),
                            new BlockPos(at[0] + 16, 100, at[1]), Transform.IDENTITY, PasteOptions.DEFAULT),
                            RunOptions.DEFAULT, assetPaste));
                    run(() -> clips.load(h.player, "tests/house.schem", loaded));
                })
                .thenWaitUntil(() -> check(assetPaste.result != null && loaded.finished(), "asset paste and load running"))
                .thenWaitUntil(() -> {
                    // Library files are sanitized for everyone: the command block comes back without its command.
                    net.minecraft.core.BlockPos command = pos(at[0] + 1, 103, at[1] + 6);
                    ClipTestSupport.checkShifted(world, before, pos(at[0] + 16, 100, at[1]), "the asset pasted by hash",
                            p -> p.equals(command));
                    check(((CommandBlockEntity) world.getBlockEntity(command.offset(16, 0, 0))).getCommandBlock()
                            .getCommand().isEmpty(), "the asset kept its command");
                    ClipboardService.ClipboardInfo info = loaded.get("load");
                    check(info.dims().equals(new BlockPos(8, 4, 8)), "loaded " + info);
                    paste(h, h.player, info.clipboardId(), new BlockPos(at[0] + 32, 100, at[1]), Transform.IDENTITY, clipboardPaste);
                })
                .thenWaitUntil(() -> check(clipboardPaste.result != null, "paste running"))
                .thenWaitUntil(() -> {
                    ClipTestSupport.checkShifted(world, before, pos(at[0] + 32, 100, at[1]), "the loaded asset pasted",
                            p -> p.equals(pos(at[0] + 1, 103, at[1] + 6)));
                    check(((CommandBlockEntity) world.getBlockEntity(pos(at[0] + 33, 103, at[1] + 6))).getCommandBlock()
                            .getCommand().isEmpty(), "the loaded asset kept its command");
                    forceChunks(world, all, false);
                    ClipTestSupport.deleteTree(root.getParent());
                    h.close();
                })
                .thenSucceed();
    }

    private static void run(ClipboardGameTest.ThrowingRun request) {
        try {
            request.run();
        } catch (EditRejected e) {
            throw new GameTestAssertException("refused: " + e.getMessage());
        }
    }
}
