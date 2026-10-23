package com.jethuit.spw.listenstats.ui

import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.FieldVisitor
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.jar.asm.Type
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

/** Only reads class files. Discovery must never initialize the player or its services. */
data class HostMethod(val owner: String, val name: String, val descriptor: String) {
    val className: String get() = owner.replace('/', '.')
    val parameters: List<String> get() = Type.getArgumentTypes(descriptor).map { it.className }
    val returnType: String get() = Type.getReturnType(descriptor).descriptor
}

data class HostField(val owner: String, val name: String, val descriptor: String)
data class HostMethodBody(val method: HostMethod, val access: Int,
    val calls: List<HostMethod>, val fields: List<HostField>) {
    val isStatic: Boolean get() = access and Opcodes.ACC_STATIC != 0
    val isPublic: Boolean get() = access and Opcodes.ACC_PUBLIC != 0
}

class HostBytecodeIndex(val methods: List<HostMethodBody>, val fields: List<HostField>) {
    private val bodies = methods.associateBy { it.method }
    fun body(method: HostMethod): HostMethodBody? = bodies[method]

    companion object {
        fun read(source: Path): HostBytecodeIndex {
            val methods = mutableListOf<HostMethodBody>()
            val fields = mutableListOf<HostField>()
            fun parse(bytes: ByteArray) {
                check(!Thread.currentThread().isInterrupted) { "Host discovery cancelled" }
                val pool = bytes.toString(Charsets.ISO_8859_1)
                if (!pool.contains("androidx/compose/runtime/") &&
                    !pool.contains("androidx/navigation3/runtime/NavBackStack") &&
                    !pool.contains("androidx/navigation3/runtime/NavKey")) return
                ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
                    private lateinit var owner: String
                    override fun visit(version: Int, access: Int, name: String, signature: String?,
                        superName: String?, interfaces: Array<out String>?) { owner = name }
                    override fun visitField(access: Int, name: String, descriptor: String,
                        signature: String?, value: Any?): FieldVisitor? {
                        fields += HostField(owner, name, descriptor)
                        return null
                    }
                    override fun visitMethod(access: Int, name: String, descriptor: String,
                        signature: String?, exceptions: Array<out String>?): MethodVisitor {
                        val calls = mutableListOf<HostMethod>()
                        val reads = mutableListOf<HostField>()
                        methods += HostMethodBody(HostMethod(owner, name, descriptor), access, calls, reads)
                        return object : MethodVisitor(Opcodes.ASM9) {
                            override fun visitMethodInsn(opcode: Int, owner: String, name: String,
                                descriptor: String, isInterface: Boolean) {
                                calls += HostMethod(owner, name, descriptor)
                            }
                            override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
                                if (opcode == Opcodes.GETFIELD || opcode == Opcodes.GETSTATIC)
                                    reads += HostField(owner, name, descriptor)
                            }
                        }
                    }
                }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
            }
            if (Files.isDirectory(source)) {
                Files.walk(source).use { paths ->
                    paths.filter { it.toString().endsWith(".class") }.forEach { parse(Files.readAllBytes(it)) }
                }
            } else JarFile(source.toFile()).use { jar ->
                for (entry in jar.entries()) {
                    check(!Thread.currentThread().isInterrupted) { "Host discovery cancelled" }
                    if (!entry.isDirectory && entry.name.endsWith(".class") && !entry.name.startsWith("META-INF/versions/"))
                        jar.getInputStream(entry).use { parse(it.readBytes()) }
                }
            }
            return HostBytecodeIndex(methods, fields)
        }
    }
}
