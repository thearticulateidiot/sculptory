package dev.sculptory.fabric.client.editor.render.ghost;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.render.ghost.GhostBudget.Candidate;
import dev.sculptory.fabric.client.editor.render.ghost.GhostBudget.Draw;
import dev.sculptory.fabric.client.editor.render.ghost.GhostBudget.Plan;
import dev.sculptory.fabric.client.editor.world.Aabb;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class GhostBudgetTest {
    private static final GhostConfig DEFAULTS = GhostConfig.DEFAULTS;

    private static Candidate fresh(int blocks, double distance) {
        return new Candidate(blocks, 0, distance, -1, false);
    }

    private static GhostConfig withBlockCap(long blocks) {
        GhostConfig d = DEFAULTS;
        return new GhostConfig(blocks, d.maxVertexBytes(), d.fullDetailDistance(), d.maxUploadsPerFrame(), d.uploadBudgetNanos(),
                d.maxEraseOutlines());
    }

    @Test
    void meshesEverythingWithinTheCaps() {
        Plan plan = GhostBudget.plan(List.of(fresh(4096, 10), fresh(100, 50), fresh(1, 0)), DEFAULTS, false);
        assertArrayEquals(new Draw[] {Draw.MESH, Draw.MESH, Draw.MESH}, plan.draws());
        assertFalse(plan.status().simplified());
        assertEquals("", plan.status().hudText());
        assertEquals(4197, plan.status().totalBlocks());
        assertEquals(4197, plan.status().meshedBlocks());
        assertEquals(0, plan.status().simplifiedSections());
    }

    @Test
    void ordersNearestFirst() {
        Plan plan = GhostBudget.plan(List.of(fresh(1, 30), fresh(1, 5), fresh(1, 90), fresh(1, 5), fresh(1, 0)), DEFAULTS, false);
        // Ties keep input order.
        assertArrayEquals(new int[] {4, 1, 3, 0, 2}, plan.nearestFirst());
    }

    @Test
    void blockCapKeepsTheNearestSectionsAndFillsWithSmallerFartherOnes() {
        GhostConfig config = withBlockCap(10_000);
        List<Candidate> candidates = List.of(
                fresh(4096, 40), // 3rd nearest: 8192 + 4096 > 10000 -> box
                fresh(4096, 20), // 2nd: 8192 so far
                fresh(4096, 10), // nearest: 4096
                fresh(1000, 60), // 4th: still fits (9192)
                fresh(1000, 70)); // 5th: would be 10192 -> box
        Plan plan = GhostBudget.plan(candidates, config, false);
        assertArrayEquals(new Draw[] {Draw.BOX, Draw.MESH, Draw.MESH, Draw.MESH, Draw.BOX}, plan.draws());
        assertEquals(GhostStatus.Reason.BLOCK_CAP, plan.status().reason());
        assertEquals(9192, plan.status().meshedBlocks());
        assertEquals(14288, plan.status().totalBlocks());
        assertEquals(2, plan.status().simplifiedSections());
        assertEquals("Preview simplified: 14.2k blocks", plan.status().hudText());
    }

    @Test
    void defaultCapIs262144BlocksNearestFirst() {
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            candidates.add(fresh(4096, 90 - i * 0.5)); // input order is farthest first
        }
        Plan plan = GhostBudget.plan(candidates, DEFAULTS, false);
        long meshed = 0;
        for (int i = 0; i < candidates.size(); i++) {
            if (plan.draw(i) == Draw.MESH) {
                meshed += candidates.get(i).blocks();
                // Every meshed section is nearer than every boxed one.
                for (int j = 0; j < candidates.size(); j++) {
                    if (plan.draw(j) == Draw.BOX) {
                        assertTrue(candidates.get(i).distance() <= candidates.get(j).distance());
                    }
                }
            }
        }
        assertEquals(262_144, meshed); // 64 sections of 4096
        assertEquals(GhostStatus.Reason.BLOCK_CAP, plan.status().reason());
    }

    @Test
    void memoryCapUsesActualBytesWhenKnownAndEstimatesOtherwise() {
        GhostConfig d = DEFAULTS;
        GhostConfig config = new GhostConfig(d.maxMeshedBlocks(), 1_000_000, d.fullDetailDistance(), d.maxUploadsPerFrame(),
                d.uploadBudgetNanos(), d.maxEraseOutlines());
        List<Candidate> candidates = List.of(
                new Candidate(4096, 0, 1, 900_000, true), // measured: 900 kB
                new Candidate(10, 0, 2, -1, false), // estimate 1600 bytes: fits
                new Candidate(1000, 0, 3, -1, false)); // estimate 160 kB: over 1 MB -> box
        Plan plan = GhostBudget.plan(candidates, config, false);
        assertArrayEquals(new Draw[] {Draw.MESH, Draw.MESH, Draw.BOX}, plan.draws());
        assertEquals(GhostStatus.Reason.MEMORY_CAP, plan.status().reason());
        assertEquals(160_000, candidates.get(2).expectedBytes());
        assertEquals(900_000, candidates.get(0).expectedBytes());
    }

    /**
     * B1: the memory cap is one budget for the whole renderer: bytes other volumes hold (a scatter's many baked
     * variants) are taken off it, so a volume that would fit alone is boxed when the others already fill the cap.
     */
    @Test
    void theMemoryCapCountsTheBytesOtherVolumesHold() {
        GhostConfig d = DEFAULTS;
        GhostConfig config = new GhostConfig(d.maxMeshedBlocks(), 1_000_000, d.fullDetailDistance(), d.maxUploadsPerFrame(),
                d.uploadBudgetNanos(), d.maxEraseOutlines());
        List<Candidate> candidates = List.of(fresh(1000, 1), fresh(1000, 2)); // 160 kB each
        assertArrayEquals(new Draw[] {Draw.MESH, Draw.MESH}, GhostBudget.plan(candidates, config, false, 0).draws());
        Plan shared = GhostBudget.plan(candidates, config, false, 700_000);
        assertArrayEquals(new Draw[] {Draw.MESH, Draw.BOX}, shared.draws(), "only 300 kB left for this volume");
        assertEquals(GhostStatus.Reason.MEMORY_CAP, shared.status().reason());
        Plan full = GhostBudget.plan(candidates, config, false, 1_000_000);
        assertArrayEquals(new Draw[] {Draw.BOX, Draw.BOX}, full.draws(), "the others hold the whole cap");
        assertArrayEquals(GhostBudget.plan(candidates, config, false).draws(),
                GhostBudget.plan(candidates, config, false, 0).draws(), "alone, as before");
    }

    /**
     * Two volumes sharing the cap never trade meshes: each keeps the bytes it has and grows only into what the other
     * leaves, so their plans are stable frame after frame.
     */
    @Test
    void volumesSharingTheCapKeepTheirMeshesAndGrowOnlyIntoTheRest() {
        long cap = 1_000_000;
        assertEquals(300_000, GhostBudget.bytesElsewhere(700_000, 400_000, cap), "the other volume's bytes");
        assertEquals(0, GhostBudget.bytesElsewhere(400_000, 400_000, cap), "alone");
        // Over the cap (meshes that were in flight): each keeps what it has, neither grows.
        assertEquals(cap - 600_000, GhostBudget.bytesElsewhere(1_200_000, 600_000, cap));
        assertEquals(0, GhostBudget.bytesElsewhere(1_500_000, 1_100_000, cap));

        GhostConfig d = DEFAULTS;
        GhostConfig config = new GhostConfig(d.maxMeshedBlocks(), cap, d.fullDetailDistance(), d.maxUploadsPerFrame(),
                d.uploadBudgetNanos(), d.maxEraseOutlines());
        // Volume A holds 600 kB, volume B 500 kB (in flight when both grew): both keep all of theirs.
        List<Candidate> a = List.of(new Candidate(1000, 0, 5, 600_000, true));
        List<Candidate> b = List.of(new Candidate(1000, 0, 1, 500_000, true), fresh(100, 2));
        for (int frame = 0; frame < 3; frame++) {
            Plan planA = GhostBudget.plan(a, config, false, GhostBudget.bytesElsewhere(1_100_000, 600_000, cap));
            Plan planB = GhostBudget.plan(b, config, false, GhostBudget.bytesElsewhere(1_100_000, 500_000, cap));
            assertArrayEquals(new Draw[] {Draw.MESH}, planA.draws(), "A keeps its mesh");
            assertArrayEquals(new Draw[] {Draw.MESH, Draw.BOX}, planB.draws(), "B keeps its mesh but may not grow");
        }
    }

    @Test
    void farSectionsAreBoxesWithHysteresisForExistingMeshes() {
        double limit = DEFAULTS.fullDetailDistance();
        List<Candidate> candidates = List.of(
                fresh(10, limit), // exactly at the limit: mesh
                fresh(10, limit + 1), // beyond, no mesh yet: box
                new Candidate(10, 0, limit + GhostBudget.HYSTERESIS - 0.5, 1000, true), // beyond, but keeps its mesh
                new Candidate(10, 0, limit + GhostBudget.HYSTERESIS + 0.5, 1000, true)); // too far even for that
        Plan plan = GhostBudget.plan(candidates, DEFAULTS, false);
        assertArrayEquals(new Draw[] {Draw.MESH, Draw.BOX, Draw.MESH, Draw.BOX}, plan.draws());
        assertEquals(GhostStatus.Reason.DISTANCE, plan.status().reason());
        assertEquals("Preview simplified: 40 blocks", plan.status().hudText());
    }

    @Test
    void capReasonOutranksDistance() {
        GhostConfig config = withBlockCap(100);
        Plan plan = GhostBudget.plan(List.of(fresh(80, 1), fresh(80, 2), fresh(5, 500)), config, false);
        assertArrayEquals(new Draw[] {Draw.MESH, Draw.BOX, Draw.BOX}, plan.draws());
        assertEquals(GhostStatus.Reason.BLOCK_CAP, plan.status().reason());
    }

    @Test
    void shaderPacksTurnEverySectionIntoABox() {
        Plan plan = GhostBudget.plan(List.of(fresh(10, 1), new Candidate(0, 5, 1, -1, false)), DEFAULTS, true);
        assertArrayEquals(new Draw[] {Draw.BOX, Draw.NONE}, plan.draws());
        assertEquals(GhostStatus.Reason.SHADER_PACK, plan.status().reason());
        assertEquals(0, plan.status().meshedBlocks());
        // Erase outlines do not depend on meshes.
        assertTrue(plan.outlineEraseCells(1));
    }

    @Test
    void eraseOutlinesAreCappedNearestFirstAndOnlyNearby() {
        GhostConfig d = DEFAULTS;
        GhostConfig config = new GhostConfig(d.maxMeshedBlocks(), d.maxVertexBytes(), d.fullDetailDistance(), d.maxUploadsPerFrame(),
                d.uploadBudgetNanos(), 100);
        List<Candidate> candidates = List.of(
                new Candidate(0, 60, 5, -1, false), // nearest: 60 cells outlined
                new Candidate(0, 50, 6, -1, false), // 110 > 100: one box instead
                new Candidate(0, 40, 7, -1, false), // 100: fits
                new Candidate(0, 0, 8, -1, false), // no erase cells
                new Candidate(0, 1, 500, -1, false)); // far: box
        Plan plan = GhostBudget.plan(candidates, config, false);
        assertArrayEquals(new boolean[] {true, false, true, false, false}, plan.outlineEraseCells());
        assertArrayEquals(new Draw[] {Draw.NONE, Draw.NONE, Draw.NONE, Draw.NONE, Draw.NONE}, plan.draws());
        assertFalse(plan.status().simplified(), "erase-only sections are not a simplification");
    }

    @Test
    void emptyInputPlansNothing() {
        Plan plan = GhostBudget.plan(List.of(), DEFAULTS, false);
        assertEquals(0, plan.draws().length);
        assertEquals(GhostStatus.EMPTY, plan.status());
    }

    @Test
    void distanceIsToTheNearestPointOfTheBox() {
        Aabb box = new Aabb(0, 0, 0, 16, 16, 16);
        assertEquals(0, GhostBudget.distance(box, 8, 8, 8), 1e-9);
        assertEquals(0, GhostBudget.distance(box, 16, 0, 0), 1e-9);
        assertEquals(4, GhostBudget.distance(box, 20, 8, 8), 1e-9);
        assertEquals(5, GhostBudget.distance(box, -3, -4, 8), 1e-9);
        assertEquals(Math.sqrt(3), GhostBudget.distance(box, 17, 17, -1), 1e-9);
    }

    @Test
    void hudTextUsesCompactCounts() {
        assertEquals("950", GhostStatus.compactCount(950));
        assertEquals("1k", GhostStatus.compactCount(1_000));
        assertEquals("262.1k", GhostStatus.compactCount(262_144));
        assertEquals("999.9k", GhostStatus.compactCount(999_999));
        assertEquals("1.2M", GhostStatus.compactCount(1_234_567));
        assertEquals("16M", GhostStatus.compactCount(16_000_000));
        assertEquals("Preview simplified: 1.2M blocks",
                new GhostStatus(1_200_000, 262_144, 200, GhostStatus.Reason.BLOCK_CAP).hudText());
    }

    // ---- Config ----

    @Test
    void configDefaultsMatchTheDesign() {
        assertEquals(262_144, DEFAULTS.maxMeshedBlocks());
        assertEquals(128L * 1024 * 1024, DEFAULTS.maxVertexBytes());
        assertEquals(96.0, DEFAULTS.fullDetailDistance());
        assertEquals(4, DEFAULTS.maxUploadsPerFrame());
        assertEquals(2_000_000L, DEFAULTS.uploadBudgetNanos());
        assertEquals(DEFAULTS, GhostConfig.parse("{}"));
    }

    @Test
    void configParsesAndClamps() {
        GhostConfig config = GhostConfig.parse("""
                { "maxMeshedBlocks": 1000, "maxVertexMegabytes": 64, "fullDetailDistance": 1,
                  "maxUploadsPerFrame": 1000, "uploadBudgetMillis": 1.5, "unknownKey": true }
                """);
        assertEquals(1000, config.maxMeshedBlocks());
        assertEquals(64L << 20, config.maxVertexBytes());
        assertEquals(16.0, config.fullDetailDistance(), "clamped up");
        assertEquals(64, config.maxUploadsPerFrame(), "clamped down");
        assertEquals(1_500_000L, config.uploadBudgetNanos());
        assertEquals(DEFAULTS.maxEraseOutlines(), config.maxEraseOutlines());
    }

    @Test
    void malformedConfigIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> GhostConfig.parse("[1, 2]"));
        assertThrows(IllegalArgumentException.class, () -> GhostConfig.parse("{ \"maxMeshedBlocks\": \"lots\" }"));
        assertThrows(IllegalArgumentException.class, () -> GhostConfig.parse("{ \"maxMeshedBlocks\": "));
        List<String> problems = new ArrayList<>();
        assertEquals(DEFAULTS, GhostConfig.load(java.nio.file.Path.of("does-not-exist", "ghost.json"), problems::add));
        assertTrue(problems.isEmpty(), "a missing file is not a problem");
    }
}
