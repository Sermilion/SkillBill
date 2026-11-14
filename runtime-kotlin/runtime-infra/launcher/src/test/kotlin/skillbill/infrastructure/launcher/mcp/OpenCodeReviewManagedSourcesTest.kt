package skillbill.infrastructure.launcher.mcp

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OpenCodeReviewManagedSourcesTest {
  @Test
  fun `dangling managed ancestor refuses admission instead of looking absent`() {
    val root = Files.createTempDirectory("opencode-managed-source-test-").toRealPath()
    try {
      val managedParent = root.resolve("managed")
      val source = managedParent.resolve("opencode")
      assertNull(managedSourceRefusal(listOf(source)))
      Files.createSymbolicLink(managedParent, root.resolve("missing-policy-root"))
      assertNotNull(managedSourceRefusal(listOf(source)))
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test
  fun `managed policy appearing after preparation refuses the spawn recheck`() {
    val root = Files.createTempDirectory("opencode-managed-source-test-").toRealPath()
    try {
      val source = root.resolve("opencode")
      assertNull(managedSourceRefusal(listOf(source)))
      Files.createDirectory(source)
      assertNotNull(managedSourceRefusal(listOf(source)))
    } finally {
      root.toFile().deleteRecursively()
    }
  }
}
