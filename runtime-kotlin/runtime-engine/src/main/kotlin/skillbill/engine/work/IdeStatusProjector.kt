package skillbill.engine.work

import me.tatarka.inject.annotations.Inject
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeOperatorDecisionPause
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeStatusRequest
import skillbill.engine.featuretask.runner.FeatureTaskRuntimeStatusService
import skillbill.engine.featuretask.runner.OPERATOR_DECISION_QUALITY_GATE_PHASE_IDS
import skillbill.engine.featuretask.runner.operatorDecisionPause
import skillbill.engine.goalrunner.model.GoalRunnerStatusRequest
import skillbill.engine.goalrunner.status.GoalRunnerStatusService
import skillbill.engine.goalrunner.status.completed
import skillbill.engine.work.model.IdeStatusCandidate
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.shellcontent.isShellContentContractFailure
import skillbill.goalrunner.model.ExecutionLiveness
import skillbill.goalrunner.model.GoalPlanningStatusState
import skillbill.goalrunner.model.GoalRunnerStatusProjection
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.idestatus.model.IdeStatusCurrentModel
import skillbill.ports.idestatus.model.IdeStatusCurrentPhaseExecution
import skillbill.ports.idestatus.model.IdeStatusLifecycleState
import skillbill.ports.idestatus.model.IdeStatusPauseReason
import skillbill.ports.idestatus.model.IdeStatusPauseReasonCode
import skillbill.ports.idestatus.model.IdeStatusProgress
import skillbill.ports.idestatus.model.IdeStatusSnapshot
import skillbill.ports.idestatus.model.IdeStatusStep
import skillbill.ports.idestatus.model.IdeStatusWorkflowFamily
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.workflowStepStatus
import java.io.IOException
import java.nio.file.Path
import java.time.Instant

internal data class IdeStatusProjectionContext(
  val unitOfWork: UnitOfWork,
  val repositoryIdentity: String,
  val observedAt: Instant,
  val repoRoot: Path,
)

private data class ChildOptionalContext(
  val currentPhaseId: String? = null,
  val currentModel: IdeStatusCurrentModel?,
  val currentPhaseExecution: IdeStatusCurrentPhaseExecution?,
  val operatorDecisionPause: FeatureTaskRuntimeOperatorDecisionPause? = null,
) {
  companion object {
    val EMPTY = ChildOptionalContext(currentModel = null, currentPhaseExecution = null)
  }
}

@Inject
class IdeStatusProjector(
  private val workflowSnapshotValidator: WorkflowSnapshotValidator,
  private val goalRunnerStatusService: GoalRunnerStatusService,
  private val featureTaskRuntimeStatusService: FeatureTaskRuntimeStatusService,
  private val diagnostics: RuntimeDiagnostics,
) {
  private val workflowEngine = WorkflowEngine()

  internal fun project(
    candidate: IdeStatusCandidate,
    context: IdeStatusProjectionContext,
  ): IdeStatusSnapshot {
    return when (candidate.workflowFamily) {
      IdeStatusWorkflowFamily.FEATURE_GOAL -> projectGoal(candidate, context)
      IdeStatusWorkflowFamily.FEATURE_TASK_RUNTIME -> projectRuntime(candidate, context)
      IdeStatusWorkflowFamily.FEATURE_VERIFY ->
        projectWorkflowFamily(candidate, context, WorkflowFamily.VERIFY)
    }
  }

  private fun projectGoal(
    candidate: IdeStatusCandidate,
    context: IdeStatusProjectionContext,
  ): IdeStatusSnapshot {
    val issueKey =
      candidate.issueKey
        ?: return incompatible(candidate, context, "Goal work is missing an issue key.")
    val projection =
      goalRunnerStatusService.status(
        GoalRunnerStatusRequest(
          issueKey = issueKey,
          repoRoot = context.repoRoot,
        ),
      )
    return assembleGoalStatusSnapshot(candidate, context, issueKey, projection)
  }

  private fun assembleGoalStatusSnapshot(
    candidate: IdeStatusCandidate,
    context: IdeStatusProjectionContext,
    issueKey: String,
    projection: GoalRunnerStatusProjection?,
  ): IdeStatusSnapshot {
    val preliminaryLifecycle = goalLifecycle(candidate, projection)
    val planning = projection?.planning?.toIdeStatusPlanning()
    val planningStep =
      planning?.takeIf {
        it.state != GoalPlanningStatusState.PREPARED && !preliminaryLifecycle.isSettled()
      }
    val freshness = IdeStatusFreshnessClassifier.classify(candidate.updatedAt, context.observedAt)
    val childContext = childOptionalContext(projection?.currentChildWorkflowId, preliminaryLifecycle)
    val lifecycle = goalLifecycleForOperatorBlock(preliminaryLifecycle, childContext)
    val childPhaseStep =
      childContext.currentPhaseId
        ?.takeIf { it.isNotBlank() && planningStep == null && lifecycle != IdeStatusLifecycleState.TERMINAL }
    val step =
      goalStep(
        planningStep,
        childPhaseStep
          ?: projection?.currentStep?.takeUnless { lifecycle == IdeStatusLifecycleState.TERMINAL },
        lifecycle,
      )
    val total =
      (projection?.let { it.completeCount + it.pendingCount + it.blockedCount })
        ?.takeIf { it > 0 }
    val progress =
      total?.let {
        IdeStatusProgress(completed = projection.completeCount, total = it)
      }
    val currentSubtask = goalCurrentSubtask(projection, context)
    val (activityAt, activityLabel) = agentActivityFields(context.unitOfWork, candidate.workflowId)
    return IdeStatusSnapshot(
      repositoryIdentity = context.repositoryIdentity,
      issueKey = issueKey,
      workflowId = candidate.workflowId,
      workflowFamily = IdeStatusWorkflowFamily.FEATURE_GOAL,
      lifecycleState = lifecycle,
      currentStep = step,
      progress = progress,
      startedAt = candidate.startedAt,
      currentSubtask = currentSubtask,
      currentModel = childContext.currentModel,
      planning = planning,
      currentPhaseExecution = planningStep?.let { null } ?: childContext.currentPhaseExecution,
      pauseRequested = projection?.pauseRequested == true && projection.paused != true,
      pausedAt =
        parseInstantOrNull(projection?.pausedAt)
          ?.takeIf { lifecycle == IdeStatusLifecycleState.PAUSED },
      pauseReason = goalPauseReason(lifecycle, projection, childContext),
      activeDurationMs = projection?.recordedActiveDurationMs(),
      activeDurationAsOf = projection?.liveActiveDurationAnchor(),
      lastAgentActivityAt = activityAt,
      lastAgentActivityLabel = activityLabel,
      updatedAt = candidate.updatedAt,
      freshness = freshness,
      summary =
        planningStep?.takeIf { lifecycle != IdeStatusLifecycleState.PAUSED }
          ?.let { goalPlanningSummary(issueKey, it) }
          ?: goalSummary(
            issueKey,
            lifecycle,
            step.label,
            projection?.blockedCount ?: 0,
            childContext.operatorDecisionPause,
          ),
    )
  }

  private fun goalLifecycle(
    candidate: IdeStatusCandidate,
    projection: GoalRunnerStatusProjection?,
  ): IdeStatusLifecycleState {
    val settledComplete =
      projection != null &&
        projection.pendingCount == 0 &&
        projection.blockedCount == 0 &&
        projection.completeCount > 0 &&
        projection.executionLiveness != ExecutionLiveness.LIVE
    if (settledComplete) return IdeStatusLifecycleState.TERMINAL
    if (candidate.lifecycleState != IdeStatusLifecycleState.ACTIVE) return candidate.lifecycleState
    return when {
      projection?.paused == true -> IdeStatusLifecycleState.PAUSED
      projection?.executionLiveness == ExecutionLiveness.IDLE -> IdeStatusLifecycleState.IDLE
      else -> IdeStatusLifecycleState.ACTIVE
    }
  }

  private fun goalLifecycleForOperatorBlock(
    lifecycle: IdeStatusLifecycleState,
    childContext: ChildOptionalContext,
  ): IdeStatusLifecycleState {
    val pause = childContext.operatorDecisionPause ?: return lifecycle
    if (lifecycle != IdeStatusLifecycleState.ACTIVE) return lifecycle
    if (pause.phaseId in OPERATOR_DECISION_QUALITY_GATE_PHASE_IDS) {
      return IdeStatusLifecycleState.BLOCKED
    }
    return lifecycle
  }

  private fun goalPauseReason(
    lifecycle: IdeStatusLifecycleState,
    projection: GoalRunnerStatusProjection?,
    childContext: ChildOptionalContext,
  ): IdeStatusPauseReason? {
    childContext.operatorDecisionPause?.let { pause ->
      if (lifecycle == IdeStatusLifecycleState.PAUSED || lifecycle == IdeStatusLifecycleState.BLOCKED) {
        return IdeStatusPauseReason.of(IdeStatusPauseReasonCode.AWAITING_OPERATOR_DECISION, pause.reason)
      }
    }
    if (lifecycle != IdeStatusLifecycleState.PAUSED) return null
    return IdeStatusPauseReasonCode.fromWire(projection?.pauseReason)
      ?.let { code -> IdeStatusPauseReason.of(code, null) }
  }

  private fun childOptionalContext(
    childWorkflowId: String?,
    lifecycle: IdeStatusLifecycleState,
  ): ChildOptionalContext {
    if (lifecycle == IdeStatusLifecycleState.TERMINAL) return ChildOptionalContext.EMPTY
    val workflowId = childWorkflowId?.takeIf(String::isNotBlank) ?: return ChildOptionalContext.EMPTY
    val degraded =
      "IDE status omitted optional child context for workflow '$workflowId': " +
        "the child's durable status could not be read."
    val status =
      try {
        featureTaskRuntimeStatusService.status(
          FeatureTaskRuntimeStatusRequest(workflowId = workflowId),
        )
      } catch (error: SkillBillRuntimeException) {
        error.rethrowUnless(error.isShellContentContractFailure())
        RuntimeDiagnosticsBestEffortWarning.record(diagnostics, degraded, error)
        null
      } catch (error: IOException) {
        RuntimeDiagnosticsBestEffortWarning.record(diagnostics, degraded, error)
        null
      } ?: return ChildOptionalContext.EMPTY
    return ChildOptionalContext(
      currentPhaseId = status.currentPhaseId?.takeIf(String::isNotBlank),
      currentModel =
        status.currentPhaseId?.let { phaseId ->
          status.phases.firstOrNull { it.phaseId == phaseId }?.toIdeStatusCurrentModel()
        },
      currentPhaseExecution = status.currentPhaseExecution,
      operatorDecisionPause = status.operatorDecisionPause,
    )
  }

  private fun projectRuntime(
    candidate: IdeStatusCandidate,
    context: IdeStatusProjectionContext,
  ): IdeStatusSnapshot {
    val snapshot =
      context.unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, candidate.workflowId)
        ?: return incompatible(candidate, context, "Runtime workflow snapshot is missing.")
    val status =
      featureTaskRuntimeStatusService.status(
        FeatureTaskRuntimeStatusRequest(
          workflowId = candidate.workflowId,
        ),
      )
    val stepId =
      status?.currentPhaseId?.takeIf(String::isNotBlank)
        ?: snapshot.currentStepId.takeIf(String::isNotBlank)
        ?: "unknown"
    val stepLabel =
      WorkflowFamily.TASK_RUNTIME.definition.stepLabels[stepId]
        ?: stepId.replace('_', ' ').replaceFirstChar { it.titlecase() }
    val phaseTotal = status?.phases?.size?.takeIf { it > 0 }
    val progress =
      phaseTotal?.let {
        IdeStatusProgress(completed = status.completeCount, total = it)
      }
    val startedAt = snapshot.startedAt ?: candidate.startedAt
    val updatedAt = candidate.updatedAt
    val (activityAt, activityLabel) = agentActivityFields(context.unitOfWork, candidate.workflowId)
    return IdeStatusSnapshot(
      repositoryIdentity = context.repositoryIdentity,
      issueKey = candidate.issueKey,
      workflowId = candidate.workflowId,
      workflowFamily = IdeStatusWorkflowFamily.FEATURE_TASK_RUNTIME,
      lifecycleState = candidate.lifecycleState,
      currentStep = IdeStatusStep(id = stepId, label = stepLabel),
      progress = progress,
      startedAt = startedAt,
      currentModel = status?.phases?.firstOrNull { it.phaseId == stepId }?.toIdeStatusCurrentModel(),
      currentPhaseExecution = status?.currentPhaseExecution?.takeIf { it.phaseId == stepId },
      pauseReason =
        status?.operatorDecisionPause?.let { pause ->
          IdeStatusPauseReason.of(IdeStatusPauseReasonCode.AWAITING_OPERATOR_DECISION, pause.reason)
        },
      lastAgentActivityAt = activityAt,
      lastAgentActivityLabel = activityLabel,
      updatedAt = updatedAt,
      freshness = IdeStatusFreshnessClassifier.classify(updatedAt, context.observedAt),
      summary =
        familySummary(
          IdeStatusWorkflowFamily.FEATURE_TASK_RUNTIME,
          candidate.issueKey,
          candidate.lifecycleState,
          stepLabel,
        ),
    )
  }

  private fun projectWorkflowFamily(
    candidate: IdeStatusCandidate,
    context: IdeStatusProjectionContext,
    family: WorkflowFamily,
  ): IdeStatusSnapshot {
    val snapshot =
      context.unitOfWork.workflowStates.get(family, candidate.workflowId)
        ?: return incompatible(
          candidate,
          context,
          "${family.humanName} workflow snapshot is missing.",
        )
    workflowSnapshotValidator.validate(snapshot, family.definition.workflowName)
    val view = workflowEngine.snapshotView(family.definition, snapshot)
    val stepId = view.currentStepId.takeIf(String::isNotBlank) ?: "unknown"
    val stepLabel =
      family.definition.stepLabels[stepId]
        ?: stepId.replace('_', ' ').replaceFirstChar { it.titlecase() }
    val completed =
      view.steps.count {
        it.status.workflowStepStatus() in setOf(WorkflowStepStatus.COMPLETED, WorkflowStepStatus.SKIPPED)
      }
    val total = family.definition.stepIds.size
    val progress = IdeStatusProgress(completed = completed, total = total).takeIf { total > 0 }
    val startedAt = snapshot.startedAt ?: candidate.startedAt
    val updatedAt = candidate.updatedAt
    val (activityAt, activityLabel) = agentActivityFields(context.unitOfWork, candidate.workflowId)
    val wireFamily =
      when (family) {
        WorkflowFamily.VERIFY -> IdeStatusWorkflowFamily.FEATURE_VERIFY
        WorkflowFamily.TASK_RUNTIME -> IdeStatusWorkflowFamily.FEATURE_TASK_RUNTIME
      }
    return IdeStatusSnapshot(
      repositoryIdentity = context.repositoryIdentity,
      issueKey = candidate.issueKey,
      workflowId = candidate.workflowId,
      workflowFamily = wireFamily,
      lifecycleState = candidate.lifecycleState,
      currentStep = IdeStatusStep(id = stepId, label = stepLabel),
      progress = progress,
      startedAt = startedAt,
      lastAgentActivityAt = activityAt,
      lastAgentActivityLabel = activityLabel,
      updatedAt = updatedAt,
      freshness = IdeStatusFreshnessClassifier.classify(updatedAt, context.observedAt),
      summary = familySummary(wireFamily, candidate.issueKey, candidate.lifecycleState, stepLabel),
    )
  }

  private fun incompatible(
    candidate: IdeStatusCandidate,
    context: IdeStatusProjectionContext,
    message: String,
  ): IdeStatusSnapshot =
    IdeStatusProblemSnapshots.incompatibleRecord(
      repositoryIdentity = context.repositoryIdentity,
      observedAt = context.observedAt,
      message = message,
      workflowId = candidate.workflowId,
    )
}
