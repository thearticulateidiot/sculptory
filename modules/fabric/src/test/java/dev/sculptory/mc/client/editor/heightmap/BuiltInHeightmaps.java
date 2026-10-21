package dev.sculptory.mc.client.editor.heightmap;

import dev.sculptory.core.SplitMix64;
import dev.sculptory.core.brush.HeightImage;

/**
 * The generator of the built-in height pictures ({@link HeightmapLibrary#BUILT_IN}): each a 256 × 256 grayscale image
 * drawn from a formula and a fixed seed, so the files under {@code assets/sculptory/heightmaps/} are our own work and
 * can be made again exactly ({@code BuiltInHeightmapsTest} checks they are). Every picture fades to black before its
 * edge, so a stamp leaves no step around its square.
 */
final class BuiltInHeightmaps {
    static final int SIDE = 256;

    private BuiltInHeightmaps() {}

    /** The picture named {@code name}. */
    static HeightImage generate(String name) {
        byte[] pixels = new byte[SIDE * SIDE];
        for (int y = 0; y < SIDE; y++) {
            for (int x = 0; x < SIDE; x++) {
                // Centred coordinates: -1 to 1 across the picture.
                double u = (x + 0.5) / SIDE * 2 - 1, v = (y + 0.5) / SIDE * 2 - 1;
                double h = switch (name) {
                    case "hill" -> hill(u, v);
                    case "mountain" -> mountain(u, v);
                    case "crater" -> crater(u, v);
                    case "mesa" -> mesa(u, v);
                    case "ridge" -> ridge(u, v);
                    case "dunes" -> dunes(u, v);
                    case "volcano" -> volcano(u, v);
                    case "terraces" -> terraces(u, v);
                    default -> throw new IllegalArgumentException("No built-in heightmap " + name);
                };
                pixels[y * SIDE + x] = (byte) (int) StrictMath.round(255 * Math.max(0, Math.min(1, h)));
            }
        }
        return new HeightImage(SIDE, SIDE, pixels);
    }

    /** A round, smooth hill. */
    private static double hill(double u, double v) {
        double d = StrictMath.sqrt(u * u + v * v) / 0.9;
        return d >= 1 ? 0 : 0.5 + 0.5 * StrictMath.cos(StrictMath.PI * d);
    }

    /** A peak with lumpy shoulders and spurs. */
    private static double mountain(double u, double v) {
        double d = StrictMath.sqrt(u * u + v * v);
        double cone = Math.max(0, 1 - d / 0.92);
        double rough = fbm(u * 3 + 7, v * 3 - 2, 5, 11L);
        double spurs = 0.5 + 0.5 * StrictMath.cos(5 * StrictMath.atan2(v, u) + rough * 3);
        // The spurs fade in away from the summit, where their angle has no meaning.
        double spread = smoothStep(0.05, 0.45, d);
        double h = StrictMath.pow(cone, 1.4) * (1 - 0.25 * spread + 0.25 * spread * spurs) + 0.18 * cone * (rough - 0.5);
        return h * fade(d);
    }

    /** A bowl inside a raised rim. */
    private static double crater(double u, double v) {
        double d = StrictMath.sqrt(u * u + v * v);
        double rim = 0.95 * StrictMath.exp(-StrictMath.pow((d - 0.5) / 0.14, 2));
        double outer = d > 0.5 ? Math.max(0, 1 - (d - 0.5) / 0.4) * 0.35 : 0;
        double floor = d < 0.5 ? 0.25 + 0.15 * (d / 0.5) * (d / 0.5) : 0;
        return Math.max(rim, Math.max(outer, floor)) * fade(d);
    }

    /** A flat-topped table with steep, irregular sides. */
    private static double mesa(double u, double v) {
        double d = StrictMath.sqrt(u * u + v * v);
        double edge = 0.58 + 0.08 * (fbm(u * 2.5, v * 2.5, 3, 23L) - 0.5);
        double top = 1 - smoothStep(edge - 0.02, edge + 0.02, d);
        double talus = Math.max(0, 1 - (d - edge) / 0.25) * 0.18;
        return Math.max(0.9 * top, d > edge ? talus : 0) * fade(d);
    }

    /** A long ridge running east to west, with a knobbly crest. */
    private static double ridge(double u, double v) {
        double along = 1 - smoothStep(0.3, 0.92, Math.abs(u));
        // A sharp crest line along the middle, the flanks curving down.
        double across = StrictMath.pow(Math.max(0, 1 - Math.abs(v) / 0.45), 1.6);
        double crest = 0.78 + 0.15 * fbm(u * 4, 1.7, 3, 31L);
        return along * across * crest * fade(StrictMath.sqrt(u * u + v * v) * 0.75);
    }

    /** Rows of dunes, gentle on the windward west side and steep on the east. */
    private static double dunes(double u, double v) {
        double phase = (u * 3.5 + 0.35 * StrictMath.sin(v * 3.1)) % 1.0;
        if (phase < 0) phase += 1;
        double profile = phase < 0.75 ? phase / 0.75 : (1 - phase) / 0.25;
        double swell = 0.55 + 0.45 * StrictMath.cos(v * 2.2);
        return smooth(profile) * swell * 0.8 * (1 - smoothStep(0.45, 0.95, StrictMath.sqrt(u * u + v * v)));
    }

    /** A cone with a crater at its top. */
    private static double volcano(double u, double v) {
        double d = StrictMath.sqrt(u * u + v * v);
        double cone = StrictMath.pow(Math.max(0, 1 - d / 0.92), 1.2);
        double pit = d < 0.16 ? (1 - smooth(d / 0.16)) * 0.35 : 0;
        double gullies = 0.04 * StrictMath.cos(9 * StrictMath.atan2(v, u)) * cone * smoothStep(0.2, 0.5, d);
        return Math.max(0, cone - pit + gullies) * fade(d);
    }

    /** A hill cut into level steps. */
    private static double terraces(double u, double v) {
        double steps = 6;
        // Capped below the next riser, so the summit is the top step, flat, rather than a lone pixel above it.
        double h = Math.min((steps - 0.2) / steps, hill(u, v));
        double level = StrictMath.floor(h * steps) / steps;
        double rise = smoothStep(0.85, 1, h * steps - StrictMath.floor(h * steps));
        return level + rise / steps;
    }

    /** 1 inside, falling smoothly to 0 by the picture's edge (d = 1). */
    private static double fade(double d) {
        return 1 - smoothStep(0.85, 1, d);
    }

    private static double smooth(double t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }

    private static double smoothStep(double from, double to, double x) {
        return smooth((x - from) / (to - from));
    }

    /** Fractal value noise, 0 to 1: {@code octaves} layers of smoothly blended lattice values. */
    private static double fbm(double x, double y, int octaves, long seed) {
        double sum = 0, weight = 0, amplitude = 1, frequency = 1;
        for (int o = 0; o < octaves; o++) {
            sum += amplitude * noise(x * frequency, y * frequency, seed + o);
            weight += amplitude;
            amplitude /= 2;
            frequency *= 2;
        }
        return sum / weight;
    }

    private static double noise(double x, double y, long seed) {
        int ix = (int) StrictMath.floor(x), iy = (int) StrictMath.floor(y);
        double fx = smooth(x - ix), fy = smooth(y - iy);
        double a = lattice(ix, iy, seed), b = lattice(ix + 1, iy, seed);
        double c = lattice(ix, iy + 1, seed), d = lattice(ix + 1, iy + 1, seed);
        return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy;
    }

    private static double lattice(int x, int y, long seed) {
        return (SplitMix64.hash(seed, x, y, 0) >>> 11) / (double) (1L << 53);
    }
}
