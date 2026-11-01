import java.nio.file.*;
import net.bytebuddy.jar.asm.*;

/** Instruments a test-only class copy. The packaged class has no counters or timers. */
public class TrendChartProbe {
    public static final long[] calls = new long[6], nanos = new long[6], started = new long[6];
    public static int geometryPoints = -1;
    public static void enter(int slot) { calls[slot]++; started[slot] = System.nanoTime(); }
    public static void exit(int slot) { nanos[slot] += System.nanoTime() - started[slot]; }
    public static void reset() {
        java.util.Arrays.fill(calls, 0); java.util.Arrays.fill(nanos, 0); geometryPoints = -1;
    }
    public static void main(String[] args) throws Exception {
        ClassReader reader = new ClassReader(Files.readAllBytes(Path.of(args[0])));
        final boolean[] candidate = {false};
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            public MethodVisitor visitMethod(int a, String name, String d, String s, String[] e) {
                if (name.equals("buildTrendGeometry")) candidate[0] = true;
                return null;
            }
        }, ClassReader.SKIP_CODE);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        final int[] methods = {0};
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, desc, signature, exceptions);
                boolean drawing = desc.contains("Landroidx/compose/ui/graphics/drawscope/DrawScope;");
                int slot = name.equals("monotonePath") ? 0
                    : drawing && (!candidate[0] || name.endsWith("$drawBase")) ? 1
                    : name.equals("TrendChart") ? 2
                    : desc.contains("Landroidx/compose/foundation/layout/BoxWithConstraintsScope;") ? 3
                    : name.equals("buildTrendGeometry") ? 4
                    : drawing && name.endsWith("$drawMarker") ? 5 : -1;
                if (slot < 0) return delegate;
                methods[0]++;
                System.out.println("PROBE slot=" + slot + " method=" + name);
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    private void call(String method) {
                        super.visitLdcInsn(slot);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, "TrendChartProbe", method, "(I)V", false);
                    }
                    public void visitCode() {
                        super.visitCode(); call("enter");
                        if (slot == 0 || slot == 4) {
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I", true);
                            super.visitFieldInsn(Opcodes.PUTSTATIC, "TrendChartProbe", "geometryPoints", "I");
                        }
                    }
                    public void visitInsn(int opcode) {
                        if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) call("exit");
                        super.visitInsn(opcode);
                    }
                };
            }
        }, 0);
        if (methods[0] != (candidate[0] ? 6 : 4)) throw new AssertionError("Unexpected measured methods: " + methods[0]);
        Path target = Path.of(args[1]); Files.createDirectories(target.getParent());
        Files.write(target, writer.toByteArray());
    }
}
