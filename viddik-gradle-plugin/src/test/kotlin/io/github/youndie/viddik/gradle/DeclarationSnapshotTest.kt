package io.github.youndie.viddik.gradle

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The snapshot is what KSP sees of the main classes. It must not move when only a body moves —
 * that is the whole point — and it must move when a declaration does, or KSP would generate from a
 * stale classpath.
 */
class DeclarationSnapshotTest {
    @Test
    fun `a body edit that shifts FunctionKeyMeta offsets and line numbers changes nothing`() {
        val before = facade(literal = "Payments", startOffset = 3701, line = 97)
        val after = facade(literal = "Payment", startOffset = 3700, line = 98)
        assertFalse(before.contentEquals(after), "the fixture classes themselves must differ")

        assertContentEquals(snapshot(before), snapshot(after))
    }

    @Test
    fun `a new public function changes the snapshot`() {
        val before = facade(literal = "Payments", startOffset = 3701, line = 97)
        val after = facade(literal = "Payments", startOffset = 3701, line = 97, extraFunction = true)

        assertFalse(snapshot(before).contentEquals(snapshot(after)))
    }

    @Test
    fun `a changed constant changes the snapshot`() {
        val before = facade(literal = "Payments", startOffset = 3701, line = 97, constant = 360)
        val after = facade(literal = "Payments", startOffset = 3701, line = 97, constant = 400)

        assertFalse(snapshot(before).contentEquals(snapshot(after)))
    }

    @Test
    fun `synthetic and function-local classes are left out`() {
        val synthetic = ClassWriter(0)
        synthetic.visit(Opcodes.V17, Opcodes.ACC_FINAL or Opcodes.ACC_SYNTHETIC, "bench/Synthetic", null, OBJECT, null)
        synthetic.visitEnd()
        assertNull(DeclarationSnapshot.of(synthetic.toByteArray()))

        val local = ClassWriter(0)
        local.visit(Opcodes.V17, Opcodes.ACC_FINAL, "bench/TemplatesKt\$ListScreen\$1", null, OBJECT, null)
        local.visitOuterClass("bench/TemplatesKt", "ListScreen", "(I)V")
        local.visitEnd()
        assertNull(DeclarationSnapshot.of(local.toByteArray()))
    }

    @Test
    fun `the snapshot is a well-formed class whose concrete methods still have a body`() {
        val bytes = snapshot(facade(literal = "Payments", startOffset = 3701, line = 97))
        var bodies = 0
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String?,
                    descriptor: String?,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor =
                    object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitCode() {
                            bodies++
                        }
                    }
            },
            0,
        )
        assertEquals(1, bodies, "ListScreen keeps a body; the synthetic lambda is gone")
    }

    @Test
    fun `a file that is not a class passes through so kotlin_module keeps top-level lookups working`() {
        val module = byteArrayOf(0, 0, 0, 3, 1, 2, 3)
        assertContentEquals(module, DeclarationSnapshot.ofEntry("META-INF/components.kotlin_module", module))

        val facade = facade(literal = "Payments", startOffset = 3701, line = 97)
        assertContentEquals(snapshot(facade), DeclarationSnapshot.ofEntry("bench/TemplatesKt.class", facade))
    }

    private fun snapshot(bytes: ByteArray): ByteArray = assertNotNull(DeclarationSnapshot.of(bytes))

    /**
     * A file facade shaped like what the Compose compiler writes for `ListScreen(seed: Int)`: the
     * string the body uses, the source offset in `@FunctionKeyMeta`, a line number, and a synthetic
     * lambda method numbered by position.
     */
    private fun facade(
        literal: String,
        startOffset: Int,
        line: Int,
        constant: Int = 360,
        extraFunction: Boolean = false,
    ): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, "bench/TemplatesKt", null, OBJECT, null)
        cw.visitSource("Templates.kt", "SMAP\nTemplates.kt\n*L\n1#1,$line:1\n*E\n")
        cw.visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, "WIDTH", "I", null, constant)
        cw.visitInnerClass("bench/TemplatesKt\$ListScreen\$1", null, null, Opcodes.ACC_FINAL)

        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "ListScreen", "(I)V", null, null)
        mv.visitAnnotation(FUNCTION_KEY_META, false).apply {
            visit("key", 194572958)
            visit("startOffset", startOffset)
            visit("endOffset", startOffset + 299)
            visitEnd()
        }
        mv.visitCode()
        val start = Label()
        mv.visitLabel(start)
        mv.visitLineNumber(line, start)
        mv.visitLdcInsn(literal)
        mv.visitInsn(Opcodes.POP)
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()

        val lambda =
            cw.visitMethod(
                Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC,
                "ListScreen\$lambda\$${line % 3}",
                "()V",
                null,
                null,
            )
        lambda.visitCode()
        lambda.visitInsn(Opcodes.RETURN)
        lambda.visitMaxs(0, 0)
        lambda.visitEnd()

        if (extraFunction) {
            val access = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_ABSTRACT
            cw.visitMethod(access, "SettingsScreen", "(I)V", null, null).visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private companion object {
        const val OBJECT = "java/lang/Object"
        const val FUNCTION_KEY_META = "Landroidx/compose/runtime/internal/FunctionKeyMeta;"
    }
}
