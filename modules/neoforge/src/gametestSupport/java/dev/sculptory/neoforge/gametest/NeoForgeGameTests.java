package dev.sculptory.neoforge.gametest;

import dev.sculptory.mc.gametest.GameTestLoaders;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.xml.parsers.ParserConfigurationException;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestRegistry;
import net.minecraft.gametest.framework.GlobalTestReporter;
import net.minecraft.gametest.framework.JUnitLikeTestReporter;
import net.minecraft.gametest.framework.StructureUtils;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers the shared GameTests (plain classes with vanilla {@code @GameTest} methods, no NeoForge annotation) on
 * NeoForge, the way Fabric API registers them, so a test has the same name and template on both loaders.
 *
 * <p>Why not {@link RegisterGameTestsEvent#register}: NeoForge 21.1 turns a registered method into a test with its own
 * patch of {@code GameTestRegistry.turnMethodIntoTestFunction}, which always builds the template as
 * {@code GameTestHooks.getTemplateNamespace(method) + ":" + <class>. + template} (the namespace from
 * {@code @GameTest(templateNamespace)} or a {@code @GameTestHolder} on the class, else "minecraft"; the class prefix
 * unless {@code @PrefixGameTestTemplate(false)}, which also drops the class from the test's name). A namespaced
 * template like {@code sculptory-gametest:empty} would come out as {@code minecraft:<class>.sculptory-gametest:empty},
 * which is no id; getting the template right needs NeoForge annotations on the shared classes. So, when NeoForge
 * fires the event (server {@code Main}, before the GameTest server is built from
 * {@code GameTestRegistry.getAllTestFunctions()}), this builds each test like Fabric API's
 * {@code TestFunctionsMixin} does (name {@code <class>.<method>} in lower case, the template as written) and adds it to
 * that collection, which vanilla's registry hands out live.
 */
public final class NeoForgeGameTests {
    private static final Logger LOG = LoggerFactory.getLogger("sculptory");
    /** Where the run writes its JUnit-style report (like Fabric API's {@code fabric-api.gametest.report-file}). */
    static final String REPORT_PROPERTY = "sculptory.gametest.report";

    private NeoForgeGameTests() {
    }

    /**
     * Installs the NeoForge {@code GameTestLoader}, the test permission handler and the registration of
     * {@code classes}. Called from a test mod's constructor.
     */
    public static void install(IEventBus modBus, List<Class<?>> classes) {
        GameTestLoaders.install(NeoForgeGameTestLoader.INSTANCE);
        NeoForge.EVENT_BUS.addListener((PermissionGatherEvent.Handler event) ->
                event.addPermissionHandler(TestPermissionHandler.ID, TestPermissionHandler::new));
        modBus.addListener((RegisterGameTestsEvent event) -> register(classes));
    }

    private static void register(List<Class<?>> classes) {
        String report = System.getProperty(REPORT_PROPERTY);
        if (report != null) {
            try {
                GlobalTestReporter.replaceWith(new JUnitLikeTestReporter(new File(report)));
            } catch (ParserConfigurationException e) {
                throw new IllegalStateException("Could not set up the GameTest report " + report, e);
            }
        }
        // NeoForge's own filter by template namespace (neoforge.enabledGameTestNamespaces), kept for these tests too.
        Set<String> enabled = Arrays.stream(System.getProperty("neoforge.enabledGameTestNamespaces", "").split(","))
                .map(String::trim)
                .filter(namespace -> !namespace.isEmpty())
                .collect(Collectors.toSet());
        int added = 0;
        for (Class<?> testClass : classes) {
            // Fabric API registers a class through GameTestRegistry.register(Class): its methods sorted by name.
            Method[] methods = testClass.getDeclaredMethods();
            Arrays.sort(methods, Comparator.comparing(Method::getName));
            boolean any = false;
            for (Method method : methods) {
                if (method.isAnnotationPresent(GameTestGenerator.class) || method.isAnnotationPresent(BeforeBatch.class)
                        || method.isAnnotationPresent(AfterBatch.class)) {
                    // Not used by the shared tests; registering them here would need the vanilla logic copied too.
                    throw new IllegalStateException(testClass.getName() + "." + method.getName()
                            + ": @GameTestGenerator, @BeforeBatch and @AfterBatch are not supported in shared GameTests");
                }
                GameTest gameTest = method.getAnnotation(GameTest.class);
                if (gameTest == null) continue;
                TestFunction test = testFunction(method, gameTest);
                if (!enabled.isEmpty()
                        && !enabled.contains(ResourceLocation.parse(test.structureName()).getNamespace())) continue;
                GameTestRegistry.getAllTestFunctions().add(test);
                any = true;
                added++;
            }
            if (any) GameTestRegistry.getAllTestClassNames().add(testClass.getSimpleName());
        }
        LOG.info("Registered {} GameTests from {} classes", added, classes.size());
    }

    private static TestFunction testFunction(Method method, GameTest gameTest) {
        String name = method.getDeclaringClass().getSimpleName().toLowerCase(Locale.ROOT) + "."
                + method.getName().toLowerCase(Locale.ROOT);
        if (gameTest.template().isEmpty()) {
            // Fabric API would use <test mod id>:<name>, a structure per test; the shared tests ship none.
            throw new IllegalStateException(name + ": a shared GameTest names its template (EMPTY_STRUCTURE)");
        }
        return new TestFunction(gameTest.batch(), name, gameTest.template(),
                StructureUtils.getRotationForRotationSteps(gameTest.rotationSteps()), gameTest.timeoutTicks(),
                gameTest.setupTicks(), gameTest.required(), gameTest.manualOnly(), gameTest.requiredSuccesses(),
                gameTest.attempts(), gameTest.skyAccess(), invoker(method));
    }

    /** Runs the test method on a new instance of its class, as vanilla and Fabric API do. */
    private static Consumer<GameTestHelper> invoker(Method method) {
        return helper -> {
            Object instance;
            try {
                instance = method.getDeclaringClass().getConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException("Could not create test class " + method.getDeclaringClass().getName(), e);
            }
            try {
                method.invoke(instance, helper);
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof RuntimeException runtime) throw runtime;
                throw new RuntimeException(e.getCause());
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Could not run the test method " + method, e);
            }
        };
    }
}
