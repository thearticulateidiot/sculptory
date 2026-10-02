package dev.sculptory.server.schem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.BlockPos;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.nbt.BlockEntityNbt;
import dev.sculptory.core.nbt.NbtCompound;
import dev.sculptory.core.nbt.NbtList;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.transform.Mirror;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class TileSanitizerTest {
    private static final FakeStateSpace FAKE = new FakeStateSpace();
    /** "testmod:widget[facing=up]" plays a command block, "[facing=down]" a sign: operator NBT. Chests are not. */
    private static final int COMMAND = FAKE.state("testmod:widget[facing=up]");
    private static final int SIGN = FAKE.state("testmod:widget[facing=down]");
    private static final int CHEST = FAKE.state("minecraft:chest");
    private static final StateSpace STATES = new OperatorStates();

    private static String json(String text, boolean click) {
        return "{\"text\":\"" + text + "\"" + (click ? ",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/op @a\"}" : "")
                + ",\"extra\":[{\"text\":\"!\"" + (click ? ",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/stop\"}" : "")
                + "}]}";
    }

    private static NbtCompound side(boolean click) {
        return NbtCompound.builder()
                .put("messages", NbtList.ofStrings(List.of(json("Hi", click), "\"\"", "\"\"", "\"\"")))
                .put("filtered_messages", NbtList.ofStrings(List.of(json("Hi", click), "\"\"", "\"\"", "\"\"")))
                .putString("color", "black")
                .build();
    }

    private static BlockEntityData sign(boolean click) {
        return BlockEntityNbt.toNbtBytes("minecraft:sign", NbtCompound.builder()
                .put("front_text", side(click)).put("back_text", side(click)).putByte("is_waxed", (byte) 0).build());
    }

    @Test
    void operatorTilesLoseTheirNbtAndSignsTheirClickEvents() throws IOException {
        Clipboard.Builder builder = Clipboard.builder(STATES, new BlockPos(4, 1, 1)).anchor(new BlockPos(1, 0, 0));
        builder.set(0, 0, 0, COMMAND);
        builder.setTile(0, 0, 0, BlockEntityNbt.toNbtBytes("minecraft:command_block",
                NbtCompound.builder().putString("Command", "op everyone").putByte("auto", (byte) 1).build()));
        builder.set(1, 0, 0, SIGN);
        builder.setTile(1, 0, 0, sign(true));
        builder.set(2, 0, 0, SIGN);
        builder.setTile(2, 0, 0, sign(false));
        builder.set(3, 0, 0, CHEST);
        BlockEntityData chest = BlockEntityNbt.toNbtBytes("minecraft:chest",
                NbtCompound.builder().putString("Lock", "keep me").build());
        builder.setTile(3, 0, 0, chest);
        Clipboard original = builder.build();

        TileSanitizer.Result result = TileSanitizer.sanitize(original);
        assertEquals(1, result.dropped());
        assertEquals(1, result.signsCleaned());
        Clipboard clean = result.clipboard();
        assertEquals(original.size(), clean.size());
        assertEquals(original.anchor(), clean.anchor());
        assertEquals(COMMAND, clean.get(0, 0, 0), "the block stays");
        assertNull(clean.tile(0, 0, 0), "the command block's NBT is gone");
        NbtCompound cleaned = BlockEntityNbt.decode(clean.tile(1, 0, 0));
        for (String sideKey : List.of("front_text", "back_text")) {
            for (String list : List.of("messages", "filtered_messages")) {
                String first = cleaned.getCompound(sideKey).getList(list).strings().get(0);
                assertFalse(first.contains("clickEvent"), first);
                assertTrue(first.contains("\"text\":\"Hi\"") && first.contains("\"text\":\"!\""), "text kept: " + first);
            }
            assertEquals("black", cleaned.getCompound(sideKey).getString("color"));
        }
        assertTrue(clean.tile(1, 0, 0) instanceof SanitizedTile, "a cleaned sign is safe to place");
        assertTrue(clean.tile(2, 0, 0) instanceof SanitizedTile, "a clean sign is marked safe too");
        assertEquals(BlockEntityNbt.decode(original.tile(2, 0, 0)), BlockEntityNbt.decode(clean.tile(2, 0, 0)),
                "a sign without click events keeps its content");
        assertEquals(BlockEntityNbt.decode(chest), BlockEntityNbt.decode(clean.tile(3, 0, 0)), "other tiles untouched");
        assertSame(chest, clean.tile(3, 0, 0));
        // Sanitizing again changes nothing.
        TileSanitizer.Result again = TileSanitizer.sanitize(clean);
        assertSame(clean, again.clipboard());
        assertFalse(again.changed());
    }

    @Test
    void cleanSignsAreMarkedSafeWithoutCountingAsCleaned() {
        Clipboard.Builder builder = Clipboard.builder(STATES, new BlockPos(1, 1, 1));
        builder.set(0, 0, 0, SIGN);
        builder.setTile(0, 0, 0, sign(false));
        TileSanitizer.Result result = TileSanitizer.sanitize(builder.build());
        assertFalse(result.changed(), "nothing was removed: no notice");
        assertTrue(result.clipboard().tile(0, 0, 0) instanceof SanitizedTile);

        Clipboard.Builder plain = Clipboard.builder(STATES, new BlockPos(1, 1, 1));
        plain.set(0, 0, 0, CHEST);
        Clipboard chests = plain.build();
        assertSame(chests, TileSanitizer.sanitize(chests).clipboard(), "nothing operator-only: the same clipboard");
    }

    @Test
    void signsKeepOnlyTheirTextFieldsAndNoDataReadingComponents() throws IOException {
        NbtCompound front = side(false).toBuilder()
                .put("messages", NbtList.ofStrings(List.of(
                        "{\"nbt\":\"Inventory\",\"entity\":\"@p\"}",
                        "{\"text\":\"a\",\"extra\":[{\"selector\":\"@a\"},{\"score\":{\"name\":\"@p\",\"objective\":\"x\"}}]}",
                        "\"plain\"", "\"\"")))
                .putString("sneaky", "x")
                .build();
        NbtCompound nbt = NbtCompound.builder()
                .put("front_text", front)
                .put("back_text", side(false))
                .putByte("is_waxed", (byte) 1)
                .putString("CustomName", "{\"text\":\"x\",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/op\"}}")
                .put("components", NbtCompound.builder().putString("minecraft:lock", "k").build())
                .build();
        Clipboard.Builder builder = Clipboard.builder(STATES, new BlockPos(1, 1, 1));
        builder.set(0, 0, 0, SIGN);
        builder.setTile(0, 0, 0, BlockEntityNbt.toNbtBytes("minecraft:hanging_sign", nbt));
        TileSanitizer.Result result = TileSanitizer.sanitize(builder.build());
        assertEquals(1, result.signsCleaned());
        BlockEntityData tile = result.clipboard().tile(0, 0, 0);
        NbtCompound cleaned = BlockEntityNbt.decode(tile);
        assertEquals(java.util.Set.of("id", "front_text", "back_text", "is_waxed"), cleaned.keys());
        assertEquals("minecraft:hanging_sign", cleaned.getString("id"));
        assertEquals(java.util.Set.of("messages", "filtered_messages", "color"), cleaned.getCompound("front_text").keys());
        List<String> messages = cleaned.getCompound("front_text").getList("messages").strings();
        assertEquals("{\"text\":\"\"}", messages.get(0), "an nbt component reads server data");
        assertFalse(messages.get(1).contains("selector") || messages.get(1).contains("score"), messages.get(1));
        assertTrue(messages.get(1).contains("\"text\":\"a\""), messages.get(1));
        assertEquals("\"plain\"", messages.get(2));
    }

    @Test
    void theNbtIdAlwaysMatchesTheSignType() throws IOException {
        // A tile claiming to be a sign whose NBT names a command block: the id follows the claimed type.
        BlockEntityData forged = new dev.sculptory.core.buffer.NbtBytes("minecraft:sign", dev.sculptory.core.nbt.NbtIo
                .toBytes(NbtCompound.builder().putString("id", "minecraft:command_block").putString("Command", "op me").build()));
        Clipboard.Builder builder = Clipboard.builder(STATES, new BlockPos(1, 1, 1));
        builder.set(0, 0, 0, SIGN);
        builder.setTile(0, 0, 0, forged);
        NbtCompound cleaned = BlockEntityNbt.decode(TileSanitizer.sanitize(builder.build()).clipboard().tile(0, 0, 0));
        assertEquals("minecraft:sign", cleaned.getString("id"));
        assertNull(cleaned.get("Command"));
    }

    @Test
    void jsonCleaningIsRecursiveAndUnparseableTextIsEmptied() {
        String nested = "{\"translate\":\"x\",\"with\":[{\"text\":\"a\",\"click_event\":{\"action\":\"run_command\"}}],"
                + "\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/op me\"}}";
        String clean = TileSanitizer.cleanJson(nested);
        assertFalse(clean.contains("click"), clean);
        assertTrue(clean.contains("\"translate\":\"x\""));
        assertEquals("\"plain\"", TileSanitizer.cleanJson("\"plain\""));
        assertEquals("\"\"", TileSanitizer.cleanJson("{\"text\":"));
        // interpret:true nbt components (and ones nested in arrays or "with") read server data too: emptied.
        String interpreted = TileSanitizer.cleanJson("[{\"nbt\":\"Items\",\"block\":\"~ ~ ~\",\"interpret\":true},"
                + "{\"translate\":\"%s\",\"with\":[{\"nbt\":\"x\",\"storage\":\"a:b\",\"interpret\":true}]}]");
        assertFalse(interpreted.contains("nbt") || interpreted.contains("interpret"), interpreted);
        assertTrue(interpreted.contains("\"translate\":\"%s\""), interpreted);
    }

    /** A message of {@code depth} nested arrays around one text component with a click event. */
    private static String nestedArrays(int depth) {
        return "[".repeat(depth - 1) + "{\"text\":\"deep\",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/op\"}}"
                + "]".repeat(depth - 1);
    }

    @Test
    void messagesNestedDeeperThanTheCapBecomeEmptyText() throws IOException {
        int cap = TileSanitizer.MAX_JSON_DEPTH;
        // The click event's own object is one level deeper than the component: the cap counts every level.
        String atCap = nestedArrays(cap - 1);
        String cleanedAtCap = TileSanitizer.cleanJson(atCap);
        assertTrue(cleanedAtCap.contains("\"text\":\"deep\"") && !cleanedAtCap.contains("click"), cleanedAtCap);
        assertEquals("\"\"", TileSanitizer.cleanJson(nestedArrays(cap)), "one level over the cap");
        // Deep enough to overflow a recursive parser, strip or writer; objects count as levels too.
        assertEquals("\"\"", TileSanitizer.cleanJson(nestedArrays(20_000)));
        assertEquals("\"\"", TileSanitizer.cleanJson("{\"extra\":".repeat(5_000) + "\"x\"" + "}".repeat(5_000)));
        // Brackets inside strings and comments do not count, and quotes in comments do not hide real brackets.
        assertEquals("[\"[[[[[[[[\"]", TileSanitizer.cleanJson("[\"[[[[[[[[\"]"));
        assertEquals("\"\"", TileSanitizer.cleanJson("/* \" */" + nestedArrays(cap)));
        assertEquals("\"\"", TileSanitizer.cleanJson("['\"'," + nestedArrays(cap) + "]"));

        // As sign content, vanilla or modded, the deep message is emptied and the sign counts as cleaned.
        NbtCompound deepSide = side(false).toBuilder()
                .put("messages", NbtList.ofStrings(List.of(nestedArrays(20_000), "\"kept\"", "\"\"", "\"\""))).build();
        Clipboard.Builder builder = Clipboard.builder(STATES, new BlockPos(1, 1, 1));
        builder.set(0, 0, 0, SIGN);
        builder.setTile(0, 0, 0, BlockEntityNbt.toNbtBytes("minecraft:sign", NbtCompound.builder()
                .put("front_text", deepSide).put("back_text", side(false)).putByte("is_waxed", (byte) 0).build()));
        TileSanitizer.Result result = TileSanitizer.sanitize(builder.build());
        assertEquals(1, result.signsCleaned());
        List<String> messages = BlockEntityNbt.decode(result.clipboard().tile(0, 0, 0)).getCompound("front_text")
                .getList("messages").strings();
        assertEquals(List.of("\"\"", "\"kept\"", "\"\"", "\"\""), messages);
    }

    /** The fake state space with two widget states marked as operator-NBT blocks. */
    private static final class OperatorStates implements StateSpace {
        @Override
        public int size() {
            return FAKE.size();
        }

        @Override
        public int air() {
            return FAKE.air();
        }

        @Override
        public int flags(int h) {
            int flags = FAKE.flags(h);
            return h == COMMAND || h == SIGN ? flags | StateFlags.OPERATOR_NBT | StateFlags.HAS_BLOCK_ENTITY : flags;
        }

        @Override
        public String format(int h) {
            return FAKE.format(h);
        }

        @Override
        public int parse(String spec) {
            return FAKE.parse(spec);
        }

        @Override
        public BlockDescriptor describe(int h) {
            return FAKE.describe(h);
        }

        @Override
        public int resolve(BlockDescriptor d) {
            return FAKE.resolve(d);
        }

        @Override
        public NamespacedId blockId(int h) {
            return FAKE.blockId(h);
        }

        @Override
        public boolean inTag(int h, NamespacedId tag) {
            return FAKE.inTag(h, tag);
        }

        @Override
        public int rotate(int h, int clockwiseQuarterTurns) {
            return FAKE.rotate(h, clockwiseQuarterTurns);
        }

        @Override
        public int mirror(int h, Mirror m) {
            return FAKE.mirror(h, m);
        }

        @Override
        public int withWaterlogged(int h, boolean on) {
            return FAKE.withWaterlogged(h, on);
        }

        @Override
        public int fluidSource(int h) {
            return FAKE.fluidSource(h);
        }
    }
}
