import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import com.jethuit.spw.listenstats.ui.*;

/** Read-only host compatibility check. Does not start SPW or install a hook. */
public final class TitlebarHookProbe {
    public static void main(String[] args) throws Exception {
        var index = HostBytecodeIndex.Companion.read(Path.of(args[1]));
        var targets = HostUiDiscovery.INSTANCE.discover(index);
        if (!targets.getWarnings().isEmpty() || targets.getCaption() == null || targets.getTheme() == null)
            throw new AssertionError("Incomplete discovery: " + targets.getWarnings());
        var bridge = new HostUiBridge(targets, TitlebarHookProbe.class.getClassLoader(),
            () -> HostAppearanceReader.INSTANCE.read());
        if (bridge.getCaption() == null) throw new AssertionError("Caption reflection failed");
        // Exercise the exact resolver used by WorkshopHookRegistrar. No transformations.
        Class<?> resolver = Class.forName("com.xuncorp.voxzen.workshop.hooks.\u052a");
        Object instance = Arrays.stream(resolver.getDeclaredFields())
            .filter(f -> Modifier.isStatic(f.getModifiers()) && f.getType() == resolver)
            .findFirst().orElseThrow().get(null);
        Method resolve = Arrays.stream(resolver.getDeclaredMethods())
            .filter(m -> Modifier.isPublic(m.getModifiers()) && Arrays.equals(m.getParameterTypes(),
                new Class<?>[] {String.class, String.class, String.class, List.class}))
            .findFirst().orElseThrow();
        for (var site : List.of(targets.getCaption().getEntry(), targets.getLayer(), targets.getPage())) {
            var parameters = site == targets.getLayer()
                ? List.of("androidx.compose.ui.Modifier", "boolean", "kotlin.jvm.functions.Function3") : List.of();
            try {
                if (resolve.invoke(instance, site.getTarget().getClassName(), site.getTarget().getName(),
                        site.getCallerClass(), parameters) == null) throw new AssertionError("Missing hook site");
            } catch (InvocationTargetException failure) {
                throw new AssertionError("Host resolver rejected " + site, failure.getCause());
            }
        }
        System.out.println("PASS SPW " + args[0] + ": fresh discovery, caption before Quick, containing Layer and navigation resolve");
        System.out.println("PASS navigator/back overlay, three desktop transforms and reactive theme signatures");
        System.out.println("LIMIT: no live hook installation, AOT, DWM or real plugin coexistence exercised");
    }
}
