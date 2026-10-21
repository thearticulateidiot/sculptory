package dev.sculptory.neoforge.client;

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
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/**
 * The client side on NeoForge, started by {@code SculptoryNeoForge} on a client after the server side: the same wiring
 * as the Fabric mod's {@code SculptoryClientMod} (the block-change stamp, the client state space, protocol v2
 * networking, the editor, builder mode, {@code /flyspeed} and the dev-only harnesses). The Fabric layer's
 * development-only {@code /bsclient} commands are typed on Fabric's client command API and are not ported.
 *
 * <p>When and on which thread: in {@code RegisterKeyMappingsEvent}, not while the mod is constructed. FML constructs
 * mods in parallel on mod-loading worker threads, and the wiring makes {@code KeyMapping}s, whose constructor writes
 * the game's static key maps (vanilla's {@code KeyMapping.ALL} and NeoForge's lookup), which are not thread-safe.
 * NeoForge 21.1 posts {@code RegisterKeyMappingsEvent} once, from {@code ClientHooks.initClientHooks} in the
 * {@code Minecraft} constructor: on the render thread, after every mod was constructed, before the first resource load
 * and before {@code FMLClientSetupEvent} (which comes from that load, on worker threads; key registration is over by
 * then). FML's {@code ModLoader.postEvent} hands it to one mod after another on that thread, so no other mod's
 * listener runs at the same time. Fabric runs its client entrypoint on the render thread in the {@code Minecraft}
 * constructor too, a little earlier.
 */
public final class SculptoryNeoForgeClient {
    private SculptoryNeoForgeClient() {
    }

    /** Called from the mod's constructor on a client: starts the client side once keys can be registered. */
    public static void init(IEventBus modBus) {
        modBus.addListener(SculptoryNeoForgeClient::start);
    }

    private static void start(RegisterKeyMappingsEvent keys) {
        Sculptory.LOG.info("Sculptory client initializing (thread {})", Thread.currentThread().getName());
        // The seam the loader-free client code registers its hooks through: installed before any of it runs. Keys are
        // registered with the event while the wiring below runs; registering one later fails.
        NeoForgeClientLoader loader = new NeoForgeClientLoader(keys);
        ClientLoaders.install(loader);
        try {
            wire();
        } finally {
            loader.closeKeyRegistration();
        }
    }

    private static void wire() {
        ClientBlockChanges.install();
        // Biome view (protocol 6) takes its snapshot again when chunks arrive (biome packets: ClientBiomeDataMixin).
        ClientLoaders.get().onChunkLoad((world, chunk) -> BiomeView.changed());
        ResentChunks.install();
        // Registered before ClientNet so each join builds the space before the handshake starts.
        ClientStateSpaces states = ClientStateSpaces.install(McStateSpace::build);
        McEditorSession session = ClientNet.install(states);
        if (SessionProvider.mockRequested()) {
            Sculptory.LOG.warn("Sculptory editor is using the mock session (-D{}=true): edits are simulated",
                    SessionProvider.MOCK_PROPERTY);
        } else {
            SessionProvider.set(new ClientEditorBackend(session, states, () -> Minecraft.getInstance().level));
        }
        EditorClient editor = EditorClient.init();
        EditorHudLayers.install();
        NeoForgeFlySpeedCommand.register(editor.flight(), editor.mode()::isEditing);
        // Builder mode (hold G): the same session and state space as the editor.
        BuilderClient.init(editor, ClientNet::session, states);
        // The dev-only harnesses (screenshot tour, play check, scripted demo): from a dev checkout only, left out of
        // the jar; started only when their property is set and their class is there.
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

    /** Whether the dev-only harness started by {@code -D<property>} should run (as in the Fabric layer). */
    private static boolean devHarness(String property, String className) {
        if (System.getProperty(property) == null) return false;
        String resource = className.replace('.', '/') + ".class";
        if (SculptoryNeoForgeClient.class.getClassLoader().getResource(resource) != null) return true;
        Sculptory.LOG.warn("Sculptory: -D{} is set, but this jar has no dev harnesses (they run from a dev checkout only)",
                property);
        return false;
    }
}
