package skillbill.engine.goalrunner.execution.core

import me.tatarka.inject.annotations.Inject
import skillbill.agentaddon.model.AgentAddonSelection
import skillbill.engine.featuretask.incident.BlockIncidentEntryReconciler
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeCrashReconciler
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionPlanResolver
import skillbill.engine.featuretask.model.execution.FeatureTaskRuntimeExecutionPlanCreationRequest
import skillbill.engine.featuretask.phaserun.StandalonePhaseStatusPublisherFactory
import skillbill.engine.goalrunner.execution.support.branchPlanFor
import skillbill.engine.goalrunner.execution.support.goalWorkBranch
import skillbill.engine.goalrunner.goalRepositoryIdentity
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.model.GoalRunPreparation
import skillbill.engine.goalrunner.model.GoalRunnerChildExecutionPlanAdmission
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerRepairStatus
import skillbill.engine.goalrunner.model.GoalRunnerRunEvent
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.model.GoalRunnerWedgeClass
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.engine.goalrunner.repair.GoalRunnerRepairCoordinator
import skillbill.engine.goalrunner.review.effectiveGoalRunnerReviewPolicy
import skillbill.engine.goalrunner.review.goalRunnerReviewPolicyMismatch
import skillbill.engine.goalrunner.status.stopped
import skillbill.goalrunner.GoalRunnerPlanner
import skillbill.goalrunner.GoalRunnerQualityGateSelectionResolver
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerRunReport
import skillbill.goalrunner.model.GoalRunnerSelection
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.ports.goalrunner.runner.model.GoalRunnerReviewPolicy
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQaSelection
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Path

@Inject
public class GoalRunnerRunPreparation(
  private val manifestStore: GoalRunnerManifestStore,
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  private val repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
  private val executionPlans: FeatureTaskRuntimeExecutionPlanResolver,
  private val crashReconciler: FeatureTaskRuntimeCrashReconciler,
  private val statusPublisherFactory: StandalonePhaseStatusPublisherFactory,
  private val gitOperations: WorkflowGitOperations,
  private val repairCoordinator: GoalRunnerRepairCoordinator,
  private val blockIncidents: BlockIncidentEntryReconciler,
) {
  internal fun guardEntry(
    blocked: (GoalRunnerRunReport) -> Boolean,
    run: () -> GoalRunnerRunReport,
  ): GoalRunnerRunReport = blockIncidents.guardEntry(blocked, run)

  internal fun prepareStart(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    resumesExistingGoal: Boolean,
  ): GoalRunPreparation {
    val startState =
      if (resumesExistingGoal) {
        when (val repaired = repairBeforeResume(state, request)) {
          is GoalRunPreparation.PreparationBlocked -> return repaired
          is GoalRunPreparation.Prepared -> repaired.state
        }
      } else {
        state
      }
    return alignGoalBranch(startState, request)?.let(GoalRunPreparation::PreparationBlocked)
      ?: GoalRunPreparation.Prepared(startState, request)
  }

  private fun repairBeforeResume(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): GoalRunPreparation {
    val issueKey = state.manifest.issueKey
    val repair =
      repairCoordinator.repairBeforeResume(issueKey, request.repoRoot)
        ?: return GoalRunPreparation.Prepared(state, request)
    if (repair.appliedRepairs.isNotEmpty()) {
      request.eventSink.emit(GoalRunnerRunEvent.ResumeRepaired(issueKey, repair.appliedRepairs))
    }
    if (repair.status == GoalRunnerRepairStatus.OPERATOR_REQUIRED) {
      val subtaskId = state.manifest.currentSubtaskIntent.subtaskId
      val acceptedBlockReference =
        repair.diagnoses
          .firstOrNull { diagnosis -> diagnosis.subtaskId == subtaskId }
          ?.wedges
          ?.firstOrNull { finding -> finding.wedgeClass == GoalRunnerWedgeClass.STALE_BLOCKED_CONTINUATION_OUTCOME }
          ?.acceptedBlockReference
      return GoalRunPreparation.PreparationBlocked(
        stopped(
          StoppedReportArgs(
            issueKey = issueKey,
            attempted = emptyList(),
            subtaskId = subtaskId,
            reason = GoalRunnerStopReason.BLOCKED,
            blockedReason =
              "Goal '$issueKey' has a resume wedge the runtime will not clear on its own: " +
                (repair.refusalReason ?: "operator action required.") +
                " Inspect it with: skill-bill goal repair $issueKey",
            workflowId = acceptedBlockReference?.sourceWorkflowId ?: state.manifest.workflowIdFor(subtaskId),
            acceptedBlockReference = acceptedBlockReference,
            historicalBlockReference = acceptedBlockReference,
            lastResumableStep =
              state.manifest.subtasks
                .firstOrNull { it.id == subtaskId }
                ?.lastResumableStep
                .orEmpty()
                .ifBlank { "plan" },
          ),
        ),
      )
    }
    val repaired =
      if (repair.appliedRepairs.isEmpty()) {
        state
      } else {
        manifestStore.loadDurableByIssueKey(issueKey, request.repoRoot)?.copy(repoRoot = request.repoRoot) ?: state
      }
    return GoalRunPreparation.Prepared(repaired, request)
  }

  private fun alignGoalBranch(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): GoalRunnerRunReport? {
    val selection = GoalRunnerPlanner.selectNext(state.manifest) as? GoalRunnerSelection.Run ?: return null
    val subtaskId = selection.decision.subtask.id
    val branch = state.manifest.branchPlanFor(subtaskId).branch.trim()
    val failure = existingGoalBranchCheckoutFailure(request.repoRoot, branch) ?: return null
    return stopped(
      StoppedReportArgs(
        issueKey = request.issueKey,
        attempted = emptyList(),
        subtaskId = subtaskId,
        reason = GoalRunnerStopReason.BLOCKED,
        blockedReason =
          "Goal '${request.issueKey}' resumes on branch '$branch', and the runtime could not check it out " +
            "before reading its specs, so it stopped instead of planning against another branch's spec " +
            "files: $failure",
        workflowId = state.parentWorkflowId,
        lastResumableStep = "plan",
      ),
    )
  }

  private fun existingGoalBranchCheckoutFailure(
    repoRoot: Path,
    branch: String,
  ): String? {
    if (branch.isBlank() || gitOperations.currentBranch(repoRoot).value.trim() == branch) return null
    val exists = gitOperations.branchExists(repoRoot, branch)
    return when {
      exists !is WorkflowGitOperationResult.Ok -> exists.error
      exists.value.trim() != "true" -> null
      else ->
        gitOperations.checkoutBranch(repoRoot, branch, baseBranch = null)
          .takeUnless { it is WorkflowGitOperationResult.Ok }
          ?.error
    }
  }

  internal fun prepareRun(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): GoalRunPreparation {
    statusPublisherFactory.registerWorkflow(
      request.repoRoot,
      request.issueKey,
      state.parentWorkflowId,
      state.manifest.goalWorkBranch(),
    )
    val persistedControl =
      manifestStore.bindRepositoryIdentity(
        state.parentWorkflowId,
        goalRepositoryIdentity(request.repoRoot, repositoryEnclosingRootPort),
      )
    stopAfterPolicyMismatch(state, request, persistedControl)?.let { return it }
    val recordedQaSelection = persistedControl.qaSelection ?: inheritedQaSelection(state)
    qaSelectionMismatch(state, request, recordedQaSelection)?.let { return it }
    val persistedReviewPolicy = manifestStore.reviewPolicy(state.parentWorkflowId)
    persistedReviewPolicy?.let { policy ->
      reviewPolicyMismatch(state, request, policy)?.let { return it }
    }
    val effectiveReviewPolicy = persistEffectiveReviewPolicy(state, request, persistedReviewPolicy)
    val effectiveQaSelection = recordedQaSelection ?: request.qaSelection ?: FeatureTaskRuntimeQaSelection.AUTO
    val effectiveControl =
      persistEffectiveStopAfterPolicy(state, request, persistedControl).let { control ->
        if (control.qaSelection == null) {
          manifestStore.updateControlState(state.parentWorkflowId) { current ->
            current.takeIf { it.qaSelection != null } ?: current.copy(qaSelection = effectiveQaSelection)
          }
        } else {
          control
        }
      }
    val preparedState = resumeForRun(state, effectiveControl)
    return GoalRunPreparation.Prepared(
      preparedState,
      request.copy(
        codeReviewMode = effectiveReviewPolicy.codeReviewMode,
        qaSelection = effectiveQaSelection,
        stopAfterSubtaskId = request.stopAfterSubtaskId ?: persistedControl.stopAfterSubtaskId,
      ),
    )
  }

  internal fun existingChildExecutionPlanAdmission(
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
          qualityGate =
            outcomeStore.recordedQualityGateSelection(workflowId)
              ?: GoalRunnerQualityGateSelectionResolver.resolve(),
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

  private fun inheritedQaSelection(state: GoalRunnerManifestState): FeatureTaskRuntimeQaSelection? =
    state.manifest.subtasks.asSequence()
      .mapNotNull { subtask ->
        subtask.workflowId?.takeIf(String::isNotBlank)?.let { outcomeStore.childQaSelection(it, null) }
      }
      .firstOrNull()

  private fun qaSelectionMismatch(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    recorded: FeatureTaskRuntimeQaSelection?,
  ): GoalRunPreparation.PreparationBlocked? {
    val requested = request.qaSelection ?: return null
    if (recorded == null || recorded == requested) return null
    return GoalRunPreparation.PreparationBlocked(
      stopped(
        StoppedReportArgs(
          issueKey = request.issueKey,
          attempted = emptyList(),
          subtaskId = state.manifest.currentSubtaskIntent.subtaskId,
          reason = GoalRunnerStopReason.BLOCKED,
          blockedReason =
            "Cannot change QA selection on goal resume: parent workflow " +
              "'${state.parentWorkflowId}' is pinned to qa:${recorded.wireValue}.",
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
