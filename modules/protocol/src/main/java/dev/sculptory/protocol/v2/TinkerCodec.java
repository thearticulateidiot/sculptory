package dev.sculptory.protocol.v2;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.tinker.EntityEdit;
import dev.sculptory.core.tinker.EntityEdits;
import dev.sculptory.core.tinker.SignText;
import java.util.ArrayList;
import java.util.List;

/**
 * The Tinker messages' bodies (protocol 5), after their leading request id:
 * <pre>
 * TinkerBlock  = palette | pos | varint expected | varint target | bool sign | [side front | side back]
 * side         = 4 × string line | u8 colour (SignText.COLORS index) | bool glowing
 * TinkerEntity = palette | uuid | varint n (≤ 32) | n × (u8 tag | fields)
 *   0 Pose: u8 part | f32 x, y, z          1 Toggle: u8 flag | bool       2 Position: f64 x, y, z
 *   3 Yaw: f32                              4 ItemRotation: u8             5 PaintingVariant: string id
 *   6 Transformation: 3 × f32 | 4 × f32 | 3 × f32                         7 Billboard: u8
 *   8 Brightness: zigzag block | zigzag sky                                 9 DisplayBlock: varint palette index
 *   10 DisplayItem: string id               11 DisplayText: string
 * TinkerResult = u8 outcome (0 done, 1 refused) | [varint reason | string detail] | bytes data (≤ 64 KiB)
 * </pre>
 * Floats travel as their IEEE bits ({@code f64} as the 8 bytes of {@link Double#doubleToRawLongBits}); values an edit's
 * constructor refuses (a NaN, an angle past ±360, a colour index out of range) make the frame {@code MALFORMED}, as do
 * unknown tags, parts and flags.
 */
final class TinkerCodec {
    /** A sign line's cap in bytes: {@link SignText#MAX_LINE_CHARS} characters of up to 3 UTF-8 bytes. */
    static final int LINE_BYTES = SignText.MAX_LINE_CHARS * 3;
    static final int ID_BYTES = 256;
    /** A display text's cap in bytes. */
    static final int TEXT_BYTES = EntityEdit.MAX_TEXT_CHARS * 3;
    static final int OUTCOME_DONE = 0;
    static final int OUTCOME_REFUSED = 1;

    private TinkerCodec() {}

    // ---------------------------------------------------------------- blocks

    static void writeBlock(WireWriter out, StatePalette.Builder palette, C2S.TinkerBlock m) throws ProtocolException {
        CoreCodec.writePos(out, m.pos());
        out.varint(palette.indexOf(m.expected()));
        out.varint(palette.indexOf(m.target()));
        out.bool(m.sign() != null);
        if (m.sign() != null) {
            writeSide(out, m.sign().front());
            writeSide(out, m.sign().back());
        }
    }

    static C2S.TinkerBlock readBlock(int reqId, WireReader in, StatePalette.Table palette) throws ProtocolException {
        BlockPos pos = CoreCodec.readPos(in);
        int expected = palette.readHandle(in);
        int target = palette.readHandle(in);
        SignText sign = in.bool() ? new SignText(readSide(in), readSide(in)) : null;
        return new C2S.TinkerBlock(reqId, pos, expected, target, sign);
    }

    private static void writeSide(WireWriter out, SignText.Side side) throws ProtocolException {
        for (String line : side.lines()) out.string(line, LINE_BYTES, "sign line");
        out.u8(SignText.COLORS.indexOf(side.color()));
        out.bool(side.glowing());
    }

    private static SignText.Side readSide(WireReader in) throws ProtocolException {
        List<String> lines = new ArrayList<>(SignText.LINES);
        for (int i = 0; i < SignText.LINES; i++) lines.add(in.string(LINE_BYTES, "sign line"));
        int color = in.u8();
        if (color >= SignText.COLORS.size()) throw WireReader.malformed("Unknown sign colour " + color);
        return new SignText.Side(lines, SignText.COLORS.get(color), in.bool());
    }

    // ---------------------------------------------------------------- entities

    static void writeEntity(WireWriter out, StatePalette.Builder palette, C2S.TinkerEntity m) throws ProtocolException {
        out.uuid(m.entity());
        out.count(m.edits().size(), EntityEdits.MAX_EDITS, "entity edits");
        for (EntityEdit edit : m.edits()) {
            out.u8(edit.tag());
            switch (edit) {
                case EntityEdit.Pose pose -> {
                    out.u8(pose.part().ordinal());
                    out.f32(pose.x());
                    out.f32(pose.y());
                    out.f32(pose.z());
                }
                case EntityEdit.Toggle toggle -> {
                    out.u8(toggle.flag().ordinal());
                    out.bool(toggle.on());
                }
                case EntityEdit.Position position -> {
                    out.i64(Double.doubleToRawLongBits(position.x()));
                    out.i64(Double.doubleToRawLongBits(position.y()));
                    out.i64(Double.doubleToRawLongBits(position.z()));
                }
                case EntityEdit.Yaw yaw -> out.f32(yaw.degrees());
                case EntityEdit.ItemRotation rotation -> out.u8(rotation.steps());
                case EntityEdit.PaintingVariant variant -> out.string(variant.id(), ID_BYTES, "painting variant");
                case EntityEdit.Transformation t -> {
                    for (float v : t.translation()) out.f32(v);
                    for (float v : t.rotation()) out.f32(v);
                    for (float v : t.scale()) out.f32(v);
                }
                case EntityEdit.BillboardMode billboard -> out.u8(billboard.mode().ordinal());
                case EntityEdit.Brightness brightness -> {
                    out.zigzag(brightness.block());
                    out.zigzag(brightness.sky());
                }
                case EntityEdit.DisplayBlock block -> out.varint(palette.indexOf(block.state()));
                case EntityEdit.DisplayItem item -> out.string(item.itemId(), ID_BYTES, "item id");
                case EntityEdit.DisplayText text -> out.string(text.text(), TEXT_BYTES, "display text");
            }
        }
    }

    static C2S.TinkerEntity readEntity(int reqId, WireReader in, StatePalette.Table palette) throws ProtocolException {
        java.util.UUID entity = in.uuid();
        int count = in.count(EntityEdits.MAX_EDITS, "entity edits");
        List<EntityEdit> edits = new ArrayList<>(count);
        for (int i = 0; i < count; i++) edits.add(readEdit(in, palette));
        return new C2S.TinkerEntity(reqId, entity, edits);
    }

    private static EntityEdit readEdit(WireReader in, StatePalette.Table palette) throws ProtocolException {
        int tag = in.u8();
        return switch (tag) {
            case 0 -> {
                EntityEdit.Part part = ordinal(EntityEdit.Part.values(), in.u8(), "pose part");
                yield new EntityEdit.Pose(part, in.f32(), in.f32(), in.f32());
            }
            case 1 -> {
                EntityEdit.Flag flag = ordinal(EntityEdit.Flag.values(), in.u8(), "entity flag");
                yield new EntityEdit.Toggle(flag, in.bool());
            }
            case 2 -> new EntityEdit.Position(Double.longBitsToDouble(in.i64()), Double.longBitsToDouble(in.i64()),
                    Double.longBitsToDouble(in.i64()));
            case 3 -> new EntityEdit.Yaw(in.f32());
            case 4 -> new EntityEdit.ItemRotation(in.u8());
            case 5 -> new EntityEdit.PaintingVariant(in.string(ID_BYTES, "painting variant"));
            case 6 -> {
                float[] translation = {in.f32(), in.f32(), in.f32()};
                float[] rotation = {in.f32(), in.f32(), in.f32(), in.f32()};
                float[] scale = {in.f32(), in.f32(), in.f32()};
                yield new EntityEdit.Transformation(translation, rotation, scale);
            }
            case 7 -> new EntityEdit.BillboardMode(ordinal(EntityEdit.Billboard.values(), in.u8(), "billboard"));
            case 8 -> {
                int block = in.zigzag();
                yield new EntityEdit.Brightness(block, in.zigzag());
            }
            case 9 -> new EntityEdit.DisplayBlock(palette.readHandle(in));
            case 10 -> new EntityEdit.DisplayItem(in.string(ID_BYTES, "item id"));
            case 11 -> new EntityEdit.DisplayText(in.string(TEXT_BYTES, "display text"));
            default -> throw WireReader.malformed("Unknown entity edit tag " + tag);
        };
    }

    private static <E extends Enum<E>> E ordinal(E[] values, int ordinal, String what) throws ProtocolException {
        if (ordinal >= values.length) throw WireReader.malformed("Unknown " + what + " " + ordinal);
        return values[ordinal];
    }

    // ---------------------------------------------------------------- results

    static void writeResult(WireWriter out, S2C.TinkerResult m) throws ProtocolException {
        out.zigzag(m.reqId());
        if (m.reason() == null) {
            out.u8(OUTCOME_DONE);
        } else {
            out.u8(OUTCOME_REFUSED);
            out.enumValue(m.reason());
            out.string(m.detail(), Codec.MAX_TEXT_BYTES, "tinker detail");
        }
        out.bytes(m.data(), S2C.TinkerResult.MAX_DATA_BYTES, "tinker data");
    }

    static S2C.TinkerResult readResult(WireReader in) throws ProtocolException {
        int reqId = in.zigzag();
        int outcome = in.u8();
        return switch (outcome) {
            case OUTCOME_DONE -> S2C.TinkerResult.done(reqId, in.bytes(S2C.TinkerResult.MAX_DATA_BYTES, "tinker data"));
            case OUTCOME_REFUSED -> {
                RejectReason reason = in.enumOf(RejectReason.values(), "reject reason");
                String detail = in.string(Codec.MAX_TEXT_BYTES, "tinker detail");
                yield new S2C.TinkerResult(reqId, reason, detail, in.bytes(S2C.TinkerResult.MAX_DATA_BYTES, "tinker data"));
            }
            default -> throw WireReader.malformed("Unknown tinker outcome " + outcome);
        };
    }
}
