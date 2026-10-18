package skillbill.infrastructure.host

import me.tatarka.inject.annotations.Inject
import skillbill.error.core.DatabaseFailureCode
import skillbill.error.core.SkillBillRuntimeException
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
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
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

private const val LINUX_START_TICKS_PREFIX = "linux-start-ticks:"
private const val START_TIME_INDEX_AFTER_COMM = 19

internal fun processBirthToken(
  handle: ProcessHandle,
  platform: HostPlatformPort,
  diagnostics: RuntimeDiagnostics,
): String? {
  if (!platform.osName.startsWith("Linux", ignoreCase = true)) {
    return handle.info().startInstant().orElse(null)?.toEpochMilli()?.toString()
  }
  val stat =
    try {
      Files.readString(Path.of("/proc/${handle.pid()}/stat"))
    } catch (failure: IOException) {
      diagnostics.warning(
        "seam=processBirthToken expected=kernel start ticks used=unavailable pid=${handle.pid()}",
        failure,
      )
      return null
    }
  val fields = stat.substringAfterLast(')', "").trim().split(Regex("\\s+"))
  val ticks = fields.getOrNull(START_TIME_INDEX_AFTER_COMM)?.toLongOrNull()
  if (ticks == null || ticks < 0) {
    diagnostics.warning(
      "seam=processBirthToken expected=kernel start ticks used=unavailable " +
        "cause=malformed_proc_stat pid=${handle.pid()}",
    )
    return null
  }
  return "$LINUX_START_TICKS_PREFIX$ticks"
}

internal fun processBirthMatches(
  handle: ProcessHandle,
  stored: String,
  platform: HostPlatformPort,
  diagnostics: RuntimeDiagnostics,
): Boolean? {
  if (stored.startsWith(LINUX_START_TICKS_PREFIX)) {
    return processBirthToken(handle, platform, diagnostics)?.let { it == stored }
  }
  val birth = handle.info().startInstant().orElse(null)?.toEpochMilli()?.toString() ?: return null
  return when {
    birth == stored -> true
    platform.osName.startsWith("Linux", ignoreCase = true) && stored.toLongOrNull() != null -> {
      diagnostics.warning(
        "seam=processBirthMatches expected=stable kernel start ticks " +
          "used=unverifiable_legacy_timestamp pid=${handle.pid()}",
      )
      null
    }
    else -> false
  }
}

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

internal fun inspectRecordedProcess(
  recorded: FeatureTaskRuntimeProcessIdentity,
  local: FeatureTaskRuntimeProcessIdentity,
  platform: HostPlatformPort,
  diagnostics: RuntimeDiagnostics,
): FeatureTaskRuntimeProcessInspection {
  if (bootIdentitiesConflict(recorded.bootIdentity, local.bootIdentity)) {
    return FeatureTaskRuntimeProcessInspection.NotRunning
  }
  val handle =
    ProcessHandle.of(recorded.pid).orElse(null)?.takeIf { it.isAlive }
      ?: return FeatureTaskRuntimeProcessInspection.NotRunning
  val matches = processBirthMatches(handle, recorded.processBirthToken, platform, diagnostics)
  return when {
    matches == null ->
      FeatureTaskRuntimeProcessInspection.Unsupported("Worker PID has no verifiable birth evidence.")
    matches -> FeatureTaskRuntimeProcessInspection.ExactLive
    else -> FeatureTaskRuntimeProcessInspection.PidReused
  }
}

internal fun currentProcessIdentity(
  platform: HostPlatformPort,
  diagnostics: RuntimeDiagnostics,
): FeatureTaskRuntimeProcessIdentity {
  val handle = ProcessHandle.current()
  return FeatureTaskRuntimeProcessIdentity(
    hostIdentity = InetAddress.getLocalHost().hostName,
    bootIdentity = processBootIdentity(diagnostics),
    pid = handle.pid(),
    processBirthToken =
      processBirthToken(handle, platform, diagnostics)
        ?: error("The current process does not expose process-birth evidence."),
  )
}

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
        if (error.code !in DATABASE_RETRYABLE_FAILURES) throw error
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
