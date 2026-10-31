package skillbill.engine.goalrunner.manifest

import skillbill.engine.goalrunner.model.GoalChildHistoryAcceptanceRestoreResult
import skillbill.engine.goalrunner.model.GoalPurgeOwnership
import skillbill.engine.goalrunner.model.GoalRunnerChildExecutionPlanAdmission
import skillbill.engine.goalrunner.model.GoalRunnerChildWorkflowSetup
import skillbill.engine.goalrunner.model.GoalRunnerCompletionPersistenceResult
import skillbill.engine.goalrunner.model.GoalRunnerLaunchAuthorization
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerPausePersistenceResult
import skillbill.engine.goalrunner.model.GoalRunnerScopedReplanOptions
import skillbill.engine.goalrunner.model.GoalRunnerScopedReplanWriteResult
import skillbill.engine.goalrunner.model.GoalRunnerStatusManifestPage
import skillbill.goalrunner.model.GoalPlanningStatusSnapshot
import skillbill.goalrunner.model.GoalPlanningStatusSummary
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.ports.agentrun.model.AgentRunSpawnAuthorization
import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.goalrunner.runner.model.GoalRunnerReviewPolicy
import skillbill.ports.persistence.model.GoalPurgeTableCounts
import skillbill.ports.persistence.model.GoalPurgeTarget
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import java.nio.file.Path

public interface GoalRunnerStatusManifestQueries {
  public fun statusManifestPage(
    parentWorkflowId: String,
    issueKey: String,
    offset: Int,
    limit: Int,
    repoRoot: Path?,
  ): GoalRunnerStatusManifestPage?

  public fun statusParentWorkflowId(
    issueKey: String,
    repoRoot: Path?,
  ): String?
}

public interface GoalRunnerManifestQueries {
  public fun loadByIssueKey(
    issueKey: String,
    repoRoot: Path? = null,
  ): GoalRunnerManifestState?

  public fun readByIssueKey(
    issueKey: String,
    repoRoot: Path? = null,
  ): GoalRunnerManifestState?

  public fun readByIssueKeyIfPresent(
    issueKey: String,
    repoRoot: Path? = null,
  ): GoalRunnerManifestState?

  public fun loadDurableByIssueKey(
    issueKey: String,
    repoRoot: Path? = null,
  ): GoalRunnerManifestState?

  public fun controlState(parentWorkflowId: String): GoalRunnerControlState

  public fun executionLease(parentWorkflowId: String): GoalRunnerExecutionLease?

  public fun reviewMode(parentWorkflowId: String): CodeReviewExecutionMode?

  public fun reviewPolicy(parentWorkflowId: String): GoalRunnerReviewPolicy?

  public fun outOfBandAcceptances(parentWorkflowId: String): Map<Int, GoalRunnerOutOfBandAcceptance>
}

public interface GoalRunnerManifestPauseCommands {
  public fun requestPause(parentWorkflowId: String): GoalRunnerControlState?

  public fun pauseNow(
    parentWorkflowId: String,
    reason: String,
    pausedAt: String,
    overwriteExistingReason: Boolean = false,
  ): GoalRunnerControlState?

  public fun requestPauseByIssueKey(
    issueKey: String,
    repoRoot: Path? = null,
  ): GoalRunnerPausePersistenceResult?

  public fun resume(parentWorkflowId: String): GoalRunnerManifestState?

  public fun pauseAtBoundary(state: GoalRunnerManifestState): GoalRunnerManifestState
}

public interface GoalRunnerManifestLeaseCommands {
  public fun acquireExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    expectedOwnerToken: String? = null,
  ): Boolean

  public fun acquireExecutionLeaseFenced(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    expectedOwner: GoalRunnerExecutionLease?,
  ): Boolean

  public fun acquireExecutionLeaseWithChildAdmission(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    expectedOwnerToken: String?,
    childAdmission: GoalRunnerChildExecutionPlanAdmission,
  ): Boolean

  public fun acquireExecutionLeaseWithChildAdmissionFenced(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    expectedOwner: GoalRunnerExecutionLease?,
    childAdmission: GoalRunnerChildExecutionPlanAdmission,
  ): Boolean

  public fun heartbeatExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
  ): Boolean

  public fun releaseExecutionLease(
    parentWorkflowId: String,
    ownerToken: String,
    generation: Long,
  ): Boolean

  public fun releaseExecutionLeaseIfExpired(
    parentWorkflowId: String,
    ownerToken: String,
    generation: Long,
    nowInstant: String,
  ): Boolean
}

public interface GoalRunnerManifestExecutionCommands :
  GoalRunnerManifestPauseCommands,
  GoalRunnerManifestLeaseCommands

public interface GoalRunnerManifestControlWrites {
  public fun restoreOutOfBandAcceptance(
    state: GoalRunnerManifestState,
    acceptance: GoalRunnerOutOfBandAcceptance,
  ): GoalChildHistoryAcceptanceRestoreResult

  public fun bindRepositoryIdentity(
    parentWorkflowId: String,
    repositoryIdentity: String,
  ): GoalRunnerControlState

  public fun persistStopAfterSubtask(
    parentWorkflowId: String,
    subtaskId: Int,
  ): GoalRunnerControlState

  public fun authorizeSubtaskLaunch(
    state: GoalRunnerManifestState,
    subtaskId: Int,
  ): GoalRunnerLaunchAuthorization

  public fun authorizePlanningLaunch(parentWorkflowId: String): AgentRunSpawnAuthorization?

  public fun persistControlState(
    parentWorkflowId: String,
    state: GoalRunnerControlState,
  ): GoalRunnerControlState

  public fun updateControlState(
    parentWorkflowId: String,
    update: (GoalRunnerControlState) -> GoalRunnerControlState,
  ): GoalRunnerControlState

  public fun clearRunnerInterruptedPause(parentWorkflowId: String): GoalRunnerControlState

  public fun persistReviewMode(
    parentWorkflowId: String,
    mode: CodeReviewExecutionMode,
  ): CodeReviewExecutionMode

  public fun persistReviewPolicy(
    parentWorkflowId: String,
    policy: GoalRunnerReviewPolicy,
  ): GoalRunnerReviewPolicy
}

public interface GoalRunnerManifestStateWrites {
  public fun planningStatus(
    parentWorkflowId: String,
    orderedSubtaskIds: List<Int>,
    blockedSubtaskId: Int? = null,
    blockedReason: String? = null,
  ): GoalPlanningStatusSnapshot?

  public fun planningStatusFromSummary(
    parentWorkflowId: String,
    summary: GoalPlanningStatusSummary,
    blockedSubtaskId: Int?,
    blockedReason: String?,
  ): GoalPlanningStatusSnapshot? =
    planningStatus(
      parentWorkflowId,
      summary.firstMissingIds,
      blockedSubtaskId,
      blockedReason,
    )

  public fun save(state: GoalRunnerManifestState): GoalRunnerManifestState

  public fun saveRuntimeState(state: GoalRunnerManifestState): GoalRunnerManifestState

  public fun saveCompletedSubtaskAtBoundary(
    state: GoalRunnerManifestState,
    subtaskId: Int,
  ): GoalRunnerCompletionPersistenceResult

  public fun saveHardReset(
    state: GoalRunnerManifestState,
    preservePlanning: Boolean = false,
  ): GoalRunnerManifestState

  public fun deleteIncompatibleChildWorkflow(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    workflowId: String,
  ): GoalRunnerManifestState

  public fun saveScopedReplan(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    options: GoalRunnerScopedReplanOptions,
  ): GoalRunnerScopedReplanWriteResult

  public fun clearReplannedChildWorkflows(
    state: GoalRunnerManifestState,
    subtaskIds: List<Int>,
  ): GoalRunnerManifestState

  public fun saveNewChildWorkflow(
    state: GoalRunnerManifestState,
    setup: GoalRunnerChildWorkflowSetup,
  ): GoalRunnerChildWorkflowSaveResult
}

public interface GoalRunnerManifestCompletionWrites {
  /**
   * Accepts every unfinished subtask against [commitSha], retires any active quality execution and
   * records goal completion at [completedAt] in one transaction.
   */
  public fun saveOperatorGoalCompletion(
    state: GoalRunnerManifestState,
    commitSha: String,
    reason: String,
    completedAt: String,
  ): GoalRunnerManifestState
}

public interface GoalRunnerManifestPurgeCommands {
  public fun listOwnedGoalChildWorkflowIds(parentWorkflowId: String): List<String>

  /** Finds every decomposed parent for [issueKey] under the repository identity of [repoRoot]. */
  public fun discoverPurgeOwnership(
    issueKey: String,
    repoRoot: Path,
  ): GoalPurgeOwnership

  /**
   * Keeps the manifest-recorded [candidateIds] that provably belong to this goal: same issue key and repository
   * identity, or a goal child of one of [parentWorkflowIds]. Unverifiable ids are not owned and are dropped.
   */
  public fun verifyOwnedWorkflowIds(
    candidateIds: Set<String>,
    parentWorkflowIds: Set<String>,
    issueKey: String,
    repoRoot: Path,
  ): Set<String>

  public fun purgeDecomposedGoal(target: GoalPurgeTarget): GoalPurgeTableCounts

  public fun countDecomposedGoalState(target: GoalPurgeTarget): GoalPurgeTableCounts
}

public interface GoalRunnerManifestStore :
  GoalRunnerManifestQueries,
  GoalRunnerStatusManifestQueries,
  GoalRunnerManifestExecutionCommands,
  GoalRunnerManifestControlWrites,
  GoalRunnerManifestStateWrites,
  GoalRunnerManifestCompletionWrites,
  GoalRunnerManifestPurgeCommands
