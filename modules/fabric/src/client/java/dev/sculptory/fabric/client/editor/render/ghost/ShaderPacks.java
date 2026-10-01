package dev.sculptory.fabric.client.editor.render.ghost;

import dev.sculptory.fabric.SculptoryMod;
import java.lang.reflect.Method;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Whether a shader pack is in use. Ghost meshes are drawn with vanilla's terrain programs, which shader packs
 * replace, so with one active every ghost section falls back to an outline.
 *
 * <p>Iris is not a build dependency: its public API ({@code net.irisshaders.iris.api.v0.IrisApi.getInstance()
 * .isShaderPackInUse()}) is looked up by reflection when the {@code iris} mod is loaded. If Iris is loaded but the
 * lookup fails, a shader pack is assumed. Render thread only.
 */
final class ShaderPacks {
    private static final String IRIS_API = "net.irisshaders.iris.api.v0.IrisApi";

    private static boolean resolved;
    private static boolean irisLoaded;
    private static Object irisApi;
    private static Method isShaderPackInUse;

    private ShaderPacks() {}

    static boolean inUse() {
        if (!resolved) {
            resolve();
        }
        if (!irisLoaded) {
            return false;
        }
        if (isShaderPackInUse == null) {
            return true;
        }
        try {
            return Boolean.TRUE.equals(isShaderPackInUse.invoke(irisApi));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            SculptoryMod.LOG.warn("Sculptory: could not ask Iris about shader packs; ghost previews use outlines", failure);
            isShaderPackInUse = null;
            return true;
        }
    }

    private static void resolve() {
        resolved = true;
        irisLoaded = FabricLoader.getInstance().isModLoaded("iris");
        if (!irisLoaded) {
            return;
        }
        try {
            Class<?> api = Class.forName(IRIS_API);
            irisApi = api.getMethod("getInstance").invoke(null);
            isShaderPackInUse = api.getMethod("isShaderPackInUse");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            SculptoryMod.LOG.warn("Sculptory: Iris is loaded but its API was not found; ghost previews use outlines", failure);
            isShaderPackInUse = null;
        }
    }
}
