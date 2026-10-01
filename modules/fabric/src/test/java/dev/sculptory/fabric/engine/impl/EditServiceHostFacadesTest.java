package dev.sculptory.fabric.engine.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.engine.ClipboardService;
import dev.sculptory.fabric.engine.EditService;
import dev.sculptory.fabric.engine.PermissionService;
import dev.sculptory.fabric.engine.ScatterService;
import dev.sculptory.fabric.net.HistoryView;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The production services the network layer gets from {@link EditServiceHost} are facades over the running server's.
 * A facade that leaves an interface method to its default answers {@code DISABLED} (or nothing) on a running server
 * without anyone noticing, as the library management and palette methods once did: every method of every service
 * interface must be declared by its facade.
 */
class EditServiceHostFacadesTest {
    @Test
    void everyFacadeForwardsEveryMethodOfItsServices() {
        Map<Object, List<Class<?>>> facades = Map.of(
                EditServiceHost.service(), List.of(EditService.class, HistoryView.class),
                EditServiceHost.clipboards(), List.of(ClipboardService.class),
                EditServiceHost.scatter(), List.of(ScatterService.class),
                EditServiceHost.permissions(), List.of(PermissionService.class));
        List<String> missing = new ArrayList<>();
        int checked = 0;
        for (Map.Entry<Object, List<Class<?>>> facade : facades.entrySet()) {
            Class<?> type = facade.getKey().getClass();
            for (Class<?> service : facade.getValue()) {
                assertTrue(service.isInstance(facade.getKey()), type + " is a " + service.getSimpleName());
                for (Method method : service.getMethods()) {
                    if (Modifier.isStatic(method.getModifiers())) continue;
                    checked++;
                    String name = type.getSimpleName() + "." + method.getName() + " (" + service.getSimpleName() + ")";
                    try {
                        Method declared = type.getDeclaredMethod(method.getName(), method.getParameterTypes());
                        if (declared.isBridge() || declared.isSynthetic()) missing.add(name);
                    } catch (NoSuchMethodException e) {
                        missing.add(name);
                    }
                }
            }
        }
        assertTrue(checked >= 20, "the service interfaces were read: " + checked + " methods");
        assertEquals(List.of(), missing, "facade methods left to the interface's default");
    }
}
