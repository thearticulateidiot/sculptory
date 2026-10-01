package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.ClipboardGameTest.copy;
import static dev.sculptory.fabric.gametest.ClipboardGameTest.decorate;
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
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.nbt.NbtCompound;
import dev.sculptory.core.nbt.NbtIo;
import dev.sculptory.core.nbt.NbtLimits;
import dev.sculptory.core.nbt.NbtList;
import dev.sculptory.core.nbt.NbtTag;
import dev.sculptory.core.schem.LitematicCodec;
import dev.sculptory.core.schem.Schematic;
import dev.sculptory.core.schem.SchematicCodec;
import dev.sculptory.core.schem.SchematicFiles;
import dev.sculptory.core.schem.SchematicFormat;
import dev.sculptory.core.schem.SchematicMetadata;
import dev.sculptory.core.schem.StructureCodec;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.fabric.engine.ClipboardService;
import dev.sculptory.fabric.engine.EditRejected;
import dev.sculptory.fabric.engine.impl.ServerClipboards;
import dev.sculptory.fabric.gametest.ClipTestSupport.Captured;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.fabric.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.fabric.schem.FabricDataFixHook;
import dev.sculptory.fabric.schem.NbtBridge;
import dev.sculptory.fabric.world.BlockWriter;
import dev.sculptory.fabric.world.FabricStateSpace;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.S2C;
import dev.sculptory.protocol.v2.StreamKind;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.CommandBlockBlockEntity;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructurePlacementData;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.Vec3i;
import net.minecraft.util.math.random.Random;

/**
 * Litematica ({@code .litematic}) and vanilla structure ({@code .nbt}) files against a real server: export, read back, upload, paste and exact undo; files the game itself writes and reads; multi-region
 * Litematica files; library saves and loads; every vanilla block state through every format. Slots 1010-1019.
 */
public final class SchematicFormatsGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;

    /** A Litematica export of the decorated box: Litematica's layout, read back identically, pasted back, undone. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_formats_litematic", tickLimit = LIMIT)
    public void litematicExportImportPlaceUndo(TestContext context) {
        exportImportPlaceUndo(context, 1010, SchematicFormat.LITEMATIC, file -> {
            check(file.getInt("Version") == LitematicCodec.VERSION && file.getInt("SubVersion") == LitematicCodec.SUB_VERSION
                    && file.getInt("MinecraftDataVersion") == 3955, "not a version 6 file: " + file.keys());
            NbtCompound regions = file.getCompound("Regions");
            check(regions.size() == 1, "regions " + regions.keys());
            NbtCompound region = regions.getCompound(regions.keys().iterator().next());
            check(xyz(region.getCompound("Position")).equals(new BlockPos(-1, 0, -2)), "Position is not -anchor");
            check(xyz(region.getCompound("Size")).equals(new BlockPos(8, 4, 8)), "Size");
            NbtCompound meta = file.getCompound("Metadata");
            check(xyz(meta.getCompound("EnclosingSize")).equals(new BlockPos(8, 4, 8)) && meta.getInt("TotalVolume") == 256
                    && meta.getInt("RegionCount") == 1, "metadata " + meta);
            check(region.getList("TileEntities").size() == 4, "block entities " + region.getList("TileEntities").size());
        });
    }

    /** A structure export of the decorated box: the structure-block layout, read back identically, pasted, undone. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_formats_structure", tickLimit = LIMIT)
    public void structureExportImportPlaceUndo(TestContext context) {
        exportImportPlaceUndo(context, 1011, SchematicFormat.STRUCTURE, file -> {
            check(file.getInt("DataVersion") == 3955, "DataVersion " + file.get("DataVersion"));
            NbtList size = file.getList("size");
            check(size != null && size.elementType() == NbtTag.INT && ints(size).equals(List.of(8, 4, 8)), "size " + size);
            check(file.getList("blocks").size() == 256, "every present cell is listed, air included");
            check(file.getList("palette").size() > 3, "palette " + file.getList("palette").size());
            int[] anchor = file.getCompound("Sculptory").getIntArray("Anchor");
            check(anchor != null && anchor[0] == 1 && anchor[1] == 0 && anchor[2] == 2, "anchor");
        });
    }

    private static void exportImportPlaceUndo(TestContext context, int slot, SchematicFormat format,
                                              Consumer<NbtCompound> layout) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, slot);
        Box source = box(at[0], 100, at[1], at[0] + 7, 103, at[1] + 7);
        Box target = box(at[0] + 16, 100, at[1], at[0] + 23, 103, at[1] + 7);
        Box all = box(at[0], 100, at[1], at[0] + 31, 103, at[1] + 7);
        loadAndForce(world, all);
        decorate(h, world, source);
        WorldSnapshot before = capture(world, source);
        WorldSnapshot targetBefore = capture(world, target);
        Path root = ClipTestSupport.libraryRoot(context);
        ServerClipboards clips = ClipTestSupport.clipboards(h, root);
        Captured<ClipboardService.ClipboardInfo> copied = copy(clips, h.player, source, source.min().offset(1, 0, 2));
        Captured<ClipboardService.Outbound> exported = new Captured<>();
        Captured<ClipboardService.ClipboardInfo> uploaded = new Captured<>();
        RecordingListener paste = new RecordingListener();
        RecordingListener undo = new RecordingListener();
        net.minecraft.util.math.BlockPos command = pos(at[0] + 1, 103, at[1] + 6);
        context.createTimedTaskRunner()
                .createAndAdd(() -> run(() -> clips.export(h.player, copied.get("copy").clipboardId(), format, exported)))
                .createAndAdd(() -> {
                    ClipboardService.Outbound out = exported.get("export");
                    check(out.kind() == StreamKind.SCHEM_FILE
                            && ("clipboard" + format.extension()).equals(out.meta().get("fileName"))
                            && format.name().equals(out.meta().get("format")), "meta " + out.meta());
                    check(out.notices().isEmpty(), "export notices " + out.notices());
                    byte[] bytes = out.payload();
                    try {
                        NbtCompound file = NbtIo.readAuto(new ByteArrayInputStream(bytes), NbtLimits.DEFAULT).value();
                        check(SchematicFormat.detect(file) == format, "detected as " + SchematicFormat.detect(file));
                        layout.accept(file);
                        Schematic read = SchematicFiles.read(new ByteArrayInputStream(bytes), h.runtime.states(),
                                SchematicCodec.Limits.DEFAULT, FabricDataFixHook.get());
                        check(read.format() == format && read.report().isLossless()
                                && read.report().blockEntities() == 4, "report " + read.report());
                        Clipboard original = h.service.clipboards().get(h.player.getUuid()).orElseThrow().clipboard();
                        String difference = SchematicLibraryGameTest.difference(original, read.clipboard());
                        check(difference == null, "read back differently: " + difference);
                        check(read.clipboard().contentHash().equals(original.contentHash()), "content hash differs");
                    } catch (IOException e) {
                        throw new GameTestException("cannot read our own file: " + e);
                    }
                    ClipTestSupport.upload(clips, h.player, "roundtrip" + format.extension(), bytes, uploaded);
                })
                .createAndAdd(() -> {
                    ClipboardService.ClipboardInfo info = uploaded.get("upload");
                    // The file's command block is sanitized even for an op; nothing else is lost.
                    check(info.notices().equals(List.of(SchematicLibraryGameTest.operatorNotice(1, 0))),
                            "notices " + info.notices());
                    paste(h, h.player, info.clipboardId(), new BlockPos(at[0] + 17, 100, at[1] + 2), Transform.IDENTITY,
                            paste);
                })
                .createAndAdd(() -> check(paste.result != null, "paste running"))
                .createAndAdd(() -> {
                    check(paste.result.outcome() == JobOutcome.COMPLETED, "paste " + paste.result);
                    ClipTestSupport.checkShifted(world, before, pos(at[0] + 16, 100, at[1]), "the file pasted back",
                            p -> p.equals(command));
                    CommandBlockBlockEntity pasted = (CommandBlockBlockEntity) world.getBlockEntity(command.add(16, 0, 0));
                    check(pasted != null && pasted.getCommandExecutor().getCommand().isEmpty(), "the file's command survived");
                    h.undo(undo);
                })
                .createAndAdd(() -> check(undo.result != null, "undo running"))
                .createAndAdd(() -> {
                    check(undo.result.outcome() == JobOutcome.COMPLETED, "undo " + undo.result);
                    checkSame(targetBefore, capture(world, target), "the pasted area after undo");
                    checkSame(before, capture(world, source), "the source");
                    forceChunks(world, all, false);
                    ClipTestSupport.deleteTree(root.getParent());
                    h.close();
                })
                .completeIfSuccessful();
    }

    /** A structure larger than a structure block loads is exported with a warning; other formats warn nothing. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_formats_structure_size", tickLimit = LIMIT)
    public void structureExportOver48Warns(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 1012);
        Box wide = box(at[0], 100, at[1], at[0] + 48, 100, at[1]);
        loadAndForce(world, wide);
        Path root = ClipTestSupport.libraryRoot(context);
        ServerClipboards clips = ClipTestSupport.clipboards(h, root);
        Captured<ClipboardService.ClipboardInfo> copied = copy(clips, h.player, wide, wide.min());
        Captured<ClipboardService.Outbound> structure = new Captured<>();
        Captured<ClipboardService.Outbound> litematic = new Captured<>();
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    ClipboardService.ClipboardInfo info = copied.get("copy");
                    run(() -> clips.export(h.player, info.clipboardId(), SchematicFormat.STRUCTURE, structure));
                    run(() -> clips.export(h.player, info.clipboardId(), SchematicFormat.LITEMATIC, litematic));
                })
                .createAndAdd(() -> {
                    List<S2C.Notice> notices = structure.get("structure export").notices();
                    check(notices.equals(List.of(new S2C.Notice(S2C.Notice.Level.WARN,
                            ServerClipboards.NOTICE_EXPORT_STRUCTURE_SIZE, List.of("49", "1", "1", "48")))),
                            "structure notices " + notices);
                    check(litematic.get("litematic export").notices().isEmpty(), "Litematica has no such limit");
                    forceChunks(world, wide, false);
                    ClipTestSupport.deleteTree(root.getParent());
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * Files the game writes and reads: a structure saved by the game's own {@code StructureTemplate} imports and pastes
     * as the world it was saved from, and our structure export loads and places in the game's own reader as our paste
     * does.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_formats_vanilla_structure", tickLimit = LIMIT)
    public void vanillaStructureFilesInterop(TestContext context) throws IOException {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 1013);
        Box source = box(at[0], 100, at[1], at[0] + 7, 103, at[1] + 7);
        Box all = box(at[0], 100, at[1], at[0] + 47, 103, at[1] + 7);
        loadAndForce(world, all);
        decorate(h, world, source);
        WorldSnapshot before = capture(world, source);
        net.minecraft.util.math.BlockPos command = pos(at[0] + 1, 103, at[1] + 6);
        // The game's own structure file of the box.
        StructureTemplate saved = new StructureTemplate();
        saved.saveFromWorld(world, pos(at[0], 100, at[1]), new Vec3i(8, 4, 8), false, Blocks.STRUCTURE_VOID);
        ByteArrayOutputStream vanillaFile = new ByteArrayOutputStream();
        net.minecraft.nbt.NbtIo.writeCompressed(saved.writeNbt(new net.minecraft.nbt.NbtCompound()), vanillaFile);
        Path root = ClipTestSupport.libraryRoot(context);
        ServerClipboards clips = ClipTestSupport.clipboards(h, root);
        Captured<ClipboardService.ClipboardInfo> uploaded = new Captured<>();
        Captured<ClipboardService.ClipboardInfo> copied = copy(clips, h.player, source, source.min());
        Captured<ClipboardService.Outbound> exported = new Captured<>();
        RecordingListener paste = new RecordingListener();
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    // The copy goes first: an upload replaces the player's clipboard.
                    ClipboardService.ClipboardInfo info = copied.get("copy");
                    run(() -> clips.export(h.player, info.clipboardId(), SchematicFormat.STRUCTURE, exported));
                })
                .createAndAdd(() -> {
                    exported.get("export");
                    ClipTestSupport.upload(clips, h.player, "vanilla.nbt", vanillaFile.toByteArray(), uploaded);
                })
                .createAndAdd(() -> {
                    ClipboardService.ClipboardInfo info = uploaded.get("upload of the game's file");
                    check(info.dims().equals(new BlockPos(8, 4, 8)) && info.cells() == 256, "info " + info);
                    check(info.notices().equals(List.of(SchematicLibraryGameTest.operatorNotice(1, 0))),
                            "notices " + info.notices());
                    paste(h, h.player, info.clipboardId(), new BlockPos(at[0] + 16, 100, at[1]), Transform.IDENTITY,
                            paste);
                })
                .createAndAdd(() -> check(paste.result != null, "paste running"))
                .createAndAdd(() -> {
                    check(paste.result.outcome() == JobOutcome.COMPLETED, "paste " + paste.result);
                    ClipTestSupport.checkShifted(world, before, pos(at[0] + 16, 100, at[1]),
                            "the game's structure file pasted", p -> p.equals(command));
                    // Now the other way: our export, read and placed by the game.
                    byte[] ours = exported.get("export").payload();
                    try {
                        net.minecraft.nbt.NbtCompound nbt = net.minecraft.nbt.NbtIo.readCompressed(
                                new ByteArrayInputStream(ours), NbtSizeTracker.ofUnlimitedBytes());
                        StructureTemplate template = new StructureTemplate();
                        template.readNbt(Registries.BLOCK.getReadOnlyWrapper(), nbt);
                        check(template.getSize().equals(new Vec3i(8, 4, 8)), "the game reads the size " + template.getSize());
                        net.minecraft.util.math.BlockPos placeAt = pos(at[0] + 32, 100, at[1]);
                        template.place(world, placeAt, placeAt, new StructurePlacementData().setUpdateNeighbors(false),
                                Random.create(1), Block.NOTIFY_LISTENERS);
                    } catch (IOException e) {
                        throw new GameTestException("the game cannot read our structure file: " + e);
                    }
                    ClipTestSupport.checkShifted(world, before, pos(at[0] + 32, 100, at[1]),
                            "our structure file placed by the game", p -> p.equals(command));
                    forceChunks(world, all, false);
                    ClipTestSupport.deleteTree(root.getParent());
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * A Litematica file of two regions, one with a negative size: each region lands at its place (the chest keeps its
     * block entity), the cells between regions are left alone (a marker block survives), and one undo takes the paste
     * back exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_formats_regions", tickLimit = LIMIT)
    public void multiRegionLitematicPlacesEachRegionAndUndoes(TestContext context) throws IOException {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        FabricStateSpace states = h.runtime.states();
        int[] at = regionCorner(context, 1014);
        Box area = box(at[0], 100, at[1], at[0] + 15, 104, at[1] + 7);
        loadAndForce(world, area);
        // Region a: 2 × 1 × 2 of stone with a chest; region b: 3 × 2 × 2 of dirt, written with a negative size.
        Clipboard.Builder a = Clipboard.builder(states, new BlockPos(2, 1, 2));
        for (int x = 0; x < 2; x++) {
            for (int z = 0; z < 2; z++) a.set(x, 0, z, h.state("minecraft:stone"));
        }
        a.set(1, 0, 1, h.state("minecraft:chest[facing=north]"));
        a.setTile(1, 0, 1, dev.sculptory.core.nbt.BlockEntityNbt.toNbtBytes("minecraft:chest",
                NbtCompound.builder().putString("CustomName", "\"Region a\"").build()));
        Clipboard.Builder b = Clipboard.builder(states, new BlockPos(3, 2, 2));
        for (int y = 0; y < 2; y++) {
            for (int z = 0; z < 2; z++) {
                for (int x = 0; x < 3; x++) b.set(x, y, z, h.state("minecraft:dirt"));
            }
        }
        NbtCompound regionA = onlyRegion(LitematicCodec.encode(a.build(), SchematicMetadata.EMPTY, 3955));
        NbtCompound regionB = onlyRegion(LitematicCodec.encode(b.build(), SchematicMetadata.EMPTY, 3955)).toBuilder()
                // x 5..7, y 1..2, z 3..4 from its far corner: Position + Size - sign(Size).
                .put("Position", xyzTag(7, 1, 4))
                .put("Size", xyzTag(-3, 2, -2))
                .build();
        NbtCompound file = NbtCompound.builder()
                .putInt("Version", 5)
                .putInt("MinecraftDataVersion", 3955)
                .put("Metadata", NbtCompound.builder().putString("Name", "two regions").build())
                .put("Regions", NbtCompound.builder().put("a", regionA).put("b", regionB).build())
                .build();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NbtIo.writeGzip(bytes, "", file);
        // A marker between the regions, where the file has no cells.
        int x0 = at[0] + 2, y0 = 101, z0 = at[1] + 1;
        BlockWriter writer = h.runtime.writer(world, new BlockWriter.Options(false, true));
        writer.write(x0 + 3, y0, z0 + 1, h.state("minecraft:glass"), null);
        WorldSnapshot before = capture(world, area);
        Path root = ClipTestSupport.libraryRoot(context);
        ServerClipboards clips = ClipTestSupport.clipboards(h, root);
        Captured<ClipboardService.ClipboardInfo> uploaded = new Captured<>();
        RecordingListener paste = new RecordingListener();
        RecordingListener undo = new RecordingListener();
        ClipTestSupport.upload(clips, h.player, "regions.litematic", bytes.toByteArray(), uploaded);
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    ClipboardService.ClipboardInfo info = uploaded.get("upload");
                    check(info.dims().equals(new BlockPos(8, 3, 5)) && info.anchor().equals(BlockPos.ORIGIN)
                            && info.cells() == 4 + 12, "info " + info);
                    check(info.notices().isEmpty(), "notices " + info.notices());
                    paste(h, h.player, info.clipboardId(), new BlockPos(x0, y0, z0), Transform.IDENTITY, paste);
                })
                .createAndAdd(() -> check(paste.result != null, "paste running"))
                .createAndAdd(() -> {
                    check(paste.result.outcome() == JobOutcome.COMPLETED, "paste " + paste.result);
                    check(world.getBlockState(pos(x0, y0, z0)).isOf(Blocks.STONE), "region a at the origin");
                    check(world.getBlockState(pos(x0 + 1, y0, z0 + 1)).isOf(Blocks.CHEST)
                            && world.getBlockEntity(pos(x0 + 1, y0, z0 + 1)) != null, "region a's chest");
                    for (int y = 1; y <= 2; y++) {
                        for (int z = 3; z <= 4; z++) {
                            for (int x = 5; x <= 7; x++) {
                                check(world.getBlockState(pos(x0 + x, y0 + y, z0 + z)).isOf(Blocks.DIRT),
                                        "region b at " + x + "," + y + "," + z);
                            }
                        }
                    }
                    check(world.getBlockState(pos(x0 + 3, y0, z0 + 1)).isOf(Blocks.GLASS), "the gap was written");
                    h.undo(undo);
                })
                .createAndAdd(() -> check(undo.result != null, "undo running"))
                .createAndAdd(() -> {
                    check(undo.result.outcome() == JobOutcome.COMPLETED, "undo " + undo.result);
                    checkSame(before, capture(world, area), "the area after undo");
                    forceChunks(world, area, false);
                    ClipTestSupport.deleteTree(root.getParent());
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * The library keeps assets in every format: a clipboard saved as {@code .litematic} and {@code .nbt} (and
     * {@code .schem}) is listed with its extension, and loading each gives the same clipboard back.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_formats_library", tickLimit = LIMIT)
    public void libraryKeepsAssetsInEveryFormat(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 1015);
        Box source = box(at[0], 100, at[1], at[0] + 7, 103, at[1] + 7);
        loadAndForce(world, source);
        decorate(h, world, source);
        Path root = ClipTestSupport.libraryRoot(context);
        ServerClipboards clips = ClipTestSupport.clipboards(h, root);
        Captured<ClipboardService.ClipboardInfo> copied = copy(clips, h.player, source, source.min().offset(1, 0, 2));
        List<String> paths = List.of("tests/house.schem", "tests/house.litematic", "tests/house.nbt");
        List<Captured<ClipboardService.Saved>> saves = new ArrayList<>();
        Captured<ClipboardService.Listing> listed = new Captured<>();
        List<Captured<ClipboardService.ClipboardInfo>> loads = new ArrayList<>();
        List<Clipboard> loaded = new ArrayList<>();
        context.createTimedTaskRunner()
                // One library write at a time per player: the saves go one after the other.
                .createAndAdd(() -> nextSave(h, clips, copied.get("copy").clipboardId(), paths, saves))
                .createAndAdd(() -> nextSave(h, clips, copied.get("copy").clipboardId(), paths, saves))
                .createAndAdd(() -> nextSave(h, clips, copied.get("copy").clipboardId(), paths, saves))
                .createAndAdd(() -> {
                    for (int i = 0; i < paths.size(); i++) {
                        check(saves.get(i).get("save " + paths.get(i)).path().equals(paths.get(i)), "saved elsewhere");
                        try {
                            byte[] bytes = Files.readAllBytes(root.resolve(paths.get(i)));
                            NbtCompound file = NbtIo.readAuto(new ByteArrayInputStream(bytes), NbtLimits.DEFAULT).value();
                            SchematicFormat expected = SchematicFormat.ofFileName(paths.get(i));
                            check(SchematicFormat.detect(file) == expected, paths.get(i) + " is not " + expected);
                        } catch (IOException e) {
                            throw new GameTestException("the saved file is missing: " + e);
                        }
                    }
                    run(() -> clips.list(h.player, "tests", listed));
                })
                .createAndAdd(() -> {
                    List<String> entries = listed.get("list").entries().stream().map(e -> e.path()).toList();
                    check(entries.equals(List.of("tests/house.litematic", "tests/house.nbt", "tests/house.schem")),
                            "listing " + entries);
                    Captured<ClipboardService.ClipboardInfo> load = new Captured<>();
                    loads.add(load);
                    run(() -> clips.load(h.player, paths.get(loads.size() - 1), load));
                })
                .createAndAdd(() -> nextLoad(h, clips, paths, loads, loaded))
                .createAndAdd(() -> nextLoad(h, clips, paths, loads, loaded))
                .createAndAdd(() -> {
                    nextLoad(h, clips, paths, loads, loaded);
                    check(loaded.size() == 3, "loaded " + loaded.size());
                    for (int i = 1; i < loaded.size(); i++) {
                        String difference = SchematicLibraryGameTest.difference(loaded.get(0), loaded.get(i));
                        check(difference == null, paths.get(i) + " loaded differently: " + difference);
                        check(loaded.get(i).contentHash().equals(loaded.get(0).contentHash()), paths.get(i) + " hash");
                    }
                    forceChunks(world, source, false);
                    ClipTestSupport.deleteTree(root.getParent());
                    h.close();
                })
                .completeIfSuccessful();
    }

    /** Takes the answer of the last save in {@code saves} (if any), then starts the next one. */
    private static void nextSave(Harness h, ServerClipboards clips, java.util.UUID clipboardId, List<String> paths,
                                 List<Captured<ClipboardService.Saved>> saves) {
        if (!saves.isEmpty()) saves.get(saves.size() - 1).get("save " + paths.get(saves.size() - 1));
        if (saves.size() == paths.size()) return;
        Captured<ClipboardService.Saved> saved = new Captured<>();
        saves.add(saved);
        run(() -> clips.save(h.player, clipboardId, paths.get(saves.size() - 1), saved));
    }

    /** Takes the answer of the last load in {@code loads}, then starts the next one (loads replace the clipboard). */
    private static void nextLoad(Harness h, ServerClipboards clips, List<String> paths,
                                 List<Captured<ClipboardService.ClipboardInfo>> loads, List<Clipboard> loaded) {
        if (loaded.size() == loads.size()) return;
        loads.get(loads.size() - 1).get("load " + paths.get(loads.size() - 1));
        loaded.add(h.service.clipboards().get(h.player.getUuid()).orElseThrow().clipboard());
        if (loads.size() == paths.size()) return;
        Captured<ClipboardService.ClipboardInfo> load = new Captured<>();
        loads.add(load);
        run(() -> clips.load(h.player, paths.get(loads.size() - 1), load));
    }

    /**
     * Every block state of the game in one clipboard, through every format and back with the game's states: nothing
     * is unknown or lost, and the game's own {@code NbtHelper} reads each palette entry of the Litematica and structure
     * files as the state it stands for.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_formats_every_state", tickLimit = LIMIT)
    public void everyVanillaStateRoundTripsThroughEveryFormat(TestContext context) throws IOException {
        Harness h = new Harness(context);
        FabricStateSpace states = h.runtime.states();
        int n = states.size();
        int width = 256;
        Clipboard.Builder builder = Clipboard.builder(states, new BlockPos(width, 1, (n + width - 1) / width));
        for (int i = n; i < width * ((n + width - 1) / width); i++) builder.set(i % width, 0, i / width, states.air());
        for (int handle = 0; handle < n; handle++) builder.set(handle % width, 0, handle / width, handle);
        Clipboard every = builder.build();
        for (SchematicFormat format : SchematicFormat.values()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            SchematicFiles.write(format, out, every, SchematicMetadata.EMPTY, FabricDataFixHook.currentDataVersion());
            Schematic read = SchematicFiles.read(new ByteArrayInputStream(out.toByteArray()), states,
                    SchematicCodec.Limits.DEFAULT, FabricDataFixHook.get());
            check(read.format() == format && read.report().isLossless(), format + " report " + read.report());
            String difference = SchematicLibraryGameTest.difference(every, read.clipboard());
            check(difference == null, format + " read back differently: " + difference);
            check(read.clipboard().contentHash().equals(every.contentHash()), format + " content hash differs");
        }
        // The palettes are the game's own block-state compounds.
        NbtCompound structure = StructureCodec.encode(every, SchematicMetadata.EMPTY, 3955);
        List<NbtCompound> palette = new ArrayList<>(structure.getList("palette").compounds());
        NbtCompound litematic = onlyRegion(LitematicCodec.encode(every, SchematicMetadata.EMPTY, 3955));
        palette.addAll(litematic.getList("BlockStatePalette").compounds());
        int checked = 0;
        for (NbtCompound entry : palette) {
            net.minecraft.nbt.NbtCompound vanilla = NbtBridge.toMinecraft(entry, 1 << 20);
            BlockState parsed = NbtHelper.toBlockState(Registries.BLOCK.getReadOnlyWrapper(), vanilla);
            NbtCompound ours = LitematicCodec.paletteEntry(states, states.handle(parsed));
            check(entry.equals(ours), "the game reads " + entry + " as " + parsed + " (" + ours + ")");
            check(entry.equals(NbtBridge.toCore(NbtHelper.fromBlockState(parsed), NbtLimits.DEFAULT)),
                    "the game writes " + parsed + " otherwise than " + entry);
            checked++;
        }
        check(checked == 2 * n, "palette entries checked " + checked + " of " + 2 * n);
        h.close();
        context.complete();
    }

    private static NbtCompound onlyRegion(NbtCompound litematic) {
        NbtCompound regions = litematic.getCompound("Regions");
        return regions.getCompound(regions.keys().iterator().next());
    }

    private static NbtCompound xyzTag(int x, int y, int z) {
        return NbtCompound.builder().putInt("x", x).putInt("y", y).putInt("z", z).build();
    }

    private static BlockPos xyz(NbtCompound tag) {
        return tag == null ? null : new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
    }

    private static List<Integer> ints(NbtList list) {
        List<Integer> out = new ArrayList<>();
        for (NbtTag tag : list.items()) out.add(NbtTag.intValue(tag));
        return out;
    }

    private static void run(ClipboardGameTest.ThrowingRun request) {
        try {
            request.run();
        } catch (EditRejected e) {
            throw new GameTestException("refused: " + e.getMessage());
        }
    }
}
