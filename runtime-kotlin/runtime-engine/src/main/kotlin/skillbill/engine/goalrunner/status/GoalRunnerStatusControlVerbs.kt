package skillbill.engine.goalrunner.status

import me.tatarka.inject.annotations.Inject
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
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.workflow.decomposition.isAtUnlaunchedBoundary
import java.nio.file.Path
import java.time.Clock

private const val GRACEFUL_TERMINATION_WAIT_MILLIS: Long = 5_000
private const val GRACEFUL_TERMINATION_POLL_MILLIS: Long = 250
private const val GRACEFUL_TERMINATION_POLLS: Int =
  (GRACEFUL_TERMINATION_WAIT_MILLIS / GRACEFUL_TERMINATION_POLL_MILLIS).toInt()

@Inject
class GoalRunnerStatusControlVerbs(
  private val manifestStore: GoalRunnerManifestStore,
  private val clock: Clock,
  private val workerSupervisor: FeatureTaskRuntimeWorkerSupervisor,
  private val repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
) {
  fun pause(
    issueKey: String,
    repoRoot: Path,
  ): GoalRunnerPauseResult {
    val loaded =
      manifestStore.loadByIssueKey(issueKey, repoRoot)
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
  ): GoalRunnerStopVerbResult {
    val loaded =
      manifestStore.loadByIssueKey(issueKey, repoRoot)
        ?: return GoalRunnerStopVerbResult(issueKey = issueKey, status = GoalRunnerStopStatus.NOT_FOUND)
    manifestStore.bindRepositoryIdentity(
      loaded.parentWorkflowId,
      goalRepositoryIdentity(repoRoot, repositoryEnclosingRootPort),
    )
    val alreadyStopped =
      loaded.controlState.paused &&
        loaded.controlState.pauseReason == GOAL_PAUSE_REASON_OPERATOR_STOP
    val control =
      manifestStore.pauseNow(
        parentWorkflowId = loaded.parentWorkflowId,
        reason = GOAL_PAUSE_REASON_OPERATOR_STOP,
        pausedAt = clock.instant().toString(),
        overwriteExistingReason = true,
      ) ?: return GoalRunnerStopVerbResult(issueKey = issueKey, status = GoalRunnerStopStatus.NOT_FOUND)

    fun outcome(
      status: GoalRunnerStopStatus,
      terminationAttempted: Boolean = false,
    ) = GoalRunnerStopVerbResult(
      issueKey = issueKey,
      status = status,
      parentWorkflowId = loaded.parentWorkflowId,
      pauseReason = control.pauseReason,
      pausedAt = control.pausedAt,
      terminationAttempted = terminationAttempted,
    )

    val lease = manifestStore.executionLease(loaded.parentWorkflowId)
    val noLiveLease = if (alreadyStopped) GoalRunnerStopStatus.ALREADY_STOPPED else GoalRunnerStopStatus.NO_LIVE_LEASE
    if (lease == null) return outcome(noLiveLease)
    val ownership = lease.asWorkerOwnership(loaded.parentWorkflowId)
    return runCatching {
      when (workerSupervisor.inspect(ownership)) {
        is FeatureTaskRuntimeProcessInspection.OwnershipMismatch,
        is FeatureTaskRuntimeProcessInspection.Unsupported,
        -> outcome(GoalRunnerStopStatus.IDENTITY_MISMATCH)
        FeatureTaskRuntimeProcessInspection.NotRunning -> {
          manifestStore.releaseExecutionLease(
            parentWorkflowId = loaded.parentWorkflowId,
            ownerToken = lease.ownerToken,
            generation = lease.generation,
          )
          outcome(noLiveLease)
        }
        FeatureTaskRuntimeProcessInspection.ExactLive -> {
          terminateOwner(ownership)
          outcome(GoalRunnerStopStatus.STOPPED, terminationAttempted = true)
        }
      }
    }.getOrElse { outcome(GoalRunnerStopStatus.STOPPED, terminationAttempted = true) }
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

  private fun terminateOwner(ownership: FeatureTaskRuntimeWorkerOwnership) {
    workerSupervisor.terminateGracefully(ownership)
    repeat(GRACEFUL_TERMINATION_POLLS) {
      if (workerSupervisor.inspect(ownership) != FeatureTaskRuntimeProcessInspection.ExactLive) return
      workerSupervisor.pause(GRACEFUL_TERMINATION_POLL_MILLIS)
    }
    if (workerSupervisor.inspect(ownership) == FeatureTaskRuntimeProcessInspection.ExactLive) {
      workerSupervisor.terminateForcibly(ownership)
    }
  }
}
