package dev.sculptory.protocol.v2;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.scatter.ScatterPlan;
import dev.sculptory.core.scatter.ScatterSettings;
import dev.sculptory.core.transform.Mirror;
import dev.sculptory.core.transform.Transform;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The payload of a {@code SCATTER_PLACEMENTS} stream (format {@value #FORMAT_NAME}): a scatter plan's placements in
 * plan order, for the client's ghost preview. Sent after {@code ScatterPlan}; the stream's meta carries
 * {@value #META_FORMAT}, {@value #META_PLAN_ID}, {@value #META_REQ_ID} and {@value #META_PLACEMENTS}.
 *
 * <pre>
 * "BSSP" | u8 format=1 | varint count (0..131,072)
 *   | count × (zigzag dx, zigzag dy, zigzag dz | varint variant | u8 transform [| u8 height])
 * </pre>
 * <ul>
 *   <li>(dx, dy, dz) is the placement's anchor minus the previous placement's anchor; the first is relative to
 *       (0, 0, 0). The anchor is the cell the variant's anchor lands on (the cell above the surface).</li>
 *   <li>{@code variant} indexes the preview's variant list.</li>
 *   <li>{@code transform}: bits 0-1 clockwise quarter turns, bits 2-3 the mirror ordinal (as in {@code RunOp}); bit 4
 *       set when a column plant's height follows (2-{@value ScatterSettings#MAX_COLUMN_HEIGHT}; without it the
 *       placement is one block tall, so a plan without taller columns encodes as before); bits 5-7 are 0.</li>
 * </ul>
 * Decoding checks every count and value and rejects trailing bytes.
 */
public final class ScatterPlacements {
    public static final String FORMAT_NAME = "bssp1";
    public static final int FORMAT = 1;
    /** The most placements a payload holds: a plan's cap. */
    public static final int MAX_PLACEMENTS = ScatterSettings.MAX_PLACEMENTS;
    /**
     * A bound on an encoded placement: three 5-byte zigzags, the variant (under {@code ScatterSettings.MAX_VARIANTS}, so
     * one byte; two allowed here), the transform byte and a height byte.
     */
    static final int MAX_ENTRY_BYTES = 19;
    /** Bit of the transform byte saying a column height follows. */
    private static final int HAS_HEIGHT = 0x10;
    /** Smallest encoded placement. */
    static final int MIN_ENTRY_BYTES = 5;

    public static final String META_FORMAT = "format";
    public static final String META_PLAN_ID = "planId";
    public static final String META_REQ_ID = "reqId";
    public static final String META_PLACEMENTS = "placements";

    private static final byte[] MAGIC = "BSSP".getBytes(StandardCharsets.US_ASCII);

    private ScatterPlacements() {}

    /**
     * @throws IllegalArgumentException over {@value #MAX_PLACEMENTS} placements, or a variant index over
     *     {@code ScatterSettings.MAX_VARIANTS}
     */
    public static byte[] encode(List<ScatterPlan.Placement> placements) {
        Objects.requireNonNull(placements);
        if (placements.size() > MAX_PLACEMENTS) {
            throw new IllegalArgumentException(placements.size() + " placements over " + MAX_PLACEMENTS);
        }
        WireWriter out = new WireWriter(16 + placements.size() * MAX_ENTRY_BYTES);
        try {
            out.raw(MAGIC);
            out.u8(FORMAT);
            out.varint(placements.size());
            int x = 0, y = 0, z = 0;
            for (ScatterPlan.Placement p : placements) {
                if (p.variant() >= ScatterSettings.MAX_VARIANTS) throw new IllegalArgumentException("Variant " + p.variant());
                BlockPos a = p.anchor();
                // Wrapping int differences are fine: the decoder wraps the same way.
                out.zigzag(a.x() - x);
                out.zigzag(a.y() - y);
                out.zigzag(a.z() - z);
                out.varint(p.variant());
                int transform = p.transform().quarterTurnsCw() | (p.transform().mirror().ordinal() << 2);
                if (p.height() > 1) {
                    out.u8(transform | HAS_HEIGHT);
                    out.u8(p.height());
                } else {
                    out.u8(transform);
                }
                x = a.x();
                y = a.y();
                z = a.z();
            }
        } catch (ProtocolException impossible) {
            throw new IllegalStateException("The buffer is sized for the largest payload", impossible);
        }
        return out.toByteArray();
    }

    /**
     * @param variants the preview's variant count; every variant index must be below it
     * @throws ProtocolException for a wrong magic or format, a count over {@value #MAX_PLACEMENTS} or beyond the
     *     bytes present, a variant index out of range, an invalid transform, or trailing bytes
     */
    public static List<ScatterPlan.Placement> decode(byte[] payload, int variants) throws ProtocolException {
        Objects.requireNonNull(payload);
        WireReader in = new WireReader(payload);
        if (in.remaining() < MAGIC.length || !Arrays.equals(in.raw(MAGIC.length), MAGIC)) {
            throw WireReader.malformed("Not a scatter placements payload");
        }
        int format = in.u8();
        if (format != FORMAT) throw WireReader.malformed("Unknown scatter placements format " + format);
        int count = in.varint();
        if (count < 0 || count > MAX_PLACEMENTS) throw WireReader.tooLarge("Placement count " + count);
        if ((long) count * MIN_ENTRY_BYTES > in.remaining()) throw WireReader.malformed("Truncated placements");
        List<ScatterPlan.Placement> placements = new ArrayList<>(count);
        int x = 0, y = 0, z = 0;
        for (int i = 0; i < count; i++) {
            x += in.zigzag();
            y += in.zigzag();
            z += in.zigzag();
            int variant = in.varint();
            if (variant < 0 || variant >= variants) throw WireReader.malformed("Variant index " + variant);
            int b = in.u8();
            int mirror = (b >>> 2) & 3;
            if ((b & ~(HAS_HEIGHT | 0xF)) != 0 || mirror >= Mirror.values().length) {
                throw WireReader.malformed("Invalid placement transform byte " + b);
            }
            int height = 1;
            if ((b & HAS_HEIGHT) != 0) {
                height = in.u8();
                if (height < 2 || height > ScatterSettings.MAX_COLUMN_HEIGHT) {
                    throw WireReader.malformed("Column height " + height);
                }
            }
            placements.add(new ScatterPlan.Placement(new BlockPos(x, y, z), variant,
                    new Transform(b & 3, Mirror.values()[mirror]), height));
        }
        in.expectEnd();
        return placements;
    }
}
