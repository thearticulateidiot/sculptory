package dev.sculptory.fabric.client;

import dev.sculptory.core.state.StateSpace;
import dev.sculptory.fabric.client.command.FabricFlySpeedCommand;
import dev.sculptory.fabric.client.editor.devtools.DevCommands;
import dev.sculptory.mc.Sculptory;
import dev.sculptory.mc.client.builder.BuilderClient;
import dev.sculptory.mc.client.editor.EditorClient;
import dev.sculptory.mc.client.editor.SessionProvider;
import dev.sculptory.mc.client.editor.biomes.BiomeView;
import dev.sculptory.mc.client.editor.check.CheckConfig;
import dev.sculptory.mc.client.editor.check.PlayCheck;
import dev.sculptory.mc.client.editor.demo.DemoConfig;
import dev.sculptory.mc.client.editor.demo.PlayDemo;
import dev.sculptory.mc.client.editor.tour.TourConfig;
import dev.sculptory.mc.client.editor.tour.UiTour;
import dev.sculptory.mc.client.net.ClientNet;
import dev.sculptory.mc.client.session.McEditorSession;
import dev.sculptory.mc.client.world.ClientBlockChanges;
import dev.sculptory.mc.client.world.ClientEditorBackend;
import dev.sculptory.mc.client.world.ClientStateSpaces;
import dev.sculptory.mc.client.world.ResentChunks;
import dev.sculptory.mc.loader.ClientLoaders;
import dev.sculptory.mc.world.McStateSpace;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/**
 * Client entrypoint: the block-change stamp, the client state space, protocol v2 networking, the editor (B) and builder
 * mode (G).
 * The editor uses the real session unless {@code -Dsculptory.mockSession=true} asks for the mock one; the
 * networking runs either way.
 */
public final class SculptoryClientMod implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        Sculptory.LOG.info("Sculptory client initializing");
        // The seam the loader-free client code registers its hooks through: installed before any of it runs.
        ClientLoaders.install(new FabricClientLoader());
        ClientBlockChanges.install();
        // Biome view (protocol 6) takes its snapshot again when chunks arrive (biome packets: ClientBiomeDataMixin).
        ClientLoaders.get().onChunkLoad((world, chunk) -> BiomeView.changed());
        ResentChunks.install();
        // Registered before ClientNet so each join builds the space before the handshake starts.
        ClientStateSpaces states = ClientStateSpaces.install(SculptoryClientMod::buildStateSpace);
        McEditorSession session = ClientNet.install(states);
        if (SessionProvider.mockRequested()) {
            Sculptory.LOG.warn("Sculptory editor is using the mock session (-D{}=true): edits are simulated",
                    SessionProvider.MOCK_PROPERTY);
        } else {
            SessionProvider.set(new ClientEditorBackend(session, states, () -> Minecraft.getInstance().level));
        }
        EditorClient editor = EditorClient.init();
        // /flyspeed and /sculptory flyspeed are Fabric client commands over the editor's flight.
        FabricFlySpeedCommand.register(editor.flight(), editor.mode()::isEditing);
        // Builder mode (hold G): the same session and state space as the editor.
        BuilderClient.init(editor, ClientNet::session, states);
        // The development-only client commands (/bsclient ghost, /bsclient rotation compare): dev runs only, and the
        // mod jar leaves them out like the harnesses below.
        if (FabricLoader.getInstance().isDevelopmentEnvironment()
                && devClassPresent("dev.sculptory.fabric.client.editor.devtools.DevCommands")) {
            DevCommands.register(states);
        }
        // The dev-only harnesses: the screenshot tour, the play check and the scripted demo.
        // They run only from a dev checkout (runClient) and are left out of the mod jar. Their property constants are
        // inlined, so without a property no harness class is loaded; with one on a jar without them, it only logs.
        if (devHarness(TourConfig.PROPERTY, "dev.sculptory.mc.client.editor.tour.UiTour")) {
            UiTour.install(System.getProperty(TourConfig.PROPERTY));
        }
        if (devHarness(CheckConfig.PROPERTY, "dev.sculptory.mc.client.editor.check.PlayCheck")) {
            PlayCheck.install(CheckConfig.fromSystem().orElseThrow());
        }
        if (devHarness(DemoConfig.PROPERTY, "dev.sculptory.mc.client.editor.demo.PlayDemo")) {
            PlayDemo.install(DemoConfig.fromSystem().orElseThrow());
        }
    }

    /**
     * Whether the dev-only harness started by {@code -D<property>} should run: the property is set and the harness's
     * class is on the classpath (a dev run; the mod jar leaves the harnesses out). Looks for the class file by name, so
     * nothing is loaded when it is missing.
     */
    private static boolean devHarness(String property, String className) {
        if (System.getProperty(property) == null) return false;
        if (devClassPresent(className)) return true;
        Sculptory.LOG.warn("Sculptory: -D{} is set, but this jar has no dev harnesses (they run from a dev checkout only)",
                property);
        return false;
    }

    /**
     * Whether the dev-only class {@code className} is on the classpath (a dev run; the mod jar leaves the dev packages
     * out). Looks for the class file by name, so nothing is loaded.
     */
    private static boolean devClassPresent(String className) {
        String resource = className.replace('.', '/') + ".class";
        return SculptoryClientMod.class.getClassLoader().getResource(resource) != null;
    }

    /** Reads {@code Block.STATE_IDS} and the bound block tags; touches no server-only class. */
    private static StateSpace buildStateSpace() {
        return McStateSpace.build();
    }
}
