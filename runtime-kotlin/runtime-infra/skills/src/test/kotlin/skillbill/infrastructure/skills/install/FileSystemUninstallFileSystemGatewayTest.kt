package skillbill.infrastructure.skills.install

import org.junit.jupiter.api.io.TempDir
import skillbill.telemetry.CONFIG_ENVIRONMENT_KEY
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileSystemUninstallFileSystemGatewayTest {
  @Test
  fun `state cleanup keeps the config lock file while removing the config`(
    @TempDir tempDir: Path,
  ) {
    val stateRoot = tempDir.resolve(".skill-bill")
    val config = stateRoot.resolve("config.json")
    val lockFile = config.resolveSibling("${config.fileName}.lock")
    Files.createDirectories(stateRoot)
    Files.writeString(config, "{}\n")
    val environment = mapOf(CONFIG_ENVIRONMENT_KEY to config.toString())
    val gateway = FileSystemUninstallFileSystemGateway()

    val removed =
      gateway.withConfigTransaction(tempDir, environment) {
        gateway.removeTreeExcept(stateRoot, gateway.configLockFilePaths(tempDir, environment))
      }

    assertTrue(removed.contains(config))
    assertFalse(Files.exists(config))
    assertTrue(Files.exists(lockFile))
    assertTrue(Files.isDirectory(stateRoot))
  }
}
