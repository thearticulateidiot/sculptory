package dev.sculptory.mc.gametest;

import dev.sculptory.mc.engine.impl.EditServiceHost;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.LoggerFactory;

/**
 * The shared GameTests: the test classes every loader's test mod registers, the template they are placed on, and the
 * set-up the test mod runs when it starts (after the mod and the engine, see {@link #start}).
 */
public final class GameTestSuite {
    /**
     * The empty 8x8x8 structure every test is placed on ({@code data/sculptory-gametest/structure/empty.nbt} in the
     * shared test resources, read by vanilla's structure manager on every loader). A test's name stays
     * {@code <class>.<method>} in lower case; the template is used as written, without the class prefix vanilla adds.
     */
    public static final String EMPTY_STRUCTURE = "sculptory-gametest:empty";

    /**
     * The GameTest classes, in registration order. Fabric lists them as {@code fabric-gametest} entrypoints in the test
     * mod's fabric.mod.json (its bootstrap checks that the two lists match); NeoForge registers these.
     */
    public static final List<Class<?>> CLASSES = List.of(
            SmokeGameTest.class,
            WriterGameTest.class,
            ExecutorGameTest.class,
            ExecutorLifecycleGameTest.class,
            PermissionGameTest.class,
            EditServiceGameTest.class,
            StrokeGameTest.class,
            BrushMaskGameTest.class,
            BrushSurfaceGameTest.class,
            BenchGameTest.class,
            ClientSyncGameTest.class,
            WorldReaderGameTest.class,
            CommandAccessGameTest.class,
            ClipboardGameTest.class,
            FlipGameTest.class,
            SchematicLibraryGameTest.class,
            SchematicFormatsGameTest.class,
            LibraryManageGameTest.class,
            LibraryAccessGameTest.class,
            WorldEditInteropGameTest.class,
            ClipboardRobustnessGameTest.class,
            ScatterGameTest.class,
            ScatterLifecycleGameTest.class,
            ScatterExecutorGameTest.class,
            ScatterBlocksGameTest.class,
            ScatterWaterGameTest.class,
            MultiplayerGameTest.class,
            HistoryOverwriteGameTest.class,
            BrushSymmetryGameTest.class,
            ShapeBrushGameTest.class,
            ShapeBrushLaneGameTest.class,
            PaletteGameTest.class,
            DurableHistoryGameTest.class,
            CompatHistoryGameTest.class,
            HistoryContentsGameTest.class,
            EntitiesGameTest.class,
            EntitySafetyGameTest.class,
            EntityRegionGameTest.class,
            RegionGameTest.class,
            OpSymmetryGameTest.class,
            GenerateGameTest.class,
            SmartSurfaceGameTest.class,
            PasteIntoGameTest.class,
            FluidGameTest.class,
            FluidUndoGameTest.class,
            FluidRestartGameTest.class,
            ConfigReloadGameTest.class,
            MixPatternGameTest.class,
            TinkerGameTest.class,
            BuilderModeGameTest.class,
            BrushWorldEdgesGameTest.class,
            ToolLabelGameTest.class,
            MaskGameTest.class,
            ReplaceOpsGameTest.class,
            WeatherGameTest.class,
            HeightmapGameTest.class,
            HeightmapLaneGameTest.class,
            HeightmapWideGameTest.class,
            LightBatchGameTest.class,
            HeightmapPlanningGameTest.class,
            LineNavGameTest.class,
            ScatterFeatureGameTest.class,
            ContractStubsGameTest.class,
            BiomesGameTest.class,
            ExtrudePathsGameTest.class,
            FreeRotationGameTest.class,
            ResamplingGameTest.class,
            AirPathsGameTest.class,
            PathPointsGameTest.class,
            ExtendToGroundGameTest.class,
            PlaytestSetupGameTest.class
    );

    private GameTestSuite() {
    }

    /**
     * Moves the server's own undo history ({@link EditServiceHost#historyDir}) into a temporary folder deleted when the
     * JVM exits, so tests that edit through the server's service (mock players come and go with every run) never leave
     * history files in the GameTest world. Idempotent; the loader's test mod calls it when it starts.
     */
    public static synchronized void start() {
        if (System.getProperty(EditServiceHost.HISTORY_DIR_PROPERTY) != null) return;
        try {
            Path temp = Files.createTempDirectory("sculptory-gametest-history");
            System.setProperty(EditServiceHost.HISTORY_DIR_PROPERTY, temp.toString());
            Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteTree(temp), "Sculptory test cleanup"));
        } catch (IOException e) {
            LoggerFactory.getLogger("sculptory").warn("Could not make a temporary history folder for GameTests", e);
        }
    }

    /** Deletes a folder and everything in it (best effort). */
    static void deleteTree(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException e) {
            LoggerFactory.getLogger("sculptory").warn("Could not delete the test history folder {}", dir, e);
        }
    }
}
