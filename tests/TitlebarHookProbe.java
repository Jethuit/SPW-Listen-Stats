import java.lang.reflect.*;
import java.util.*;
import net.bytebuddy.jar.asm.*;
import com.jethuit.spw.listenstats.ui.HostUiTargets;

/** Read-only host compatibility check. Does not start SPW or install a hook. */
public final class TitlebarHookProbe {
    private static final String METHOD = "\u037f";
    private static final String COMPOSABLE = "(Landroidx/compose/runtime/Composer;I)V";

    public static void main(String[] args) throws Exception {
        boolean beforeQuick = Arrays.asList(args).contains("--before-quick");
        String version = args[0];
        HostUiTargets targets = HostUiTargets.Companion.forVersion(version);
        String target = beforeQuick ? targets.getDebugCaption() : targets.getQuickCaption();
        String caller = targets.getTitlebarCaller();
        // Exercise the actual resolver used by WorkshopHookRegistrar.afterComposable.
        // Its class loading uses initialize=false; no player/service is started.
        Class<?> resolver = Class.forName("com.xuncorp.voxzen.workshop.hooks.\u052a");
        Object instance = Arrays.stream(resolver.getDeclaredFields())
            .filter(f -> Modifier.isStatic(f.getModifiers()) && f.getType() == resolver)
            .findFirst().orElseThrow().get(null);
        Method resolve = Arrays.stream(resolver.getDeclaredMethods())
            .filter(m -> Modifier.isPublic(m.getModifiers()) && Arrays.equals(m.getParameterTypes(),
                new Class<?>[] {String.class, String.class, String.class, List.class}))
            .findFirst().orElseThrow();
        Object result;
        try {
            result = resolve.invoke(instance, target, METHOD, caller, List.of());
        } catch (InvocationTargetException failure) {
            throw new AssertionError("Host UI hook resolver rejected the candidate", failure.getCause());
        }
        if (result == null) throw new AssertionError("No hook target");
        System.out.println("PASS host UI hook resolver accepts the exact target/caller and zero business parameters");
        resolve.invoke(instance, "androidx.compose.ui.\u0559", "\u037f",
            targets.getPageCaller(), List.of("androidx.compose.ui.Modifier", "boolean", "kotlin.jvm.functions.Function3"));
        resolve.invoke(instance, "com.xuncorp.voxzen.ui.navigation.AppNavigationKt", "AppNavigation",
            targets.getPageCaller(), List.of());
        resolve.invoke(instance, "androidx.compose.animation.SharedTransitionScopeKt", "SharedTransitionLayout",
            "com.xuncorp.voxzen.ui.navigation.AppNavigationKt",
            List.of("androidx.compose.ui.Modifier", "kotlin.jvm.functions.Function3"));
        System.out.println("PASS SPW " + version + " containing Layer and nested navigation call sites resolve through the host resolver");

        Class<?> navigator = Class.forName("com.xuncorp.voxzen.ui.navigation.Navigator", false,
            TitlebarHookProbe.class.getClassLoader());
        Class<?> route = Class.forName("com.xuncorp.voxzen.ui.navigation.ScreenRoute", false,
            TitlebarHookProbe.class.getClassLoader());
        for (String name : List.of("navigate", "replace")) navigator.getDeclaredMethod(name, route);
        for (String name : List.of("back", "forward", "getNavBackStack")) navigator.getDeclaredMethod(name);
        Class.forName("com.xuncorp.voxzen.ui.navigation.AppNavigationKt")
            .getDeclaredMethod("getLocalNavigator").invoke(null);
        Class.forName("com.xuncorp.voxzen.ui.component.\u0529", false,
            TitlebarHookProbe.class.getClassLoader()).getDeclaredMethod(METHOD,
                kotlin.jvm.functions.Function0.class, String.class, androidx.compose.ui.Modifier.class,
                boolean.class, String.class, float.class, kotlin.jvm.functions.Function3.class,
                androidx.compose.runtime.Composer.class, int.class, int.class);
        try {
            HostUiTargets.Companion.forVersion("1.19.999");
            throw new AssertionError("Unknown host must not silently use stale call sites");
        } catch (IllegalStateException expected) { }
        System.out.println("PASS navigator, composition local, caption signature and unsupported-version guard");

        List<String> calls = new ArrayList<>();
        int[] matches = {0};
        int[] rows = {0};
        try (var stream = ClassLoader.getSystemResourceAsStream(caller.replace('.', '/') + ".class")) {
            if (stream == null) throw new AssertionError("Missing caller bytecode");
            new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                        String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode, String owner, String called,
                                String desc, boolean isInterface) {
                            if (owner.equals(target.replace('.', '/')) && called.equals(METHOD)
                                    && desc.equals(COMPOSABLE)) matches[0]++;
                            if (name.equals(METHOD) && descriptor.equals(COMPOSABLE)) {
                                if (owner.equals("androidx/compose/foundation/layout/RowKt")
                                        && called.equals("rowMeasurePolicy")) rows[0]++;
                                if (desc.equals(COMPOSABLE) && owner.startsWith("androidx/compose/ui/"))
                                    calls.add(owner);
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        if (matches[0] != 1) throw new AssertionError("Expected one call site, got " + matches[0]);
        int quick = calls.indexOf(targets.getQuickCaption().replace('.', '/'));
        int steam = calls.indexOf(targets.getSteamCaption().replace('.', '/'));
        if (rows[0] != 1 || quick < 0 || steam != quick + 1)
            throw new AssertionError("Caption row/order changed");
        if (beforeQuick && calls.indexOf(target.replace('.', '/')) != quick - 1)
            throw new AssertionError("DebugCaptionButton no longer immediately precedes QuickCaptionButton");
        System.out.println("PASS one matching call across the entire caller class, including extracted lambdas");
        System.out.println("PASS one caption Row; QuickCaptionButton immediately precedes optional SteamCaptionButton");
        if (beforeQuick)
            System.out.println("PASS after DebugCaptionButton places the candidate before Quick, independently of Steam visibility");
        System.out.println("LIMIT: registration/agent transformation, rendering, pointer hit testing and real-window behavior are not exercised");
    }
}
