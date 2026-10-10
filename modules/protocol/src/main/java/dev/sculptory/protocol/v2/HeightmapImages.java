package dev.sculptory.protocol.v2;

import dev.sculptory.core.brush.BrushSpec;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * The Heightmap brush's image on the wire (protocol 6, inside {@code StrokeBegin}). An image up to
 * {@value #RAW_SIDE} pixels a side (a radius-32 stamp, 4,225 bytes) travels raw. A wider one (up to 257 × 257 = 66,049
 * bytes, more than a client frame holds) travels deflated: each row's pixels as differences from the pixel to their
 * left (the first as it is), mod 256, then zlib-deflated. Smooth pictures shrink well (the built-in ones to 2-16 KB at
 * 257 × 257); a picture too noisy to fit a frame is refused by the client before it sends anything.
 *
 * <p>Reading inflates into exactly {@code side²} bytes and refuses (MALFORMED) data that is corrupt (a bad stream or
 * checksum), ends early, would inflate further, or has bytes after the stream: nothing larger than the image is ever
 * allocated, whatever the frame holds.
 */
final class HeightmapImages {
    /** Images up to this side travel raw: the largest stamp of every brush but the Heightmap brush. */
    static final int RAW_SIDE = 2 * BrushSpec.MAX_RADIUS + 1;

    private HeightmapImages() {}

    /** {@code heights} ({@code side × side}, row by row) as the wire's deflated bytes. */
    static byte[] deflate(byte[] heights, int side) {
        byte[] filtered = new byte[heights.length];
        for (int v = 0; v < side; v++) {
            int row = v * side;
            filtered[row] = heights[row];
            for (int u = 1; u < side; u++) filtered[row + u] = (byte) (heights[row + u] - heights[row + u - 1]);
        }
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            deflater.setInput(filtered);
            deflater.finish();
            byte[] buffer = new byte[filtered.length + 64];
            int n = 0;
            while (!deflater.finished()) {
                if (n == buffer.length) buffer = Arrays.copyOf(buffer, buffer.length * 2);
                n += deflater.deflate(buffer, n, buffer.length - n);
            }
            return Arrays.copyOf(buffer, n);
        } finally {
            deflater.end();
        }
    }

    /**
     * The {@code side × side} image the wire's {@code data} holds.
     *
     * @throws ProtocolException (MALFORMED) for corrupt, short, long or trailing data
     */
    static byte[] inflate(byte[] data, int side) throws ProtocolException {
        byte[] filtered = new byte[side * side];
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(data);
            int n = 0;
            while (n < filtered.length) {
                int got = inflater.inflate(filtered, n, filtered.length - n);
                if (got == 0) {
                    throw WireReader.malformed("Heightmap image ends after " + n + " of " + filtered.length + " bytes");
                }
                n += got;
            }
            if (!inflater.finished()) {
                // The image is whole: the stream may only end (its checksum) here, with nothing more to inflate.
                if (inflater.inflate(new byte[1]) != 0) throw WireReader.malformed("Heightmap image over " + filtered.length + " bytes");
                if (!inflater.finished()) throw WireReader.malformed("Heightmap image stream not ended");
            }
            if (inflater.getRemaining() != 0) throw WireReader.malformed("Bytes after the heightmap image");
        } catch (DataFormatException e) {
            throw WireReader.malformed("Corrupt heightmap image: " + e.getMessage());
        } finally {
            inflater.end();
        }
        byte[] heights = filtered;
        for (int v = 0; v < side; v++) {
            int row = v * side;
            for (int u = 1; u < side; u++) heights[row + u] = (byte) (heights[row + u] + heights[row + u - 1]);
        }
        return heights;
    }
}
