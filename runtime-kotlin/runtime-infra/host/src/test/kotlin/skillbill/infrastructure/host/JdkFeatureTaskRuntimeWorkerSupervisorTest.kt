package skillbill.infrastructure.host

import org.junit.jupiter.api.Assumptions.assumeTrue
import skillbill.error.core.DatabaseFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.infrastructure.host.jvm.JdkHostPlatformPort
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatPlan
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatTick
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class RecordingDiagnostics : RuntimeDiagnostics {
  val warnings = CopyOnWriteArrayList<String>()
  val errors = CopyOnWriteArrayList<String>()

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
  fun `a drifting legacy Linux timestamp cannot mark a live owner dead or authorize its termination`() {
    assumeTrue(JdkHostPlatformPort.osName.startsWith("Linux", ignoreCase = true))
    val diagnostics = RecordingDiagnostics()
    val supervisor = JdkFeatureTaskRuntimeWorkerSupervisor(diagnostics, JdkHostPlatformPort)
    val current = supervisor.currentProcess()
    val child = ProcessBuilder("sleep", "30").start()
    val legacyBirth = child.toHandle().info().startInstant().orElseThrow().toEpochMilli()
    val exactLegacy = ownershipFor(current, current.bootIdentity, legacyBirth.toString()).copy(pid = child.pid())
    val driftingLegacy = exactLegacy.copy(processBirthToken = (legacyBirth + 1000).toString())
    val events = AtomicInteger()
    try {
      assertEquals(FeatureTaskRuntimeProcessInspection.ExactLive, supervisor.inspect(exactLegacy))
      assertIs<FeatureTaskRuntimeProcessInspection.Unsupported>(supervisor.inspect(driftingLegacy))
      assertNull(supervisor.observe(driftingLegacy) { events.incrementAndGet() })
      assertEquals(0, events.get())
      assertNull(supervisor.processStartedAt(driftingLegacy))
      assertEquals(false, supervisor.terminateGracefully(driftingLegacy))
      assertEquals(false, supervisor.terminateForcibly(driftingLegacy))
      assertTrue(child.isAlive)
      assertTrue(diagnostics.warnings.any { it.contains("unverifiable_legacy_timestamp") })
    } finally {
      child.destroyForcibly()
      assertTrue(child.waitFor(5, TimeUnit.SECONDS))
      assertEquals(FeatureTaskRuntimeProcessInspection.NotRunning, supervisor.inspect(driftingLegacy))
    }
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
  fun `owner exit delivers an event without heartbeat or database writes`() {
    val supervisor = JdkFeatureTaskRuntimeWorkerSupervisor(RecordingDiagnostics(), JdkHostPlatformPort)
    val current = supervisor.currentProcess()
    val child = ProcessBuilder("sleep", "30").start()
    val ownership =
      ownershipFor(current, current.bootIdentity).copy(
        pid = child.pid(),
        processBirthToken = processBirthToken(child.toHandle(), JdkHostPlatformPort, RecordingDiagnostics())!!,
      )
    val exited = CountDownLatch(1)
    val observation = supervisor.observe(ownership) { exited.countDown() }
    try {
      assertEquals(FeatureTaskRuntimeProcessInspection.ExactLive, supervisor.inspect(ownership))
      child.destroyForcibly()
      assertTrue(exited.await(5, TimeUnit.SECONDS))
      assertEquals(FeatureTaskRuntimeProcessInspection.NotRunning, supervisor.inspect(ownership))
    } finally {
      observation?.close()
      child.destroyForcibly()
    }
  }

  @Test
  fun `owner death before exit registration still delivers the invalidation`() {
    val supervisor = JdkFeatureTaskRuntimeWorkerSupervisor(RecordingDiagnostics(), JdkHostPlatformPort)
    val current = supervisor.currentProcess()
    val child = ProcessBuilder("sleep", "30").start()
    val ownership =
      ownershipFor(current, current.bootIdentity).copy(
        pid = child.pid(),
        processBirthToken = processBirthToken(child.toHandle(), JdkHostPlatformPort, RecordingDiagnostics())!!,
      )
    child.destroyForcibly()
    assertTrue(child.waitFor(5, TimeUnit.SECONDS))
    val events = AtomicInteger()
    assertNull(supervisor.observe(ownership) { events.incrementAndGet() })
    assertEquals(1, events.get())
    val reused = ownershipFor(current, current.bootIdentity, differentBirthToken(current.processBirthToken))
    assertNull(supervisor.observe(reused) { events.incrementAndGet() })
    assertEquals(2, events.get())
  }

  @Test
  fun `awaitExit returns after a live owned process exits`() {
    val supervisor = JdkFeatureTaskRuntimeWorkerSupervisor(RecordingDiagnostics(), JdkHostPlatformPort)
    val current = supervisor.currentProcess()
    val child = ProcessBuilder("sleep", "1").start()
    val childHandle = child.toHandle()
    val birth = processBirthToken(childHandle, JdkHostPlatformPort, RecordingDiagnostics())!!
    val ownership =
      FeatureTaskRuntimeWorkerOwnership(
        workflowId = "wftr-test",
        generation = 1,
        ownerToken = "owner-token-0001",
        hostIdentity = current.hostIdentity,
        bootIdentity = current.bootIdentity,
        pid = child.pid(),
        processBirthToken = birth,
        leaseState = FeatureTaskRuntimeWorkerLeaseState.ACTIVE,
        heartbeatAt = "2026-07-14T10:00:00Z",
        expiresAt = "2026-07-14T10:00:30Z",
        phaseId = "goal_runner",
        phaseAttempt = 1,
      )
    try {
      assertEquals(FeatureTaskRuntimeProcessInspection.ExactLive, supervisor.inspect(ownership))
      supervisor.awaitExit(ownership, Duration.ofSeconds(5))
      assertEquals(FeatureTaskRuntimeProcessInspection.NotRunning, supervisor.inspect(ownership))
    } finally {
      child.destroyForcibly()
    }
  }

  @Test
  fun `awaitExit returns on timeout while the owned process is still live`() {
    val diagnostics = RecordingDiagnostics()
    val supervisor = JdkFeatureTaskRuntimeWorkerSupervisor(diagnostics, JdkHostPlatformPort)
    val current = supervisor.currentProcess()
    val child = ProcessBuilder("sleep", "30").start()
    val childHandle = child.toHandle()
    val birth = processBirthToken(childHandle, JdkHostPlatformPort, RecordingDiagnostics())!!
    val ownership =
      FeatureTaskRuntimeWorkerOwnership(
        workflowId = "wftr-test",
        generation = 1,
        ownerToken = "owner-token-0001",
        hostIdentity = current.hostIdentity,
        bootIdentity = current.bootIdentity,
        pid = child.pid(),
        processBirthToken = birth,
        leaseState = FeatureTaskRuntimeWorkerLeaseState.ACTIVE,
        heartbeatAt = "2026-07-14T10:00:00Z",
        expiresAt = "2026-07-14T10:00:30Z",
        phaseId = "goal_runner",
        phaseAttempt = 1,
      )
    try {
      assertEquals(FeatureTaskRuntimeProcessInspection.ExactLive, supervisor.inspect(ownership))
      supervisor.awaitExit(ownership, Duration.ofMillis(200))
      assertEquals(FeatureTaskRuntimeProcessInspection.ExactLive, supervisor.inspect(ownership))
      assertTrue(
        diagnostics.warnings.any { it.contains("Timed out") && it.contains("pid=${ownership.pid}") },
        "timeout must be observable: ${diagnostics.warnings}",
      )
    } finally {
      child.destroyForcibly()
    }
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

  @Test
  fun `a throwing tick is absorbed and lease renewal keeps ticking`() {
    val diagnostics = RecordingDiagnostics()
    val supervisor = JdkFeatureTaskRuntimeWorkerSupervisor(diagnostics, JdkHostPlatformPort)
    val ticks = AtomicInteger()
    val renewedAfterFailure = CountDownLatch(2)

    val heartbeat =
      supervisor.startHeartbeat(plan()) {
        if (ticks.incrementAndGet() == 1) {
          throw SkillBillRuntimeException(DatabaseFailureCode.BUSY, "database is locked")
        }
        renewedAfterFailure.countDown()
        FeatureTaskRuntimeHeartbeatTick.Renewed
      }

    try {
      assertTrue(
        renewedAfterFailure.await(TICK_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        "a tick that threw must not end lease renewal, but only $ticks ticks ran",
      )
    } finally {
      heartbeat.stop()
    }
    assertNull(heartbeat.fencingLostReason(), "a transient failure is not a fencing loss")
    val warning = assertSingle(diagnostics.warnings)
    assertTrue(warning.contains("wftr-label"), "the warning must name the lease: $warning")
    assertTrue(warning.contains("consecutive failures=1"), "the warning must count failures: $warning")
    assertTrue(diagnostics.errors.isEmpty(), "a single transient failure must not escalate")
  }

  @Test
  fun `renewal failures that outlast the lease escalate to an error`() {
    val diagnostics = RecordingDiagnostics()
    val supervisor = JdkFeatureTaskRuntimeWorkerSupervisor(diagnostics, JdkHostPlatformPort)
    val escalated = CountDownLatch(1)

    val heartbeat =
      supervisor.startHeartbeat(plan(leaseSeconds = 0)) {
        escalated.countDown()
        throw SkillBillRuntimeException(DatabaseFailureCode.BUSY, "database is locked")
      }

    try {
      assertTrue(escalated.await(TICK_TIMEOUT_SECONDS, TimeUnit.SECONDS), "the first tick never ran")
      awaitUntil { diagnostics.errors.isNotEmpty() }
    } finally {
      heartbeat.stop()
    }
    assertTrue(
      diagnostics.errors.first().contains("lease has already expired"),
      "a failure window past the lease must escalate: ${diagnostics.errors.first()}",
    )
  }

  @Test
  fun `lost fencing stops renewal and is reported to the run owner`() {
    val diagnostics = RecordingDiagnostics()
    val supervisor = JdkFeatureTaskRuntimeWorkerSupervisor(diagnostics, JdkHostPlatformPort)
    val ticks = AtomicInteger()
    val observed = CountDownLatch(1)

    val heartbeat =
      supervisor.startHeartbeat(plan()) {
        ticks.incrementAndGet()
        observed.countDown()
        FeatureTaskRuntimeHeartbeatTick.FencingLost("another owner holds the lease")
      }

    try {
      assertTrue(observed.await(TICK_TIMEOUT_SECONDS, TimeUnit.SECONDS), "the first tick never ran")
      awaitUntil { diagnostics.errors.isNotEmpty() }
      Thread.sleep(QUIESCE_MILLIS)
      assertEquals(1, ticks.get(), "renewal must stop after fencing is lost")
    } finally {
      heartbeat.stop()
    }
    assertEquals("another owner holds the lease", heartbeat.fencingLostReason())
    assertTrue(
      assertSingle(diagnostics.errors).contains("another owner holds the lease"),
      "the run owner must be told why renewal stopped",
    )
  }

  private fun plan(leaseSeconds: Long = 30) =
    FeatureTaskRuntimeHeartbeatPlan(
      label = "wftr-label",
      intervalSeconds = 1,
      leaseSeconds = leaseSeconds,
      retryDelaySeconds = 1,
    )

  private fun <T> assertSingle(values: List<T>): T {
    assertEquals(1, values.size, "expected exactly one entry, got $values")
    return values.first()
  }

  private fun awaitUntil(condition: () -> Boolean) {
    val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(TICK_TIMEOUT_SECONDS)
    while (!condition() && System.nanoTime() < deadlineNanos) {
      Thread.sleep(POLL_MILLIS)
    }
    assertTrue(condition(), "condition never became true within ${TICK_TIMEOUT_SECONDS}s")
  }

  private companion object {
    const val TICK_TIMEOUT_SECONDS = 20L
    const val QUIESCE_MILLIS = 2_500L
    const val POLL_MILLIS = 25L
  }
}
