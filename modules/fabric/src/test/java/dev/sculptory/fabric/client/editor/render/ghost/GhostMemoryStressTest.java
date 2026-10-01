package dev.sculptory.fabric.client.editor.render.ghost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * A synthetic stress test of the renderer-wide ghost memory budget (M3 "Client ghosts").
 *
 * <p>{@link GhostRenderer} needs GL, so its per-frame loop is modelled here step for step, around the real
 * {@link GhostBudget}: per placement, upload finished meshes (renderer-wide upload budget), plan with
 * {@link GhostBudget#bytesElsewhere}, free meshes no placement wanted for a whole frame, and request missing meshes
 * nearest first with at most {@link GhostMeshCache#MAX_IN_FLIGHT} jobs per volume. Meshes land one to three frames
 * later with their <em>actual</em> size, which differs from the planner's 160 bytes/block estimate by up to 5 times
 * (sparse or leafy content shows far more faces per block than solid content).
 *
 * <p>Scenarios: a 2,097,152-cell paste (the clipboard cap), a scatter of 32 baked variants drawn by 1,280 placements, a
 * 512-cell-wide place preview, and all of them at once, while the camera flies across and then stops.
 */
class GhostMemoryStressTest {
    private static final double BYTES_PER_BLOCK_SOLID = 60; // a solid box: only its outside faces
    private static final double BYTES_PER_BLOCK_SPARSE = 768; // six faces of 128 bytes per block

    /** One section of a synthetic volume: blocks, its centre in the volume and its actual mesh size. */
    private record Sec(int blocks, double x, double y, double z, long actualBytes) {}

    private static final class Vol {
        final String name;
        final List<Sec> sections = new ArrayList<>();
        long[] bytes;
        /** Size of the last mesh built (kept after it is freed; content never changes here), or -1. */
        long[] measured;
        boolean[] meshed;
        boolean[] pending;
        long[] wantedFrame;
        int inFlight;
        final Deque<Job> finished = new ArrayDeque<>();

        Vol(String name) {
            this.name = name;
        }

        void seal() {
            int n = sections.size();
            bytes = new long[n];
            measured = new long[n];
            java.util.Arrays.fill(measured, -1);
            meshed = new boolean[n];
            pending = new boolean[n];
            wantedFrame = new long[n];
            java.util.Arrays.fill(wantedFrame, -2);
            inFlight = 0;
            finished.clear();
        }

        long total() {
            long sum = 0;
            for (long b : bytes) sum += b;
            return sum;
        }

        long meshedBlocks() {
            long sum = 0;
            for (int i = 0; i < sections.size(); i++) {
                if (meshed[i]) sum += sections.get(i).blocks();
            }
            return sum;
        }

        long maxSectionBytes() {
            long max = 0;
            for (Sec s : sections) max = Math.max(max, s.actualBytes());
            return max;
        }
    }

    private record Place(Vol vol, double x, double y, double z) {}

    private record Job(Vol vol, int section, long readyFrame) {}

    /** What one run saw. */
    private record Stats(long maxTotal, long finalTotal, long bound, int freesWhileStill, int requestsWhileStill,
                         long maxMeshedBlocksPerVolume) {}

    /**
     * A box-shaped volume of sections, each with {@code fill} of its 4096 cells as blocks; actual mesh bytes per block
     * drawn from [{@code minBpb}, {@code maxBpb}].
     */
    private static Vol box(String name, int sx, int sy, int sz, double fill, double minBpb, double maxBpb, Random random) {
        Vol vol = new Vol(name);
        for (int x = 0; x < sx; x++) {
            for (int y = 0; y < sy; y++) {
                for (int z = 0; z < sz; z++) {
                    int blocks = (int) Math.max(1, Math.round(4096 * fill));
                    double bpb = minBpb + random.nextDouble() * (maxBpb - minBpb);
                    vol.sections.add(new Sec(blocks, x * 16 + 8, y * 16 + 8, z * 16 + 8, (long) (blocks * bpb)));
                }
            }
        }
        vol.seal();
        return vol;
    }

    /** A small scatter variant (a tree: one or two sections of a few hundred leafy blocks). */
    private static Vol variant(String name, Random random) {
        Vol vol = new Vol(name);
        int sections = 1 + random.nextInt(2);
        for (int i = 0; i < sections; i++) {
            int blocks = 40 + random.nextInt(360);
            vol.sections.add(new Sec(blocks, 8, i * 16 + 8, 8, (long) (blocks * (300 + random.nextDouble() * 468))));
        }
        vol.seal();
        return vol;
    }

    /**
     * Runs {@code frames} frames; the camera flies along x for the first {@code moving} frames, then stops. Mirrors
     * {@code GhostRenderer.render}/{@code requestMeshes} per placement, in order.
     */
    private static Stats simulate(List<Place> places, List<Vol> vols, GhostConfig config, int frames, int moving,
                                  double camY, double fromX, double toX, double camZ, Random random,
                                  boolean rememberSizes) {
        List<Job> running = new ArrayList<>();
        long maxTotal = 0;
        int freesWhileStill = 0;
        int requestsWhileStill = 0;
        long maxMeshedBlocks = 0;
        long bound = config.maxVertexBytes();
        for (Vol vol : vols) bound += GhostMeshCache.MAX_IN_FLIGHT * vol.maxSectionBytes();
        for (long frame = 1; frame <= frames; frame++) {
            double t = Math.min(1.0, (frame - 1) / (double) Math.max(1, moving));
            double camX = fromX + (toX - fromX) * t;
            boolean still = frame > frames - 100; // the last 100 frames, long after the camera stopped
            // Worker results that are ready by now.
            for (int i = running.size() - 1; i >= 0; i--) {
                Job job = running.get(i);
                if (job.readyFrame() <= frame) {
                    job.vol().finished.add(job);
                    running.remove(i);
                }
            }
            int uploadsLeft = config.maxUploadsPerFrame();
            for (Place place : places) {
                Vol vol = place.vol();
                // upload(cache): finished meshes, within the renderer-wide per-frame budget.
                while (uploadsLeft > 0 && !vol.finished.isEmpty()) {
                    Job job = vol.finished.poll();
                    vol.inFlight--;
                    vol.pending[job.section()] = false;
                    vol.meshed[job.section()] = true;
                    vol.bytes[job.section()] = vol.sections.get(job.section()).actualBytes();
                    vol.measured[job.section()] = vol.bytes[job.section()];
                    uploadsLeft--;
                }
                List<GhostBudget.Candidate> candidates = new ArrayList<>(vol.sections.size());
                for (int i = 0; i < vol.sections.size(); i++) {
                    Sec s = vol.sections.get(i);
                    double dx = Math.max(0, Math.abs(place.x() + s.x() - camX) - 8);
                    double dy = Math.max(0, Math.abs(place.y() + s.y() - camY) - 8);
                    double dz = Math.max(0, Math.abs(place.z() + s.z() - camZ) - 8);
                    double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    candidates.add(new GhostBudget.Candidate(s.blocks(), 0, distance, vol.meshed[i] ? vol.bytes[i] : rememberSizes ? vol.measured[i] : -1,
                            vol.meshed[i]));
                }
                long all = 0;
                for (Vol v : vols) all += v.total();
                long elsewhere = GhostBudget.bytesElsewhere(all, vol.total(), config.maxVertexBytes());
                GhostBudget.Plan plan = GhostBudget.plan(candidates, config, false, elsewhere);
                // requestMeshes: keep what a placement wanted this frame or the last, free the rest...
                for (int i = 0; i < vol.sections.size(); i++) {
                    if (plan.draw(i) == GhostBudget.Draw.MESH) {
                        vol.wantedFrame[i] = frame;
                    } else if (vol.meshed[i] && frame - vol.wantedFrame[i] > 1) {
                        vol.meshed[i] = false;
                        vol.bytes[i] = 0;
                        if (still) freesWhileStill++;
                    }
                }
                // ...then ask for missing meshes, nearest first, while fewer than MAX_IN_FLIGHT are in flight.
                for (int index : plan.nearestFirst()) {
                    if (plan.draw(index) != GhostBudget.Draw.MESH || vol.pending[index] || vol.meshed[index]) continue;
                    if (vol.inFlight >= GhostMeshCache.MAX_IN_FLIGHT) break;
                    vol.pending[index] = true;
                    vol.inFlight++;
                    running.add(new Job(vol, index, frame + 1 + random.nextInt(3)));
                    if (still) requestsWhileStill++;
                }
            }
            long total = 0;
            for (Vol v : vols) {
                total += v.total();
                maxMeshedBlocks = Math.max(maxMeshedBlocks, v.meshedBlocks());
            }
            maxTotal = Math.max(maxTotal, total);
        }
        long finalTotal = 0;
        for (Vol v : vols) finalTotal += v.total();
        return new Stats(maxTotal, finalTotal, bound, freesWhileStill, requestsWhileStill, maxMeshedBlocks);
    }

    private static GhostConfig withMemoryCap(long bytes) {
        GhostConfig d = GhostConfig.DEFAULTS;
        return new GhostConfig(d.maxMeshedBlocks(), bytes, d.fullDetailDistance(), d.maxUploadsPerFrame(),
                d.uploadBudgetNanos(), d.maxEraseOutlines());
    }

    private static void report(String what, Stats s, GhostConfig config) {
        System.out.printf(Locale.ROOT, "ghost stress %s: cap %.1f MB, peak %.1f MB (%.2f x cap), settled %.1f MB, "
                        + "in-flight bound %.1f MB, frees/requests while still %d/%d, max meshed blocks per volume %,d%n",
                what, config.maxVertexBytes() / 1e6, s.maxTotal() / 1e6, s.maxTotal() / (double) config.maxVertexBytes(),
                s.finalTotal() / 1e6, s.bound() / 1e6, s.freesWhileStill(), s.requestsWhileStill(),
                s.maxMeshedBlocksPerVolume());
    }

    /** Checks shared by every scenario: the documented bound, the per-volume block cap, and a stable end state. */
    private static void checkBudget(Stats s, GhostConfig config) {
        assertTrue(s.maxTotal() <= s.bound(), "peak " + s.maxTotal() + " over the cap plus in-flight meshes " + s.bound());
        assertTrue(s.maxMeshedBlocksPerVolume() <= config.maxMeshedBlocks() + GhostMeshCache.MAX_IN_FLIGHT * 4096L,
                "a volume meshed " + s.maxMeshedBlocksPerVolume() + " blocks");
        assertEquals(0, s.freesWhileStill(), "meshes freed while nothing moved (thrashing)");
        assertEquals(0, s.requestsWhileStill(), "meshes requested while nothing moved (thrashing)");
    }

    @Test
    void aClipboardSizedPasteStaysWithinTheCap() {
        Random random = new Random(1);
        // 2,097,152 cells: 16 × 8 × 16 sections, half of each section filled, sparse content (up to 768 B/block).
        Vol paste = box("paste", 16, 8, 16, 0.5, BYTES_PER_BLOCK_SOLID, BYTES_PER_BLOCK_SPARSE, random);
        GhostConfig config = GhostConfig.DEFAULTS;
        Stats s = simulate(List.of(new Place(paste, 0, 64, 0)), List.of(paste), config, 600, 300, 100, -64, 320, 128,
                random, true);
        report("2M paste", s, config);
        checkBudget(s, config);
        // One volume shrinks back under the cap on its own.
        assertTrue(s.finalTotal() <= config.maxVertexBytes(), "settled at " + s.finalTotal());
    }

    @Test
    void aScatterOf32BakedVariantsStaysWithinTheCap() {
        Random random = new Random(2);
        List<Vol> variants = new ArrayList<>();
        List<Place> places = new ArrayList<>();
        for (int v = 0; v < 32; v++) {
            Vol vol = variant("variant " + v, random);
            variants.add(vol);
            for (int p = 0; p < 40; p++) places.add(new Place(vol, random.nextInt(512), 64, random.nextInt(512)));
        }
        GhostConfig config = withMemoryCap(4L << 20); // small enough that the cap binds
        Stats s = simulate(places, variants, config, 600, 300, 90, 0, 512, 256, random, true);
        report("scatter 32 variants x 40", s, config);
        checkBudget(s, config);
        assertTrue(s.finalTotal() <= config.maxVertexBytes() * 1.1, "settled at " + s.finalTotal());
    }

    /** A 2M-cell paste, a 512 × 64 × 512 place preview (a quarter full) and 32 scatter variants × 40 placements. */
    private record Mixed(List<Vol> vols, List<Place> places) {
        static Mixed build(Random random) {
            List<Vol> vols = new ArrayList<>();
            List<Place> places = new ArrayList<>();
            Vol paste = box("paste", 16, 8, 16, 0.5, BYTES_PER_BLOCK_SOLID, BYTES_PER_BLOCK_SPARSE, random);
            vols.add(paste);
            places.add(new Place(paste, 0, 64, 0));
            Vol place = box("place", 32, 4, 32, 0.25, BYTES_PER_BLOCK_SOLID, BYTES_PER_BLOCK_SPARSE, random);
            vols.add(place);
            places.add(new Place(place, 128, 70, 64));
            for (int v = 0; v < 32; v++) {
                Vol vol = variant("variant " + v, random);
                vols.add(vol);
                for (int p = 0; p < 40; p++) places.add(new Place(vol, random.nextInt(512), 64, random.nextInt(512)));
            }
            return new Mixed(vols, places);
        }

        Stats run(long cap, Random random, boolean rememberSizes) {
            for (Vol vol : vols) vol.seal();
            return simulate(places, vols, withMemoryCap(cap), 1000, 400, 100, -64, 512, 200, random, rememberSizes);
        }
    }

    @Test
    void pasteScatterAndPlacePreviewsShareOneBudget() {
        Random random = new Random(3);
        Mixed mixed = Mixed.build(random);
        for (long cap : new long[] {GhostConfig.DEFAULTS.maxVertexBytes(), 32L << 20}) {
            GhostConfig config = withMemoryCap(cap);
            Stats s = mixed.run(cap, random, true);
            report("paste + place + scatter, cap " + (cap >> 20) + " MiB", s, config);
            checkBudget(s, config);
            // Several volumes can each keep what they hold, so the total may stay above the cap by the meshes that
            // were in flight when it filled (checkBudget's bound). At the default cap it settles below it.
            if (cap == GhostConfig.DEFAULTS.maxVertexBytes()) {
                assertTrue(s.finalTotal() <= config.maxVertexBytes(), "settled at " + s.finalTotal());
            }
        }
    }

    /**
     * Regression (M4 perf): planning a freed section with the per-block estimate again, instead of the size its last
     * mesh really had, makes sections at the edge of a binding cap mesh, get boxed and freed, and mesh again forever
     * while nothing moves. {@code GhostMeshCache.Entry.knownBytes} keeps the measured size.
     */
    @Test
    void forgettingMeasuredMeshSizesMakesSectionsChurn() {
        Mixed mixed = Mixed.build(new Random(3));
        long cap = 32L << 20;
        Stats forgetting = mixed.run(cap, new Random(4), false);
        report("forgetting measured sizes, cap 32 MiB", forgetting, withMemoryCap(cap));
        assertTrue(forgetting.freesWhileStill() > 0 && forgetting.requestsWhileStill() > 0,
                "expected churn with the old policy: " + forgetting);
        Stats remembering = mixed.run(cap, new Random(4), true);
        assertEquals(0, remembering.freesWhileStill(), "churn with measured sizes: " + remembering);
        assertEquals(0, remembering.requestsWhileStill(), "churn with measured sizes: " + remembering);
    }
}
