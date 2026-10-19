import java.lang.reflect.*;
import java.util.*;
import net.bytebuddy.jar.asm.*;

/** Read-only SPW 1.19.0 investigation. Does not start SPW or install a hook. */
public final class TitlebarHookProbe {
    private static final String TARGET = "androidx.compose.ui.\u04cc";
    private static final String CALLER = "androidx.compose.ui.\u0205";
    private static final String METHOD = "\u037f";
    private static final String COMPOSABLE = "(Landroidx/compose/runtime/Composer;I)V";

    public static void main(String[] args) throws Exception {
        boolean beforeQuick = Arrays.asList(args).contains("--before-quick");
        String target = beforeQuick ? "androidx.compose.ui.\u04f4" : TARGET;
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
            result = resolve.invoke(instance, target, METHOD, CALLER, List.of());
        } catch (InvocationTargetException failure) {
            throw new AssertionError("Host UI hook resolver rejected the candidate", failure.getCause());
        }
        if (result == null) throw new AssertionError("No hook target");
        System.out.println("PASS host UI hook resolver accepts the exact target/caller and zero business parameters");

        List<String> calls = new ArrayList<>();
        int[] matches = {0};
        int[] rows = {0};
        try (var stream = ClassLoader.getSystemResourceAsStream(CALLER.replace('.', '/') + ".class")) {
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
        int quick = calls.indexOf(TARGET.replace('.', '/'));
        int steam = calls.indexOf("androidx/compose/ui/\u04d6");
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
