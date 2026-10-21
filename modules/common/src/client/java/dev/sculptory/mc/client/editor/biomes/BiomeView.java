package dev.sculptory.mc.client.editor.biomes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Biome view (protocol 6, View ▸ Biome view): the ground's top faces around the camera tinted by their biome, and a
 * legend of the biomes in view. Purely client-side: it reads the biomes the client holds, which the server's biome
 * packets keep up to date, so a painted biome shows as soon as its packet arrives.
 *
 * <p>The tint is a snapshot of every block column within {@value #RADIUS} blocks of the camera: its top block (the
 * {@code WORLD_SURFACE} heightmap) and the biome of the quart holding it. It is taken on the client thread (about
 * 6,500 heightmap reads and a few hundred biome reads) when the view turns on, when biomes or chunks arrive
 * ({@link #changed}, bumped by the biome packet hook and chunk loads), or when the camera moves {@value #MOVE} blocks;
 * between those the same snapshot is drawn each frame. Client thread only (but {@link #changed}).
 */
public final class BiomeView {
    /** How far from the camera the ground is tinted, in blocks across. */
    public static final int RADIUS = 40;
    /** How far the camera moves before the snapshot is taken again. */
    static final int MOVE = 8;
    /** Biome packets and chunk loads seen (bumped from the network thread's hand-off too). */
    private static final AtomicLong CHANGES = new AtomicLong();

    /** One biome of the legend: its id, name, colour and how many columns in view show it. */
    public record LegendEntry(String id, String name, int color, int columns) {}

    /**
     * The snapshot: per tinted column its x, z, top block y and biome colour; and the legend, most columns first.
     */
    public record Tint(int[] xs, int[] zs, int[] ys, int[] colors, List<LegendEntry> legend) {
        public int size() {
            return xs.length;
        }
    }

    private boolean enabled;
    private Tint tint;
    private long seenChanges = -1;
    private int seenX = Integer.MIN_VALUE;
    private int seenZ = Integer.MIN_VALUE;
    private ClientLevel seenWorld;

    /** Biomes or chunks arrived: the snapshot is taken again on the next frame. Any thread. */
    public static void changed() {
        CHANGES.incrementAndGet();
    }

    public boolean enabled() {
        return enabled;
    }

    /** Turns the view on or off; off drops the snapshot. */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            tint = null;
            seenChanges = -1;
        }
    }

    /** The snapshot to draw, while on. */
    public Optional<Tint> tint() {
        return enabled ? Optional.ofNullable(tint) : Optional.empty();
    }

    /** Takes the snapshot again around the camera at (x, z) when something changed since; once a frame while on. */
    public void update(ClientLevel world, double cameraX, double cameraZ, BiomeCatalog catalog) {
        if (!enabled) return;
        if (world == null) {
            tint = null;
            return;
        }
        int x = (int) Math.floor(cameraX), z = (int) Math.floor(cameraZ);
        long changes = CHANGES.get();
        if (world == seenWorld && changes == seenChanges && Math.abs(x - seenX) < MOVE && Math.abs(z - seenZ) < MOVE) {
            return;
        }
        seenWorld = world;
        seenChanges = changes;
        seenX = x;
        seenZ = z;
        tint = sample(world, x, z, Objects.requireNonNull(catalog));
    }

    private static Tint sample(ClientLevel world, int cx, int cz, BiomeCatalog catalog) {
        int side = 2 * RADIUS + 1, max = side * side;
        int[] xs = new int[max], zs = new int[max], ys = new int[max], colors = new int[max];
        int n = 0;
        Map<Long, String> quarts = new HashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        int bottom = world.getMinBuildHeight();
        for (int z = cz - RADIUS; z <= cz + RADIUS; z++) {
            for (int x = cx - RADIUS; x <= cx + RADIUS; x++) {
                if (!world.hasChunk(x >> 4, z >> 4)) continue;
                int top = world.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                if (top < bottom) continue;
                int qx = x >> 2, qy = top >> 2, qz = z >> 2;
                long key = ((long) (qx & 0x3FFFFFF) << 38) | ((long) (qz & 0x3FFFFFF) << 12) | (qy & 0xFFF);
                String id = quarts.computeIfAbsent(key, k -> idOf(world.getNoiseBiome(qx, qy, qz)));
                xs[n] = x;
                zs[n] = z;
                ys[n] = top;
                colors[n] = catalog.color(id);
                counts.merge(id, 1, Integer::sum);
                n++;
            }
        }
        List<LegendEntry> legend = new ArrayList<>(counts.size());
        counts.forEach((id, columns) -> legend.add(new LegendEntry(id, catalog.name(id), catalog.color(id), columns)));
        legend.sort(Comparator.comparingInt(LegendEntry::columns).reversed().thenComparing(LegendEntry::name));
        return new Tint(java.util.Arrays.copyOf(xs, n), java.util.Arrays.copyOf(zs, n), java.util.Arrays.copyOf(ys, n),
                java.util.Arrays.copyOf(colors, n), List.copyOf(legend));
    }

    private static String idOf(Holder<Biome> biome) {
        return biome.unwrapKey().map(key -> key.location().toString()).orElse("minecraft:plains");
    }
}
