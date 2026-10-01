package skillbill.engine.goalrunner.manifest

import skillbill.engine.goalrunner.model.GoalRunnerChildExecutionPlanAdmission
import skillbill.engine.goalrunner.model.GoalRunnerChildWorkflowSetup
import skillbill.engine.goalrunner.model.GoalRunnerCompletionPersistenceResult
import skillbill.engine.goalrunner.model.GoalRunnerLaunchAuthorization
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerPausePersistenceResult
import skillbill.engine.goalrunner.model.GoalRunnerScopedReplanOptions
import skillbill.engine.goalrunner.model.GoalRunnerScopedReplanWriteResult
import skillbill.goalrunner.model.GoalPlanningStatusSnapshot
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.ports.agentrun.model.AgentRunSpawnAuthorization
import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.goalrunner.runner.model.GoalRunnerReviewPolicy
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import java.nio.file.Path

interface GoalRunnerManifestQueries {
  fun loadByIssueKey(
    issueKey: String,
    repoRoot: Path? = null,
  ): GoalRunnerManifestState?

  fun readByIssueKey(
    issueKey: String,
    repoRoot: Path? = null,
  ): GoalRunnerManifestState?

  fun readByIssueKeyIfPresent(
    issueKey: String,
    repoRoot: Path? = null,
  ): GoalRunnerManifestState?

  fun loadDurableByIssueKey(issueKey: String): GoalRunnerManifestState?

  fun controlState(parentWorkflowId: String): GoalRunnerControlState

  fun executionLease(parentWorkflowId: String): GoalRunnerExecutionLease?

  fun reviewMode(parentWorkflowId: String): CodeReviewExecutionMode?

  fun reviewPolicy(parentWorkflowId: String): GoalRunnerReviewPolicy?

  fun outOfBandAcceptances(parentWorkflowId: String): Map<Int, GoalRunnerOutOfBandAcceptance>

  fun sharedPreplanPayloadSha256(parentWorkflowId: String): String?
}

interface GoalRunnerManifestExecutionCommands {
  fun requestPause(parentWorkflowId: String): GoalRunnerControlState?

  fun pauseNow(
    parentWorkflowId: String,
    reason: String,
    pausedAt: String,
    overwriteExistingReason: Boolean = false,
  ): GoalRunnerControlState?

  fun requestPauseByIssueKey(
    issueKey: String,
    repoRoot: Path? = null,
  ): GoalRunnerPausePersistenceResult?

  fun resume(parentWorkflowId: String): GoalRunnerManifestState?

  fun pauseAtBoundary(state: GoalRunnerManifestState): GoalRunnerManifestState

  fun acquireExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    expectedOwnerToken: String? = null,
  ): Boolean

  fun acquireExecutionLeaseWithChildAdmission(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    expectedOwnerToken: String?,
    childAdmission: GoalRunnerChildExecutionPlanAdmission,
  ): Boolean

  fun heartbeatExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
  ): Boolean

  fun releaseExecutionLease(
    parentWorkflowId: String,
    ownerToken: String,
    generation: Long,
  ): Boolean

  fun releaseExecutionLeaseIfExpired(
    parentWorkflowId: String,
    ownerToken: String,
    generation: Long,
    nowInstant: String,
  ): Boolean
}

interface GoalRunnerManifestControlWrites {
  fun bindRepositoryIdentity(
    parentWorkflowId: String,
    repositoryIdentity: String,
  ): GoalRunnerControlState

  fun persistStopAfterSubtask(
    parentWorkflowId: String,
    subtaskId: Int,
  ): GoalRunnerControlState

  fun authorizeSubtaskLaunch(
    state: GoalRunnerManifestState,
    subtaskId: Int,
  ): GoalRunnerLaunchAuthorization

  fun authorizePlanningLaunch(parentWorkflowId: String): AgentRunSpawnAuthorization?

  fun persistControlState(
    parentWorkflowId: String,
    state: GoalRunnerControlState,
  ): GoalRunnerControlState

  fun clearRunnerInterruptedPause(parentWorkflowId: String): GoalRunnerControlState

  fun persistReviewMode(
    parentWorkflowId: String,
    mode: CodeReviewExecutionMode,
  ): CodeReviewExecutionMode

  fun persistReviewPolicy(
    parentWorkflowId: String,
    policy: GoalRunnerReviewPolicy,
  ): GoalRunnerReviewPolicy

  fun persistOutOfBandAcceptance(
    parentWorkflowId: String,
    acceptance: GoalRunnerOutOfBandAcceptance,
  ): GoalRunnerOutOfBandAcceptance
}

interface GoalRunnerManifestStateWrites {
  fun planningStatus(
    parentWorkflowId: String,
    orderedSubtaskIds: List<Int>,
    blockedSubtaskId: Int? = null,
    blockedReason: String? = null,
  ): GoalPlanningStatusSnapshot?

  fun save(state: GoalRunnerManifestState): GoalRunnerManifestState

  fun saveRuntimeState(state: GoalRunnerManifestState): GoalRunnerManifestState

  fun saveCompletedSubtaskAtBoundary(
    state: GoalRunnerManifestState,
    subtaskId: Int,
  ): GoalRunnerCompletionPersistenceResult

  fun saveHardReset(
    state: GoalRunnerManifestState,
    preservePlanning: Boolean = false,
  ): GoalRunnerManifestState

  fun deleteIncompatibleChildWorkflow(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    workflowId: String,
  ): GoalRunnerManifestState

  fun saveScopedReplan(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    options: GoalRunnerScopedReplanOptions = GoalRunnerScopedReplanOptions(),
  ): GoalRunnerScopedReplanWriteResult

  fun saveNewChildWorkflow(
    state: GoalRunnerManifestState,
    setup: GoalRunnerChildWorkflowSetup,
  ): GoalRunnerManifestState
}

interface GoalRunnerManifestPurgeCommands {
  fun listOwnedGoalChildWorkflowIds(parentWorkflowId: String): List<String>

  fun purgeDecomposedGoal(parentWorkflowId: String)
}

interface GoalRunnerManifestStore :
  GoalRunnerManifestQueries,
  GoalRunnerManifestExecutionCommands,
  GoalRunnerManifestControlWrites,
  GoalRunnerManifestStateWrites,
  GoalRunnerManifestPurgeCommands
