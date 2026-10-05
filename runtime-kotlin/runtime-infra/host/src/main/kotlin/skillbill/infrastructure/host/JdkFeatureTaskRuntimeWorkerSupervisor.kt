package skillbill.infrastructure.host

import me.tatarka.inject.annotations.Inject
import skillbill.error.core.DatabaseFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.taskruntime.FeatureTaskRuntimeHeartbeat
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatPlan
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatTick
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import java.io.IOException
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException

private const val INIT_PID: Long = 1L
private const val BOOT_IDENTITY_UNAVAILABLE: String = "boot-identity-unavailable"

private const val LEGACY_BOOT_IDENTITY_PREFIX: String = "fallback-"

private fun bootIdentitiesConflict(
  stored: String,
  local: String,
): Boolean = bootIdentityIsDecisive(stored) && bootIdentityIsDecisive(local) && stored != local

private fun bootIdentityIsDecisive(value: String): Boolean =
  value.isNotBlank() &&
    value != BOOT_IDENTITY_UNAVAILABLE &&
    !value.startsWith(LEGACY_BOOT_IDENTITY_PREFIX)

internal fun processBirthToken(handle: ProcessHandle): String? =
  handle.info().startInstant().orElse(null)?.toEpochMilli()?.toString()

internal fun processBootIdentity(diagnostics: RuntimeDiagnostics): String {
  val linuxBootId = Path.of("/proc/sys/kernel/random/boot_id")
  if (Files.isReadable(linuxBootId)) {
    runCatching { Files.readString(linuxBootId).trim() }
      .getOrNull()
      ?.takeIf(String::isNotBlank)
      ?.let { return it }
  }
  runCatching { ProcessHandle.of(INIT_PID).flatMap { it.info().startInstant() }.orElse(null) }
    .getOrNull()
    ?.let { return "boot-${it.toEpochMilli()}" }
  diagnostics.warning(
    "seam=processBootIdentity expected=kernel boot id or init-process birth " +
      "used=$BOOT_IDENTITY_UNAVAILABLE. This platform exposes neither; process-birth evidence remains " +
      "the reused-pid guard.",
  )
  return BOOT_IDENTITY_UNAVAILABLE
}

@Inject
class JdkFeatureTaskRuntimeWorkerSupervisor(
  private val diagnostics: RuntimeDiagnostics,
) : FeatureTaskRuntimeWorkerSupervisor {
  override fun currentProcess(): FeatureTaskRuntimeProcessIdentity {
    val handle = ProcessHandle.current()
    return FeatureTaskRuntimeProcessIdentity(
      hostIdentity = InetAddress.getLocalHost().hostName,
      bootIdentity = processBootIdentity(diagnostics),
      pid = handle.pid(),
      processBirthToken =
        processBirthToken(handle)
          ?: error("The current process does not expose process-birth evidence."),
    )
  }

  override fun inspect(ownership: FeatureTaskRuntimeWorkerOwnership): FeatureTaskRuntimeProcessInspection =
    runCatching { currentProcess() }.fold(
      onSuccess = { local -> inspectLocalOwnership(ownership, local) },
      onFailure = {
        FeatureTaskRuntimeProcessInspection.Unsupported(it.message ?: "Local process identity is unavailable.")
      },
    )

  override fun awaitExit(
    ownership: FeatureTaskRuntimeWorkerOwnership,
    timeout: Duration,
  ) {
    require(!timeout.isNegative && !timeout.isZero) {
      "awaitExit timeout must be positive, got $timeout"
    }
    if (inspect(ownership) != FeatureTaskRuntimeProcessInspection.ExactLive) return
    val handle = ProcessHandle.of(ownership.pid).orElse(null) ?: return
    if (handle.pid() == ProcessHandle.current().pid()) return
    diagnostics.warning(
      "Waiting for existing goal runner pid=${ownership.pid} (duplicate-launch attach).",
    )
    try {
      handle.onExit().get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
      diagnostics.warning(
        "Timed out after ${timeout.toMillis()}ms waiting for existing goal runner " +
          "pid=${ownership.pid} (duplicate-launch attach).",
      )
    } catch (interrupted: InterruptedException) {
      Thread.currentThread().interrupt()
      throw interrupted
    }
  }

  private fun inspectLocalOwnership(
    ownership: FeatureTaskRuntimeWorkerOwnership,
    local: FeatureTaskRuntimeProcessIdentity,
  ): FeatureTaskRuntimeProcessInspection {
    if (ownership.hostIdentity != local.hostIdentity) {
      return FeatureTaskRuntimeProcessInspection.OwnershipMismatch(
        "Worker ownership belongs to a different host.",
      )
    }
    if (bootIdentitiesConflict(ownership.bootIdentity, local.bootIdentity)) {
      return FeatureTaskRuntimeProcessInspection.NotRunning
    }

    val handle =
      ProcessHandle.of(ownership.pid).orElse(null)
        ?: return FeatureTaskRuntimeProcessInspection.NotRunning
    val birth = processBirthToken(handle)
    return when {
      birth == null ->
        FeatureTaskRuntimeProcessInspection.Unsupported("Worker PID has no verifiable birth evidence.")
      handle.isAlive && birth == ownership.processBirthToken -> FeatureTaskRuntimeProcessInspection.ExactLive
      !handle.isAlive -> FeatureTaskRuntimeProcessInspection.NotRunning
      else -> FeatureTaskRuntimeProcessInspection.OwnershipMismatch("Worker PID was reused by a different process.")
    }
  }

  override fun terminateGracefully(ownership: FeatureTaskRuntimeWorkerOwnership): Boolean =
    exactHandle(ownership)?.destroy() ?: false

  override fun terminateForcibly(ownership: FeatureTaskRuntimeWorkerOwnership): Boolean =
    exactHandle(ownership)?.destroyForcibly() ?: false

  override fun startHeartbeat(
    plan: FeatureTaskRuntimeHeartbeatPlan,
    heartbeat: () -> FeatureTaskRuntimeHeartbeatTick,
  ): FeatureTaskRuntimeHeartbeat {
    val executor =
      Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "skill-bill-worker-heartbeat").apply { isDaemon = true }
      }
    return HeartbeatLoop(plan, heartbeat, diagnostics, executor).also(HeartbeatLoop::start)
  }

  override fun pause(durationMillis: Long) {
    Thread.sleep(durationMillis)
  }

  private fun exactHandle(ownership: FeatureTaskRuntimeWorkerOwnership): ProcessHandle? =
    if (inspect(ownership) == FeatureTaskRuntimeProcessInspection.ExactLive) {
      ProcessHandle.of(ownership.pid).orElse(null)
    } else {
      null
    }
}

private class HeartbeatLoop(
  private val plan: FeatureTaskRuntimeHeartbeatPlan,
  private val heartbeat: () -> FeatureTaskRuntimeHeartbeatTick,
  private val diagnostics: RuntimeDiagnostics,
  private val executor: ScheduledExecutorService,
) : FeatureTaskRuntimeHeartbeat {
  private val consecutiveFailures = AtomicInteger()
  private val lastRenewalNanos = AtomicLong(System.nanoTime())
  private val fencingLost = AtomicReference<String?>(null)

  fun start() = scheduleNext(plan.intervalSeconds)

  override fun stop() {
    executor.shutdownNow()
  }

  override fun fencingLostReason(): String? = fencingLost.get()

  private fun runTick() {
    val tick =
      try {
        heartbeat()
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (error: IOException) {
        reportFailure(error)
        scheduleNext(plan.retryDelaySeconds)
        return
      } catch (error: SkillBillRuntimeException) {
        if (error.code != DatabaseFailureCode.ACCESS && error.code != DatabaseFailureCode.BUSY) throw error
        reportFailure(error)
        scheduleNext(plan.retryDelaySeconds)
        return
      }
    consecutiveFailures.set(0)
    lastRenewalNanos.set(System.nanoTime())
    when (tick) {
      FeatureTaskRuntimeHeartbeatTick.Renewed -> scheduleNext(plan.intervalSeconds)
      is FeatureTaskRuntimeHeartbeatTick.FencingLost -> {
        fencingLost.set(tick.reason)
        diagnostics.error("Worker heartbeat for '${plan.label}' stopped renewing the lease: ${tick.reason}")
      }
    }
  }

  private fun scheduleNext(delaySeconds: Long) {
    runCatching { executor.schedule(::runTick, delaySeconds, TimeUnit.SECONDS) }
  }

  private fun reportFailure(error: Throwable) {
    val failures = consecutiveFailures.incrementAndGet()
    val staleSeconds = (System.nanoTime() - lastRenewalNanos.get()) / NANOS_PER_SECOND
    val detail =
      "Worker heartbeat tick failed for '${plan.label}': consecutive failures=$failures, " +
        "${staleSeconds}s since the last renewal, retrying in ${plan.retryDelaySeconds}s."
    if (staleSeconds >= plan.leaseSeconds) {
      diagnostics.error("$detail The lease has already expired and this worker now reads as idle.", error)
    } else {
      diagnostics.warning(detail, error)
    }
  }

  private companion object {
    const val NANOS_PER_SECOND = 1_000_000_000L
  }
}
