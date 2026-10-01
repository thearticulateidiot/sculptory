package dev.sculptory.core.tinker;

import dev.sculptory.core.NamespacedId;
import java.util.Arrays;
import java.util.Objects;

/**
 * One change Tinker makes to an entity. Every value is absolute (the panel sends
 * what it shows, not a difference), and {@link EntityEdits#apply} writes it into the entity's NBT; which kinds take
 * which edit is {@link #appliesTo}. Constructors check ranges, so a decoded edit is always a valid one; the wire tags are
 * {@link #tag()} (append-only).
 */
public sealed interface EntityEdit {
    /** Largest translation along an axis, and largest scale, a display may be given. */
    float MAX_DISPLAY_OFFSET = 64f;
    /** Longest display text, in characters, and most lines. */
    int MAX_TEXT_CHARS = 1024;
    int MAX_TEXT_LINES = 16;

    /** The wire tag. */
    int tag();

    /** Whether entities of {@code kind} take this edit. */
    boolean appliesTo(TinkerKind kind);

    /** An armor stand's limbs and head, as its {@code Pose} compound names them. */
    enum Part {
        HEAD("Head", 0, 0, 0),
        BODY("Body", 0, 0, 0),
        LEFT_ARM("LeftArm", -10, 0, -10),
        RIGHT_ARM("RightArm", -15, 0, 10),
        LEFT_LEG("LeftLeg", -1, 0, -1),
        RIGHT_LEG("RightLeg", 1, 0, 1);

        private final String nbtKey;
        private final float[] standard;

        Part(String nbtKey, float x, float y, float z) {
            this.nbtKey = nbtKey;
            this.standard = new float[] {x, y, z};
        }

        public String nbtKey() {
            return nbtKey;
        }

        /** Vanilla's angles for the part when its pose leaves it out ({@code ArmorStandEntity.DEFAULT_*_ROTATION}). */
        public float[] standard() {
            return standard.clone();
        }
    }

    /** A yes/no setting, as the entity's NBT names it. */
    enum Flag {
        SMALL("Small"),
        SHOW_ARMS("ShowArms"),
        NO_BASE_PLATE("NoBasePlate"),
        INVISIBLE("Invisible"),
        NO_GRAVITY("NoGravity"),
        FIXED("Fixed");

        private final String nbtKey;

        Flag(String nbtKey) {
            this.nbtKey = nbtKey;
        }

        public String nbtKey() {
            return nbtKey;
        }

        /** Armor stands take every flag but Fixed; item frames Invisible and Fixed. */
        public boolean appliesTo(TinkerKind kind) {
            return switch (this) {
                case SMALL, SHOW_ARMS, NO_BASE_PLATE, NO_GRAVITY -> kind == TinkerKind.ARMOR_STAND;
                case INVISIBLE -> kind == TinkerKind.ARMOR_STAND || kind.itemFrame();
                case FIXED -> kind.itemFrame();
            };
        }
    }

    /** How a display turns to the camera ({@code billboard}). */
    enum Billboard {
        FIXED, VERTICAL, HORIZONTAL, CENTER;

        public String nbtName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        /** The mode NBT names {@code name}, or {@link #FIXED} (vanilla's default) for anything else. */
        public static Billboard of(String name) {
            for (Billboard mode : values()) {
                if (mode.nbtName().equals(name)) return mode;
            }
            return FIXED;
        }
    }

    /** An armor stand part's three angles, in degrees (each within ±360). */
    record Pose(Part part, float x, float y, float z) implements EntityEdit {
        public Pose {
            Objects.requireNonNull(part);
            angle(x);
            angle(y);
            angle(z);
        }

        @Override
        public int tag() {
            return 0;
        }

        @Override
        public boolean appliesTo(TinkerKind kind) {
            return kind == TinkerKind.ARMOR_STAND;
        }
    }

    record Toggle(Flag flag, boolean on) implements EntityEdit {
        public Toggle {
            Objects.requireNonNull(flag);
        }

        @Override
        public int tag() {
            return 1;
        }

        @Override
        public boolean appliesTo(TinkerKind kind) {
            return flag.appliesTo(kind);
        }
    }

    /**
     * Where the entity stands (its {@code Pos}). A hanging entity moves by whole blocks only (its block moves with it);
     * a move of more than {@link EntityEdits#MAX_MOVE} blocks from where it is is refused.
     */
    record Position(double x, double y, double z) implements EntityEdit {
        public Position {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("Position is not finite");
            }
        }

        @Override
        public int tag() {
            return 2;
        }

        @Override
        public boolean appliesTo(TinkerKind kind) {
            return true;
        }
    }

    /** Which way the entity faces, in degrees (armor stands and displays). */
    record Yaw(float degrees) implements EntityEdit {
        public Yaw {
            if (!Float.isFinite(degrees)) throw new IllegalArgumentException("Yaw is not finite");
        }

        @Override
        public int tag() {
            return 3;
        }

        @Override
        public boolean appliesTo(TinkerKind kind) {
            return kind.turns();
        }
    }

    /** How far an item frame's item is turned, in eighths of a turn (0-7). */
    record ItemRotation(int steps) implements EntityEdit {
        public ItemRotation {
            if (steps < 0 || steps > 7) throw new IllegalArgumentException("Item rotation " + steps + " is not 0-7");
        }

        @Override
        public int tag() {
            return 4;
        }

        @Override
        public boolean appliesTo(TinkerKind kind) {
            return kind.itemFrame();
        }
    }

    /** A painting's picture, by its registry id (the server checks it is one). */
    record PaintingVariant(String id) implements EntityEdit {
        public PaintingVariant {
            new NamespacedId(Objects.requireNonNull(id));
        }

        @Override
        public int tag() {
            return 5;
        }

        @Override
        public boolean appliesTo(TinkerKind kind) {
            return kind == TinkerKind.PAINTING;
        }
    }

    /**
     * A display's transformation: {@code translation} (x, y, z, each within ±{@value #MAX_DISPLAY_OFFSET}), its left
     * rotation as a quaternion (x, y, z, w; normalized when written) and {@code scale} (each within
     * ±{@value #MAX_DISPLAY_OFFSET}). The right rotation is kept as it is. Arrays are copied in and out.
     */
    record Transformation(float[] translation, float[] rotation, float[] scale) implements EntityEdit {
        public Transformation {
            translation = bounded(translation, 3, "translation");
            rotation = finite(rotation, 4, "rotation");
            scale = bounded(scale, 3, "scale");
            double norm = 0;
            for (float c : rotation) norm += (double) c * c;
            if (norm < 1e-12) throw new IllegalArgumentException("A zero rotation quaternion");
        }

        @Override
        public float[] translation() {
            return translation.clone();
        }

        @Override
        public float[] rotation() {
            return rotation.clone();
        }

        @Override
        public float[] scale() {
            return scale.clone();
        }

        @Override
        public int tag() {
            return 6;
        }

        @Override
        public boolean appliesTo(TinkerKind kind) {
            return kind.display();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Transformation other && Arrays.equals(translation, other.translation)
                    && Arrays.equals(rotation, other.rotation) && Arrays.equals(scale, other.scale);
        }

        @Override
        public int hashCode() {
            return Objects.hash(Arrays.hashCode(translation), Arrays.hashCode(rotation), Arrays.hashCode(scale));
        }

        @Override
        public String toString() {
            return "Transformation[translation=" + Arrays.toString(translation) + ", rotation="
                    + Arrays.toString(rotation) + ", scale=" + Arrays.toString(scale) + "]";
        }

        private static float[] finite(float[] values, int length, String what) {
            Objects.requireNonNull(values);
            if (values.length != length) throw new IllegalArgumentException(what + " needs " + length + " values");
            for (float v : values) {
                if (!Float.isFinite(v)) throw new IllegalArgumentException(what + " is not finite");
            }
            return values.clone();
        }

        private static float[] bounded(float[] values, int length, String what) {
            float[] copy = finite(values, length, what);
            for (float v : copy) {
                if (Math.abs(v) > MAX_DISPLAY_OFFSET) {
                    throw new IllegalArgumentException(what + " " + v + " is over " + MAX_DISPLAY_OFFSET);
                }
            }
            return copy;
        }
    }

    record BillboardMode(Billboard mode) implements EntityEdit {
        public BillboardMode {
            Objects.requireNonNull(mode);
        }

        @Override
        public int tag() {
            return 7;
        }

        @Override
        public boolean appliesTo(TinkerKind kind) {
            return kind.display();
        }
    }

    /**
     * A display's light: block and sky light 0-15, or both -1 for the light where it stands (vanilla's default, no
     * {@code brightness} key).
     */
    record Brightness(int block, int sky) implements EntityEdit {
        public static final Brightness AUTO = new Brightness(-1, -1);

        public Brightness {
            boolean auto = block == -1 && sky == -1;
            if (!auto && (block < 0 || block > 15 || sky < 0 || sky > 15)) {
                throw new IllegalArgumentException("Brightness " + block + "/" + sky + " is not 0-15 (or both -1)");
            }
        }

        public boolean auto() {
            return block < 0;
        }

        @Override
        public int tag() {
            return 8;
        }

        @Override
        public boolean appliesTo(TinkerKind kind) {
            return kind.display();
        }
    }

    /** What a block display shows: a block state (a handle of the state space it was read with). */
    record DisplayBlock(int state) implements EntityEdit {
        public DisplayBlock {
            if (state < 0) throw new IllegalArgumentException("Negative state handle");
        }

        @Override
        public int tag() {
            return 9;
        }

        @Override
        public boolean appliesTo(TinkerKind kind) {
            return kind == TinkerKind.BLOCK_DISPLAY;
        }
    }

    /** What an item display shows: one item, by its registry id (the server checks it is one, and not air). */
    record DisplayItem(String itemId) implements EntityEdit {
        public DisplayItem {
            new NamespacedId(Objects.requireNonNull(itemId));
        }

        @Override
        public int tag() {
            return 10;
        }

        @Override
        public boolean appliesTo(TinkerKind kind) {
            return kind == TinkerKind.ITEM_DISPLAY;
        }
    }

    /**
     * What a text display shows: plain text, lines separated by {@code \n}, each line {@link SignText#clean cleaned}; at
     * most {@value #MAX_TEXT_LINES} lines and {@value #MAX_TEXT_CHARS} characters.
     */
    record DisplayText(String text) implements EntityEdit {
        public DisplayText {
            Objects.requireNonNull(text);
            String[] lines = text.split("\n", -1);
            if (lines.length > MAX_TEXT_LINES) throw new IllegalArgumentException("More than " + MAX_TEXT_LINES + " lines");
            StringBuilder cleaned = new StringBuilder();
            for (int i = 0; i < lines.length; i++) {
                if (i > 0) cleaned.append('\n');
                cleaned.append(SignText.clean(lines[i]));
            }
            if (cleaned.length() > MAX_TEXT_CHARS) {
                throw new IllegalArgumentException("Display text over " + MAX_TEXT_CHARS + " characters");
            }
            text = cleaned.toString();
        }

        @Override
        public int tag() {
            return 11;
        }

        @Override
        public boolean appliesTo(TinkerKind kind) {
            return kind == TinkerKind.TEXT_DISPLAY;
        }
    }

    private static void angle(float degrees) {
        if (!Float.isFinite(degrees) || Math.abs(degrees) > 360f) {
            throw new IllegalArgumentException("Angle " + degrees + " is not within ±360");
        }
    }
}
