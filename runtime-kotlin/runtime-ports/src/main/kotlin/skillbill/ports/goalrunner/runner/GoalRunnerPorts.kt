package skillbill.ports.goalrunner.runner

import skillbill.goalrunner.model.GoalPlanningStatusSnapshot
import skillbill.goalrunner.model.GoalRunnerAttemptLedgerSummary
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerWirePayload
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunSpawnAuthorization
import skillbill.ports.goalrunner.runner.model.GoalPullRequestRequest
import skillbill.ports.goalrunner.runner.model.GoalPullRequestResult
import skillbill.ports.goalrunner.runner.model.GoalRunnerChildExecutionPlanAdmission
import skillbill.ports.goalrunner.runner.model.GoalRunnerChildWorkflowSetup
import skillbill.ports.goalrunner.runner.model.GoalRunnerCompletionPersistenceResult
import skillbill.ports.goalrunner.runner.model.GoalRunnerLaunchAuthorization
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.goalrunner.runner.model.GoalRunnerPausePersistenceResult
import skillbill.ports.goalrunner.runner.model.GoalRunnerReviewPolicy
import skillbill.ports.goalrunner.runner.model.GoalRunnerScopedReplanOptions
import skillbill.ports.goalrunner.runner.model.GoalRunnerScopedReplanWriteResult
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.review.context.model.launch.CodeReviewExecutionMode
import skillbill.workflow.model.goalreview.GoalSubtaskReviewPassResult
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import java.nio.file.Path

interface GoalRunnerManifestQueries {
  fun loadByIssueKey(issueKey: String, repoRoot: Path? = null): GoalRunnerManifestState?

  fun readByIssueKey(issueKey: String, repoRoot: Path? = null): GoalRunnerManifestState?

  fun readByIssueKeyIfPresent(issueKey: String, repoRoot: Path? = null): GoalRunnerManifestState?

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

  fun requestPauseByIssueKey(issueKey: String, repoRoot: Path? = null): GoalRunnerPausePersistenceResult?

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

  fun heartbeatExecutionLease(parentWorkflowId: String, lease: GoalRunnerExecutionLease): Boolean

  fun releaseExecutionLease(parentWorkflowId: String, ownerToken: String, generation: Long): Boolean

  fun releaseExecutionLeaseIfExpired(
    parentWorkflowId: String,
    ownerToken: String,
    generation: Long,
    nowInstant: String,
  ): Boolean
}

interface GoalRunnerManifestControlWrites {
  fun bindRepositoryIdentity(parentWorkflowId: String, repositoryIdentity: String): GoalRunnerControlState

  fun persistStopAfterSubtask(parentWorkflowId: String, subtaskId: Int): GoalRunnerControlState

  fun authorizeSubtaskLaunch(state: GoalRunnerManifestState, subtaskId: Int): GoalRunnerLaunchAuthorization

  fun authorizePlanningLaunch(parentWorkflowId: String): AgentRunSpawnAuthorization?

  fun persistControlState(parentWorkflowId: String, state: GoalRunnerControlState): GoalRunnerControlState

  fun clearRunnerInterruptedPause(parentWorkflowId: String): GoalRunnerControlState

  fun persistReviewMode(parentWorkflowId: String, mode: CodeReviewExecutionMode): CodeReviewExecutionMode

  fun persistReviewPolicy(parentWorkflowId: String, policy: GoalRunnerReviewPolicy): GoalRunnerReviewPolicy

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

  fun saveHardReset(state: GoalRunnerManifestState, preservePlanning: Boolean = false): GoalRunnerManifestState

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

interface GoalRunnerTerminalOutcomeStore {
  fun terminalOutcome(workflowId: String, issueKey: String, subtaskId: Int): GoalRunnerStoredOutcome?

  fun recoverAndPersistTerminalOutcome(
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    repoRoot: Path,
  ): GoalRunnerStoredOutcome?

  fun recoverMissingResultPrefixOutput(
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    output: GoalRunnerWirePayload,
  ): GoalRunnerStoredOutcome?
}

interface GoalRunnerReviewOutcomeStore {
  fun goalSubtaskReviewState(workflowId: String): GoalSubtaskReviewState?

  fun unemittedGoalReviewPasses(workflowId: String): List<GoalSubtaskReviewPassResult>

  fun acknowledgeGoalReviewPass(workflowId: String, passNumber: Int): Boolean
}

interface GoalRunnerWorkflowOutcomeStore :
  GoalRunnerTerminalOutcomeStore,
  GoalRunnerReviewOutcomeStore,
  GoalRunnerWorkflowProgressStore,
  GoalRunnerWorkflowLedgerWriteStore,
  GoalRunnerWorkflowOutcomeMutationStore

interface GoalRunnerAttemptLedgerStore {
  fun readAttemptLedgerSummary(issueKey: String): GoalRunnerAttemptLedgerSummary
}

fun interface GoalRunnerSubtaskLauncher {
  fun launch(request: GoalRunnerSubtaskLaunchRequest): AgentRunLaunchOutcome
}

fun interface GoalPullRequestPort {
  fun open(request: GoalPullRequestRequest): GoalPullRequestResult
}
