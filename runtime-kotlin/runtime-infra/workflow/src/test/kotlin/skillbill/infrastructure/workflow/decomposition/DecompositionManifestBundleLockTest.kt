package skillbill.infrastructure.workflow.decomposition

import org.junit.jupiter.api.io.TempDir
import skillbill.infrastructure.host.jvm.JdkHostPlatformPort
import skillbill.infrastructure.host.jvm.RuntimeTempEnvironmentKeys
import skillbill.ports.system.HostPlatformPort
import skillbill.telemetry.STATE_DIR_ENVIRONMENT_KEY
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DecompositionManifestBundleLockTest {
  @TempDir
  lateinit var workspace: Path

  private val parent: Path get() = workspace.resolve("bundle")

  private fun port(
    tempRoot: Path,
    environment: Map<String, String>,
  ): HostPlatformPort =
    object : HostPlatformPort by JdkHostPlatformPort {
      override fun resolveEnvironment(): Map<String, String> = environment

      override fun resolveUserHome(): Path = workspace.resolve("home")

      override fun resolveTemporaryDirectory(): Path = tempRoot
    }

  private fun lockFileHeldBy(
    hostPlatform: HostPlatformPort,
    lockRoot: Path,
  ): Path = withDecompositionManifestBundleLock(parent, hostPlatform) { lockRoot.listDirectoryEntries().single() }

  @Test
  fun `runs with different temp roots hold one lock file under state`() {
    val state = workspace.resolve("state")
    val environment = mapOf(STATE_DIR_ENVIRONMENT_KEY to state.toString())
    val lockRoot = state.resolve("locks").resolve("skill-bill-decomposition-manifest-locks")

    val first = lockFileHeldBy(port(workspace.resolve("run-a"), environment), lockRoot)
    val second = lockFileHeldBy(port(workspace.resolve("run-b"), environment), lockRoot)

    assertEquals(first, second)
    assertTrue(first.fileName.toString().endsWith(".lock"))
  }

  @Test
  fun `the lock directory override replaces the state location`() {
    val state = workspace.resolve("state")
    val lockRoot = workspace.resolve("worker-locks")
    val environment =
      mapOf(
        STATE_DIR_ENVIRONMENT_KEY to state.toString(),
        RuntimeTempEnvironmentKeys.SKILL_BILL_LOCK_DIR to lockRoot.toString(),
      )

    lockFileHeldBy(port(workspace.resolve("run-a"), environment), lockRoot)

    assertTrue(Files.notExists(state.resolve("locks")))
  }
}
