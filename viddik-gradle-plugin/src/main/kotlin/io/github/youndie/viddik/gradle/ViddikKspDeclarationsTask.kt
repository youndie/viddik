package io.github.youndie.viddik.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.transform.CacheableTransform
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.zip.ZipFile

/**
 * Copies the main compilation's output with everything but the declarations taken out, for the test
 * source set's KSP run to read instead of the real classes.
 *
 * KSP re-runs whenever a class on its classpath changes, and with Compose almost every edit changes
 * one: the compiler puts `@FunctionKeyMeta(startOffset, endOffset)` on every composable, so retyping a
 * string literal moves the offsets of every composable after it in the file. KSP then marks every
 * fixture file that looks the class up as dirty and reprocesses it — on a module of 1000 fixtures,
 * ~0.8 s added to every edit-and-record cycle, for a registry that comes out byte-identical.
 *
 * The snapshot keeps what a processor can see — classes, signatures, fields and their constant
 * values, annotations, Kotlin metadata, resources such as `*.kotlin_module` — and drops method bodies,
 * debug information, `FunctionKeyMeta`, synthetic classes and members, and anonymous inner-class
 * entries. A file is rewritten only when its snapshot differs, so an edit that changes no declaration
 * leaves this task's output — and with it KSP's input — exactly as it was.
 */
@DisableCachingByDefault(
    because = "Reading the main classes is cheaper than a cache round trip, and the output is only useful in place.",
)
public abstract class ViddikKspDeclarationsTask : DefaultTask() {
    /** The main compilation's class directories. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:IgnoreEmptyDirectories
    public abstract val classes: ConfigurableFileCollection

    @get:OutputDirectory
    public abstract val outputDir: DirectoryProperty

    @TaskAction
    internal fun snapshot() {
        val out = outputDir.get().asFile
        val kept = mutableSetOf<String>()
        classes.asFileTree.visit { details ->
            if (details.isDirectory) return@visit
            val path = details.relativePath.pathString
            val snapshot = DeclarationSnapshot.ofEntry(path, details.file.readBytes())
            if (snapshot != null) {
                kept += path
                writeIfChanged(File(out, path), snapshot)
            }
        }
        out
            .walkBottomUp()
            .filter { it.isFile && it.relativeTo(out).invariantSeparatorsPath !in kept }
            .forEach { it.delete() }
    }

    private fun writeIfChanged(
        target: File,
        bytes: ByteArray,
    ) {
        if (target.isFile && target.readBytes().contentEquals(bytes)) return
        target.parentFile.mkdirs()
        target.writeBytes(bytes)
    }
}

/**
 * The same snapshot for a project dependency on the test compile classpath — a sibling module's jar
 * or class directory — for the layout where the fixtures sit in a module of their own and every
 * component they draw comes from elsewhere (issue #53).
 *
 * A transform rather than a task: it reads the artifact Gradle hands it and nothing of the module that
 * produced it, so it stays within what isolated projects allow; each artifact is snapshotted and
 * cached on its own; and a body edit in one module re-runs the transform for that module's jar only,
 * producing the same files as before, which is what keeps KSP's input unchanged.
 */
@CacheableTransform
public abstract class ViddikDeclarationsTransform : TransformAction<TransformParameters.None> {
    @get:InputArtifact
    @get:Classpath
    public abstract val artifact: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val input = artifact.get().asFile
        val out = outputs.dir(input.name.removeSuffix(".jar") + "-declarations")
        val write = { path: String, bytes: ByteArray ->
            DeclarationSnapshot.ofEntry(path, bytes)?.let { snapshot ->
                File(out, path).apply { parentFile.mkdirs() }.writeBytes(snapshot)
            }
        }
        if (input.isDirectory) {
            input
                .walkTopDown()
                .filter { it.isFile }
                .forEach { write(it.relativeTo(input).invariantSeparatorsPath, it.readBytes()) }
        } else if (input.isFile) {
            ZipFile(input).use { zip ->
                zip
                    .entries()
                    .asSequence()
                    .filter { !it.isDirectory && !it.name.startsWith(MANIFEST_DIR) }
                    .forEach { entry -> write(entry.name, zip.getInputStream(entry).use { it.readBytes() }) }
            }
        }
    }

    private companion object {
        // The jar manifest carries nothing a processor reads, and some builds stamp it per build.
        const val MANIFEST_DIR = "META-INF/MANIFEST"
    }
}

/** What a symbol processor can observe of a class file, and nothing that a body-only edit moves. */
internal object DeclarationSnapshot {
    private const val FUNCTION_KEY_META = "Landroidx/compose/runtime/internal/FunctionKeyMeta;"
    private const val NOT_IMPLEMENTED = Opcodes.ACC_ABSTRACT or Opcodes.ACC_NATIVE

    private const val CLASS_SUFFIX = ".class"

    /** A class file through [of]; any other file — `*.kotlin_module` among them — as it is. */
    fun ofEntry(
        path: String,
        bytes: ByteArray,
    ): ByteArray? = if (path.endsWith(CLASS_SUFFIX)) of(bytes) else bytes

    /**
     * The snapshot of [bytes], or `null` for a class no processor can refer to: a synthetic one, or
     * one declared inside a function (a local or anonymous class — lambdas compiled to classes among
     * them), whose name is a position and moves when another is added above it.
     */
    fun of(bytes: ByteArray): ByteArray? {
        val reader = ClassReader(bytes)
        if (reader.access and Opcodes.ACC_SYNTHETIC != 0 || declaredInsideAFunction(reader)) return null
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        reader.accept(Declarations(writer), ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG)
        return writer.toByteArray()
    }

    /** A local or anonymous class carries an EnclosingMethod attribute; nothing else does. */
    private fun declaredInsideAFunction(reader: ClassReader): Boolean {
        var enclosed = false
        reader.accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitOuterClass(
                    owner: String?,
                    name: String?,
                    descriptor: String?,
                ) {
                    enclosed = true
                }
            },
            ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
        return enclosed
    }

    private class Declarations(
        next: ClassVisitor,
    ) : ClassVisitor(Opcodes.ASM9, next) {
        // An anonymous class (a lambda compiled to a class, an object expression) has no name a
        // processor could look up, and its entry here shifts whenever one is added above it.
        override fun visitInnerClass(
            name: String?,
            outerName: String?,
            innerName: String?,
            access: Int,
        ) {
            if (innerName != null && access and Opcodes.ACC_SYNTHETIC == 0) {
                super.visitInnerClass(name, outerName, innerName, access)
            }
        }

        override fun visitMethod(
            access: Int,
            name: String?,
            descriptor: String?,
            signature: String?,
            exceptions: Array<out String>?,
        ): MethodVisitor? {
            // Lambda bodies (`foo$lambda$0`), bridges and accessors: numbered by position, invisible
            // to a processor.
            if (access and Opcodes.ACC_SYNTHETIC != 0) return null
            val method = super.visitMethod(access, name, descriptor, signature, exceptions) ?: return null
            return Signature(method, implemented = access and NOT_IMPLEMENTED == 0)
        }
    }

    private class Signature(
        next: MethodVisitor,
        private val implemented: Boolean,
    ) : MethodVisitor(Opcodes.ASM9, next) {
        override fun visitAnnotation(
            descriptor: String?,
            visible: Boolean,
        ) = if (descriptor == FUNCTION_KEY_META) null else super.visitAnnotation(descriptor, visible)

        // SKIP_CODE leaves a concrete method with no Code attribute, which a class file may not have.
        // `throw null` is the smallest body that keeps the file well-formed for whatever reads it.
        override fun visitEnd() {
            if (implemented) {
                super.visitCode()
                super.visitInsn(Opcodes.ACONST_NULL)
                super.visitInsn(Opcodes.ATHROW)
                super.visitMaxs(0, 0)
            }
            super.visitEnd()
        }
    }
}
