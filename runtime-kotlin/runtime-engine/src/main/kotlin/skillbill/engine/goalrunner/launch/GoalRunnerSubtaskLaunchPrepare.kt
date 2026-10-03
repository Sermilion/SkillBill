package skillbill.engine.goalrunner.launch

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.baseBranch
import skillbill.application.workflow.persist.generateWorkflowId
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionPlanResolver
import skillbill.engine.featuretask.model.execution.FeatureTaskRuntimeExecutionPlanCreationRequest
import skillbill.engine.goalrunner.execution.core.StoppedReportArgs
import skillbill.engine.goalrunner.execution.core.workflowIdFor
import skillbill.engine.goalrunner.execution.support.GoalRunnerIterationResult
import skillbill.engine.goalrunner.execution.support.PreparedLaunch
import skillbill.engine.goalrunner.execution.support.RUNTIME_WORKFLOW_ID_PREFIX
import skillbill.engine.goalrunner.execution.support.branchPlanFor
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.model.GoalRunnerChildWorkflowSetup
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerRunEvent
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.engine.goalrunner.planning.model.GoalPlanningSweepOutcome
import skillbill.engine.goalrunner.planning.outcome.canonicalRepository
import skillbill.engine.goalrunner.planning.recovery.goalPlanningChildImportConflictBlockedReason
import skillbill.engine.goalrunner.reset.reviewBaselineBlockedReason
import skillbill.engine.goalrunner.review.effectiveAgentAddonSelection
import skillbill.engine.goalrunner.status.stopped
import skillbill.engine.goalrunner.status.supervisionEvent
import skillbill.error.shellcontent.IncompatibleGoalPlanningPreparationRecoveryError
import skillbill.goalrunner.GoalRunnerQualityGateSelectionResolver
import skillbill.goalrunner.model.GoalRunnerSelection
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.ports.goalrunner.runner.model.GoalRunnerReviewPolicy
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaselineResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationStatus
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.decomposition.withAttemptedSubtask
import skillbill.workflow.decomposition.withBranchSetupBlockedSubtask
import skillbill.workflow.decomposition.withWorkflowId
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.model.decompositionStatus
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Path
import java.time.Clock
import kotlin.random.Random

@Inject
class GoalRunnerSubtaskLaunchPrepare(
  private val manifestStore: GoalRunnerManifestStore,
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  private val gitOperations: WorkflowGitOperations,
  private val repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
  private val clock: Clock,
  private val random: Random,
  private val executionPlans: FeatureTaskRuntimeExecutionPlanResolver,
) {
  fun goalReviewBaseline(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    request: GoalRunnerRunRequest,
  ): GoalSubtaskReviewBaselineResult {
    val existingWorkflowId = state.manifest.workflowIdFor(subtaskId)
    if (existingWorkflowId != null) {
      return runCatching {
        outcomeStore.goalSubtaskReviewState(existingWorkflowId)
          ?.let { reviewState ->
            GoalSubtaskReviewBaselineResult(
              status = WorkflowGitOperationStatus.OK,
              baseline = GoalSubtaskReviewBaseline(reviewState.reviewBaseSha, reviewState.baselineUntrackedPaths),
            )
          }
          ?: GoalSubtaskReviewBaselineResult(
            status = WorkflowGitOperationStatus.ERROR,
            error =
              "Goal-subtask review state is missing for existing child '$existingWorkflowId'; " +
                "refusing to recapture its immutable baseline.",
          )
      }.getOrElse { error ->
        GoalSubtaskReviewBaselineResult(
          status = WorkflowGitOperationStatus.ERROR,
          error =
            "Goal-subtask review persistence is malformed for existing child '$existingWorkflowId': " +
              error.message.orEmpty(),
        )
      }
    }
    val branch =
      state.manifest.branchPlanFor(subtaskId).branch.takeIf(String::isNotBlank)
        ?: state.manifest.featureBranch?.takeIf(String::isNotBlank)
        ?: return GoalSubtaskReviewBaselineResult(
          status = WorkflowGitOperationStatus.ERROR,
          error = "Goal subtask '$subtaskId' has no durable child branch for review baseline capture.",
        )
    return gitOperations.captureGoalSubtaskReviewBaseline(request.repoRoot, branch)
  }

  internal fun blockedReviewBaselineIteration(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    reason: String,
    request: GoalRunnerRunRequest,
  ): GoalRunnerIterationResult {
    val blockedReason = reviewBaselineBlockedReason(state.manifest, subtaskId, reason)
    val blocked = state.manifest.withBranchSetupBlockedSubtask(subtaskId, blockedReason)
    val saved = manifestStore.save(state.copy(manifest = blocked))
    request.eventSink.emit(
      GoalRunnerRunEvent.SubtaskStopped(
        issueKey = saved.manifest.issueKey,
        subtaskId = subtaskId,
        reason = GoalRunnerStopReason.BLOCKED.name.lowercase(),
        blockedReason = blockedReason,
        currentStepId = "preplan",
      ),
    )
    return GoalRunnerIterationResult(
      state = saved,
      report =
        stopped(
          StoppedReportArgs(
            issueKey = saved.manifest.issueKey,
            attempted = emptyList(),
            subtaskId = subtaskId,
            reason = GoalRunnerStopReason.BLOCKED,
            blockedReason = blockedReason,
            workflowId = state.manifest.workflowIdFor(subtaskId),
            lastResumableStep = "preplan",
          ),
        ),
    )
  }

  internal fun blockedOnRecoveryError(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    error: Throwable,
    request: GoalRunnerRunRequest,
  ): GoalRunnerIterationResult {
    val (targetSubtaskId, reason) =
      when (error) {
        is IncompatibleGoalPlanningPreparationRecoveryError ->
          error.subtaskId to
            goalPlanningChildImportConflictBlockedReason(
              state.manifest.issueKey,
              error.subtaskId,
              error,
            )
        else -> throw error
      }
    state.manifest.workflowIdFor(targetSubtaskId)?.takeIf(String::isNotBlank)?.let { workflowId ->
      runCatching {
        outcomeStore.markBlocked(
          workflowId = workflowId,
          blockedReason = reason,
          lastResumableStep = "preplan",
          supervisionEvent = null,
        )
      }
    }
    return blockedReviewBaselineIteration(state, targetSubtaskId, reason, request)
  }

  fun emitGoalReviewSummaries(
    issueKey: String,
    subtaskId: Int,
    workflowId: String,
    request: GoalRunnerRunRequest,
  ) {
    outcomeStore.unemittedGoalReviewPasses(workflowId).forEach { pass ->
      request.eventSink.emit(
        GoalRunnerRunEvent.SubtaskReviewSummary(
          issueKey = issueKey,
          subtaskId = subtaskId,
          passNumber = pass.passNumber,
          verdict = pass.verdict.wireValue,
          findingCount = pass.findings.size,
          unresolvedFindingCount = pass.unresolvedFindingCount,
          findings = pass.findings,
        ),
      )
      check(outcomeStore.acknowledgeGoalReviewPass(workflowId, pass.passNumber)) {
        "Goal-subtask review summary pass ${pass.passNumber} could not be acknowledged after emission."
      }
    }
  }

  internal fun prepareAttemptedLaunch(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    request: GoalRunnerRunRequest,
    reviewBaseline: GoalSubtaskReviewBaseline,
    planning: GoalPlanningSweepOutcome.PreparedAll,
  ): PreparedLaunch {
    val priorWorkflowId = state.manifest.workflowIdFor(subtaskId)
    val subtask =
      requireNotNull(state.manifest.subtasks.firstOrNull { it.id == subtaskId }) {
        "Goal subtask '$subtaskId' is missing from the decomposition manifest."
      }
    val executionPlan =
      executionPlans.resolveCreation(
        FeatureTaskRuntimeExecutionPlanCreationRequest(
          repoRoot = request.repoRoot,
          definition = SkeletonDefinition.GOAL_CHILD,
          reviewMode = request.codeReviewMode ?: CodeReviewExecutionMode.DEFAULT,
          qualityGate = GoalRunnerQualityGateSelectionResolver.resolve(state.manifest, subtaskId),
          validationDepth = ValidationDepth.FULL,
          timeout = request.timeout,
          workflowId = priorWorkflowId,
        ),
      )
    val firstRun = priorWorkflowId == null
    val resumesBlockedChild = subtask.status.decompositionStatus() == DecompositionStatus.BLOCKED && !firstRun
    val assignedWorkflowId = priorWorkflowId ?: generateWorkflowId(RUNTIME_WORKFLOW_ID_PREFIX, clock, random)
    val canonicalRepository = repositoryEnclosingRootPort.canonicalPath(request.repoRoot)
    val governedSpecPath = governedChildSpecPath(subtaskId, subtask.specPath, canonicalRepository)
    val attemptedManifest =
      state.manifest.withAttemptedSubtask(subtaskId)
        .let { manifest -> if (firstRun) manifest.withWorkflowId(subtaskId, assignedWorkflowId) else manifest }
    val attemptedState =
      run {
        val branch =
          attemptedManifest.branchPlanFor(subtaskId).branch.takeIf(String::isNotBlank)
            ?: attemptedManifest.featureBranch?.takeIf(String::isNotBlank)
            ?: error("Goal subtask '$subtaskId' has no durable branch for review baseline persistence.")
        manifestStore.saveNewChildWorkflow(
          state.copy(manifest = attemptedManifest),
          GoalRunnerChildWorkflowSetup(
            subtaskId = subtaskId,
            workflowId = assignedWorkflowId,
            goalBranch = branch,
            normalizedIssueKey = FeatureTaskExecutionIdentityPolicy.canonicalIssueKey(state.manifest.issueKey),
            repositoryIdentity = repositoryEnclosingRootPort.repositoryIdentity(canonicalRepository),
            governedSpecPath = governedSpecPath,
            reviewBaseline = reviewBaseline,
            reviewPolicy =
              GoalRunnerReviewPolicy(
                codeReviewMode = request.codeReviewMode ?: CodeReviewExecutionMode.DEFAULT,
                agentAddonSelection = manifestStore.effectiveAgentAddonSelection(state.parentWorkflowId, request),
              ),
            planningHydration = planning.hydrationFor(subtaskId),
            executionPlan = executionPlan,
            operatorResumePhaseId =
              (
                subtask.lastResumableStep?.takeIf(String::isNotBlank)
                  ?: FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT
              )
                .takeIf { resumesBlockedChild },
            operatorResumeReason =
              "Operator resumed the goal after a blocked stop at subtask $subtaskId."
                .takeIf { resumesBlockedChild },
          ),
        )
      }
    return PreparedLaunch(attemptedState, assignedWorkflowId.takeIf { firstRun })
  }

  private fun governedChildSpecPath(
    subtaskId: Int,
    specPath: String,
    canonicalRepository: Path,
  ): String {
    val rawSpecPath =
      requireNotNull(
        specPath.takeIf(String::isNotBlank),
      ) { "Goal subtask '$subtaskId' has no governed spec path." }
    val lexicalSpecPath =
      Path.of(rawSpecPath).let { path ->
        (if (path.isAbsolute) path else canonicalRepository.resolve(path)).toAbsolutePath().normalize()
      }
    val resolvedSpecPath = repositoryEnclosingRootPort.optionalRealPath(lexicalSpecPath) ?: lexicalSpecPath
    check(resolvedSpecPath.startsWith(canonicalRepository)) {
      "Goal subtask '$subtaskId' governed spec path escapes repository '$canonicalRepository'."
    }
    return canonicalRepository.relativize(resolvedSpecPath).joinToString("/")
  }

  internal fun goalBranchSetupFailure(
    state: GoalRunnerManifestState,
    selection: GoalRunnerSelection.Run,
    request: GoalRunnerRunRequest,
  ): GoalRunnerIterationResult? {
    val subtaskId = selection.decision.subtask.id
    val branchPlan = state.manifest.branchPlanFor(subtaskId)
    if (branchPlan.branch.isBlank()) {
      return null
    }
    val checkout = gitOperations.checkoutBranch(request.repoRoot, branchPlan.branch, branchPlan.baseBranch)
    val setupError =
      if (checkout !is WorkflowGitOperationResult.Ok) {
        checkout.error
      } else if (branchPlan.validateBase) {
        gitOperations.validateBranchBase(request.repoRoot, branchPlan.branch, branchPlan.baseBranch)
          .takeUnless { it is WorkflowGitOperationResult.Ok }
          ?.error
          .orEmpty()
      } else {
        ""
      }
    return setupError.takeIf(String::isNotBlank)?.let { error ->
      blockedBranchSetupIteration(state, subtaskId, error, request)
    }
  }

  private fun blockedBranchSetupIteration(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    reason: String,
    request: GoalRunnerRunRequest,
  ): GoalRunnerIterationResult {
    val blocked = state.manifest.withBranchSetupBlockedSubtask(subtaskId, reason)
    val saved = manifestStore.save(state.copy(manifest = blocked))
    request.eventSink.emit(
      GoalRunnerRunEvent.SubtaskStopped(
        issueKey = saved.manifest.issueKey,
        subtaskId = subtaskId,
        reason = GoalRunnerStopReason.BLOCKED.name.lowercase(),
        blockedReason = reason,
        currentStepId = "create_branch",
      ),
    )
    return GoalRunnerIterationResult(
      state = saved,
      report =
        stopped(
          StoppedReportArgs(
            issueKey = saved.manifest.issueKey,
            attempted = emptyList(),
            subtaskId = subtaskId,
            reason = GoalRunnerStopReason.BLOCKED,
            blockedReason = reason,
            workflowId = null,
            lastResumableStep = "create_branch",
          ),
        ),
    )
  }
}
