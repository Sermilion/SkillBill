package skillbill.infrastructure.host

import org.junit.jupiter.api.Assumptions.assumeTrue
import skillbill.infrastructure.host.jvm.JdkHostPlatformPort
import skillbill.infrastructure.host.process.processBirthToken
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Optional
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class RecordingDiagnostics : RuntimeDiagnostics {
  val warnings = CopyOnWriteArrayList<String>()
  val errors = CopyOnWriteArrayList<String>()

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
  ) {
    errors += message
  }
}

class JdkFeatureTaskRuntimeWorkerSupervisorTest {
  @Test
  fun `Linux owner identity ignores JVM start timestamp drift and still rejects different kernel births`() {
    assumeTrue(JdkHostPlatformPort.osName.startsWith("Linux", ignoreCase = true))
    val diagnostics = RecordingDiagnostics()
    val supervisor = JdkFeatureTaskRuntimeWorkerSupervisor(diagnostics, JdkHostPlatformPort)
    val current = supervisor.currentProcess()
    val handle = ProcessHandle.current()
    val shifted =
      object : ProcessHandle by handle {
        override fun info(): ProcessHandle.Info =
          object : ProcessHandle.Info by handle.info() {
            override fun startInstant(): Optional<Instant> =
              Optional.of(handle.info().startInstant().orElseThrow().plusSeconds(1))
          }
      }

    assertEquals(current.processBirthToken, processBirthToken(shifted, JdkHostPlatformPort, diagnostics))
    assertEquals(
      FeatureTaskRuntimeProcessInspection.ExactLive,
      supervisor.inspect(ownershipFor(current, current.bootIdentity)),
    )
    val differentTicks = current.processBirthToken.substringAfter(':').toLong() + 1
    val reused = ownershipFor(current, current.bootIdentity, "linux-start-ticks:$differentTicks")
    assertEquals(FeatureTaskRuntimeProcessInspection.PidReused, supervisor.inspect(reused))
    assertTrue(diagnostics.warnings.isEmpty())
  }

  @Test
  fun `worker from a previous boot on this host is not running`() {
    val supervisor = JdkFeatureTaskRuntimeWorkerSupervisor(RecordingDiagnostics(), JdkHostPlatformPort)
    val current = supervisor.currentProcess()
    val ownership =
      FeatureTaskRuntimeWorkerOwnership(
        workflowId = "wftr-test",
        generation = 1,
        ownerToken = "owner-token-0001",
        hostIdentity = current.hostIdentity,
        bootIdentity = "previous-${current.bootIdentity}",
        pid = current.pid,
        processBirthToken = current.processBirthToken,
        leaseState = FeatureTaskRuntimeWorkerLeaseState.ACTIVE,
        heartbeatAt = "2026-07-14T10:00:00Z",
        expiresAt = "2026-07-14T10:00:30Z",
        phaseId = "implement",
        phaseAttempt = 1,
      )

    assertEquals(FeatureTaskRuntimeProcessInspection.NotRunning, supervisor.inspect(ownership))
  }

  @Test
  fun `a live worker whose stored boot identity came from the drifting fallback still reads live`() {
    val supervisor = JdkFeatureTaskRuntimeWorkerSupervisor(RecordingDiagnostics(), JdkHostPlatformPort)
    val current = supervisor.currentProcess()

    val ownership = ownershipFor(current, bootIdentity = "fallback-1787579295")

    assertEquals(FeatureTaskRuntimeProcessInspection.ExactLive, supervisor.inspect(ownership))
  }

  @Test
  fun `a live worker with no known boot identity still reads live`() {
    val supervisor = JdkFeatureTaskRuntimeWorkerSupervisor(RecordingDiagnostics(), JdkHostPlatformPort)
    val current = supervisor.currentProcess()

    val ownership = ownershipFor(current, bootIdentity = "boot-identity-unavailable")

    assertEquals(FeatureTaskRuntimeProcessInspection.ExactLive, supervisor.inspect(ownership))
  }

  @Test
  fun `a live worker still reads live after the network renames the machine`() {
    val supervisor = JdkFeatureTaskRuntimeWorkerSupervisor(RecordingDiagnostics(), JdkHostPlatformPort)
    val current = supervisor.currentProcess()

    val ownership =
      ownershipFor(
        current,
        bootIdentity = current.bootIdentity,
        hostIdentity = "before-vpn-${current.hostIdentity}",
      )

    assertEquals(FeatureTaskRuntimeProcessInspection.ExactLive, supervisor.inspect(ownership))
  }

  @Test
  fun `a reused pid is still caught when the boot check abstains`() {
    val supervisor = JdkFeatureTaskRuntimeWorkerSupervisor(RecordingDiagnostics(), JdkHostPlatformPort)
    val current = supervisor.currentProcess()

    val ownership =
      ownershipFor(
        current,
        bootIdentity = "boot-identity-unavailable",
        processBirthToken = differentBirthToken(current.processBirthToken),
      )

    assertTrue(
      supervisor.inspect(ownership) is FeatureTaskRuntimeProcessInspection.PidReused,
      "a different process occupying the pid must not read as the original worker",
    )
  }

  @Test
  fun `boot identity is a property of the machine's boot, not of the calling process`() {
    val supervisor = JdkFeatureTaskRuntimeWorkerSupervisor(RecordingDiagnostics(), JdkHostPlatformPort)
    val kernelBootId = Path.of("/proc/sys/kernel/random/boot_id")
    val expected =
      if (Files.isReadable(kernelBootId)) {
        Files.readString(kernelBootId).trim()
      } else {
        "boot-" + ProcessHandle.of(1).flatMap { it.info().startInstant() }.orElseThrow().toEpochMilli()
      }

    assertEquals(expected, supervisor.currentProcess().bootIdentity)
    assertEquals(
      supervisor.currentProcess().bootIdentity,
      supervisor.currentProcess().bootIdentity,
      "a second reading in the same process must not drift",
    )
  }

  private fun differentBirthToken(token: String): String {
    val prefix = token.substringBeforeLast(':', "")
    val value = token.substringAfterLast(':').toLong() - 1
    return if (prefix.isEmpty()) value.toString() else "$prefix:$value"
  }

  private fun ownershipFor(
    current: FeatureTaskRuntimeProcessIdentity,
    bootIdentity: String,
    processBirthToken: String = current.processBirthToken,
    hostIdentity: String = current.hostIdentity,
  ) = FeatureTaskRuntimeWorkerOwnership(
    workflowId = "wftr-test",
    generation = 1,
    ownerToken = "owner-token-0001",
    hostIdentity = hostIdentity,
    bootIdentity = bootIdentity,
    pid = current.pid,
    processBirthToken = processBirthToken,
    leaseState = FeatureTaskRuntimeWorkerLeaseState.ACTIVE,
    heartbeatAt = "2026-07-14T10:00:00Z",
    expiresAt = "2026-07-14T10:00:30Z",
    phaseId = "implement",
    phaseAttempt = 1,
  )
}
