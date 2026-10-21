package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.EditTestSupport.loadAndForce;
import static dev.sculptory.fabric.gametest.EngineTestSupport.box;
import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.forceChunks;
import static dev.sculptory.fabric.gametest.EngineTestSupport.pos;
import static dev.sculptory.fabric.gametest.EngineTestSupport.regionCorner;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.nbt.BlockEntityNbt;
import dev.sculptory.core.nbt.NbtList;
import dev.sculptory.core.schem.Schematic;
import dev.sculptory.core.schem.SchematicCodec;
import dev.sculptory.fabric.gametest.ClipTestSupport.Captured;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.mc.schem.McDataFixHook;
import dev.sculptory.mc.world.BlockWriter;
import dev.sculptory.mc.world.McStateSpace;
import dev.sculptory.server.engine.ClipboardService;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.impl.ServerClipboards;
import dev.sculptory.server.platform.WriteOptions;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import org.slf4j.LoggerFactory;

/**
 * Opt-in interop check against WorldEdit's own Sponge v3 reader and writer.
 * Runs only with {@code SCULPTORY_WE_INTEROP=1} and WorldEdit loaded in the GameTest runtime, which is never a
 * committed dependency: a local init script adds the jar, e.g.
 * <pre>
 * allprojects { pluginManager.withPlugin('fabric-loom') {
 *     dependencies { modLocalRuntime files('.../.local/worldedit-mod-7.3.8.jar') } } }
 * </pre>
 * run as {@code scripts/gradle.ps1 :fabric:runGameTest --init-script <that file>}. WorldEdit is reached by reflection,
 * so this compiles without it. Otherwise the test logs that it was skipped and passes.
 *
 * <p>Checks: (1) a region written by WorldEdit's v3 writer reads with our codec into the same states, block entity
 * and anchor ({@code origin - min}); (2) our v3 file reads with WorldEdit's v3 reader into the same states, and
 * WorldEdit's {@code origin - minimumPoint} equals our anchor.
 */
public final class WorldEditInteropGameTest implements FabricGameTest {
    private static final String ENV = "SCULPTORY_WE_INTEROP";

    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_we_interop",
            timeoutTicks = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT)
    public void worldEditSpongeV3Interop(GameTestHelper context) throws Exception {
        boolean enabled = "1".equals(System.getenv(ENV));
        boolean loaded = FabricLoader.getInstance().isModLoaded("worldedit");
        if (!enabled || !loaded) {
            LoggerFactory.getLogger("sculptory").info("worldEditSpongeV3Interop skipped ({}={}, WorldEdit jar loaded: {})",
                    ENV, System.getenv(ENV), loaded);
            context.succeed();
            return;
        }
        Harness h = new Harness(context);
        ServerLevel world = h.world;
        McStateSpace states = h.runtime.states();
        int[] at = regionCorner(context, 60);
        Box source = box(at[0], 100, at[1], at[0] + 5, 102, at[1] + 4);
        Box all = box(at[0], 100, at[1], at[0] + 15, 102, at[1] + 15);
        loadAndForce(world, all);
        BlockWriter writer = h.runtime.writer(world, WriteOptions.DEFAULT);
        String[] specs = {"minecraft:stone", "minecraft:oak_stairs[facing=east,half=top]", "minecraft:oak_log[axis=x]",
                "minecraft:glass_pane[north=true,east=true]", "minecraft:white_wool"};
        for (int x = 0; x < 6; x++) {
            for (int z = 0; z < 5; z++) writer.write(at[0] + x, 100, at[1] + z, h.state(specs[(x + z) % specs.length]), null);
        }
        net.minecraft.core.BlockPos chest = pos(at[0] + 2, 101, at[1] + 2);
        writer.write(chest.getX(), chest.getY(), chest.getZ(), h.state("minecraft:chest[facing=north]"), null);
        ((ChestBlockEntity) world.getBlockEntity(chest)).setItem(4, new ItemStack(Items.EMERALD, 9));
        BlockPos origin = new BlockPos(at[0] + 2, 100, at[1] + 1);
        BlockPos anchor = new BlockPos(2, 0, 1);

        // (1) WorldEdit writes, we read.
        byte[] theirs = worldEditWrite(world, source, origin);
        Schematic read = SchematicCodec.read(new ByteArrayInputStream(theirs), states, SchematicCodec.Limits.DEFAULT,
                McDataFixHook.get());
        Clipboard ours = read.clipboard();
        check(read.formatVersion() == 3, "WorldEdit wrote version " + read.formatVersion());
        check(ours.size().equals(new BlockPos(6, 3, 5)), "size " + ours.size());
        check(ours.anchor().equals(anchor), "anchor from WorldEdit's Offset: " + ours.anchor() + ", expected " + anchor);
        check(read.report().unknownStates().isEmpty(), "unknown states " + read.report().unknownStates());
        for (int y = 0; y < 3; y++) {
            for (int z = 0; z < 5; z++) {
                for (int x = 0; x < 6; x++) {
                    int expected = Block.getId(world.getBlockState(pos(at[0] + x, 100 + y, at[1] + z)));
                    check(ours.get(x, y, z) == expected, "cell " + x + "," + y + "," + z + ": "
                            + states.format(ours.get(x, y, z)) + " vs " + states.format(expected));
                }
            }
        }
        BlockEntityData tile = ours.tile(2, 1, 2);
        check(tile != null && tile.typeId().equals("minecraft:chest"), "chest tile " + tile);
        NbtList items = BlockEntityNbt.decode(tile).getList("Items");
        check(items != null && items.size() == 1, "chest items " + items);

        // (2) We write, WorldEdit reads.
        var clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        Captured<ClipboardService.ClipboardInfo> copied = ClipboardGameTest.copy(clips, h.player, source, origin);
        Captured<ClipboardService.Outbound> exported = new Captured<>();
        context.startSequence()
                .thenWaitUntil(() -> {
                    ClipboardService.ClipboardInfo info = copied.get("copy");
                    try {
                        clips.export(h.player, info.clipboardId(), exported);
                    } catch (EditRejected e) {
                        throw new GameTestAssertException("export refused: " + e.getMessage());
                    }
                })
                .thenWaitUntil(() -> {
                    byte[] mine = exported.get("export").payload();
                    try {
                        checkWorldEditRead(mine, world, states, source, anchor);
                    } catch (ReflectiveOperationException | IOException e) {
                        throw new GameTestAssertException("WorldEdit could not read our file: " + e);
                    }
                    forceChunks(world, all, false);
                    h.close();
                })
                .thenSucceed();
    }

    /** WorldEdit's v3 bytes for {@code box} with the clipboard origin at {@code origin}. */
    private static byte[] worldEditWrite(ServerLevel world, Box box, BlockPos origin) throws Exception {
        Object weWorld = callStatic("com.sk89q.worldedit.fabric.FabricAdapter", "adapt", world);
        Object min = vector(box.min().x(), box.min().y(), box.min().z());
        Object max = vector(box.max().x(), box.max().y(), box.max().z());
        Object region = construct("com.sk89q.worldedit.regions.CuboidRegion", weWorld, min, max);
        Object clipboard = construct("com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard", region);
        call(clipboard, "setOrigin", vector(origin.x(), origin.y(), origin.z()));
        Object worldEdit = callStatic("com.sk89q.worldedit.WorldEdit", "getInstance");
        Object session = call(worldEdit, "newEditSession", weWorld);
        try {
            Object copy = construct("com.sk89q.worldedit.function.operation.ForwardExtentCopy", session, region, clipboard, min);
            callStatic("com.sk89q.worldedit.function.operation.Operations", "complete", copy);
        } finally {
            call(session, "close");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Object writer = call(format(), "getWriter", out);
        try {
            call(writer, "write", clipboard);
        } finally {
            call(writer, "close");
        }
        return out.toByteArray();
    }

    private static void checkWorldEditRead(byte[] bytes, ServerLevel world, McStateSpace states, Box box,
                                           BlockPos anchor) throws ReflectiveOperationException, IOException {
        Object reader = call(format(), "getReader", new ByteArrayInputStream(bytes));
        Object clipboard;
        try {
            clipboard = call(reader, "read");
        } finally {
            call(reader, "close");
        }
        int[] dims = xyz(call(clipboard, "getDimensions"));
        check(dims[0] == 6 && dims[1] == 3 && dims[2] == 5, "WorldEdit dims " + dims[0] + "," + dims[1] + "," + dims[2]);
        int[] weOrigin = xyz(call(clipboard, "getOrigin"));
        int[] weMin = xyz(call(clipboard, "getMinimumPoint"));
        BlockPos weAnchor = new BlockPos(weOrigin[0] - weMin[0], weOrigin[1] - weMin[1], weOrigin[2] - weMin[2]);
        check(weAnchor.equals(anchor), "WorldEdit's origin - min is " + weAnchor + ", our anchor " + anchor);
        for (int y = 0; y < 3; y++) {
            for (int z = 0; z < 5; z++) {
                for (int x = 0; x < 6; x++) {
                    Object state = call(clipboard, "getBlock", vector(weMin[0] + x, weMin[1] + y, weMin[2] + z));
                    String text = (String) call(state, "getAsString");
                    int expected = Block.getId(world.getBlockState(pos(box.min().x() + x, box.min().y() + y,
                            box.min().z() + z)));
                    check(states.parse(text) == expected, "WorldEdit read " + text + " at " + x + "," + y + "," + z
                            + ", expected " + states.format(expected));
                }
            }
        }
        Object chest = call(clipboard, "getFullBlock", vector(weMin[0] + 2, weMin[1] + 1, weMin[2] + 2));
        check((Boolean) call(chest, "hasNbtData"), "WorldEdit lost the chest's block entity");
    }

    private static Object format() throws ReflectiveOperationException {
        return Class.forName("com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat")
                .getField("SPONGE_V3_SCHEMATIC").get(null);
    }

    private static Object vector(int x, int y, int z) throws ReflectiveOperationException {
        return callStatic("com.sk89q.worldedit.math.BlockVector3", "at", x, y, z);
    }

    private static int[] xyz(Object vector) throws ReflectiveOperationException {
        return new int[] {(Integer) call(vector, "x"), (Integer) call(vector, "y"), (Integer) call(vector, "z")};
    }

    // ------------------------------------------------------------------ reflection

    private static Object construct(String type, Object... args) throws ReflectiveOperationException {
        for (Constructor<?> constructor : Class.forName(type).getConstructors()) {
            if (fits(constructor.getParameterTypes(), args)) return unwrap(() -> constructor.newInstance(args));
        }
        throw new NoSuchMethodException(type + " constructor for " + args.length + " arguments");
    }

    private static Object callStatic(String type, String name, Object... args) throws ReflectiveOperationException {
        return invoke(Class.forName(type), null, name, args);
    }

    private static Object call(Object target, String name, Object... args) throws ReflectiveOperationException {
        return invoke(target.getClass(), target, name, args);
    }

    private static Object invoke(Class<?> type, Object target, String name, Object... args)
            throws ReflectiveOperationException {
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(name) || (target == null) != Modifier.isStatic(method.getModifiers())) continue;
            if (!fits(method.getParameterTypes(), args)) continue;
            method.setAccessible(true);
            return unwrap(() -> method.invoke(target, args));
        }
        throw new NoSuchMethodException(type.getName() + "." + name + " for " + args.length + " arguments");
    }

    private static boolean fits(Class<?>[] parameters, Object[] args) {
        if (parameters.length != args.length) return false;
        for (int i = 0; i < parameters.length; i++) {
            Class<?> p = parameters[i];
            if (p.isPrimitive()) {
                if (!(p == int.class && args[i] instanceof Integer)) return false;
            } else if (args[i] != null && !p.isInstance(args[i])) {
                return false;
            }
        }
        return true;
    }

    @FunctionalInterface
    private interface Reflective {
        Object run() throws ReflectiveOperationException;
    }

    private static Object unwrap(Reflective call) throws ReflectiveOperationException {
        try {
            return call.run();
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw e;
        }
    }
}
