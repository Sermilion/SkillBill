package skillbill.engine.work

import me.tatarka.inject.annotations.Inject
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.lifecycle.continuation.isRuntimeAgentId
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeOperatorDecisionPause
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimePhaseStatus
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeStatusRequest
import skillbill.engine.featuretask.qa.capability.QaCapabilityResolution
import skillbill.engine.featuretask.qa.capability.QaCapabilityResolver
import skillbill.engine.featuretask.runner.FeatureTaskRuntimeStatusService
import skillbill.engine.featuretask.runner.OPERATOR_DECISION_QUALITY_GATE_PHASE_IDS
import skillbill.engine.featuretask.runner.operatorDecisionPause
import skillbill.engine.goal.GOAL_PLANNING_OPERATION_KIND
import skillbill.engine.goal.GoalManifestProjectionReader
import skillbill.engine.goal.GoalRunnerStatusPort
import skillbill.engine.goal.GoalRunnerStatusRequest
import skillbill.engine.goal.StandalonePlanReportPort
import skillbill.engine.work.eligibility.standaloneLaunchAgent
import skillbill.engine.work.failure.IdeStatusProblemSnapshots
import skillbill.engine.work.model.IdeStatusCandidate
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.shellcontent.isInvalidWorkflowStateFailure
import skillbill.error.shellcontent.isShellContentContractFailure
import skillbill.goalrunner.ledger.declaredProgressEventFrom
import skillbill.goalrunner.model.ExecutionLiveness
import skillbill.goalrunner.model.GoalPlanningStatusSnapshot
import skillbill.goalrunner.model.GoalPlanningStatusState
import skillbill.goalrunner.model.GoalRunnerAssessmentResult
import skillbill.goalrunner.model.GoalRunnerAssessmentState
import skillbill.goalrunner.model.GoalRunnerStatusProjection
import skillbill.idestatus.model.IdeStatusExecutionScope
import skillbill.idestatus.model.IdeStatusLifecycleState
import skillbill.idestatus.model.IdeStatusPolicy
import skillbill.idestatus.model.IdeStatusWorkflowFamily
import skillbill.ports.db.model.RetainedWorkflowDiagnostic
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.idestatus.model.IdeStatusCurrentModel
import skillbill.ports.idestatus.model.IdeStatusCurrentPhaseExecution
import skillbill.ports.idestatus.model.IdeStatusCurrentSubtask
import skillbill.ports.idestatus.model.IdeStatusExecutionIdentity
import skillbill.ports.idestatus.model.IdeStatusPauseReason
import skillbill.ports.idestatus.model.IdeStatusPauseReasonCode
import skillbill.ports.idestatus.model.IdeStatusPlanning
import skillbill.ports.idestatus.model.IdeStatusProgress
import skillbill.ports.idestatus.model.IdeStatusSnapshot
import skillbill.ports.idestatus.model.IdeStatusStep
import skillbill.ports.idestatus.model.IdeStatusWorkflowQaOutcome
import skillbill.ports.idestatus.model.StandalonePhaseStatusRecord
import skillbill.ports.idestatus.model.StandalonePlanReport
import skillbill.ports.idestatus.model.StandaloneQaOutcome
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.work.model.WorkItem
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.goalobservability.GoalProgressEventKind
import skillbill.workflow.model.goalobservability.GoalProgressOutcome
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.validation.FeatureTaskRuntimeProviderLimitPause
import java.io.IOException
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

internal data class IdeStatusProjectionContext(
  val unitOfWork: UnitOfWork,
  val repositoryIdentity: String,
  val branchCorrelation: String?,
  val observedAt: Instant,
  val repoRoot: Path,
)

internal data class IdeStatusProjection(
  val snapshot: IdeStatusSnapshot,
  val exactPauseReason: String?,
  val executingAgentId: String? = null,
  val migrationDiagnostic: RetainedWorkflowDiagnostic? = null,
)

private data class GoalPauseReasonProjection(
  val bounded: IdeStatusPauseReason?,
  val exactText: String?,
)

private data class ChildOptionalContext(
  val currentPhaseId: String? = null,
  val currentModel: IdeStatusCurrentModel?,
  val currentPhaseExecution: IdeStatusCurrentPhaseExecution?,
  val operatorDecisionPause: FeatureTaskRuntimeOperatorDecisionPause? = null,
  val criterionExplanation: String? = null,
  val migrationDiagnostic: RetainedWorkflowDiagnostic? = null,
) {
  companion object {
    val EMPTY = ChildOptionalContext(currentModel = null, currentPhaseExecution = null)
  }
}

@Inject
public class IdeStatusProjector(
  private val workflowSnapshotValidator: WorkflowSnapshotValidator,
  private val goalRunnerStatusService: GoalRunnerStatusPort,
  private val featureTaskRuntimeStatusService: FeatureTaskRuntimeStatusService,
  private val manifestStore: GoalManifestProjectionReader,
  private val diagnostics: RuntimeDiagnostics,
  private val qaCapabilities: QaCapabilityResolver,
  private val planReports: StandalonePlanReportPort,
  private val retainedProjection: RetainedWorkflowBoardProjection,
) {
  internal val standalonePlanProgressProjector =
    StandalonePlanProgressProjector(planReports, featureTaskRuntimeStatusService, diagnostics)

  private val workflowEngine = WorkflowEngine()

  internal fun goalBranchCorrelation(
    item: WorkItem,
    family: IdeStatusWorkflowFamily,
    registeredBranch: String?,
    currentBranch: String?,
    repoRoot: Path,
  ): String? {
    if (family != IdeStatusWorkflowFamily.FEATURE_GOAL || registeredBranch == null || currentBranch == null) {
      return registeredBranch
    }
    if (registeredBranch == currentBranch || registeredBranch == "HEAD") return registeredBranch
    val state = item.issueKey?.let { manifestStore.read(it, repoRoot) }
    val manifest =
      state?.manifest?.takeIf {
        state.parentWorkflowId == item.workflowId
      }
    val ownsBranch =
      manifest != null &&
        (manifest.featureBranch == currentBranch || manifest.stackBranches.any { it.branch == currentBranch })
    return if (ownsBranch) currentBranch else registeredBranch
  }

  internal fun goalSpecBundle(
    manifest: DecompositionManifest,
    repoRoot: Path,
  ): StandalonePlanReport = planReports.report(manifest, repoRoot)

  internal fun goalFinishPipeline(
    repoRoot: Path,
    assessment: GoalRunnerAssessmentResult? = null,
  ): List<String> =
    assessmentFinishSteps(assessment) +
      when (val capability = qaCapabilities.resolve(repoRoot)) {
        is QaCapabilityResolution.Absent -> GOAL_FINISH_PIPELINE.filterNot { it in PhaseSlot.QA.steps }
        is QaCapabilityResolution.Declared -> GOAL_FINISH_PIPELINE
        is QaCapabilityResolution.Incompatible -> {
          RuntimeDiagnosticsBestEffortWarning.record(diagnostics, capability.reason)
          GOAL_FINISH_PIPELINE
        }
      }

  private fun assessmentFinishSteps(assessment: GoalRunnerAssessmentResult?): List<String> =
    when {
      assessment == null -> emptyList()
      assessment.fixRan || assessment.state in IN_PROGRESS_ASSESSMENT -> GOAL_ASSESSMENT_STEPS
      else -> listOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_GOAL_ASSESSMENT)
    }

  internal fun project(
    candidate: IdeStatusCandidate,
    context: IdeStatusProjectionContext,
  ): IdeStatusProjection {
    candidate.migrationDiagnostic?.let { return migrationProjection(candidate, context, it) }
    candidate.standaloneStatus?.let { record ->
      val exactPauseReason =
        record.terminalResult?.takeIf { classifiedProviderLimitPause(it) }
          ?: resolveExecutionReason(record, context.unitOfWork).text
      return IdeStatusProjection(
        projectStandalonePhase(context, record),
        exactPauseReason,
        standaloneLaunchAgent(record, context.unitOfWork),
      )
    }
    val projection =
      when (candidate.workflowFamily) {
        IdeStatusWorkflowFamily.FEATURE_GOAL -> projectGoal(candidate, context)
        IdeStatusWorkflowFamily.FEATURE_TASK_RUNTIME ->
          goalBackedRuntimeSnapshot(candidate, context) ?: projectRuntime(candidate, context)
        IdeStatusWorkflowFamily.FEATURE_VERIFY ->
          IdeStatusProjection(projectWorkflowFamily(candidate, context, WorkflowFamily.VERIFY), null)
      }
    return projection.withWorkflowQaOutcome(context.unitOfWork)
  }

  private fun IdeStatusProjection.withWorkflowQaOutcome(unitOfWork: UnitOfWork): IdeStatusProjection {
    val workflowId = snapshot.workflowId
    if (snapshot.problem != null || workflowId == null) return this
    val recorded = unitOfWork.featureTaskQaOutcomes.find(workflowId) ?: return this
    val qaOutcome =
      IdeStatusWorkflowQaOutcome(
        outcome = recorded.outcome,
        reason = recorded.reason,
        failureKind = recorded.failureKind?.wireValue,
        recordedAt = recorded.recordedAt,
      )
    return copy(snapshot = snapshot.copy(workflowQaOutcome = qaOutcome))
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
    val lifecycle = IdeStatusPolicy.standaloneLifecycle(record.lifecycleState)
    val execution =
      IdeStatusExecutionIdentity(
        scope = IdeStatusExecutionScope.STANDALONE_PHASE,
        executionId = record.executionId,
        statusStoreId = record.statusStoreId,
        runSequence = record.runSequence,
        statusRevision = record.statusRevision,
        invocationId = record.invocationId,
        phaseId = record.phaseId,
        launchScope = record.launchScope,
        qaOutcome = record.qaOutcome,
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
      summary =
        resolveExecutionReason(record, context.unitOfWork).text
          ?: listOfNotNull(
            standalonePhaseStateSummary(record.phaseId, lifecycle, record.lifecycleState, record.qaOutcome),
            record.monitorTargetDetail(),
          ).joinToString(" "),
      title = IdeStatusTitles.standalonePhase(record, seededPlanFeatureName(record, context)),
    )
  }

  private fun seededPlanFeatureName(
    record: StandalonePhaseStatusRecord,
    context: IdeStatusProjectionContext,
  ): String? {
    val issueKey = record.issueKey?.takeIf(String::isNotBlank) ?: return null
    if (record.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN || record.planReport != null) return null
    return planReports.seededFeatureName(issueKey, context.repoRoot)
  }

  private fun goalBackedRuntimeSnapshot(
    candidate: IdeStatusCandidate,
    context: IdeStatusProjectionContext,
  ): IdeStatusProjection? {
    val issueKey = candidate.issueKey ?: return null
    val manifestState = manifestStore.read(issueKey, context.repoRoot) ?: return null
    val projection =
      goalRunnerStatusService.status(
        GoalRunnerStatusRequest(
          issueKey = issueKey,
          repoRoot = context.repoRoot,
          pruneCheckpointRefs = false,
          includeCompletedSubtaskValidation = false,
        ),
      )
    val completionRecorded = projection?.admittedParentComplete() == true
    val goalCandidate =
      candidate.copy(
        workflowId = manifestState.parentWorkflowId,
        workflowFamily = IdeStatusWorkflowFamily.FEATURE_GOAL,
        isGoalAuthoritative = true,
        lifecycleState = IdeStatusLifecycleState.ACTIVE,
      )
    val preliminaryLifecycle = goalLifecycle(goalCandidate, projection, completionRecorded)
    val liveFinalization = goalProjectsLiveFinalizationStep(projection)
    if (!goalShowsOpenCiMonitor(projection, preliminaryLifecycle) && !liveFinalization) {
      return null
    }
    return assembleGoalStatusSnapshot(goalCandidate, context, issueKey, projection, manifestState.manifest.featureName)
  }

  private fun projectGoal(
    candidate: IdeStatusCandidate,
    context: IdeStatusProjectionContext,
  ): IdeStatusProjection {
    val issueKey =
      candidate.issueKey
        ?: return IdeStatusProjection(incompatible(candidate, context, "Goal work is missing an issue key."), null)
    val projection =
      goalRunnerStatusService.status(
        GoalRunnerStatusRequest(
          issueKey = issueKey,
          repoRoot = context.repoRoot,
          pruneCheckpointRefs = false,
          includeCompletedSubtaskValidation = false,
        ),
      )
    val featureName = manifestStore.read(issueKey, context.repoRoot)?.manifest?.featureName
    return assembleGoalStatusSnapshot(candidate, context, issueKey, projection, featureName)
  }

  private fun assembleGoalStatusSnapshot(
    candidate: IdeStatusCandidate,
    context: IdeStatusProjectionContext,
    issueKey: String,
    projection: GoalRunnerStatusProjection?,
    featureName: String?,
  ): IdeStatusProjection {
    val preliminaryLifecycle = goalLifecycle(candidate, projection, projection?.admittedParentComplete() == true)
    val planning = projection?.planning?.toIdeStatusPlanning()
    val childContext = childOptionalContext(projection?.currentChildWorkflowId, preliminaryLifecycle)
    childContext.migrationDiagnostic?.let { return migrationProjection(candidate, context, it) }
    val lifecycle = goalLifecycleForOperatorBlock(preliminaryLifecycle, childContext)
    val planningStep =
      livePlanningOverlay(planning, projection?.currentStep, lifecycle, childContext.currentPhaseId)
    val openCiMonitor = goalShowsOpenCiMonitor(projection, lifecycle)
    val liveFinalization = goalProjectsLiveFinalizationStep(projection)
    val pauseReason =
      goalPauseReason(
        lifecycle,
        projection,
        childContext,
        context.unitOfWork.goalRunnerControls.controlState(candidate.workflowId).goalBlockedReason,
      )
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
    val snapshot =
      IdeStatusSnapshot(
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
        currentModel =
          if (planningStep != null || liveFinalization || openCiMonitor) {
            goalStepModel(context.unitOfWork, candidate.workflowId, step.id, planningStep != null, lifecycle)
          } else {
            childContext.currentModel
          },
        planning = planning,
        currentPhaseExecution = childContext.currentPhaseExecution.takeIf { planningStep == null },
        pauseRequested = goalPauseRequested(projection),
        pausedAt = goalPausedAt(projection, lifecycle),
        pauseReason = pauseReason.bounded,
        activeDurationMs = projection?.recordedActiveDurationMs(),
        activeDurationAsOf = projection?.liveActiveDurationAnchor(),
        lastAgentActivityAt = activityAt,
        lastAgentActivityLabel = activityLabel,
        stalledSince =
          projection?.progressStall?.lastProgressAt?.takeIf { lifecycle == IdeStatusLifecycleState.ACTIVE },
        updatedAt = candidate.updatedAt,
        freshness = IdeStatusFreshnessClassifier.classify(candidate.updatedAt, context.observedAt),
        summary =
          planningStep?.takeIf { lifecycle != IdeStatusLifecycleState.PAUSED }
            ?.let { goalPlanningSummary(issueKey, it) }
            ?: goalSnapshotSummary(issueKey, lifecycle, step.label, projection, childContext),
        title = IdeStatusTitles.goal(featureName, issueKey),
      )
    return IdeStatusProjection(snapshot, pauseReason.exactText, projection?.activeAgent?.takeIf(String::isNotBlank))
  }

  private fun goalSnapshotSummary(
    issueKey: String,
    lifecycle: IdeStatusLifecycleState,
    stepLabel: String,
    projection: GoalRunnerStatusProjection?,
    childContext: ChildOptionalContext,
  ): String =
    childContext.criterionExplanation?.let {
      "Acceptance criteria: $it. " +
        goalSummary(
          issueKey,
          lifecycle,
          stepLabel,
          projection?.blockedCount ?: 0,
          childContext.operatorDecisionPause,
        )
    } ?: goalSummary(
      issueKey,
      lifecycle,
      stepLabel,
      projection?.blockedCount ?: 0,
      childContext.operatorDecisionPause,
    )

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
    if (candidate.lifecycleState == IdeStatusLifecycleState.CANCELED) return IdeStatusLifecycleState.CANCELED
    val liveFinalization = goalProjectsLiveFinalizationStep(projection)
    val candidateLifecycle =
      if (candidate.lifecycleState == IdeStatusLifecycleState.TERMINAL &&
        (projection?.hasUnfinishedSubtasks() == true || liveFinalization)
      ) {
        IdeStatusLifecycleState.ACTIVE
      } else {
        candidate.lifecycleState
      }
    if (completionRecorded || candidateLifecycle == IdeStatusLifecycleState.TERMINAL) {
      return IdeStatusLifecycleState.TERMINAL
    }
    if (goalProjectsLiveResume(candidateLifecycle, projection)) {
      return IdeStatusLifecycleState.ACTIVE
    }
    return when {
      projection?.paused == true && classifiedProviderLimitPause(projection.pauseReason) ->
        IdeStatusLifecycleState.PAUSED
      candidateLifecycle != IdeStatusLifecycleState.ACTIVE -> candidateLifecycle
      projection?.paused == true -> IdeStatusLifecycleState.PAUSED
      projection?.executionLiveness != ExecutionLiveness.LIVE -> IdeStatusLifecycleState.IDLE
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
    goalBlockedReason: String?,
  ): GoalPauseReasonProjection {
    val durableReason = projection?.pauseReason
    val classifiedDurable = classifiedProviderLimitPause(durableReason)
    return operatorDecisionGoalPauseReason(
      lifecycle,
      childContext.operatorDecisionPause,
      classifiedDurable,
      durableReason,
    ) ?: pausedOrBlockedGoalPauseReason(
      lifecycle,
      projection,
      goalBlockedReason,
      classifiedDurable,
      durableReason,
    )
  }

  private fun childOptionalContext(
    childWorkflowId: String?,
    lifecycle: IdeStatusLifecycleState,
  ): ChildOptionalContext {
    if (lifecycle in
      setOf(
        IdeStatusLifecycleState.TERMINAL,
        IdeStatusLifecycleState.CANCELED,
      )
    ) {
      return ChildOptionalContext.EMPTY
    }
    val workflowId = childWorkflowId?.takeIf(String::isNotBlank) ?: return ChildOptionalContext.EMPTY
    val degraded =
      "IDE status omitted optional child context for workflow '$workflowId': " +
        "the child's durable status could not be read."
    val status =
      try {
        featureTaskRuntimeStatusService.readPreparedStatus(
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
      criterionExplanation = status.criterionExplanation,
      migrationDiagnostic = status.migrationDiagnostic,
    )
  }

  private fun projectRuntime(
    candidate: IdeStatusCandidate,
    context: IdeStatusProjectionContext,
  ): IdeStatusProjection {
    val status =
      featureTaskRuntimeStatusService.readPreparedStatus(
        FeatureTaskRuntimeStatusRequest(
          workflowId = candidate.workflowId,
        ),
      ) ?: return IdeStatusProjection(incompatible(candidate, context, "Runtime workflow snapshot is missing."), null)
    status.migrationDiagnostic?.let { return migrationProjection(candidate, context, it) }
    val stepId =
      status.currentPhaseId?.takeIf(String::isNotBlank)
        ?: context.unitOfWork.workflowStates.readFeatureTaskWorkflowHeader(candidate.workflowId)?.currentStepId
          ?.takeIf(String::isNotBlank) ?: "unknown"
    val stepLabel =
      WorkflowFamily.TASK_RUNTIME.definition.stepLabels[stepId]
        ?: stepId.replace('_', ' ').replaceFirstChar { it.titlecase() }
    val phaseTotal = status.phases.size.takeIf { it > 0 }
    val progress =
      phaseTotal?.let {
        IdeStatusProgress(completed = status.completeCount, total = it)
      }
    val startedAt = candidate.startedAt
    val updatedAt = candidate.updatedAt
    val (activityAt, activityLabel) = agentActivityFields(context.unitOfWork, candidate.workflowId)
    val snapshot =
      IdeStatusSnapshot(
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
        currentModel = status.phases.firstOrNull { it.phaseId == stepId }?.toIdeStatusCurrentModel(),
        currentPhaseExecution = status.currentPhaseExecution?.takeIf { it.phaseId == stepId },
        pauseReason =
          status.operatorDecisionPause?.let { pause ->
            IdeStatusPauseReason.of(IdeStatusPauseReasonCode.AWAITING_OPERATOR_DECISION, pause.reason)
          },
        lastAgentActivityAt = activityAt,
        lastAgentActivityLabel = activityLabel,
        updatedAt = updatedAt,
        freshness = IdeStatusFreshnessClassifier.classify(updatedAt, context.observedAt),
        summary =
          status.criterionExplanation?.let {
            "Acceptance criteria: $it. " +
              familySummary(
                IdeStatusWorkflowFamily.FEATURE_TASK_RUNTIME,
                candidate.issueKey,
                candidate.lifecycleState,
                stepLabel,
              )
          } ?: familySummary(
            IdeStatusWorkflowFamily.FEATURE_TASK_RUNTIME,
            candidate.issueKey,
            candidate.lifecycleState,
            stepLabel,
          ),
        title = IdeStatusTitles.workflow(WorkflowFamily.TASK_RUNTIME, candidate.issueKey, context.branchCorrelation),
      )
    val executingAgentId =
      status.phases.firstOrNull { it.status.workflowStepStatus() == WorkflowStepStatus.RUNNING }
        ?.resolvedAgentId?.takeIf(String::isRuntimeAgentId)
        ?: status.finalizingAgentId?.takeIf(String::isRuntimeAgentId)
    return IdeStatusProjection(snapshot, status.operatorDecisionPause?.reason, executingAgentId)
  }

  private fun migrationProjection(
    candidate: IdeStatusCandidate,
    context: IdeStatusProjectionContext,
    diagnostic: RetainedWorkflowDiagnostic,
  ): IdeStatusProjection {
    val item = retainedProjection.item(diagnostic, context.observedAt)
    return IdeStatusProjection(
      snapshot =
        item.snapshot.copy(
          repositoryIdentity = retainedProjection.repositoryIdentity(diagnostic) ?: item.snapshot.repositoryIdentity,
          branchCorrelation = candidate.branchCorrelation,
          workflowId = candidate.workflowId,
          issueKey = candidate.issueKey,
          workflowFamily = candidate.workflowFamily,
          execution = candidate.execution,
        ),
      exactPauseReason = diagnostic.guidance,
      migrationDiagnostic = diagnostic,
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
    val lifecycle =
      if (family == WorkflowFamily.VERIFY) {
        IdeStatusPolicy.verifyLifecycle(candidate.lifecycleState, updatedAt, context.observedAt)
      } else {
        candidate.lifecycleState
      }
    val summary =
      if (lifecycle == IdeStatusLifecycleState.IDLE && candidate.lifecycleState == IdeStatusLifecycleState.ACTIVE) {
        val subject = candidate.issueKey?.let { "${wireFamily.wireValue} $it" } ?: wireFamily.wireValue
        "$subject stopped reporting progress at $stepLabel and is treated as abandoned."
      } else {
        familySummary(wireFamily, candidate.issueKey, lifecycle, stepLabel)
      }
    return IdeStatusSnapshot(
      repositoryIdentity = context.repositoryIdentity,
      branchCorrelation = context.branchCorrelation,
      issueKey = candidate.issueKey,
      workflowId = candidate.workflowId,
      workflowFamily = wireFamily,
      execution = candidate.execution,
      lifecycleState = lifecycle,
      currentStep = IdeStatusStep(id = stepId, label = stepLabel),
      progress = progress,
      startedAt = startedAt,
      lastAgentActivityAt = activityAt,
      lastAgentActivityLabel = activityLabel,
      updatedAt = updatedAt,
      freshness = IdeStatusFreshnessClassifier.classify(updatedAt, context.observedAt),
      summary = summary,
      title = workflowTitle(candidate, context, family),
    )
  }

  private fun workflowTitle(
    candidate: IdeStatusCandidate,
    context: IdeStatusProjectionContext,
    family: WorkflowFamily,
  ): String =
    if (family == WorkflowFamily.TASK_RUNTIME) {
      candidate.issueKey
        ?.let { manifestStore.read(it, context.repoRoot) }
        ?.takeIf { it.parentWorkflowId == candidate.workflowId }
        ?.manifest?.featureName?.takeIf(String::isNotBlank)
        ?: IdeStatusTitles.workflow(family, candidate.issueKey, context.branchCorrelation)
    } else {
      IdeStatusTitles.workflow(family, candidate.issueKey, context.branchCorrelation)
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

internal val GOAL_ASSESSMENT_STEPS =
  listOf(
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_GOAL_ASSESSMENT,
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_GOAL_ASSESSMENT_FIX,
  )

private val IN_PROGRESS_ASSESSMENT = setOf(GoalRunnerAssessmentState.PENDING, GoalRunnerAssessmentState.RUNNING)

internal fun blockedGoalPauseReason(
  reason: String?,
  currentSubtaskId: Int?,
): IdeStatusPauseReason? {
  if (classifiedProviderLimitPause(reason)) return null
  return reason
    ?.takeIf { currentSubtaskId != null }
    ?.let { IdeStatusPauseReason.of(IdeStatusPauseReasonCode.AWAITING_OPERATOR_DECISION, it) }
}

private fun operatorDecisionGoalPauseReason(
  lifecycle: IdeStatusLifecycleState,
  pause: FeatureTaskRuntimeOperatorDecisionPause?,
  classifiedDurable: Boolean,
  durableReason: String?,
): GoalPauseReasonProjection? {
  if (pause == null) return null
  if (lifecycle != IdeStatusLifecycleState.PAUSED && lifecycle != IdeStatusLifecycleState.BLOCKED) return null
  if (classifiedDurable || classifiedProviderLimitPause(pause.reason)) {
    return GoalPauseReasonProjection(null, if (classifiedDurable) durableReason else pause.reason)
  }
  return GoalPauseReasonProjection(
    IdeStatusPauseReason.of(IdeStatusPauseReasonCode.AWAITING_OPERATOR_DECISION, pause.reason),
    pause.reason,
  )
}

private fun pausedOrBlockedGoalPauseReason(
  lifecycle: IdeStatusLifecycleState,
  projection: GoalRunnerStatusProjection?,
  goalBlockedReason: String?,
  classifiedDurable: Boolean,
  durableReason: String?,
): GoalPauseReasonProjection {
  if (lifecycle == IdeStatusLifecycleState.BLOCKED) {
    val reason = projection?.currentSubtaskBlockedReason ?: projection?.planning?.reason ?: goalBlockedReason
    val exact = reason?.takeIf(String::isNotBlank)
    return GoalPauseReasonProjection(blockedGoalPauseReason(exact, projection?.currentSubtaskId), exact)
  }
  if (lifecycle != IdeStatusLifecycleState.PAUSED) return GoalPauseReasonProjection(null, null)
  if (classifiedDurable) return GoalPauseReasonProjection(null, durableReason)
  val bounded =
    IdeStatusPauseReasonCode.fromWire(durableReason)
      ?.let { code -> IdeStatusPauseReason.of(code, null) }
  return GoalPauseReasonProjection(bounded, durableReason)
}

private fun classifiedProviderLimitPause(reason: String?): Boolean =
  reason != null && FeatureTaskRuntimeProviderLimitPause.isProviderLimitPauseReason(reason)

internal fun goalCurrentSubtask(
  projection: GoalRunnerStatusProjection?,
  context: IdeStatusProjectionContext,
): IdeStatusCurrentSubtask? =
  projection?.currentSubtaskId?.takeIf { it > 0 }?.let { subtaskId ->
    IdeStatusCurrentSubtask(
      id = subtaskId.toString(),
      startedAt =
        projection.currentChildWorkflowId?.takeIf(String::isNotBlank)?.let { workflowId ->
          parseInstantOrNull(
            context.unitOfWork.workflowStates.getFeatureTaskWorkflow(workflowId)?.startedAt,
          )
        },
      activeDurationMs = projection.recordedSubtaskActiveDurationMs(),
      activeDurationAsOf = projection.liveSubtaskActiveDurationAnchor(),
    )
  }

internal fun livePlanningOverlay(
  planning: IdeStatusPlanning?,
  projectedStep: String?,
  lifecycle: IdeStatusLifecycleState,
  childPhaseId: String?,
): IdeStatusPlanning? =
  planning?.takeIf {
    it.state != GoalPlanningStatusState.PREPARED &&
      !lifecycle.isSettled() &&
      childPhaseId == null &&
      projectedStep.isLivePlanningStep()
  }

internal fun String?.isLivePlanningStep(): Boolean = isNullOrBlank() || this in LIVE_PLANNING_STEP_IDS

internal fun goalStep(
  planningStep: IdeStatusPlanning?,
  projectedStep: String?,
  lifecycle: IdeStatusLifecycleState,
): IdeStatusStep {
  if (planningStep != null) return IdeStatusStep(id = "planning", label = "Planning")
  val projected = projectedStep?.takeIf(String::isNotBlank)
  if (projected != null) return IdeStatusStep(id = projected, label = projected)
  return if (lifecycle == IdeStatusLifecycleState.TERMINAL) {
    IdeStatusStep(id = "done", label = "Complete")
  } else {
    IdeStatusStep(id = "goal", label = "Goal")
  }
}

internal fun goalStepModel(
  unitOfWork: UnitOfWork,
  workflowId: String,
  stepId: String,
  planning: Boolean,
  lifecycle: IdeStatusLifecycleState,
): IdeStatusCurrentModel? {
  if (lifecycle in setOf(IdeStatusLifecycleState.TERMINAL, IdeStatusLifecycleState.CANCELED)) return null
  val event =
    unitOfWork.workflowStates.readGoalStatusSnapshot(workflowId)
      ?.let { declaredProgressEventFrom(it.artifacts) } ?: return null
  val active =
    event.workflowId == workflowId && event.eventKind != GoalProgressEventKind.PHASE_COMPLETED &&
      event.outcome == GoalProgressOutcome.NONE
  val matchesStep =
    if (planning) {
      event.operationKind == GOAL_PLANNING_OPERATION_KIND
    } else {
      (event.stepId ?: event.workflowPhase) == stepId
    }
  return event.takeIf { active && matchesStep }?.launchedModel?.takeIf(String::isNotBlank)?.let {
    IdeStatusCurrentModel(it, event.effort?.takeIf(String::isNotBlank), event.stepId ?: event.workflowPhase)
  }
}

internal fun FeatureTaskRuntimePhaseStatus.toIdeStatusCurrentModel(): IdeStatusCurrentModel? {
  if (status.workflowStepStatus() == WorkflowStepStatus.COMPLETED) return null
  return launchedModel?.takeIf(String::isNotBlank)?.let { model ->
    IdeStatusCurrentModel(
      model = model,
      effort = launchedEffort?.takeIf(String::isNotBlank),
      phaseId = phaseId.takeIf(String::isNotBlank),
    )
  }
}

internal fun GoalPlanningStatusSnapshot.toIdeStatusPlanning(): IdeStatusPlanning =
  IdeStatusPlanning(
    state = state,
    sharedPreplanPrepared = sharedPreplanPrepared,
    plannedSubtaskCount = plannedSubtaskCount,
    totalSubtaskCount = totalSubtaskCount,
    currentPlanningSubtaskId = currentPlanningSubtaskId?.toString(),
    planningWaveSubtaskIds = planningWaveSubtaskIds.map(Int::toString),
    reason = reason,
  )

internal fun IdeStatusLifecycleState.isSettled(): Boolean =
  this == IdeStatusLifecycleState.BLOCKED ||
    this == IdeStatusLifecycleState.FAILED ||
    this == IdeStatusLifecycleState.TERMINAL

internal fun goalPlanningSummary(
  issueKey: String,
  planning: IdeStatusPlanning,
): String {
  val concurrent = planning.planningWaveSubtaskIds.size
  val wave =
    when (concurrent) {
      0 -> ""
      1 -> " 1 subtask is being planned now."
      else -> " $concurrent subtasks are being planned now."
    }
  return "Goal $issueKey is planning subtasks " +
    "(${planning.plannedSubtaskCount}/${planning.totalSubtaskCount} planned).$wave"
}

internal fun GoalRunnerStatusProjection.hasUnfinishedSubtasks(): Boolean = pendingCount > 0 || blockedCount > 0

internal fun goalStaysOnOpenCiMonitor(projection: GoalRunnerStatusProjection?): Boolean {
  if (projection == null || projection.paused) return false
  if (projection.admittedParentComplete()) return false
  if (projection.pendingCount > 0 || projection.blockedCount > 0) return false
  return projection.completeCount > 0
}

internal fun goalShowsOpenCiMonitor(
  projection: GoalRunnerStatusProjection?,
  lifecycle: IdeStatusLifecycleState,
): Boolean =
  goalStaysOnOpenCiMonitor(projection) &&
    projection?.executionLiveness == ExecutionLiveness.LIVE &&
    projection.currentStep in setOf(OPEN_CI_MONITOR_STEP, "monitor_fix") &&
    lifecycle == IdeStatusLifecycleState.ACTIVE

internal fun goalProjectsLiveResume(
  lifecycle: IdeStatusLifecycleState,
  projection: GoalRunnerStatusProjection?,
): Boolean {
  if (projection == null || projection.executionLiveness != ExecutionLiveness.LIVE || projection.paused) return false
  return when (lifecycle) {
    IdeStatusLifecycleState.PAUSED -> true
    IdeStatusLifecycleState.BLOCKED ->
      goalProjectsLiveFinalizationStep(projection) ||
        (
          projection.planning?.state?.let { it != GoalPlanningStatusState.PREPARED } == true &&
            projection.currentStep in ACTIVE_PLANNING_PHASE_IDS
        )
    else -> false
  }
}

internal fun goalProjectsLiveFinalizationStep(projection: GoalRunnerStatusProjection?): Boolean {
  if (projection == null || projection.executionLiveness != ExecutionLiveness.LIVE) return false
  if (!goalStaysOnOpenCiMonitor(projection)) return false
  val step = projection.currentStep?.takeIf(String::isNotBlank) ?: return false
  return step in GOAL_FINISH_PIPELINE || step in GOAL_ASSESSMENT_STEPS
}

internal data class GoalCurrentStepSignals(
  val openCiMonitor: Boolean,
  val liveFinalizationStep: Boolean,
)

internal fun goalCurrentStep(
  planningStep: IdeStatusPlanning?,
  childPhaseId: String?,
  projectedStep: String?,
  lifecycle: IdeStatusLifecycleState,
  signals: GoalCurrentStepSignals,
): IdeStatusStep {
  val terminal = lifecycle == IdeStatusLifecycleState.TERMINAL
  val childPhaseStep =
    childPhaseId
      ?.takeIf { it.isNotBlank() && planningStep == null && !terminal }
      ?.takeUnless { signals.openCiMonitor || signals.liveFinalizationStep }
  return goalStep(
    planningStep,
    childPhaseStep
      ?: projectedStep?.takeUnless { terminal }
      ?: OPEN_CI_MONITOR_STEP.takeIf { signals.openCiMonitor },
    lifecycle,
  )
}

internal fun GoalRunnerStatusProjection.toIdeStatusProgress(): IdeStatusProgress? {
  val total = completeCount + pendingCount + blockedCount
  if (total <= 0) return null
  return IdeStatusProgress(completed = completeCount, total = total)
}

internal const val OPEN_CI_MONITOR_STEP: String = "monitor"

private val ACTIVE_PLANNING_PHASE_IDS =
  setOf(
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_INTAKE,
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN,
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_SUBTASKS,
  )

internal val LIVE_PLANNING_STEP_IDS: Set<String> =
  ACTIVE_PLANNING_PHASE_IDS + setOf("planning", "pending_launch", "initializing")

internal fun goalSummary(
  issueKey: String,
  lifecycle: IdeStatusLifecycleState,
  stepLabel: String,
  blockedCount: Int,
  operatorDecisionPause: FeatureTaskRuntimeOperatorDecisionPause? = null,
): String =
  when (lifecycle) {
    IdeStatusLifecycleState.BLOCKED -> {
      operatorDecisionPause?.reason?.takeIf(String::isNotBlank)?.let { reason ->
        "Goal $issueKey is blocked: $reason"
      } ?: run {
        val subtasks = if (blockedCount == 1) "subtask" else "subtasks"
        "Goal $issueKey is blocked" + if (blockedCount > 0) " ($blockedCount $subtasks)." else "."
      }
    }
    IdeStatusLifecycleState.PAUSED -> "Goal $issueKey is paused."
    IdeStatusLifecycleState.FAILED -> "Goal $issueKey failed."
    IdeStatusLifecycleState.CANCELED -> "Goal $issueKey was canceled."
    IdeStatusLifecycleState.TERMINAL -> "Goal $issueKey is complete."
    IdeStatusLifecycleState.ACTIVE -> "Goal $issueKey is active on $stepLabel."
    IdeStatusLifecycleState.IDLE -> "Goal $issueKey is idle."
  }

internal fun standalonePhaseStateSummary(
  phaseId: String,
  lifecycle: IdeStatusLifecycleState,
  recordedState: String,
  qaOutcome: StandaloneQaOutcome?,
): String =
  when {
    lifecycle != IdeStatusLifecycleState.TERMINAL -> "Standalone phase $phaseId is $recordedState."
    qaOutcome == StandaloneQaOutcome.ATTEMPTED ->
      "Standalone phase $phaseId finished with verdict ${StandaloneQaOutcome.INCOMPLETE.wireValue}."
    qaOutcome != null -> "Standalone phase $phaseId finished with verdict ${qaOutcome.wireValue}."
    else -> "Standalone phase $phaseId is complete."
  }

internal fun familySummary(
  family: IdeStatusWorkflowFamily,
  issueKey: String?,
  lifecycle: IdeStatusLifecycleState,
  stepLabel: String,
): String {
  val subject = issueKey?.let { "${family.wireValue} $it" } ?: family.wireValue
  return when (lifecycle) {
    IdeStatusLifecycleState.ACTIVE -> "$subject is active on $stepLabel."
    IdeStatusLifecycleState.PAUSED -> "$subject is paused at $stepLabel."
    IdeStatusLifecycleState.BLOCKED -> "$subject is blocked at $stepLabel."
    IdeStatusLifecycleState.FAILED -> "$subject failed at $stepLabel."
    IdeStatusLifecycleState.CANCELED -> "$subject was canceled."
    IdeStatusLifecycleState.TERMINAL -> "$subject is complete."
    IdeStatusLifecycleState.IDLE -> "No matching Skill Bill work for this repository."
  }
}

internal fun parseInstantOrNull(value: String?): Instant? {
  if (value.isNullOrBlank()) return null
  return try {
    Instant.parse(value)
  } catch (_: DateTimeParseException) {
    try {
      OffsetDateTime.parse(value).toInstant()
    } catch (_: DateTimeParseException) {
      null
    } ?: try {
      LocalDateTime.parse(value.trim(), SQLITE_TIMESTAMP_FORMATTER).toInstant(ZoneOffset.UTC)
    } catch (_: DateTimeParseException) {
      null
    }
  }
}

internal val SQLITE_TIMESTAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

internal object IdeStatusTitles {
  fun goal(
    featureName: String?,
    issueKey: String,
  ): String = featureName.nonBlank() ?: issueKey

  fun standalonePhase(
    record: StandalonePhaseStatusRecord,
    seededFeatureName: String? = null,
  ): String =
    record.planReport?.featureName.nonBlank()
      ?: seededFeatureName.nonBlank()
      ?: nameWithTarget(
        displayName(record.phaseId),
        record.issueKey.nonBlank()?.let { "for $it" }
          ?: record.monitorTarget?.let { target ->
            target.branchRef?.let { "on branch $it" } ?: "on PR #${target.pullRequestKey.substringAfterLast('#')}"
          }
          ?: branchTarget(record.branchCorrelation),
      )

  fun workflow(
    family: WorkflowFamily,
    issueKey: String?,
    branch: String?,
  ): String {
    val name =
      when (family) {
        WorkflowFamily.VERIFY -> "Verify"
        WorkflowFamily.TASK_RUNTIME -> "Feature task"
      }
    return nameWithTarget(name, issueKey.nonBlank()?.let { "for $it" } ?: branchTarget(branch))
  }

  private fun displayName(phaseId: String): String =
    PHASE_NAMES[phaseId] ?: phaseId.replace('-', ' ').replace('_', ' ').replaceFirstChar(Char::uppercaseChar)

  private fun nameWithTarget(
    name: String,
    target: String?,
  ): String = target?.let { "$name $it" } ?: name

  private fun branchTarget(branch: String?): String? =
    branch.nonBlank()?.takeUnless { it == DETACHED_HEAD }?.let { "on branch $it" }

  private fun String?.nonBlank(): String? = this?.trim()?.takeIf(String::isNotEmpty)

  private const val DETACHED_HEAD = "HEAD"

  private val PHASE_NAMES =
    mapOf(
      "review" to "Code review",
      "validation" to "Validation",
      PhaseSlot.QA.wireValue to "QA",
      PhaseSlot.PLAN.wireValue to "Plan",
      "pr" to "Pull request",
      PhaseSlot.MONITOR.wireValue to "CI monitor",
      "standalone-monitor" to "CI monitor",
    )
}
