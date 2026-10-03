package skillbill.engine.goalrunner.execution.core

import me.tatarka.inject.annotations.Inject
import skillbill.agentaddon.model.AgentAddonSelection
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeCrashReconciler
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionPlanResolver
import skillbill.engine.featuretask.model.execution.FeatureTaskRuntimeExecutionPlanCreationRequest
import skillbill.engine.goalplanning.GoalPlanningMigrationAdmission
import skillbill.engine.goalrunner.goalRepositoryIdentity
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.model.GoalRunPreparation
import skillbill.engine.goalrunner.model.GoalRunnerChildExecutionPlanAdmission
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.planning.recovery.GoalRunnerSpecDriftRecovery
import skillbill.engine.goalrunner.review.effectiveGoalRunnerReviewPolicy
import skillbill.engine.goalrunner.review.goalRunnerReviewPolicyMismatch
import skillbill.engine.goalrunner.status.stopped
import skillbill.goalrunner.GoalRunnerQualityGateSelectionResolver
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.runner.model.GoalRunnerReviewPolicy
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition

@Inject
class GoalRunnerRunPreparation(
  private val manifestStore: GoalRunnerManifestStore,
  private val repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
  private val executionPlans: FeatureTaskRuntimeExecutionPlanResolver,
  private val crashReconciler: FeatureTaskRuntimeCrashReconciler,
  private val specDriftRecovery: GoalRunnerSpecDriftRecovery,
  private val migrationAdmission: GoalPlanningMigrationAdmission,
) {
  internal fun admitPlanningMigration(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ) {
    val repositoryIdentity = goalRepositoryIdentity(request.repoRoot, repositoryEnclosingRootPort)
    migrationAdmission.admit(
      GoalPlanningIdentity(
        state.parentWorkflowId,
        state.manifest.issueKey.trim().uppercase(),
        repositoryIdentity,
      ),
    )
  }

  internal fun refreshSpecPlanning(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): GoalRunnerManifestState = specDriftRecovery.refresh(state, request)

  fun prepareRun(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): GoalRunPreparation {
    val persistedControl =
      manifestStore.bindRepositoryIdentity(
        state.parentWorkflowId,
        goalRepositoryIdentity(request.repoRoot, repositoryEnclosingRootPort),
      )
    stopAfterPolicyMismatch(state, request, persistedControl)?.let { return it }
    val persistedReviewPolicy = manifestStore.reviewPolicy(state.parentWorkflowId)
    persistedReviewPolicy?.let { policy ->
      reviewPolicyMismatch(state, request, policy)?.let { return it }
    }
    val effectiveReviewPolicy = persistEffectiveReviewPolicy(state, request, persistedReviewPolicy)
    val effectiveControl = persistEffectiveStopAfterPolicy(state, request, persistedControl)
    val preparedState = resumeForRun(state, effectiveControl)
    return GoalRunPreparation.Prepared(
      preparedState,
      request.copy(
        codeReviewMode = effectiveReviewPolicy.codeReviewMode,
        stopAfterSubtaskId = request.stopAfterSubtaskId ?: persistedControl.stopAfterSubtaskId,
      ),
    )
  }

  fun existingChildExecutionPlanAdmission(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): GoalRunnerChildExecutionPlanAdmission? {
    val subtaskId = state.manifest.currentSubtaskIntent.subtaskId
    val workflowId = state.manifest.workflowIdFor(subtaskId)?.takeIf(String::isNotBlank) ?: return null
    val reviewMode =
      manifestStore.reviewPolicy(state.parentWorkflowId)?.codeReviewMode
        ?: effectiveGoalRunnerReviewPolicy(request.codeReviewMode, null).codeReviewMode
    val plan =
      executionPlans.resolveCreation(
        FeatureTaskRuntimeExecutionPlanCreationRequest(
          repoRoot = request.repoRoot,
          definition = SkeletonDefinition.GOAL_CHILD,
          reviewMode = reviewMode,
          qualityGate = GoalRunnerQualityGateSelectionResolver.resolve(state.manifest, subtaskId),
          validationDepth = ValidationDepth.FULL,
          timeout = request.timeout,
          workflowId = workflowId,
        ),
      )
    crashReconciler.reconcile(workflowId)
    return GoalRunnerChildExecutionPlanAdmission(workflowId, plan)
  }

  private fun stopAfterPolicyMismatch(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    persistedControl: GoalRunnerControlState,
  ): GoalRunPreparation.PreparationBlocked? {
    val requested = request.stopAfterSubtaskId ?: return null
    val persisted = persistedControl.stopAfterSubtaskId ?: return null
    if (persisted == requested) return null
    return GoalRunPreparation.PreparationBlocked(
      stopped(
        StoppedReportArgs(
          issueKey = request.issueKey,
          attempted = emptyList(),
          subtaskId = state.manifest.currentSubtaskIntent.subtaskId,
          reason = GoalRunnerStopReason.BLOCKED,
          blockedReason =
            "Cannot change stop-after subtask policy on goal resume: parent workflow " +
              "'${state.parentWorkflowId}' is pinned to subtask $persisted.",
          workflowId = state.parentWorkflowId,
          lastResumableStep = "preplan",
        ),
      ),
    )
  }

  private fun persistEffectiveReviewPolicy(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    persistedReviewPolicy: GoalRunnerReviewPolicy?,
  ): GoalRunnerReviewPolicy {
    val requestedAgentAddonSelection = request.agentAddonSelection.persisted
    val effectiveAgentAddonSelection =
      requestedAgentAddonSelection
        .takeUnless { it.entries.isEmpty() }
        ?: persistedReviewPolicy?.agentAddonSelection
        ?: AgentAddonSelection()
    val effectiveReviewPolicy =
      effectiveGoalRunnerReviewPolicy(
        request.codeReviewMode,
        persistedReviewPolicy,
      )
    val requestedReviewPolicy =
      GoalRunnerReviewPolicy(
        codeReviewMode = effectiveReviewPolicy.codeReviewMode,
        agentAddonSelection = effectiveAgentAddonSelection,
      )
    return manifestStore.persistReviewPolicy(
      parentWorkflowId = state.parentWorkflowId,
      policy = requestedReviewPolicy,
    )
  }

  private fun persistEffectiveStopAfterPolicy(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    persistedControl: GoalRunnerControlState,
  ): GoalRunnerControlState =
    if (request.stopAfterSubtaskId != null && persistedControl.stopAfterSubtaskId == null) {
      manifestStore.persistStopAfterSubtask(
        state.parentWorkflowId,
        request.stopAfterSubtaskId,
      )
    } else {
      persistedControl
    }

  private fun resumeForRun(
    state: GoalRunnerManifestState,
    effectiveControl: GoalRunnerControlState,
  ): GoalRunnerManifestState {
    val clearsPause = effectiveControl.paused || effectiveControl.pauseRequested
    val resumedState =
      if (clearsPause) {
        manifestStore.resume(state.parentWorkflowId) ?: state
      } else {
        state
      }
    return resumedState.copy(
      controlState =
        if (clearsPause) {
          manifestStore.controlState(state.parentWorkflowId)
        } else {
          effectiveControl
        },
    )
  }

  private fun reviewPolicyMismatch(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    policy: GoalRunnerReviewPolicy,
  ): GoalRunPreparation.PreparationBlocked? {
    val requestedAgentAddonSelection = request.agentAddonSelection.persisted
    val reason =
      goalRunnerReviewPolicyMismatch(
        state.parentWorkflowId,
        request.codeReviewMode,
        policy,
      ) ?: if (
        requestedAgentAddonSelection.entries.isNotEmpty() &&
        policy.agentAddonSelection != requestedAgentAddonSelection
      ) {
        "Cannot change agent add-on selection on goal resume: parent workflow '${state.parentWorkflowId}' " +
          "has a different durable selection."
      } else {
        return null
      }
    return GoalRunPreparation.PreparationBlocked(
      stopped(
        StoppedReportArgs(
          issueKey = request.issueKey,
          attempted = emptyList(),
          subtaskId = 0,
          reason = GoalRunnerStopReason.BLOCKED,
          blockedReason = reason,
          workflowId = state.parentWorkflowId,
          lastResumableStep = "preplan",
        ),
      ),
    )
  }
}
