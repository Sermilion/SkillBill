package skillbill.engine.goalrunner.manifest

import skillbill.engine.goalrunner.model.GoalRunnerChildExecutionPlanAdmission
import skillbill.engine.goalrunner.model.GoalRunnerChildWorkflowSetup
import skillbill.engine.goalrunner.model.GoalRunnerCompletionPersistenceResult
import skillbill.engine.goalrunner.model.GoalRunnerLaunchAuthorization
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerPausePersistenceResult
import skillbill.engine.goalrunner.model.GoalRunnerScopedReplanOptions
import skillbill.engine.goalrunner.model.GoalRunnerScopedReplanWriteResult
import skillbill.goalrunner.model.GOAL_PAUSE_REASON_RUNNER_INTERRUPTED
import skillbill.goalrunner.model.GoalPlanningStatusSnapshot
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.ports.agentrun.model.AgentRunSpawnAuthorization
import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.goalrunner.runner.model.GoalRunnerReviewPolicy
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import java.nio.file.Path
import java.time.Instant

abstract class GoalRunnerManifestStoreDefaults : GoalRunnerManifestStore {
  override fun readByIssueKey(
    issueKey: String,
    repoRoot: Path?,
  ): GoalRunnerManifestState? = loadByIssueKey(issueKey, repoRoot)

  override fun readByIssueKeyIfPresent(
    issueKey: String,
    repoRoot: Path?,
  ): GoalRunnerManifestState? = readByIssueKey(issueKey, repoRoot)

  override fun loadDurableByIssueKey(issueKey: String): GoalRunnerManifestState? = loadByIssueKey(issueKey, null)

  override fun requestPause(parentWorkflowId: String): GoalRunnerControlState? = null

  override fun pauseNow(
    parentWorkflowId: String,
    reason: String,
    pausedAt: String,
    overwriteExistingReason: Boolean,
  ): GoalRunnerControlState? = null

  override fun requestPauseByIssueKey(
    issueKey: String,
    repoRoot: Path?,
  ): GoalRunnerPausePersistenceResult? = null

  override fun resume(parentWorkflowId: String): GoalRunnerManifestState? = null

  override fun pauseAtBoundary(state: GoalRunnerManifestState): GoalRunnerManifestState = state

  override fun acquireExecutionLeaseWithChildAdmission(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    expectedOwnerToken: String?,
    childAdmission: GoalRunnerChildExecutionPlanAdmission,
  ): Boolean = acquireExecutionLease(parentWorkflowId, lease, expectedOwnerToken)

  override fun executionLease(parentWorkflowId: String): GoalRunnerExecutionLease? = null

  override fun releaseExecutionLeaseIfExpired(
    parentWorkflowId: String,
    ownerToken: String,
    generation: Long,
    nowInstant: String,
  ): Boolean {
    val lease = executionLease(parentWorkflowId) ?: return false
    if (lease.ownerToken != ownerToken || lease.generation != generation) return false
    if (lease.expiresAt.isAfter(Instant.parse(nowInstant))) return false
    return releaseExecutionLease(parentWorkflowId, ownerToken, generation)
  }

  override fun controlState(parentWorkflowId: String): GoalRunnerControlState = GoalRunnerControlState()

  override fun bindRepositoryIdentity(
    parentWorkflowId: String,
    repositoryIdentity: String,
  ): GoalRunnerControlState {
    require(repositoryIdentity.isNotBlank()) { "repositoryIdentity is required." }
    return controlState(parentWorkflowId)
  }

  override fun persistStopAfterSubtask(
    parentWorkflowId: String,
    subtaskId: Int,
  ): GoalRunnerControlState = GoalRunnerControlState(stopAfterSubtaskId = subtaskId)

  override fun authorizeSubtaskLaunch(
    state: GoalRunnerManifestState,
    subtaskId: Int,
  ): GoalRunnerLaunchAuthorization {
    require(subtaskId > 0) { "subtaskId must be positive." }
    val controls = controlState(state.parentWorkflowId)
    return GoalRunnerLaunchAuthorization(
      authorized = !controls.requiresPauseBoundary(state.manifest),
      controlState = controls,
    )
  }

  override fun authorizePlanningLaunch(parentWorkflowId: String): AgentRunSpawnAuthorization? = null

  override fun persistControlState(
    parentWorkflowId: String,
    state: GoalRunnerControlState,
  ): GoalRunnerControlState = state

  override fun clearRunnerInterruptedPause(parentWorkflowId: String): GoalRunnerControlState {
    val state = controlState(parentWorkflowId)
    if (state.pauseReason != GOAL_PAUSE_REASON_RUNNER_INTERRUPTED) return state
    return persistControlState(
      parentWorkflowId,
      state.copy(
        paused = false,
        pauseRequested = false,
        pauseConsumed = false,
        pauseReason = null,
        pausedAt = null,
      ),
    )
  }

  override fun planningStatus(
    parentWorkflowId: String,
    orderedSubtaskIds: List<Int>,
    blockedSubtaskId: Int?,
    blockedReason: String?,
  ): GoalPlanningStatusSnapshot? = null

  override fun saveRuntimeState(state: GoalRunnerManifestState): GoalRunnerManifestState = save(state)

  override fun saveCompletedSubtaskAtBoundary(
    state: GoalRunnerManifestState,
    subtaskId: Int,
  ): GoalRunnerCompletionPersistenceResult =
    GoalRunnerCompletionPersistenceResult(
      state = saveRuntimeState(state),
      paused = false,
    )

  override fun saveHardReset(
    state: GoalRunnerManifestState,
    preservePlanning: Boolean,
  ): GoalRunnerManifestState = error("Goal runner manifest store must atomically persist hard reset state.")

  override fun deleteIncompatibleChildWorkflow(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    workflowId: String,
  ): GoalRunnerManifestState =
    error("Goal runner manifest store must atomically delete a selected incompatible child workflow.")

  override fun saveScopedReplan(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    options: GoalRunnerScopedReplanOptions,
  ): GoalRunnerScopedReplanWriteResult =
    error("Goal runner manifest store must atomically persist a scoped subtask replan.")

  override fun sharedPreplanPayloadSha256(parentWorkflowId: String): String? = null

  override fun saveNewChildWorkflow(
    state: GoalRunnerManifestState,
    setup: GoalRunnerChildWorkflowSetup,
  ): GoalRunnerManifestState = error("Goal runner manifest store must atomically persist new child workflow state.")

  override fun listOwnedGoalChildWorkflowIds(parentWorkflowId: String): List<String> = emptyList()

  override fun purgeDecomposedGoal(parentWorkflowId: String) {
    error("Goal runner manifest store must atomically purge a decomposed goal.")
  }

  override fun reviewMode(parentWorkflowId: String): CodeReviewExecutionMode? = null

  override fun persistReviewMode(
    parentWorkflowId: String,
    mode: CodeReviewExecutionMode,
  ): CodeReviewExecutionMode = mode

  override fun reviewPolicy(parentWorkflowId: String): GoalRunnerReviewPolicy? =
    reviewMode(parentWorkflowId)?.let(::GoalRunnerReviewPolicy)

  override fun persistReviewPolicy(
    parentWorkflowId: String,
    policy: GoalRunnerReviewPolicy,
  ): GoalRunnerReviewPolicy =
    GoalRunnerReviewPolicy(
      codeReviewMode = persistReviewMode(parentWorkflowId, policy.codeReviewMode),
      agentAddonSelection = policy.agentAddonSelection,
    )

  override fun outOfBandAcceptances(parentWorkflowId: String): Map<Int, GoalRunnerOutOfBandAcceptance> = emptyMap()

  override fun persistOutOfBandAcceptance(
    parentWorkflowId: String,
    acceptance: GoalRunnerOutOfBandAcceptance,
  ): GoalRunnerOutOfBandAcceptance =
    error("Goal runner manifest store must durably persist out-of-band subtask acceptance.")
}
