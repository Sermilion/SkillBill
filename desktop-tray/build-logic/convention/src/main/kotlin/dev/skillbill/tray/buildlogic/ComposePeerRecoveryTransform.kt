package dev.skillbill.tray.buildlogic

import org.gradle.api.artifacts.transform.CacheableTransform
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@CacheableTransform
abstract class ComposePeerRecoveryTransform : TransformAction<TransformParameters.None> {
  @get:InputArtifact
  @get:PathSensitive(PathSensitivity.NAME_ONLY)
  abstract val inputArtifact: Provider<FileSystemLocation>

  override fun transform(outputs: TransformOutputs) {
    val input = inputArtifact.get().asFile
    if (!input.name.startsWith("ui-desktop-")) {
      outputs.file(input)
      return
    }
    check(input.name == "ui-desktop-1.12.1.jar") {
      "Review the Compose native-peer recovery patch before changing Compose: ${input.name}"
    }
    var patchedEntries = 0
    ZipFile(input).use { source ->
      ZipOutputStream(outputs.file("ui-desktop-1.12.1-peer-recovery.jar").outputStream()).use { target ->
        source.entries().asSequence().forEach { entry ->
          target.putNextEntry(ZipEntry(entry.name).apply { time = 0 })
          val bytes = source.getInputStream(entry).use { it.readBytes() }
          target.write(if (entry.name == "${ComposePeerRecoveryPatch.LAYER}.class") {
            patchedEntries++
            ComposePeerRecoveryPatch.apply(bytes)
          } else bytes)
          target.closeEntry()
        }
      }
    }
    check(patchedEntries == 1) { "Compose native-peer recovery target is missing from ${input.name}" }
  }
}

internal object ComposePeerRecoveryPatch {
  const val OWNER = "androidx/compose/ui/scene/skia/SurfaceSkiaLayerComponent"
  const val LAYER = "$OWNER\$hierarchyRoot\$1"

  fun apply(bytes: ByteArray): ByteArray {
    val reader = ClassReader(bytes)
    check(reader.className == LAYER) { "Unexpected Compose native-peer recovery class: ${reader.className}" }
    val writer = ClassWriter(reader, ClassWriter.COMPUTE_MAXS)
    var patchedReturns = 0
    reader.accept(object : ClassVisitor(Opcodes.ASM9, writer) {
      override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
        val delegate = super.visitMethod(access, name, descriptor, signature, exceptions)
        if (name != "addNotify" || descriptor != "()V") return delegate
        return object : MethodVisitor(Opcodes.ASM9, delegate) {
          override fun visitInsn(opcode: Int) {
            if (opcode == Opcodes.RETURN) {
              patchedReturns++
              visitVarInsn(Opcodes.ALOAD, 0)
              visitFieldInsn(Opcodes.GETFIELD, LAYER, "this\$0", "L$OWNER;")
              visitInsn(Opcodes.ICONST_0)
              visitMethodInsn(Opcodes.INVOKESTATIC, OWNER, "access\$setDisposed\$p", "(L$OWNER;Z)V", false)
            }
            super.visitInsn(opcode)
          }
        }
      }
    }, 0)
    check(patchedReturns == 1) { "Compose addNotify no longer matches the native-peer recovery patch" }
    return writer.toByteArray()
  }
}
