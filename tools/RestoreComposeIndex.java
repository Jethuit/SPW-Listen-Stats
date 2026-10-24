import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.jetbrains.org.objectweb.asm.*;
import org.jetbrains.kotlin.metadata.deserialization.MetadataVersion;
import org.jetbrains.kotlin.metadata.jvm.JvmModuleProtoBuf;
import org.jetbrains.kotlin.metadata.jvm.deserialization.ModuleMappingKt;
import org.jetbrains.kotlin.metadata.jvm.deserialization.PackageParts;

/** Compile-only package index from the current host's own Kotlin annotations. */
public final class RestoreComposeIndex {
    public static void main(String[] args) throws Exception {
        Map<String, PackageParts> packages = new TreeMap<>();
        int[] count = {0};
        Set<String> foundationParts = Set.of(
            "androidx/compose/foundation/FocusableKt.class",
            "androidx/compose/foundation/gestures/TapGestureDetectorKt.class",
            "androidx/compose/foundation/lazy/LazyDslKt.class",
            "androidx/compose/foundation/lazy/LazyListStateKt.class",
            "androidx/compose/foundation/Scrollbar_desktopKt.class",
            "androidx/compose/foundation/v2/Scrollbar_desktopKt.class"
        );
        try (var host = new JarFile(args[0])) {
            for (var entry : Collections.list(host.entries())) {
                String path = entry.getName();
                if ((!path.startsWith("androidx/compose/runtime/") && !foundationParts.contains(path)) || !path.endsWith(".class") ||
                    path.startsWith("androidx/compose/runtime/saveable/") ||
                    path.startsWith("androidx/compose/runtime/retain/") ||
                    path.startsWith("androidx/compose/runtime/annotation/")) continue;
                String internalName = path.substring(0, path.length() - 6);
                new ClassReader(host.getInputStream(entry).readAllBytes()).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                        if (!descriptor.equals("Lkotlin/Metadata;")) return null;
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            int kind = 1;
                            String facade = null;
                            String packageName = internalName.substring(0, internalName.lastIndexOf('/')).replace('/', '.');
                            @Override public void visit(String name, Object value) {
                                if (name.equals("k")) kind = (Integer) value;
                                if (name.equals("xs")) facade = (String) value;
                                if (name.equals("pn") && !((String) value).isEmpty()) packageName = (String) value;
                            }
                            @Override public void visitEnd() {
                                if (kind == 2 || kind == 5) {
                                    packages.computeIfAbsent(packageName, PackageParts::new)
                                        .addPart(internalName, kind == 5 ? facade : null);
                                    count[0]++;
                                }
                            }
                        };
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
        }
        if (count[0] == 0) throw new IllegalStateException("Host Compose Kotlin metadata is unavailable");
        var module = JvmModuleProtoBuf.Module.newBuilder();
        packages.values().forEach(parts -> parts.addTo(module));
        try (var jar = new JarOutputStream(Files.newOutputStream(Path.of(args[1])))) {
            jar.putNextEntry(new JarEntry("META-INF/spw-compose-compile.kotlin_module"));
            jar.write(ModuleMappingKt.serializeToByteArray(module.build(), MetadataVersion.INSTANCE, 0));
            jar.closeEntry();
        }
        System.out.println("Compose compile index: " + count[0] + " package parts; no runtime classes bundled");
    }
}
