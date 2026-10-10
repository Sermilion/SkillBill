package skillbill.engine.work

import me.tatarka.inject.annotations.Inject
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeOperatorDecisionPause
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeStatusRequest
import skillbill.engine.featuretask.runner.FeatureTaskRuntimeStatusService
import skillbill.engine.featuretask.runner.OPERATOR_DECISION_QUALITY_GATE_PHASE_IDS
import skillbill.engine.featuretask.runner.operatorDecisionPause
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.model.GoalRunnerStatusRequest
import skillbill.engine.goalrunner.status.GoalRunnerStatusService
import skillbill.engine.goalrunner.status.completed
import skillbill.engine.work.model.IdeStatusCandidate
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.shellcontent.isInvalidWorkflowStateFailure
import skillbill.error.shellcontent.isShellContentContractFailure
import skillbill.goalrunner.model.ExecutionLiveness
import skillbill.goalrunner.model.GoalPlanningStatusState
import skillbill.goalrunner.model.GoalRunnerStatusProjection
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.idestatus.model.IdeStatusCurrentModel
import skillbill.ports.idestatus.model.IdeStatusCurrentPhaseExecution
import skillbill.ports.idestatus.model.IdeStatusExecutionIdentity
import skillbill.ports.idestatus.model.IdeStatusExecutionScope
import skillbill.ports.idestatus.model.IdeStatusFreshness
import skillbill.ports.idestatus.model.IdeStatusLifecycleState
import skillbill.ports.idestatus.model.IdeStatusPauseReason
import skillbill.ports.idestatus.model.IdeStatusPauseReasonCode
import skillbill.ports.idestatus.model.IdeStatusPlanning
import skillbill.ports.idestatus.model.IdeStatusProgress
import skillbill.ports.idestatus.model.IdeStatusSnapshot
import skillbill.ports.idestatus.model.IdeStatusStep
import skillbill.ports.idestatus.model.IdeStatusWorkflowFamily
import skillbill.ports.idestatus.model.StandalonePhaseStatusRecord
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
  val branchCorrelation: String?,
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
  private val manifestStore: GoalRunnerManifestStore,
  private val diagnostics: RuntimeDiagnostics,
) {
  private val workflowEngine = WorkflowEngine()

  internal fun project(
    candidate: IdeStatusCandidate,
    context: IdeStatusProjectionContext,
  ): IdeStatusSnapshot {
    candidate.standaloneStatus?.let { return projectStandalonePhase(context, it) }
    return when (candidate.workflowFamily) {
      IdeStatusWorkflowFamily.FEATURE_GOAL -> projectGoal(candidate, context)
      IdeStatusWorkflowFamily.FEATURE_TASK_RUNTIME ->
        goalBackedRuntimeSnapshot(candidate, context) ?: projectRuntime(candidate, context)
      IdeStatusWorkflowFamily.FEATURE_VERIFY ->
        projectWorkflowFamily(candidate, context, WorkflowFamily.VERIFY)
    }
  }

  internal fun readableForSelection(
    candidate: IdeStatusCandidate,
    context: IdeStatusProjectionContext,
  ): Boolean {
    if (candidate.workflowFamily != IdeStatusWorkflowFamily.FEATURE_VERIFY) return true
    val snapshot =
      context.unitOfWork.workflowStates.get(WorkflowFamily.VERIFY, candidate.workflowId) ?: return true
    return try {
      workflowSnapshotValidator.validate(snapshot, WorkflowFamily.VERIFY.definition.workflowName)
      true
    } catch (error: SkillBillRuntimeException) {
      error.rethrowUnless(error.isInvalidWorkflowStateFailure())
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "seam=ide_status value_expected=readable_workflow_row value_used=skipped " +
          "workflow_id=${candidate.workflowId} error=${error.message.orEmpty()}",
        error,
      )
      false
    }
  }

  private fun projectStandalonePhase(
    context: IdeStatusProjectionContext,
    record: StandalonePhaseStatusRecord,
  ): IdeStatusSnapshot {
    val lifecycle = lifecycleFromStandaloneState(record.lifecycleState)
    val execution =
      IdeStatusExecutionIdentity(
        scope = IdeStatusExecutionScope.STANDALONE_PHASE,
        executionId = record.executionId,
        statusStoreId = record.statusStoreId,
        runSequence = record.runSequence,
        statusRevision = record.statusRevision,
        invocationId = record.invocationId,
        phaseId = record.phaseId,
      )
    return IdeStatusSnapshot(
      repositoryIdentity = context.repositoryIdentity,
      branchCorrelation = record.branchCorrelation,
      issueKey = record.issueKey,
      workflowId = record.workflowId,
      workflowFamily = record.workflowId?.let { IdeStatusWorkflowFamily.FEATURE_TASK_RUNTIME },
      execution = execution,
      currentActivity = record.currentActivity,
      lifecycleState = lifecycle,
      currentStep = IdeStatusStep(record.currentStep, record.currentStep),
      startedAt = record.startedAt,
      activeDurationMs = record.activeDurationMs,
      activeDurationAsOf = record.activeDurationAsOf,
      updatedAt = record.updatedAt,
      freshness = IdeStatusFreshnessClassifier.classify(record.updatedAt, context.observedAt),
      summary = record.terminalResult ?: "Standalone phase ${record.phaseId} is ${record.lifecycleState}.",
    )
  }

  private fun lifecycleFromStandaloneState(state: String): IdeStatusLifecycleState =
    when (state) {
      "active", "running" -> IdeStatusLifecycleState.ACTIVE
      "paused", "runner_interrupted" -> IdeStatusLifecycleState.PAUSED
      "blocked" -> IdeStatusLifecycleState.BLOCKED
      "failed" -> IdeStatusLifecycleState.FAILED
      "terminal", "completed", "success" -> IdeStatusLifecycleState.TERMINAL
      else -> IdeStatusLifecycleState.FAILED
    }

  private fun goalBackedRuntimeSnapshot(
    candidate: IdeStatusCandidate,
    context: IdeStatusProjectionContext,
  ): IdeStatusSnapshot? {
    val issueKey = candidate.issueKey ?: return null
    val manifestState = manifestStore.readByIssueKey(issueKey, context.repoRoot) ?: return null
    val projection =
      goalRunnerStatusService.status(
        GoalRunnerStatusRequest(
          issueKey = issueKey,
          repoRoot = context.repoRoot,
        ),
      )
    val completionRecorded =
      context.unitOfWork.goalRunnerControls.controlState(manifestState.parentWorkflowId).goalCompletedAt != null
    val goalCandidate =
      candidate.copy(
        workflowId = manifestState.parentWorkflowId,
        workflowFamily = IdeStatusWorkflowFamily.FEATURE_GOAL,
        isGoalAuthoritative = true,
      )
    val preliminaryLifecycle = goalLifecycle(goalCandidate, projection, completionRecorded)
    val liveFinalization = goalProjectsLiveFinalizationStep(projection, completionRecorded)
    if (!goalShowsOpenCiMonitor(projection, completionRecorded, preliminaryLifecycle) && !liveFinalization) {
      return null
    }
    return assembleGoalStatusSnapshot(goalCandidate, context, issueKey, projection)
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
    val completionRecorded =
      context.unitOfWork.goalRunnerControls.controlState(candidate.workflowId).goalCompletedAt != null
    val preliminaryLifecycle = goalLifecycle(candidate, projection, completionRecorded)
    val planning = projection?.planning?.toIdeStatusPlanning()
    val planningStep = goalPlanningStep(planning, preliminaryLifecycle)
    val childContext = childOptionalContext(projection?.currentChildWorkflowId, preliminaryLifecycle)
    val lifecycle = goalLifecycleForOperatorBlock(preliminaryLifecycle, childContext)
    val openCiMonitor = goalShowsOpenCiMonitor(projection, completionRecorded, lifecycle)
    val liveFinalization = goalProjectsLiveFinalizationStep(projection, completionRecorded)
    val freshness =
      if (openCiMonitor) {
        IdeStatusFreshness.FRESH
      } else {
        IdeStatusFreshnessClassifier.classify(candidate.updatedAt, context.observedAt)
      }
    val step =
      goalCurrentStep(
        planningStep,
        childContext.currentPhaseId,
        projection?.currentStep,
        lifecycle,
        GoalCurrentStepSignals(
          openCiMonitor = openCiMonitor,
          liveFinalizationStep = liveFinalization,
        ),
      )
    val (activityAt, activityLabel) = agentActivityFields(context.unitOfWork, candidate.workflowId)
    return IdeStatusSnapshot(
      repositoryIdentity = context.repositoryIdentity,
      branchCorrelation = context.branchCorrelation,
      issueKey = issueKey,
      workflowId = candidate.workflowId,
      workflowFamily = IdeStatusWorkflowFamily.FEATURE_GOAL,
      execution = candidate.execution,
      lifecycleState = lifecycle,
      currentStep = step,
      progress = projection?.toIdeStatusProgress(),
      startedAt = candidate.startedAt,
      currentSubtask = goalCurrentSubtask(projection, context),
      currentModel = childContext.currentModel,
      planning = planning,
      currentPhaseExecution = childContext.currentPhaseExecution.takeIf { planningStep == null },
      pauseRequested = goalPauseRequested(projection),
      pausedAt = goalPausedAt(projection, lifecycle),
      pauseReason = goalPauseReason(lifecycle, projection, childContext),
      activeDurationMs = projection?.recordedActiveDurationMs(),
      activeDurationAsOf = projection?.liveActiveDurationAnchor(),
      lastAgentActivityAt = activityAt,
      lastAgentActivityLabel = activityLabel,
      updatedAt = if (openCiMonitor) context.observedAt else candidate.updatedAt,
      freshness = freshness,
      currentActivity = OPEN_CI_MONITOR_ACTIVITY.takeIf { openCiMonitor },
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

  private fun goalPlanningStep(
    planning: IdeStatusPlanning?,
    preliminaryLifecycle: IdeStatusLifecycleState,
  ): IdeStatusPlanning? =
    planning?.takeIf {
      it.state != GoalPlanningStatusState.PREPARED && !preliminaryLifecycle.isSettled()
    }

  private fun goalPauseRequested(projection: GoalRunnerStatusProjection?): Boolean =
    projection?.pauseRequested == true && projection.paused != true

  private fun goalPausedAt(
    projection: GoalRunnerStatusProjection?,
    lifecycle: IdeStatusLifecycleState,
  ): Instant? =
    parseInstantOrNull(projection?.pausedAt)
      ?.takeIf { lifecycle == IdeStatusLifecycleState.PAUSED }

  private fun goalLifecycle(
    candidate: IdeStatusCandidate,
    projection: GoalRunnerStatusProjection?,
    completionRecorded: Boolean,
  ): IdeStatusLifecycleState {
    if (completionRecorded && projection?.executionLiveness != ExecutionLiveness.LIVE) {
      return IdeStatusLifecycleState.TERMINAL
    }
    if (candidate.lifecycleState == IdeStatusLifecycleState.TERMINAL) {
      return if (projection?.executionLiveness == ExecutionLiveness.LIVE) {
        IdeStatusLifecycleState.ACTIVE
      } else {
        IdeStatusLifecycleState.IDLE
      }
    }
    if (candidate.lifecycleState == IdeStatusLifecycleState.PAUSED &&
      projection?.executionLiveness == ExecutionLiveness.LIVE && !projection.paused
    ) {
      return IdeStatusLifecycleState.ACTIVE
    }
    return when {
      candidate.lifecycleState != IdeStatusLifecycleState.ACTIVE -> candidate.lifecycleState
      projection?.paused == true -> IdeStatusLifecycleState.PAUSED
      projection?.executionLiveness == ExecutionLiveness.IDLE &&
        !goalStaysOnOpenCiMonitor(projection, completionRecorded) -> IdeStatusLifecycleState.IDLE
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
      branchCorrelation = context.branchCorrelation,
      issueKey = candidate.issueKey,
      workflowId = candidate.workflowId,
      workflowFamily = IdeStatusWorkflowFamily.FEATURE_TASK_RUNTIME,
      execution = candidate.execution,
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
    try {
      workflowSnapshotValidator.validate(snapshot, family.definition.workflowName)
    } catch (error: SkillBillRuntimeException) {
      error.rethrowUnless(error.isInvalidWorkflowStateFailure())
      return incompatible(candidate, context, error.message ?: "Incompatible workflow record.")
    }
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
      branchCorrelation = context.branchCorrelation,
      issueKey = candidate.issueKey,
      workflowId = candidate.workflowId,
      workflowFamily = wireFamily,
      execution = candidate.execution,
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
