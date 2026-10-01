package dev.sculptory.protocol.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.Sha256;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.BrushTool;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.core.brush.Falloff;
import dev.sculptory.core.brush.SculptMode;
import dev.sculptory.core.brush.Shape;
import dev.sculptory.core.brush.ShapeSpec;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.brush.SurfacePlane;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.brush.WeatherSpec;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.MixLayout;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.mask.BlockSet;
import dev.sculptory.core.mask.EditMask;
import dev.sculptory.core.mask.MaskEntry;
import dev.sculptory.core.mask.MaskRule;
import dev.sculptory.core.palette.BlockPalette;
import dev.sculptory.core.palette.PalettePattern;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.ShapeKind;
import dev.sculptory.core.scatter.ScatterArea;
import dev.sculptory.core.schem.SchematicFormat;
import dev.sculptory.core.scatter.ScatterPlan;
import dev.sculptory.core.scatter.ScatterSettings;
import dev.sculptory.core.scatter.ScatterSource;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.tinker.EntityEdit;
import dev.sculptory.core.tinker.EntityEdits;
import dev.sculptory.core.tinker.SignText;
import dev.sculptory.core.transform.Mirror;
import dev.sculptory.core.transform.Transform;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Protocol v5 is the version of the batch after 2026-09-29 and is frozen when that batch is released: these tests hold the exact bytes of every message type (and of every variant a
 * message can carry), the order of every enum that crosses the wire, and the wire caps, against the committed fixture
 * {@code golden-v5.txt}. Until the release, streams of that batch add their messages to v5 and update the fixture
 * with them; after it, any change here (a field, a tag, an appended enum value, a cap) is a new protocol: raise
 * {@link ProtocolV2#VERSION} to 6, keep this fixture as it is, and add a {@code golden-v6.txt} with the new bytes.
 *
 * <p>A failure prints the lines the fixture would need, to copy into the next version's file.
 */
class WireGoldenTest {
    private static final String FIXTURE = "golden-v5.txt";
    private static final FakeStateSpace STATES = new FakeStateSpace();
    private static final HexFormat HEX = HexFormat.of();
    private static final UUID ID = new UUID(0x0123456789abcdefL, 0xfedcba9876543210L);
    private static final Box BOX = Box.of(new BlockPos(-3, 60, 5), new BlockPos(12, 70, 20));
    private static final String HASH = Sha256.digest(new byte[] {1, 2, 3}).hex();
    private static final String BUILD = "0.2.0-dev+6a043a85";

    /** The frozen protocol. */
    @Test
    void theProtocolIsFive() {
        assertEquals(5, ProtocolV2.VERSION, "v5 is this fixture's version: a new protocol gets its own golden file");
        assertEquals(5, Handshake.MIN_PROTOCOL);
        assertEquals(5, Handshake.MAX_PROTOCOL);
    }

    /** Every sample encodes to its fixture bytes, and the fixture bytes decode to the sample. */
    @Test
    void everyMessageEncodesAndDecodesExactlyAsFrozen() throws ProtocolException {
        Map<String, String> fixture = fixture("msg");
        Map<String, Message> samples = samples();
        List<String> wanted = new ArrayList<>();
        for (Map.Entry<String, Message> sample : samples.entrySet()) {
            String actual = HEX.formatHex(encode(sample.getValue()));
            String frozen = fixture.get(sample.getKey());
            if (!actual.equals(frozen)) {
                wanted.add("msg " + sample.getKey() + " " + actual + (frozen == null ? "   (missing)" : "   (was " + frozen + ")"));
                continue;
            }
            byte[] bytes = HEX.parseHex(frozen);
            Message decoded = sample.getValue() instanceof C2S ? Codec.decodeC2S(bytes, STATES) : Codec.decodeS2C(bytes, STATES);
            assertEquals(sample.getValue(), decoded, sample.getKey() + ": the frozen bytes decode to another message");
        }
        List<String> stale = fixture.keySet().stream().filter(name -> !samples.containsKey(name)).toList();
        if (!wanted.isEmpty() || !stale.isEmpty()) {
            fail("Protocol v5's wire bytes changed. Before v5 is released, update golden-v5.txt; after, a wire change needs protocol 6 (see WireGoldenTest).\n"
                    + String.join("\n", wanted) + (stale.isEmpty() ? "" : "\nIn the fixture but not sampled: " + stale));
        }
    }

    /** Every message type, both directions, has at least one frozen sample. */
    @Test
    void everyMessageTypeIsFrozen() {
        Set<MessageType> covered = EnumSet.noneOf(MessageType.class);
        for (Message message : samples().values()) covered.add(message.type());
        Set<MessageType> missing = EnumSet.complementOf(EnumSet.copyOf(covered));
        assertTrue(missing.isEmpty(), "message types without a golden sample: " + missing);
        // The codes too (they are in the bytes, but a renumbered type without a sample change would show here first).
        Map<String, String> codes = fixture("type");
        Map<String, String> actual = new TreeMap<>();
        for (MessageType type : MessageType.values()) actual.put(type.name(), Integer.toString(type.code()));
        assertEquals(new TreeMap<>(codes), actual, "message type codes changed:\n" + lines("type", actual));
    }

    /**
     * The value order of every enum written by ordinal: a v5 peer refuses an ordinal past its own last value, so
     * appending one is a wire change too.
     */
    @Test
    void wireEnumsKeepTheirValues() {
        Map<String, String> actual = new TreeMap<>();
        for (Class<? extends Enum<?>> type : WIRE_ENUMS) {
            actual.put(type.getName().substring(type.getName().lastIndexOf('.') + 1),
                    Arrays.stream(type.getEnumConstants()).map(Enum::name).collect(Collectors.joining(",")));
        }
        assertEquals(new TreeMap<>(fixture("enum")), actual, "a wire enum changed:\n" + lines("enum", actual));
    }

    /** The caps a v5 decoder enforces. */
    @Test
    void wireCapsKeepTheirValues() {
        Map<String, String> actual = new TreeMap<>();
        actual.put("MAX_C2S_FRAME", Integer.toString(ProtocolV2.MAX_C2S_FRAME));
        actual.put("MAX_S2C_FRAME", Integer.toString(ProtocolV2.MAX_S2C_FRAME));
        actual.put("MAX_MOD_VERSION_BYTES", Integer.toString(Codec.MAX_MOD_VERSION_BYTES));
        actual.put("MAX_PATH_BYTES", Integer.toString(Codec.MAX_PATH_BYTES));
        actual.put("MAX_NAME_BYTES", Integer.toString(Codec.MAX_NAME_BYTES));
        actual.put("MAX_TEXT_BYTES", Integer.toString(Codec.MAX_TEXT_BYTES));
        actual.put("MAX_FEATURE_BYTES", Integer.toString(Codec.MAX_FEATURE_BYTES));
        actual.put("MAX_META_KEY_BYTES", Integer.toString(Codec.MAX_META_KEY_BYTES));
        actual.put("MAX_HASH_BYTES", Integer.toString(Codec.MAX_HASH_BYTES));
        actual.put("MAX_LIBRARY_ENTRIES", Integer.toString(Codec.MAX_LIBRARY_ENTRIES));
        actual.put("MAX_NOTICE_ARGS", Integer.toString(Codec.MAX_NOTICE_ARGS));
        actual.put("MAX_SCATTER_VARIANTS", Integer.toString(Codec.MAX_SCATTER_VARIANTS));
        actual.put("MAX_SCATTER_STAMPS", Integer.toString(Codec.MAX_SCATTER_STAMPS));
        actual.put("MAX_SCATTER_OUTCOMES", Integer.toString(Codec.MAX_SCATTER_OUTCOMES));
        actual.put("MAX_DABS", Integer.toString(C2S.Dabs.MAX_DABS));
        actual.put("MAX_BUILDER_CELLS", Integer.toString(C2S.BuilderBreak.MAX_CELLS));
        actual.put("MAX_META", Integer.toString(StreamOpen.MAX_META));
        actual.put("SCATTER_PLACEMENTS_FORMAT", ScatterPlacements.FORMAT_NAME);
        actual.put("MAX_ENTITY_EDITS", Integer.toString(EntityEdits.MAX_EDITS));
        actual.put("MAX_TINKER_DATA_BYTES", Integer.toString(S2C.TinkerResult.MAX_DATA_BYTES));
        actual.put("TINKER_LINE_BYTES", Integer.toString(TinkerCodec.LINE_BYTES));
        actual.put("TINKER_ID_BYTES", Integer.toString(TinkerCodec.ID_BYTES));
        actual.put("TINKER_TEXT_BYTES", Integer.toString(TinkerCodec.TEXT_BYTES));
        actual.put("PROPERTY_NAME_BYTES", Integer.toString(CoreCodec.PROPERTY_NAME_BYTES));
        assertEquals(new TreeMap<>(fixture("cap")), actual, "a wire cap changed:\n" + lines("cap", actual));
    }

    /** The scatter placements stream payload (its own format, {@code bssp1}), frozen with the protocol. */
    @Test
    void scatterPlacementsPayloadIsFrozen() throws ProtocolException {
        List<ScatterPlan.Placement> placements = List.of(
                new ScatterPlan.Placement(new BlockPos(10, 64, -20), 0, new Transform(1, Mirror.NONE), 1),
                new ScatterPlan.Placement(new BlockPos(12, 63, -25), 2, new Transform(3, Mirror.X), 4),
                new ScatterPlan.Placement(new BlockPos(-300, 70, 900), 1, new Transform(0, Mirror.Z), 1));
        String actual = HEX.formatHex(ScatterPlacements.encode(placements));
        assertEquals(fixture("payload").get("scatter_placements"), actual,
                "the scatter placements payload changed:\npayload scatter_placements " + actual);
        assertEquals(placements, ScatterPlacements.decode(HEX.parseHex(actual), 3));
    }

    // ---------------------------------------------------------------- the samples

    @SuppressWarnings("unchecked")
    private static final List<Class<? extends Enum<?>>> WIRE_ENUMS = List.of(
            ConflictPolicy.class, EntityFilter.class, S2C.StrokeStatus.Status.class, RejectReason.class, Phase.class,
            JobOutcome.class, S2C.LibraryListing.Entry.Kind.class, S2C.Notice.Level.class, AssetAccess.Mode.class,
            StreamKind.class, PasteOptions.Into.class, BrushTool.class, Falloff.class, Shape.class, SculptMode.class,
            ShapeSpec.Kind.class, ShapeSpec.Mode.class, Facing.class, Symmetry.Mode.class, ShapeKind.class, Mirror.class,
            PalettePattern.Kind.class,
            EntityEdit.Part.class, EntityEdit.Flag.class, EntityEdit.Billboard.class,
            SchematicFormat.class, BuilderPower.class, OpLabel.class, NavigateMode.class, WeatherSpec.Mode.class);

    /**
     * Named samples: every message type, and every variant of the values messages carry. Never edit one in place once
     * v5 is released. Until v5 is released (no v5 build has shipped), a
     * change may update a sample and the fixture in place; after the release a change is protocol 6.
     */
    static Map<String, Message> samples() {
        Map<String, Message> s = new LinkedHashMap<>();
        int stone = STATES.state("minecraft:stone");
        int dirt = STATES.state("minecraft:dirt");
        int water = STATES.state("minecraft:water[level=0]");
        Region cuboid = new Region.Cuboid(BOX);
        Region sphere = new Region.Shape(BOX, ShapeKind.ELLIPSOID, Facing.UP);
        Region cylinder = new Region.Shape(BOX, ShapeKind.values()[1], Facing.EAST);
        Region uploaded = new Region.Uploaded(Sha256.digest(new byte[] {9}), BOX, 77);
        Pattern single = new Pattern.Single(stone);
        Pattern weighted = new Pattern.Weighted(new int[] {stone, dirt}, new int[] {3, 1}, 42L);
        CellMask everyMask = new CellMask.And(List.of(
                new CellMask.States(new int[] {stone, dirt}),
                new CellMask.Or(List.of(new CellMask.Blocks(List.of(new NamespacedId("minecraft:grass_block"))),
                        new CellMask.Tag(new NamespacedId("minecraft:logs")))),
                new CellMask.Not(CellMask.ANY)));
        SurfaceMask everySurface = new SurfaceMask.And(List.of(
                new SurfaceMask.SurfaceBlocks(new CellMask.States(new int[] {dirt})),
                new SurfaceMask.Elevation(-10, 120),
                new SurfaceMask.Not(new SurfaceMask.Slope(2, 5)),
                SurfaceMask.ANY));
        Symmetry rotate = new Symmetry(Symmetry.Mode.values()[Symmetry.Mode.values().length - 1], 4, 6);
        Symmetry mirror = new Symmetry(Symmetry.Mode.MIRROR_X, -7, 0);
        Transform turned = new Transform(1, Mirror.X);

        // Handshake
        s.put("hello", new C2S.Hello(5, 5, BUILD, Features.of(Features.STROKES, Features.REGION_OPS, Features.HISTORY)));
        s.put("welcome", new S2C.Welcome(5, Features.of(Features.STROKES, Features.MODDED_FACING_FALLBACK),
                new Limits(2_097_152, 1_000_000, 32, 20, 16L << 20, 2, 2_097_152, 65_536),
                new PermissionMask(0b1010_0111L), 1234567890123L, BUILD));
        s.put("welcome_no_build", new S2C.Welcome(5, Features.NONE, Limits.DEFAULTS, PermissionMask.NONE, -5L));
        s.put("incompatible", new S2C.Incompatible(5, 5, BUILD));
        s.put("incompatible_for_old_clients", new S2C.Incompatible(5, 5));
        s.put("permissions_changed", new S2C.PermissionsChanged(new PermissionMask(3), Limits.DEFAULTS));

        // Brushes
        BrushSpec raise = new BrushSpec(BrushTool.RAISE, 8, 0.5f, Falloff.SMOOTH, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0,
                1L);
        s.put("stroke_begin_terrain", new C2S.StrokeBegin(1, raise));
        s.put("stroke_begin_paint_masked", new C2S.StrokeBegin(2, new BrushSpec(BrushTool.PAINT, 5, 1f, Falloff.values()[0],
                Shape.values()[Shape.values().length - 1], weighted, everySurface, 3, 70, -9L,
                Box.of(new BlockPos(-100, -20, -100), new BlockPos(100, 200, 100)), mirror, null)));
        s.put("stroke_begin_shape", new C2S.StrokeBegin(3, new BrushSpec(BrushTool.SHAPE, 6, 1f, Falloff.SMOOTH,
                Shape.CIRCLE, single, SurfaceMask.ANY, 0, 0, 5L, null, rotate,
                new ShapeSpec(ShapeSpec.Kind.CYLINDER, 9, Facing.NORTH, ShapeSpec.Mode.values()[1], 2))));
        s.put("stroke_begin_surface_flatten", new C2S.StrokeBegin(4, new BrushSpec(BrushTool.FLATTEN, 10, 0.25f,
                Falloff.SMOOTH, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 64, 6L)
                .withSurface(new SurfacePlane(Facing.WEST, -40))));
        s.put("dabs", new C2S.Dabs(1, 5, List.of(new Dab(0, 16, 1024, -32, 255), new Dab(1, -1_000_000, 64, 7, 3))));
        s.put("stroke_end", new C2S.StrokeEnd(1));
        s.put("resync", new C2S.Resync(BOX));
        s.put("stroke_status_ok", new S2C.StrokeStatus(1, 9, S2C.StrokeStatus.Status.OK, null, 7));
        s.put("stroke_status_rejected", new S2C.StrokeStatus(1, 4, S2C.StrokeStatus.Status.REJECTED, RejectReason.AREA_BUSY));
        s.put("stroke_status_ended", new S2C.StrokeStatus(2, 11, S2C.StrokeStatus.Status.ENDED, null, -1));

        // Region operations: every op, region, pattern and mask variant
        s.put("run_op_fill", new C2S.RunOp(3, new OpSpec.Fill(cuboid, single, CellMask.ANY), false,
                ConflictPolicy.SKIP_CONFLICTS));
        s.put("run_op_fill_weighted_sphere", new C2S.RunOp(3, new OpSpec.Fill(sphere, weighted, everyMask), true,
                ConflictPolicy.OVERWRITE));
        s.put("run_op_fill_waterlog", new C2S.RunOp(3, new OpSpec.Fill(uploaded, new Pattern.Waterlog(water), CellMask.ANY),
                false, ConflictPolicy.SKIP_CONFLICTS));
        // Mix patterns: the Arranged pattern with each op layout.
        Pattern.Weighted mix = new Pattern.Weighted(new int[] {stone, dirt}, new int[] {3, 1}, 42L);
        s.put("run_op_fill_patches", new C2S.RunOp(3, new OpSpec.Fill(cuboid, new Pattern.Arranged(mix,
                new MixLayout.Patches(7)), CellMask.ANY), false, ConflictPolicy.SKIP_CONFLICTS));
        s.put("run_op_fill_gradient_mirrored", new C2S.RunOp(3, new OpSpec.Fill(cuboid, new Pattern.Arranged(mix,
                new MixLayout.Gradient(new BlockPos(-3, 60, 5), new BlockPos(12, 70, -20), 5)), CellMask.ANY, mirror), false,
                ConflictPolicy.SKIP_CONFLICTS));
        s.put("stroke_begin_palette_steepness", new C2S.StrokeBegin(5, new BrushSpec(BrushTool.PALETTE, 4, 1f,
                Falloff.CONSTANT, Shape.CIRCLE, new Pattern.Arranged(mix, new MixLayout.Steepness(12)), SurfaceMask.ANY, 2, 0,
                7L)));
        s.put("run_op_replace", new C2S.RunOp(4, new OpSpec.Replace(cylinder, new CellMask.States(new int[] {dirt}),
                new Pattern.Dry()), false, ConflictPolicy.SKIP_CONFLICTS));
        s.put("run_op_erase", new C2S.RunOp(5, new OpSpec.Erase(cuboid, new CellMask.Tag(new NamespacedId("minecraft:logs"))),
                false, ConflictPolicy.SKIP_CONFLICTS));
        s.put("run_op_hollow", new C2S.RunOp(6, new OpSpec.Hollow(cuboid, 2, single), false, ConflictPolicy.SKIP_CONFLICTS));
        s.put("run_op_walls", new C2S.RunOp(7, new OpSpec.Walls(sphere, 1, single), false, ConflictPolicy.SKIP_CONFLICTS));
        s.put("run_op_paste_clipboard", new C2S.RunOp(8, new OpSpec.Paste(new SourceRef.Clipboard(ID), new BlockPos(1, 2, 3),
                turned, new PasteOptions(true, false, true, PasteOptions.Into.values()[1])), false,
                ConflictPolicy.SKIP_CONFLICTS));
        s.put("run_op_paste_asset_symmetric", new C2S.RunOp(9, new OpSpec.Paste(new SourceRef.Asset(HASH),
                new BlockPos(-5, 70, 8), new Transform(0, Mirror.NONE), new PasteOptions(false, true, false,
                PasteOptions.Into.EVERYTHING), mirror), true, ConflictPolicy.SKIP_CONFLICTS));
        s.put("run_op_move", new C2S.RunOp(10, new OpSpec.Move(cuboid, new BlockPos(0, 5, -3), turned, single,
                EntityFilter.values()[1], rotate, PasteOptions.Into.EVERYTHING), false, ConflictPolicy.SKIP_CONFLICTS));
        s.put("run_op_stack", new C2S.RunOp(11, new OpSpec.Stack(uploaded, 0, 11, 0, 4, EntityFilter.NONE, Symmetry.NONE,
                PasteOptions.Into.values()[PasteOptions.Into.values().length - 1]), false, ConflictPolicy.SKIP_CONFLICTS));
        // The flip upside down: bit 4 of the transform byte, and the Stack op's trailing bool.
        s.put("run_op_move_upside_down", new C2S.RunOp(13, new OpSpec.Move(cuboid, new BlockPos(2, 0, 0),
                new Transform(1, Mirror.X, true), single, EntityFilter.NONE, Symmetry.NONE, PasteOptions.Into.EVERYTHING),
                false, ConflictPolicy.SKIP_CONFLICTS));
        s.put("run_op_stack_upside_down", new C2S.RunOp(14, new OpSpec.Stack(cuboid, 5, 0, 0, 2, EntityFilter.values()[1],
                Symmetry.NONE, PasteOptions.Into.EVERYTHING, true), false, ConflictPolicy.SKIP_CONFLICTS));
        s.put("run_op_scatter_commit", new C2S.RunOp(12, new OpSpec.ScatterCommit(ID), false,
                ConflictPolicy.SKIP_CONFLICTS));
        // Every tool label (the labelled op kinds: a fill, a paste, a stack, an erase, a move).
        s.put("run_op_fill_flood", new C2S.RunOp(13, new OpSpec.Fill(uploaded, new Pattern.Waterlog(water), CellMask.ANY),
                false, ConflictPolicy.SKIP_CONFLICTS, OpLabel.FLOOD));
        s.put("run_op_fill_drain", new C2S.RunOp(14, new OpSpec.Fill(uploaded, new Pattern.Dry(), CellMask.ANY), false,
                ConflictPolicy.SKIP_CONFLICTS, OpLabel.DRAIN));
        s.put("run_op_paste_road", new C2S.RunOp(15, new OpSpec.Paste(new SourceRef.Clipboard(ID), new BlockPos(1, 2, 3),
                Transform.IDENTITY, new PasteOptions(true, false, false)), false, ConflictPolicy.SKIP_CONFLICTS, OpLabel.ROAD));
        s.put("run_op_paste_roof", new C2S.RunOp(16, new OpSpec.Paste(new SourceRef.Clipboard(ID), new BlockPos(1, 2, 3),
                Transform.IDENTITY, new PasteOptions(true, false, false)), false, ConflictPolicy.SKIP_CONFLICTS, OpLabel.ROOF));
        s.put("run_op_stack_extrude", new C2S.RunOp(17, new OpSpec.Stack(cuboid, 1, 0, 0, 2, EntityFilter.NONE),
                false, ConflictPolicy.SKIP_CONFLICTS, OpLabel.EXTRUDE));
        s.put("run_op_erase_carve", new C2S.RunOp(18, new OpSpec.Erase(uploaded, CellMask.ANY), false,
                ConflictPolicy.SKIP_CONFLICTS, OpLabel.CARVE));
        s.put("run_op_move_smear", new C2S.RunOp(19, new OpSpec.Move(cuboid, new BlockPos(0, 0, 2), Transform.IDENTITY,
                single, EntityFilter.NONE), false, ConflictPolicy.SKIP_CONFLICTS, OpLabel.SMEAR));
        s.put("cancel_job", new C2S.CancelJob(ID));
        s.put("job_accepted", new S2C.JobAccepted(3, ID, 64));
        s.put("job_rejected", new S2C.JobRejected(3, RejectReason.TOO_LARGE));
        s.put("job_progress", new S2C.JobProgress(ID, 10, 64, Phase.APPLY));
        s.put("job_finished", new S2C.JobFinished(ID, JobOutcome.COMPLETED, 60, 4, 1, 2));

        // History
        s.put("undo", new C2S.Undo(4, ConflictPolicy.SKIP_CONFLICTS));
        s.put("redo", new C2S.Redo(5, ConflictPolicy.OVERWRITE));
        s.put("history_overwrite", new C2S.HistoryOverwrite(18, true, 2));
        s.put("history_state", new S2C.HistoryState(true, true, "Fill", "Paste", 4096, List.of("Fill", "Brush"),
                List.of("Paste")));

        // Clipboard, library, files
        s.put("copy", new C2S.Copy(6, cuboid, new BlockPos(0, 64, 0), false, CellMask.ANY, EntityFilter.NONE));
        s.put("copy_cut_masked", new C2S.Copy(7, uploaded, new BlockPos(-1, 60, 2), true, everyMask,
                EntityFilter.values()[EntityFilter.values().length - 1]));
        s.put("preview_request_asset", new C2S.PreviewRequest(new SourceRef.Asset(HASH)));
        s.put("preview_request_clipboard", new C2S.PreviewRequest(new SourceRef.Clipboard(ID)));
        s.put("clipboard_ready", new S2C.ClipboardReady(6, ID, new BlockPos(4, 5, 6), new BlockPos(-1, 0, 2), 120, 2048, 3));
        s.put("library_list", new C2S.LibraryList(7, "trees"));
        s.put("library_load", new C2S.LibraryLoad(8, "trees/oak.schem"));
        s.put("library_listing", new S2C.LibraryListing(7, "trees", List.of(
                new S2C.LibraryListing.Entry("big", true, 0, "", S2C.LibraryListing.Entry.Kind.FOLDER, false),
                new S2C.LibraryListing.Entry("oak.schem", false, 5120, HASH, S2C.LibraryListing.Entry.Kind.SCHEMATIC, true),
                new S2C.LibraryListing.Entry("moss.palette.json", false, 300, "", S2C.LibraryListing.Entry.Kind.PALETTE,
                        false)), true));
        s.put("save_asset", new C2S.SaveAsset(9, ID, "mine/house.schem"));
        s.put("asset_saved", new S2C.AssetSaved(9, "mine/house.schem", HASH));
        // Protocol 5: the export format (a varint after the id).
        s.put("export_clipboard", new C2S.ExportClipboard(10, ID));
        s.put("export_clipboard_litematic", new C2S.ExportClipboard(19, ID, SchematicFormat.LITEMATIC));
        s.put("export_clipboard_structure", new C2S.ExportClipboard(20, ID, SchematicFormat.STRUCTURE));
        s.put("upload_begin", new C2S.UploadBegin(11, "house.schem", 1234));
        s.put("upload_grant", new S2C.UploadGrant(11, 42, 4L << 20));
        s.put("upload_result_ok", new S2C.UploadResult(11, ID, null));
        s.put("upload_result_error", new S2C.UploadResult(11, null, "too large"));
        s.put("library_move", new C2S.LibraryMove(13, false, "trees/oak.schem", "rocks/oak.schem"));
        s.put("library_delete", new C2S.LibraryDelete(14, true, "old"));
        s.put("library_create_folder", new C2S.LibraryCreateFolder(15, "trees/big"));
        s.put("library_changed", new S2C.LibraryChanged(13, false, "trees/oak.schem", "rocks/oak.schem"));
        s.put("library_access_get", new C2S.LibraryAccessGet(20, "trees/oak.schem"));
        s.put("library_access_set", new C2S.LibraryAccessSet(21, "trees/oak.schem", AssetAccess.listed(List.of(
                new AssetAccess.Grantee(new UUID(1, 2), "Alice"), new AssetAccess.Grantee(null, "Typed")))));
        s.put("library_access_everyone", new S2C.LibraryAccess(22, "trees/oak.schem", AssetAccess.EVERYONE));
        s.put("library_access_listed", new S2C.LibraryAccess(20, "trees/oak.schem", AssetAccess.listed(List.of(
                new AssetAccess.Grantee(new UUID(1, 2), "Alice")))));
        BlockPalette palette = new BlockPalette(List.of(new BlockPalette.Entry("minecraft:moss_block", 4),
                new BlockPalette.Entry("minecraft:stone", 1)));
        s.put("palette_save", new C2S.PaletteSave(16, "palettes/moss.palette.json", palette));
        s.put("palette_load", new C2S.PaletteLoad(17, "palettes/moss.palette.json"));
        s.put("palette_data", new S2C.PaletteData(17, "palettes/moss.palette.json", palette, 1, List.of("modded:gone")));
        // Mix patterns: a palette's pattern.
        s.put("palette_save_patterned", new C2S.PaletteSave(24, "palettes/snow.palette.json", palette.withPattern(
                new PalettePattern(PalettePattern.Kind.GRADIENT, 9, 3, 20, -77L))));
        s.put("selection_upload", new C2S.SelectionUpload(19, Sha256.digest(new byte[] {7}), BOX, 40, 900));
        s.put("selection_ready", new S2C.SelectionReady(19, Sha256.digest(new byte[] {7})));
        s.put("generated_upload", new C2S.GeneratedUpload(23, BOX, 12, 300));

        // Builder mode
        s.put("builder_powers", new C2S.BuilderPowers(BuilderPower.LONG_REACH.bit() | BuilderPower.TINKER.bit()));
        s.put("builder_place", new C2S.BuilderPlace(9, false, new BlockPos(3, 64, -2), Facing.UP, 0.25f, 1f, 0.5f,
                BuilderPower.KEEP_SHAPE.bit(), Symmetry.NONE));
        s.put("builder_place_air_mirrored", new C2S.BuilderPlace(-4, true, new BlockPos(-30, 70, 12), Facing.WEST, 0f,
                0.75f, 0.125f, BuilderPower.PLACE_IN_AIR.bit() | BuilderPower.MIRROR.bit() | BuilderPower.FORCE_PLACE.bit(),
                new Symmetry(Symmetry.Mode.MIRROR_XZ, -41, 25)));
        s.put("builder_break_click", new C2S.BuilderBreak(10, 2, List.of(new BlockPos(3, 64, -2)), 0, Symmetry.NONE,
                false, true));
        s.put("builder_break_drag", new C2S.BuilderBreak(11, 3, List.of(new BlockPos(3, 64, -2), new BlockPos(4, 64, -2)),
                BuilderPower.BULLDOZER.bit() | BuilderPower.MIRROR.bit(), new Symmetry(Symmetry.Mode.ROTATE_4, 7, 9), true,
                false));
        s.put("builder_drag_end", new C2S.BuilderDragEnd(3));

        // Scatter
        s.put("scatter_preview_stamps", new C2S.ScatterPreview(12, new ScatterArea.Stamps(List.of(
                ScatterArea.Stamp.paint(10, -4, 8), ScatterArea.Stamp.erase(12, -2, 3))),
                new C2S.ScatterPreview.Settings(1L, 4, new ScatterSettings.Density.Fraction(0.5), SurfaceMask.ANY,
                        ScatterSettings.Fit.DEFAULT),
                List.of(new C2S.ScatterPreview.Variant(new ScatterSource.Held(new SourceRef.Clipboard(ID)), 1)),
                ScatterSettings.Transforms.ALL));
        s.put("scatter_preview_region", new C2S.ScatterPreview(13, new ScatterArea.Region(BOX),
                new C2S.ScatterPreview.Settings(-2L, 0, new ScatterSettings.Density.Count(25), everySurface,
                        new ScatterSettings.Fit(true, 0.75, true), new ScatterSettings.ColumnHeight(2, 4)),
                List.of(new C2S.ScatterPreview.Variant(new ScatterSource.Held(new SourceRef.Asset(HASH)), 3),
                        new C2S.ScatterPreview.Variant(new ScatterSource.Block("minecraft:pink_petals[flower_amount=3]"), 1)),
                new ScatterSettings.Transforms(2, true)));
        s.put("scatter_plan", new S2C.ScatterPlan(12, ID, 30, new TreeMap<>(Map.of("SLOPE", 3, "SPACING", 9)), 900, BOX));
        s.put("scatter_plan_empty", new S2C.ScatterPlan(14, ID, 0, new TreeMap<>(), 0, null));

        // Tinker (protocol 5): both block changes, an entity look and every entity edit, both results, the property pattern
        s.put("tinker_block_property", new C2S.TinkerBlock(24, new BlockPos(3, 64, -9), water,
                STATES.state("minecraft:water[level=3]"), null));
        s.put("tinker_block_sign", new C2S.TinkerBlock(25, new BlockPos(-3, 60, 5), water, water, new SignText(
                new SignText.Side(List.of("Hello", "", "wörld", "!"), "red", true), SignText.Side.EMPTY)));
        s.put("tinker_entity_look", new C2S.TinkerEntity(26, ID, List.of()));
        s.put("tinker_entity_every_edit", new C2S.TinkerEntity(27, ID, List.of(
                new EntityEdit.Pose(EntityEdit.Part.LEFT_ARM, -10f, 0f, 22.5f),
                new EntityEdit.Toggle(EntityEdit.Flag.NO_GRAVITY, true),
                new EntityEdit.Position(1.5, 64.0625, -20.25),
                new EntityEdit.Yaw(90f),
                new EntityEdit.ItemRotation(3),
                new EntityEdit.PaintingVariant("minecraft:kebab"),
                new EntityEdit.Transformation(new float[] {0.5f, 0f, -0.25f}, new float[] {0f, 0.7071068f, 0f, 0.7071068f},
                        new float[] {2f, 1f, 0.5f}),
                new EntityEdit.BillboardMode(EntityEdit.Billboard.CENTER),
                new EntityEdit.Brightness(15, 7),
                new EntityEdit.DisplayBlock(dirt),
                new EntityEdit.DisplayItem("minecraft:diamond_sword"),
                new EntityEdit.DisplayText("Line one\nLine two"))));
        s.put("tinker_result_done", S2C.TinkerResult.done(27, new byte[] {10, 0, 0, 3, 0, 3, 89, 97, 119, 0}));
        s.put("tinker_result_refused", S2C.TinkerResult.refused(24, RejectReason.PROTECTED, "the block is protected"));
        s.put("run_op_fill_set_property", new C2S.RunOp(28, new OpSpec.Fill(cuboid, new Pattern.SetProperty(
                STATES.state("minecraft:water[level=3]"), "level"), CellMask.ANY), false, ConflictPolicy.SKIP_CONFLICTS));

        // The WorldEdit-inspired batch (protocol 5): the global mask, every rule kind and block set entry
        BlockSet logs = new BlockSet(List.of(new BlockSet.Block(new NamespacedId("minecraft:grass_block")),
                new BlockSet.Tag(new NamespacedId("minecraft:logs")),
                new BlockSet.State(dev.sculptory.core.BlockDescriptor.parse("minecraft:water[level=3]"))));
        EditMask everyRule = new EditMask(List.of(
                MaskEntry.of(new MaskRule.Is(logs)),
                new MaskEntry(new MaskRule.OnTopOf(BlockSet.of(new BlockSet.Block(new NamespacedId("minecraft:stone")))), true),
                MaskEntry.of(new MaskRule.Under(BlockSet.of(new BlockSet.Tag(new NamespacedId("minecraft:leaves"))))),
                new MaskEntry(new MaskRule.NextTo(logs), true),
                MaskEntry.of(new MaskRule.Exposed()),
                MaskEntry.of(new MaskRule.NotAir()),
                new MaskEntry(new MaskRule.Solid(), true),
                MaskEntry.of(new MaskRule.Height(-64, 120)),
                MaskEntry.of(new MaskRule.Slope(1, MaskRule.MAX_SLOPE)),
                MaskEntry.of(new MaskRule.Inside(sphere)),
                MaskEntry.of(new MaskRule.Chance(35, -77L))), true);
        s.put("set_edit_mask_off", new C2S.SetEditMask(29, EditMask.NONE));
        s.put("set_edit_mask_every_rule", new C2S.SetEditMask(30, everyRule));
        s.put("edit_mask_state_accepted", S2C.EditMaskState.accepted(30));
        s.put("edit_mask_state_refused", S2C.EditMaskState.refused(31, RejectReason.SELECTION_NOT_LOADED,
                "the selection of the Inside rule is not on the server"));
        s.put("stroke_begin_rules_mask", new C2S.StrokeBegin(6, new BrushSpec(BrushTool.PAINT, 5, 1f, Falloff.SMOOTH,
                Shape.CIRCLE, single, new SurfaceMask.And(List.of(new SurfaceMask.Rules(new EditMask(List.of(
                        MaskEntry.of(new MaskRule.Is(logs)), new MaskEntry(new MaskRule.Slope(0, 3), true)), false)),
                        new SurfaceMask.Rules(EditMask.NONE))), 1, 0, 8L)));
        // Better Replace, Overlay, Naturalize and Update blocks
        s.put("run_op_replace_keep_shape", new C2S.RunOp(32, new OpSpec.Replace(cuboid,
                new CellMask.Blocks(List.of(new NamespacedId("minecraft:grass_block"))), new Pattern.KeepShape(weighted),
                mirror), false, ConflictPolicy.SKIP_CONFLICTS));
        s.put("run_op_replace_family", new C2S.RunOp(33, new OpSpec.Replace(uploaded,
                new CellMask.Blocks(List.of(new NamespacedId("minecraft:oak_planks"), new NamespacedId("minecraft:oak_stairs"))),
                new Pattern.Remap(List.of(
                        new Pattern.BlockSwap(new NamespacedId("minecraft:oak_planks"), new NamespacedId("minecraft:spruce_planks")),
                        new Pattern.BlockSwap(new NamespacedId("minecraft:oak_stairs"), new NamespacedId("minecraft:spruce_stairs"))),
                        true)), false, ConflictPolicy.SKIP_CONFLICTS));
        s.put("run_op_overlay", new C2S.RunOp(34, new OpSpec.Overlay(sphere, single, 3, mirror), false,
                ConflictPolicy.SKIP_CONFLICTS));
        s.put("run_op_naturalize", new C2S.RunOp(35, new OpSpec.Naturalize(cuboid, single, 1, weighted, 3,
                new Pattern.Single(dirt)), false, ConflictPolicy.SKIP_CONFLICTS));
        s.put("run_op_update_blocks", new C2S.RunOp(36, new OpSpec.UpdateBlocks(uploaded, rotate), false,
                ConflictPolicy.SKIP_CONFLICTS));
        // Weather
        s.put("stroke_begin_weather", new C2S.StrokeBegin(7, new BrushSpec(BrushTool.WEATHER, 6, 0.75f, Falloff.SMOOTH,
                Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0, 9L, null, Symmetry.NONE, null, SculptMode.TERRAIN, null,
                new WeatherSpec(WeatherSpec.Mode.values()[WeatherSpec.Mode.values().length - 1]))));
        // Jump and Through, lines
        s.put("navigate_jump", new C2S.Navigate(37, NavigateMode.JUMP, new BlockPos(3, 64, -2), Facing.UP, 0.25f, -0.5f,
                0.8f));
        s.put("navigate_through", new C2S.Navigate(38, NavigateMode.THROUGH, new BlockPos(-30, 70, 12), Facing.WEST, -1f,
                0f, 0f));
        s.put("navigate_result_landed", S2C.NavigateResult.landed(37, new BlockPos(3, 65, -2)));
        s.put("navigate_result_refused", S2C.NavigateResult.refused(38, RejectReason.NO_PERMISSION));
        s.put("run_op_paste_line", new C2S.RunOp(39, new OpSpec.Paste(new SourceRef.Clipboard(ID), new BlockPos(1, 2, 3),
                Transform.IDENTITY, new PasteOptions(true, false, false)), false, ConflictPolicy.SKIP_CONFLICTS, OpLabel.LINE));
        s.put("run_op_paste_shape_line", new C2S.RunOp(40, new OpSpec.Paste(new SourceRef.Clipboard(ID),
                new BlockPos(1, 2, 3), Transform.IDENTITY, new PasteOptions(true, false, false, PasteOptions.Into.AIR)), false,
                ConflictPolicy.SKIP_CONFLICTS, OpLabel.SHAPE_LINE));
        // Scatter features
        s.put("scatter_preview_feature", new C2S.ScatterPreview(41, new ScatterArea.Region(BOX),
                new C2S.ScatterPreview.Settings(3L, 6, new ScatterSettings.Density.Count(1), SurfaceMask.ANY,
                        ScatterSettings.Fit.DEFAULT),
                List.of(new C2S.ScatterPreview.Variant(new ScatterSource.Feature("minecraft:fancy_oak"), 2),
                        new C2S.ScatterPreview.Variant(new ScatterSource.Feature("minecraft:forest_rock"), 1)),
                ScatterSettings.Transforms.ALL));

        // Notices
        s.put("notice", new S2C.Notice(S2C.Notice.Level.WARN, "sculptory.notice.test", List.of("a", "b")));

        // Streams, both directions
        for (StreamKind kind : StreamKind.values()) {
            s.put("stream_open_" + kind.name().toLowerCase(java.util.Locale.ROOT), new StreamOpen(42, kind, 100,
                    new TreeMap<>(Map.of("name", "house.schem", "format", "x1"))));
        }
        s.put("stream_chunk", new StreamChunk(42, 0, new byte[] {1, 2, 3, (byte) 0xff}));
        s.put("stream_end", new StreamEnd(42, Sha256.digest(new byte[] {1, 2, 3})));
        s.put("stream_abort", new StreamAbort(42, "cancelled"));
        s.put("stream_credit", new StreamCredit(42, 4L << 20));
        return s;
    }

    // ---------------------------------------------------------------- helpers

    private static byte[] encode(Message message) throws ProtocolException {
        return message instanceof C2S c2s ? Codec.encodeC2S(c2s, STATES) : Codec.encodeS2C((S2C) message, STATES);
    }

    /** The fixture's lines of one kind, {@code <kind> <name> <value>}, as name to value. */
    private static Map<String, String> fixture(String kind) {
        Map<String, String> entries = new LinkedHashMap<>();
        try (InputStream in = WireGoldenTest.class.getResourceAsStream(FIXTURE)) {
            assertNotNull(in, "missing test resource " + FIXTURE);
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = reader.readLine()) != null; ) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split(" ", 3);
                if (parts.length != 3) fail("malformed fixture line: " + line);
                if (!parts[0].equals(kind)) continue;
                if (entries.put(parts[1], parts[2]) != null) fail("duplicate fixture entry: " + line);
            }
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        return entries;
    }

    private static String lines(String kind, Map<String, String> values) {
        return values.entrySet().stream().map(e -> kind + " " + e.getKey() + " " + e.getValue())
                .collect(Collectors.joining("\n"));
    }
}
