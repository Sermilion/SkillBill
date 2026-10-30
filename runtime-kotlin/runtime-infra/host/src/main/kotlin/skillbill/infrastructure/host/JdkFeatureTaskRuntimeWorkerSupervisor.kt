package skillbill.infrastructure.host

import me.tatarka.inject.annotations.Inject
import skillbill.error.core.DatabaseFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.infrastructure.host.process.currentProcessIdentity
import skillbill.infrastructure.host.process.inspectRecordedProcess
import skillbill.infrastructure.host.process.processBirthMatches
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.system.HostPlatformPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeHeartbeat
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.WorkOwnerExitObserver
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatPlan
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatTick
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException

@Inject
class JdkFeatureTaskRuntimeWorkerSupervisor(
  private val diagnostics: RuntimeDiagnostics,
  private val platform: HostPlatformPort,
) : FeatureTaskRuntimeWorkerSupervisor, WorkOwnerExitObserver {
  override fun currentProcess(): FeatureTaskRuntimeProcessIdentity = currentProcessIdentity(platform, diagnostics)

  override fun inspect(ownership: FeatureTaskRuntimeWorkerOwnership): FeatureTaskRuntimeProcessInspection =
    runCatching { currentProcess() }.fold(
      onSuccess = { local -> inspectLocalOwnership(ownership, local) },
      onFailure = {
        FeatureTaskRuntimeProcessInspection.Unsupported(it.message ?: "Local process identity is unavailable.")
      },
    )

  override fun observe(
    ownership: FeatureTaskRuntimeWorkerOwnership,
    onExit: () -> Unit,
  ): AutoCloseable? {
    when (inspect(ownership)) {
      FeatureTaskRuntimeProcessInspection.NotRunning,
      FeatureTaskRuntimeProcessInspection.ForeignHost,
      FeatureTaskRuntimeProcessInspection.PidReused,
      -> {
        onExit()
        return null
      }
      is FeatureTaskRuntimeProcessInspection.Unsupported -> return null
      FeatureTaskRuntimeProcessInspection.ExactLive -> Unit
    }
    val handle = ProcessHandle.of(ownership.pid).orElse(null)
    if (handle == null || !handle.isAlive) {
      onExit()
      return null
    }
    return when (processBirthMatches(handle, ownership.processBirthToken, platform, diagnostics)) {
      false -> {
        onExit()
        null
      }
      null -> null
      true ->
        if (handle.pid() == ProcessHandle.current().pid()) {
          null
        } else {
          val completion = handle.onExit().thenRun(onExit)
          AutoCloseable { completion.cancel(false) }
        }
    }
  }

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

  override fun processStartedAt(ownership: FeatureTaskRuntimeWorkerOwnership): Instant? =
    exactHandle(ownership)?.info()?.startInstant()?.orElse(null)

  private fun inspectLocalOwnership(
    ownership: FeatureTaskRuntimeWorkerOwnership,
    local: FeatureTaskRuntimeProcessIdentity,
  ): FeatureTaskRuntimeProcessInspection =
    inspectRecordedProcess(
      FeatureTaskRuntimeProcessIdentity(
        ownership.hostIdentity,
        ownership.bootIdentity,
        ownership.pid,
        ownership.processBirthToken,
      ),
      local,
      platform,
      diagnostics,
    )

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

  private fun exactHandle(ownership: FeatureTaskRuntimeWorkerOwnership): ProcessHandle? {
    if (inspect(ownership) != FeatureTaskRuntimeProcessInspection.ExactLive) return null
    val handle = ProcessHandle.of(ownership.pid).orElse(null) ?: return null
    return handle.takeIf {
      it.isAlive && processBirthMatches(it, ownership.processBirthToken, platform, diagnostics) == true
    }
  }
}

private class HeartbeatLoop(
  private val plan: FeatureTaskRuntimeHeartbeatPlan,
  private val heartbeat: () -> FeatureTaskRuntimeHeartbeatTick,
  private val diagnostics: RuntimeDiagnostics,
  private val executor: ScheduledExecutorService,
) : FeatureTaskRuntimeHeartbeat {
  private val lifecycleLock = Any()
  private val consecutiveFailures = AtomicInteger()
  private val lastRenewalNanos = AtomicLong(System.nanoTime())
  private val fencingLost = AtomicReference<String?>(null)
  private var stopped = false

  fun start() = scheduleNext(plan.intervalSeconds)

  override fun stop() {
    synchronized(lifecycleLock) {
      stopped = true
      executor.shutdownNow()
    }
  }

  override fun fencingLostReason(): String? = fencingLost.get()

  private fun runTick() {
    synchronized(lifecycleLock) {
      if (stopped) return
      runTickWhileActive()
    }
  }

  private fun runTickWhileActive() {
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
        if (error.code !in DATABASE_RETRYABLE_FAILURES) throw error
        reportFailure(error)
        scheduleNext(plan.retryDelaySeconds)
        return
      }
    consecutiveFailures.set(0)
    lastRenewalNanos.set(System.nanoTime())
    when (tick) {
      FeatureTaskRuntimeHeartbeatTick.Renewed -> scheduleNext(plan.intervalSeconds)
      FeatureTaskRuntimeHeartbeatTick.WorkflowFinished -> Unit
      is FeatureTaskRuntimeHeartbeatTick.FencingLost -> {
        fencingLost.set(tick.reason)
        diagnostics.error("Worker heartbeat for '${plan.label}' stopped renewing the lease: ${tick.reason}")
      }
    }
  }

  private fun scheduleNext(delaySeconds: Long) {
    synchronized(lifecycleLock) {
      if (!stopped) runCatching { executor.schedule(::runTick, delaySeconds, TimeUnit.SECONDS) }
    }
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
    val DATABASE_RETRYABLE_FAILURES =
      setOf(
        DatabaseFailureCode.ACCESS,
        DatabaseFailureCode.BUSY,
        DatabaseFailureCode.MISSING,
        DatabaseFailureCode.INCOMPATIBLE_SCHEMA,
        DatabaseFailureCode.CORRUPT,
      )
  }
}
