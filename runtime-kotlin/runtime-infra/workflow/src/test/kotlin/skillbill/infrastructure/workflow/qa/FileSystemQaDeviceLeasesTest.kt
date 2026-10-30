package skillbill.infrastructure.workflow.qa

import skillbill.infrastructure.workflow.monitor.FakeMonitorProcessInspector
import skillbill.model.EnvironmentContext
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.qa.model.QaLeaseResult
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.telemetry.STATE_DIR_ENVIRONMENT_KEY
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FileSystemQaDeviceLeasesTest {
  private val repoRoot: Path = Files.createTempDirectory("skillbill-qa-lease").also(::gitInit)
  private val stateDir: Path = Files.createTempDirectory("skillbill-qa-lease-state")
  private val environment =
    EnvironmentContext(environment = mapOf(STATE_DIR_ENVIRONMENT_KEY to stateDir.toString()), userHome = stateDir)
  private val inspector = FakeMonitorProcessInspector(FeatureTaskRuntimeProcessInspection.ExactLive)
  private val warnings = mutableListOf<String>()
  private val diagnostics =
    object : RuntimeDiagnostics {
      override fun info(message: String) = Unit

      override fun warning(
        message: String,
        error: Throwable?,
      ) {
        warnings += message
      }

      override fun error(
        message: String,
        error: Throwable?,
      ) = Unit
    }
  private val leases = FileSystemQaDeviceLeases(inspector, Clock.systemUTC(), diagnostics, environment)
  private val leaseFile: Path

  init {
    val workspaces = FileSystemQaWorkspace(inspector, Clock.systemUTC(), environment)
    workspaces.open(repoRoot, FIRST)
    workspaces.open(repoRoot, SECOND)
    leaseFile = QaPaths.leaseFile(QaPaths.qaRoot(environment, repoRoot), DEVICE)
  }

  @AfterTest
  fun cleanUp() {
    repoRoot.toFile().deleteRecursively()
    stateDir.toFile().deleteRecursively()
  }

  @Test
  fun `a live foreign owner contends and leaves its lease file byte-identical`() {
    assertIs<QaLeaseResult.Acquired>(leases.acquire(repoRoot, DEVICE, FIRST))
    val before = Files.readAllBytes(leaseFile)

    val contended = assertIs<QaLeaseResult.Contended>(leases.acquire(repoRoot, DEVICE, SECOND))

    assertEquals(FIRST, contended.ownerInvocationId)
    assertContentEquals(before, Files.readAllBytes(leaseFile))
  }

  @Test
  fun `a lease whose owner process is confirmed dead is reclaimed with a record`() {
    assertIs<QaLeaseResult.Acquired>(leases.acquire(repoRoot, DEVICE, FIRST))
    inspector.inspection = FeatureTaskRuntimeProcessInspection.NotRunning

    val reclaimed = assertIs<QaLeaseResult.Acquired>(leases.acquire(repoRoot, DEVICE, SECOND))

    assertEquals(SECOND, reclaimed.lease.invocationId)
    assertTrue(warnings.single().contains("kind=stale_reclaim"), "stale reclaim must emit one record: $warnings")
  }

  private companion object {
    const val FIRST = "phr-first"
    const val SECOND = "phr-second"
    val DEVICE = "a".repeat(64)

    fun gitInit(directory: Path) {
      val process = ProcessBuilder("git", "init", "-q", directory.toString()).redirectErrorStream(true).start()
      check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "git init failed in $directory" }
    }
  }
}
