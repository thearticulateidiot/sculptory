package dev.sculptory.fabric.schem;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.buffer.NbtBytes;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.nbt.BlockEntityNbt;
import dev.sculptory.core.nbt.NbtCompound;
import dev.sculptory.core.nbt.NbtList;
import dev.sculptory.core.nbt.NbtTag;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.state.StateSpace;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Removes operator-only content from block entities, for content that did not come from this server's world
 * (uploads, library files) and for content leaving it with a player who may not handle operator NBT (export,
 * saving to the library). Only tiles on {@code StateFlags.OPERATOR_NBT} states are touched:
 * <ul>
 *   <li><b>Signs and hanging signs</b> keep their text and nothing else, and become a {@link SanitizedTile} (safe for
 *       everyone to place). Signs are vanilla's two types and every type the state space names
 *       ({@link StateSpace#isSignBlockEntity}: on Fabric, modded signs built on vanilla's, such as Farmer's Delight
 *       canvas signs). The cell's state is not checked here: a sign tile on a command-block cell is reduced to text
 *       too, and {@code BlockWriter} refuses it when placing (the type does not fit the state), so the block keeps
 *       its default block entity:
 *       <ul>
 *         <li>only {@code is_waxed} and the two sides are kept, and of each side only {@code messages},
 *             {@code filtered_messages}, {@code color} and {@code has_glowing_text};</li>
 *         <li>every {@code clickEvent} (and {@code click_event}) is removed, recursively, from the messages' JSON
 *             text components;</li>
 *         <li>components that read server data ({@code nbt}, {@code selector}, {@code score}) become empty text;</li>
 *         <li>a message that cannot be parsed, or nests JSON deeper than {@value #MAX_JSON_DEPTH} levels, becomes
 *             empty text.</li>
 *       </ul></li>
 *   <li><b>Everything else</b> on an operator-NBT state (command, structure and jigsaw blocks, spawners, trial
 *       spawners, lecterns, and any tile whose type is not a sign) loses its NBT: the block keeps its default block
 *       entity.</li>
 * </ul>
 * {@link Result#signsCleaned()} counts only signs from which something was removed. Pure Java (no Minecraft
 * types); safe off the server thread.
 */
public final class TileSanitizer {
    private static final Set<String> SIGNS = Set.of("minecraft:sign", "minecraft:hanging_sign");
    private static final Set<String> CLICK_KEYS = Set.of("clickEvent", "click_event");
    private static final Set<String> DATA_KEYS = Set.of("nbt", "selector", "score");
    private static final List<String> SIDES = List.of("front_text", "back_text");
    private static final Set<String> MESSAGE_LISTS = Set.of("messages", "filtered_messages");
    private static final Set<String> SIDE_VALUES = Set.of("color", "has_glowing_text");
    private static final Gson GSON = new Gson();
    private static final String EMPTY_TEXT = "\"\"";
    /** Deepest JSON nesting (arrays and objects) a sign message may have; parsing and cleaning it recurse per level. */
    static final int MAX_JSON_DEPTH = 64;

    private TileSanitizer() {}

    /**
     * What sanitizing changed.
     *
     * @param dropped operator-only block entities whose NBT was removed
     * @param signsCleaned signs that had click events removed
     */
    public record Result(Clipboard clipboard, int dropped, int signsCleaned) {
        public Result {
            Objects.requireNonNull(clipboard);
        }

        public boolean changed() {
            return dropped > 0 || signsCleaned > 0;
        }
    }

    /** The clipboard with operator-only content removed (the same instance when nothing needed changing). */
    public static Result sanitize(Clipboard clipboard) {
        StateSpace states = clipboard.states();
        BlockBuffer blocks = clipboard.copyBlocks();
        int[] counts = {0, 0};
        boolean[] replaced = {false};
        for (long key : blocks.sortedKeys()) {
            SectionBuffer section = blocks.section(key);
            section.forEachTile((i, tile) -> {
                if (!StateFlags.has(states.flags(section.get(i)), StateFlags.OPERATOR_NBT)) return;
                if (tile instanceof SanitizedTile) return;
                replaced[0] = true;
                if (isSign(states, tile.typeId())) {
                    CleanSign clean = cleanSign(tile);
                    section.setTile(i, clean.tile());
                    if (clean.changed()) counts[1]++;
                } else {
                    section.setTile(i, null);
                    counts[0]++;
                }
            });
        }
        if (!replaced[0]) return new Result(clipboard, 0, 0);
        return new Result(rebuild(clipboard, blocks), counts[0], counts[1]);
    }

    /** Whether a tile of type {@code typeId} is a sign: vanilla's types, or a sign type the state space names. */
    static boolean isSign(StateSpace states, String typeId) {
        return SIGNS.contains(typeId) || states.isSignBlockEntity(typeId);
    }

    /** A sign reduced to text, and whether anything was removed from it. */
    record CleanSign(SanitizedTile tile, boolean changed) {}

    /**
     * Sign content that was a {@link SanitizedTile} when the undo history recorded it, read back from the history
     * journal: cleaned again (which changes nothing in text already cleaned), so it is placed for everyone as before.
     */
    public static SanitizedTile restoredSign(String typeId, byte[] nbtBytes) {
        return cleanSign(new NbtBytes(typeId, nbtBytes)).tile();
    }

    /**
     * A sign whose text a player set with Tinker, cleaned as signs from files are:
     * only the sign fields, no click events, no components reading server data, JSON nesting capped. Its lines are
     * literal text already; the sides the player left keep only their text.
     */
    public static SanitizedTile sanitizedSign(String typeId, byte[] nbtBytes) {
        return cleanSign(new NbtBytes(typeId, nbtBytes)).tile();
    }

    /** A sign tile reduced to text; unreadable NBT becomes a sign without text. */
    static CleanSign cleanSign(BlockEntityData tile) {
        NbtCompound nbt;
        try {
            nbt = BlockEntityNbt.decode(tile);
        } catch (IOException e) {
            return new CleanSign(sanitized(tile.typeId(), NbtCompound.EMPTY), true);
        }
        NbtCompound original = BlockEntityNbt.normalize(tile.typeId(), nbt);
        NbtCompound cleaned = BlockEntityNbt.normalize(tile.typeId(), cleanSignNbt(nbt));
        return new CleanSign(sanitized(tile.typeId(), cleaned), !cleaned.equals(original));
    }

    private static SanitizedTile sanitized(String typeId, NbtCompound nbt) {
        return new SanitizedTile(typeId, BlockEntityNbt.toNbtBytes(typeId, nbt).nbtBytes());
    }

    /** The sign fields only, with click events and data-reading components removed from both sides' messages. */
    static NbtCompound cleanSignNbt(NbtCompound nbt) {
        NbtCompound.Builder out = NbtCompound.builder();
        NbtTag waxed = nbt.get("is_waxed");
        if (waxed instanceof NbtTag.NbtByte) out.put("is_waxed", waxed);
        for (String side : SIDES) {
            NbtCompound text = nbt.getCompound(side);
            if (text == null) continue;
            NbtCompound.Builder cleanedSide = NbtCompound.builder();
            text.entries().forEach((key, value) -> {
                if (SIDE_VALUES.contains(key) && !(value instanceof NbtCompound) && !(value instanceof NbtList)) {
                    cleanedSide.put(key, value);
                } else if (MESSAGE_LISTS.contains(key) && value instanceof NbtList list) {
                    List<NbtTag> items = new ArrayList<>(list.size());
                    for (NbtTag item : list.items()) items.add(cleanComponent(item));
                    cleanedSide.put(key, NbtList.of(list.elementType(), items));
                }
            });
            out.put(side, cleanedSide.build());
        }
        return out.build();
    }

    /** One text component: a JSON string (1.21.1) or an NBT component (later formats). */
    private static NbtTag cleanComponent(NbtTag item) {
        return switch (item) {
            case NbtTag.NbtString s -> new NbtTag.NbtString(cleanJson(s.value()));
            case NbtCompound c -> stripKeys(c);
            case NbtList l -> {
                List<NbtTag> items = new ArrayList<>(l.size());
                for (NbtTag inner : l.items()) items.add(cleanComponent(inner));
                yield NbtList.of(l.elementType(), items);
            }
            default -> item;
        };
    }

    /**
     * JSON text without click events or data-reading components; unparseable text, and text nesting deeper than
     * {@value #MAX_JSON_DEPTH} levels, becomes empty text.
     */
    static String cleanJson(String json) {
        if (nestsDeeperThan(json, MAX_JSON_DEPTH)) return EMPTY_TEXT;
        JsonElement element;
        try {
            element = JsonParser.parseString(json);
        } catch (JsonParseException | IllegalStateException e) {
            return EMPTY_TEXT;
        }
        boolean[] removed = {false};
        JsonElement cleaned = strip(element, removed);
        return removed[0] ? GSON.toJson(cleaned) : json;
    }

    /**
     * Whether JSON text nests arrays and objects deeper than {@code limit}, read with Gson's own (non-recursive)
     * tokenizer, as leniently as {@link JsonParser#parseString} reads it, so strings and comments hide the same
     * brackets from both. Text the tokenizer rejects before reaching the limit is left to the parser to refuse.
     */
    static boolean nestsDeeperThan(String json, int limit) {
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setLenient(true);
            int depth = 0;
            while (true) {
                switch (reader.peek()) {
                    case BEGIN_ARRAY -> {
                        reader.beginArray();
                        if (++depth > limit) return true;
                    }
                    case BEGIN_OBJECT -> {
                        reader.beginObject();
                        if (++depth > limit) return true;
                    }
                    case END_ARRAY -> {
                        reader.endArray();
                        depth--;
                    }
                    case END_OBJECT -> {
                        reader.endObject();
                        depth--;
                    }
                    case NAME -> reader.nextName();
                    case END_DOCUMENT -> {
                        return false;
                    }
                    default -> reader.skipValue();
                }
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * The element without click events (edited in place); an object reading server data ({@code nbt},
     * {@code selector}, {@code score}) is replaced by empty text. Sets {@code removed[0]} when anything went.
     */
    private static JsonElement strip(JsonElement element, boolean[] removed) {
        if (element instanceof JsonObject object) {
            for (String key : DATA_KEYS) {
                if (object.has(key)) {
                    removed[0] = true;
                    JsonObject empty = new JsonObject();
                    empty.addProperty("text", "");
                    return empty;
                }
            }
            for (String key : CLICK_KEYS) {
                if (object.remove(key) != null) removed[0] = true;
            }
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) entry.setValue(strip(entry.getValue(), removed));
        } else if (element instanceof JsonArray array) {
            for (int i = 0; i < array.size(); i++) array.set(i, strip(array.get(i), removed));
        }
        return element;
    }

    private static NbtCompound stripKeys(NbtCompound compound) {
        for (String key : DATA_KEYS) {
            if (compound.contains(key)) return NbtCompound.builder().putString("text", "").build();
        }
        NbtCompound.Builder out = NbtCompound.builder();
        compound.entries().forEach((key, value) -> {
            if (!CLICK_KEYS.contains(key)) out.put(key, cleanComponent(value));
        });
        return out.build();
    }

    private static Clipboard rebuild(Clipboard original, BlockBuffer blocks) {
        Clipboard.Builder builder = Clipboard.builder(original.states(), original.size())
                .anchor(original.anchor()).source(original.source());
        for (long key : blocks.sortedKeys()) {
            SectionBuffer section = blocks.section(key);
            int ox = BlockBuffer.keyX(key) << 4, oy = BlockBuffer.keyY(key) << 4, oz = BlockBuffer.keyZ(key) << 4;
            section.forEachPresent(i -> builder.set(ox + SectionBuffer.localX(i), oy + SectionBuffer.localY(i),
                    oz + SectionBuffer.localZ(i), section.get(i)));
            section.forEachTile((i, tile) -> builder.setTile(ox + SectionBuffer.localX(i), oy + SectionBuffer.localY(i),
                    oz + SectionBuffer.localZ(i), tile));
        }
        // Entities are EntitySanitizer's business: they go along as they are.
        original.entities().forEach(builder::addEntity);
        return builder.build();
    }
}
