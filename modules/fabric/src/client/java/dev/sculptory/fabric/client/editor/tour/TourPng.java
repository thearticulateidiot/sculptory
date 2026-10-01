package dev.sculptory.fabric.client.editor.tour;

import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Writes a wiki picture as a small PNG: at most 256 colours (a palette PNG, a quarter of the size of a true-colour one)
 * and the strongest compression. A picture of the editor's windows has fewer colours than that and stays exact; one of
 * the world is reduced by median cut, which keeps it well within the 300 KB a wiki picture may take. Pure Java (no
 * Minecraft), so it can be tested.
 */
public final class TourPng {
    /** The most colours a palette PNG holds. */
    public static final int MAX_COLOURS = 256;

    private TourPng() {}

    /**
     * The PNG of a {@code width} × {@code height} picture whose pixels are {@code rgb} (0xRRGGBB, row by row, alpha
     * ignored).
     */
    public static byte[] encode(int[] rgb, int width, int height) {
        if (width < 1 || height < 1 || rgb.length != width * height) {
            throw new IllegalArgumentException("A " + width + "x" + height + " picture needs " + width * height
                    + " pixels, got " + rgb.length);
        }
        int[] palette = palette(rgb, MAX_COLOURS);
        Map<Integer, Integer> indexOf = nearest(rgb, palette);
        byte[] r = new byte[palette.length];
        byte[] g = new byte[palette.length];
        byte[] b = new byte[palette.length];
        for (int i = 0; i < palette.length; i++) {
            r[i] = (byte) (palette[i] >> 16);
            g[i] = (byte) (palette[i] >> 8);
            b[i] = (byte) palette[i];
        }
        int bits = palette.length <= 2 ? 1 : palette.length <= 4 ? 2 : palette.length <= 16 ? 4 : 8;
        IndexColorModel model = new IndexColorModel(bits, palette.length, r, g, b);
        BufferedImage image = new BufferedImage(width, height,
                bits == 8 ? BufferedImage.TYPE_BYTE_INDEXED : BufferedImage.TYPE_BYTE_BINARY, model);
        var raster = image.getRaster();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                raster.setSample(x, y, 0, indexOf.get(rgb[y * width + x] & 0xFFFFFF));
            }
        }
        return write(image);
    }

    /**
     * At most {@code max} colours standing for the picture's: its own colours when it has no more, otherwise the mean
     * colours of the boxes a median cut of its colours (weighted by how many pixels have each) splits into.
     */
    static int[] palette(int[] rgb, int max) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (int pixel : rgb) {
            counts.merge(pixel & 0xFFFFFF, 1, Integer::sum);
        }
        if (counts.size() <= max) {
            return counts.keySet().stream().mapToInt(Integer::intValue).sorted().toArray();
        }
        List<Box> boxes = new ArrayList<>();
        List<int[]> all = new ArrayList<>(counts.size());
        counts.forEach((colour, count) -> all.add(new int[] {colour, count}));
        boxes.add(new Box(all));
        while (boxes.size() < max) {
            Box widest = boxes.stream().filter(box -> box.colours.size() > 1)
                    .max(Comparator.comparingLong(Box::weight)).orElse(null);
            if (widest == null) {
                break;
            }
            boxes.remove(widest);
            boxes.addAll(widest.split());
        }
        return boxes.stream().mapToInt(Box::mean).distinct().toArray();
    }

    /** For each colour of the picture, the index of the nearest palette colour. */
    private static Map<Integer, Integer> nearest(int[] rgb, int[] palette) {
        Map<Integer, Integer> indexOf = new HashMap<>();
        for (int pixel : rgb) {
            int colour = pixel & 0xFFFFFF;
            if (indexOf.containsKey(colour)) {
                continue;
            }
            int best = 0;
            long bestDistance = Long.MAX_VALUE;
            for (int i = 0; i < palette.length; i++) {
                long distance = distance(colour, palette[i]);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = i;
                }
            }
            indexOf.put(colour, best);
        }
        return indexOf;
    }

    private static long distance(int a, int b) {
        int dr = ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
        int dg = ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF);
        int db = (a & 0xFF) - (b & 0xFF);
        // Green counts most, blue least, as the eye sees them.
        return 3L * dr * dr + 4L * dg * dg + 2L * db * db;
    }

    private static byte[] write(BufferedImage image) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("png").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(0.0f); // the strongest deflate
            }
            writer.write(null, new IIOImage(image, null, null), param);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    /** Colours with their pixel counts, split along the channel with the widest range at the weighted median. */
    private static final class Box {
        private final List<int[]> colours;
        private final int channel;
        private final int range;
        private final long pixels;

        Box(List<int[]> colours) {
            this.colours = colours;
            int bestChannel = 0;
            int bestRange = -1;
            for (int c = 0; c < 3; c++) {
                int shift = 16 - 8 * c;
                int low = 255;
                int high = 0;
                for (int[] entry : colours) {
                    int value = (entry[0] >> shift) & 0xFF;
                    low = Math.min(low, value);
                    high = Math.max(high, value);
                }
                if (high - low > bestRange) {
                    bestRange = high - low;
                    bestChannel = c;
                }
            }
            this.channel = bestChannel;
            this.range = bestRange;
            long total = 0;
            for (int[] entry : colours) {
                total += entry[1];
            }
            this.pixels = total;
        }

        /** Which box to split next: wide boxes of many pixels first. */
        long weight() {
            return range * (long) Math.sqrt(pixels);
        }

        List<Box> split() {
            int shift = 16 - 8 * channel;
            colours.sort(Comparator.comparingInt(entry -> (entry[0] >> shift) & 0xFF));
            long half = pixels / 2;
            long seen = 0;
            int cut = 1;
            for (int i = 0; i < colours.size() - 1; i++) {
                seen += colours.get(i)[1];
                cut = i + 1;
                if (seen >= half) {
                    break;
                }
            }
            return List.of(new Box(new ArrayList<>(colours.subList(0, cut))),
                    new Box(new ArrayList<>(colours.subList(cut, colours.size()))));
        }

        int mean() {
            long r = 0;
            long g = 0;
            long b = 0;
            for (int[] entry : colours) {
                r += (long) ((entry[0] >> 16) & 0xFF) * entry[1];
                g += (long) ((entry[0] >> 8) & 0xFF) * entry[1];
                b += (long) (entry[0] & 0xFF) * entry[1];
            }
            return (int) (r / pixels) << 16 | (int) (g / pixels) << 8 | (int) (b / pixels);
        }
    }
}
