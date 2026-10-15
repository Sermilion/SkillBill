package dev.skillbill.tray.buildlogic

import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ComposePeerRecoveryPatchTest {
  @Test
  fun `an upstream lifecycle change fails instead of shipping an unpatched renderer`() {
    val writer = ClassWriter(0)
    writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, ComposePeerRecoveryPatch.LAYER, null, "java/lang/Object", null)
    writer.visitEnd()

    val failure = assertFailsWith<IllegalStateException> { ComposePeerRecoveryPatch.apply(writer.toByteArray()) }

    assertTrue(failure.message.orEmpty().contains("addNotify no longer matches"))
  }
}
