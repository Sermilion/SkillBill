package skillbill.infrastructure.skills.install

import org.junit.jupiter.api.io.TempDir
import skillbill.infrastructure.skills.install.symlink.retireObsoleteDispatcherLinks
import skillbill.install.model.SupportedAgent
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InstallOwnedLegacyDispatcherCleanupTest {
  @Test
  fun `legacy cleanup removes only a dispatcher link into the managed cache`(
    @TempDir tempDir: Path,
  ) {
    val targetDir = Files.createDirectory(tempDir.resolve("agents"))
    val cacheRoot = Files.createDirectory(tempDir.resolve("cache"))
    val managedTarget = Files.createDirectory(cacheRoot.resolve("skill-bill"))
    val legacyLink = targetDir.resolve("skill-bill")
    Files.createSymbolicLink(legacyLink, managedTarget)
    val retired = retireObsoleteDispatcherLinks(targetDir, false, cacheRoot, SupportedAgent.CODEX)

    assertEquals(mapOf(legacyLink to managedTarget), retired.rawTargets)
    assertTrue(retired.issues.isEmpty())
    assertTrue(Files.isDirectory(managedTarget))

    Files.createDirectory(legacyLink)
    Files.createFile(legacyLink.resolve(".skill-bill-install"))
    val preserved = retireObsoleteDispatcherLinks(targetDir, false, cacheRoot, SupportedAgent.CODEX)

    assertTrue(preserved.rawTargets.isEmpty())
    assertEquals(legacyLink.toString(), preserved.issues.single().path.toString())
    assertTrue(Files.isRegularFile(legacyLink.resolve(".skill-bill-install")))
  }
}
