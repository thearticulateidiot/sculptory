package dev.sculptory.fabric.client;

import dev.sculptory.core.state.StateSpace;
import dev.sculptory.fabric.SculptoryMod;
import dev.sculptory.fabric.client.builder.BuilderClient;
import dev.sculptory.fabric.client.editor.EditorClient;
import dev.sculptory.fabric.client.editor.SessionProvider;
import dev.sculptory.fabric.client.editor.check.CheckConfig;
import dev.sculptory.fabric.client.editor.check.PlayCheck;
import dev.sculptory.fabric.client.editor.demo.DemoConfig;
import dev.sculptory.fabric.client.editor.demo.PlayDemo;
import dev.sculptory.fabric.client.editor.render.ghost.GhostDebug;
import dev.sculptory.fabric.client.editor.tour.TourConfig;
import dev.sculptory.fabric.client.editor.tour.UiTour;
import dev.sculptory.fabric.client.net.ClientNet;
import dev.sculptory.fabric.client.session.FabricEditorSession;
import dev.sculptory.fabric.client.world.ClientBlockChanges;
import dev.sculptory.fabric.client.world.ClientEditorBackend;
import dev.sculptory.fabric.client.world.ClientStateSpaces;
import dev.sculptory.fabric.client.world.ResentChunks;
import dev.sculptory.fabric.world.FabricStateSpace;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;

/**
 * Client entrypoint: the block-change stamp, the client state space, protocol v2 networking, the editor (B) and builder
 * mode (G).
 * The editor uses the real session unless {@code -Dsculptory.mockSession=true} asks for the mock one; the
 * networking runs either way.
 */
public final class SculptoryClientMod implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        SculptoryMod.LOG.info("Sculptory client initializing");
        ClientBlockChanges.install();
        ResentChunks.install();
        // Registered before ClientNet so each join builds the space before the handshake starts.
        ClientStateSpaces states = ClientStateSpaces.install(SculptoryClientMod::buildStateSpace);
        FabricEditorSession session = ClientNet.install(states);
        if (SessionProvider.mockRequested()) {
            SculptoryMod.LOG.warn("Sculptory editor is using the mock session (-D{}=true): edits are simulated",
                    SessionProvider.MOCK_PROPERTY);
        } else {
            SessionProvider.set(new ClientEditorBackend(session, states, () -> MinecraftClient.getInstance().world));
        }
        EditorClient editor = EditorClient.init();
        // Builder mode (hold G): the same session and state space as the editor.
        BuilderClient.init(editor, ClientNet::session, states);
        GhostDebug.register();
        // The dev-only harnesses: the screenshot tour, the play check and the scripted demo.
        // They run only from a dev checkout (runClient) and are left out of the mod jar. Their property constants are
        // inlined, so without a property no harness class is loaded; with one on a jar without them, it only logs.
        if (devHarness(TourConfig.PROPERTY, "dev.sculptory.fabric.client.editor.tour.UiTour")) {
            UiTour.install(System.getProperty(TourConfig.PROPERTY));
        }
        if (devHarness(CheckConfig.PROPERTY, "dev.sculptory.fabric.client.editor.check.PlayCheck")) {
            PlayCheck.install(CheckConfig.fromSystem().orElseThrow());
        }
        if (devHarness(DemoConfig.PROPERTY, "dev.sculptory.fabric.client.editor.demo.PlayDemo")) {
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
        String resource = className.replace('.', '/') + ".class";
        if (SculptoryClientMod.class.getClassLoader().getResource(resource) != null) return true;
        SculptoryMod.LOG.warn("Sculptory: -D{} is set, but this jar has no dev harnesses (they run from a dev checkout only)",
                property);
        return false;
    }

    /** Reads {@code Block.STATE_IDS} and the bound block tags; touches no server-only class. */
    private static StateSpace buildStateSpace() {
        return FabricStateSpace.build();
    }
}
