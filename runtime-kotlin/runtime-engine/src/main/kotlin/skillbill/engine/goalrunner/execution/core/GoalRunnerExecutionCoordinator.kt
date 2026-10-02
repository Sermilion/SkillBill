package skillbill.engine.goalrunner.execution.core

import me.tatarka.inject.annotations.Inject
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.model.GoalRunnerChildExecutionPlanAdmission
import skillbill.goalrunner.model.GOAL_PAUSE_REASON_RUNNER_INTERRUPTED
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.process.DaemonThreadPort
import skillbill.ports.process.IdentifierGeneratorPort
import skillbill.ports.process.ShutdownHookPort
import skillbill.ports.process.ShutdownHookRegistration
import skillbill.ports.taskruntime.FeatureTaskRuntimeHeartbeat
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatPlan
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatTick
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import java.time.Clock
import java.time.Duration

interface GoalRunnerExecutionCoordinator {
  fun <T> runOwned(
    parentWorkflowId: String,
    block: () -> T,
  ): GoalRunnerOwnedRun<T>

  fun <T> runOwnedWithChildAdmission(
    parentWorkflowId: String,
    childAdmission: GoalRunnerChildExecutionPlanAdmission,
    block: () -> T,
  ): GoalRunnerOwnedRun<T>
}

sealed interface GoalRunnerOwnedRun<out T> {
  data class Completed<T>(val value: T) : GoalRunnerOwnedRun<T>

  data class AlreadyRunning(val reason: String) : GoalRunnerOwnedRun<Nothing>
}

private fun alreadyRunningReason(
  parentWorkflowId: String,
  detail: String,
): String = "Goal parent '$parentWorkflowId' cannot start: $detail"

private sealed interface LeaseClaim {
  data class Reclaimable(val ownerToken: String) : LeaseClaim

  data class Blocked(val detail: String) : LeaseClaim
}

private sealed interface LeaseAcquisition {
  data class Acquired(val lease: GoalRunnerExecutionLease) : LeaseAcquisition

  data class AlreadyRunning(val reason: String) : LeaseAcquisition
}

fun GoalRunnerExecutionLease.asWorkerOwnership(parentWorkflowId: String) =
  FeatureTaskRuntimeWorkerOwnership(
    workflowId = parentWorkflowId,
    generation = generation,
    ownerToken = ownerToken,
    hostIdentity = hostIdentity,
    bootIdentity = bootIdentity,
    pid = pid,
    processBirthToken = processBirthToken,
    leaseState = FeatureTaskRuntimeWorkerLeaseState.ACTIVE,
    heartbeatAt = heartbeatAt.toString(),
    expiresAt = expiresAt.toString(),
    phaseId = "goal_runner",
    phaseAttempt = 1,
  )

@Inject
class DefaultGoalRunnerExecutionCoordinator(
  private val manifestStore: GoalRunnerManifestStore,
  private val supervisor: FeatureTaskRuntimeWorkerSupervisor,
  private val clock: Clock,
  private val shutdownHookPort: ShutdownHookPort,
  private val daemonThreadPort: DaemonThreadPort,
  private val identifierGeneratorPort: IdentifierGeneratorPort,
) : GoalRunnerExecutionCoordinator {
  override fun <T> runOwned(
    parentWorkflowId: String,
    block: () -> T,
  ): GoalRunnerOwnedRun<T> = runOwned(parentWorkflowId, null, block)

  override fun <T> runOwnedWithChildAdmission(
    parentWorkflowId: String,
    childAdmission: GoalRunnerChildExecutionPlanAdmission,
    block: () -> T,
  ): GoalRunnerOwnedRun<T> = runOwned(parentWorkflowId, childAdmission, block)

  private fun <T> runOwned(
    parentWorkflowId: String,
    childAdmission: GoalRunnerChildExecutionPlanAdmission?,
    block: () -> T,
  ): GoalRunnerOwnedRun<T> =
    when (val acquisition = acquireLease(parentWorkflowId, childAdmission)) {
      is LeaseAcquisition.AlreadyRunning -> GoalRunnerOwnedRun.AlreadyRunning(acquisition.reason)
      is LeaseAcquisition.Acquired -> runWithLease(parentWorkflowId, acquisition.lease, block)
    }

  private fun acquireLease(
    parentWorkflowId: String,
    childAdmission: GoalRunnerChildExecutionPlanAdmission?,
  ): LeaseAcquisition {
    val existing = manifestStore.executionLease(parentWorkflowId)
    val expectedOwnerToken =
      when (val claim = existing?.let { reclaimableOwnerToken(parentWorkflowId, it) }) {
        null -> null
        is LeaseClaim.Reclaimable -> claim.ownerToken
        is LeaseClaim.Blocked ->
          return LeaseAcquisition.AlreadyRunning(alreadyRunningReason(parentWorkflowId, claim.detail))
      }
    val lease = newLease(existing, supervisor.currentProcess())
    val acquired =
      if (childAdmission == null) {
        manifestStore.acquireExecutionLease(parentWorkflowId, lease, expectedOwnerToken)
      } else {
        manifestStore.acquireExecutionLeaseWithChildAdmission(
          parentWorkflowId,
          lease,
          expectedOwnerToken,
          childAdmission,
        )
      }
    if (!acquired) {
      return LeaseAcquisition.AlreadyRunning(
        alreadyRunningReason(
          parentWorkflowId,
          "another goal runner claimed the execution lease before this run could start",
        ),
      )
    }
    if (existing != null && leaseIsExpired(existing)) {
      clearStalePauseOrReleaseLease(parentWorkflowId, lease)
    }
    return LeaseAcquisition.Acquired(lease)
  }

  private fun <T> runWithLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    block: () -> T,
  ): GoalRunnerOwnedRun<T> {
    val plan =
      FeatureTaskRuntimeHeartbeatPlan(
        label = parentWorkflowId,
        intervalSeconds = HEARTBEAT_SECONDS,
        leaseSeconds = LEASE_DURATION.seconds,
      )
    val heartbeat = startHeartbeat(parentWorkflowId, lease, plan)
    val shutdownHookRegistration = registerShutdownHook(parentWorkflowId, lease, heartbeat)
    val bodyResult = executeBody(block)
    val teardownFailure = teardown(parentWorkflowId, lease, heartbeat, shutdownHookRegistration)
    return completeRun(parentWorkflowId, bodyResult, teardownFailure, heartbeat)
  }

  private fun <T> executeBody(block: () -> T): Result<T> =
    runCatching(block).also { it.exceptionOrNull()?.let(::preserveInterruption) }

  private fun teardown(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    heartbeat: FeatureTaskRuntimeHeartbeat,
    shutdownHookRegistration: ShutdownHookRegistration,
  ): Throwable? {
    var teardownFailure: Throwable? = null
    cleanupFailure {
      if (!shutdownHookRegistration.unregister()) {
        error("Goal parent '$parentWorkflowId' shutdown hook could not be unregistered.")
      }
    }?.let { teardownFailure = it }
    cleanupFailure { heartbeat.stop() }?.let { next ->
      teardownFailure = mergeTeardownFailure(teardownFailure, next)
    }
    cleanupFailure { releaseExecutionLease(parentWorkflowId, lease) }?.let { next ->
      teardownFailure = mergeTeardownFailure(teardownFailure, next)
    }
    return teardownFailure
  }

  private fun mergeTeardownFailure(
    existing: Throwable?,
    next: Throwable,
  ): Throwable = existing?.also { addSuppressedIfDistinct(it, next) } ?: next

  private fun <T> completeRun(
    parentWorkflowId: String,
    bodyResult: Result<T>,
    teardownFailure: Throwable?,
    heartbeat: FeatureTaskRuntimeHeartbeat,
  ): GoalRunnerOwnedRun<T> {
    val bodyFailure = bodyResult.exceptionOrNull()
    if (bodyFailure != null) {
      teardownFailure?.let { secondary -> addSuppressedIfDistinct(bodyFailure, secondary) }
      throw bodyFailure
    }
    heartbeat.fencingLostReason()?.let { reason ->
      return GoalRunnerOwnedRun.AlreadyRunning(alreadyRunningReason(parentWorkflowId, reason))
    }
    teardownFailure?.let { throw it }
    return GoalRunnerOwnedRun.Completed(bodyResult.getOrThrow())
  }

  private fun startHeartbeat(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    plan: FeatureTaskRuntimeHeartbeatPlan,
  ): FeatureTaskRuntimeHeartbeat {
    return runCatching {
      supervisor.startHeartbeat(plan) {
        val now = clock.instant()
        val updated = lease.copy(heartbeatAt = now, expiresAt = now.plus(LEASE_DURATION))
        if (manifestStore.heartbeatExecutionLease(parentWorkflowId, updated)) {
          FeatureTaskRuntimeHeartbeatTick.Renewed
        } else {
          FeatureTaskRuntimeHeartbeatTick.FencingLost(
            "goal parent '$parentWorkflowId' execution lease fencing was lost",
          )
        }
      }
    }.getOrElse { failure ->
      preserveInterruption(failure)
      runCatching {
        releaseExecutionLease(parentWorkflowId, lease)
      }.onFailure { cleanupFailure ->
        preserveInterruption(cleanupFailure)
        addSuppressedIfDistinct(failure, cleanupFailure)
      }
      throw failure
    }
  }

  private fun registerShutdownHook(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    heartbeat: FeatureTaskRuntimeHeartbeat,
  ): ShutdownHookRegistration {
    return runCatching {
      shutdownHookPort.register {
        recordInterruption(parentWorkflowId)
      }
    }.getOrElse { failure ->
      preserveInterruption(failure)
      cleanupFailure { heartbeat.stop() }?.let { failure.addSuppressed(it) }
      runCatching {
        releaseExecutionLease(parentWorkflowId, lease)
      }.onFailure { cleanupFailure ->
        preserveInterruption(cleanupFailure)
        addSuppressedIfDistinct(failure, cleanupFailure)
      }
      throw failure
    }
  }

  private fun clearStalePauseOrReleaseLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
  ) {
    val failure =
      runCatching {
        clearStaleRunnerInterruptedPause(parentWorkflowId)
      }.exceptionOrNull()
    if (failure != null) {
      preserveInterruption(failure)
      runCatching {
        releaseExecutionLease(parentWorkflowId, lease)
      }.onFailure { cleanupFailure ->
        preserveInterruption(cleanupFailure)
        addSuppressedIfDistinct(failure, cleanupFailure)
      }
      throw failure
    }
  }

  private fun releaseExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
  ) {
    if (!manifestStore.releaseExecutionLease(parentWorkflowId, lease.ownerToken, lease.generation)) {
      error("Goal parent '$parentWorkflowId' execution lease release lost fencing.")
    }
  }

  private fun cleanupFailure(action: () -> Unit): Throwable? =
    runCatching(action).exceptionOrNull()?.also(::preserveInterruption)

  private fun preserveInterruption(failure: Throwable) {
    if (failure is InterruptedException) {
      Thread.currentThread().interrupt()
    }
  }

  private fun addSuppressedIfDistinct(
    primary: Throwable,
    secondary: Throwable,
  ) {
    if (primary !== secondary) {
      primary.addSuppressed(secondary)
    }
  }

  fun recordInterruption(parentWorkflowId: String) {
    daemonThreadPort.runWithJoinBudget(
      action = {
        runCatching {
          manifestStore.pauseNow(
            parentWorkflowId = parentWorkflowId,
            reason = GOAL_PAUSE_REASON_RUNNER_INTERRUPTED,
            pausedAt = clock.instant().toString(),
            overwriteExistingReason = false,
          )
        }
      },
      joinBudgetMillis = SHUTDOWN_WRITE_BUDGET.toMillis(),
    )
  }

  private fun leaseIsExpired(lease: GoalRunnerExecutionLease): Boolean =
    !lease.expiresAtInstant.isAfter(clock.instant())

  private fun clearStaleRunnerInterruptedPause(parentWorkflowId: String) {
    manifestStore.clearRunnerInterruptedPause(parentWorkflowId)
  }

  private fun reclaimableOwnerToken(
    parentWorkflowId: String,
    existing: GoalRunnerExecutionLease,
  ): LeaseClaim {
    if (leaseIsExpired(existing)) return LeaseClaim.Reclaimable(existing.ownerToken)
    val ownership = existing.asWorkerOwnership(parentWorkflowId)
    return when (supervisor.inspect(ownership)) {
      FeatureTaskRuntimeProcessInspection.NotRunning -> LeaseClaim.Reclaimable(existing.ownerToken)
      FeatureTaskRuntimeProcessInspection.ExactLive -> reclaimAfterLiveOwner(existing, ownership)
      is FeatureTaskRuntimeProcessInspection.OwnershipMismatch -> blockedClaim(OWNER_AMBIGUOUS)
      is FeatureTaskRuntimeProcessInspection.Unsupported -> blockedClaim(OWNER_UNINSPECTABLE)
    }
  }

  private fun reclaimAfterLiveOwner(
    existing: GoalRunnerExecutionLease,
    ownership: FeatureTaskRuntimeWorkerOwnership,
  ): LeaseClaim {
    if (isCurrentProcess(existing)) {
      return blockedClaim("this process already owns the execution lease")
    }
    if (!isDuplicateLaunchRace(existing)) {
      return blockedClaim(OWNER_LIVE)
    }
    supervisor.awaitExit(ownership, DUPLICATE_LAUNCH_WINDOW)
    return when (supervisor.inspect(ownership)) {
      FeatureTaskRuntimeProcessInspection.NotRunning -> LeaseClaim.Reclaimable(existing.ownerToken)
      FeatureTaskRuntimeProcessInspection.ExactLive -> blockedClaim(OWNER_LIVE)
      is FeatureTaskRuntimeProcessInspection.OwnershipMismatch -> blockedClaim(OWNER_AMBIGUOUS)
      is FeatureTaskRuntimeProcessInspection.Unsupported -> blockedClaim(OWNER_UNINSPECTABLE)
    }
  }

  private fun isCurrentProcess(existing: GoalRunnerExecutionLease): Boolean {
    val current = supervisor.currentProcess()
    return existing.pid == current.pid && existing.processBirthToken == current.processBirthToken
  }

  private fun isDuplicateLaunchRace(existing: GoalRunnerExecutionLease): Boolean {
    val ownerStartMs = existing.processBirthToken.toLongOrNull() ?: return false
    val ageMs = clock.millis() - ownerStartMs
    return ageMs in 0 until DUPLICATE_LAUNCH_WINDOW.toMillis()
  }

  private fun blockedClaim(detail: String): LeaseClaim = LeaseClaim.Blocked(detail)

  private fun newLease(
    existing: GoalRunnerExecutionLease?,
    process: FeatureTaskRuntimeProcessIdentity,
  ): GoalRunnerExecutionLease {
    val now = clock.instant()
    return GoalRunnerExecutionLease(
      generation = (existing?.generation ?: 0) + 1,
      ownerToken = identifierGeneratorPort.randomToken(),
      hostIdentity = process.hostIdentity,
      bootIdentity = process.bootIdentity,
      pid = process.pid,
      processBirthToken = process.processBirthToken,
      heartbeatAt = now,
      expiresAt = now.plus(LEASE_DURATION),
    )
  }

  private companion object {
    val LEASE_DURATION: Duration = Duration.ofSeconds(30)
    val DUPLICATE_LAUNCH_WINDOW: Duration = Duration.ofSeconds(60)
    const val HEARTBEAT_SECONDS: Long = 10
    const val OWNER_AMBIGUOUS = "the existing process owner is ambiguous"
    const val OWNER_UNINSPECTABLE = "the existing process owner cannot be inspected"
    const val OWNER_LIVE = "another goal runner process is live"

    val SHUTDOWN_WRITE_BUDGET: Duration = Duration.ofSeconds(2)
  }
}
