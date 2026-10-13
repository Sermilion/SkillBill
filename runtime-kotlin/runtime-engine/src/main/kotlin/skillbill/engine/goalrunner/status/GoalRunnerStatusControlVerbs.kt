package skillbill.engine.goalrunner.status

import me.tatarka.inject.annotations.Inject
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.goalrunner.execution.core.asWorkerOwnership
import skillbill.engine.goalrunner.goalRepositoryIdentity
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.model.GoalRunnerPauseResult
import skillbill.engine.goalrunner.model.GoalRunnerPauseStatus
import skillbill.engine.goalrunner.model.GoalRunnerResumeResult
import skillbill.engine.goalrunner.model.GoalRunnerResumeStatus
import skillbill.engine.goalrunner.model.GoalRunnerStopStatus
import skillbill.engine.goalrunner.model.GoalRunnerStopVerbResult
import skillbill.goalrunner.model.GOAL_PAUSE_REASON_OPERATOR_STOP
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.db.model.FencedTransactionResult
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.filesystem.model.DatabaseFileObservation
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.workflow.decomposition.isAtUnlaunchedBoundary
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CancellationException

private const val GRACEFUL_TERMINATION_WAIT_MILLIS: Long = 5_000

@Inject
class GoalRunnerStatusControlVerbs(
  private val manifestStore: GoalRunnerManifestStore,
  private val database: DatabaseSessionFactory,
  private val clock: Clock,
  private val workerSupervisor: FeatureTaskRuntimeWorkerSupervisor,
  private val repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
  private val diagnostics: RuntimeDiagnostics,
) {
  fun pause(
    issueKey: String,
    repoRoot: Path,
    expectedParentWorkflowId: String? = null,
  ): GoalRunnerPauseResult {
    val loaded =
      (
        if (expectedParentWorkflowId == null) {
          manifestStore.loadByIssueKey(issueKey, repoRoot)
        } else {
          manifestStore.loadDurableByIssueKey(issueKey, repoRoot)
            ?.takeIf { it.parentWorkflowId == expectedParentWorkflowId }
        }
      )
        ?: return GoalRunnerPauseResult(issueKey = issueKey, status = GoalRunnerPauseStatus.NOT_FOUND)
    val repositoryIdentity = goalRepositoryIdentity(repoRoot, repositoryEnclosingRootPort)
    manifestStore.bindRepositoryIdentity(loaded.parentWorkflowId, repositoryIdentity)
    val control =
      manifestStore.requestPause(loaded.parentWorkflowId)
        ?: return GoalRunnerPauseResult(issueKey = issueKey, status = GoalRunnerPauseStatus.NOT_FOUND)
    val effectiveControl =
      if (
        control.requiresPauseBoundary(loaded.manifest) && loaded.manifest.isAtUnlaunchedBoundary()
      ) {
        manifestStore.pauseAtBoundary(
          loaded.copy(controlState = control),
        ).controlState
      } else {
        control
      }
    return GoalRunnerPauseResult(
      issueKey = issueKey,
      parentWorkflowId = loaded.parentWorkflowId,
      status = if (effectiveControl.paused) GoalRunnerPauseStatus.PAUSED else GoalRunnerPauseStatus.REQUESTED,
      paused = effectiveControl.paused,
      pauseRequested = effectiveControl.pauseRequested,
      pauseReason = effectiveControl.pauseReason,
    )
  }

  fun stop(
    issueKey: String,
    repoRoot: Path,
    expectedParentWorkflowId: String? = null,
    databaseFileObservation: DatabaseFileObservation? = null,
    beforeAdmission: (() -> Boolean)? = null,
  ): GoalRunnerStopVerbResult {
    val result = reserveStop(issueKey, repoRoot, expectedParentWorkflowId, databaseFileObservation, beforeAdmission)
    val reservation =
      when (result) {
        FencedTransactionResult.ChangedTarget ->
          return GoalRunnerStopVerbResult(issueKey = issueKey, status = GoalRunnerStopStatus.CHANGED_TARGET)
        is FencedTransactionResult.Committed ->
          result.value
            ?: return GoalRunnerStopVerbResult(issueKey = issueKey, status = GoalRunnerStopStatus.NOT_FOUND)
      }

    fun outcome(
      status: GoalRunnerStopStatus,
      terminationAttempted: Boolean = false,
      terminationFailureCode: String? = null,
    ) = GoalRunnerStopVerbResult(
      issueKey = issueKey,
      status = status,
      parentWorkflowId = reservation.parentWorkflowId,
      pauseReason = reservation.pauseReason,
      pausedAt = reservation.pausedAt,
      terminationAttempted = terminationAttempted,
      terminationFailureCode = terminationFailureCode,
    )

    val lease = reservation.lease
    val noLiveLease = reservation.noLiveLeaseStatus()
    if (lease == null) return outcome(noLiveLease)
    val ownership = lease.asWorkerOwnership(reservation.parentWorkflowId)
    return runCatching {
      when (workerSupervisor.inspect(ownership)) {
        FeatureTaskRuntimeProcessInspection.ForeignHost -> outcome(GoalRunnerStopStatus.FOREIGN_HOST)
        FeatureTaskRuntimeProcessInspection.PidReused -> outcome(GoalRunnerStopStatus.PID_REUSED)
        is FeatureTaskRuntimeProcessInspection.Unsupported -> outcome(GoalRunnerStopStatus.UNINSPECTABLE)
        FeatureTaskRuntimeProcessInspection.NotRunning -> {
          outcome(
            if (releaseStoppedLease(reservation.parentWorkflowId, lease, databaseFileObservation)) {
              noLiveLease
            } else {
              GoalRunnerStopStatus.CHANGED_TARGET
            },
          )
        }
        FeatureTaskRuntimeProcessInspection.ExactLive -> {
          when (terminateOwner(ownership)) {
            TerminationOutcome.EXIT_CONFIRMED -> outcome(GoalRunnerStopStatus.STOPPED, terminationAttempted = true)
            TerminationOutcome.OWNERSHIP_CHANGED ->
              outcome(
                GoalRunnerStopStatus.IDENTITY_MISMATCH,
                terminationAttempted = true,
              )
            TerminationOutcome.STILL_LIVE ->
              outcome(
                GoalRunnerStopStatus.TERMINATION_INCOMPLETE,
                terminationAttempted = true,
                terminationFailureCode = "owner_still_live",
              )
          }
        }
      }
    }.getOrElse { failure ->
      rethrowCooperativeTerminationFailure(failure)
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "seam=goal-control.stop outcome=termination_incomplete cause=${failure.javaClass.simpleName}",
        null,
      )
      outcome(
        GoalRunnerStopStatus.TERMINATION_INCOMPLETE,
        terminationAttempted = true,
        terminationFailureCode = "termination_failed",
      )
    }
  }

  private fun rethrowCooperativeTerminationFailure(failure: Throwable) {
    when (failure) {
      is CancellationException -> throw failure
      is InterruptedException -> {
        Thread.currentThread().interrupt()
        throw failure
      }
    }
  }

  private fun releaseStoppedLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    observation: DatabaseFileObservation?,
  ): Boolean {
    val release = {
      manifestStore.releaseExecutionLease(parentWorkflowId, lease.ownerToken, lease.generation)
    }
    if (observation == null) {
      release()
      return true
    }
    return when (database.transactionFenced(observation) { release() }) {
      FencedTransactionResult.ChangedTarget -> false
      is FencedTransactionResult.Committed -> true
    }
  }

  private fun reserveStop(
    issueKey: String,
    repoRoot: Path,
    expectedParentWorkflowId: String?,
    databaseFileObservation: DatabaseFileObservation?,
    beforeAdmission: (() -> Boolean)?,
  ): FencedTransactionResult<StopReservation?> {
    val reserve: (UnitOfWork) -> StopReservation? = reserve@{
      if (beforeAdmission?.invoke() == false) return@reserve null
      val loaded =
        (
          if (expectedParentWorkflowId == null) {
            manifestStore.loadByIssueKey(issueKey, repoRoot)
          } else {
            manifestStore.loadDurableByIssueKey(issueKey, repoRoot)
              ?.takeIf { it.parentWorkflowId == expectedParentWorkflowId }
          }
        ) ?: return@reserve null
      manifestStore.bindRepositoryIdentity(
        loaded.parentWorkflowId,
        goalRepositoryIdentity(repoRoot, repositoryEnclosingRootPort),
      )
      val control =
        manifestStore.pauseNow(
          parentWorkflowId = loaded.parentWorkflowId,
          reason = GOAL_PAUSE_REASON_OPERATOR_STOP,
          pausedAt = clock.instant().toString(),
          overwriteExistingReason = true,
        ) ?: return@reserve null
      StopReservation(
        loaded.parentWorkflowId,
        control.pauseReason,
        control.pausedAt,
        loaded.controlState.paused && loaded.controlState.pauseReason == GOAL_PAUSE_REASON_OPERATOR_STOP,
        manifestStore.executionLease(loaded.parentWorkflowId),
      )
    }
    return if (databaseFileObservation == null) {
      FencedTransactionResult.Committed(database.transaction(reserve))
    } else {
      database.transactionFenced(databaseFileObservation, reserve)
    }
  }

  fun resume(
    issueKey: String,
    repoRoot: Path,
  ): GoalRunnerResumeResult {
    val loaded =
      manifestStore.loadByIssueKey(issueKey, repoRoot)
        ?: return GoalRunnerResumeResult(issueKey = issueKey, status = GoalRunnerResumeStatus.NOT_FOUND)
    manifestStore.bindRepositoryIdentity(
      loaded.parentWorkflowId,
      goalRepositoryIdentity(repoRoot, repositoryEnclosingRootPort),
    )
    val before = manifestStore.controlState(loaded.parentWorkflowId)
    if (!before.paused && !before.pauseRequested) {
      return GoalRunnerResumeResult(
        issueKey = issueKey,
        parentWorkflowId = loaded.parentWorkflowId,
        status = GoalRunnerResumeStatus.NOT_PAUSED,
      )
    }
    manifestStore.resume(loaded.parentWorkflowId)
      ?: return GoalRunnerResumeResult(issueKey = issueKey, status = GoalRunnerResumeStatus.NOT_FOUND)
    return GoalRunnerResumeResult(
      issueKey = issueKey,
      parentWorkflowId = loaded.parentWorkflowId,
      status = GoalRunnerResumeStatus.RESUMED,
      clearedPauseReason = before.pauseReason,
    )
  }

  private fun terminateOwner(ownership: FeatureTaskRuntimeWorkerOwnership): TerminationOutcome {
    if (workerSupervisor.inspect(ownership) != FeatureTaskRuntimeProcessInspection.ExactLive) {
      return if (workerSupervisor.inspect(ownership) == FeatureTaskRuntimeProcessInspection.NotRunning) {
        TerminationOutcome.EXIT_CONFIRMED
      } else {
        TerminationOutcome.OWNERSHIP_CHANGED
      }
    }
    workerSupervisor.terminateGracefully(ownership)
    workerSupervisor.awaitExit(ownership, Duration.ofMillis(GRACEFUL_TERMINATION_WAIT_MILLIS))
    when (workerSupervisor.inspect(ownership)) {
      FeatureTaskRuntimeProcessInspection.NotRunning -> return TerminationOutcome.EXIT_CONFIRMED
      FeatureTaskRuntimeProcessInspection.ExactLive -> Unit
      else -> return TerminationOutcome.OWNERSHIP_CHANGED
    }
    workerSupervisor.terminateForcibly(ownership)
    workerSupervisor.awaitExit(ownership, Duration.ofMillis(GRACEFUL_TERMINATION_WAIT_MILLIS))
    return when (workerSupervisor.inspect(ownership)) {
      FeatureTaskRuntimeProcessInspection.NotRunning -> TerminationOutcome.EXIT_CONFIRMED
      FeatureTaskRuntimeProcessInspection.ExactLive -> TerminationOutcome.STILL_LIVE
      else -> TerminationOutcome.OWNERSHIP_CHANGED
    }
  }

  private data class StopReservation(
    val parentWorkflowId: String,
    val pauseReason: String?,
    val pausedAt: String?,
    val alreadyStopped: Boolean,
    val lease: GoalRunnerExecutionLease?,
  ) {
    fun noLiveLeaseStatus(): GoalRunnerStopStatus =
      if (alreadyStopped) GoalRunnerStopStatus.ALREADY_STOPPED else GoalRunnerStopStatus.NO_LIVE_LEASE
  }

  private enum class TerminationOutcome { EXIT_CONFIRMED, STILL_LIVE, OWNERSHIP_CHANGED }
}
