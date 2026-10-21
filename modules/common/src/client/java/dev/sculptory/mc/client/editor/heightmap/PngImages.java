package dev.sculptory.mc.client.editor.heightmap;

import dev.sculptory.core.brush.HeightImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipException;

/**
 * Reads any PNG into a {@link HeightImage} and writes 8-bit grayscale PNGs, in plain Java (no game or desktop
 * libraries), so the built-in pictures and the builder's own read the same everywhere, in tests too.
 *
 * <p>Every standard PNG reads: grayscale, RGB, palette, with alpha or not, 1 to 16 bits, interlaced or not. A colour
 * pixel's height is its brightness ({@code (299 R + 587 G + 114 B) / 1000}); 16-bit samples keep their high byte;
 * transparency darkens a pixel toward 0 (a see-through area is low ground). The rows are unpacked one at a time as
 * they are inflated, and a picture wider or taller than {@value #MAX_LOADED} pixels is shrunk while it is read, each
 * pixel the mean of an {@code f × f} block ({@code f} the smallest whole factor that fits): memory stays at most a few
 * MB whatever the file claims. Anything else is refused with a {@link Reason} ({@link BadImageException}).
 */
public final class PngImages {
    /** The widest or tallest picture read, before shrinking. */
    public static final int MAX_SIDE = 4096;
    /** Pictures are kept at most this many pixels a side: shrunk as they are read. */
    public static final int MAX_LOADED = 1024;
    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    /** The Adam7 passes: x start, y start, x step, y step. */
    private static final int[][] ADAM7 = {{0, 0, 8, 8}, {4, 0, 8, 8}, {0, 4, 4, 8}, {2, 0, 4, 4}, {0, 2, 2, 4},
            {1, 0, 2, 2}, {0, 1, 1, 2}};

    private PngImages() {}

    /** Why a file can't be used: each has its own one-line message ({@code sculptory.notice.heightmap_bad_file.<key>}). */
    public enum Reason {
        NOT_PNG, DAMAGED, TOO_LARGE, UNSUPPORTED, FILE_TOO_LARGE, BAD_NAME, UNREADABLE;

        /** The reason's part of its message key. */
        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** A PNG that can't be used, and why. */
    public static final class BadImageException extends Exception {
        private final Reason reason;

        public BadImageException(Reason reason) {
            super(reason.key());
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }

    /** Reads {@code bytes} as a PNG; the height of each pixel is its brightness. */
    public static HeightImage read(byte[] bytes) throws BadImageException {
        if (bytes.length < SIGNATURE.length + 25) throw new BadImageException(Reason.NOT_PNG);
        for (int i = 0; i < SIGNATURE.length; i++) {
            if (bytes[i] != SIGNATURE[i]) throw new BadImageException(Reason.NOT_PNG);
        }
        int pos = SIGNATURE.length;
        int width = 0, height = 0, depth = 0, colour = -1, interlace = 0;
        byte[] palette = null;
        byte[] paletteAlpha = null;
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        boolean ended = false;
        while (pos + 12 <= bytes.length) {
            int length = readInt(bytes, pos);
            String type = new String(bytes, pos + 4, 4, StandardCharsets.ISO_8859_1);
            int start = pos + 8;
            if (length < 0 || (long) start + length + 4 > bytes.length) throw new BadImageException(Reason.DAMAGED);
            switch (type) {
                case "IHDR" -> {
                    if (length < 13) throw new BadImageException(Reason.DAMAGED);
                    width = readInt(bytes, start);
                    height = readInt(bytes, start + 4);
                    depth = bytes[start + 8] & 0xFF;
                    colour = bytes[start + 9] & 0xFF;
                    interlace = bytes[start + 12] & 0xFF;
                    if (bytes[start + 10] != 0 || bytes[start + 11] != 0 || interlace > 1) {
                        throw new BadImageException(Reason.UNSUPPORTED);
                    }
                }
                case "PLTE" -> {
                    palette = new byte[length];
                    System.arraycopy(bytes, start, palette, 0, length);
                }
                case "tRNS" -> {
                    if (colour == 3) {
                        paletteAlpha = new byte[length];
                        System.arraycopy(bytes, start, paletteAlpha, 0, length);
                    }
                }
                case "IDAT" -> data.write(bytes, start, length);
                case "IEND" -> ended = true;
                default -> {
                    // Ancillary chunks (text, gamma, colour profiles) don't change the heights.
                }
            }
            if (ended) break;
            pos = start + length + 4;
        }
        if (colour < 0) throw new BadImageException(Reason.DAMAGED);
        if (width < 1 || height < 1 || width > MAX_SIDE || height > MAX_SIDE) throw new BadImageException(Reason.TOO_LARGE);
        int channels = switch (colour) {
            case 0, 3 -> 1;
            case 2 -> 3;
            case 4 -> 2;
            case 6 -> 4;
            default -> throw new BadImageException(Reason.UNSUPPORTED);
        };
        boolean depthOk = switch (colour) {
            case 0 -> depth == 1 || depth == 2 || depth == 4 || depth == 8 || depth == 16;
            case 3 -> depth == 1 || depth == 2 || depth == 4 || depth == 8;
            default -> depth == 8 || depth == 16;
        };
        if (!depthOk) throw new BadImageException(Reason.UNSUPPORTED);
        if (colour == 3 && palette == null) throw new BadImageException(Reason.DAMAGED);
        Shrinker shrinker = new Shrinker(width, height);
        Decoder decoder = new Decoder(colour, depth, channels, channels * depth, palette, paletteAlpha, shrinker);
        try (DataInputStream rows = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(data.toByteArray()),
                new Inflater(), 8192))) {
            if (interlace == 0) {
                decoder.pass(rows, 0, 0, 1, 1, width, height);
            } else {
                for (int[] pass : ADAM7) {
                    int w = (width - pass[0] + pass[2] - 1) / pass[2];
                    int h = (height - pass[1] + pass[3] - 1) / pass[3];
                    if (w > 0 && h > 0) decoder.pass(rows, pass[0], pass[1], pass[2], pass[3], w, h);
                }
            }
        } catch (EOFException | ZipException e) {
            throw new BadImageException(Reason.DAMAGED);
        } catch (IOException e) {
            throw new BadImageException(Reason.UNREADABLE);
        }
        return shrinker.image();
    }

    /** An 8-bit grayscale PNG of {@code image}, without interlacing, each row with the filter that suits it best. */
    public static byte[] writeGray(HeightImage image) {
        int w = image.width(), h = image.height();
        byte[] pixels = image.pixels();
        ByteArrayOutputStream rows = new ByteArrayOutputStream();
        byte[] previous = new byte[w];
        for (int y = 0; y < h; y++) {
            byte[] row = new byte[w];
            System.arraycopy(pixels, y * w, row, 0, w);
            byte[] best = null;
            long bestCost = Long.MAX_VALUE;
            for (int filter = 0; filter <= 4; filter++) {
                byte[] filtered = filter(row, previous, filter);
                long cost = 0;
                for (int i = 1; i < filtered.length; i++) cost += Math.abs((int) filtered[i]);
                if (cost < bestCost) {
                    bestCost = cost;
                    best = filtered;
                }
            }
            rows.write(best, 0, best.length);
            previous = row;
        }
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        deflater.setInput(rows.toByteArray());
        deflater.finish();
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (!deflater.finished()) compressed.write(buffer, 0, deflater.deflate(buffer));
        deflater.end();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(SIGNATURE, 0, SIGNATURE.length);
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        try (DataOutputStream ihdr = new DataOutputStream(header)) {
            ihdr.writeInt(w);
            ihdr.writeInt(h);
            ihdr.write(new byte[] {8, 0, 0, 0, 0});
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        chunk(out, "IHDR", header.toByteArray());
        chunk(out, "IDAT", compressed.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    /** {@code row} with PNG filter {@code type} applied against {@code previous}, the type byte first (one byte a pixel). */
    private static byte[] filter(byte[] row, byte[] previous, int type) {
        byte[] out = new byte[row.length + 1];
        out[0] = (byte) type;
        for (int i = 0; i < row.length; i++) {
            int a = i > 0 ? row[i - 1] & 0xFF : 0, b = previous[i] & 0xFF, c = i > 0 ? previous[i - 1] & 0xFF : 0;
            int predictor = switch (type) {
                case 1 -> a;
                case 2 -> b;
                case 3 -> (a + b) / 2;
                case 4 -> paeth(a, b, c);
                default -> 0;
            };
            out[i + 1] = (byte) ((row[i] & 0xFF) - predictor);
        }
        return out;
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] name = type.getBytes(StandardCharsets.ISO_8859_1);
        writeInt(out, data.length);
        out.write(name, 0, 4);
        out.write(data, 0, data.length);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        writeInt(out, (int) crc.getValue());
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write(value >>> 24);
        out.write(value >>> 16);
        out.write(value >>> 8);
        out.write(value);
    }

    private static int readInt(byte[] bytes, int at) {
        return ((bytes[at] & 0xFF) << 24) | ((bytes[at + 1] & 0xFF) << 16) | ((bytes[at + 2] & 0xFF) << 8) | (bytes[at + 3] & 0xFF);
    }

    private static int paeth(int a, int b, int c) {
        int p = a + b - c;
        int pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
        if (pa <= pb && pa <= pc) return a;
        return pb <= pc ? b : c;
    }

    /**
     * The picture as it is read, shrunk by a whole factor {@code f} so it fits {@link #MAX_LOADED} pixels a side: each
     * pixel goes into its {@code f × f} block, whatever order the pixels come in (Adam7 included).
     */
    private static final class Shrinker {
        private final int width, factor, outWidth, outHeight;
        private final int[] sums;
        private final short[] counts;

        Shrinker(int width, int height) {
            this.width = width;
            this.factor = (Math.max(width, height) + MAX_LOADED - 1) / MAX_LOADED;
            this.outWidth = (width + factor - 1) / factor;
            this.outHeight = (height + factor - 1) / factor;
            this.sums = new int[outWidth * outHeight];
            this.counts = factor == 1 ? null : new short[outWidth * outHeight];
        }

        void add(int x, int y, int value) {
            int i = (y / factor) * outWidth + x / factor;
            sums[i] += value;
            if (counts != null) counts[i]++;
        }

        HeightImage image() {
            byte[] pixels = new byte[sums.length];
            for (int i = 0; i < pixels.length; i++) {
                int n = counts == null ? 1 : Math.max(1, counts[i]);
                pixels[i] = (byte) ((sums[i] + n / 2) / n);
            }
            return new HeightImage(outWidth, outHeight, pixels);
        }
    }

    /** Unfilters a pass's rows as they are inflated and turns each pixel into a height. */
    private static final class Decoder {
        private final int colour, depth, channels, bitsPerPixel;
        private final byte[] palette, paletteAlpha;
        private final Shrinker out;

        Decoder(int colour, int depth, int channels, int bitsPerPixel, byte[] palette, byte[] paletteAlpha, Shrinker out) {
            this.colour = colour;
            this.depth = depth;
            this.channels = channels;
            this.bitsPerPixel = bitsPerPixel;
            this.palette = palette;
            this.paletteAlpha = paletteAlpha;
            this.out = out;
        }

        void pass(DataInputStream in, int x0, int y0, int dx, int dy, int w, int h) throws IOException, BadImageException {
            int rowBytes = (w * bitsPerPixel + 7) / 8;
            int bpp = Math.max(1, bitsPerPixel / 8);
            byte[] previous = new byte[rowBytes];
            byte[] row = new byte[rowBytes];
            for (int y = 0; y < h; y++) {
                int type = in.readUnsignedByte();
                if (type > 4) throw new BadImageException(Reason.DAMAGED);
                in.readFully(row);
                for (int i = 0; i < rowBytes; i++) {
                    int a = i >= bpp ? row[i - bpp] & 0xFF : 0, b = previous[i] & 0xFF;
                    int c = i >= bpp ? previous[i - bpp] & 0xFF : 0;
                    int predictor = switch (type) {
                        case 1 -> a;
                        case 2 -> b;
                        case 3 -> (a + b) / 2;
                        case 4 -> paeth(a, b, c);
                        default -> 0;
                    };
                    row[i] = (byte) (row[i] + predictor);
                }
                for (int x = 0; x < w; x++) out.add(x0 + x * dx, y0 + y * dy, height(row, x));
                byte[] swap = previous;
                previous = row;
                row = swap;
            }
        }

        /** Pixel {@code x} of an unfiltered row as a height, 0-255. */
        private int height(byte[] row, int x) {
            if (depth < 8) {
                int bit = x * depth;
                int value = ((row[bit >> 3] & 0xFF) >> (8 - depth - (bit & 7))) & ((1 << depth) - 1);
                if (colour == 3) return paletteHeight(value);
                return value * 255 / ((1 << depth) - 1);
            }
            int step = depth / 8;
            int at = x * channels * step;
            int s0 = row[at] & 0xFF;
            return switch (colour) {
                case 0 -> s0;
                case 2 -> luminance(s0, row[at + step] & 0xFF, row[at + 2 * step] & 0xFF);
                case 3 -> paletteHeight(s0);
                case 4 -> s0 * (row[at + step] & 0xFF) / 255;
                default -> luminance(s0, row[at + step] & 0xFF, row[at + 2 * step] & 0xFF) * (row[at + 3 * step] & 0xFF) / 255;
            };
        }

        private int paletteHeight(int index) {
            if (3 * index + 2 >= palette.length) return 0;
            int value = luminance(palette[3 * index] & 0xFF, palette[3 * index + 1] & 0xFF, palette[3 * index + 2] & 0xFF);
            if (paletteAlpha != null && index < paletteAlpha.length) value = value * (paletteAlpha[index] & 0xFF) / 255;
            return value;
        }

        private static int luminance(int r, int g, int b) {
            return (299 * r + 587 * g + 114 * b + 500) / 1000;
        }
    }
}
