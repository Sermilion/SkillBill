package skillbill.infrastructure.skills.install.nativeagent.inventory

import skillbill.error.core.SkillBillRuntimeException
import skillbill.infrastructure.contracts.sha256Hex
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class NativeAgentSelectedArtifactTest {
  @Test
  fun `deleted or retargeted managed worker cannot launch from a surviving cache record`() {
    val root = Files.createTempDirectory("native-worker-admission-test-").toRealPath()
    try {
      val content = "selected worker".toByteArray()
      val target = Files.write(root.resolve("worker.md"), content)
      val installed = Files.createSymbolicLink(root.resolve("installed.md"), target)
      val entry =
        NativeAgentLinkInventoryEntry(
          logicalName = "worker",
          provider = "opencode",
          installedPath = installed,
          cacheTargetPath = target,
          contentDigest = sha256Hex(content),
          sourceRoot = root,
        )
      assertContentEquals(content, NativeAgentLinkInventory.readSelectedArtifact(entry))
      Files.delete(installed)
      assertFailsWith<SkillBillRuntimeException> { NativeAgentLinkInventory.readSelectedArtifact(entry) }
      val replacement = Files.write(root.resolve("replacement.md"), content)
      Files.createSymbolicLink(installed, replacement)
      assertFailsWith<SkillBillRuntimeException> { NativeAgentLinkInventory.readSelectedArtifact(entry) }
    } finally {
      root.toFile().deleteRecursively()
    }
  }
}
