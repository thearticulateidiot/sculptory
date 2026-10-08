package dev.sculptory.server.engine.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.biome.QuartSelection;
import dev.sculptory.core.brush.BiomeSpec;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.BrushTool;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.core.brush.Falloff;
import dev.sculptory.core.brush.SculptMode;
import dev.sculptory.core.brush.Shape;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.history.BiomeChange;
import dev.sculptory.core.history.BiomeChanges;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.history.EditRecord;
import dev.sculptory.core.history.RecordBuilder;
import dev.sculptory.core.region.CellSet;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.Regions;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.ChunkPermit;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.platform.WorldBiomes;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The biome work of jobs (protocol 6) against a world of plains: a fill sets and records every quart it may, refusing
 * protected columns and unknown biomes; an undo restores what the step left, skips the target and keeps quarts painted
 * since (Undo anyway overwrites them); and the stroke admission helpers.
 */
class BiomeJobsTest {
    private static final List<String> IDS = List.of("minecraft:plains", "minecraft:desert", "minecraft:cherry_grove");

    /** A world of plains, every chunk loaded but chunk x 5 (blocks 80..95). */
    static final class FakeBiomes implements WorldBiomes {
        final Long2IntOpenHashMap quarts = new Long2IntOpenHashMap();
        /** Quarts holding a biome the registry does not list (a removed datapack): get returns -1. */
        final LongOpenHashSet unlisted = new LongOpenHashSet();
        int sets;

        @Override
        public List<String> ids() {
            return IDS;
        }

        @Override
        public int handle(String id) {
            return IDS.indexOf(id);
        }

        @Override
        public String id(int handle) {
            return IDS.get(handle);
        }

        @Override
        public boolean loaded(int cx, int cz) {
            return cx != 5;
        }

        @Override
        public int get(int qx, int qy, int qz) {
            if (unlisted.contains(dev.sculptory.core.history.BiomeHistory.key(qx, qy, qz))) return -1;
            return quarts.getOrDefault(dev.sculptory.core.history.BiomeHistory.key(qx, qy, qz), 0);
        }

        @Override
        public int set(int qx, int qy, int qz, int handle) {
            int before = get(qx, qy, qz);
            if (before != handle && before >= 0) {
                quarts.put(dev.sculptory.core.history.BiomeHistory.key(qx, qy, qz), handle);
                sets++;
            }
            return before;
        }

        @Override
        public int flush() {
            return 0;
        }
    }

    /** Columns with x below 0 are protected. */
    private static final PermitSource EAST_ONLY = (cx, cz) -> cx < 0 ? ChunkPermit.DENY : ChunkPermit.ALLOW;

    private static BiomeWork.Context context(FakeBiomes world, RecordBuilder record) {
        return new BiomeWork.Context(world, EAST_ONLY, RecordSink.into(record), (x, y, z) -> y >= -64 && y < 320);
    }

    /** The biome stage: every read column, then every write column. */
    private static void run(BiomeWork work, BiomeWork.Context ctx) {
        for (long column : work.readColumns()) work.read(column, ctx);
        for (long column : work.columns()) work.apply(column, ctx);
    }

    @Test
    void aFillSetsAndRecordsEveryQuartItMayAndCountsTheProtectedOnes() {
        FakeBiomes world = new FakeBiomes();
        RecordBuilder record = new RecordBuilder();
        BiomeWork.Context ctx = context(world, record);
        Region box = new Region.Cuboid(new Box(new BlockPos(-4, 60, 0), new BlockPos(7, 63, 3)));
        BiomeWork fill = BiomeJobs.fill(QuartSelection.touched(box, false, -64, 320), "minecraft:desert");
        assertEquals(3, fill.quarts());
        run(fill, ctx);
        assertEquals(2, ctx.changed);
        assertEquals(1, ctx.protectedQuarts, "the quart west of x 0");
        assertEquals(1, world.get(0, 15, 0));
        assertEquals(0, world.get(-1, 15, 0));
        assertEquals(List.of(new BiomeChange(0, 15, 0, "minecraft:plains", "minecraft:desert"),
                new BiomeChange(1, 15, 0, "minecraft:plains", "minecraft:desert")), record.build().biomes());
        // Again: nothing changes, nothing is recorded.
        RecordBuilder again = new RecordBuilder();
        BiomeWork.Context second = context(world, again);
        run(fill, second);
        assertEquals(0, second.changed);
        assertTrue(again.build().isEmpty());
        // A biome the world does not know is counted and never written.
        BiomeWork.Context unknown = context(world, new RecordBuilder());
        run(BiomeJobs.fill(QuartSelection.touched(box, false, -64, 320), "mod:glade"), unknown);
        assertEquals(0, unknown.changed);
        assertEquals(2, unknown.conflicts);
    }

    @Test
    void anUndoRestoresWhatTheStepLeftAndKeepsQuartsPaintedSince() {
        FakeBiomes world = new FakeBiomes();
        // The step painted three quarts desert; one was painted cherry grove since, one was put back by hand.
        BiomeChanges record = BiomeChanges.of(List.of(new BiomeChange(0, 0, 0, "minecraft:plains", "minecraft:desert"),
                new BiomeChange(1, 0, 0, "minecraft:plains", "minecraft:desert"),
                new BiomeChange(2, 0, 0, "minecraft:plains", "minecraft:desert")));
        world.set(0, 0, 0, 1);
        world.set(1, 0, 0, 2);
        world.sets = 0;
        BiomeWork.Context ctx = context(world, new RecordBuilder());
        run(BiomeJobs.history(record.swapped(), ConflictPolicy.SKIP_CONFLICTS), ctx);
        assertEquals(0, world.get(0, 0, 0), "restored");
        assertEquals(2, world.get(1, 0, 0), "kept: painted since");
        assertEquals(0, world.get(2, 0, 0), "already the target");
        assertEquals(1, ctx.changed);
        assertEquals(1, ctx.conflicts);
        assertEquals(1, world.sets);
        // Undo anyway takes the kept one back too.
        BiomeWork.Context anyway = context(world, new RecordBuilder());
        run(BiomeJobs.history(record.swapped(), ConflictPolicy.OVERWRITE), anyway);
        assertEquals(0, world.get(1, 0, 0));
        assertEquals(0, anyway.conflicts);
    }

    @Test
    void aQuartHoldingAnUnlistedBiomeIsKeptCountedAndNeverRecorded() {
        FakeBiomes world = new FakeBiomes();
        world.unlisted.add(dev.sculptory.core.history.BiomeHistory.key(1, 15, 0));
        RecordBuilder record = new RecordBuilder();
        BiomeWork.Context ctx = context(world, record);
        Region box = new Region.Cuboid(new Box(new BlockPos(0, 60, 0), new BlockPos(11, 63, 3)));
        run(BiomeJobs.fill(QuartSelection.touched(box, false, -64, 320), "minecraft:desert"), ctx);
        assertEquals(2, ctx.changed);
        assertEquals(1, ctx.conflicts, "the unlisted quart");
        assertEquals(0, world.sets - 2);
        assertEquals(List.of(new BiomeChange(0, 15, 0, "minecraft:plains", "minecraft:desert"),
                new BiomeChange(2, 15, 0, "minecraft:plains", "minecraft:desert")), record.build().biomes());
        // A plan writes around it too.
        BiomeJobs.Plan plan = new BiomeJobs.Plan();
        plan.set(1, 15, 0, "minecraft:cherry_grove");
        BiomeWork.Context planned = context(world, new RecordBuilder());
        run(plan.work(), planned);
        assertEquals(0, planned.changed);
        assertEquals(1, planned.conflicts);
        // An undo whose quart now holds an unlisted biome keeps it, under both policies, and counts it.
        world.unlisted.add(dev.sculptory.core.history.BiomeHistory.key(0, 15, 0));
        BiomeChanges recorded = BiomeChanges.of(record.build().biomes());
        for (ConflictPolicy policy : ConflictPolicy.values()) {
            BiomeWork.Context undo = context(world, new RecordBuilder());
            run(BiomeJobs.history(recorded.swapped(), policy), undo);
            assertEquals(1, undo.conflicts, policy.name());
            assertEquals(-1, world.get(0, 15, 0), policy.name());
        }
        assertEquals(0, world.get(2, 15, 0), "the listed quart is restored");
    }

    private static BiomeJobs.Carried.Builder carried(int maxColumns, long maxQuarts) {
        return new BiomeJobs.Carried.Builder(-64, 320, maxColumns, maxQuarts);
    }

    private static Long2ObjectOpenHashMap<long[]> columnsOf(Region region, Box from) {
        return Regions.columns(region, from.min().y(), from.max().y(), 4096);
    }

    private static java.util.Set<Long> set(long[] columns) {
        return java.util.Arrays.stream(columns).boxed().collect(java.util.stream.Collectors.toSet());
    }

    /**
     * A move's carried biomes are read when the job's biome stage runs (every source column first), not at admission: a
     * source repainted in between is carried as it is then, and a target overlapping its source reads it before
     * anything is written.
     */
    @Test
    void carriedBiomesAreReadWhenTheStageRunsAllBeforeTheFirstWrite() throws Exception {
        FakeBiomes world = new FakeBiomes();
        // A row of four quarts at x 0..15, y 60..63: desert, cherry grove, plains, plains.
        world.set(0, 15, 0, 1);
        world.set(1, 15, 0, 2);
        Box from = new Box(new BlockPos(0, 60, 0), new BlockPos(15, 63, 3));
        Region region = new Region.Cuboid(from);
        BiomeJobs.Carried.Builder builder = carried(4096, Long.MAX_VALUE);
        // Moved one quart east: the target overlaps the source.
        builder.target(builder.source(region, from, columnsOf(region, from)), Transform.IDENTITY,
                new BlockPos(4, 60, 0));
        BiomeWork work = builder.build();
        assertEquals(java.util.Set.of(ColumnPlan.pack(0, 0), ColumnPlan.pack(1, 0)), set(work.columns()),
                "the columns the moved cells land in (and a quart from them, inside the result box)");
        // Repainted after admission: the job carries what is there when it runs.
        world.set(2, 15, 0, 1);
        RecordBuilder record = new RecordBuilder();
        BiomeWork.Context ctx = context(world, record);
        run(work, ctx);
        assertEquals(1, world.get(1, 15, 0), "desert moved one east");
        assertEquals(2, world.get(2, 15, 0), "cherry grove moved one east, read before it was overwritten");
        assertEquals(1, world.get(3, 15, 0), "the repainted desert moved one east");
        assertEquals(0, world.get(4, 15, 0), "plains moved one east");
        assertEquals(1, world.get(0, 15, 0), "a move's vacated quarts keep their biome");
        assertEquals(java.util.Set.of(new BiomeChange(1, 15, 0, "minecraft:cherry_grove", "minecraft:desert"),
                new BiomeChange(2, 15, 0, "minecraft:desert", "minecraft:cherry_grove"),
                new BiomeChange(3, 15, 0, "minecraft:plains", "minecraft:desert")),
                java.util.Set.copyOf(record.build().biomes()));
    }

    /**
     * Two cells 29 million blocks apart: a move's or stack's biome work lists only the columns the cells are in and land
     * in (nothing by bounding box), quickly, and carries their biomes; past the column cap or the volume limit it is
     * refused while it is being listed.
     */
    @Test
    void farApartCellsListOnlyTheirOwnColumns() throws Exception {
        CellSet.Builder cells = CellSet.builder();
        cells.add(32, 64, 0);
        cells.add(29_000_000, 64, 29_000_000);
        Region region = new Region.Cells(cells.build());
        Box from = region.bounds();
        BiomeWork[] work = new BiomeWork[1];
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
            BiomeJobs.Carried.Builder builder = carried(4096, 1_000_000);
            int source = builder.source(region, from, columnsOf(region, from));
            builder.target(source, Transform.IDENTITY, from.min().offset(32, 0, 0)); // a move 32 east
            for (int k = 1; k <= 3; k++) builder.target(source, Transform.IDENTITY, from.min().offset(0, 0, 64 * k));
            assertTrue(builder.bound() <= 4L * 8 * 16, "bound " + builder.bound());
            work[0] = builder.build();
        });
        assertEquals(2, work[0].readColumns().length);
        assertEquals(20, work[0].columns().length, "the landed cells' columns and those a quart away, in the box");
        FakeBiomes world = new FakeBiomes();
        world.set(8, 16, 0, 2);
        run(work[0], context(world, new RecordBuilder()));
        assertEquals(2, world.get(16, 16, 0), "carried with the near cell");
        assertEquals(2, world.get(8, 16, 48), "into the third stacked copy");
        assertEquals(0, world.get((29_000_000 + 32) >> 2, 16, 29_000_000 >> 2), "plains carried with the far one");

        // Past the column cap, or the volume limit, while listing.
        BiomeJobs.Carried.Builder capped = carried(9, Long.MAX_VALUE);
        int s = capped.source(region, from, columnsOf(region, from));
        capped.target(s, Transform.IDENTITY, from.min().offset(32, 0, 0));
        EditRejected tooMany = assertThrows(EditRejected.class,
                () -> capped.target(s, Transform.IDENTITY, from.min().offset(0, 0, 64)));
        assertEquals(RejectReason.TOO_LARGE, tooMany.reason());
        Box box = new Box(new BlockPos(0, 0, 0), new BlockPos(399, 63, 399));
        BiomeJobs.Carried.Builder limited = carried(4096, 100_000);
        Region cuboid = new Region.Cuboid(box);
        int b = limited.source(cuboid, box, columnsOf(cuboid, box));
        EditRejected tooLarge = assertThrows(EditRejected.class,
                () -> limited.target(b, Transform.IDENTITY, new BlockPos(400, 0, 0)));
        assertEquals(RejectReason.TOO_LARGE, tooLarge.reason());
    }

    /**
     * A quart whose mapped source quart holds a cell is written even in a chunk column no cell lands in, as a copy then
     * paste writes it: a cell at x 12 moved 3 east lands at x 15 (chunk 0), yet the result quart x 16..19 (chunk 1)
     * samples x 15, in the cell's source quart.
     */
    @Test
    void aQuartAcrossAChunkBorderIsCarriedLikeAPaste() throws Exception {
        CellSet.Builder cells = CellSet.builder();
        cells.add(12, 64, 0);
        cells.add(15, 64, 20);
        Region region = new Region.Cells(cells.build());
        Box from = region.bounds();
        BiomeJobs.Carried.Builder builder = carried(4096, Long.MAX_VALUE);
        builder.target(builder.source(region, from, columnsOf(region, from)), Transform.IDENTITY,
                from.min().offset(3, 0, 0));
        BiomeWork work = builder.build();
        assertTrue(set(work.columns()).contains(ColumnPlan.pack(1, 0)), "the column a quart from the landed cell");
        FakeBiomes world = new FakeBiomes();
        world.set(3, 16, 0, 1);
        // What a paste of a copy of the region would write there.
        dev.sculptory.core.biome.BiomeGrid copied = dev.sculptory.core.biome.BiomeMapping.capture(region, from, -64, 320,
                (qx, qy, qz) -> IDS.get(world.get(qx, qy, qz)));
        java.util.Map<Long, String> pasted = new java.util.HashMap<>();
        dev.sculptory.core.biome.BiomeMapping.map(copied, new BlockPos(from.sizeX(), from.sizeY(), from.sizeZ()),
                dev.sculptory.core.transform.Orientation.IDENTITY, from.min().offset(3, 0, 0), -64, 320,
                (qx, qy, qz, id) -> pasted.put(dev.sculptory.core.history.BiomeHistory.key(qx, qy, qz), id));
        assertEquals("minecraft:desert", pasted.get(dev.sculptory.core.history.BiomeHistory.key(4, 16, 0)));
        run(work, context(world, new RecordBuilder()));
        assertEquals(1, world.get(4, 16, 0), "carried into chunk 1 as the paste writes it");
        for (java.util.Map.Entry<Long, String> quart : pasted.entrySet()) {
            long key = quart.getKey();
            assertEquals(IDS.indexOf(quart.getValue()), world.get(dev.sculptory.core.history.BiomeHistory.keyX(key),
                    dev.sculptory.core.history.BiomeHistory.keyY(key), dev.sculptory.core.history.BiomeHistory.keyZ(key)),
                    "every quart the paste writes");
        }
    }

    /**
     * A stack of a 2048 × 1 × 2048 slab 256 high by a player with limit.bypass lists its columns once and shifts them,
     * quickly; past the absolute listing ceiling it is refused even so.
     */
    @Test
    void aBypassStackListsItsColumnsOnceAndHasACeiling() throws Exception {
        Box slab = new Box(new BlockPos(0, 0, 0), new BlockPos(2047, 0, 2047));
        Region region = new Region.Cuboid(slab);
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
            BiomeJobs.Carried.Builder builder = carried(16_384, Long.MAX_VALUE);
            int source = builder.source(region, slab, Regions.columns(region, 0, 0, 16_384));
            List<BlockPos> copies = new java.util.ArrayList<>();
            for (int k = 1; k <= 256; k++) copies.add(new BlockPos(0, k, 0));
            builder.stack(source, Transform.IDENTITY, copies);
            assertEquals(16_384, builder.build().columns().length, "the slab's own columns");
        });
        // Shifted by 17 and 3 blocks a copy: at most 16 remainders worked out bit by bit, then whole chunks.
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
            BiomeJobs.Carried.Builder builder = carried(1 << 20, Long.MAX_VALUE);
            int source = builder.source(region, slab, Regions.columns(region, 0, 0, 16_384));
            List<BlockPos> copies = new java.util.ArrayList<>();
            for (int k = 1; k <= 256; k++) copies.add(new BlockPos(17 * k, k, 3 * k));
            builder.stack(source, Transform.IDENTITY, copies);
            assertTrue(builder.build().columns().length > 16_384);
        });
        BiomeJobs.Carried.Builder over = carried(16_384, Long.MAX_VALUE);
        int source = over.source(region, slab, Regions.columns(region, 0, 0, 16_384));
        List<BlockPos> copies = new java.util.ArrayList<>();
        for (int k = 1; k <= 257; k++) copies.add(new BlockPos(0, k, 0));
        EditRejected refused = assertThrows(EditRejected.class, () -> over.stack(source, Transform.IDENTITY, copies));
        assertEquals(RejectReason.TOO_LARGE, refused.reason());
    }

    /** A context allowing every column (the image tests write at negative x). */
    private static BiomeWork.Context open(FakeBiomes world, RecordBuilder record) {
        return new BiomeWork.Context(world, (cx, cz) -> ChunkPermit.ALLOW, RecordSink.into(record),
                (x, y, z) -> y >= -64 && y < 320);
    }

    private static final Symmetry MIRROR_AT_HALF = new Symmetry(Symmetry.Mode.MIRROR_X, 2, 0);
    private static final Symmetry.Image MIRRORED = MIRROR_AT_HALF.images().get(1);

    /**
     * A mirrored copy of a free paste's biomes covers exactly the image box: the original box x 1..8 mirrored at
     * x2 = 2 is x -7..0; its quarts -2..0 take the biome of the cell each clamped centre is the image of, quart -3
     * (outside the copy) is left alone. (The old rule imaged unclamped quart centres and wrote a quart beside the box.)
     */
    @Test
    void anImagedPastePlansTheQuartsOfTheImageBox() {
        // Box x 1..8: its grid's quarts (phase 1) are local 0..2 desert, 3..6 cherry grove, 7 desert.
        dev.sculptory.core.biome.BiomeGrid.Builder grid = dev.sculptory.core.biome.BiomeGrid.builder(
                new BlockPos(1, 0, 0), new BlockPos(8, 1, 1));
        for (int x = 0; x < 8; x++) grid.setAt(x, 0, 0, x >= 3 && x <= 6 ? "minecraft:cherry_grove" : "minecraft:desert");
        Box target = new Box(new BlockPos(1, 64, 0), new BlockPos(8, 64, 0));
        BiomeJobs.Plan plan = new BiomeJobs.Plan();
        BiomeJobs.mapImage(plan, grid.build(), new BlockPos(8, 1, 1), dev.sculptory.core.transform.Orientation.IDENTITY,
                target, MIRROR_AT_HALF, MIRRORED, -64, 320);
        FakeBiomes world = new FakeBiomes();
        run(plan.work(), open(world, new RecordBuilder()));
        // The image box is x -7..0. Quart 0: centre 2 clamped to 0, the image of x 1 (local 0, desert); quart -1: centre
        // -2, the image of x 3 (local 2, desert); quart -2: centre -6, the image of x 7 (local 6, cherry grove).
        assertEquals(1, world.get(0, 16, 0));
        assertEquals(1, world.get(-1, 16, 0));
        assertEquals(2, world.get(-2, 16, 0));
        assertEquals(0, world.get(-3, 16, 0), "outside the copy");
        assertEquals(3, plan.count());
    }

    /**
     * A free move's mirrored copy reads its own imaged source: each image-box quart's clamped centre goes back to the
     * original's result cell, samples the original's source cell, and takes the biome under that cell's image. The
     * original x 1..4 moved 8 east (x 9..12); mirrored at x2 = 2, the copy's source is x -3..0 and its box x -11..-8.
     */
    @Test
    void aFreeMovesMirroredCopyReadsItsOwnSource() throws Exception {
        Box box = new Box(new BlockPos(1, 64, 0), new BlockPos(4, 64, 0));
        Region region = new Region.Cuboid(box);
        Box imageBox = MIRROR_AT_HALF.imageBox(MIRRORED, box);
        Region imageRegion = new Region.Cuboid(imageBox);
        Box result = new Box(new BlockPos(9, 64, 0), new BlockPos(12, 64, 0));
        Box imageResult = MIRROR_AT_HALF.imageBox(MIRRORED, result);
        assertEquals(new Box(new BlockPos(-11, 64, 0), new BlockPos(-8, 64, 0)), imageResult);
        BiomeJobs.Carried.Builder builder = carried(4096, Long.MAX_VALUE);
        dev.sculptory.core.transform.Orientation identity = dev.sculptory.core.transform.Orientation.IDENTITY;
        builder.target(builder.source(region, box, columnsOf(region, box)), box, identity, result.min(),
                columnsOf(new Region.Cuboid(result), result), MIRROR_AT_HALF, Symmetry.Image.IDENTITY);
        builder.target(builder.source(imageRegion, imageBox, columnsOf(imageRegion, imageBox)), box, identity,
                result.min(), columnsOf(new Region.Cuboid(imageResult), imageResult), MIRROR_AT_HALF, MIRRORED);
        FakeBiomes world = new FakeBiomes();
        world.set(-1, 16, 0, 1); // desert under the copy's source x -3..-1
        world.set(0, 16, 0, 2); // cherry grove at x 0..3: the copy's x 0 and the original's x 1..3
        run(builder.build(), open(world, new RecordBuilder()));
        assertEquals(2, world.get(2, 16, 0), "original quart 2: centre 10, source x 2 (cherry grove)");
        assertEquals(0, world.get(3, 16, 0), "original quart 3: centre clamped to 12, source x 4 (plains)");
        assertEquals(1, world.get(-3, 16, 0), "copy quart -3: back to x 11, source x 3, its image x -2 (desert)");
        assertEquals(2, world.get(-2, 16, 0), "copy quart -2: clamped to -8, back to 9, source x 1, image x 0");
        assertEquals(0, world.get(-4, 16, 0), "outside the copy");
    }

    @Test
    void aPlanKeepsTheFirstBiomeOfAQuartPlannedTwice() {
        FakeBiomes world = new FakeBiomes();
        BiomeJobs.Plan plan = new BiomeJobs.Plan();
        plan.set(3, 0, 3, "minecraft:desert");
        plan.set(3, 0, 3, "minecraft:cherry_grove");
        plan.set(40, 0, 3, "minecraft:desert");
        RecordBuilder record = new RecordBuilder();
        BiomeWork.Context ctx = context(world, record);
        run(plan.work(), ctx);
        assertEquals(1, world.get(3, 0, 3), "the first plan wins, as the original copy's blocks do");
        assertEquals(2, plan.count(), "distinct quarts");
        assertEquals(List.of(new BiomeChange(3, 0, 3, "minecraft:plains", "minecraft:desert"),
                new BiomeChange(40, 0, 3, "minecraft:plains", "minecraft:desert")), record.build().biomes());
    }

    @Test
    void strokesNeedTheBiomeNodeAndCountTheirQuarts() {
        BrushSpec biome = new BrushSpec(BrushTool.BIOME, 32, 1f, Falloff.CONSTANT, Shape.CIRCLE, null, SurfaceMask.ANY, 0,
                0, 1L, null, Symmetry.NONE, null, SculptMode.TERRAIN, null, null, null,
                new BiomeSpec("minecraft:desert", true));
        assertEquals(Perm.BIOME, EngineEditService.strokeNode(biome));
        assertEquals(Perm.BRUSH, EngineEditService.strokeNode(new BrushSpec(BrushTool.RAISE, 2, 1f, Falloff.CONSTANT,
                Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0, 1L)));
        assertEquals(18L * 18 * 96, EngineEditService.biomeQuartsPerStep(biome, -64, 320));
        Dab dab = new Dab(0, 0, 70 * 16, 0, Dab.FULL_PRESSURE);
        Box box = EngineEditService.dabBox(biome, -64, 320, dab);
        assertEquals(-64, box.min().y(), "columns: the whole height");
        assertEquals(319, box.max().y());
        assertEquals(-35, box.min().x(), "the radius, half a quart and the slope's neighbours");
        EditRecord fill = new EditRecord(new BlockBuffer(), new BlockBuffer(), List.of(),
                List.of(new BiomeChange(0, 0, 0, "minecraft:plains", "minecraft:desert")));
        assertEquals(" · 1 biome cell", EngineEditService.contents(fill));
    }
}
